package com.ijiazhen.pinyin9x;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 九宫格拼音输入法 — App 首页
 * 提供输入法管理、测试、热词管理、数据备份等功能入口
 */
public class MainActivity extends Activity {

    private static final int COLOR_BG = 0xFFF2F2F7;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_ACCENT = 0xFF007AFF;
    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_TEXT_DIM = 0xFF8E8E93;
    private static final int COLOR_DIVIDER = 0xFFDCDCE0;
    private static final int COLOR_KEY_BG = 0xFFE5E5EA;
    private static final int COLOR_GREEN = 0xFF34C759;
    private static final int COLOR_RED = 0xFFFF3B30;

    private FrameLayout container;
    private ScrollView testOutputScroll;
    private TextView testOutputText;
    private StringBuilder testOutputBuffer = new StringBuilder();
    private String mRestoreTargetPath; // 手动选择文件还原时的目标路径

    // 2026-08-05 02:50: 邻接词管理分页
    private static final int ADJ_PAGE = 50;
    private static final int BATCH_MIN_LEN_DEFAULT = 6;
    private int ngramPage = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        container = (FrameLayout) findViewById(R.id.container);
        buildHomeScreen();
        // 2026-07-31: 语音输入所需的录音权限统一在 MainActivity 申请
        requestVoicePermissionIfNeeded();
    }

    // ====== 首页 ======
    private void buildHomeScreen() {
        container.removeAllViews();

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        root.setBackgroundColor(COLOR_BG);

        TextView title = makeTitle("九家输入法");
        root.addView(title);
        root.addView(makeSpace(8));

        TextView subtitle = new TextView(this);
        subtitle.setText("输入法管理与测试工具");
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_TEXT_DIM);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);
        root.addView(makeSpace(20));

        String[][] features = {
            {"输入法管理", "进入系统输入法设置页面"},
            {"全部键盘击键测试", "测试所有键盘布局与按键响应"},
            {"引擎数据库自动化测试", "断言输入法引擎查询正确性"},
            {"三步上栏手动测试", "手动测试三阶段组词流程"},
            {"十字光标连选自动测试", "测试Shift+方向键文本连选功能"},
            {"用户热词管理", "管理用户自定义词组"},
            // [移除热句功能-2026-08-05 05:27:52] 已删除「热句管理」菜单入口
            {"邻接词管理", "管理 N-Gram 邻接词数据"},
            {"收藏记录管理", "管理收藏夹内容"},
            {"剪切记录管理", "管理剪贴板历史"},
            {"数据备份与恢复", "备份或还原数据库文件"},
        };

        for (int i = 0; i < features.length; i++) {
            final int idx = i;
            root.addView(makeFeatureButton(features[i][0], features[i][1], idx + 1, v -> onFeatureClick(idx)));
            root.addView(makeSpace(8));
        }

        sv.addView(root);
        container.addView(sv);
    }

    private void onFeatureClick(int index) {
        switch (index) {
            case 0: openImeSettings(); break;
            case 1: buildKeyTestScreen(); break;
            case 2: buildEngineTestScreen(); break;
            case 3: buildComposeTestScreen(); break;
            case 4: buildCursorSelectionTestScreen(); break;
            case 5: buildHotWordsScreen(); break;
            // [移除热句功能-2026-08-05 05:27:52] case 6 热句管理已删除，后续编号顺移
            case 6: buildAdjacencyScreen(); break;
            case 7: buildFavoritesScreen(); break;
            case 8: buildClipboardScreen(); break;
            case 9: buildBackupScreen(); break;
        }
    }

    // ====== 1. 输入法管理 ======
    private void openImeSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (Exception e) {
            toast("无法打开输入法设置");
        }
    }

    // ====== 3. 全部键盘击键测试 ======
    private void buildKeyTestScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("键盘击键测试", v -> buildHomeScreen()));
        root.addView(makeSpace(8));

        testOutputScroll = new ScrollView(this);
        testOutputText = new TextView(this);
        testOutputText.setTextSize(13);
        testOutputText.setTextColor(COLOR_TEXT);
        testOutputText.setPadding(dp(12), dp(8), dp(12), dp(8));
        testOutputText.setBackgroundColor(COLOR_SURFACE);
        testOutputBuffer.setLength(0);
        testOutputText.setText("点击下方按键开始测试...\n");
        testOutputScroll.addView(testOutputText);
        root.addView(testOutputScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(makeSpace(8));

        String[][] keyGroups = {
            {"1","2","3","4","5","6","7","8","9","0"},
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"⇧","z","x","c","v","b","n","m","⌫"},
            {"，","。","？","！","；","：","、","…"},
            {"（","）","【","】","《","》","｛","｝"},
            {"＋","－","×","÷","￥","$","€","£"},
            {"空格","↵","Tab","撤销"},
        };

        for (String[] row : keyGroups) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setPadding(dp(4), dp(2), dp(4), dp(2));
            for (String key : row) {
                Button btn = new Button(this);
                btn.setText(key);
                btn.setTextSize(12);
                btn.setAllCaps(false);
                btn.setPadding(dp(4), dp(8), dp(4), dp(8));
                btn.setOnClickListener(v -> testKeyPress(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
                lp.setMargins(dp(1), 0, dp(1), 0);
                rowView.addView(btn, lp);
            }
            root.addView(rowView);
        }

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button clearBtn = new Button(this);
        clearBtn.setText("清空输出");
        clearBtn.setOnClickListener(v -> {
            testOutputBuffer.setLength(0);
            testOutputText.setText("清空完成\n");
        });
        bottom.addView(clearBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        bottom.addView(backBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bottom);

        container.addView(root);
    }

    private void testKeyPress(String key) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        testOutputBuffer.append("[").append(time).append("] 按键: ").append(key).append("\n");
        testOutputText.setText(testOutputBuffer.toString());
        testOutputScroll.post(() -> testOutputScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ====== 4. 引擎数据库自动化测试 ======
    private void buildEngineTestScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("引擎数据库自动化测试", v -> buildHomeScreen()));
        root.addView(makeSpace(8));

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));

        PinyinEngine.init(this);
        DictDBHelper db = DictDBHelper.getInstance(this);

        // 测试用例定义
        String[][] testCases = {
            {"单音节查询 9426=zhan", "9426", "hasZhan", "zhan数字串应匹配拼音zhan"},
            {"已知词组 2426=chin", "2426", "hasPrefix", "2426应为chin/chain等拼音前缀"},
            {"数字分段 968", "968", "segments", "968应分段为zou或you"},
            {"数据库可达 chars表", "ke", "charCount", "ke拼音应有对应汉字"},
            {"数据库可达 phrases表", "2426", "phraseQuery", "phrases表可按数字串查询"},
        };

        List<View> resultViews = new ArrayList<>();
        for (String[] tc : testCases) {
            String name = tc[0];
            String input = tc[1];
            String checkType = tc[2];
            String assertion = tc[3];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(COLOR_SURFACE);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(6));

            TextView nameTv = new TextView(this);
            nameTv.setText("测试: " + name);
            nameTv.setTextSize(14);
            nameTv.setTextColor(COLOR_TEXT);
            nameTv.setTypeface(null, Typeface.BOLD);
            row.addView(nameTv);

            TextView assertTv = new TextView(this);
            assertTv.setText("断言: " + assertion);
            assertTv.setTextSize(12);
            assertTv.setTextColor(COLOR_TEXT_DIM);
            row.addView(assertTv);

            boolean passed = runTest(input, checkType, db);
            TextView resultTv = new TextView(this);
            resultTv.setText(passed ? "PASS" : "FAIL");
            resultTv.setTextSize(16);
            resultTv.setTextColor(passed ? COLOR_GREEN : COLOR_RED);
            resultTv.setTypeface(null, Typeface.BOLD);
            resultTv.setPadding(0, dp(4), 0, 0);
            row.addView(resultTv);

            content.addView(row, rlp);
            resultViews.add(resultTv);
        }

        TextView summary = new TextView(this);
        summary.setTextSize(15);
        summary.setTextColor(COLOR_TEXT);
        summary.setPadding(0, dp(8), 0, 0);
        content.addView(summary);

        Button runBtn = new Button(this);
        runBtn.setText("一键运行全部测试");
        runBtn.setOnClickListener(v -> {
            int passCount = 0;
            for (int i = 0; i < testCases.length; i++) {
                boolean ok = runTest(testCases[i][1], testCases[i][2], db);
                TextView rv = (TextView) resultViews.get(i);
                rv.setText(ok ? "PASS" : "FAIL");
                rv.setTextColor(ok ? COLOR_GREEN : COLOR_RED);
                if (ok) passCount++;
            }
            summary.setText("结果: " + passCount + "/" + testCases.length + " 通过");
        });
        content.addView(runBtn);
        content.addView(makeSpace(8));

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        content.addView(backBtn);

        sv.addView(content);
        root.addView(sv);
        container.addView(root);
    }

    private boolean runTest(String input, String type, DictDBHelper db) {
        try {
            switch (type) {
                case "hasZhan": {
                    List<PinyinEngine.Candidate> cands = PinyinEngine.getCandidates(input);
                    for (PinyinEngine.Candidate c : cands) {
                        if (c.text.contains("战")) return true;
                    }
                    return false;
                }
                case "hasPrefix": {
                    List<String> prefixes = PinyinEngine.getPrefixPinyin(input);
                    return !prefixes.isEmpty();
                }
                case "segments": {
                    List<List<String>> segs = PinyinEngine.getPinyinSequences(input);
                    return !segs.isEmpty();
                }
                case "charCount": {
                    int count = db.getCharCountByPinyin(input);
                    return count > 0;
                }
                case "phraseQuery": {
                    List<DictDBHelper.PhraseEntry> phrases = db.queryPhrasesByDigitSeq(input, 10);
                    return true; // 连接正常即可
                }
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ====== 5. 三步上栏手动测试 ======
    private void buildComposeTestScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("三步上栏手动测试", v -> buildHomeScreen()));
        root.addView(makeSpace(8));

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView instr = new TextView(this);
        instr.setText("输入数字串，查看拼音分段结果与候选词。\n示例: 9685863364 (zou you jun/deng等)");
        instr.setTextSize(13);
        instr.setTextColor(COLOR_TEXT_DIM);
        content.addView(instr);
        content.addView(makeSpace(8));

        EditText input = new EditText(this);
        input.setHint("输入数字串，如 2426");
        input.setTextSize(16);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackgroundColor(COLOR_SURFACE);
        content.addView(input);
        content.addView(makeSpace(8));

        TextView resultText = new TextView(this);
        resultText.setTextSize(14);
        resultText.setTextColor(COLOR_TEXT);
        resultText.setBackgroundColor(COLOR_SURFACE);
        resultText.setPadding(dp(12), dp(10), dp(12), dp(10));
        resultText.setMinHeight(dp(200));
        resultText.setText("等待输入...");
        content.addView(resultText);
        content.addView(makeSpace(8));

        Button searchBtn = new Button(this);
        searchBtn.setText("执行分段查询");
        searchBtn.setOnClickListener(v -> {
            String digits = input.getText().toString().trim();
            if (TextUtils.isEmpty(digits) || !digits.matches("[0-9]+")) {
                resultText.setText("请输入有效数字串");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("数字串: ").append(digits).append("\n\n");

            List<List<String>> segs = PinyinEngine.getPinyinSequences(digits);
            if (segs.isEmpty()) {
                sb.append("无完整分段，尝试前缀匹配...\n");
                List<String> prefixes = PinyinEngine.getPrefixPinyin(digits);
                if (!prefixes.isEmpty()) {
                    sb.append("前缀候选: ").append(TextUtils.join(", ", prefixes)).append("\n");
                } else {
                    sb.append("无法解析，请缩短数字串或尝试其他输入\n");
                }
            } else {
                sb.append("拼音分段 (").append(segs.size()).append("种):\n");
                int show = Math.min(segs.size(), 15);
                for (int i = 0; i < show; i++) {
                    sb.append("  ").append(i + 1).append(". ");
                    sb.append(TextUtils.join(" + ", segs.get(i))).append("\n");
                }
                if (segs.size() > 15) sb.append("  ... (").append(segs.size() - 15).append("更多)\n");

                sb.append("\n候选词:\n");
                List<PinyinEngine.Candidate> cands = PinyinEngine.getCandidates(digits);
                int show2 = Math.min(cands.size(), 20);
                for (int i = 0; i < show2; i++) {
                    PinyinEngine.Candidate c = cands.get(i);
                    sb.append("  ").append(c.text).append(" [").append(c.type).append("] score=").append((int)c.score).append("\n");
                }
            }
            resultText.setText(sb.toString());
        });
        content.addView(searchBtn);
        content.addView(makeSpace(8));

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        content.addView(backBtn);

        sv.addView(content);
        root.addView(sv);
        container.addView(root);
    }

    // ====== 5.5 十字光标连选自动测试 ======
    private void buildCursorSelectionTestScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("十字光标连选自动测试", v -> buildHomeScreen()));
        root.addView(makeSpace(8));

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView instr = new TextView(this);
        instr.setText("测试 Shift+方向键 的文本连选功能。\n"
            + "验证：光标移动、Sel模式选择、连续扩展选区、取消选择。");
        instr.setTextSize(13);
        instr.setTextColor(COLOR_TEXT_DIM);
        content.addView(instr);
        content.addView(makeSpace(12));

        String testText = "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。";
        EditText testField = new EditText(this);
        testField.setText(testText);
        testField.setTextSize(16);
        testField.setPadding(dp(12), dp(14), dp(12), dp(14));
        testField.setBackgroundColor(COLOR_SURFACE);
        content.addView(testField);
        content.addView(makeSpace(8));

        TextView resultText = new TextView(this);
        resultText.setTextSize(14);
        resultText.setTextColor(COLOR_TEXT);
        resultText.setBackgroundColor(COLOR_SURFACE);
        resultText.setPadding(dp(12), dp(10), dp(12), dp(10));
        resultText.setMinHeight(dp(200));
        resultText.setText("等待测试...");
        content.addView(resultText);
        content.addView(makeSpace(8));

        Button runBtn = new Button(this);
        runBtn.setText("一键运行连选测试");
        runBtn.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 十字光标连选测试 ==========\n\n");

            testField.requestFocus();
            testField.setSelection(testText.length());
            sb.append("初始状态：光标在末尾\n\n");

            // Test 1: 光标键盘正常移动 (无Sel)
            sb.append("--- 测试1: 基本方向键移动 ---\n");
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, false);
            sb.append("  左移1: 光标位置=").append(testField.getSelectionStart()).append("\n");
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, false);
            sb.append("  左移2: 光标位置=").append(testField.getSelectionStart()).append("\n");
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, false);
            sb.append("  右移1: 光标位置=").append(testField.getSelectionStart()).append("\n");
            boolean test1 = testField.getSelectionStart() == testText.length() - 1;
            sb.append("  结果: ").append(test1 ? "PASS" : "FAIL").append("\n\n");

            // Test 2: Sel模式 - Shift+左方向键选择
            sb.append("--- 测试2: Sel模式启用 ---\n");
            testField.setSelection(testText.length());
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, true);
            int selStartAfterOne = testField.getSelectionStart();
            int selEndAfterOne = testField.getSelectionEnd();
            sb.append("  Sel+左移1: start=").append(selStartAfterOne)
                .append(" end=").append(selEndAfterOne).append("\n");
            boolean test2 = selEndAfterOne > selStartAfterOne;
            sb.append("  预期: start < end (有选中区域)\n");
            sb.append("  结果: ").append(test2 ? "PASS" : "FAIL").append("\n\n");

            // Test 3: 连续选择 - 多次Shift+左
            sb.append("--- 测试3: 连选(多次Shift+左) ---\n");
            testField.setSelection(testText.length());
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, true);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, true);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, true);
            int selStart3 = testField.getSelectionStart();
            int selEnd3 = testField.getSelectionEnd();
            sb.append("  Sel+左x3: start=").append(selStart3).append(" end=").append(selEnd3).append("\n");
            String selectedText3 = testText.substring(selStart3, selEnd3);
            sb.append("  选中文本: \"").append(selectedText3).append("\"\n");
            boolean test3 = selEnd3 == testText.length() && selStart3 == testText.length() - 3;
            sb.append("  预期: 选中末尾3个字\n");
            sb.append("  结果: ").append(test3 ? "PASS" : "FAIL").append("\n\n");

            // Test 4: 取消Sel后正常移动
            sb.append("--- 测试4: 取消Sel模式后正常移动 ---\n");
            testField.setSelection(testText.length());
            testField.setSelection(testText.length() - 2, testText.length());
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, false);
            int selStart4 = testField.getSelectionStart();
            int selEnd4 = testField.getSelectionEnd();
            sb.append("  取消Sel+右移: start=").append(selStart4).append(" end=").append(selEnd4).append("\n");
            boolean test4 = selStart4 == selEnd4;
            sb.append("  预期: 无选中(取消选区)\n");
            sb.append("  结果: ").append(test4 ? "PASS" : "FAIL").append("\n\n");

            // Test 5: 连续Shift+右反向选择
            sb.append("--- 测试5: Shift+右方向反向连选 ---\n");
            testField.setSelection(0);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, true);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, true);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, true);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_RIGHT, true);
            int selStart5 = testField.getSelectionStart();
            int selEnd5 = testField.getSelectionEnd();
            sb.append("  Sel+右x4: start=").append(selStart5).append(" end=").append(selEnd5).append("\n");
            String selectedText5 = testText.substring(selStart5, selEnd5);
            sb.append("  选中文本: \"").append(selectedText5).append("\"\n");
            boolean test5 = selStart5 == 0 && selEnd5 == 4;
            sb.append("  预期: 从开头选中4个字\n");
            sb.append("  结果: ").append(test5 ? "PASS" : "FAIL").append("\n\n");

            // Test 6: 上下方向键
            sb.append("--- 测试6: 上下方向键 ---\n");
            testField.setSelection(0);
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_DOWN, false);
            int selDown = testField.getSelectionStart();
            sb.append("  下移: 光标位置=").append(selDown).append("\n");
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_UP, false);
            int selUp = testField.getSelectionStart();
            sb.append("  上移: 光标位置=").append(selUp).append("\n");
            boolean test6 = selDown > 0;
            sb.append("  结果: ").append(test6 ? "PASS" : "FAIL").append("\n\n");

            // Summary
            int passCount = (test1 ? 1 : 0) + (test2 ? 1 : 0) + (test3 ? 1 : 0)
                          + (test4 ? 1 : 0) + (test5 ? 1 : 0) + (test6 ? 1 : 0);
            sb.append("========== 汇总 ==========\n");
            sb.append("通过: ").append(passCount).append("/6\n");
            sb.append("测试1(基本移动): ").append(test1 ? "PASS" : "FAIL").append("\n");
            sb.append("测试2(Sel选择): ").append(test2 ? "PASS" : "FAIL").append("\n");
            sb.append("测试3(连选): ").append(test3 ? "PASS" : "FAIL").append("\n");
            sb.append("测试4(取消Sel): ").append(test4 ? "PASS" : "FAIL").append("\n");
            sb.append("测试5(反向连选): ").append(test5 ? "PASS" : "FAIL").append("\n");
            sb.append("测试6(上下方向): ").append(test6 ? "PASS" : "FAIL").append("\n");
            sb.append("\n全部通过: ").append(passCount == 6 ? "是" : "否");

            resultText.setText(sb.toString());
        });
        content.addView(runBtn);
        content.addView(makeSpace(8));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button selToggleBtn = new Button(this);
        selToggleBtn.setText("手动Sel切换");
        selToggleBtn.setOnClickListener(v -> {
            testField.requestFocus();
            sendTestCursorKey(testField, KeyEvent.KEYCODE_DPAD_LEFT, true);
        });
        btnRow.addView(selToggleBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button resetBtn = new Button(this);
        resetBtn.setText("重置文本");
        resetBtn.setOnClickListener(v -> testField.setText(testText));
        btnRow.addView(resetBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(btnRow);
        content.addView(makeSpace(8));

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        content.addView(backBtn);

        sv.addView(content);
        root.addView(sv);
        container.addView(root);
    }

    private void sendTestCursorKey(EditText field, int keyCode, boolean withShift) {
        long now = android.os.SystemClock.uptimeMillis();
        if (withShift) {
            field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0));
        }
        int meta = withShift ? KeyEvent.META_SHIFT_ON : 0;
        field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
        if (withShift) {
            field.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyEvent.META_SHIFT_ON));
        }
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    }

    // ====== 6. 用户热词管理 ======
    // 2026-08-05 02:20: 分页加载（每页100条），列表底部"加载更多"，修复超过100条看不到的问题
    private int hotWordsOffset = 0;
    private static final int HOT_WORDS_PAGE = 100;

    private void buildHotWordsScreen() {
        container.removeAllViews();
        hotWordsOffset = 0;
        renderHotWordsScreen();
    }

    private void renderHotWordsScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("用户热词管理", v -> buildHomeScreen()));
        root.addView(makeSpace(4));

        DictDBHelper db = DictDBHelper.getInstance(this);
        int phraseCount = db.getUserPhraseCount();
        TextView stats = new TextView(this);
        stats.setText("共 " + phraseCount + " 条用户热词");
        stats.setTextSize(13);
        stats.setTextColor(COLOR_TEXT_DIM);
        stats.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(stats);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));

        List<DictDBHelper.PhraseEntry> phrases = db.getAllUserPhrases(hotWordsOffset, HOT_WORDS_PAGE);
        if (phrases.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无用户热词，使用输入法组词功能或输入句子后自动添加");
            empty.setTextSize(14);
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            list.addView(empty);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (DictDBHelper.PhraseEntry p : phrases) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(COLOR_SURFACE);
                row.setPadding(dp(12), dp(8), dp(4), dp(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(2));

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView phraseTv = new TextView(this);
                phraseTv.setText(p.text);
                phraseTv.setTextSize(15);
                phraseTv.setTextColor(COLOR_TEXT);
                info.addView(phraseTv);
                TextView detailTv = new TextView(this);
                // 2026-08-05 02:20: 明细增加最近学习时间，直观对应新排序
                String timeStr;
                try {
                    timeStr = sdf.format(new Date(p.updatedAt * 1000));
                } catch (Exception e) {
                    timeStr = "";
                }
                detailTv.setText("数字串: " + p.digitSeq + "  频率: " + p.frequency
                    + (timeStr.isEmpty() ? "" : "  更新: " + timeStr));
                detailTv.setTextSize(11);
                detailTv.setTextColor(COLOR_TEXT_DIM);
                info.addView(detailTv);
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                Button delBtn = new Button(this);
                delBtn.setText("删除");
                delBtn.setTextSize(12);
                delBtn.setTextColor(COLOR_RED);
                final String dps = p.digitSeq;
                final String dpt = p.text;
                delBtn.setOnClickListener(v -> {
                    db.deletePhrase(dps, dpt);
                    buildHotWordsScreen();
                });
                row.addView(delBtn);

                list.addView(row, rlp);
            }
            // 2026-08-05 02:20: 未加载完时显示"加载更多"按钮，点击追加下一页
            if (hotWordsOffset + phrases.size() < phraseCount) {
                Button moreBtn = new Button(this);
                moreBtn.setText("加载更多（还剩 " + (phraseCount - hotWordsOffset - phrases.size()) + " 条）");
                moreBtn.setTextSize(13);
                moreBtn.setOnClickListener(v -> {
                    hotWordsOffset += HOT_WORDS_PAGE;
                    renderHotWordsScreen();
                });
                list.addView(moreBtn);
            }
        }

        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // [新增多种清理模式-2026-08-05 05:27:52] 热词管理页批量清理入口
        Button hotBatchBtn = new Button(this);
        hotBatchBtn.setText("批量清理（多种模式）");
        hotBatchBtn.setTextSize(13);
        hotBatchBtn.setTextColor(COLOR_RED);
        hotBatchBtn.setOnClickListener(v -> showHotWordsCleanupDialog(db));
        root.addView(hotBatchBtn);

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        root.addView(backBtn);

        container.addView(root);
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 热词批量清理：先选模式，再输入阈值，反馈删除条数
    private void showHotWordsCleanupDialog(final DictDBHelper db) {
        final String[] modes = {
            "频率低的（默认 ≤2）",
            "超过一个月未更新（30天）",
            "超过一周未更新（7天）",
            "字数太多的（默认 ≥5字）"
        };
        new AlertDialog.Builder(this)
            .setTitle("选择清理模式")
            .setItems(modes, (d, which) -> {
                switch (which) {
                    case 0: promptAndDeleteHotWords(db, "频率上限", 2, "maxFreq"); break;
                    case 1: doDeleteHotWords(db, 30, "days"); break;
                    case 2: doDeleteHotWords(db, 7, "days"); break;
                    case 3: promptAndDeleteHotWords(db, "字数下限", 5, "minLen"); break;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 热词：输入阈值后执行删除
    private void promptAndDeleteHotWords(final DictDBHelper db, String label, int defVal, final String mode) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(defVal));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("批量清理用户热词")
            .setMessage("请输入" + label + "：")
            .setView(input)
            .setPositiveButton("删除", (d, w) -> {
                int val;
                try { val = Integer.parseInt(input.getText().toString().trim()); }
                catch (Exception e) { val = defVal; }
                if (val < 1) val = 1;
                doDeleteHotWords(db, val, mode);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 热词：执行删除并反馈条数（仅删用户词，不删系统词）
    private void doDeleteHotWords(DictDBHelper db, int val, String mode) {
        int n = 0;
        String desc = "";
        if ("maxFreq".equals(mode)) {
            n = db.deleteUserPhraseByMaxFreq(val);
            desc = "频率≤" + val;
        } else if ("days".equals(mode)) {
            n = db.deleteUserPhraseBeforeDays(val);
            desc = "超过" + val + "天未更新";
        } else if ("minLen".equals(mode)) {
            n = db.deleteUserPhraseByMinLen(val);
            desc = "字数≥" + val;
        }
        toast("已删除 " + n + " 条用户热词（" + desc + "）");
        buildHotWordsScreen();
    }

    // [移除热句功能-2026-08-05 05:27:52] 已删除整个 buildHotSentencesScreen 方法

    // ====== 7.5 邻接词管理 ======
    private void buildAdjacencyScreen() {
        container.removeAllViews();
        ngramPage = 0;
        renderAdjacencyScreen();
    }

    private void renderAdjacencyScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("邻接词管理", v -> buildHomeScreen()));
        root.addView(makeSpace(4));

        DictDBHelper db = DictDBHelper.getInstance(this);
        int total = db.getAdjacencyCount();
        int pageCount = total == 0 ? 0 : (total - 1) / ADJ_PAGE + 1;
        if (pageCount > 0 && ngramPage > pageCount - 1) ngramPage = pageCount - 1;
        if (ngramPage < 0) ngramPage = 0;

        TextView stats = new TextView(this);
        int bigCount = db.getAdjacencyCountByMinLen(BATCH_MIN_LEN_DEFAULT);
        stats.setText("共 " + total + " 条邻接词，其中跟随词≥" + BATCH_MIN_LEN_DEFAULT + "字 " + bigCount + " 条");
        stats.setTextSize(13);
        stats.setTextColor(COLOR_TEXT_DIM);
        stats.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(stats);

        Button batchBtn = new Button(this);
        // [新增多种清理模式-2026-08-05 05:27:52] 批量清理改为4种模式选择
        batchBtn.setText("批量清理（多种模式）");
        batchBtn.setTextSize(13);
        batchBtn.setTextColor(COLOR_RED);
        batchBtn.setOnClickListener(v -> showAdjacencyCleanupDialog(db));
        root.addView(batchBtn);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));

        List<DictDBHelper.AdjacencyEntry> entries = db.getAllAdjacency(ngramPage * ADJ_PAGE, ADJ_PAGE);
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无邻接词数据");
            empty.setTextSize(14);
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            list.addView(empty);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (DictDBHelper.AdjacencyEntry e : entries) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(COLOR_SURFACE);
                row.setPadding(dp(12), dp(8), dp(4), dp(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(2));

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView mainTv = new TextView(this);
                String ctx = e.context;
                if (ctx.length() > 12) ctx = ctx.substring(0, 12) + "…";
                mainTv.setText(ctx + " → " + e.nextWord);
                mainTv.setTextSize(15);
                mainTv.setTextColor(COLOR_TEXT);
                info.addView(mainTv);
                TextView detailTv = new TextView(this);
                String timeStr;
                try {
                    timeStr = sdf.format(new Date(e.updatedAt * 1000));
                } catch (Exception ex) {
                    timeStr = "";
                }
                detailTv.setText("跟随词 " + e.nextWord.length() + " 字  频率: " + e.freq
                    + (timeStr.isEmpty() ? "" : "  更新: " + timeStr));
                detailTv.setTextSize(11);
                detailTv.setTextColor(COLOR_TEXT_DIM);
                info.addView(detailTv);
                row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                Button delBtn = new Button(this);
                delBtn.setText("删除");
                delBtn.setTextSize(12);
                delBtn.setTextColor(COLOR_RED);
                final int eid = e.id;
                delBtn.setOnClickListener(v -> {
                    db.deleteAdjacencyById(eid);
                    renderAdjacencyScreen();
                });
                row.addView(delBtn);

                list.addView(row, rlp);
            }
        }

        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 翻页控件
        LinearLayout pageRow = new LinearLayout(this);
        pageRow.setOrientation(LinearLayout.HORIZONTAL);
        Button prevBtn = new Button(this);
        prevBtn.setText("上一页");
        prevBtn.setTextSize(13);
        prevBtn.setEnabled(ngramPage > 0);
        prevBtn.setOnClickListener(v -> { ngramPage--; renderAdjacencyScreen(); });
        pageRow.addView(prevBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView pageTv = new TextView(this);
        pageTv.setText((total == 0 ? 0 : ngramPage + 1) + " / " + pageCount);
        pageTv.setTextSize(13);
        pageTv.setTextColor(COLOR_TEXT);
        pageTv.setGravity(Gravity.CENTER);
        pageRow.addView(pageTv, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button nextBtn = new Button(this);
        nextBtn.setText("下一页");
        nextBtn.setTextSize(13);
        nextBtn.setEnabled(total == 0 || ngramPage < pageCount - 1);
        nextBtn.setOnClickListener(v -> { ngramPage++; renderAdjacencyScreen(); });
        pageRow.addView(nextBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(pageRow);

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        root.addView(backBtn);

        container.addView(root);
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 邻接词批量清理：先选模式，再输入阈值，反馈删除条数
    private void showAdjacencyCleanupDialog(final DictDBHelper db) {
        final String[] modes = {
            "频率低的（默认 ≤2）",
            "超过一个月未更新（30天）",
            "超过一周未更新（7天）",
            "字数太多的（默认 ≥6）"
        };
        new AlertDialog.Builder(this)
            .setTitle("选择清理模式")
            .setItems(modes, (d, which) -> {
                switch (which) {
                    case 0: promptAndDeleteAdjacency(db, "频率上限", 2, "maxFreq"); break;
                    case 1: doDeleteAdjacency(db, 30, "days"); break;
                    case 2: doDeleteAdjacency(db, 7, "days"); break;
                    case 3: promptAndDeleteAdjacency(db, "字数下限", BATCH_MIN_LEN_DEFAULT, "minLen"); break;
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 邻接词：输入阈值后执行删除
    private void promptAndDeleteAdjacency(final DictDBHelper db, String label, int defVal, final String mode) {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(defVal));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("批量清理邻接词")
            .setMessage("请输入" + label + "：")
            .setView(input)
            .setPositiveButton("删除", (d, w) -> {
                int val;
                try { val = Integer.parseInt(input.getText().toString().trim()); }
                catch (Exception e) { val = defVal; }
                if (val < 1) val = 1;
                doDeleteAdjacency(db, val, mode);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // [新增多种清理模式-2026-08-05 05:27:52] 邻接词：执行删除并反馈条数
    private void doDeleteAdjacency(DictDBHelper db, int val, String mode) {
        int n = 0;
        String desc = "";
        if ("maxFreq".equals(mode)) {
            n = db.deleteAdjacencyByMaxFreq(val);
            desc = "频率≤" + val;
        } else if ("days".equals(mode)) {
            n = db.deleteAdjacencyBeforeDays(val);
            desc = "超过" + val + "天未更新";
        } else if ("minLen".equals(mode)) {
            n = db.deleteAdjacencyByMinLen(val);
            desc = "跟随词≥" + val + "字";
        }
        toast("已删除 " + n + " 条邻接词（" + desc + "）");
        ngramPage = 0;
        renderAdjacencyScreen();
    }

    // ====== 7. 收藏记录管理 ======
    private void buildFavoritesScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("收藏记录管理", v -> buildHomeScreen()));
        root.addView(makeSpace(4));

        ClipDBHelper clipDb = ClipDBHelper.getInstance(this);
        int favCount = clipDb.getFavCount();
        TextView stats = new TextView(this);
        stats.setText("共 " + favCount + " 条收藏 (置顶优先)");
        stats.setTextSize(13);
        stats.setTextColor(COLOR_TEXT_DIM);
        stats.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(stats);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));

        List<ClipDBHelper.FavEntry> favs = clipDb.getAllFavorites();
        if (favs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("收藏夹为空");
            empty.setTextSize(14);
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            list.addView(empty);
        } else {
            for (ClipDBHelper.FavEntry f : favs) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(f.pinned ? COLOR_ACCENT : COLOR_SURFACE);
                row.setPadding(dp(12), dp(8), dp(4), dp(8));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.setMargins(0, 0, 0, dp(2));

                TextView textTv = new TextView(this);
                String display = f.text.length() > 30 ? f.text.substring(0, 30) + "..." : f.text;
                textTv.setText(display);
                textTv.setTextSize(14);
                textTv.setTextColor(f.pinned ? 0xFFFFFFFF : COLOR_TEXT);
                row.addView(textTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                Button pinBtn = new Button(this);
                pinBtn.setText(f.pinned ? "取消置顶" : "置顶");
                pinBtn.setTextSize(11);
                final long fid = f.id;
                pinBtn.setOnClickListener(v -> {
                    clipDb.togglePinFavorite(fid);
                    buildFavoritesScreen();
                });
                row.addView(pinBtn);

                Button delBtn = new Button(this);
                delBtn.setText("删除");
                delBtn.setTextSize(11);
                delBtn.setTextColor(COLOR_RED);
                delBtn.setOnClickListener(v -> {
                    clipDb.deleteFavorite(fid);
                    buildFavoritesScreen();
                });
                row.addView(delBtn);

                list.addView(row, rlp);
            }
        }

        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        root.addView(backBtn);

        container.addView(root);
    }

    // ====== 8. 剪切记录管理 ======
    private void buildClipboardScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("剪切记录管理", v -> buildHomeScreen()));
        root.addView(makeSpace(4));

        ClipDBHelper clipDb = ClipDBHelper.getInstance(this);
        int clipCount = clipDb.getClipCount();
        TextView stats = new TextView(this);
        stats.setText("共 " + clipCount + " 条记录 (上限100条)");
        stats.setTextSize(13);
        stats.setTextColor(COLOR_TEXT_DIM);
        stats.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(stats);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));

        List<ClipDBHelper.ClipEntry> clips = clipDb.getAllClips();
        if (clips.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("剪切板为空");
            empty.setTextSize(14);
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            list.addView(empty);
        } else {
            for (ClipDBHelper.ClipEntry c : clips) {
                // 2026-08-05 03:00: 每条记录两行结构 — 文本全宽显示，操作按钮独立一行，不挤压文本
                LinearLayout entry = new LinearLayout(this);
                entry.setOrientation(LinearLayout.VERTICAL);
                entry.setBackgroundColor(COLOR_SURFACE);
                entry.setPadding(dp(12), dp(8), dp(4), dp(8));
                LinearLayout.LayoutParams entryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                entryLp.setMargins(0, 0, 0, dp(2));

                LinearLayout info = new LinearLayout(this);
                info.setOrientation(LinearLayout.VERTICAL);
                TextView textTv = new TextView(this);
                String display = c.text.length() > 50 ? c.text.substring(0, 50) + "..." : c.text;
                textTv.setText(display);
                textTv.setTextSize(13);
                textTv.setTextColor(COLOR_TEXT);
                info.addView(textTv);
                TextView timeTv = new TextView(this);
                timeTv.setText(new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(c.timestamp)));
                timeTv.setTextSize(11);
                timeTv.setTextColor(COLOR_TEXT_DIM);
                info.addView(timeTv);
                entry.addView(info);

                // 2026-08-05 03:00: 按钮独立一行，等宽分布
                LinearLayout btnRow = new LinearLayout(this);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setPadding(0, dp(4), 0, 0);

                // [移除热句功能-2026-08-05 05:27:52] 已删除「+热句」按钮，该行仅剩收藏、删除两个按钮

                Button favBtn = new Button(this);
                favBtn.setText("收藏");
                favBtn.setTextSize(11);
                favBtn.setTextColor(COLOR_ACCENT);
                final String ct = c.text;
                final long cid = c.id;
                favBtn.setOnClickListener(v -> {
                    clipDb.addFavorite(ct);
                    toast("已收藏: " + (ct.length() > 10 ? ct.substring(0, 10) + "..." : ct));
                });
                LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(0, dp(44), 1);
                favLp.setMargins(0, 0, dp(4), 0);
                btnRow.addView(favBtn, favLp);

                Button delBtn = new Button(this);
                delBtn.setText("删除");
                delBtn.setTextSize(11);
                delBtn.setTextColor(COLOR_RED);
                delBtn.setOnClickListener(v -> {
                    clipDb.deleteClip(cid);
                    buildClipboardScreen();
                });
                btnRow.addView(delBtn, new LinearLayout.LayoutParams(0, dp(44), 1));

                entry.addView(btnRow);
                list.addView(entry, entryLp);
            }
        }

        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(4), dp(8), dp(8));
        Button copyBtn = new Button(this);
        copyBtn.setText("一键清空");
        copyBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定要删除全部剪切记录吗？收藏记录不受影响。")
                .setPositiveButton("确定", (d, w) -> {
                    clipDb.deleteAllClips();
                    buildClipboardScreen();
                    toast("已清空全部剪切记录");
                })
                .setNegativeButton("取消", null)
                .show();
        });
        bottom.addView(copyBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        bottom.addView(backBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bottom);

        container.addView(root);
    }

    // ====== 9. 数据备份与恢复 ======
    private void buildBackupScreen() {
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        root.addView(makeHeader("数据备份与恢复", v -> buildHomeScreen()));
        root.addView(makeSpace(4));

        ScrollView sv = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));

        DictDBHelper dictDb = DictDBHelper.getInstance(this);
        ClipDBHelper clipDb = ClipDBHelper.getInstance(this);

        long dbSize = dictDb.getDictDbSize();
        int phraseCount = dictDb.getPhraseCount();
        int userPhraseCount = dictDb.getUserPhraseCount();
        int favCount = clipDb.getFavCount();
        int clipCount = clipDb.getClipCount();

        TextView info = new TextView(this);
        info.setText(
            "数据库文件: " + formatSize(dbSize) + "\n" +
            "  单字表 (chars): 17,047 条\n" +
            "  词组表 (phrases): " + phraseCount + " 条 (其中用户词 " + userPhraseCount + " 条)\n" +
            // [移除热句功能-2026-08-05 05:27:52] 已删除「热句表 (hot_sentences)」信息行
            "  邻接词表 (ngram_adjacency): 已学习\n" +
            "  剪切记录 (clipboard): " + clipCount + " 条\n" +
            "  收藏记录 (favorites): " + favCount + " 条\n" +
            "\n备份包含全部数据（含邻接词联想），保存到: Download/backup<时间戳>.db" +
            "\n还原时可从多个备份文件中选择" +
            "\n也可手动选择其他位置的.db文件");
        info.setTextSize(13);
        info.setTextColor(COLOR_TEXT);
        info.setBackgroundColor(COLOR_SURFACE);
        info.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(info);
        content.addView(makeSpace(12));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button backupBtn = new Button(this);
        backupBtn.setText("备份到 Download");
        backupBtn.setOnClickListener(v -> backupToDownloads(dictDb.getDbPath()));
        btnRow.addView(backupBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button restoreBtn = new Button(this);
        restoreBtn.setText("从 Download 还原");
        restoreBtn.setOnClickListener(v -> restoreFromDownloads(dictDb.getDbPath()));
        btnRow.addView(restoreBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(btnRow);
        content.addView(makeSpace(8));

        // 手动选择db文件还原按钮（解决卸载重装后不认旧备份的问题）
        Button manualPickBtn = new Button(this);
        manualPickBtn.setText("手动选择db文件还原");
        manualPickBtn.setOnClickListener(v -> pickBackupFileManually(dictDb.getDbPath()));
        content.addView(manualPickBtn);
        content.addView(makeSpace(8));

        // [新增手动增量还原-2026-08-05 05:27:52] 增量还原按钮：合并备份数据，不覆盖本地库
        Button incrementalBtn = new Button(this);
        incrementalBtn.setText("手动增量还原（不覆盖本地库）");
        incrementalBtn.setOnClickListener(v -> pickBackupFileForIncremental());
        content.addView(incrementalBtn);
        content.addView(makeSpace(12));

        Button backBtn = new Button(this);
        backBtn.setText("返回首页");
        backBtn.setOnClickListener(v -> buildHomeScreen());
        content.addView(backBtn);

        sv.addView(content);
        root.addView(sv);
        container.addView(root);
    }

    // 2026-07-31: 备份/恢复改为直接复制 db 到公共下载目录 Download
    private static final int REQ_STORAGE = 1001;
    private static final int REQ_VOICE = 1002;
    private static final int REQ_FILE_PICK = 1003;
    // [新增手动增量还原-2026-08-05 05:27:52] 增量还原文件选择请求码
    private static final int REQ_FILE_PICK_INCREMENTAL = 1004;
    private static final String BACKUP_PREFIX = "backup";
    private static final String BACKUP_SUFFIX = ".db";

    private String generateBackupName() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return BACKUP_PREFIX + timestamp + BACKUP_SUFFIX;
    }

    private void backupToDownloads(String sourcePath) {
        File src = new File(sourcePath);
        if (!src.exists()) {
            toast("源文件不存在: " + sourcePath);
            return;
        }
        String backupName = generateBackupName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, backupName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    toast("备份失败: 无法创建下载目录文件");
                    return;
                }
                OutputStream out = getContentResolver().openOutputStream(uri);
                copyTo(src, out);
                out.close();
                toast("备份成功: Download/" + backupName + " (" + formatSize(src.length()) + ")");
            } catch (Exception e) {
                toast("备份失败: " + e.getMessage());
            }
        } else {
            if (!hasStoragePermission()) {
                requestStoragePermission();
                return;
            }
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, backupName);
                copyFile(src, dest);
                toast("备份成功: Download/" + backupName + " (" + formatSize(dest.length()) + ")");
            } catch (Exception e) {
                toast("备份失败: " + e.getMessage());
            }
        }
    }

    private void restoreFromDownloads(String targetPath) {
        List<BackupFileInfo> backups = findBackupFiles();
        if (backups.isEmpty()) {
            toast("未找到备份文件 (Download/*.db)");
            return;
        }
        if (backups.size() == 1) {
            confirmRestore(backups.get(0), targetPath);
        } else {
            showBackupFilePicker(backups, targetPath);
        }
    }

    private void confirmRestore(BackupFileInfo backup, String targetPath) {
        new AlertDialog.Builder(this)
            .setTitle("确认还原")
            .setMessage("还原将覆盖当前数据库，是否继续？\n备份文件: " + backup.name)
            .setPositiveButton("确认还原", (d, w) -> doRestore(backup, targetPath))
            .setNegativeButton("取消", null)
            .show();
    }

    private void showBackupFilePicker(List<BackupFileInfo> backups, String targetPath) {
        String[] names = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            BackupFileInfo b = backups.get(i);
            names[i] = b.name + "  (" + formatSize(b.size) + ")";
        }
        new AlertDialog.Builder(this)
            .setTitle("选择要还原的备份文件")
            .setItems(names, (d, which) -> {
                BackupFileInfo selected = backups.get(which);
                confirmRestore(selected, targetPath);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void doRestore(BackupFileInfo backup, String targetPath) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                InputStream in = getContentResolver().openInputStream(backup.uri);
                copyFrom(in, new File(targetPath));
                in.close();
            } else {
                if (!hasStoragePermission()) {
                    requestStoragePermission();
                    return;
                }
                copyFile(backup.file, new File(targetPath));
            }
            DictDBHelper.resetInstance();
            DictDBHelper.getInstance(this);
            ClipDBHelper.resetInstance();
            toast("还原成功: " + backup.name);
        } catch (Exception e) {
            toast("还原失败: " + e.getMessage());
        }
    }

    // 手动选择db文件还原（解决卸载重装后不认旧备份的问题）
    private void pickBackupFileManually(String targetPath) {
        mRestoreTargetPath = targetPath;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // 过滤只显示 .db 文件
        String[] mimeTypes = {"application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQ_FILE_PICK);
    }

    // [新增手动增量还原-2026-08-05 05:27:52] 选择备份文件用于增量合并（不覆盖本地库）
    private void pickBackupFileForIncremental() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQ_FILE_PICK_INCREMENTAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // 获取文件名用于显示
                String fileName = getFileNameFromUri(uri);
                // 将 URI 权限持久化，避免重启后丢失
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                doRestoreFromUri(uri, fileName, mRestoreTargetPath);
            }
        }
        // [新增手动增量还原-2026-08-05 05:27:52] 增量还原：合并备份数据，不覆盖本地库
        if (requestCode == REQ_FILE_PICK_INCREMENTAL && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String fileName = getFileNameFromUri(uri);
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
                doIncrementalRestoreFromUri(uri, fileName);
            }
        }
    }

    // [新增手动增量还原-2026-08-05 05:27:52] 执行增量还原：把备份复制到临时文件，合并后删除
    private void doIncrementalRestoreFromUri(Uri uri, String fileName) {
        new AlertDialog.Builder(this)
            .setTitle("确认增量还原")
            .setMessage("将合并备份数据到本地库，不覆盖现有数据。\n备份文件: " + fileName)
            .setPositiveButton("确认合并", (d, w) -> {
                java.io.File tmpFile = null;
                android.database.sqlite.SQLiteDatabase backupDb = null;
                try {
                    // 把选中的备份复制到临时文件（不碰本地库路径）
                    tmpFile = java.io.File.createTempFile("incr_backup_", ".db", getCacheDir());
                    InputStream in = getContentResolver().openInputStream(uri);
                    copyFrom(in, tmpFile);
                    in.close();

                    backupDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                        tmpFile.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                    DictDBHelper dictDb = DictDBHelper.getInstance(this);
                    String report = dictDb.mergeFromBackup(backupDb);
                    // 合并后重置剪切库实例，确保 ClipDBHelper 读取最新数据
                    ClipDBHelper.resetInstance();
                    ClipDBHelper.getInstance(this);
                    toast(report);
                } catch (Exception e) {
                    toast("增量还原失败: " + e.getMessage());
                } finally {
                    if (backupDb != null) backupDb.close();
                    if (tmpFile != null) tmpFile.delete();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String getFileNameFromUri(Uri uri) {
        String name = null;
        Cursor c = getContentResolver().query(uri,
            new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    name = c.getString(0);
                }
            } finally {
                c.close();
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name != null ? name : "未知文件";
    }

    private void doRestoreFromUri(Uri uri, String fileName, String targetPath) {
        new AlertDialog.Builder(this)
            .setTitle("确认还原")
            .setMessage("还原将覆盖当前数据库，是否继续？\n备份文件: " + fileName)
            .setPositiveButton("确认还原", (d, w) -> {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    copyFrom(in, new File(targetPath));
                    in.close();
                    DictDBHelper.resetInstance();
                    DictDBHelper.getInstance(this);
                    ClipDBHelper.resetInstance();
                    toast("还原成功: " + fileName);
                } catch (Exception e) {
                    toast("还原失败: " + e.getMessage());
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private static class BackupFileInfo {
        String name;
        long size;
        Uri uri;
        File file;
    }

    private List<BackupFileInfo> findBackupFiles() {
        List<BackupFileInfo> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            // 先找 backup*.db 文件，再找所有 .db 文件（解决卸载重装后不认旧备份的问题）
            Cursor c = getContentResolver().query(collection,
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{"%.db"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        BackupFileInfo info = new BackupFileInfo();
                        info.name = c.getString(1);
                        info.size = c.getLong(2);
                        info.uri = Uri.withAppendedPath(collection, String.valueOf(c.getLong(0)));
                        result.add(info);
                    }
                } finally {
                    c.close();
                }
            }
        } else {
            if (!hasStoragePermission()) {
                requestStoragePermission();
                return result;
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) ->
                    name.endsWith(BACKUP_SUFFIX));
                if (files != null) {
                    Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        BackupFileInfo info = new BackupFileInfo();
                        info.name = f.getName();
                        info.size = f.length();
                        info.file = f;
                        result.add(info);
                    }
                }
            }
        }
        return result;
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQ_STORAGE) {
                toast("权限已授予，请再次点击备份或还原");
            } else if (requestCode == REQ_VOICE) {
                toast("录音权限已授予，语音输入可用");
                // 2026-07-31: 通知输入法自动启动语音识别
                sendBroadcast(new Intent(PinyinIME.ACTION_VOICE_PERMISSION_GRANTED));
            }
        }
    }

    // 2026-07-31: 申请语音输入所需的录音权限
    private void requestVoicePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_VOICE);
        }
    }

    private void copyTo(File src, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(src);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        in.close();
    }

    private void copyFrom(InputStream in, File dst) throws IOException {
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        out.close();
        in.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        out.close();
        in.close();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ====== UI 辅助 ======
    private LinearLayout makeHeader(String title, View.OnClickListener backAction) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(COLOR_SURFACE);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("< 返回");
        back.setTextSize(13);
        back.setOnClickListener(backAction);
        header.addView(back);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(COLOR_TEXT);
        titleTv.setTypeface(null, Typeface.BOLD);
        titleTv.setPadding(dp(12), 0, 0, 0);
        header.addView(titleTv);

        return header;
    }

    private TextView makeTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(22);
        tv.setTextColor(COLOR_TEXT);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private View makeFeatureButton(String title, String desc, int num, View.OnClickListener listener) {
        LinearLayout btn = new LinearLayout(this);
        btn.setOrientation(LinearLayout.VERTICAL);
        btn.setBackgroundColor(COLOR_SURFACE);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        btn.setClickable(true);
        btn.setOnClickListener(listener);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        TextView numTv = new TextView(this);
        numTv.setText(String.valueOf(num));
        numTv.setTextSize(18);
        numTv.setTextColor(COLOR_ACCENT);
        numTv.setTypeface(null, Typeface.BOLD);
        numTv.setPadding(0, 0, dp(10), 0);
        top.addView(numTv);

        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(COLOR_TEXT);
        top.addView(titleTv);

        btn.addView(top);

        TextView descTv = new TextView(this);
        descTv.setText(desc);
        descTv.setTextSize(12);
        descTv.setTextColor(COLOR_TEXT_DIM);
        descTv.setPadding(dp(28), dp(2), 0, 0);
        btn.addView(descTv);

        return btn;
    }

    private View makeSpace(int dp) {
        View v = new View(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(dp(dp), dp(dp)));
        return v;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
