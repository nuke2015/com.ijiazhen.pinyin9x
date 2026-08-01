package com.ijiazhen.pinyin9x;

import android.content.Context;
import java.util.*;

/**
 * 九宫格拼音引擎
 * 音节表常量，字词通过 DictDBHelper 从 SQLite 查询
 */
public class PinyinEngine {

    private static DictDBHelper dbHelper;

    // 字母到数字键映射
    private static final int[] LETTER_TO_DIGIT = new int[26];
    static {
        String[] groups = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
        for (int d = 2; d <= 9; d++) {
            for (char c : groups[d].toCharArray()) {
                LETTER_TO_DIGIT[c - 'a'] = d;
            }
        }
    }

    // 数字序列 -> 拼音音节列表
    private static final Map<String, List<String>> DIGIT_TO_SYL = new HashMap<>();

    // 完整拼音音节表 (415条)
    private static final String[] SYLLABLES = {
        "a","ai","an","ang","ao",
        "ba","bai","ban","bang","bao","bei","ben","beng","bi","bian","biao","bie","bin","bing","bo","bu",
        "ca","cai","can","cang","cao","ce","cen","ceng","cha","chai","chan","chang","chao","che","chen","cheng","chi","chong","chou","chu","chua","chuai","chuan","chuang","chui","chun","chuo","ci","cong","cou","cu","cuan","cui","cun","cuo",
        "da","dai","dan","dang","dao","de","dei","den","deng","di","dian","diao","die","ding","diu","dong","dou","du","duan","dui","dun","duo",
        "e","ei","en","eng","er",
        "fa","fan","fang","fei","fen","feng","fo","fou","fu",
        "ga","gai","gan","gang","gao","ge","gei","gen","geng","gong","gou","gu","gua","guai","guan","guang","gui","gun","guo",
        "ha","hai","han","hang","hao","he","hei","hen","heng","hong","hou","hu","hua","huai","huan","huang","hui","hun","huo",
        "ji","jia","jian","jiang","jiao","jie","jin","jing","jiong","jiu","ju","juan","jue","jun",
        "ka","kai","kan","kang","kao","ke","ken","keng","kong","kou","ku","kua","kuai","kuan","kuang","kui","kun","kuo",
        "la","lai","lan","lang","lao","le","lei","leng","li","lia","lian","liang","liao","lie","lin","ling","liu","long","lou","lu","luan","lun","luo","lv","lve",
        "m","ma","mai","man","mang","mao","me","mei","men","meng","mi","mian","miao","mie","min","ming","miu","mo","mou","mu",
        "n","na","nai","nan","nang","nao","ne","nei","nen","neng","ng","ni","nian","niang","niao","nie","nin","ning","niu","nong","nou","nu","nuan","nuo","nv","nve",
        "o","ou",
        "pa","pai","pan","pang","pao","pei","pen","peng","pi","pian","piao","pie","pin","ping","po","pou","pu",
        "qi","qia","qian","qiang","qiao","qie","qin","qing","qiong","qiu","qu","quan","que","qun",
        "ran","rang","rao","re","ren","reng","ri","rong","rou","ru","rua","ruan","rui","run","ruo",
        "sa","sai","san","sang","sao","se","sen","seng","sha","shai","shan","shang","shao","she","shei","shen","sheng","shi","shou","shu","shua","shuai","shuan","shuang","shui","shun","shuo","si","song","sou","su","suan","sui","sun","suo",
        "ta","tai","tan","tang","tao","te","teng","ti","tian","tiao","tie","ting","tong","tou","tu","tuan","tui","tun","tuo",
        "wa","wai","wan","wang","wei","wen","weng","wo","wu",
        "xi","xia","xian","xiang","xiao","xie","xin","xing","xiong","xiu","xu","xuan","xue","xun",
        "ya","yan","yang","yao","ye","yi","yin","ying","yo","yong","you","yu","yuan","yue","yun",
        "za","zai","zan","zang","zao","ze","zei","zen","zeng","zha","zhai","zhan","zhang","zhao","zhe","zhei","zhen","zheng","zhi","zhong","zhou","zhu","zhua","zhuai","zhuan","zhuang","zhui","zhun","zhuo","zi","zong","zou","zu","zuan","zui","zun","zuo"
    };

    static {
        for (String syl : SYLLABLES) {
            String digits = toDigits(syl);
            if (!DIGIT_TO_SYL.containsKey(digits)) {
                DIGIT_TO_SYL.put(digits, new ArrayList<String>());
            }
            DIGIT_TO_SYL.get(digits).add(syl);
        }
    }

    static class Candidate {
        public String text;
        public String pinyin;
        public String type;
        public double score;
        public Candidate(String t, String p, String ty, double s) {
            text = t; pinyin = p; type = ty; score = s;
        }
    }

    private static String toDigits(String syl) {
        StringBuilder sb = new StringBuilder();
        for (char c : syl.toCharArray()) {
            int idx = c - 'a';
            if (idx >= 0 && idx < 26) sb.append(LETTER_TO_DIGIT[idx]);
        }
        return sb.toString();
    }

