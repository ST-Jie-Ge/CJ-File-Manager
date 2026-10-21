package com.cj.music;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;

/**
 * 网络位置 → 本机的落盘记录：下载到目录、下载到临时缓存都记一笔。
 * 「传输任务」页据此列出最近保存/缓存的文件，并提供「跳到所在目录」「打开此文件」两个入口。
 *
 * 只存路径与来源这类元数据，不碰口令；文件被删掉（含缓存被清理）的条目读取时静默丢弃，
 * 免得列表里躺着一堆点不开的死链接。
 */
final class TransferHistory {
    private static final String PREFS = "transfer_history";
    private static final String KEY = "items";
    /** 只留最近 30 条：这是个快捷入口，不是归档系统。 */
    private static final int LIMIT = 30;
    /** 下载（保存到目录） */
    static final String KIND_SAVED = "已保存";
    /** 临时缓存（下载并打开/分享） */
    static final String KIND_CACHED = "临时缓存";

    private TransferHistory() {}

    static final class Item {
        final String path, name, kind, source;
        final long size, time;

        Item(String path, String name, long size, String kind, String source, long time) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.kind = kind;
            this.source = source;
            this.time = time;
        }

        /** 列表去重 / Compose 侧比较用：同路径同大小同时间视为同一条。 */
        @Override public boolean equals(Object other) {
            if (!(other instanceof Item)) return false;
            Item that = (Item) other;
            return size == that.size && time == that.time && path.equals(that.path)
                    && name.equals(that.name) && kind.equals(that.kind);
        }

        @Override public int hashCode() { return path.hashCode() * 31 + (int) time; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 记一笔；同一路径重复出现时更新并提到最前（重下同名文件不会堆两条）。 */
    static synchronized void record(Context context, File file, String kind, String source) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        ArrayList<Item> items = list(context);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).path.equals(path)) items.remove(i);
        items.add(0, new Item(path, file.getName(), file.length(), kind,
                source == null ? "" : source, System.currentTimeMillis()));
        while (items.size() > LIMIT) items.remove(items.size() - 1);
        write(context, items);
    }

    /** 最近记录（新的在前）；已不存在的文件自动剔除。 */
    static synchronized ArrayList<Item> list(Context context) {
        ArrayList<Item> items = new ArrayList<>();
        String raw = prefs(context).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return items;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String path = o.optString("path", "");
                String name = o.optString("name", "");
                if (path.isEmpty() || name.isEmpty()) continue;
                File file = new File(path);
                if (!file.isFile()) continue;   // 文件没了（用户删了 / 缓存被清理）就别留着
                items.add(new Item(path, name, file.length(), o.optString("kind", KIND_SAVED),
                        o.optString("source", ""), o.optLong("time", 0L)));
            }
        } catch (Exception ignored) {
            // 记录坏了就当没有，不影响下载本身
        }
        return items;
    }

    /** 删单条（只删记录，绝不删文件）。 */
    static synchronized void remove(Context context, String path) {
        ArrayList<Item> items = list(context);
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).path.equals(path)) items.remove(i);
        write(context, items);
    }

    /** 清空记录（只清列表，文件留在原处）。 */
    static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static void write(Context context, ArrayList<Item> items) {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("path", item.path);
                o.put("name", item.name);
                o.put("size", item.size);
                o.put("kind", item.kind);
                o.put("source", item.source);
                o.put("time", item.time);
            } catch (Exception ignored) {
                continue;
            }
            array.put(o);
        }
        prefs(context).edit().putString(KEY, array.toString()).apply();
    }
}
