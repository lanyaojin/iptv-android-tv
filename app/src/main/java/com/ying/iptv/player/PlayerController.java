package com.ying.iptv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;

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
    public static final int MAX_RETRY = 3;
    private static final long RETRY_STEP_MS = 800L;
    /** 连续稳定播放超过此时长后，重连额度才重新计满，避免抖动源无限重连。 */
    private static final long STABLE_PLAY_MS = 8000L;

    private final ExoPlayer player;
    private final PlayerView playerView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String url;
    private int attempt;
    private long startedAt;
    private boolean failed;
    /** 多线路频道：首次可恢复错误立即交给上层换线，不空等 3 轮重连。 */
    private boolean fastFailover;
    private Listener listener;

    public PlayerController(Context context, PlayerView view) {
        Context app = context.getApplicationContext();
        // 源站直播窗口很短（分片 5~6s、约 4 片），默认 50s 缓冲目标不切实际。
        // 缩短起播与卡顿后恢复所需缓冲，同时保留约 2~5 个分片的余量。
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 1500, 3000)
                .build();
        this.player = new ExoPlayer.Builder(app)
                .setLoadControl(loadControl)
                .build();
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

    /** 是否启用「快速换线」：多线路频道首轮失败直接回调 onFailed，由上层换下一条线路。 */
    public void setFastFailover(boolean enabled) {
        this.fastFailover = enabled;
    }

    public ExoPlayer player() {
        return player;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    /** stop() 之后播放器处于 IDLE，据此判断是否需要恢复播放。 */
    public boolean isIdle() {
        return player.getPlaybackState() == Player.STATE_IDLE;
    }

    public void play(String address) {
        this.url = address;
        resetSession();
        start();
    }

    /** 手动重试（失败态按 OK 触发）。 */
    public void retry() {
        if (url == null) return;
        resetSession();
        start();
    }

    /** 从后台返回时恢复播放（onStop 会 stop() 播放器）。 */
    public void resume() {
        if (url == null || !isIdle()) return;
        resetSession();
        start();
    }

    private void resetSession() {
        attempt = 0;
        failed = false;
        startedAt = SystemClock.elapsedRealtime();
    }

    private void start() {
        if (url == null || failed) return;
        // 每次重新建源前清掉旧的重连回调，避免换台后旧频道的重试覆盖新频道。
        handler.removeCallbacksAndMessages(null);
        try {
            player.stop();
            player.setMediaSource(buildSource(url));
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            fail(friendly(e));
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
        if (looksLikeHls(address)) {
            HlsMediaSource.Factory factory = new HlsMediaSource.Factory(dataSourceFactory());
            factory.setAllowChunklessPreparation(true);
            return factory.createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSourceFactory()).createMediaSource(item);
    }

    /**
     * 判断是否为 HLS。除 .m3u8 外，覆盖 /relay/、/live/ 一类不带扩展名
     * 但实际返回 m3u8 播放列表的中转路径；明确的文件流扩展名优先排除。
     * 另覆盖咪咕数字代理（/1905/&lt;纯数字ID&gt;）等无扩展名中转地址：
     * 这类地址返回的也是 m3u8 清单，若按渐进式文件处理，ExoPlayer 会因
     * 无法识别 m3u8 文本而报 UnrecognizedInputFormatException。
     */
    private static boolean looksLikeHls(String address) {
        if (address == null) return false;
        String p = address.toLowerCase(Locale.ROOT).split("\\?")[0];
        if (p.endsWith(".mp4") || p.endsWith(".ts") || p.endsWith(".mkv")
                || p.endsWith(".mp3") || p.endsWith(".flv") || p.endsWith(".avi")) {
            return false;
        }
        if (p.contains(".m3u8")) return true;
        if (p.contains("/hls/") || p.contains("/live/")
                || p.contains("/relay/") || p.contains("/proxy/")) {
            return true;
        }
        int lastSlash = p.lastIndexOf('/');
        String last = lastSlash >= 0 ? p.substring(lastSlash + 1) : p;
        if (last.matches("\\d+")) return true; // /608807420 咪咕数字代理
        return !last.contains("."); // 路径末段无扩展名 → 按 HLS 处理
    }

    private DataSource.Factory dataSourceFactory() {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        factory.setUserAgent(UA);
        factory.setConnectTimeoutMs(8000);
        factory.setReadTimeoutMs(12000);
        factory.setAllowCrossProtocolRedirects(true);
        return factory;
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) {
            // 只有在稳定播放一段时间后成功就绪，才重置重连额度；
            // 抖动源反复 READY/BUFFERING 不会因此获得无限重试次数。
            if (SystemClock.elapsedRealtime() - startedAt > STABLE_PLAY_MS) {
                attempt = 0;
            }
            if (listener != null) listener.onReady();
        } else if (state == Player.STATE_BUFFERING) {
            if (listener != null) listener.onBuffering();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        // 连续错误时先撤销尚未执行的重试，防止多条重试链叠加。
        handler.removeCallbacksAndMessages(null);

        String permanent = permanentReason(error);
        if (permanent == null && isUnrecognizedContent(error)) {
            // 中转接口返回 JSON/空内容、或 HLS 清单解析失败：重试同样解不出视频流。
            permanent = "源返回的不是可播放的视频流";
        }
        if (permanent != null) {
            // 确定性错误重试无意义，立即提示，避免干等 3 轮。
            fail(permanent);
            return;
        }
        if (fastFailover) {
            // 多线路频道首轮失败：换一条线路比反复重连同一条死链恢复更快。
            // 一圈线路都试完后由上层关闭此标志，再走完整重试兜底。
            fail("当前线路不可用");
            return;
        }
        if (attempt < MAX_RETRY) {
            attempt++;
            final int current = attempt;
            if (listener != null) listener.onRetrying(current);
            handler.postDelayed(this::start, RETRY_STEP_MS * current);
        } else {
            fail(friendly(error));
        }
    }

    /** 统一的失败出口：只通知一次，并停掉播放器，避免继续占用网络。 */
    private void fail(String message) {
        if (failed) return;
        failed = true;
        handler.removeCallbacksAndMessages(null);
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        if (listener != null) listener.onFailed(message);
    }

    /** 遍历异常链，找出确定不可恢复的 HTTP 状态（403 可能是 CDN 瞬态封锁，仍保留重试）。 */
    private static String permanentReason(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
                if (code == 400 || code == 401 || code == 404 || code == 410) {
                    return "源地址返回 HTTP " + code + "，该频道不可用";
                }
            }
            t = t.getCause();
        }
        return null;
    }

    /** 解析器无法识别返回内容时判定为确定性失败（含 HLS 清单被 JSON/错误页替代的情况）。 */
    private static boolean isUnrecognizedContent(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t.getClass().getSimpleName().contains("UnrecognizedInputFormat")) return true;
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("none of the available extractors")
                        || lower.contains("could not read the stream")
                        || lower.contains("unexpected end of stream")
                        || lower.contains("unexpected #extm3u")
                        || lower.contains("does not start with #extm3u")
                        || lower.contains("input does not start")
                        || lower.contains("invalid playlist")
                        || lower.contains("parser exception")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /** 将底层异常转成电视上可读的中文短句。 */
    private static String friendly(Throwable error) {
        Throwable t = error;
        String http = null;
        while (t != null) {
            if (http == null && t instanceof HttpDataSource.InvalidResponseCodeException) {
                http = "HTTP " + ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("unable to resolve host") || lower.contains("unknownhost")) {
                    return "网络不可达或域名解析失败";
                }
                if (lower.contains("timeout") || lower.contains("timed out")) {
                    return "连接超时，源服务器无响应";
                }
                if (lower.contains("connection refused") || lower.contains("econnreset")) {
                    return "源服务器拒绝连接";
                }
                if (lower.contains("malformed") || lower.contains("invalid url")) {
                    return "源地址格式不正确";
                }
            }
            t = t.getCause();
        }
        if (http != null) return "源服务器返回 " + http;
        return "源不可用或已失效";
    }
}
