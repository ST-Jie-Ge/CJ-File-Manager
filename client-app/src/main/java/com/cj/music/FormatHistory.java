package com.cj.music;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 格式工厂的转换历史。
 *
 * 只记成功的产物（失败的在结果卡片上已经提示过，留在历史里只会变成点不开的死条目），
 * 最多存 50 条，超出丢最旧的。存 SharedPreferences 的 JSON 数组——条目少、结构简单，
 * 不值得为它建表。
 */
final class FormatHistory {
    static final int MAX = 50;
    private static final String KEY = "history";

    private FormatHistory() {}
    static String mimeFor(String name,String fallback){
        int dot=name==null?-1:name.lastIndexOf('.');
        String ext=dot<0?"":name.substring(dot+1).toLowerCase(java.util.Locale.ROOT);
        String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime==null?fallback:mime;
    }

    static final class Item {
        final String name;   // 输出文件名
        final String path;   // 输出绝对路径
        final String from;   // 源文件名
        final String mime;   // 输出 mime（播放时要用）
        final long size;     // 产物字节数
        final long seconds;  // 转码耗时（秒）
        final long at;       // 完成时间戳（毫秒）

        Item(String name, String path, String from, String mime, long size, long seconds, long at) {
            this.name = name; this.path = path; this.from = from;
            this.mime = mime; this.size = size; this.seconds = seconds; this.at = at;
        }

        JSONObject to() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name); o.put("path", path); o.put("from", from);
                o.put("mime", mime); o.put("size", size); o.put("seconds", seconds); o.put("at", at);
            } catch (Exception ignored) { }
            return o;
        }

        static Item from(JSONObject o) {
            return new Item(o.optString("name", ""), o.optString("path", ""), o.optString("from", ""),
                    o.optString("mime", "video/*"), o.optLong("size", 0), o.optLong("seconds", 0), o.optLong("at", 0));
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("factory", Context.MODE_PRIVATE);
    }

    /** 最新的排在最前面。 */
    static List<Item> read(Context context) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(Item.from(array.getJSONObject(i)));
        } catch (Exception ignored) { }
        return out;
    }

    // Mutations are called on the UI thread by the factory completion and history UI.
    static void add(Context context, Item item) {
        List<Item> all = read(context);
        all.add(0, item);
        while (all.size() > MAX) all.remove(all.size() - 1);
        write(context, all);
    }

    static void remove(Context context, String path) {
        List<Item> all = read(context);
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).path.equals(path)) all.remove(i);
        write(context, all);
    }

    static void clear(Context context) { prefs(context).edit().remove(KEY).apply(); }

    private static void write(Context context, List<Item> items) {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) array.put(item.to());
            prefs(context).edit().putString(KEY, array.toString()).apply();
        } catch (Exception ignored) { }
    }
}