    public static void init(Context ctx) {
        dbHelper = DictDBHelper.getInstance(ctx);
    }

    /**
     * 获取数字串的候选列表
     */
    public static List<Candidate> getCandidates(String digitStr) {
        List<Candidate> results = new ArrayList<>();
        if (dbHelper == null || digitStr.isEmpty()) return results;

        // 1. 已知词组查询
        List<DictDBHelper.PhraseEntry> phrases = dbHelper.queryPhrasesByDigitSeq(digitStr, 50);
        for (DictDBHelper.PhraseEntry p : phrases) {
            results.add(new Candidate(p.text, digitStr, "known", p.frequency * 10));
        }

        // 2. DP 分段候选
        List<List<String>> segmentations = segment(digitStr);
        // 仅唯一分词路径时生成多音节组合，歧义路径交给三步上栏
        boolean uniqueSeg = segmentations.size() == 1;
        for (List<String> segs : segmentations) {
            List<DictDBHelper.CharEntry> chars = dbHelper.queryCharsByPinyins(segs, 300);
            Map<String, DictDBHelper.CharEntry> charMap = new HashMap<>();
            for (DictDBHelper.CharEntry ce : chars) {
                String key = ce.pinyin;
                if (!charMap.containsKey(key) || charMap.get(key).frequency < ce.frequency) {
                    charMap.put(key, ce);
                }
            }

            if (segs.size() <= 4) {
                List<List<DictDBHelper.CharEntry>> posChars = new ArrayList<>();
                for (String syl : segs) {
                    List<DictDBHelper.CharEntry> list = new ArrayList<>();
                    for (DictDBHelper.CharEntry ce : chars) {
                        if (ce.pinyin.equals(syl)) list.add(ce);
                    }
                    if (list.isEmpty()) {
                        list.add(new DictDBHelper.CharEntry(syl.charAt(0), syl, 0));
                    }
                    posChars.add(list);
                }
                if (segs.size() == 1) {
                    int rank = 0;
                    for (DictDBHelper.CharEntry ce : posChars.get(0)) {
                        if (rank >= 50) break;
                        results.add(new Candidate(String.valueOf(ce.character), ce.pinyin, "single", ce.frequency * 3));
                        rank++;
                    }
                } else if (uniqueSeg) {
                    List<String> combos = new ArrayList<>();
                    generateCombinations(posChars, 0, new StringBuilder(), combos, 50);
                    for (String combo : combos) {
                        results.add(new Candidate(combo, String.join("+", segs), "phrase", segs.size() * segs.size()));
                    }
                }
            }
        }

        // 3. 前缀/部分匹配 - 查以该数字串开头的所有拼音对应的字
        List<String> prefixPinyins = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : DIGIT_TO_SYL.entrySet()) {
            if (e.getKey().startsWith(digitStr) && e.getKey().length() > digitStr.length()) {
                for (String syl : e.getValue()) {
                    if (!prefixPinyins.contains(syl)) prefixPinyins.add(syl);
                }
            }
        }
        if (!prefixPinyins.isEmpty() && prefixPinyins.size() <= 30) {
            List<DictDBHelper.CharEntry> prefixChars = dbHelper.queryCharsByPinyins(prefixPinyins, 30);
            for (DictDBHelper.CharEntry ce : prefixChars) {
                results.add(new Candidate(String.valueOf(ce.character), ce.pinyin, "partial", ce.frequency * 0.3));
            }
        }

        // 排序去重
        Map<String, Candidate> seen = new LinkedHashMap<>();
        results.sort((a, b) -> Double.compare(b.score, a.score));
        for (Candidate c : results) {
            if (!seen.containsKey(c.text)) {
                seen.put(c.text, c);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * DP 拼音分段 - segLen² 加权
     */
    private static List<List<String>> segment(String digitStr) {
        int n = digitStr.length();
        if (n == 0) return new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<List<String>>[] dp = new List[n + 1];
        double[] best = new double[n + 1];
        for (int i = 1; i <= n; i++) best[i] = -1;
        best[0] = 0;
        dp[0] = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                String sub = digitStr.substring(j, i);
                List<String> syls = DIGIT_TO_SYL.get(sub);
                if (syls != null && best[j] >= 0) {
                    double score = best[j] + (sub.length() * sub.length());
                    if (score > best[i]) {
                        best[i] = score;
                        List<List<String>> newDp = new ArrayList<>();
                        if (dp[j].isEmpty()) {
                            for (String syl : syls) {
                                List<String> single = new ArrayList<>();
                                single.add(syl);
                                newDp.add(single);
                            }
                        } else {
                            for (List<String> prefix : dp[j]) {
                                for (String syl : syls) {
                                    List<String> combined = new ArrayList<>(prefix);
                                    combined.add(syl);
                                    newDp.add(combined);
                                }
                            }
                        }
                        dp[i] = newDp;
                    }
                }
            }
        }

        // 前缀匹配
        if (dp[n] == null || dp[n].isEmpty()) {
            for (Map.Entry<String, List<String>> e : DIGIT_TO_SYL.entrySet()) {
                if (e.getKey().startsWith(digitStr) && e.getKey().length() > digitStr.length()) {
                    if (dp[n] == null) dp[n] = new ArrayList<>();
                    for (String syl : e.getValue()) {
                        List<String> seg = new ArrayList<>();
                        seg.add(syl);
                        dp[n].add(seg);
                    }
                }
            }
        }

        return dp[n] != null ? dp[n] : new ArrayList<List<String>>();
    }

