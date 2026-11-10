package com.ying.iptv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 本地偏好：直播源列表、当前源、上次频道、收藏、启动行为。 */
public class Prefs {

    private static final String FILE = "iptv_prefs";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_CURRENT = "current_source";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_REMEMBER = "remember";
    private static final String KEY_NEED_REFRESH = "need_refresh";

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 轻量对象，SharedPreferences 本身由框架缓存，无需自建单例。 */
    public static Prefs get(Context context) {
        return new Prefs(context);
    }

    // ---------------- 直播源 ----------------

    public List<Source> getSources() {
        List<Source> out = new ArrayList<>();
        String raw = sp.getString(KEY_SOURCES, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    if (url.isEmpty()) continue;
                    out.add(new Source(o.optString("name", ""), url));
                }
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    public void saveSources(List<Source> sources) {
        JSONArray arr = new JSONArray();
        for (Source s : sources) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", s.name == null ? "" : s.name);
                o.put("url", s.url == null ? "" : s.url);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        sp.edit().putString(KEY_SOURCES, arr.toString()).apply();
    }

    public int getCurrentSource() {
        int index = sp.getInt(KEY_CURRENT, 0);
        List<Source> sources = getSources();
        if (index < 0 || index >= sources.size()) index = 0;
        return index;
    }

    public void setCurrentSource(int index) {
        sp.edit().putInt(KEY_CURRENT, index).apply();
    }

    /** 当前源；尚未配置任何源时返回 null，调用方需判空。 */
    public Source currentSource() {
        List<Source> sources = getSources();
        if (sources.isEmpty()) return null;
        return sources.get(getCurrentSource());
    }

    public void updateCurrentSource(Source source) {
        List<Source> sources = getSources();
        int index = getCurrentSource();
        sources.set(index, source);
        saveSources(sources);
    }

    public void addSource(Source source) {
        List<Source> sources = getSources();
        sources.add(source);
        saveSources(sources);
        setCurrentSource(sources.size() - 1);
    }

    public void removeCurrentSource() {
        List<Source> sources = getSources();
        if (sources.size() <= 1) return;
        sources.remove(getCurrentSource());
        saveSources(sources);
        setCurrentSource(0);
    }

    // ---------------- 播放记忆 ----------------

    /** 返回上次播放的频道地址，未开启记忆时返回 null。 */
    public String getLastUrl() {
        if (!isRemember()) return null;
        return sp.getString(KEY_LAST_URL, null);
    }

    public void setLastUrl(String url) {
        sp.edit().putString(KEY_LAST_URL, url).apply();
    }

    public boolean isRemember() {
        return sp.getBoolean(KEY_REMEMBER, true);
    }

    public void setRemember(boolean remember) {
        sp.edit().putBoolean(KEY_REMEMBER, remember).apply();
    }

    // ---------------- 收藏 ----------------

    public Set<String> getFavorites() {
        return new HashSet<>(sp.getStringSet(KEY_FAVORITES, new HashSet<>()));
    }

    public boolean isFavorite(String key) {
        return sp.getStringSet(KEY_FAVORITES, new HashSet<>()).contains(key);
    }

    /** 切换收藏状态，返回切换后是否为收藏。 */
    public boolean toggleFavorite(String key) {
        Set<String> set = new HashSet<>(sp.getStringSet(KEY_FAVORITES, new HashSet<>()));
        boolean added;
        if (set.contains(key)) {
            set.remove(key);
            added = false;
        } else {
            set.add(key);
            added = true;
        }
        sp.edit().putStringSet(KEY_FAVORITES, set).apply();
        return added;
    }

    // ---------------- 刷新标记 ----------------

    public boolean needRefresh() {
        return sp.getBoolean(KEY_NEED_REFRESH, false);
    }

    public void setNeedRefresh(boolean need) {
        sp.edit().putBoolean(KEY_NEED_REFRESH, need).apply();
    }
}
