package com.easyflow.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends Activity {
  private static final int PICK = 1;
  private ExoPlayer player;
  private PlayerView playerView;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);

    Button pick = new Button(this);
    pick.setText("Pick a video");
    root.addView(pick, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));

    playerView = new PlayerView(this);
    root.addView(playerView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    setContentView(root);

    pick.setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("video/*");
      startActivityForResult(i, PICK);
    });
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
      play(data.getData());
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
