package com.ally.tvtube;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public final class Store {
    private static final String PREF_NAME = "tv";
    private static final String KEY_HISTORY = "store_history";
    private static final String KEY_FAVORITES = "store_favorites";
    private static final String KEY_SEARCHES = "store_searches";
    private static final int MAX_HISTORY = 60;
    private static final int MAX_FAVORITES = 200;
    private static final int MAX_SEARCHES = 12;

    public static class HistoryItem {
        public Api.Video video;
        public int posMs;
        public long updatedAt;

        public HistoryItem(Api.Video video, int posMs, long updatedAt) {
            this.video = video;
            this.posMs = posMs;
            this.updatedAt = updatedAt;
        }
    }

    private Store() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static JSONArray loadArray(Context c, String key) {
        String raw = prefs(c).getString(key, "");
        if (raw == null || raw.length() == 0) {
            return new JSONArray();
        }
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void saveArray(Context c, String key, JSONArray arr) {
        prefs(c).edit().putString(key, arr.toString()).apply();
    }

    private static Api.Video copyVideo(Api.Video video) {
        return new Api.Video(
            safe(video.id),
            safe(video.title),
            safe(video.author),
            safe(video.thumb),
            safe(video.duration)
        );
    }

    public static synchronized void addHistory(Context c, Api.Video video, int posMs) {
        if (video == null || video.id == null || video.id.length() == 0) {
            return;
        }
        if (posMs < 0) {
            posMs = 0;
        }

        ArrayList<HistoryItem> list = getHistory(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (video.id.equals(list.get(i).video.id)) {
                list.remove(i);
            }
        }

        HistoryItem item = new HistoryItem(copyVideo(video), posMs, System.currentTimeMillis());
        list.add(0, item);
        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        saveHistoryList(c, list);
    }

    public static synchronized ArrayList<HistoryItem> getHistory(Context c) {
        JSONArray arr = loadArray(c, KEY_HISTORY);
        ArrayList<HistoryItem> list = new ArrayList<HistoryItem>();

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.length() == 0) {
                    continue;
                }
                Api.Video v = new Api.Video(
                    id,
                    o.optString("title", ""),
                    o.optString("author", ""),
                    o.optString("thumb", ""),
                    ""
                );
                int posMs = o.optInt("posMs", 0);
                long updatedAt = o.optLong("updatedAt", 0L);
                list.add(new HistoryItem(v, posMs, updatedAt));
            } catch (Exception ignored) {
            }
        }

        Collections.sort(list, new Comparator<HistoryItem>() {
            @Override
            public int compare(HistoryItem a, HistoryItem b) {
                if (a.updatedAt > b.updatedAt) return -1;
                if (a.updatedAt < b.updatedAt) return 1;
                return 0;
            }
        });

        while (list.size() > MAX_HISTORY) {
            list.remove(list.size() - 1);
        }
        return list;
    }

    private static void saveHistoryList(Context c, ArrayList<HistoryItem> list) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            HistoryItem h = list.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("id", safe(h.video.id));
                o.put("title", safe(h.video.title));
                o.put("author", safe(h.video.author));
                o.put("thumb", safe(h.video.thumb));
                o.put("posMs", h.posMs);
                o.put("updatedAt", h.updatedAt);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        saveArray(c, KEY_HISTORY, arr);
    }

    public static synchronized boolean removeHistory(Context c, String id) {
        if (id == null || id.length() == 0) {
            return false;
        }

        ArrayList<HistoryItem> list = getHistory(c);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (id.equals(list.get(i).video.id)) {
                list.remove(i);
                removed = true;
            }
        }

        if (!removed) {
            return false;
        }
        saveHistoryList(c, list);
        return true;
    }

    public static synchronized boolean removeHistory(Context c, Api.Video video) {
        if (video == null) {
            return false;
        }
        return removeHistory(c, video.id);
    }

    public static synchronized boolean isFavorite(Context c, String id) {
        if (id == null || id.length() == 0) {
            return false;
        }

        JSONArray arr = loadArray(c, KEY_FAVORITES);
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (id.equals(o.optString("id", ""))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static synchronized boolean isFavorite(Context c, Api.Video video) {
        if (video == null) {
            return false;
        }
        return isFavorite(c, video.id);
    }

    public static synchronized boolean toggleFavorite(Context c, Api.Video video) {
        if (video == null || video.id == null || video.id.length() == 0) {
            return false;
        }

        JSONArray arr = loadArray(c, KEY_FAVORITES);
        int found = -1;
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (video.id.equals(o.optString("id", ""))) {
                    found = i;
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        if (found >= 0) {
            JSONArray next = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                if (i == found) {
                    continue;
                }
                try {
                    next.put(arr.getJSONObject(i));
                } catch (Exception ignored) {
                }
            }
            saveArray(c, KEY_FAVORITES, next);
            return false;
        }

        JSONArray next = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("id", safe(video.id));
            o.put("title", safe(video.title));
            o.put("author", safe(video.author));
            o.put("thumb", safe(video.thumb));
            next.put(o);
        } catch (Exception ignored) {
        }

        for (int i = 0; i < arr.length() && next.length() < MAX_FAVORITES; i++) {
            try {
                next.put(arr.getJSONObject(i));
            } catch (Exception ignored) {
            }
        }

        saveArray(c, KEY_FAVORITES, next);
        return true;
    }

    public static synchronized ArrayList<Api.Video> getFavorites(Context c) {
        JSONArray arr = loadArray(c, KEY_FAVORITES);
        ArrayList<Api.Video> list = new ArrayList<Api.Video>();

        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.length() == 0) {
                    continue;
                }
                list.add(new Api.Video(
                    id,
                    o.optString("title", ""),
                    o.optString("author", ""),
                    o.optString("thumb", ""),
                    ""
                ));
            } catch (Exception ignored) {
            }
        }

        while (list.size() > MAX_FAVORITES) {
            list.remove(list.size() - 1);
        }
        return list;
    }

    public static synchronized void addSearch(Context c, String keyword) {
        if (keyword == null) {
            return;
        }

        String q = keyword.trim();
        if (q.length() == 0) {
            return;
        }

        ArrayList<String> list = getSearches(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (q.equalsIgnoreCase(list.get(i))) {
                list.remove(i);
            }
        }

        list.add(0, q);
        while (list.size() > MAX_SEARCHES) {
            list.remove(list.size() - 1);
        }

        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            try {
                arr.put(list.get(i));
            } catch (Exception ignored) {
            }
        }
        saveArray(c, KEY_SEARCHES, arr);
    }

    public static synchronized ArrayList<String> getSearches(Context c) {
        JSONArray arr = loadArray(c, KEY_SEARCHES);
        ArrayList<String> list = new ArrayList<String>();

        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i);
            if (s != null && s.length() > 0) {
                list.add(s);
            }
        }

        while (list.size() > MAX_SEARCHES) {
            list.remove(list.size() - 1);
        }
        return list;
    }

    public static synchronized void clearAll(Context c) {
        prefs(c).edit()
            .remove(KEY_HISTORY)
            .remove(KEY_FAVORITES)
            .remove(KEY_SEARCHES)
            .apply();
    }
}
