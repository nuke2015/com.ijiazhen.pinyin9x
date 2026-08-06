package com.ijiazhen.pinyin9x;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.*;
import java.util.*;

/**
 * 词库数据库管理器
 * 首次启动从 assets 复制预构建 SQLite 数据库到应用私有目录
 */
public class DictDBHelper {

    private static final String DB_NAME = "pinyin_dict.db";
    private static DictDBHelper instance;
    private SQLiteDatabase db;
    private final String dbPath;


    //新增对外获取方法
    public static DictDBHelper getInstance(){
        return instance;
    }

    private DictDBHelper(Context ctx) {
        dbPath = ctx.getDatabasePath(DB_NAME).getAbsolutePath();
        if (!new File(dbPath).exists()) {
            copyFromAssets(ctx);
        }
        db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READWRITE);
        db.execSQL("CREATE TABLE IF NOT EXISTS clipboard (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "text TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS favorites (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "text TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL, " +
            "pinned INTEGER DEFAULT 0)");
        // [移除热句功能-2026-08-05 05:27:52] 已删除 hot_sentences 建表语句（新库不再创建该表，老库数据保留）
        // 2026-08-02: N-Gram 邻接词表 — 记录词与词的上下文跟随关系
        // 例如用户输入"你好"后接着输入"我们是"，记录 (你好, 我们是, freq)
        db.execSQL("CREATE TABLE IF NOT EXISTS ngram_adjacency (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "context TEXT NOT NULL, " +      // 上文（已上屏的最后一个词）
            "next_word TEXT NOT NULL, " +    // 跟随词
            "freq INTEGER NOT NULL DEFAULT 1, " +
            "updated_at INTEGER NOT NULL, " +
            "UNIQUE(context, next_word))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ngram_context ON ngram_adjacency(context)");
        // 2026-07-31: 确保 phrases 表有 source 列，区分系统词与用户词（老库自动迁移）
        Cursor pc = db.rawQuery("PRAGMA table_info(phrases)", null);
        boolean hasSource = false;
        while (pc.moveToNext()) {
            if ("source".equals(pc.getString(1))) { hasSource = true; break; }
        }
        pc.close();
        if (!hasSource) {
            db.execSQL("ALTER TABLE phrases ADD COLUMN source TEXT NOT NULL DEFAULT 'system'");
        }
        // 2026-08-05 02:20: 确保 phrases 表有 updated_at 列，支撑热词"按最近最新"排序（老库自动迁移）
        Cursor uc = db.rawQuery("PRAGMA table_info(phrases)", null);
        boolean hasUpdatedAt = false;
        while (uc.moveToNext()) {
            if ("updated_at".equals(uc.getString(1))) { hasUpdatedAt = true; break; }
        }
        uc.close();
        if (!hasUpdatedAt) {
            db.execSQL("ALTER TABLE phrases ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
            // 老数据统一初始化为当前时间，避免排序退化为全 0
            db.execSQL("UPDATE phrases SET updated_at=?", new Object[]{System.currentTimeMillis() / 1000});
        }
    }

    public static synchronized DictDBHelper getInstance(Context ctx) {
        if (instance == null) {
            instance = new DictDBHelper(ctx.getApplicationContext());
        }
        return instance;
    }

    public static synchronized void resetInstance() {
        if (instance != null && instance.db != null) {
            instance.db.close();
        }
        instance = null;
    }

    private void copyFromAssets(Context ctx) {
        try {
            File dir = new File(dbPath).getParentFile();
            if (!dir.exists()) dir.mkdirs();
            InputStream in = ctx.getAssets().open(DB_NAME);
            FileOutputStream out = new FileOutputStream(dbPath);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();
            in.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy dictionary database", e);
        }
    }

    /**
     * 按拼音查询候选字，按频率降序
     */
    public List<CharEntry> queryCharsByPinyins(List<String> pinyins, int limit) {
        List<CharEntry> result = new ArrayList<>();
        if (pinyins.isEmpty()) return result;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < pinyins.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String sql = "SELECT hanzi, pinyin, freq FROM chars WHERE pinyin IN (" + placeholders + ") ORDER BY freq DESC LIMIT ?";
        String[] args = new String[pinyins.size() + 1];
        for (int i = 0; i < pinyins.size(); i++) args[i] = pinyins.get(i);
        args[pinyins.size()] = String.valueOf(limit);
        Cursor c = db.rawQuery(sql, args);
        while (c.moveToNext()) {
            result.add(new CharEntry(c.getString(0).charAt(0), c.getString(1), c.getInt(2)));
        }
        c.close();
        return result;
    }

    /**
     * 按数字序列查询已知词组
     */
    public List<PhraseEntry> queryPhrasesByDigitSeq(String digitSeq, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT phrase, freq FROM phrases WHERE digit_seq=? ORDER BY freq DESC LIMIT ?",
            new String[]{digitSeq, String.valueOf(limit)});
        while (c.moveToNext()) {
            result.add(new PhraseEntry(c.getString(0), c.getInt(1)));
        }
        c.close();
        return result;
    }

