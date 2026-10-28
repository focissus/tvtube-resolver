package com.ally.tvtube;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Api {
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;
    private static final String PREF_NAME = "tv";
    private static final String KEY_BASE = "base";
    private static final String DEFAULT_BASE = "http://192.168.1.10:8000";

    private static final LruCache<String, Bitmap> BITMAP_CACHE = 
        new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

    private Api() {}

    public static class Video {
        public String id;
        public String title;
        public String author;
        public String thumb;
        public String duration;

        public Video(String id, String title, String author, String thumb, String duration) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.thumb = thumb;
            this.duration = duration;
        }
    }

    public static class Stream {
        public String url;
        public String quality;
        public String mime;

        public Stream(String url, String quality, String mime) {
            this.url = url;
            this.quality = quality;
            this.mime = mime;
        }
    }

    public static String baseUrl(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_BASE, DEFAULT_BASE);
    }

    public static void setBaseUrl(Context c, String url) {
        SharedPreferences sp = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_BASE, url).commit();
    }

    private static String httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, READ_TIMEOUT);
    }

    public static ArrayList<Video> search(Context c, String q) throws Exception {
        String base = baseUrl(c);
        String fullUrl = base + "/api/search?q=" + URLEncoder.encode(q, "UTF-8");
        String response = httpGet(fullUrl);
        ArrayList<Video> list = new ArrayList<Video>();

        try {
            JSONObject json = new JSONObject(response);
            JSONArray items = json.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.optString("id", "");
                String title = item.optString("title", "");
                String author = item.optString("author", "");
                String thumb = item.optString("thumb", "");
                String duration = item.optString("duration", "");
                list.add(new Video(id, title, author, thumb, duration));
            }
        } catch (Exception e) {
            throw new Exception("Dữ liệu phản hồi không hợp lệ.");
        }

        return list;
    }

    private static String httpGet(String urlStr, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String detail = readErrorBody(conn);
                if (detail.length() > 0) throw new Exception(detail);
                throw new Exception("Máy chủ phản hồi lỗi " + code);
            }
            return readAll(conn.getInputStream());
        } catch (java.net.SocketTimeoutException te) {
            throw new Exception("Máy chủ đang khởi động, thử lại sau ít giây.");
        } catch (java.io.IOException ioe) {
            throw new Exception("Không thể kết nối đến máy chủ.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(java.io.InputStream is) throws java.io.IOException {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            try { r.close(); } catch (Exception ignored) {}
        }
        return sb.toString();
    }

    /** Doc thong bao loi ma may chu tra ve, uu tien truong JSON "error". */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            java.io.InputStream es = conn.getErrorStream();
            if (es == null) return "";
            String body = readAll(es);
            if (body.length() == 0) return "";
            try {
                org.json.JSONObject o = new org.json.JSONObject(body);
                String e = o.optString("error", "");
                if (e.length() > 0) return e;
            } catch (Exception ignored) {}
            return body.length() > 160 ? body.substring(0, 160) : body;
        } catch (Exception e) {
            return "";
        }
    }


    public interface Progress {
        void onProgress(int seconds);
        boolean isCancelled();
    }

    /** GET tra ve ca ma HTTP va noi dung. */
    private static String[] httpGetWithCode(String urlStr, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                return new String[]{String.valueOf(code), readAll(conn.getInputStream())};
            }
            if (code == 202) {
                return new String[]{"202", readAll(conn.getInputStream())};
            }
            String detail = readErrorBody(conn);
            throw new Exception(detail.length() > 0 ? detail : ("Máy chủ phản hồi lỗi " + code));
        } catch (java.io.IOException ioe) {
            throw new Exception("Không thể kết nối đến máy chủ.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Lấy luồng: máy chủ có thể trả 202 (đang chuẩn bị) nên phải hỏi lại tiến độ. */
    public static Stream resolve(Context c, String videoId, Progress cb) throws Exception {
        String base = baseUrl(c);
        String q = "/api/resolve?id=" + URLEncoder.encode(videoId, "UTF-8");
        String[] r = httpGetWithCode(base + q, 20000);
        if ("200".equals(r[0])) return parseStream(r[1]);

        long deadline = System.currentTimeMillis() + 8 * 60 * 1000L;
        int fails = 0;
        long startedAt = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cb != null && cb.isCancelled()) throw new Exception("Đã huỷ.");
            String[] st;
            try {
                // may chu giu ket noi toi khi xong, khong phai hoi lap
                st = httpGetWithCode(base + "/api/wait?id=" + URLEncoder.encode(videoId, "UTF-8") + "&timeout=20", 30000);
                fails = 0;
            } catch (Exception netErr) {
                if (++fails >= 4) throw netErr;
                Thread.sleep(1000);
                continue;
            }
            JSONObject o = new JSONObject(st[1]);
            if (o.optString("url", "").length() > 0) return parseStream(st[1]);
            String status = o.optString("status", "");
            if ("error".equals(status)) throw new Exception(o.optString("error", "Không lấy được video"));
            if ("unknown".equals(status)) {
                httpGetWithCode(base + q, 20000);
            }
            int secs = (int) ((System.currentTimeMillis() - startedAt) / 1000);
            if (cb != null) cb.onProgress(secs);
        }
        throw new Exception("Máy chủ chuẩn bị quá lâu, thử lại sau.");
    }

    /** Cho tới khi máy chủ có tệp đầy đủ (không phải đoạn ngắn). */
    public static String waitFull(Context c, String videoId, Progress cb) throws Exception {
        String base = baseUrl(c);
        long deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (cb != null && cb.isCancelled()) return null;
            Thread.sleep(3000);
            try {
                String[] st = httpGetWithCode(base + "/api/wait?id=" + URLEncoder.encode(videoId, "UTF-8") + "&timeout=15", 25000);
                JSONObject o = new JSONObject(st[1]);
                String url = o.optString("url", "");
                if (url.length() > 0 && url.indexOf(".fast.mp4") < 0) return url;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Hai luong roi de phat bang ExoPlayer, khong can ghep. */
    public static class Pair {
        public String videoUrl, audioUrl;
        public Pair(String v, String a) { videoUrl = v; audioUrl = a; }
    }

    public static Pair streams(Context c, String videoId) throws Exception {
        String body = httpGet(baseUrl(c) + "/api/streams?id=" + URLEncoder.encode(videoId, "UTF-8"), 20000);
        try {
            JSONObject o = new JSONObject(body);
            String v = o.optString("video", "");
            String a = o.optString("audio", "");
            if (v.length() == 0 || a.length() == 0) return null;
            return new Pair(v, a);
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    private static Stream parseStream(String body) throws Exception {
        try {
            JSONObject json = new JSONObject(body);
            String url = json.optString("url", "");
            String quality = json.optString("quality", "");
            String mime = json.optString("mime", "");
            if (url.length() == 0) throw new Exception("Máy chủ không trả về đường dẫn video.");
            if (mime.length() > 0 && mime.indexOf("mp4") < 0)
                throw new Exception("Định dạng video không phù hợp với TV.");
            return new Stream(url, quality, mime);
        } catch (org.json.JSONException je) {
            throw new Exception("Máy chủ trả về dữ liệu không đúng định dạng.");
        }
    }

    public static Stream resolve(Context c, String videoId) throws Exception {
        String base = baseUrl(c);
        String fullUrl = base + "/api/resolve?id=" + URLEncoder.encode(videoId, "UTF-8");
        String response = httpGet(fullUrl);

        try {
            JSONObject json = new JSONObject(response);
            String url = json.optString("url", "");
            String quality = json.optString("quality", "");
            String mime = json.optString("mime", "");
            if (url.length() == 0) throw new Exception("Máy chủ không trả về đường dẫn video.");
            if (mime.length() > 0 && mime.indexOf("mp4") < 0)
                throw new Exception("Định dạng video không phù hợp với TV.");
            return new Stream(url, quality, mime);
        } catch (org.json.JSONException je) {
            throw new Exception("Máy chủ trả về dữ liệu không đúng định dạng.");
        }
    }

    private static volatile String lastPrefetch = "";
    private static volatile long lastPrefetchAt = 0;

    /** Bao may chu tai truoc video dang duoc chon. Khong cho ket qua, loi thi bo qua. */
    public static void prefetch(Context c, final String videoId) {
        if (videoId == null || videoId.length() == 0) return;
        long now = System.currentTimeMillis();
        if (videoId.equals(lastPrefetch) && now - lastPrefetchAt < 60000) return;
        if (now - lastPrefetchAt < 400) return;   // dang di chuyen nhanh, dung ban qua nhieu
        lastPrefetch = videoId;
        lastPrefetchAt = now;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try { httpGetWithCode(baseUrl(app) + "/api/prefetch?id=" + URLEncoder.encode(videoId, "UTF-8"), 8000); }
                catch (Exception ignored) {}
            }
        }).start();
    }

    public static Bitmap thumb(Context c, String url, int w, int h) {
        if (url == null || url.length() == 0) return null;

        synchronized (BITMAP_CACHE) {
            Bitmap cached = BITMAP_CACHE.get(url);
            if (cached != null) {
                return cached;
            }
        }

        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp != null) {
                    synchronized (BITMAP_CACHE) {
                        BITMAP_CACHE.put(url, bmp);
                    }
                    return bmp;
                }
            }
        } catch (Throwable t) {
            // Trả về null khi có lỗi
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }
}
