package com.ying.iptv.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 直播源加载：优先本地缓存，必要时下载并缓存。 */
public final class PlaylistRepository {

    public interface Callback {
        /** 开始加载；fromNetwork 表示需要联网下载。 */
        void onLoading(boolean fromNetwork);

        void onSuccess(Playlist playlist, boolean fromCache);

        void onError(String message);
    }

    private static final String UA = "IPTV/1.0 (Android TV)";
    private static final long CACHE_TTL_MS = 6L * 60 * 60 * 1000; // 6 小时
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PlaylistRepository() {
    }

    public static void load(Context context, String url, boolean forceRefresh, Callback callback) {
        Context app = context.getApplicationContext();
        boolean hasCache = cacheFile(app, url).isFile();
        MAIN.post(() -> callback.onLoading(forceRefresh || !hasCache));

        EXECUTOR.execute(() -> {
            File cache = cacheFile(app, url);
            try {
                boolean fresh = hasCache && !forceRefresh
                        && System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS;
                if (fresh) {
                    String text = readFile(cache);
                    Playlist playlist = M3uParser.parse(text);
                    if (!playlist.isEmpty()) {
                        MAIN.post(() -> callback.onSuccess(playlist, true));
                        return;
                    }
                }

                String text = download(url);
                Playlist playlist = M3uParser.parse(text);
                if (playlist.isEmpty()) {
                    MAIN.post(() -> callback.onError("该地址未解析到直播频道"));
                    return;
                }
                writeFile(cache, text);
                MAIN.post(() -> callback.onSuccess(playlist, false));
            } catch (Exception e) {
                // 网络失败时退回旧缓存，保证电视上仍有台可看
                if (hasCache) {
                    try {
                        Playlist playlist = M3uParser.parse(readFile(cache));
                        if (!playlist.isEmpty()) {
                            MAIN.post(() -> callback.onSuccess(playlist, true));
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
                MAIN.post(() -> callback.onError(describe(e)));
            }
        });
    }

    /** 清空某个源的缓存，供切换源/刷新使用。 */
    public static void invalidate(Context context, String url) {
        File f = cacheFile(context.getApplicationContext(), url);
        if (f.isFile()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String download(String address) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(address);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = readAll(in);
                if (bytes.length == 0) throw new IOException("内容为空");
                return decode(bytes);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("文件过大");
        }
        return out.toByteArray();
    }

    /** 优先 UTF-8；出现替换字符时按 GBK 重解，兼容老式源。 */
    private static String decode(byte[] bytes) {
        int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;
        String utf8 = new String(bytes, offset, bytes.length - offset, Charset.forName("UTF-8"));
        if (utf8.indexOf('\uFFFD') < 0) return utf8;
        try {
            String gbk = new String(bytes, offset, bytes.length - offset, Charset.forName("GBK"));
            if (gbk.indexOf('\uFFFD') < 0) return gbk;
        } catch (Exception ignored) {
        }
        return utf8;
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        if (e instanceof java.net.UnknownHostException) return "无法解析域名";
        if (e instanceof java.net.ConnectException) return "无法连接服务器";
        String msg = e.getMessage();
        return (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
    }

    private static File cacheFile(Context context, String url) {
        return new File(context.getCacheDir(), "playlist_" + md5(url) + ".m3u");
    }

    private static String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return decode(readAll(in));
        }
    }

    private static void writeFile(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(Charset.forName("UTF-8")));
        } catch (IOException ignored) {
        }
    }

    /** 便捷方法：返回当前源地址；未配置源时返回 null。 */
    public static String currentUrl(Context context) {
        Source source = Prefs.get(context).currentSource();
        return source != null ? source.url : null;
    }
}
