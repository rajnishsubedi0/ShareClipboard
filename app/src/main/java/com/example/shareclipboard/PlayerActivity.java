package com.example.shareclipboard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "com.example.shareclipboard.extra.URL";

    private static final String STATE_POSITION = "state_position";
    private static final String STATE_PLAY_WHEN_READY = "state_play_when_ready";
    private static final long SEEK_INCREMENT_MS = 10000;

    private PlayerView playerView;
    private ExoPlayer player;
    private String url;
    private long playbackPositionMs = 0;
    private boolean playWhenReady = true;
    private boolean errorDialogShowing = false;

    public static Intent createIntent(Context context, String url) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_URL, url);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String requested = getIntent().getStringExtra(EXTRA_URL);
        if (requested == null || requested.trim().isEmpty()) {
            finish();
            return;
        }
        url = requested.trim();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_player);
        playerView = findViewById(R.id.playerView);
        hideSystemBars();

        if (savedInstanceState != null) {
            playbackPositionMs = savedInstanceState.getLong(STATE_POSITION, 0);
            playWhenReady = savedInstanceState.getBoolean(STATE_PLAY_WHEN_READY, true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (url != null) {
            initializePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            playbackPositionMs = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
        }
        outState.putLong(STATE_POSITION, playbackPositionMs);
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void initializePlayer() {
        if (player != null) {
            return;
        }
        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                showPlaybackError(error);
            }
        });
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.setPlayWhenReady(playWhenReady);
        if (playbackPositionMs > 0) {
            player.seekTo(playbackPositionMs);
        }
        player.prepare();
    }

    private void releasePlayer() {
        if (player == null) {
            return;
        }
        playbackPositionMs = player.getCurrentPosition();
        playWhenReady = player.getPlayWhenReady();
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void showPlaybackError(PlaybackException error) {
        if (errorDialogShowing || isFinishing() || isDestroyed()) {
            return;
        }
        errorDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Can't play this in the app")
                .setMessage("The built-in player couldn't play this link (" + error.getErrorCodeName()
                        + "). You can try opening it in VLC instead.")
                .setPositiveButton("Open in VLC", (dialog, which) -> VlcLauncher.open(this, url))
                .setNegativeButton("Close", (dialog, which) -> finish())
                .setOnDismissListener(dialog -> errorDialogShowing = false)
                .show();
    }
}
