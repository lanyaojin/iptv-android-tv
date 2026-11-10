package com.ying.iptv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简频道图标加载器：内存 LRU + 线程池 + 采样解码，避免低配电视 OOM。 */
public final class ImageLoader {

    private static final int MAX_HEIGHT = 96;
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setTag(url);
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(url);
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap bitmap = fetch(url);
            if (bitmap == null) return;
            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private static Bitmap fetch(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "IPTV/1.0 (Android TV)");
            conn.setInstanceFollowRedirects(true);
            byte[] data = readAll(conn.getInputStream());
            if (data.length == 0) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outHeight <= 0) return null;

            int sample = 1;
            while (bounds.outHeight / sample > MAX_HEIGHT * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[8 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 2 * 1024 * 1024) break;
        }
        in.close();
        return out.toByteArray();
    }
}
