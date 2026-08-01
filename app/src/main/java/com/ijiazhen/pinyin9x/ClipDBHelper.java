package com.ijiazhen.pinyin9x;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.*;

/**
 * 剪切板与收藏夹 SQLite 数据库管理器
 * 复用 DictDBHelper 的 SQLiteDatabase 实例
 */
public class ClipDBHelper {

    private static ClipDBHelper instance;
    private SQLiteDatabase db;

    private ClipDBHelper(Context ctx) {
        db = DictDBHelper.getInstance(ctx).getDatabase();
    }

    public static synchronized ClipDBHelper getInstance(Context ctx) {
        if (instance == null) {
            instance = new ClipDBHelper(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public static class ClipEntry {
        public long id;
        public String text;
        public long timestamp;
        ClipEntry(long i, String t, long ts) { id = i; text = t; timestamp = ts; }
    }

    public static class FavEntry {
        public long id;
        public String text;
        public long timestamp;
        public boolean pinned;
        FavEntry(long i, String t, long ts, int p) { id = i; text = t; timestamp = ts; pinned = p == 1; }
    }

    // === 剪切板 ===

    public void addClip(String text) {
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM clipboard WHERE text=?", new Object[]{text});
            db.execSQL("INSERT INTO clipboard (text, timestamp) VALUES (?, ?)",
                new Object[]{text, now});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        trimClipboard();
    }

    private void trimClipboard() {
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM clipboard", null);
            if (c.moveToFirst()) {
                int count = c.getInt(0);
                if (count > 100) {
                    db.execSQL("DELETE FROM clipboard WHERE id IN " +
                        "(SELECT id FROM clipboard ORDER BY timestamp ASC LIMIT " + (count - 100) + ")");
                }
            }
            c.close();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<ClipEntry> getAllClips() {
        List<ClipEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT id, text, timestamp FROM clipboard ORDER BY timestamp DESC", null);
        while (c.moveToNext()) {
            result.add(new ClipEntry(c.getLong(0), c.getString(1), c.getLong(2)));
        }
        c.close();
        return result;
    }

    public void deleteClip(long id) {
        db.execSQL("DELETE FROM clipboard WHERE id=?", new Object[]{id});
    }

    // 2026-08-01: 一键清空全部剪切记录
    public void deleteAllClips() {
        db.execSQL("DELETE FROM clipboard");
    }

    public ClipEntry getClip(long id) {
        Cursor c = db.rawQuery("SELECT id, text, timestamp FROM clipboard WHERE id=?", new String[]{String.valueOf(id)});
        ClipEntry entry = null;
        if (c.moveToFirst()) {
            entry = new ClipEntry(c.getLong(0), c.getString(1), c.getLong(2));
        }
        c.close();
        return entry;
    }

    // === 收藏夹 ===

    public void addFavorite(String text) {
        long now = System.currentTimeMillis();
        Cursor c = db.rawQuery("SELECT id FROM favorites WHERE text=?", new String[]{text});
        if (c.moveToFirst()) {
            c.close();
            return;
        }
        c.close();
        db.execSQL("INSERT INTO favorites (text, timestamp, pinned) VALUES (?, ?, 0)",
            new Object[]{text, now});
    }

    public List<FavEntry> getAllFavorites() {
        List<FavEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT id, text, timestamp, pinned FROM favorites ORDER BY pinned DESC, timestamp DESC", null);
        while (c.moveToNext()) {
            result.add(new FavEntry(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)));
        }
        c.close();
        return result;
    }

    public void deleteFavorite(long id) {
        db.execSQL("DELETE FROM favorites WHERE id=?", new Object[]{id});
    }

    public void togglePinFavorite(long id) {
        Cursor c = db.rawQuery("SELECT pinned FROM favorites WHERE id=?", new String[]{String.valueOf(id)});
        if (c.moveToFirst()) {
            int current = c.getInt(0);
            c.close();
            db.execSQL("UPDATE favorites SET pinned=? WHERE id=?",
                new Object[]{current == 1 ? 0 : 1, id});
        } else {
            c.close();
        }
    }

    public int getClipCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM clipboard", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public int getFavCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM favorites", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public long getDbSize() {
        // return DictDBHelper.instance != null ? DictDBHelper.instance.getDictDbSize() : 0;
        DictDBHelper helper = DictDBHelper.getInstance();
        return helper != null ? helper.getDictDbSize() : 0;
    }

    public String getDbPath() {
        // return DictDBHelper.instance != null ? DictDBHelper.instance.getDbPath() : null;
        DictDBHelper helper = DictDBHelper.getInstance();
        return helper != null ? helper.getDbPath() : null;
    }
}
