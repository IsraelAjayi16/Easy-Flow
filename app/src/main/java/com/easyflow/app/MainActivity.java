package com.easyflow.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
  private static final int PICK = 1;
  private ExoPlayer player;
  private PlayerView playerView;
  private TextView status;
  private Uri videoUri;
  private long startMs = 0;
  private long endMs = 0;
  private final List<EditedMediaItem> clips = new ArrayList<>();

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
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
    row.addView(setStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    row.addView(setEnd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    root.addView(row, wrap());

    status = new TextView(this);
    status.setText("Pick a video first");
    status.setPadding(24, 16, 24, 16);
    root.addView(status, wrap());

    LinearLayout row2 = new LinearLayout(this);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    Button addClip = new Button(this);
    addClip.setText("Add clip");
    Button clear = new Button(this);
    clear.setText("Clear all");
    row2.addView(addClip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    row2.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    root.addView(row2, wrap());

    Button export = new Button(this);
    export.setText("Export");
    root.addView(export, wrap());

    setContentView(root);

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
    });

    export.setOnClickListener(v -> exportProject());
  }

  private LinearLayout.LayoutParams wrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private void showTimes() {
    status.setText("Start: " + (startMs / 1000.0) + "s   End: " + (endMs / 1000.0)
        + "s   Clips in project: " + clips.size());
  }

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
    player.play();
    startMs = 0;
    endMs = 0;
    status.setText("Trim if you want, then tap Add clip. Clips in project: " + clips.size());
  }

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
    clips.add(new EditedMediaItem.Builder(mib.build()).build());
    startMs = 0;
    endMs = 0;
    status.setText((trimmed ? "Trimmed clip" : "Full clip") + " added. Clips in project: "
        + clips.size() + ". Pick the next video, or tap Export.");
  }

  private void exportProject() {
    if (clips.isEmpty()) {
      status.setText("No clips yet. Pick a video and tap Add clip.");
      return;
    }
    status.setText("Exporting " + clips.size() + " clip(s)... please wait");
    final File out = new File(getCacheDir(), "project_" + System.currentTimeMillis() + ".mp4");

    Composition composition = new Composition.Builder(
        new EditedMediaItemSequence(new ArrayList<>(clips))).build();

    Transformer transformer = new Transformer.Builder(this)
        .addListener(new Transformer.Listener() {
          @Override
          public void onCompleted(Composition composition, ExportResult result) {
            saveToGallery(out);
          }

          @Override
          public void onError(Composition composition, ExportResult result, ExportException e) {
            status.setText("Export failed: " + e.getMessage());
          }
        })
        .build();

    transformer.start(composition, out.getAbsolutePath());
  }

  private void saveToGallery(File file) {
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
        runOnUiThread(() -> status.setText("Done! Saved to Movies/EasyFlow"));
      } catch (Exception e) {
        runOnUiThread(() -> status.setText("Save failed: " + e.getMessage()));
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
    if (player != null) player.release();
  }
}
