package com.easyflow.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.util.LruCache;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

  // ---------- Custom ring that shows export progress ----------
  static class RingView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float progress = 0f;
    private boolean spinning = true;
    private float spin = 0f;
    private final float pad;

    RingView(Context c) {
      super(c);
      float d = c.getResources().getDisplayMetrics().density;
      float stroke = 14f * d;
      pad = stroke;
      track.setStyle(Paint.Style.STROKE);
      track.setStrokeWidth(stroke);
      track.setColor(Color.parseColor("#2A2A3A"));
      arc.setStyle(Paint.Style.STROKE);
      arc.setStrokeWidth(stroke);
      arc.setStrokeCap(Paint.Cap.ROUND);
      arc.setColor(Color.parseColor("#7C6CFF"));
    }

    void setProgress(float p, boolean known) {
      progress = p;
      spinning = !known;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
      float s = Math.min(getWidth(), getHeight());
      box.set(pad, pad, s - pad, s - pad);
      c.drawOval(box, track);
      if (spinning) {
        spin = (spin + 8f) % 360f;
        c.drawArc(box, spin, 90f, false, arc);
        invalidate();
      } else {
        c.drawArc(box, -90f, 360f * progress, false, arc);
      }
    }
  }

  // ---------- A clip in the project ----------
  // A video clip is a piece of a source video (srcStart to srcEnd, in ms).
  // An image clip lasts srcEnd - srcStart ms.
  static class Clip {
    final Uri uri;
    final long srcStart;
    final long srcEnd;
    final boolean isImage;
    final float speed;
    final float volume;
    final float pitch;

    Clip(Uri uri, long srcStart, long srcEnd, boolean isImage,
         float speed, float volume, float pitch) {
      this.uri = uri;
      this.srcStart = srcStart;
      this.srcEnd = srcEnd;
      this.isImage = isImage;
      this.speed = speed;
      this.volume = volume;
      this.pitch = pitch;
    }

    long durationMs() {
      return srcEnd - srcStart;
    }

    Clip withRange(long s, long e) {
      return new Clip(uri, s, e, isImage, speed, volume, pitch);
    }

    MediaItem item() {
      MediaItem.Builder b = new MediaItem.Builder().setUri(uri);
      if (!isImage) {
        b.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(srcStart)
            .setEndPositionMs(srcEnd)
            .build());
      }
      return b.build();
    }
  }

  // ---------- One speed for a whole clip ----------
  static class ConstantSpeed implements SpeedProvider {
    private final float speed;

    ConstantSpeed(float speed) {
      this.speed = speed;
    }

    @Override
    public float getSpeed(long timeUs) {
      return speed;
    }

    @Override
    public long getNextSpeedChangeTimeUs(long timeUs) {
      return C.TIME_UNSET;
    }
  }

  // ---------- The timeline strip ----------
  static class TimelineView extends View {
    interface Listener {
      void onSelect(int index);
    }

    private final Paint bg = new Paint();
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float d;
    private final List<Clip> data = new ArrayList<>();
    private int selected = -1;
    private Listener listener;

    TimelineView(Context c) {
      super(c);
      d = c.getResources().getDisplayMetrics().density;
      bg.setColor(Color.parseColor("#141230"));
      tick.setColor(Color.parseColor("#9E9E9E"));
      tick.setStrokeWidth(1f * d);
      outline.setStyle(Paint.Style.STROKE);
      outline.setStrokeWidth(2f * d);
      outline.setColor(Color.WHITE);
      label.setColor(Color.WHITE);
      label.setTextSize(10f * d);
      label.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setListener(Listener l) {
      listener = l;
    }

    void setData(List<Clip> clips, int sel) {
      data.clear();
      data.addAll(clips);
      selected = sel;
      requestLayout();
      invalidate();
    }

    private float widthOf(Clip c) {
      float sec = (c.durationMs() / c.speed) / 1000f;
      return Math.max(56f * d, sec * 40f * d);
    }

    @Override
    protected void onMeasure(int w, int h) {
      float total = 24f * d;
      for (Clip c : data) total += widthOf(c) + 3f * d;
      int minW = getResources().getDisplayMetrics().widthPixels;
      setMeasuredDimension((int) Math.max(total, minW), (int) (56f * d));
    }

    private String num(float v) {
      return v == (int) v ? String.valueOf((int) v) : String.valueOf(v);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      canvas.drawRect(0, 0, getWidth(), getHeight(), bg);
      for (float tx = 8f * d; tx < getWidth(); tx += 40f * d) {
        canvas.drawLine(tx, 0, tx, 7f * d, tick);
      }
      float left = 8f * d;
      for (int i = 0; i < data.size(); i++) {
        Clip c = data.get(i);
        float w = widthOf(c);
        rect.set(left, 12f * d, left + w, getHeight() - 4f * d);
        blockPaint.setShader(new android.graphics.LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.parseColor("#673AB7"), Color.parseColor("#9575CD"),
            android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, 6f * d, 6f * d, blockPaint);
        if (i == selected) canvas.drawRoundRect(rect, 6f * d, 6f * d, outline);
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawText((c.isImage ? "Image " : "Clip ") + (i + 1),
            rect.left + 6f * d, rect.top + 14f * d, label);
        String info;
        if (c.isImage) {
          info = String.format(java.util.Locale.US, "%.1fs", c.durationMs() / 1000f);
        } else {
          info = String.format(java.util.Locale.US, "%.1fs", (c.durationMs() / c.speed) / 1000f)
              + "  " + num(c.speed) + "x  " + Math.round(c.volume * 100) + "%";
        }
        canvas.drawText(info, rect.left + 6f * d, rect.top + 28f * d, label);
        canvas.restore();
        left += w + 3f * d;
      }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
      if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) return true;
      if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
        float left = 8f * d;
        for (int i = 0; i < data.size(); i++) {
          float w = widthOf(data.get(i));
          if (e.getX() >= left && e.getX() <= left + w) {
            if (listener != null) listener.onSelect(i);
            return true;
          }
          left += w + 3f * d;
        }
        if (listener != null) listener.onSelect(-1);
        return true;
      }
      return super.onTouchEvent(e);
    }
  }

  // ---------- Options ----------
  private static final int PICK = 1;
  private static final long IMAGE_MS = 3000;
  private static final String[] QUALITY_LABELS = {
      "Original size", "180p", "240p", "360p", "480p",
      "720p (HD)", "1080p (Full HD)", "1440p (2K)", "2160p (4K)", "4320p (8K)"};
  private static final int[] QUALITY_HEIGHTS = {0, 180, 240, 360, 480, 720, 1080, 1440, 2160, 4320};
  private static final String[] FPS_LABELS = {
      "Original fps", "24 fps", "30 fps", "60 fps", "120 fps", "240 fps"};
  private static final float[] FPS_VALUES = {0f, 24f, 30f, 60f, 120f, 240f};
  private static final String[] BITRATE_LABELS = {
      "Auto bitrate", "1 Mbps", "2 Mbps", "4 Mbps", "8 Mbps", "16 Mbps", "30 Mbps", "50 Mbps"};
  private static final int[] BITRATES = {
      0, 1000000, 2000000, 4000000, 8000000, 16000000, 30000000, 50000000};
  private static final String[] SPEED_LABELS = {
      "0.1x", "0.25x", "0.5x", "0.75x", "1x speed", "1.5x", "2x", "3x", "4x", "5x", "6x", "8x", "10x"};
  private static final float[] SPEED_VALUES = {
      0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f};
  private static final String[] VOLUME_LABELS = {
      "Muted", "25% vol", "50% vol", "100% vol", "150% vol", "200% vol"};
  private static final float[] VOLUME_VALUES = {0f, 0.25f, 0.5f, 1f, 1.5f, 2f};
  // -1 means "pitch follows speed"
    private static final String[] PITCH_LABELS = {"Pitch natural", "Pitch follows speed"};
  private static final float[] PITCH_VALUES = {1f, -1f};

  // ---------- State ----------
  private ExoPlayer player;
  private PlayerView playerView;
  private ImageView imageView;
  private SeekBar seekBar;
  private TextView timeText, status, limits;
  private Button playBtn;
  private Spinner quality, fpsSpinner, bitrateSpinner, speedSpinner, volumeSpinner, pitchSpinner;
  private Uri videoUri;       // the file shown in the preview
  private Uri loadedUri;      // the video currently loaded in the player
  private boolean previewIsImage = false;
  private boolean seeking = false;
  private long startMs = 0;
  private long endMs = 0;
  private final List<Clip> clips = new ArrayList<>();
  private int maxBitrate = 0;
  private String limitsText = "";
  private String adjustNote = "";

  private TimelineView timeline;
  private TextView timelineInfo;
  private int selectedClip = -1;

  private Transformer transformer;
  private File currentOut;
  private boolean exporting = false;
  private boolean overlayFinished = false;
  private long exportStart;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ProgressHolder holder = new ProgressHolder();

  private LinearLayout overlay;
  private RingView ring;
  private TextView ovTitle, ovPercent, ovDetail, ovTime;
  private Button ovButton;

  private final Runnable poll = new Runnable() {
    @Override
    public void run() {
      if (!exporting || transformer == null) return;
      int st = transformer.getProgress(holder);
      if (st == Transformer.PROGRESS_STATE_AVAILABLE) {
        ring.setProgress(holder.progress / 100f, true);
        ovPercent.setText(holder.progress + "%");
      } else {
        ring.setProgress(0f, false);
        ovPercent.setText("...");
      }
      long s = (System.currentTimeMillis() - exportStart) / 1000;
      ovTime.setText("Time: " + s + "s");
      handler.postDelayed(this, 300);
    }
  };

  // Keeps the seek bar and time readout up to date
  private final Runnable ticker = new Runnable() {
    @Override
    public void run() {
      if (player != null && !previewIsImage) {
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (dur > 0) {
          if (!seeking) seekBar.setProgress((int) (pos * 1000 / dur));
          timeText.setText(fmt(pos) + " / " + fmt(dur));
        }
        playBtn.setText(player.isPlaying() ? "Pause" : "Play");
      } else {
        playBtn.setText("Play");
      }
      handler.postDelayed(this, 100);
    }
  };

  private String fmt(long ms) {
    return String.format(java.util.Locale.US, "%.2fs", ms / 1000.0);
  }

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    readLimits();

    FrameLayout rootFrame = new FrameLayout(this);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    Button pick = new Button(this);
    pick.setText("Pick a video or image");
    root.addView(pick, wrap());

    // Preview area: the video player, with an image view on top for pictures
    FrameLayout previewFrame = new FrameLayout(this);
    playerView = new PlayerView(this);
    playerView.setUseController(false);
    previewFrame.addView(playerView, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    imageView = new ImageView(this);
    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
    imageView.setBackgroundColor(Color.BLACK);
    imageView.setVisibility(View.GONE);
    previewFrame.addView(imageView, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(previewFrame, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    // Time readout and seek bar
    timeText = new TextView(this);
    timeText.setText("0.00s / 0.00s");
    timeText.setTextSize(12);
    timeText.setPadding(24, 4, 24, 0);
    root.addView(timeText, wrap());

    seekBar = new SeekBar(this);
    seekBar.setMax(1000);
    root.addView(seekBar, wrap());
    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
        if (fromUser && player != null && !previewIsImage) {
          long dur = player.getDuration();
          if (dur > 0) player.seekTo(dur * p / 1000);
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar s) {
        seeking = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar s) {
        seeking = false;
      }
    });

    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    Button setStart = new Button(this);
    setStart.setText("Set start");
    playBtn = new Button(this);
    playBtn.setText("Play");
    Button setEnd = new Button(this);
    setEnd.setText("Set end");
    row.addView(setStart, half());
    row.addView(playBtn, half());
    row.addView(setEnd, half());
    root.addView(row, wrap());

    status = new TextView(this);
    status.setText("Pick a video or image first");
    status.setPadding(24, 8, 24, 8);
    root.addView(status, wrap());

    // Timeline
    timelineInfo = new TextView(this);
    timelineInfo.setTextSize(12);
    timelineInfo.setPadding(24, 0, 24, 4);
    root.addView(timelineInfo, wrap());

    timeline = new TimelineView(this);
    timeline.setListener(index -> {
      selectedClip = index;
      refreshTimeline();
      if (index >= 0 && index < clips.size()) showClip(clips.get(index));
    });
    android.widget.HorizontalScrollView tlScroll = new android.widget.HorizontalScrollView(this);
    tlScroll.setHorizontalScrollBarEnabled(false);
    tlScroll.addView(timeline);
    root.addView(tlScroll, wrap());

    LinearLayout tlRow = new LinearLayout(this);
    tlRow.setOrientation(LinearLayout.HORIZONTAL);
    Button moveLeft = new Button(this);
    moveLeft.setText("Left");
    Button moveRight = new Button(this);
    moveRight.setText("Right");
    Button splitBtn = new Button(this);
    splitBtn.setText("Split");
    Button deleteClip = new Button(this);
    deleteClip.setText("Delete");
    tlRow.addView(moveLeft, half());
    tlRow.addView(moveRight, half());
    tlRow.addView(splitBtn, half());
    tlRow.addView(deleteClip, half());
    root.addView(tlRow, wrap());

    // Speed, volume and pitch
    android.widget.AdapterView.OnItemSelectedListener previewListener =
        new android.widget.AdapterView.OnItemSelectedListener() {
          @Override
          public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                     int position, long id) {
            applyPreviewParams();
          }

          @Override
          public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        };

    speedSpinner = makeSpinner(SPEED_LABELS);
    speedSpinner.setSelection(4); // 1x speed
    volumeSpinner = makeSpinner(VOLUME_LABELS);
    volumeSpinner.setSelection(3); // 100%
    pitchSpinner = makeSpinner(PITCH_LABELS);
    speedSpinner.setOnItemSelectedListener(previewListener);
    volumeSpinner.setOnItemSelectedListener(previewListener);
    pitchSpinner.setOnItemSelectedListener(previewListener);

    LinearLayout rowSV = new LinearLayout(this);
    rowSV.setOrientation(LinearLayout.HORIZONTAL);
    rowSV.addView(speedSpinner, half());
    rowSV.addView(volumeSpinner, half());
    rowSV.addView(pitchSpinner, half());
    root.addView(rowSV, wrap());

    LinearLayout row2 = new LinearLayout(this);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    Button addClip = new Button(this);
    addClip.setText("Add clip");
    Button clear = new Button(this);
    clear.setText("Clear all");
    row2.addView(addClip, half());
    row2.addView(clear, half());
    root.addView(row2, wrap());

    LinearLayout row3 = new LinearLayout(this);
    row3.setOrientation(LinearLayout.HORIZONTAL);
    quality = makeSpinner(QUALITY_LABELS);
    fpsSpinner = makeSpinner(FPS_LABELS);
    row3.addView(quality, half());
    row3.addView(fpsSpinner, half());
    root.addView(row3, wrap());

    bitrateSpinner = makeSpinner(BITRATE_LABELS);
    root.addView(bitrateSpinner, wrap());

    limits = new TextView(this);
    limits.setText(limitsText);
    limits.setTextSize(12);
    limits.setPadding(24, 0, 24, 8);
    root.addView(limits, wrap());

    Button export = new Button(this);
    export.setText("Export");
    root.addView(export, wrap());

    rootFrame.addView(root, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    buildOverlay(rootFrame);
    setContentView(rootFrame);

    pick.setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"video/*", "image/*"});
      startActivityForResult(i, PICK);
    });

    playBtn.setOnClickListener(v -> {
      if (player == null || previewIsImage) return;
      if (player.isPlaying()) {
        player.pause();
      } else {
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        player.play();
      }
    });

    setStart.setOnClickListener(v -> {
      if (player == null || previewIsImage) return;
      startMs = player.getCurrentPosition();
      if (endMs <= startMs) endMs = player.getDuration();
      showTimes();
    });

    setEnd.setOnClickListener(v -> {
      if (player == null || previewIsImage) return;
      endMs = player.getCurrentPosition();
      showTimes();
    });

    addClip.setOnClickListener(v -> addCurrentClip());

    clear.setOnClickListener(v -> {
      clips.clear();
      selectedClip = -1;
      refreshTimeline();
      status.setText("Cleared. Clips in project: 0");
    });

    moveLeft.setOnClickListener(v -> {
      if (selectedClip > 0) {
        java.util.Collections.swap(clips, selectedClip, selectedClip - 1);
        selectedClip--;
        refreshTimeline();
      }
    });
    moveRight.setOnClickListener(v -> {
      if (selectedClip >= 0 && selectedClip < clips.size() - 1) {
        java.util.Collections.swap(clips, selectedClip, selectedClip + 1);
        selectedClip++;
        refreshTimeline();
      }
    });
    deleteClip.setOnClickListener(v -> {
      if (selectedClip >= 0 && selectedClip < clips.size()) {
        clips.remove(selectedClip);
        selectedClip = -1;
        refreshTimeline();
      }
    });
    splitBtn.setOnClickListener(v -> splitSelected());

    export.setOnClickListener(v -> exportProject());

    refreshTimeline();
    handler.post(ticker);
  }

  // ---------- Phone encoder limits ----------
  private void readLimits() {
    try {
      MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
      for (MediaCodecInfo info : list.getCodecInfos()) {
        if (!info.isEncoder()) continue;
        String name = info.getName();
        if (name.startsWith("OMX.google") || name.startsWith("c2.android")) continue;
        for (String t : info.getSupportedTypes()) {
          if (!t.equals("video/avc")) continue;
          MediaCodecInfo.VideoCapabilities vc =
              info.getCapabilitiesForType(t).getVideoCapabilities();
          maxBitrate = vc.getBitrateRange().getUpper();
          limitsText = "Phone encoder (H.264): up to "
              + vc.getSupportedWidths().getUpper() + "x" + vc.getSupportedHeights().getUpper()
              + ", max " + (maxBitrate / 1000000) + " Mbps";
          return;
        }
      }
      limitsText = "No hardware H.264 encoder found on this phone";
    } catch (Exception e) {
      limitsText = "Could not read the phone's encoder limits";
    }
  }

  // ---------- UI helpers ----------
  private Spinner makeSpinner(String[] labels) {
    Spinner s = new Spinner(this);
    s.setAdapter(new ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item, labels));
    return s;
  }

  private LinearLayout.LayoutParams wrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private LinearLayout.LayoutParams half() {
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
  }

  private void buildOverlay(FrameLayout parent) {
    float d = getResources().getDisplayMetrics().density;

    overlay = new LinearLayout(this);
    overlay.setOrientation(LinearLayout.VERTICAL);
    overlay.setGravity(Gravity.CENTER);
    overlay.setBackgroundColor(Color.parseColor("#F2101018"));
    overlay.setClickable(true);
    overlay.setVisibility(View.GONE);
    overlay.setPadding((int) (24 * d), (int) (24 * d), (int) (24 * d), (int) (24 * d));

    ovTitle = new TextView(this);
    ovTitle.setTextColor(Color.WHITE);
    ovTitle.setTextSize(26);
    ovTitle.setTypeface(Typeface.DEFAULT_BOLD);
    ovTitle.setGravity(Gravity.CENTER);
    overlay.addView(ovTitle, wrap());

    FrameLayout ringBox = new FrameLayout(this);
    int size = (int) (220 * d);
    ring = new RingView(this);
    ringBox.addView(ring, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
    ovPercent = new TextView(this);
    ovPercent.setTextColor(Color.WHITE);
    ovPercent.setTextSize(34);
    ovPercent.setTypeface(Typeface.DEFAULT_BOLD);
    ringBox.addView(ovPercent, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rb.topMargin = (int) (28 * d);
    rb.bottomMargin = (int) (28 * d);
    overlay.addView(ringBox, rb);

    ovDetail = new TextView(this);
    ovDetail.setTextColor(Color.parseColor("#C8C8D8"));
    ovDetail.setTextSize(16);
    ovDetail.setGravity(Gravity.CENTER);
    overlay.addView(ovDetail, wrap());

    ovTime = new TextView(this);
    ovTime.setTextColor(Color.parseColor("#8888A0"));
    ovTime.setTextSize(14);
    ovTime.setGravity(Gravity.CENTER);
    ovTime.setPadding(0, (int) (8 * d), 0, (int) (24 * d));
    overlay.addView(ovTime, wrap());

    ovButton = new Button(this);
    overlay.addView(ovButton, wrap());
    ovButton.setOnClickListener(v -> onOverlayButton());

    parent.addView(overlay, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  private void showOverlayExporting(String summary) {
    overlayFinished = false;
    ovTitle.setText("Exporting");
    ovPercent.setText("...");
    ovDetail.setText(summary + "\nKeep the app open until it finishes.");
    ovTime.setText("Time: 0s");
    ovButton.setText("Cancel");
    ring.setProgress(0f, false);
    overlay.setVisibility(View.VISIBLE);
  }

  private void showOverlayDone(String size, String actual) {
    exporting = false;
    overlayFinished = true;
    ring.setProgress(1f, true);
    ovTitle.setText("Done!");
    ovPercent.setText("100%");
    String text = "Saved to Movies/EasyFlow\nSize: " + size;
    if (!actual.isEmpty()) text += "\n" + actual;
    if (!adjustNote.isEmpty()) text += "\n\nYour phone needed changes:\n" + adjustNote;
    ovDetail.setText(text);
    ovButton.setText("Close");
  }

  private void showOverlayError(String message) {
    exporting = false;
    overlayFinished = true;
    ring.setProgress(0f, true);
    ovTitle.setText("Export failed");
    ovPercent.setText("!");
    ovDetail.setText(message);
    ovButton.setText("Close");
  }

  private void onOverlayButton() {
    if (!overlayFinished) {
      exporting = false;
      if (transformer != null) transformer.cancel();
      if (currentOut != null) currentOut.delete();
      status.setText("Export cancelled.");
    }
    overlay.setVisibility(View.GONE);
    restorePreview();
  }

  private void showTimes() {
    status.setText("Start: " + (startMs / 1000.0) + "s   End: " + (endMs / 1000.0)
        + "s   Clips in project: " + clips.size());
  }

  private void refreshTimeline() {
    timeline.setData(clips, selectedClip);
    long total = 0;
    for (Clip c : clips) total += (long) (c.durationMs() / c.speed);
    timelineInfo.setText("Timeline: " + clips.size() + " clip(s), "
        + String.format(java.util.Locale.US, "%.1f", total / 1000.0) + "s total after speed"
        + (selectedClip >= 0 ? "   |   Clip " + (selectedClip + 1) + " selected" : ""));
  }

  // ---------- Preview ----------
  private boolean isImageUri(Uri uri) {
    String type = getContentResolver().getType(uri);
    return type != null && type.startsWith("image/");
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
      videoUri = data.getData();
      startMs = 0;
      endMs = 0;
      if (isImageUri(videoUri)) {
        showImage(videoUri);
        status.setText("Image loaded. Tap Add clip (images last 3 seconds). Clips: " + clips.size());
      } else {
        loadVideo(videoUri, true);
        status.setText("Trim if you want, then tap Add clip. Clips in project: " + clips.size());
      }
    }
  }

  private void showImage(Uri uri) {
    if (player != null) player.pause();
    previewIsImage = true;
    imageView.setImageURI(uri);
    imageView.setVisibility(View.VISIBLE);
    timeText.setText("Image");
  }

  private void loadVideo(Uri uri, boolean autoplay) {
    previewIsImage = false;
    imageView.setVisibility(View.GONE);
    if (player == null) {
      player = new ExoPlayer.Builder(this).build();
      playerView.setPlayer(player);
    }
    player.setMediaItem(MediaItem.fromUri(uri));
    player.prepare();
    loadedUri = uri;
    applyPreviewParams();
    player.setPlayWhenReady(autoplay);
  }

  // Preview speed, pitch and volume follow the dropdowns
  private void applyPreviewParams() {
    if (player == null) return;
    float sp = SPEED_VALUES[speedSpinner.getSelectedItemPosition()];
    float pv = PITCH_VALUES[pitchSpinner.getSelectedItemPosition()];
    float pitch = pv < 0 ? sp : pv;
    pitch = Math.max(0.1f, Math.min(8f, pitch));
    player.setPlaybackParameters(new PlaybackParameters(sp, pitch));
    player.setVolume(Math.min(1f, VOLUME_VALUES[volumeSpinner.getSelectedItemPosition()]));
  }

  // Tapping a block in the timeline shows that clip in the preview
  private void showClip(Clip c) {
    videoUri = c.uri;
    if (c.isImage) {
      showImage(c.uri);
      return;
    }
    if (player == null || !c.uri.equals(loadedUri) || previewIsImage) {
      loadVideo(c.uri, false);
    } else {
      player.pause();
    }
    player.seekTo(c.srcStart);
    status.setText("Clip " + (selectedClip + 1) + " covers "
        + fmt(c.srcStart) + " to " + fmt(c.srcEnd)
        + ". Drag the seek bar, then tap Split.");
  }

  private void releasePlayer() {
    if (player != null) {
      playerView.setPlayer(null);
      player.release();
      player = null;
      loadedUri = null;
    }
  }

  private void restorePreview() {
    if (videoUri == null || player != null || previewIsImage) return;
    loadVideo(videoUri, false);
  }

  // ---------- Clips ----------
  private void addCurrentClip() {
    if (videoUri == null) {
      status.setText("Pick a video or image first");
      return;
    }
    int sp = speedSpinner.getSelectedItemPosition();
    int vp = volumeSpinner.getSelectedItemPosition();
    int pp = pitchSpinner.getSelectedItemPosition();
    float speed = SPEED_VALUES[sp];
    float pv = PITCH_VALUES[pp];
    float pitch = pv < 0 ? speed : pv;

    boolean image = previewIsImage;
    long s;
    long e;
    if (image) {
      s = 0;
      e = IMAGE_MS;
      speed = 1f;
      pitch = 1f;
    } else {
      long dur = player != null ? player.getDuration() : 0;
      if (dur <= 0 || dur == C.TIME_UNSET || dur > 36000000L) dur = 3000;
      boolean trimmed = endMs > startMs;
      s = trimmed ? startMs : 0;
      e = trimmed ? endMs : dur;
    }
    clips.add(new Clip(videoUri, s, e, image, speed, VOLUME_VALUES[vp], pitch));
    selectedClip = clips.size() - 1;
    refreshTimeline();
    startMs = 0;
    endMs = 0;
    status.setText((image ? "Image" : "Clip") + " added. Clips in project: " + clips.size()
        + ". Pick the next one, or tap Export.");
  }

  private void splitSelected() {
    if (selectedClip < 0 || selectedClip >= clips.size()) {
      status.setText("Tap a clip in the timeline first.");
      return;
    }
    Clip c = clips.get(selectedClip);
    if (c.isImage) {
      status.setText("Images can't be split.");
      return;
    }
    if (player == null || previewIsImage || !c.uri.equals(loadedUri)) {
      status.setText("Tap the clip again so its video loads in the preview, then drag to the split point.");
      return;
    }
    long p = player.getCurrentPosition();
    if (p <= c.srcStart + 100 || p >= c.srcEnd - 100) {
      status.setText("Move the seek bar inside the clip (" + fmt(c.srcStart) + " to "
          + fmt(c.srcEnd) + ") and try again.");
      return;
    }
    Clip first = c.withRange(c.srcStart, p);
    Clip second = c.withRange(p, c.srcEnd);
    clips.set(selectedClip, first);
    clips.add(selectedClip + 1, second);
    refreshTimeline();
    status.setText("Split at " + fmt(p) + ". Clips in project: " + clips.size());
  }

  // ---------- Export ----------
  private void exportProject() {
    if (clips.isEmpty()) {
      status.setText("No clips yet. Pick a video or image and tap Add clip.");
      return;
    }
    final int height = QUALITY_HEIGHTS[quality.getSelectedItemPosition()];
    final float fps = FPS_VALUES[fpsSpinner.getSelectedItemPosition()];
    final int bitrate = BITRATES[bitrateSpinner.getSelectedItemPosition()];
    final String label = QUALITY_LABELS[quality.getSelectedItemPosition()];

    if (bitrate > 0 && maxBitrate > 0 && bitrate > maxBitrate) {
      status.setText("Your phone's encoder allows at most " + (maxBitrate / 1000000)
          + " Mbps. Pick a lower bitrate.");
      return;
    }

    final String summary = clips.size() + " clip(s)\n" + label + "  |  "
        + FPS_LABELS[fpsSpinner.getSelectedItemPosition()] + "  |  "
        + BITRATE_LABELS[bitrateSpinner.getSelectedItemPosition()];

    // Free the video player so the phone's encoder has room to start.
    releasePlayer();
    adjustNote = "";
    showOverlayExporting(summary);
    exportStart = System.currentTimeMillis();
    startExport(height, fps, bitrate, label, 0);
  }

  // attempt 0: exactly what you asked for
  // attempt 1: same, but without the fixed-bitrate mode
  // attempt 2: let the phone pick the nearest bitrate/size it accepts
  // attempt 3: also leave the frame rate unchanged
  private void startExport(final int height, final float fps, final int bitrate,
                           final String label, final int attempt) {
    currentOut = new File(getCacheDir(), "project_" + System.currentTimeMillis() + ".mp4");
    final File out = currentOut;

    final boolean cbr = attempt == 0 && bitrate > 0;
    final boolean fallback = attempt >= 2;
    final int useBitrate = attempt >= 2 ? 0 : bitrate;
    final boolean useFps = attempt < 3;

    List<EditedMediaItem> items = new ArrayList<>();
    for (Clip clip : clips) {
      List<Effect> videoEffects = new ArrayList<>();
      List<AudioProcessor> audioEffects = new ArrayList<>();

      if (!clip.isImage) {
        if (clip.speed != 1f) {
          android.util.Pair<AudioProcessor, Effect> pair =
              Effects.createExperimentalSpeedChangingEffect(new ConstantSpeed(clip.speed));
          audioEffects.add(pair.first);
          videoEffects.add(pair.second);
        }
        if (clip.pitch != 1f) {
          SonicAudioProcessor pitchProcessor = new SonicAudioProcessor();
          pitchProcessor.setPitch(clip.pitch);
          audioEffects.add(pitchProcessor);
        }
        if (clip.volume != 1f) {
          ChannelMixingAudioProcessor mixer = new ChannelMixingAudioProcessor();
          mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(clip.volume));
          mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(clip.volume));
          audioEffects.add(mixer);
        }
      }
      if (fps > 0 && useFps) videoEffects.add(FrameDropEffect.createDefaultFrameDropEffect(fps));
      if (height > 0) videoEffects.add(Presentation.createForHeight(height));

      EditedMediaItem.Builder eb = new EditedMediaItem.Builder(clip.item());
      if (clip.isImage) {
        eb.setDurationUs(clip.durationMs() * 1000L);
        eb.setFrameRate(30);
      }
      if (!videoEffects.isEmpty() || !audioEffects.isEmpty()) {
        eb.setEffects(new Effects(audioEffects, videoEffects));
      }
      items.add(eb.build());
    }
    Composition composition = new Composition.Builder(
        new EditedMediaItemSequence(items)).build();

    DefaultEncoderFactory.Builder ef = new DefaultEncoderFactory.Builder(this)
        .setEnableFallback(fallback);
    if (useBitrate > 0) {
      VideoEncoderSettings.Builder vb = new VideoEncoderSettings.Builder().setBitrate(useBitrate);
      if (cbr) vb.setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
      ef.setRequestedVideoEncoderSettings(vb.build());
    }

    transformer = new Transformer.Builder(this)
        .setEncoderFactory(ef.build())
        .addListener(new Transformer.Listener() {
          @Override
          public void onCompleted(Composition c, ExportResult result) {
            exporting = false;
            saveToGallery(out, sizeOf(out), describe(result));
          }

          @Override
          public void onError(Composition c, ExportResult result, ExportException e) {
            out.delete();
            String code = e.getErrorCodeName();
            boolean retryable = code.contains("FORMAT_UNSUPPORTED") || code.contains("INIT_FAILED");
            if (retryable && attempt < 3) {
              if (attempt == 1) adjustNote += "- Bitrate set to automatic\n";
              if (attempt == 2) adjustNote += "- Frame rate left unchanged\n";
              ovDetail.setText("Your phone rejected those settings. Trying a safer setup ("
                  + (attempt + 1) + " of 3)...");
              startExport(height, fps, bitrate, label, attempt + 1);
              return;
            }
            showOverlayError(friendlyError(e, label));
          }
        })
        .build();

    exporting = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
    transformer.start(composition, out.getAbsolutePath());
  }

  private String describe(ExportResult r) {
    String s = "";
    if (r.durationMs > 0 && r.videoFrameCount > 0) {
      s += String.format(java.util.Locale.US, "%.1f fps",
          r.videoFrameCount * 1000.0 / r.durationMs);
    }
    if (r.averageVideoBitrate > 0) {
      s += (s.isEmpty() ? "" : "  |  ")
          + String.format(java.util.Locale.US, "%.1f Mbps", r.averageVideoBitrate / 1000000.0);
    }
    return s.isEmpty() ? "" : "Actual: " + s;
  }

  private String sizeOf(File f) {
    try {
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      r.setDataSource(f.getAbsolutePath());
      String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
      String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
      String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
      r.release();
      if ("90".equals(rot) || "270".equals(rot)) return h + "x" + w;
      return w + "x" + h;
    } catch (Exception e) {
      return "unknown size";
    }
  }

  private String friendlyError(ExportException e, String label) {
    String code = e.getErrorCodeName();
    String msg;
    if (code.contains("INIT_FAILED")) {
      msg = "The phone's encoder couldn't start. It may be busy or low on memory. "
          + "Close other apps and try again.";
    } else if (code.contains("FORMAT_UNSUPPORTED")) {
      msg = "Your phone doesn't support " + label
          + " even after trying safer settings. Try a lower quality.";
    } else if (code.contains("DECOD")) {
      msg = "Your phone can't read one of these files (format not supported).";
    } else {
      msg = "Something went wrong during export.";
    }
    return msg + "\n\nCode: " + code;
  }

  private void saveToGallery(File file, String size, String actual) {
    runOnUiThread(() -> ovDetail.setText("Saving to gallery..."));
    new Thread(() -> {
      try {
        ContentResolver cr = getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "EasyFlow_" + System.currentTimeMillis() + ".mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EasyFlow");
        Uri dest = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        try (InputStream in = new FileInputStream(file);
             OutputStream os = cr.openOutputStream(dest)) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        file.delete();
        runOnUiThread(() -> {
          showOverlayDone(size, actual);
          status.setText("Done! Saved to Movies/EasyFlow (" + size + ")");
        });
      } catch (Exception e) {
        runOnUiThread(() -> showOverlayError("Save failed: " + e.getMessage()));
      }
    }).start();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (player != null) player.pause();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    exporting = false;
    handler.removeCallbacksAndMessages(null);
    if (transformer != null) transformer.cancel();
    if (player != null) player.release();
  }
               }
