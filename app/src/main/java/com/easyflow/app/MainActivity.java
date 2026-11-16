package com.easyflow.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
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
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.SpeedChangeEffect;
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

  // ---------- A clip in the project: the video part plus its speed ----------
  static class Clip {
    final MediaItem item;
    final float speed;
    final float volume;
        final long durationMs;

    Clip(MediaItem item, float speed, float volume, long durationMs) {
      this.durationMs = durationMs;
      this.item = item;
      this.speed = speed;
      this.volume = volume;
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
      outline.setStrokeWidth(2.5f * d);
      outline.setColor(Color.WHITE);
      label.setColor(Color.WHITE);
      label.setTextSize(11f * d);
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
      float sec = (c.durationMs / c.speed) / 1000f;
      return Math.max(64f * d, sec * 40f * d);
    }

    @Override
    protected void onMeasure(int w, int h) {
      float total = 24f * d;
      for (Clip c : data) total += widthOf(c) + 4f * d;
      int minW = getResources().getDisplayMetrics().widthPixels;
      setMeasuredDimension((int) Math.max(total, minW), (int) (84f * d));
    }

    private String num(float v) {
      return v == (int) v ? String.valueOf((int) v) : String.valueOf(v);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      canvas.drawRect(0, 0, getWidth(), getHeight(), bg);
      for (float tx = 8f * d; tx < getWidth(); tx += 40f * d) {
        canvas.drawLine(tx, 0, tx, 8f * d, tick);
      }
      float left = 8f * d;
      for (int i = 0; i < data.size(); i++) {
        Clip c = data.get(i);
        float w = widthOf(c);
        rect.set(left, 18f * d, left + w, getHeight() - 8f * d);
        blockPaint.setShader(new android.graphics.LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.parseColor("#673AB7"), Color.parseColor("#9575CD"),
            android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, 8f * d, 8f * d, blockPaint);
        if (i == selected) canvas.drawRoundRect(rect, 8f * d, 8f * d, outline);
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawText("Clip " + (i + 1), rect.left + 8f * d, rect.top + 18f * d, label);
        String info = String.format(java.util.Locale.US, "%.1fs", (c.durationMs / c.speed) / 1000f)
            + "  " + num(c.speed) + "x  " + Math.round(c.volume * 100) + "%";
        canvas.drawText(info, rect.left + 8f * d, rect.top + 36f * d, label);
        canvas.restore();
        left += w + 4f * d;
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
          left += w + 4f * d;
        }
        if (listener != null) listener.onSelect(-1);
        return true;
      }
      return super.onTouchEvent(e);
    }
  }

  private TimelineView timeline;
  private TextView timelineInfo;
  private int selectedClip = -1;

  private void refreshTimeline() {
    timeline.setData(clips, selectedClip);
    long total = 0;
    for (Clip c : clips) total += (long) (c.durationMs / c.speed);
    timelineInfo.setText("Timeline: " + clips.size() + " clip(s), "
        + String.format(java.util.Locale.US, "%.1f", total / 1000.0) + "s total after speed"
        + (selectedClip >= 0 ? "   |   Clip " + (selectedClip + 1) + " selected" : ""));
                    }
  
  // ---------- Options ----------
  private static final int PICK = 1;
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
  // ---------- State ----------
  private ExoPlayer player;
  private PlayerView playerView;
  private TextView status, limits;
    private Spinner quality, fpsSpinner, bitrateSpinner, speedSpinner, volumeSpinner;
    private static final String[] VOLUME_LABELS = {
      "Muted", "25% vol", "50% vol", "100% vol", "150% vol", "200% vol"};
  private static final float[] VOLUME_VALUES = {0f, 0.25f, 0.5f, 1f, 1.5f, 2f};
  private Uri videoUri;
  private long startMs = 0;
  private long endMs = 0;
  private final List<Clip> clips = new ArrayList<>();
  private int maxBitrate = 0;
  private String limitsText = "";
  private String adjustNote = "";

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

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    readLimits();

    FrameLayout rootFrame = new FrameLayout(this);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    Button pick = new Button(this);
    pick.setText("Pick a video");
    root.addView(pick, wrap());

    playerView = new PlayerView(this);
    root.addView(playerView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    Button setStart = new Button(this);
    setStart.setText("Set start");
    Button setEnd = new Button(this);
    setEnd.setText("Set end");
    row.addView(setStart, half());
    row.addView(setEnd, half());
    root.addView(row, wrap());
        
    timelineInfo = new TextView(this);
    timelineInfo.setTextSize(12);
    timelineInfo.setPadding(24, 0, 24, 4);
    root.addView(timelineInfo, wrap());

    timeline = new TimelineView(this);
    timeline.setListener(index -> {
      selectedClip = index;
      refreshTimeline();
    });
    
    android.widget.HorizontalScrollView tlScroll = new android.widget.HorizontalScrollView(this);
    tlScroll.setHorizontalScrollBarEnabled(false);
    tlScroll.addView(timeline);
    root.addView(tlScroll, wrap());

    LinearLayout tlRow = new LinearLayout(this);
    tlRow.setOrientation(LinearLayout.HORIZONTAL);
    Button moveLeft = new Button(this);
    moveLeft.setText("Move left");
    Button moveRight = new Button(this);
    moveRight.setText("Move right");
    Button deleteClip = new Button(this);
    deleteClip.setText("Delete");
    tlRow.addView(moveLeft, half());
    tlRow.addView(moveRight, half());
    tlRow.addView(deleteClip, half());
    root.addView(tlRow, wrap());

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
    refreshTimeline();

    status = new TextView(this);
    status.setText("Pick a video first");
    status.setPadding(24, 12, 24, 12);
    root.addView(status, wrap());

    LinearLayout row2 = new LinearLayout(this);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    speedSpinner = makeSpinner(SPEED_LABELS);
        speedSpinner.setSelection(4); // 1x speed
    speedSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        if (player != null) {
          player.setPlaybackParameters(
              new androidx.media3.common.PlaybackParameters(SPEED_VALUES[position]));
        }
      }

      @Override
      public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    
    Button addClip = new Button(this);
    addClip.setText("Add clip");
    Button clear = new Button(this);
    clear.setText("Clear all");
    volumeSpinner = makeSpinner(VOLUME_LABELS);
    volumeSpinner.setSelection(3); // 100%
    volumeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override
      public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        if (player != null) player.setVolume(Math.min(1f, VOLUME_VALUES[position]));
      }

      @Override
      public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    LinearLayout rowSV = new LinearLayout(this);
    rowSV.setOrientation(LinearLayout.HORIZONTAL);
    rowSV.addView(speedSpinner, half());
    rowSV.addView(volumeSpinner, half());
    root.addView(rowSV, wrap());
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
      i.setType("video/*");
      startActivityForResult(i, PICK);
    });

    setStart.setOnClickListener(v -> {
      if (player == null) return;
      startMs = player.getCurrentPosition();
      if (endMs <= startMs) endMs = player.getDuration();
      showTimes();
    });

    setEnd.setOnClickListener(v -> {
      if (player == null) return;
      endMs = player.getCurrentPosition();
      showTimes();
    });

    addClip.setOnClickListener(v -> addCurrentClip());

    clear.setOnClickListener(v -> {
      clips.clear();
      status.setText("Cleared. Clips in project: 0");
            selectedClip = -1;
      refreshTimeline();
    });

    export.setOnClickListener(v -> exportProject());
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

  // ---------- Player ----------
  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
      videoUri = data.getData();
      play(videoUri);
    }
  }

  private void play(Uri uri) {
    if (player == null) {
      player = new ExoPlayer.Builder(this).build();
      playerView.setPlayer(player);
    }
    player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
    player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(
        SPEED_VALUES[speedSpinner.getSelectedItemPosition()]));
    player.play();
    startMs = 0;
    endMs = 0;
    status.setText("Trim if you want, then tap Add clip. Clips in project: " + clips.size());
  }

  private void releasePlayer() {
    if (player != null) {
      playerView.setPlayer(null);
      player.release();
      player = null;
    }
  }

  private void restorePreview() {
    if (videoUri == null || player != null) return;
    player = new ExoPlayer.Builder(this).build();
    playerView.setPlayer(player);
    player.setMediaItem(MediaItem.fromUri(videoUri));
    player.prepare();
  }

  // ---------- Clips ----------
  private void addCurrentClip() {
    if (videoUri == null) {
      status.setText("Pick a video first");
      return;
    }
    MediaItem.Builder mib = new MediaItem.Builder().setUri(videoUri);
    boolean trimmed = endMs > startMs;
    if (trimmed) {
      mib.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(startMs)
          .setEndPositionMs(endMs)
          .build());
    }
    int sp = speedSpinner.getSelectedItemPosition();
    int vp = volumeSpinner.getSelectedItemPosition();
    clips.add(new Clip(mib.build(), SPEED_VALUES[sp], VOLUME_VALUES[vp]));
    startMs = 0;
    endMs = 0;
    status.setText((trimmed ? "Trimmed clip" : "Full clip") + " added at "
        + SPEED_LABELS[sp] + ". Clips in project: " + clips.size()
        + ". Pick the next video, or tap Export.");
  }

  // ---------- Export ----------
  private void exportProject() {
    if (clips.isEmpty()) {
      status.setText("No clips yet. Pick a video and tap Add clip.");
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
            if (clip.speed != 1f) {
        android.util.Pair<AudioProcessor, Effect> pair =
            Effects.createExperimentalSpeedChangingEffect(new ConstantSpeed(clip.speed));
        audioEffects.add(pair.first);
        videoEffects.add(pair.second);
      }
            if (clip.volume != 1f) {
        ChannelMixingAudioProcessor mixer = new ChannelMixingAudioProcessor();
        mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(clip.volume));
        mixer.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(clip.volume));
        audioEffects.add(mixer);
      }
      if (height > 0) videoEffects.add(Presentation.createForHeight(height));

      EditedMediaItem.Builder eb = new EditedMediaItem.Builder(clip.item);
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
      msg = "Your phone can't read one of these videos (format not supported).";
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
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
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
