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
        // 2026-08-01: 热句联想表，按标点分段存储，支持渐进式补全
        db.execSQL("CREATE TABLE IF NOT EXISTS hot_sentences (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "sentence TEXT NOT NULL, " +
            "digit_seq TEXT NOT NULL, " +
            "freq INTEGER NOT NULL DEFAULT 1, " +
            "updated_at INTEGER NOT NULL)");
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
        Cursor c = db.rawQuery("SELECT freq FROM phrases WHERE digit_seq=? AND phrase=?",
            new String[]{digitSeq, phrase});
        if (c.moveToFirst()) {
            int oldFreq = c.getInt(0);
            c.close();
            // 2026-07-31: 用户学习入口（组词/句子拆分）命中的词也标记为用户词
            db.execSQL("UPDATE phrases SET freq=?, source=? WHERE digit_seq=? AND phrase=?",
                new Object[]{oldFreq + delta, "user", digitSeq, phrase});
        } else {
            c.close();
            db.execSQL("INSERT INTO phrases (digit_seq, phrase, freq, source) VALUES (?, ?, ?, ?)",
                new Object[]{digitSeq, phrase, initFreq, "user"});
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
            Cursor c = db.rawQuery(
                "SELECT next_word, freq FROM ngram_adjacency WHERE context=? ORDER BY freq DESC LIMIT ?",
                new String[]{key, String.valueOf(limit)});
            while (c.moveToNext()) {
                PhraseEntry p = new PhraseEntry(c.getString(0), c.getInt(1));
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
    public List<PhraseEntry> getAllUserPhrases(int offset, int limit) {
        List<PhraseEntry> result = new ArrayList<>();
        Cursor c = db.rawQuery(
            "SELECT phrase, digit_seq, freq FROM phrases WHERE source='user' ORDER BY freq DESC LIMIT ? OFFSET ?",
            new String[]{String.valueOf(limit), String.valueOf(offset)});
        while (c.moveToNext()) {
            PhraseEntry p = new PhraseEntry(c.getString(0), c.getInt(2));
            p.digitSeq = c.getString(1);
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

    // ====== 2026-08-01: 热句联想（方案A: 前缀热句补全） ======

    /**
     * 学习热句：将完整句子存入 hot_sentences 表
     * 句子按标点分段后，每段也作为独立条目学习
     */
    public void learnHotSentence(String sentence) {
        if (sentence == null || sentence.length() < 4) return;
        // 去除首尾空白
        sentence = sentence.trim();
        if (sentence.length() < 4) return;

        // 2026-08-01: 去掉标点后计算数字序列，避免 toDigitSeq 遇到标点返回空串
        String cleanText = sentence.replaceAll("[^\\u4e00-\\u9fff]", "");
        if (cleanText.length() < 4) return;
        String digitSeq = PinyinEngine.toDigitSeq(cleanText);
        if (digitSeq.isEmpty()) return;

        long now = System.currentTimeMillis() / 1000;

        // 检查是否已存在
        Cursor c = db.rawQuery(
            "SELECT id, freq FROM hot_sentences WHERE sentence=?",
            new String[]{sentence});
        if (c.moveToFirst()) {
            int id = c.getInt(0);
            int oldFreq = c.getInt(1);
            c.close();
            db.execSQL("UPDATE hot_sentences SET freq=?, updated_at=? WHERE id=?",
                new Object[]{oldFreq + 1, now, id});
        } else {
            c.close();
            db.execSQL("INSERT INTO hot_sentences (sentence, digit_seq, freq, updated_at) VALUES (?, ?, ?, ?)",
                new Object[]{sentence, digitSeq, 1, now});
        }
    }

    /**
     * 按前缀查询热句：给定已上屏的文本，查找以此开头的热句，返回下一段
     * 返回 null 表示没有匹配的热句
     */
    public String queryHotNextSegment(String prefixText) {
        if (prefixText == null || prefixText.length() < 2) return null;
        // 去掉末尾可能存在的标点，方便匹配
        String cleanPrefix = prefixText.replaceAll("[，。？！；：、…,!.?;:]+$", "");
        if (cleanPrefix.length() < 2) return null;

        Cursor c = db.rawQuery(
            "SELECT sentence FROM hot_sentences WHERE sentence LIKE ? || '%' ORDER BY freq DESC LIMIT 1",
            new String[]{cleanPrefix});
        String result = null;
        if (c.moveToFirst()) {
            String fullSentence = c.getString(0);
            // 提取下一段：从 cleanPrefix 之后到下一个标点或句尾
            if (fullSentence.length() > cleanPrefix.length()) {
                String remaining = fullSentence.substring(cleanPrefix.length());
                // 跳过开头的标点
                remaining = remaining.replaceAll("^[，。？！；：、…,!.?;:]+", "");
                if (!remaining.isEmpty()) {
                    // 提取到下一个标点为止
                    String[] parts = remaining.split("[，。？！；：、…,!.?;:]", 2);
                    result = parts[0];
                    if (result.isEmpty()) result = null;
                }
            }
        }
        c.close();
        return result;
    }

    /**
     * 按前缀查询完整热句（用于渐进式补全）
     * 返回以 prefixText 开头的完整热句，或 null
     */
    public String queryHotSentenceFull(String prefixText) {
        if (prefixText == null || prefixText.length() < 2) return null;
        String cleanPrefix = prefixText.replaceAll("[，。？！；：、…,!.?;:]+$", "");
        if (cleanPrefix.length() < 2) return null;

        Cursor c = db.rawQuery(
            "SELECT sentence FROM hot_sentences WHERE sentence LIKE ? || '%' ORDER BY freq DESC LIMIT 1",
            new String[]{cleanPrefix});
        String result = null;
        if (c.moveToFirst()) {
            result = c.getString(0);
        }
        c.close();
        return result;
    }

    /**
     * 预置热句种子数据（首次初始化时调用）
     */
    public void seedHotSentences() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM hot_sentences", null);
        boolean hasData = false;
        if (c.moveToFirst()) hasData = c.getInt(0) > 0;
        c.close();
        if (hasData) return; // 已有数据，不重复播种

        // 预置热句
        String[] seeds = {
            "你好，我们是家家月嫂，请问有什么可以帮您",
            "好的，收到，马上处理",
            "谢谢，辛苦啦",
            "没问题，请放心",
            "不好意思，打扰了",
        };
        for (String s : seeds) {
            learnHotSentence(s);
        }
    }

    private static String[] appendArg(String[] src, String append) {
        String[] result = new String[src.length + 1];
        System.arraycopy(src, 0, result, 0, src.length);
        result[src.length] = append;
        return result;
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
        PhraseEntry(String t, int f) { text = t; frequency = f; }
    }
}