    private static void generateCombinations(List<List<DictDBHelper.CharEntry>> posChars, int idx,
                                              StringBuilder prefix, List<String> results, int limit) {
        if (results.size() >= limit) return;
        if (idx == posChars.size()) {
            results.add(prefix.toString());
            return;
        }
        int maxPerPos = Math.min(6, posChars.get(idx).size());
        for (int i = 0; i < maxPerPos; i++) {
            int len = prefix.length();
            prefix.append(posChars.get(idx).get(i).character);
            generateCombinations(posChars, idx + 1, prefix, results, limit);
            prefix.setLength(len);
        }
    }

    /**
     * 获取最佳分段的拼音选项（只返回每个位置的可能拼音，不拼接组合）
     * 返回 null 表示无法完全分段
     */
    public static List<List<String>> getBestSegmentedPinyins(String digitStr) {
        int n = digitStr.length();
        int[] best = new int[n + 1];
        @SuppressWarnings("unchecked")
        List<String>[] dpSegments = new List[n + 1];

        for (int i = 1; i <= n; i++) best[i] = -1;
        best[0] = 0;
        dpSegments[0] = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                String sub = digitStr.substring(j, i);
                if (DIGIT_TO_SYL.containsKey(sub) && best[j] >= 0) {
                    int score = best[j] + sub.length() * sub.length();
                    if (score > best[i]) {
                        best[i] = score;
                        dpSegments[i] = new ArrayList<>(dpSegments[j]);
                        dpSegments[i].add(sub);
                    }
                }
            }
        }

        if (best[n] < 0) return null;

        List<List<String>> result = new ArrayList<>();
        for (String seg : dpSegments[n]) {
            result.add(DIGIT_TO_SYL.get(seg));
        }
        return result;
    }

    public static List<List<String>> getPinyinSequences(String digitStr) {
        List<List<String>> results = new ArrayList<>();
        enumerateSequences(digitStr, 0, new ArrayList<String>(), results, 30);
        results.sort((a, b) -> Integer.compare(a.size(), b.size()));
        return results;
    }

    private static void enumerateSequences(String digits, int pos,
                                            List<String> current, List<List<String>> results, int limit) {
        if (results.size() >= limit) return;
        if (pos >= digits.length()) {
            if (!current.isEmpty()) {
                results.add(new ArrayList<>(current));
            }
            return;
        }
        for (int len = 1; len <= Math.min(6, digits.length() - pos); len++) {
            String sub = digits.substring(pos, pos + len);
            List<String> syls = DIGIT_TO_SYL.get(sub);
            if (syls != null) {
                for (String syl : syls) {
                    current.add(syl);
                    enumerateSequences(digits, pos + len, current, results, limit);
                    current.remove(current.size() - 1);
                }
            }
        }
    }

    public static List<DictDBHelper.CharEntry> getCharsByPinyin(String pinyin, int page, int pageSize) {
        if (dbHelper == null) return new ArrayList<>();
        return dbHelper.queryCharsByPinyin(pinyin, page * pageSize, pageSize);
    }

    public static int getCharCountByPinyin(String pinyin) {
        if (dbHelper == null) return 0;
        return dbHelper.getCharCountByPinyin(pinyin);
    }

    public static void addLearnedPhrase(String digitStr, String phrase) {
        if (dbHelper != null) dbHelper.upsertPhrase(digitStr, phrase);
    }

    // 2026-07-29: 将字符串逐字转拼音数字序列，用于热词自动学习
    public static String toDigitSeq(String text) {
        if (dbHelper == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x4e00 || ch > 0x9fff) return ""; // 非汉字不处理
            String py = dbHelper.getPinyinForChar(ch);
            if (py == null) return "";
            sb.append(toDigits(py));
        }
        return sb.toString();
    }

    /**
     * 获取数字串对应的拼音显示
     */
    public static List<String> getPinyinDisplay(String digitStr) {
        List<List<String>> segs = segment(digitStr);
        if (!segs.isEmpty()) return segs.get(0);
        return new ArrayList<>();
    }

    /**
     * 获取前缀拼音
     */
    public static List<String> getPrefixPinyin(String digitStr) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : DIGIT_TO_SYL.entrySet()) {
            if (entry.getKey().startsWith(digitStr) && entry.getKey().length() > digitStr.length()) {
                for (String syl : entry.getValue()) {
                    if (!result.contains(syl)) result.add(syl);
                }
            }
        }
        if (result.size() > 10) result = result.subList(0, 10);
        return result;
    }
}
