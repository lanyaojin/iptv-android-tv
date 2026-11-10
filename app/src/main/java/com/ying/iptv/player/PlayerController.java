package com.ying.iptv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

/** ExoPlayer 封装：负责建源、播放、断流自动重连。 */
public class PlayerController implements Player.Listener {

    public interface Listener {
        void onReady();

        void onBuffering();

        /** 第 attempt 次自动重连。 */
        void onRetrying(int attempt);

        void onFailed(String message);
    }

    private static final String UA = "IPTV/1.0 (Android TV)";
    private static final int MAX_RETRY = 3;

    private final ExoPlayer player;
    private final PlayerView playerView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String url;
    private int attempt;
    private Listener listener;

    public PlayerController(Context context, PlayerView view) {
        Context app = context.getApplicationContext();
        this.player = new ExoPlayer.Builder(app).build();
        this.player.setWakeMode(C.WAKE_MODE_NETWORK);
        this.player.addListener(this);
        this.playerView = view;
        view.setPlayer(player);
        view.setUseController(false);
        view.setKeepScreenOn(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public ExoPlayer player() {
        return player;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void play(String address) {
        this.url = address;
        this.attempt = 0;
        start();
    }

    private void start() {
        if (url == null) return;
        handler.removeCallbacksAndMessages(null);
        try {
            player.stop();
            player.setMediaSource(buildSource(url));
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            if (listener != null) listener.onFailed(describe(e));
        }
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        player.stop();
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        player.removeListener(this);
        player.release();
        playerView.setPlayer(null);
    }

    private MediaSource buildSource(String address) {
        MediaItem item = MediaItem.fromUri(address);
        String lower = address.toLowerCase();
        if (lower.contains(".m3u8")) {
            HlsMediaSource.Factory factory = new HlsMediaSource.Factory(dataSourceFactory());
            factory.setAllowChunklessPreparation(true);
            return factory.createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSourceFactory()).createMediaSource(item);
    }

    private DataSource.Factory dataSourceFactory() {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        factory.setUserAgent(UA);
        factory.setConnectTimeoutMs(10000);
        factory.setReadTimeoutMs(15000);
        factory.setAllowCrossProtocolRedirects(true);
        return factory;
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) {
            attempt = 0;
            if (listener != null) listener.onReady();
        } else if (state == Player.STATE_BUFFERING) {
            if (listener != null) listener.onBuffering();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        if (attempt < MAX_RETRY) {
            attempt++;
            final int current = attempt;
            if (listener != null) listener.onRetrying(current);
            handler.postDelayed(this::start, 1200L * current);
        } else if (listener != null) {
            listener.onFailed(describe(error));
        }
    }

    private String describe(Exception e) {
        String name = e.getClass().getSimpleName();
        String cause = e.getCause() != null ? e.getCause().getMessage() : null;
        String msg = e.getMessage();
        StringBuilder sb = new StringBuilder();
        if (msg != null && !msg.isEmpty()) sb.append(msg);
        if (cause != null && !cause.isEmpty()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(cause);
        }
        if (sb.length() == 0) sb.append(name);
        return sb.toString();
    }
}
