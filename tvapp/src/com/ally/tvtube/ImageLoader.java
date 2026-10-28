package com.ally.tvtube;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {
    private static final int CACHE_SIZE = 6 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
    private static final int THREAD_COUNT = 3;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREAD_COUNT);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ImageLoader() {}

    public static void load(ImageView view, String url, int reqWidth, int reqHeight) {
        if (view == null) return;

        if (url == null || url.length() == 0) {
            view.setTag(null);
            view.setImageBitmap(null);
            return;
        }

        Request request = new Request(url, reqWidth, reqHeight);
        view.setTag(request);

        Bitmap cached = getCached(request.key());
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageBitmap(null);
        EXECUTOR.execute(new LoadTask(view, request));
    }

    private static Bitmap getCached(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    private static void putCached(String key, Bitmap bmp) {
        if (bmp == null) return;
        synchronized (CACHE) {
            CACHE.put(key, bmp);
        }
    }

    private static Bitmap downloadAndDecode(Request request) {
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL url = new URL(request.url);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.connect();

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            is = new BufferedInputStream(conn.getInputStream(), 8192);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer)) != -1) {
                baos.write(buffer, 0, count);
            }

            byte[] data = baos.toByteArray();
            if (data.length == 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);

            opts.inSampleSize = calculateInSampleSize(opts, request.width, request.height);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable t) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;

        if (reqWidth <= 0 || reqHeight <= 0 || width <= 0 || height <= 0) {
            return inSampleSize;
        }

        while ((width / (inSampleSize * 2)) >= reqWidth && (height / (inSampleSize * 2)) >= reqHeight) {
            inSampleSize *= 2;
        }

        return inSampleSize;
    }

    private static class Request {
        final String url;
        final int width;
        final int height;

        Request(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        String key() {
            return url + "#" + width + "x" + height;
        }
    }

    private static class LoadTask implements Runnable {
        private final WeakReference<ImageView> viewRef;
        private final Request request;

        LoadTask(ImageView view, Request request) {
            this.viewRef = new WeakReference<ImageView>(view);
            this.request = request;
        }

        @Override
        public void run() {
            Bitmap bmp = getCached(request.key());
            if (bmp == null) {
                bmp = downloadAndDecode(request);
                if (bmp != null) {
                    putCached(request.key(), bmp);
                }
            }

            final Bitmap result = bmp;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    ImageView view = viewRef.get();
                    if (view == null) return;
                    if (view.getTag() != request) return;

                    if (result != null) {
                        view.setImageBitmap(result);
                    } else {
                        view.setImageBitmap(null);
                    }
                }
            });
        }
    }
}