    // [词组前缀匹配-2026-08-05] 按数字序列前缀查询词组，使输入部分数字时也能匹配到完整词组
    // 例如输入"阿"(2)时匹配"阿姨"(294)、"阿鼻地狱"等
    public List<PhraseEntry> queryPhrasesByDigitSeqPrefix(String digitSeqPrefix, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT phrase, freq FROM phrases WHERE digit_seq LIKE ? || '%' AND digit_seq != ? ORDER BY freq DESC LIMIT ?",
            new String[]{digitSeqPrefix, digitSeqPrefix, String.valueOf(limit)});
        while (c.moveToNext()) {
            result.add(new PhraseEntry(c.getString(0), c.getInt(1)));
        }
        c.close();
        return result;
    }

    /**
     * 按多个数字序列查询词组
     */
    public List<PhraseEntry> queryPhrasesByDigitSeqs(List<String> digitSeqs, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        if (digitSeqs.isEmpty()) return result;

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < digitSeqs.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        Cursor c = db.rawQuery(
            "SELECT phrase, freq FROM phrases WHERE digit_seq IN (" + placeholders + ") ORDER BY freq DESC LIMIT ?",
            appendArg(digitSeqs.toArray(new String[0]), String.valueOf(limit)));
        while (c.moveToNext()) {
            result.add(new PhraseEntry(c.getString(0), c.getInt(1)));
        }
        c.close();
        return result;
    }

    public List<CharEntry> queryCharsByPinyin(String pinyin, int offset, int limit) {
        List<CharEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT hanzi, pinyin, freq FROM chars WHERE pinyin=? ORDER BY freq DESC LIMIT ? OFFSET ?",
            new String[]{pinyin, String.valueOf(limit), String.valueOf(offset)});
        while (c.moveToNext()) {
            result.add(new CharEntry(c.getString(0).charAt(0), c.getString(1), c.getInt(2)));
        }
        c.close();
        return result;
    }

    public int getCharCountByPinyin(String pinyin) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM chars WHERE pinyin=?", new String[]{pinyin});
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public void upsertPhrase(String digitSeq, String phrase, int delta, int initFreq) {
        long now = System.currentTimeMillis() / 1000;
        Cursor c = db.rawQuery("SELECT freq FROM phrases WHERE digit_seq=? AND phrase=?",
            new String[]{digitSeq, phrase});
        if (c.moveToFirst()) {
            int oldFreq = c.getInt(0);
            c.close();
            // 2026-07-31: 用户学习入口（组词/句子拆分）命中的词也标记为用户词
            // 2026-08-05 02:20: 命中时同步刷新 updated_at，支撑热词"按最近最新"排序
            db.execSQL("UPDATE phrases SET freq=?, source=?, updated_at=? WHERE digit_seq=? AND phrase=?",
                new Object[]{oldFreq + delta, "user", now, digitSeq, phrase});
        } else {
            c.close();
            // 2026-08-05 02:20: 新词记录创建时间 updated_at
            db.execSQL("INSERT INTO phrases (digit_seq, phrase, freq, source, updated_at) VALUES (?, ?, ?, ?, ?)",
                new Object[]{digitSeq, phrase, initFreq, "user", now});
        }
    }

    public void upsertPhrase(String digitSeq, String phrase) {
        upsertPhrase(digitSeq, phrase, 50, 300);
    }

    public void hotUpsert(String digitSeq, String phrase) {
        upsertPhrase(digitSeq, phrase, 200, 500);
    }

    /**
     * 2026-08-02: 查询短语是否存在（用于 FMM 分词）
     * 返回 true 表示该短语在词典中存在
     */
    public boolean phraseExists(String phrase) {
        Cursor c = db.rawQuery(
            "SELECT 1 FROM phrases WHERE phrase=? LIMIT 1",
            new String[]{phrase});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    /**
     * 2026-08-02: N-Gram 邻接学习 — 记录"上文→跟随词"共现关系
     * 例如用户先输入"你好"，再输入"我们是"，记录 (你好 → 我们是, freq+1)
     * @param context  上一个上屏的词/字
     * @param nextWord 本次上屏的词/字
     */
    public void learnAdjacency(String context, String nextWord) {
        if (context == null || context.isEmpty() || nextWord == null || nextWord.isEmpty()) return;
        // 只学习中文上下文
        if (!isAllChineseText(context)) return;

        long now = System.currentTimeMillis() / 1000;
        Cursor c = db.rawQuery(
            "SELECT freq FROM ngram_adjacency WHERE context=? AND next_word=?",
            new String[]{context, nextWord});
        if (c.moveToFirst()) {
            int oldFreq = c.getInt(0);
            c.close();
            db.execSQL("UPDATE ngram_adjacency SET freq=?, updated_at=? WHERE context=? AND next_word=?",
                new Object[]{oldFreq + 1, now, context, nextWord});
        } else {
            c.close();
            db.execSQL("INSERT INTO ngram_adjacency (context, next_word, freq, updated_at) VALUES (?, ?, ?, ?)",
                new Object[]{context, nextWord, 1, now});
        }
    }

    /**
     * 2026-08-02: 查询邻接词 — 给定上文，返回最常跟随的词组列表
     * 支持多级回溯：先精确匹配完整上文，没有则用末尾2字、末尾1字回退
     * @param context  已上屏的文本（取末尾1-4字作为查询key）
     * @param limit    返回条数
     */
    public List<PhraseEntry> queryAdjacency(String context, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        if (context == null || context.isEmpty()) return result;
        if (!isAllChineseText(context)) return result;

        // 多级回溯：4字 → 3字 → 2字 → 1字
        for (int len = Math.min(4, context.length()); len >= 1; len--) {
            String key = context.substring(context.length() - len);
            // [排序调整-2026-08-05] 改为最近优先(updated_at DESC)，频次次之(freq DESC)
            Cursor c = db.rawQuery(
                "SELECT next_word, freq, updated_at FROM ngram_adjacency WHERE context=? ORDER BY updated_at DESC, freq DESC LIMIT ?",
                new String[]{key, String.valueOf(limit)});
            while (c.moveToNext()) {
                PhraseEntry p = new PhraseEntry(c.getString(0), c.getInt(1));
                p.updatedAt = c.getLong(2);
                result.add(p);
            }
            c.close();
            if (!result.isEmpty()) break; // 命中就不再回退
        }
        return result;
    }

    /**
     * 2026-08-02: 判断字符串是否全中文
     */
    private boolean isAllChineseText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x4e00 || c > 0x9fff) return false;
        }
        return true;
    }

    /**
     * 2026-08-02: 正向最大匹配（FMM）分词
     * 利用已有 phrases 词典，将句子切分为有意义的词组
     * 连续未匹配单字合并为"新词候选"（如"育婴师"→"育婴师"）
     */
    public List<String> segmentByFMM(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        int pos = 0;
        int maxWordLen = 6;
        StringBuilder unknownRun = new StringBuilder(); // 连续未匹配单字缓冲

        while (pos < text.length()) {
            int matchedLen = 0;
            for (int len = Math.min(maxWordLen, text.length() - pos); len >= 2; len--) {
                String sub = text.substring(pos, pos + len);
                if (phraseExists(sub)) {
                    matchedLen = len;
                    // 先把之前积累的连续单字作为一个新词输出
                    if (unknownRun.length() >= 2) {
                        result.add(unknownRun.toString());
                    } else if (unknownRun.length() == 1) {
                        result.add(unknownRun.toString());
                    }
                    unknownRun.setLength(0);
                    result.add(sub);
                    break;
                }
            }
            if (matchedLen == 0) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    // 数字合并到连续单字缓冲（如"预留2周"中"2"归入未知段）
                    unknownRun.append(c);
                } else {
                    unknownRun.append(c);
                }
                pos++;
            } else {
                pos += matchedLen;
            }
        }
        // 收尾：把最后积累的连续单字作为一个新词输出
        if (unknownRun.length() >= 2) {
            result.add(unknownRun.toString());
        } else if (unknownRun.length() == 1) {
            result.add(unknownRun.toString());
        }
        return result;
    }

    /**
     * 查汉字的拼音
     */
    public String getPinyinForChar(char hanzi) {
        Cursor c = db.rawQuery(
            "SELECT pinyin FROM chars WHERE hanzi=? ORDER BY freq DESC LIMIT 1",
            new String[]{String.valueOf(hanzi)});
        String result = null;
        if (c.moveToFirst()) result = c.getString(0);
        c.close();
        return result;
    }

    public List<PhraseEntry> getAllPhrases(int offset, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT phrase, digit_seq, freq FROM phrases ORDER BY freq DESC LIMIT ? OFFSET ?",
            new String[]{String.valueOf(limit), String.valueOf(offset)});
        while (c.moveToNext()) {
            PhraseEntry p = new PhraseEntry(c.getString(0), c.getInt(2));
            p.digitSeq = c.getString(1);
            result.add(p);
        }
        c.close();
        return result;
    }

    public int getPhraseCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    // 2026-07-31: 仅查询用户词（组词/句子拆分产生的词），系统预置词不在此列
    // 2026-08-05 02:20: 排序改为按最近更新优先，rowid 作次级键保证老数据顺序稳定
    public List<PhraseEntry> getAllUserPhrases(int offset, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT phrase, digit_seq, freq, updated_at FROM phrases WHERE source='user' " +
            "ORDER BY updated_at DESC, rowid DESC LIMIT ? OFFSET ?",
            new String[]{String.valueOf(limit), String.valueOf(offset)});
        while (c.moveToNext()) {
            PhraseEntry p = new PhraseEntry(c.getString(0), c.getInt(2));
            p.digitSeq = c.getString(1);
            p.updatedAt = c.getLong(3);
            result.add(p);
        }
        c.close();
        return result;
    }

    public int getUserPhraseCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases WHERE source='user'", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    public void deletePhrase(String digitSeq, String phrase) {
        db.execSQL("DELETE FROM phrases WHERE digit_seq=? AND phrase=?",
            new Object[]{digitSeq, phrase});
    }

    public long getDictDbSize() {
        File f = new File(dbPath);
        return f.exists() ? f.length() : 0;
    }

    public String getDbPath() {
        return dbPath;
    }

    public SQLiteDatabase getDatabase() {
        return db;
    }

    // [移除热句功能-2026-08-05 05:27:52] 已删除 learnHotSentence / queryHotNextSegment / queryHotSentenceFull / seedHotSentences 共4个热句方法

    // ====== 2026-08-05 02:50: N-Gram 邻接词管理 ======

    /**
     * 分页查询全部邻接词，按最近更新排序（updated_at 为主键，rowid 兜底保证顺序稳定）
     */
    public List<AdjacencyEntry> getAllAdjacency(int offset, int limit) {
        List<AdjacencyEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT id, context, next_word, freq, updated_at FROM ngram_adjacency " +
            "ORDER BY updated_at DESC, rowid DESC LIMIT ? OFFSET ?",
            new String[]{String.valueOf(limit), String.valueOf(offset)});
        while (c.moveToNext()) {
            result.add(new AdjacencyEntry(c.getInt(0), c.getString(1), c.getString(2),
                c.getInt(3), c.getLong(4)));
        }
        c.close();
        return result;
    }

    public int getAdjacencyCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM ngram_adjacency", null);
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /**
     * 统计跟随词字数 >= minLen 的邻接词条数
     */
    public int getAdjacencyCountByMinLen(int minLen) {
        Cursor c = db.rawQuery(
            "SELECT COUNT(*) FROM ngram_adjacency WHERE length(next_word) >= ?",
            new String[]{String.valueOf(minLen)});
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }

    /**
     * 单条删除邻接词记录
     */
    public void deleteAdjacencyById(int id) {
        db.execSQL("DELETE FROM ngram_adjacency WHERE id=?", new Object[]{id});
    }

    /**
     * 批量删除跟随词字数 >= minLen 的邻接词，返回删除条数
     */
    public int deleteAdjacencyByMinLen(int minLen) {
        int n = getAdjacencyCountByMinLen(minLen);
        db.execSQL("DELETE FROM ngram_adjacency WHERE length(next_word) >= ?",
            new Object[]{minLen});
        return n;
    }

    // ====== [新增多种清理模式-2026-08-05 05:27:52] ======
    // 意图：为邻接词/热词管理页提供多种批量清理方式，返回删除条数供 UI 反馈。

    /** 邻接词：删除 freq <= maxFreq 的记录，返回删除条数 */
    public int deleteAdjacencyByMaxFreq(int maxFreq) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM ngram_adjacency WHERE freq <= ?",
            new String[]{String.valueOf(maxFreq)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.execSQL("DELETE FROM ngram_adjacency WHERE freq <= ?", new Object[]{maxFreq});
        return n;
    }

    /** 邻接词：删除 updated_at 早于 days 天前的记录，返回删除条数 */
    public int deleteAdjacencyBeforeDays(int days) {
        long cutoff = (System.currentTimeMillis() / 1000) - (long) days * 24 * 3600;
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM ngram_adjacency WHERE updated_at < ?",
            new String[]{String.valueOf(cutoff)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.execSQL("DELETE FROM ngram_adjacency WHERE updated_at < ?", new Object[]{cutoff});
        return n;
    }

    /** 热词(用户词)：删除 freq <= maxFreq 的用户词，返回删除条数 */
    public int deleteUserPhraseByMaxFreq(int maxFreq) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases WHERE source='user' AND freq <= ?",
            new String[]{String.valueOf(maxFreq)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.execSQL("DELETE FROM phrases WHERE source='user' AND freq <= ?", new Object[]{maxFreq});
        return n;
    }

    /** 热词(用户词)：删除 updated_at 早于 days 天前的用户词，返回删除条数 */
    public int deleteUserPhraseBeforeDays(int days) {
        long cutoff = (System.currentTimeMillis() / 1000) - (long) days * 24 * 3600;
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases WHERE source='user' AND updated_at < ?",
            new String[]{String.valueOf(cutoff)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.execSQL("DELETE FROM phrases WHERE source='user' AND updated_at < ?", new Object[]{cutoff});
        return n;
    }

    /** 热词(用户词)：删除 length(phrase) >= minLen 的用户词，返回删除条数 */
    public int deleteUserPhraseByMinLen(int minLen) {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases WHERE source='user' AND length(phrase) >= ?",
            new String[]{String.valueOf(minLen)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        db.execSQL("DELETE FROM phrases WHERE source='user' AND length(phrase) >= ?", new Object[]{minLen});
        return n;
    }

    private static String[] appendArg(String[] src, String append) {
        String[] result = new String[src.length + 1];
        System.arraycopy(src, 0, result, 0, src.length);
        result[src.length] = append;
        return result;
    }

    // ====== [新增手动增量还原-2026-08-05 05:27:52] ======
    // 意图：从用户选择的备份库中合并用户数据到本地库，不覆盖本地数据库文件。
    // 合并策略：phrases(用户词) 频率取较大、updated_at 取较新；
    //           ngram_adjacency(邻接词) 频率相加、updated_at 取较新；
    //           clipboard/favorites 按 text 去重，本地没有的才插入。
    // 全程在一个事务内完成，返回各表合并条数的报告字符串。
    public String mergeFromBackup(SQLiteDatabase backupDb) {
        int mergedPhrases = 0, mergedAdj = 0, mergedClip = 0, mergedFav = 0;

        db.beginTransaction();
        try {
            // 1. phrases(用户词) — 仅合并备份中 source='user' 的词
            if (tableHasColumn(backupDb, "phrases", "source")) {
                Cursor bc = backupDb.rawQuery(
                    "SELECT digit_seq, phrase, freq, updated_at FROM phrases WHERE source='user'", null);
                long now = System.currentTimeMillis() / 1000;
                while (bc.moveToNext()) {
                    String digitSeq = bc.getString(0);
                    String phrase = bc.getString(1);
                    int bFreq = bc.getInt(2);
                    long bUpdatedAt = bc.isNull(3) ? now : bc.getLong(3);

                    Cursor lc = db.rawQuery(
                        "SELECT freq, updated_at FROM phrases WHERE digit_seq=? AND phrase=?",
                        new String[]{digitSeq, phrase});
                    if (lc.moveToFirst()) {
                        int lFreq = lc.getInt(0);
                        long lUpdatedAt = lc.isNull(1) ? now : lc.getLong(1);
                        lc.close();
                        int maxFreq = Math.max(lFreq, bFreq);
                        long maxUpdatedAt = Math.max(lUpdatedAt, bUpdatedAt);
                        db.execSQL("UPDATE phrases SET freq=?, updated_at=? WHERE digit_seq=? AND phrase=?",
                            new Object[]{maxFreq, maxUpdatedAt, digitSeq, phrase});
                    } else {
                        lc.close();
                        db.execSQL("INSERT INTO phrases (digit_seq, phrase, freq, source, updated_at) VALUES (?, ?, ?, 'user', ?)",
                            new Object[]{digitSeq, phrase, bFreq, bUpdatedAt});
                    }
                    mergedPhrases++;
                }
                bc.close();
            }

            // 2. ngram_adjacency(邻接词) — 频率相加、updated_at 取较新
            if (tableExists(backupDb, "ngram_adjacency")) {
                Cursor bc = backupDb.rawQuery(
                    "SELECT context, next_word, freq, updated_at FROM ngram_adjacency", null);
                long now = System.currentTimeMillis() / 1000;
                while (bc.moveToNext()) {
                    String context = bc.getString(0);
                    String nextWord = bc.getString(1);
                    int bFreq = bc.getInt(2);
                    long bUpdatedAt = bc.isNull(3) ? now : bc.getLong(3);

                    Cursor lc = db.rawQuery(
                        "SELECT freq, updated_at FROM ngram_adjacency WHERE context=? AND next_word=?",
                        new String[]{context, nextWord});
                    if (lc.moveToFirst()) {
                        int lFreq = lc.getInt(0);
                        long lUpdatedAt = lc.isNull(1) ? now : lc.getLong(1);
                        lc.close();
                        int sumFreq = lFreq + bFreq;
                        long maxUpdatedAt = Math.max(lUpdatedAt, bUpdatedAt);
                        db.execSQL("UPDATE ngram_adjacency SET freq=?, updated_at=? WHERE context=? AND next_word=?",
                            new Object[]{sumFreq, maxUpdatedAt, context, nextWord});
                    } else {
                        lc.close();
                        db.execSQL("INSERT INTO ngram_adjacency (context, next_word, freq, updated_at) VALUES (?, ?, ?, ?)",
                            new Object[]{context, nextWord, bFreq, bUpdatedAt});
                    }
                    mergedAdj++;
                }
                bc.close();
            }

            // 3. clipboard(剪切记录) — 按 text 去重，本地没有的插入
            if (tableExists(backupDb, "clipboard")) {
                Cursor bc = backupDb.rawQuery("SELECT text, timestamp FROM clipboard", null);
                while (bc.moveToNext()) {
                    String text = bc.getString(0);
                    long ts = bc.getLong(1);
                    Cursor lc = db.rawQuery("SELECT 1 FROM clipboard WHERE text=?", new String[]{text});
                    if (!lc.moveToFirst()) {
                        lc.close();
                        db.execSQL("INSERT INTO clipboard (text, timestamp) VALUES (?, ?)",
                            new Object[]{text, ts});
                        mergedClip++;
                    } else {
                        lc.close();
                    }
                }
                bc.close();
            }

            // 4. favorites(收藏) — 按 text 去重，本地没有的插入
            if (tableExists(backupDb, "favorites")) {
                Cursor bc = backupDb.rawQuery("SELECT text, timestamp FROM favorites", null);
                while (bc.moveToNext()) {
                    String text = bc.getString(0);
                    long ts = bc.getLong(1);
                    Cursor lc = db.rawQuery("SELECT 1 FROM favorites WHERE text=?", new String[]{text});
                    if (!lc.moveToFirst()) {
                        lc.close();
                        db.execSQL("INSERT INTO favorites (text, timestamp, pinned) VALUES (?, ?, 0)",
                            new Object[]{text, ts});
                        mergedFav++;
                    } else {
                        lc.close();
                    }
                }
                bc.close();
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return "增量还原完成:\n用户词 +" + mergedPhrases +
            "\n邻接词 +" + mergedAdj +
            "\n剪切记录 +" + mergedClip +
            "\n收藏 +" + mergedFav;
    }

    // [新增手动增量还原-2026-08-05 05:27:52] 判断备份库中某表是否存在
    private boolean tableExists(SQLiteDatabase backupDb, String tableName) {
        Cursor c = backupDb.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{tableName});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    // [新增手动增量还原-2026-08-05 05:27:52] 判断备份库某表是否有指定列（兼容老库）
    private boolean tableHasColumn(SQLiteDatabase backupDb, String tableName, String columnName) {
        Cursor c = backupDb.rawQuery("PRAGMA table_info(" + tableName + ")", null);
        boolean has = false;
        while (c.moveToNext()) {
            if (columnName.equals(c.getString(1))) { has = true; break; }
        }
        c.close();
        return has;
    }

    public static class CharEntry {
        public char character;
        public String pinyin;
        public int frequency;
        CharEntry(char c, String p, int f) { character = c; pinyin = p; frequency = f; }
    }

    public static class PhraseEntry {
        public String text;
        public String digitSeq;
        public int frequency;
        public long updatedAt; // 2026-08-05: 最近学习时间，支撑热词排序
        PhraseEntry(String t, int f) { text = t; frequency = f; }
    }

    /**
     * 2026-08-05 02:50: N-Gram 邻接词记录
     */
    public static class AdjacencyEntry {
        public int id;
        public String context;
        public String nextWord;
        public int freq;
        public long updatedAt;
        AdjacencyEntry(int id, String context, String nextWord, int freq, long updatedAt) {
            this.id = id;
            this.context = context;
            this.nextWord = nextWord;
            this.freq = freq;
            this.updatedAt = updatedAt;
        }
    }
}
