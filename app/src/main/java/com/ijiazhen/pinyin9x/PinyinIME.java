package com.ijiazhen.pinyin9x;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;

import java.util.*;

/**
 * 九宫格拼音输入法 - InputMethodService 主类
 * 行业最佳实践: View 缓存、InputConnection 安全调用、生命周期管理
 */
public class PinyinIME extends InputMethodService {

    // === 模式常量 ===
    private static final int MODE_PINYIN = 0;
    private static final int MODE_QWERTY = 1;
    private static final int MODE_SYMBOL = 2;
    private static final int MODE_CURSOR = 3;
    private static final int MODE_NUMBER = 4;
    private static final int MODE_CLIPBOARD = 5;
    private static final int MODE_FAVORITES = 6;
    private static final int MODE_SETTINGS = 7; // 2026-08-01: 设置面板

    // === 状态 ===
    private int currentMode = MODE_PINYIN;
    private boolean shiftOn = false;
    private boolean capsLock = false;
    private StringBuilder pinyinDigits = new StringBuilder();
    private List<PinyinEngine.Candidate> candidates = new ArrayList<>();
    private int candidatePage = 0;
    private static final int PAGE_SIZE = 8;
    private boolean selMode = false;
    private KeyButton selToggleBtn;
    private TextView expandToggleBtn;
    private boolean expandMode = false;
    private int expandPage = 0;
    private static final int EXPAND_LOAD = 20;
    private boolean composingExpandMode = false;
    private int composingExpandPage = 0;
    private static final int COMPOSING_EXPAND_LOAD = 28;

    // === 三步上栏状态 ===
    private boolean isComposing = false;
    private List<List<String>> composingPinyinOptions;
    private List<String> composingPinyins;
    private StringBuilder composingChars;
    private int composingIndex;
    private int composingCharPage;
    private int lastComposingIndex = -1;
    private String composingDigitStr;
    private static final int COMPOSING_PAGE_SIZE = 8;

    // === 撤消栈 ===
    private LinkedList<String> undoStack = new LinkedList<>();
    private static final int MAX_UNDO = 50;

    // 2026-07-29: 手动输入缓冲区，用于热词自动拆分学习
    private StringBuilder manualInputBuffer = new StringBuilder();
    private static final int MAX_BUFFER_LEN = 200;

    // 2026-07-29: 语音输入
    private boolean voiceInputActive = false;
    private SherpaOnnxVoiceRecognizer sherpaRecognizer;
    private Handler voiceHandler;
    private Runnable voiceTimeoutTask;
    private Runnable voiceStatusHideTask;
    private int voiceCommittedLen = 0;
    private View voiceKeyView;
    private ValueAnimator voiceAnimator;
    private static final int VOICE_TIMEOUT_MS = 60000;
    // 2026-07-31: MainActivity 授权录音成功后通知输入法自动启动语音识别
    public static final String ACTION_VOICE_PERMISSION_GRANTED = "com.ijiazhen.pinyin9x.VOICE_PERMISSION_GRANTED";
    private BroadcastReceiver voicePermReceiver;

    // [移除热句功能-2026-08-05 05:27:52] 已删除热句渐进式补全状态变量及 lastCommitted

    // === 2026-08-02: N-Gram 邻接联想状态 ===
    private String lastContext = "";              // 上一次上屏的中文文本（非标点），用于逐词邻接学习
    private String segmentFirstWord = "";         // 当前标点段内的第一个词，用于短语邻接学习
    // [增强邻接学习-2026-08-05] 段首词之后的累计文本，用于学习 段首词→后续完整短语
    private String segmentAfterFirst = "";

    // === 符号键盘状态 ===
    // 2026-07-27: 去除分类tab，保留分类标题直接滚动浏览全部符号
    private static final java.util.Map<String, String> BRACKET_PAIRS = new java.util.HashMap<>();
    static {
        BRACKET_PAIRS.put("(", ")");
        BRACKET_PAIRS.put("[", "]");
        BRACKET_PAIRS.put("{", "}");
        // 2026-07-31: 小于号属于数字运算符，不参与自动配对
        BRACKET_PAIRS.put("\u300a", "\u300b");
        BRACKET_PAIRS.put("\u3010", "\u3011");
    }
    private static final String[] SYMBOL_CAT_NAMES = {
        "中文符号", "半角符号", "括号", "数学运算", "特殊符号"
    };
    private static final String[][] SYMBOL_CAT_DATA = {
        // 中文符号 (16个)
        {"，", "。", "？", "！", "；", "：", "、", "…",
         "‘", "’", "\u201c", "\u201d", "【", "】", "《", "》"},
        // 半角符号 (24个)
        {",", ".", "?", "!", ";", ":", "\"", "'",
         "-", "_", "—", "~", "@", "#", "$", "%",
         "\\", "/", "&", "|", "^", "*", "`", "·"},
        // 括号 (8个)
        {"(", ")", "[", "]", "{", "}", "<", ">"},
        // 数学运算 (16个)
        {"+", "-", "×", "÷", "=", "≈", "≠", "±",
         "<", ">", "≤", "≥", "∑", "∏", "√", "∞"},
        // 特殊符号 (16个)
        {"©", "®", "™", "°", "℃", "￥", "€", "£",
         "★", "☆", "◆", "◇", "●", "○", "■", "□"},
    };
    // === 视图 ===
    private LinearLayout rootView;
    private LinearLayout candidateBar;
    private TextView pinyinText;
    private HorizontalScrollView candidateScroll;
    private LinearLayout candidateList;
    private LinearLayout navBar;
    private FrameLayout keyboardContainer;
    private View currentKeyboard;

    // 各键盘视图（缓存）
    private View kbPinyin, kbQwerty, kbSymbol, kbCursor, kbNumber, kbClipboard, kbFavorites, kbSettings;

    // 展开面板
    private View expandPanel;

    // === 管理器 ===
    private ClipDBHelper clipDb;
    private SharedPreferences prefs;

    // === 颜色 (白色主题) ===
    private static final int COLOR_BG = 0xFFF2F2F7;
    private static final int COLOR_SURFACE = 0xFFFFFFFF;
    private static final int COLOR_KEY_BG = 0xFFFFFFFF;
    private static final int COLOR_KEY_HOVER = 0xFFDCDCDC;
    private static final int COLOR_ACCENT = 0xFF007AFF;
    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_TEXT_DIM = 0xFF8E8E93;
    private static final int COLOR_TEXT_BRIGHT = 0xFF1C1C1E;
    private static final int COLOR_CANDIDATE_BG = 0xFFE8E8ED;
    private static final int COLOR_NAV_BG = 0xFFF9F9FB;
    private static final int COLOR_NAV_ACTIVE = 0xFF007AFF;
    private static final int COLOR_DIVIDER = 0xFFDCDCE0;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("pinyin_ime", Context.MODE_PRIVATE);
        PinyinEngine.init(this);
        // [移除热句功能-2026-08-05 05:27:52] 已删除 seedHotSentences 调用
        clipDb = ClipDBHelper.getInstance(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.addPrimaryClipChangedListener(() -> {
                android.content.ClipData cd = cm.getPrimaryClip();
                if (cd != null && cd.getItemCount() > 0) {
                    CharSequence cs = cd.getItemAt(0).getText();
                    if (cs != null && cs.length() > 0) {
                        // 1. 保存到应用内剪切列表
                        clipDb.addClip(cs.toString());
                        // 2. 立即清空系统剪切板，防止第三方读取
                        //    用 post 延迟，确保本次回调完成后再清空，避免递归
                        if (voiceHandler == null) voiceHandler = new Handler(Looper.getMainLooper());
                        voiceHandler.post(() -> {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    cm.clearPrimaryClip();
                                } else {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                }
            });
        }
        // 2026-07-31: 接收 MainActivity 授权录音成功通知，自动启动语音识别
        voicePermReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_VOICE_PERMISSION_GRANTED.equals(intent.getAction()) && !voiceInputActive) {
                    startVoiceInput(voiceKeyView);
                }
            }
        };
        IntentFilter vf = new IntentFilter(ACTION_VOICE_PERMISSION_GRANTED);
        // 2026-07-31: Android 13+ 动态注册广播必须指定导出标志，否则运行时抛 SecurityException
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voicePermReceiver, vf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(voicePermReceiver, vf);
        }
    }

    @Override
    public View onCreateInputView() {
        if (rootView != null) return rootView;
        buildUI();
        return rootView;
    }

    @Override
    public View onCreateCandidatesView() {
        // 候选栏直接集成在键盘视图中，不使用系统 candidates view
        return null;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // 智能键盘选择：纯数字验证码才打开数字键盘，否则默认九宫中文
        if (info != null && isNumericVerification(info)) {
            switchToKeyboard(MODE_NUMBER);
        } else {
            switchToKeyboard(MODE_PINYIN);
        }
        updateCandidates();
    }

    private boolean isNumericVerification(EditorInfo info) {
        int inputType = info.inputType & 0x0000000F; // type mask
        if (inputType == android.text.InputType.TYPE_CLASS_NUMBER
            || inputType == android.text.InputType.TYPE_CLASS_PHONE) {
            return true;
        }
        // 检查 numeric 或 numberPassword
        int variation = info.inputType & 0x000000F0;
        if (inputType == android.text.InputType.TYPE_CLASS_NUMBER
            && (variation == android.text.InputType.TYPE_NUMBER_VARIATION_NORMAL
                || variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            return true;
        }
        return false;
    }

    // 2026-07-29: 收起键盘时触发热词学习
    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        if (currentMode == MODE_PINYIN) {
            splitAndLearn();
            // [增强邻接学习-2026-08-05] 收起键盘时兜底学习 段首词→后续短语（用户未输入标点的情况）
            if (!segmentFirstWord.isEmpty()) {
                learnAdjacencyFromScreen(getCurrentInputConnection(), segmentFirstWord);
                segmentFirstWord = "";
                segmentAfterFirst = "";
                lastContext = "";
            }
        }
    }

    // 2026-07-29: 销毁时清理语音资源
    @Override
    public void onDestroy() {
        super.onDestroy();
        // 2026-07-31: 注销权限通知广播
        if (voicePermReceiver != null) {
            try {
                unregisterReceiver(voicePermReceiver);
            } catch (Exception ignored) {}
            voicePermReceiver = null;
        }
        // 2026-08-01: 销毁 Sherpa-ONNX 识别器
        if (sherpaRecognizer != null) {
            sherpaRecognizer.destroy();
            sherpaRecognizer = null;
        }
        if (voiceInputActive) stopVoiceInput(); // 2026-08-01: 销毁时停止语音
        // 2026-08-01: 清理延迟隐藏任务
        if (voiceStatusHideTask != null && voiceHandler != null) {
            voiceHandler.removeCallbacks(voiceStatusHideTask);
            voiceStatusHideTask = null;
        }
        cancelVoiceTimeout();
        stopVoiceAnimation();
    }

    // ====== UI 构建 ======
    private void buildUI() {
        Context ctx = this;
        rootView = new LinearLayout(ctx);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(COLOR_BG);

        // 候选栏
        buildCandidateBar(ctx);

        // 导航栏
        buildNavBar(ctx);

        // 键盘容器 — 统一 0.32 屏高度
        keyboardContainer = new FrameLayout(ctx);
        int halfScreenHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.33f);
        keyboardContainer.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, halfScreenHeight));

        // 构建所有键盘
        kbPinyin = buildPinyinKeyboard(ctx);
        kbQwerty = buildQwertyKeyboard(ctx);
        kbSymbol = buildSymbolKeyboard(ctx);
        kbCursor = buildCursorKeyboard(ctx);
        kbNumber = buildNumberKeyboard(ctx);
        kbClipboard = buildClipboardPanel(ctx);
        kbFavorites = buildFavoritesPanel(ctx);
        kbSettings = buildSettingsPanel(ctx);

        FrameLayout.LayoutParams kbLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        keyboardContainer.addView(kbPinyin, kbLp);
        keyboardContainer.addView(kbQwerty, kbLp);
        keyboardContainer.addView(kbSymbol, kbLp);
        keyboardContainer.addView(kbCursor, kbLp);
        keyboardContainer.addView(kbNumber, kbLp);
        keyboardContainer.addView(kbClipboard, kbLp);
        keyboardContainer.addView(kbFavorites, kbLp);
        keyboardContainer.addView(kbSettings, kbLp);

        currentKeyboard = kbPinyin;
        switchToKeyboard(MODE_PINYIN);

        rootView.addView(keyboardContainer);
    }

    private void buildCandidateBar(Context ctx) {
        candidateBar = new LinearLayout(ctx);
        candidateBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable candidateBorder = new GradientDrawable();
        candidateBorder.setColor(COLOR_SURFACE);
        candidateBorder.setStroke(dp(1), COLOR_TEXT_DIM, dp(5), dp(3));
        candidateBar.setBackground(candidateBorder);
        candidateBar.setPadding(8, 6, 8, 4);

        pinyinText = new TextView(ctx);
        pinyinText.setTextSize(15);
        pinyinText.setTextColor(COLOR_TEXT_DIM);
        pinyinText.setMinHeight(dp(20));

        LinearLayout candidateRow = new LinearLayout(ctx);
        candidateRow.setOrientation(LinearLayout.HORIZONTAL);
        candidateRow.setGravity(Gravity.CENTER_VERTICAL);

        // 2026-07-29: 候选栏最左边添加收起键盘按钮
        TextView hideBtn = new TextView(ctx);
        hideBtn.setText("\u24D2");
        hideBtn.setTextSize(18);
        hideBtn.setTextColor(COLOR_ACCENT);
        hideBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        hideBtn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hideBtn.setOnClickListener(v -> requestHideSelf(0));

        candidateScroll = new HorizontalScrollView(ctx);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        candidateScroll.setLayoutParams(new LinearLayout.LayoutParams(
            0, dp(38), 1));
        candidateList = new LinearLayout(ctx);
        candidateList.setOrientation(LinearLayout.HORIZONTAL);
        candidateList.setGravity(Gravity.CENTER_VERTICAL);
        candidateScroll.addView(candidateList, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));

        TextView expandBtn = new TextView(ctx);
        expandBtn.setText("\u25BC");
        expandBtn.setTextSize(14);
        expandBtn.setTextColor(COLOR_ACCENT);
        expandBtn.setPadding(dp(4), dp(4), dp(6), dp(4));
        expandBtn.setOnClickListener(v -> toggleExpand());

        candidateRow.addView(hideBtn);
        candidateRow.addView(candidateScroll);
        candidateRow.addView(expandBtn);

        candidateBar.addView(pinyinText);
        candidateBar.addView(candidateRow);
        rootView.addView(candidateBar);
    }

    private void buildNavBar(Context ctx) {
        navBar = new LinearLayout(ctx);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(COLOR_NAV_BG);
        navBar.setPadding(0, 0, 0, 0);

        LinearLayout navRow = new LinearLayout(ctx);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setBackgroundColor(COLOR_NAV_BG);
        navRow.setPadding(0, 0, 0, 0);

        // 2026-08-02: 「设置」弹出子菜单，「说」语音录入
        String[] labels = {"设置", "说", "光标", "英", "符", "数", "中"};
        final int[] modes = {MODE_FAVORITES, MODE_CLIPBOARD, MODE_CURSOR, MODE_QWERTY, MODE_SYMBOL, MODE_NUMBER, MODE_PINYIN};

        for (int i = 0; i < labels.length; i++) {
            final int mode = modes[i];
            TextView btn = new TextView(ctx);
            btn.setText(labels[i]);
            btn.setTextSize(14);
            btn.setTextColor(COLOR_TEXT_DIM);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(2), dp(8), dp(2), dp(8));
            // 2026-08-01: 「设置」弹出子菜单（收藏列表、剪切列表、语音录入）
            if (i == 0) {
                btn.setOnClickListener(v -> switchToKeyboard(MODE_SETTINGS));
            } else if (i == 1) {
                // 2026-08-02: 「说」语音录入按钮
                btn.setOnClickListener(v -> voiceToggle(btn));
            } else {
                btn.setOnClickListener(v -> switchToKeyboard(mode));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            navRow.addView(btn, lp);
            btn.setTag("nav_" + mode);
        }

        navBar.addView(navRow);
        rootView.addView(navBar);
    }

    /**
     * 2026-08-01: 设置弹出面板 — 整页铺开，和符号键盘一样
     */
    private View buildSettingsPanel(Context ctx) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackgroundColor(COLOR_BG);

        // 标题
        TextView title = new TextView(ctx);
        title.setText("功能面板");
        title.setTextSize(16);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        panel.addView(title);

        // 收藏列表按钮
        Button favBtn = makePanelButton(ctx, "收藏列表");
        favBtn.setOnClickListener(v -> switchToKeyboard(MODE_FAVORITES));
        panel.addView(favBtn);

        // 剪切列表按钮
        Button clipBtn = makePanelButton(ctx, "剪切列表");
        clipBtn.setOnClickListener(v -> switchToKeyboard(MODE_CLIPBOARD));
        panel.addView(clipBtn);

        // 语音录入按钮 — 可切换开/关，激活时闪烁
        Button settingsVoiceBtn = makePanelButton(ctx, "语音录入");
        settingsVoiceBtn.setOnClickListener(v -> {
            if (voiceInputActive) {
                stopVoiceInput();
                settingsVoiceBtn.setText("语音录入");
                settingsVoiceBtn.setBackgroundColor(COLOR_KEY_BG);
                settingsVoiceBtn.setTextColor(COLOR_TEXT);
            } else {
                settingsVoiceBtn.setBackgroundColor(COLOR_ACCENT);
                settingsVoiceBtn.setTextColor(0xFFFFFFFF);
                startVoiceInput(settingsVoiceBtn);
                settingsVoiceBtn.setText("语音录入 ●");
            }
        });
        panel.addView(settingsVoiceBtn);

        // 2026-08-01: 如果此前已激活语音，恢复闪烁
        if (voiceInputActive) {
            settingsVoiceBtn.setBackgroundColor(COLOR_ACCENT);
            settingsVoiceBtn.setTextColor(0xFFFFFFFF);
            settingsVoiceBtn.setText("语音录入 ●");
            voiceKeyView = settingsVoiceBtn;
            startVoiceAnimation();
        }

        return panel;
    }

    /**
     * 2026-08-01: 刷新设置面板中的语音按钮状态
     */
    private void refreshSettingsPanel() {
        if (kbSettings == null) return;
        // 重建设置面板以刷新语音按钮状态
        LinearLayout parent = (LinearLayout) kbSettings;
        parent.removeAllViews();

        Context ctx = this;
        TextView title = new TextView(ctx);
        title.setText("功能面板");
        title.setTextSize(16);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        parent.addView(title);

        Button favBtn = makePanelButton(ctx, "收藏列表");
        favBtn.setOnClickListener(v -> switchToKeyboard(MODE_FAVORITES));
        parent.addView(favBtn);

        Button clipBtn = makePanelButton(ctx, "剪切列表");
        clipBtn.setOnClickListener(v -> switchToKeyboard(MODE_CLIPBOARD));
        parent.addView(clipBtn);

        Button settingsVoiceBtn = makePanelButton(ctx, voiceInputActive ? "语音录入 ●" : "语音录入");
        if (voiceInputActive) {
            settingsVoiceBtn.setBackgroundColor(COLOR_ACCENT);
            settingsVoiceBtn.setTextColor(0xFFFFFFFF);
            voiceKeyView = settingsVoiceBtn;
            startVoiceAnimation();
        }
        settingsVoiceBtn.setOnClickListener(v -> {
            if (voiceInputActive) {
                stopVoiceInput();
                parent.removeAllViews();
                refreshSettingsPanel();
            } else {
                settingsVoiceBtn.setBackgroundColor(COLOR_ACCENT);
                settingsVoiceBtn.setTextColor(0xFFFFFFFF);
                startVoiceInput(settingsVoiceBtn);
                settingsVoiceBtn.setText("语音录入 ●");
            }
        });
        parent.addView(settingsVoiceBtn);
    }

    private Button makePanelButton(Context ctx, String text) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setTextColor(COLOR_TEXT);
        btn.setAllCaps(false);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        btn.setLayoutParams(lp);
        return btn;
    }

    private void switchToKeyboard(int mode) {
        // 2026-08-05 02:40: 切换键盘时取消候选栏确认状态
        cancelCandidateConfirm();
        currentMode = mode;
        shiftOn = false;
        capsLock = false;
        selMode = false;
        if (selToggleBtn != null) selToggleBtn.setBackgroundColor(COLOR_KEY_BG);

        kbPinyin.setVisibility(mode == MODE_PINYIN ? View.VISIBLE : View.GONE);
        kbQwerty.setVisibility(mode == MODE_QWERTY ? View.VISIBLE : View.GONE);
        kbSymbol.setVisibility(mode == MODE_SYMBOL ? View.VISIBLE : View.GONE);
        kbCursor.setVisibility(mode == MODE_CURSOR ? View.VISIBLE : View.GONE);
        kbNumber.setVisibility(mode == MODE_NUMBER ? View.VISIBLE : View.GONE);
        kbClipboard.setVisibility(mode == MODE_CLIPBOARD ? View.VISIBLE : View.GONE);
        kbFavorites.setVisibility(mode == MODE_FAVORITES ? View.VISIBLE : View.GONE);
        kbSettings.setVisibility(mode == MODE_SETTINGS ? View.VISIBLE : View.GONE);

        // 2026-08-01: 更新导航栏高亮
        // 切换键盘时：如果不是切到设置面板，停止语音并隐藏状态提示
        if (voiceInputActive && mode != MODE_SETTINGS) {
            stopVoiceInput();
            hideVoiceStatus();
        }
        LinearLayout navRow = (LinearLayout) navBar.getChildAt(0);
        for (int i = 0; i < navRow.getChildCount(); i++) {
            TextView btn = (TextView) navRow.getChildAt(i);
            String tag = (String) btn.getTag();
            boolean active = tag.equals("nav_" + mode);
            btn.setTextColor(active ? COLOR_NAV_ACTIVE : COLOR_TEXT_DIM);
            btn.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }

        // 任何键盘切换都清空候选项
        pinyinDigits.setLength(0);
        candidates.clear();
        candidatePage = 0;
        if (expandMode || composingExpandMode) {
            expandMode = false;
            composingExpandMode = false;
            if (expandPanel != null) expandPanel.setVisibility(View.GONE);
            if (currentKeyboard != null) currentKeyboard.setVisibility(View.VISIBLE);
        }
        isComposing = false;
        composingDigitStr = null;
        composingPinyinOptions = null;
        composingPinyins = null;
        composingChars = null;
        composingIndex = 0;
        composingCharPage = 0;
        lastComposingIndex = 0;
        updateCandidates();

        // 刷新特殊面板
        if (mode == MODE_CLIPBOARD) refreshClipboardList();
        if (mode == MODE_FAVORITES) refreshFavoritesList();
        // 2026-07-27: 切换到符号键盘时刷新内容，修复空白bug
        if (mode == MODE_SYMBOL) refreshSymbolKeyboard();
        if (mode == MODE_SETTINGS) refreshSettingsPanel();

        updateShiftVisual();
    }

    // ====== 键盘构建 (使用嵌套 LinearLayout，行业推荐做法) ======
    // 2026-07-28 T010+T011+T012: 重构为5×4面板，左侧标点列+底部功能键
    private View buildPinyinKeyboard(Context ctx) {
        LinearLayout kb = new LinearLayout(ctx);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(2), dp(2), dp(2), dp(2));

        // 2026-07-29: 隐藏数字2~9，只显示字母
        String[] t9digits = {"@","2","3","4","5","6","7","8","9"};
        String[] t9labels = {"@","abc","def","ghi","jkl","mno","pqrs","tuv","wxyz"};
        String[] puncts = {"，", "。", "？", "：", "（）"};
        int[] rows5 = {0, 1, 2, 3, 4};

        for (int r : rows5) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);

            // 左侧标点键
            final String punct = puncts[r];
            KeyButton punctBtn = makeKey(ctx, punct, null, COLOR_KEY_BG);
            punctBtn.setOnClickListener(v -> {
                commitText(punct);
                splitAndLearn();
            });
            row.addView(punctBtn, keyLp(1));

            if (r < 3) {
                // T9 数字行: 左侧标点 + 3个字母键
                for (int c = 0; c < 3; c++) {
                    int idx = r * 3 + c;
                    KeyButton btn = makeKey(ctx, t9labels[idx], null, COLOR_KEY_BG);
                    if (idx == 0) {
                        // 2026-07-29: 键1改为语音输入，短按@，长按切换语音
                        btn.setLabel("@", null);
                        btn.setLongClickable(true);
                        btn.setOnClickListener(v -> commitText("@"));
                        btn.setOnLongClickListener(v -> { voiceToggle(btn); return true; });
                        voiceKeyView = btn;
                    } else {
                        final String digit = t9digits[idx];
                        btn.setOnClickListener(v -> onT9Digit(digit));
                        // 2026-07-29: 统一字母键字号，避免 pqrs/wxyz 因长度>3 变小
                        btn.setTextSizes(16, 0);
                    }
                    row.addView(btn, keyLp(1));
                }
            } else if (r == 3) {
                // 第四行: 撤消 | 清空 | 删除
                KeyButton undoBtn = makeKey(ctx, "撤消", null, COLOR_KEY_BG);
                undoBtn.setOnClickListener(v -> onUndo());
                row.addView(undoBtn, keyLp(1));
                KeyButton clearBtn = makeKey(ctx, "清空", null, COLOR_KEY_BG);
                clearBtn.setOnClickListener(v -> onClear());
                row.addView(clearBtn, keyLp(1));
                KeyButton delBtn = makeKey(ctx, "删除", null, COLOR_KEY_BG);
                delBtn.setOnClickListener(v -> onBackspace());
                row.addView(delBtn, keyLp(1));
            } else {
                // 第五行: 空格 | 换行 | 查找
                KeyButton spaceBtn = makeKey(ctx, "空格", null, COLOR_KEY_BG);
                spaceBtn.setOnClickListener(v -> onSpace());
                row.addView(spaceBtn, keyLp(1));
                KeyButton newlineBtn = makeKey(ctx, "\u21B5", null, COLOR_KEY_BG);
                newlineBtn.setOnClickListener(v -> {
                    commitText("\n");
                    splitAndLearn();
                });
                row.addView(newlineBtn, keyLp(1));
                KeyButton searchBtn = makeKey(ctx, "查找", null, COLOR_KEY_BG);
                searchBtn.setOnClickListener(v -> doWordSearch());
                row.addView(searchBtn, keyLp(1));
            }
            kb.addView(row);
        }

        return kb;
    }

    private View buildQwertyKeyboard(Context ctx) {
        LinearLayout kb = new LinearLayout(ctx);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(2), dp(2), dp(2), dp(2));

        // Row 1
        String[] row1 = {"q","w","e","r","t","y","u","i","o","p"};
        kb.addView(makeKeyRow(ctx, row1));

        // Row 2
        String[] row2 = {"a","s","d","f","g","h","j","k","l"};
        kb.addView(makeKeyRow(ctx, row2));

        // Row 3
        LinearLayout row3 = new LinearLayout(ctx);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        KeyButton shiftBtn = makeKey(ctx, "\u21E7", null, COLOR_KEY_BG);
        shiftBtn.setTag("shift_key");
        shiftBtn.setOnClickListener(v -> {
            capsLock = false;
            shiftOn = !shiftOn;
            updateShiftVisual();
        });
        shiftBtn.setOnLongClickListener(v -> {
            capsLock = true;
            shiftOn = true;
            updateShiftVisual();
            return true;
        });
        row3.addView(shiftBtn, keyLp(1));

        String[] row3b = {"z","x","c","v","b","n","m"};
        for (String s : row3b) {
            KeyButton btn = makeKey(ctx, s, null, COLOR_KEY_BG);
            btn.setOnClickListener(v -> onQwertyKey(s));
            row3.addView(btn, keyLp(1));
        }

        KeyButton commaBtn2 = makeKey(ctx, ",", null, COLOR_KEY_BG);
        commaBtn2.setOnClickListener(v -> onQwertyKey(","));
        row3.addView(commaBtn2, keyLp(1));
        kb.addView(row3);

        // Row 4
        LinearLayout row4 = new LinearLayout(ctx);
        row4.setOrientation(LinearLayout.HORIZONTAL);

        KeyButton delBtn = makeKey(ctx, "\u5220\u9664", null, COLOR_KEY_BG);
        delBtn.setOnClickListener(v -> onBackspace());
        row4.addView(delBtn, keyLp(1));

        KeyButton clearBtn2 = makeKey(ctx, "\u6e05\u7a7a", null, COLOR_KEY_BG);
        clearBtn2.setOnClickListener(v -> onClear());
        row4.addView(clearBtn2, keyLp(1));

        KeyButton spaceBtn2 = makeKey(ctx, "空格", null, COLOR_KEY_BG);
        spaceBtn2.setOnClickListener(v -> onSpace());
        row4.addView(spaceBtn2, keyLp(1));

        KeyButton dotBtn = makeKey(ctx, ".", null, COLOR_KEY_BG);
        dotBtn.setOnClickListener(v -> onQwertyKey("."));
        row4.addView(dotBtn, keyLp(1));

        KeyButton enterBtn = makeKey(ctx, "\u21B5", null, COLOR_KEY_BG);
        enterBtn.setOnClickListener(v -> onEnter());
        row4.addView(enterBtn, keyLp(1));
        kb.addView(row4);

        // Row 5: 撤销 剪切 复制 粘贴 光标操控
        LinearLayout row5 = new LinearLayout(ctx);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        addCursorKey(row5, ctx, "\u64a4\u6d88", 16, 1);
        addCursorKey(row5, ctx, "\u526a\u5207", 12, 1);
        addCursorKey(row5, ctx, "\u590d\u5236", 13, 1);
        addCursorKey(row5, ctx, "\u7c98\u8d34", 14, 1);
        KeyButton cursorBtn = makeKey(ctx, "\u5149\u6807", null, COLOR_KEY_BG);
        cursorBtn.setOnClickListener(v -> switchToKeyboard(MODE_CURSOR));
        row5.addView(cursorBtn, keyLp(1));
        kb.addView(row5);

        return kb;
    }

    private LinearLayout makeKeyRow(Context ctx, String[] keys) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String s : keys) {
            KeyButton btn = makeKey(ctx, s, null, COLOR_KEY_BG);
            btn.setOnClickListener(v -> onQwertyKey(s));
            row.addView(btn, keyLp(1));
        }
        return row;
    }

    private void updateShiftVisual() {
        View shiftBtn = rootView.findViewWithTag("shift_key");
        if (shiftBtn != null) {
            if (capsLock) {
                shiftBtn.setBackgroundColor(0xFFFF9500);
            } else {
                shiftBtn.setBackgroundColor(shiftOn ? COLOR_ACCENT : COLOR_KEY_BG);
            }
        }
        updateQwertyCase(kbQwerty);
    }

    private void updateQwertyCase(View parent) {
        if (parent instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) parent;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof KeyButton) {
                    KeyButton kb = (KeyButton) child;
                    String label = kb.getMainLabel();
                    if (label != null && label.length() == 1 && Character.isLetter(label.charAt(0))) {
                        kb.setLabel(shiftOn ? label.toUpperCase() : label.toLowerCase(), null);
                    }
                } else if (child instanceof ViewGroup) {
                    updateQwertyCase(child);
                }
            }
        }
    }

    private KeyButton makeKey(Context ctx, String label, String subLabel, int bgColor) {
        KeyButton btn = new KeyButton(ctx);
        btn.setLabel(label, subLabel);
        btn.setBackgroundColor(bgColor);
        btn.setTextSizes(16, 0);
        return btn;
    }

    private View buildSymbolKeyboard(Context ctx) {
        LinearLayout kb = new LinearLayout(ctx);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(2), dp(2), dp(2), dp(2));
        kb.setTag("symbol_kb");

        // 2026-07-27: 删除分类tab，直接滚动浏览全部符号
        // 符号滚动区域 — 单页垂直滚动，包含全部分类
        ScrollView symScroll = new ScrollView(ctx);
        symScroll.setTag("symbol_scroll");
        LinearLayout symContainer = new LinearLayout(ctx);
        symContainer.setOrientation(LinearLayout.VERTICAL);
        symContainer.setTag("symbol_container");
        symScroll.addView(symContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        kb.addView(symScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        refreshSymbolKeyboard();
        return kb;
    }

    private void refreshSymbolKeyboard() {
        if (kbSymbol == null) return;

        // 2026-07-27: 去除分类tab，直接渲染全部分类带标题滚动浏览
        // 渲染全部分类到单一滚动容器
        View container = kbSymbol.findViewWithTag("symbol_container");
        if (!(container instanceof LinearLayout)) return;
        LinearLayout symContainer = (LinearLayout) container;
        symContainer.removeAllViews();
        Context ctx = this;

        for (int cat = 0; cat < SYMBOL_CAT_NAMES.length; cat++) {
            // 分类标题
            TextView header = new TextView(ctx);
            header.setText(SYMBOL_CAT_NAMES[cat]);
            header.setTextSize(13);
            header.setTextColor(COLOR_TEXT_DIM);
            header.setPadding(dp(4), dp(6), dp(4), dp(4));
            header.setTag("sym_header_" + cat);
            symContainer.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 符号网格
            String[] data = SYMBOL_CAT_DATA[cat];
            int totalRows = (data.length + 3) / 4;
            for (int row = 0; row < totalRows; row++) {
                LinearLayout rowLayout = new LinearLayout(ctx);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                for (int col = 0; col < 4; col++) {
                    int idx = row * 4 + col;
                    if (idx < data.length) {
                        final String sym = data[idx];
                        KeyButton btn = makeKey(ctx, sym, null, COLOR_KEY_BG);
                        btn.setOnClickListener(v -> commitSymbol(sym));
                        rowLayout.addView(btn, keyLp(1));
                    } else {
                        View empty = new View(ctx);
                        rowLayout.addView(empty, keyLp(1));
                    }
                }
                symContainer.addView(rowLayout);
            }
        }

        // 2026-07-27: 去除自动滚动定位，用户自行滚动浏览
    }

    // 2026-07-28 T015: 光标键盘文字替换
    private View buildCursorKeyboard(Context ctx) {
        LinearLayout kb = new LinearLayout(ctx);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(2), dp(2), dp(2), dp(2));

        // Row 1: [Home] [↑] [End] [全选]
        LinearLayout r1 = new LinearLayout(ctx); r1.setOrientation(LinearLayout.HORIZONTAL);
        addCursorKey(r1, ctx, "Home", 2, 1);
        addCursorKey(r1, ctx, "\u2191", 1, 1);
        addCursorKey(r1, ctx, "End", 6, 1);
        addCursorKey(r1, ctx, "\u5168\u9009", 9, 1);
        kb.addView(r1);

        // 2026-07-29: Row 2 [←] [选择] [→] [复制] - 删除↔复制交换
        LinearLayout r2 = new LinearLayout(ctx); r2.setOrientation(LinearLayout.HORIZONTAL);
        addCursorKey(r2, ctx, "\u2190", 3, 1);
        selToggleBtn = makeKey(ctx, "\u9009\u62e9", null, COLOR_KEY_BG);
        selToggleBtn.setOnClickListener(v -> { selMode = !selMode; updateSelButton(); });
        r2.addView(selToggleBtn, keyLp(1));
        addCursorKey(r2, ctx, "\u2192", 5, 1);
        addCursorKey(r2, ctx, "\u590d\u5236", 13, 1);
        kb.addView(r2);

        // 2026-08-05 02:20: Row 3 [PgUp] [↓] [PgDn] [剪切] - 撤消↔PgUp、删除↔PgDn 交换后
        LinearLayout r3 = new LinearLayout(ctx); r3.setOrientation(LinearLayout.HORIZONTAL);
        addCursorKey(r3, ctx, "PgUp", 7, 1);
        addCursorKey(r3, ctx, "\u2193", 4, 1);
        addCursorKey(r3, ctx, "PgDn", 8, 1);
        addCursorKey(r3, ctx, "\u526a\u5207", 12, 1);
        kb.addView(r3);

        // 2026-08-05 02:20: Row 4 [收藏列表] [剪切列表] [粘贴] [删除] - 空格移出，删除移入
        LinearLayout r4 = new LinearLayout(ctx); r4.setOrientation(LinearLayout.HORIZONTAL);
        KeyButton favListBtn = makeKey(ctx, "\u6536\u85cf\u5217\u8868", null, COLOR_KEY_BG);
        favListBtn.setOnClickListener(v -> switchToKeyboard(MODE_FAVORITES));
        r4.addView(favListBtn, keyLp(1));
        KeyButton clipListBtn = makeKey(ctx, "\u526a\u5207\u5217\u8868", null, COLOR_KEY_BG);
        clipListBtn.setOnClickListener(v -> switchToKeyboard(MODE_CLIPBOARD));
        r4.addView(clipListBtn, keyLp(1));
        addCursorKey(r4, ctx, "\u7c98\u8d34", 14, 1);
        addCursorKey(r4, ctx, "\u5220\u9664", 15, 1);
        kb.addView(r4);

        // 2026-08-05 02:20: Row 5 [撤消] [空格] [清空] [换行] - 撤消/空格移入，PgUp/PgDn 移出
        LinearLayout r5 = new LinearLayout(ctx); r5.setOrientation(LinearLayout.HORIZONTAL);
        addCursorKey(r5, ctx, "\u64a4\u6d88", 16, 1);
        KeyButton spaceBtn5 = makeKey(ctx, "\u7a7a\u683c", null, COLOR_KEY_BG);
        spaceBtn5.setOnClickListener(v -> onSpace());
        r5.addView(spaceBtn5, keyLp(1));
        KeyButton clearBtn5 = makeKey(ctx, "\u6e05\u7a7a", null, COLOR_KEY_BG);
        clearBtn5.setOnClickListener(v -> onClear());
        r5.addView(clearBtn5, keyLp(1));
        KeyButton newlineBtn = makeKey(ctx, "\u21B5", null, COLOR_KEY_BG);
        newlineBtn.setOnClickListener(v -> commitText("\n"));
        r5.addView(newlineBtn, keyLp(1));
        kb.addView(r5);

        return kb;
    }

    private void addCursorKey(LinearLayout row, Context ctx, String label, int action, float weight) {
        if (label.isEmpty()) {
            View empty = new View(ctx);
            row.addView(empty, keyLp(weight));
            return;
        }
        KeyButton btn = makeKey(ctx, label, null, COLOR_KEY_BG);
        btn.setOnClickListener(v -> onCursorAction(action));
        row.addView(btn, keyLp(weight));
    }

    // 2026-07-28 T013+T014: 重构为5×4面板，左侧运算符+底部功能键
    private View buildNumberKeyboard(Context ctx) {
        LinearLayout kb = new LinearLayout(ctx);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setPadding(dp(2), dp(2), dp(2), dp(2));

        String[] ops = {"+", "-", "*", "/", "%"};
        int[] nums = {1,2,3,4,5,6,7,8,9};

        for (int r = 0; r < 5; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);

            if (r == 4) {
                // 2026-07-31: 第五行: 撤消 | 清空 | 删除 | 换行（% 改为换行，换行与撤消交换位置）
                KeyButton undoBtn = makeKey(ctx, "\u64a4\u6d88", null, COLOR_KEY_BG);
                undoBtn.setOnClickListener(v -> onUndoCursor());
                row.addView(undoBtn, keyLp(1));
                KeyButton clearBtn = makeKey(ctx, "\u6e05\u7a7a", null, COLOR_KEY_BG);
                clearBtn.setOnClickListener(v -> onClear());
                row.addView(clearBtn, keyLp(1));
                KeyButton delBtn = makeKey(ctx, "\u5220\u9664", null, COLOR_KEY_BG);
                delBtn.setOnClickListener(v -> onBackspace());
                row.addView(delBtn, keyLp(1));
                KeyButton newlineBtn = makeKey(ctx, "\u21B5", null, COLOR_KEY_BG);
                newlineBtn.setOnClickListener(v -> commitText("\n"));
                row.addView(newlineBtn, keyLp(1));
                kb.addView(row);
                continue;
            }

            // 左侧运算符
            final String opSym = ops[r];
            KeyButton opBtn = makeKey(ctx, opSym, null, COLOR_KEY_BG);
            opBtn.setOnClickListener(v -> commitText(opSym));
            row.addView(opBtn, keyLp(1));

            if (r < 3) {
                // 数字行
                for (int c = 0; c < 3; c++) {
                    final String digit = String.valueOf(nums[r * 3 + c]);
                    KeyButton btn = makeKey(ctx, digit, null, COLOR_KEY_BG);
                    btn.setOnClickListener(v -> onNumberKey(digit));
                    row.addView(btn, keyLp(1));
                }
            } else {
                // 第四行: . 0 =
                KeyButton dotBtn = makeKey(ctx, ".", null, COLOR_KEY_BG);
                dotBtn.setOnClickListener(v -> onNumberKey("."));
                row.addView(dotBtn, keyLp(1));
                KeyButton zeroBtn = makeKey(ctx, "0", null, COLOR_KEY_BG);
                zeroBtn.setOnClickListener(v -> onNumberKey("0"));
                row.addView(zeroBtn, keyLp(1));
                KeyButton eqBtn = makeKey(ctx, "=", null, COLOR_KEY_BG);
                eqBtn.setOnClickListener(v -> onCalculate());
                row.addView(eqBtn, keyLp(1));
            }
            kb.addView(row);
        }

        return kb;
    }

    // 2026-07-28 T018: 移除底部操作栏，操作按钮移至每条目内
    private View buildClipboardPanel(Context ctx) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setTag("clip_list");
        scroll.addView(listContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        panel.addView(scroll, llp);

        return panel;
    }

    private View buildFavoritesPanel(Context ctx) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setTag("fav_list");
        scroll.addView(listContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        panel.addView(scroll, llp);

        return panel;
    }

    // ====== 键盘输入处理 ======
    private void onT9Digit(String digit) {
        if (isComposing) return;
        // [移除热句功能-2026-08-05 05:27:52] 已删除 clearHotSentenceState 调用
        pinyinDigits.append(digit);
        candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
        updateCandidates();
    }

    private void onQwertyKey(String key) {
        String text = key;
        if (text.length() == 1 && Character.isLetter(text.charAt(0))) {
            text = (capsLock || shiftOn) ? text.toUpperCase() : text.toLowerCase();
            if (shiftOn && !capsLock) { shiftOn = false; updateShiftVisual(); }
        }
        commitText(text);
    }

    private void onSymbolKey(String sym) {
        commitText(sym);
    }

    private void onNumberKey(String num) {
        commitText(num);
    }

    // 2026-07-29: =号公式计算
    private void onCalculate() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { commitText("="); return; }
        CharSequence before = ic.getTextBeforeCursor(200, 0);
        if (before == null || before.length() == 0) { commitText("="); return; }
        String text = before.toString();
        // 从后往前提取数学表达式
        String expr = extractExpression(text);
        if (expr.isEmpty()) { commitText("="); return; }
        try {
            double result = evalExpression(expr);
            String resultStr;
            if (result == (long) result) {
                resultStr = String.valueOf((long) result);
            } else {
                resultStr = String.valueOf(result);
            }
            // 2026-08-01: 4 条候选：①完整算式 ②=结果 ③原式 ④纯结果
            pinyinDigits.setLength(0);
            candidates.clear();
            candidates.add(new PinyinEngine.Candidate(expr + "=" + resultStr, "", "calc", 1));
            candidates.add(new PinyinEngine.Candidate("=" + resultStr, "", "calc", 1));
            candidates.add(new PinyinEngine.Candidate(expr, "", "calc", 1));
            candidates.add(new PinyinEngine.Candidate(resultStr, "", "calc", 1));
            updateCandidates();
        } catch (Exception e) {
            pinyinDigits.setLength(0);
            candidates.clear();
            candidates.add(new PinyinEngine.Candidate("计算错误", "", "error", 0));
            updateCandidates();
        }
    }

    private String extractExpression(String text) {
        StringBuilder sb = new StringBuilder();
        boolean hasDigit = false;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')') {
                sb.insert(0, c);
                if (Character.isDigit(c)) hasDigit = true;
            } else if (c == 'x' || c == 'X' || c == '\u00d7') {
                sb.insert(0, '*');
            } else if (c == '\u00f7') {
                sb.insert(0, '/');
            } else if (c == ' ') {
                continue;
            } else {
                break;
            }
        }
        return hasDigit ? sb.toString() : "";
    }

    private double evalExpression(String expr) {
        return new ExpressionEvaluator(expr).parse();
    }

    // 2026-07-29: 简单递归下降表达式求值器 (+, -, *, /, 括号, 小数)
    static class ExpressionEvaluator {
        private String expr;
        private int pos;

        ExpressionEvaluator(String expr) { this.expr = expr; this.pos = 0; }

        double parse() {
            double val = parseAddSub();
            if (pos < expr.length()) throw new RuntimeException("Unexpected char");
            return val;
        }

        double parseAddSub() {
            double left = parseMulDiv();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '+') { pos++; left += parseMulDiv(); }
                else if (op == '-') { pos++; left -= parseMulDiv(); }
                else break;
            }
            return left;
        }

        double parseMulDiv() {
            double left = parseAtom();
            while (pos < expr.length()) {
                char op = expr.charAt(pos);
                if (op == '*') { pos++; left *= parseAtom(); }
                else if (op == '/') { pos++; double d = parseAtom(); if (d == 0) throw new RuntimeException("Div by zero"); left /= d; }
                else break;
            }
            return left;
        }

        double parseAtom() {
            if (pos >= expr.length()) throw new RuntimeException("Unexpected end");
            char c = expr.charAt(pos);
            if (c == '(') {
                pos++;
                double val = parseAddSub();
                if (pos >= expr.length() || expr.charAt(pos) != ')') throw new RuntimeException("Missing )");
                pos++;
                return val;
            }
            if (c == '-') {
                pos++;
                return -parseAtom();
            }
            int start = pos;
            while (pos < expr.length() && (Character.isDigit(expr.charAt(pos)) || expr.charAt(pos) == '.')) pos++;
            if (start == pos) throw new RuntimeException("Expected number");
            return Double.parseDouble(expr.substring(start, pos));
        }
    }

    private void onSpace() {
        if (currentMode == MODE_PINYIN && pinyinDigits.length() > 0 && !candidates.isEmpty()) {
            commitText(candidates.get(0).text);
            onCandidateSelected();
        } else {
            commitText(" ");
        }
    }

    private void onEnter() {
        if (currentMode == MODE_PINYIN && pinyinDigits.length() > 0 && !candidates.isEmpty()) {
            commitText(candidates.get(0).text);
            pinyinDigits.setLength(0);
            candidates.clear();
            updateCandidates();
        }
        commitText("\n");
    }

    private void onBackspace() {
        if (isComposing) {
            if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
                if (!composingPinyins.isEmpty()) {
                    composingPinyins.remove(composingPinyins.size() - 1);
                    composingIndex = composingPinyins.size();
                    showComposingPhase();
                } else {
                    isComposing = false;
                    pinyinDigits.append(composingDigitStr);
                    candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
                    updateCandidates();
                }
                return;
            }
            if (composingPinyins != null && !composingPinyins.isEmpty() && composingIndex > 0) {
                composingIndex--;
                composingCharPage = 0;
                if (composingChars.length() > 0) composingChars.deleteCharAt(composingChars.length() - 1);
                showComposingPhase();
            } else {
                isComposing = false;
                pinyinDigits.append(composingDigitStr);
                candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
                updateCandidates();
            }
            return;
        }
        if (currentMode == MODE_PINYIN && pinyinDigits.length() > 0) {
            pinyinDigits.setLength(pinyinDigits.length() - 1);
            candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
            updateCandidates();
        } else {
            // 2026-07-29: 删除前将内容加入撤消栈
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                CharSequence deleted = ic.getTextBeforeCursor(1, 0);
                if (deleted != null && deleted.length() > 0) {
                    undoStack.addFirst(deleted.toString());
                    if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
                }
                ic.deleteSurroundingText(1, 0);
            }
        }
    }

    private void onCursorAction(int action) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (action) {
            case 1:  sendCursorKey(ic, KeyEvent.KEYCODE_DPAD_UP); break;
            case 2:  goToLineStart(ic); break;
            case 3:  sendCursorKey(ic, KeyEvent.KEYCODE_DPAD_LEFT); break;
            case 4:  sendCursorKey(ic, KeyEvent.KEYCODE_DPAD_DOWN); break;
            case 5:  sendCursorKey(ic, KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case 6:  goToLineEnd(ic); break;
            case 7:  moveCursorByLines(ic, -10); break;
            case 8:  moveCursorByLines(ic, 10); break;
            case 9:  ic.performContextMenuAction(android.R.id.selectAll); break;
            case 11: commitText("\t"); break;
            case 12: ic.performContextMenuAction(android.R.id.cut); break;
            case 13: ic.performContextMenuAction(android.R.id.copy); break;
            case 14: switchToKeyboard(MODE_CLIPBOARD); break;
            case 15: onCursorDelete(); break;
            case 16: onUndoCursor(); break;
        }
    }

    private void goToLineStart(InputConnection ic) {
        // Home = 正文开头
        if (selMode) {
            // Shift连选：从0选到当前光标末尾位置
            int cursorEnd = getCursorEndPos(ic);
            ic.setSelection(0, cursorEnd);
        } else {
            ic.setSelection(0, 0);
        }
    }

    private void goToLineEnd(InputConnection ic) {
        // End = 正文结尾
        int totalLen = getFullTextLength(ic);
        if (selMode) {
            // Shift连选：从当前光标起始位置选到全文末尾
            int cursorStart = getCursorStartPos(ic);
            ic.setSelection(cursorStart, totalLen);
        } else {
            ic.setSelection(totalLen, totalLen);
        }
    }

    // 获取选区起始绝对位置（无选区时=光标位置）
    // getTextBeforeCursor 在有选区时返回选区前的文本，无选区时返回光标前的文本
    private int getCursorStartPos(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        return before != null ? before.length() : 0;
    }

    // 获取选区结束绝对位置（无选区时=光标位置）
    // getTextBeforeCursor + getSelectedText = 选区末尾
    private int getCursorEndPos(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        int beforeLen = before != null ? before.length() : 0;
        CharSequence selected = ic.getSelectedText(0);
        int selLen = selected != null ? selected.length() : 0;
        return beforeLen + selLen;
    }

    // 获取全文总长度 = 选区前 + 选区内 + 选区后
    private int getFullTextLength(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(100000, 0);
        int beforeLen = before != null ? before.length() : 0;
        CharSequence selected = ic.getSelectedText(0);
        int selLen = selected != null ? selected.length() : 0;
        CharSequence after = ic.getTextAfterCursor(100000, 0);
        int afterLen = after != null ? after.length() : 0;
        return beforeLen + selLen + afterLen;
    }

    // 2026-07-31: PgUp/PgDn = 上/下 10 行，基于全文计算目标行并 setSelection
    private void moveCursorByLines(InputConnection ic, int lineOffset) {
        if (selMode) {
            int n = Math.abs(lineOffset);
            for (int i = 0; i < n; i++) {
                sendCursorKey(ic, lineOffset > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP);
            }
            return;
        }
        android.view.inputmethod.ExtractedTextRequest req = new android.view.inputmethod.ExtractedTextRequest();
        android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
        if (et == null || et.text == null) {
            sendCursorKey(ic, lineOffset > 0 ? KeyEvent.KEYCODE_PAGE_DOWN : KeyEvent.KEYCODE_PAGE_UP);
            return;
        }
        String full = et.text.toString();
        int base = et.startOffset;
        int rel = et.selectionStart;
        if (rel < 0) rel = 0;
        if (rel > full.length()) rel = full.length();
        int line = 0;
        for (int i = 0; i < rel; i++) {
            if (full.charAt(i) == '\n') line++;
        }
        int targetLine = line + lineOffset;
        if (targetLine < 0) targetLine = 0;
        int targetIdx = 0;
        int cur = 0;
        boolean found = false;
        for (int i = 0; i <= full.length(); i++) {
            if (cur == targetLine) { targetIdx = i; found = true; break; }
            if (i < full.length() && full.charAt(i) == '\n') cur++;
        }
        if (!found) targetIdx = full.length();
        ic.setSelection(base + targetIdx, base + targetIdx);
    }

    private void sendCursorKey(InputConnection ic, int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        if (selMode) {
            ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0));
        }
        int meta = selMode ? KeyEvent.META_SHIFT_ON : 0;
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
        if (selMode) {
            ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_SHIFT_LEFT, 0, KeyEvent.META_SHIFT_ON));
        }
    }

    private void onCursorDelete() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // 2026-07-29: 删除前将内容加入撤消栈
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            undoStack.addFirst(sel.toString());
            if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
            ic.commitText("", 1);
        } else {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (before != null && before.length() > 0) {
                undoStack.addFirst(before.toString());
                if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
            }
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
        }
    }

    private void updateSelButton() {
        if (selToggleBtn == null) return;
        selToggleBtn.setBackgroundColor(selMode ? COLOR_ACCENT : COLOR_KEY_BG);
        selToggleBtn.setTextColor(selMode ? 0xFFFFFFFF : COLOR_TEXT);
    }

    // ====== 文本操作 ======
    private void commitText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
        // [移除热句功能-2026-08-05 05:27:52] 已删除 lastCommitted 赋值（仅供热句匹配）
        // 2026-08-02: 统一热词学习逻辑 — 非标点文本追加到缓冲区，标点触发分词
        if (!isPunctuationOnly(text)) {
            // 拼音模式和数字模式都追加（数字如"2"也要进buffer，保证"预留2周"完整学习）
            if (currentMode == MODE_PINYIN || currentMode == MODE_NUMBER) {
                if (manualInputBuffer.length() + text.length() > MAX_BUFFER_LEN) {
                    splitAndLearn();
                }
                manualInputBuffer.append(text);
            }
            // 2026-08-02: N-Gram 逐词邻接学习 — 记录上一个词→本次词
            if (!lastContext.isEmpty()) {
                DictDBHelper db = DictDBHelper.getInstance();
                if (db != null) db.learnAdjacency(lastContext, text);
            }
            // [逐字拆分学习-2026-08-05] 多字词内部逐字建立邻接关系，碎片化到单字级
            // 例如上屏"看了吗" → 学习 资料→看、看→了、了→吗
            if (text.length() >= 2 && isAllChinese(text)) {
                DictDBHelper db = DictDBHelper.getInstance();
                if (db != null) {
                    // 上一词的末字 → 本次首字
                    if (!lastContext.isEmpty()) {
                        String lastChar = lastContext.substring(lastContext.length() - 1);
                        String firstChar = text.substring(0, 1);
                        db.learnAdjacency(lastChar, firstChar);
                    }
                    // 本次内部逐字
                    for (int i = 0; i < text.length() - 1; i++) {
                        db.learnAdjacency(text.substring(i, i + 1), text.substring(i + 1, i + 2));
                    }
                }
            }
            // 记录本段第一个词（用于标点触发时的短语学习）
            if (segmentFirstWord.isEmpty()) {
                segmentFirstWord = text;
                segmentAfterFirst = "";
            } else if (!text.equals(segmentFirstWord)) {
                // [增强邻接学习-2026-08-05] 累计段首词之后的文本，学习 段首词→后续完整短语
                // 例如: 上屏"宝妈"后接着上屏"你预产期是什么时候" → 学习 宝妈→你预产期是什么时候
                segmentAfterFirst += text;
                if (segmentAfterFirst.length() >= 2) {
                    DictDBHelper db = DictDBHelper.getInstance();
                    if (db != null) db.learnAdjacency(segmentFirstWord, segmentAfterFirst);
                }
            }
            // 更新上下文为本次文本（只保留末尾4字，防止过长）
            if (text.length() > 4) {
                lastContext = text.substring(text.length() - 4);
            } else {
                lastContext = text;
            }
        } else {
            // 2026-08-02: 任何模式下提交标点都触发缓冲区分词学习
            splitAndLearn();
            // 2026-08-02: N-Gram 短语学习 — 把上一段的第一个词→整段后续短语
            // 例如: "请问服务地址是哪里" → 记录 "请问→服务地址是哪里"
            if (!segmentFirstWord.isEmpty()) {
                learnAdjacencyFromScreen(ic, segmentFirstWord);
            }
            // 标点后重置：下一段重新记录第一个词
            segmentFirstWord = "";
            segmentAfterFirst = "";
            lastContext = "";
        }
        // [移除热句功能-2026-08-05 05:27:52] 已删除热句自动学习注释
    }

    /**
     * 2026-08-02: 从屏幕文本学习 N-Gram 短语邻接
     * 取标点前的完整短语，用段首词作为 context，剩余部分作为 next_phrase
     * 例如屏幕: "好的，请问服务地址是哪里？"
     *   → 取"请问服务地址是哪里"
     *   → context="请问", next_phrase="服务地址是哪里"
     */
    private void learnAdjacencyFromScreen(InputConnection ic, String firstWord) {
        if (ic == null || firstWord == null || firstWord.isEmpty()) return;
        CharSequence before = ic.getTextBeforeCursor(100, 0);
        if (before == null || before.length() < 4) return;
        String text = before.toString();

        // 跳过末尾标点
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c == '，' || c == '。' || c == '？' || c == '！' || c == '；' || c == '：' ||
                c == ',' || c == '.' || c == '?' || c == '!' || c == ';' || c == ':' ||
                c == '\n' || c == ' ') {
                end--;
            } else {
                break;
            }
        }
        if (end < 2) return;

        // 往前找到上一个标点（分句）
        int start = end;
        for (int i = end - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == '？' || c == '！' || c == '；' || c == '：' ||
                c == ',' || c == '.' || c == '?' || c == '!' || c == ';' || c == ':' ||
                c == '\n') {
                start = i + 1;
                break;
            }
        }
        String phrase = text.substring(start, end).trim();
        if (phrase.length() < 3) return; // 太短不值得学习

        // 找到 firstWord 在 phrase 中的位置
        int ctxPos = phrase.indexOf(firstWord);
        if (ctxPos >= 0 && ctxPos + firstWord.length() < phrase.length()) {
            String nextPhrase = phrase.substring(ctxPos + firstWord.length());
            if (nextPhrase.length() >= 2) {
                DictDBHelper db = DictDBHelper.getInstance();
                if (db != null) db.learnAdjacency(firstWord, nextPhrase);
            }
        }
    }

    // 2026-07-29: 判断是否纯标点/空白
    private boolean isPunctuationOnly(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return false;
            if (c >= 'a' && c <= 'z') return false;
            if (c >= 'A' && c <= 'Z') return false;
            if (c >= '0' && c <= '9') return false;
        }
        return true;
    }

    private void commitSymbol(String sym) {
        String right = BRACKET_PAIRS.get(sym);
        if (right != null) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                // 2026-07-29: 提交成对标点后用光标左移代替 getExtractedText，更可靠
                ic.commitText(sym + right, 1);
                long now = android.os.SystemClock.uptimeMillis();
                ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));
                ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_DPAD_LEFT, 0, 0));
            }
        } else {
            commitText(sym);
        }
    }

    private void onUndo() {
        // 2026-07-29: 优先从 undoStack 恢复删除的文字，其次回退拼音数字
        if (!undoStack.isEmpty()) {
            onUndoCursor();
            return;
        }
        if (isComposing) {
            isComposing = false;
            composingPinyinOptions = null;
            composingPinyins = null;
            composingChars = null;
        }
        if (pinyinDigits.length() > 0) {
            int delCount = Math.min(3, pinyinDigits.length());
            pinyinDigits.setLength(pinyinDigits.length() - delCount);
            candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
        } else {
            pinyinDigits.setLength(0);
            candidates.clear();
        }
        candidatePage = 0;
        if (expandMode) {
            expandMode = false;
            if (expandPanel != null) expandPanel.setVisibility(View.GONE);
            if (currentKeyboard != null) currentKeyboard.setVisibility(View.VISIBLE);
        }
        updateCandidates();
    }

    private void onClear() {
        if (isComposing) {
            // 在组词模式下，清空相当于退出
            isComposing = false;
            composingPinyinOptions = null;
            composingPinyins = null;
            composingChars = null;
            pinyinDigits.setLength(0);
            candidates.clear();
            candidatePage = 0;
            updateCandidates();
            return;
        }
        if (currentMode == MODE_PINYIN && pinyinDigits.length() > 0) {
            // 有候选时，删除一个拼音段(3个数字)
            int delCount = Math.min(3, pinyinDigits.length());
            String removed = pinyinDigits.substring(pinyinDigits.length() - delCount);
            pinyinDigits.setLength(pinyinDigits.length() - delCount);
            candidates = PinyinEngine.getCandidates(pinyinDigits.toString());
            candidatePage = 0;
            updateCandidates();
        } else {
            // 没有候选时，3倍速度删除屏幕上的字
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                CharSequence deleted = ic.getTextBeforeCursor(3, 0);
                if (deleted != null && deleted.length() > 0) {
                    undoStack.addFirst(deleted.toString());
                    if (undoStack.size() > MAX_UNDO) undoStack.removeLast();
                    ic.deleteSurroundingText(deleted.length(), 0);
                }
            }
        }
    }

    private void onUndoCursor() {
        // 撤消上一次删除操作
        if (undoStack.isEmpty()) return;
        String text = undoStack.removeFirst();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(text, 1);
        }
    }

    // 2026-08-02: 自动拆分热词学习 — 基于 FMM 句法分词
    private void splitAndLearn() {
        String text = manualInputBuffer.toString();
        manualInputBuffer.setLength(0);
        if (text.length() < 2) return;

        DictDBHelper db = DictDBHelper.getInstance();
        if (db == null) return;

        // 按标点符号分句
        String[] sentences = text.split("[，。？！；：、…,!.?;:\\-\\s]+");
        for (String sentence : sentences) {
            if (sentence.length() < 2 || sentence.length() > 30) continue;

            // 2026-08-02: 用 FMM 分词，只学习有意义的词组
            List<String> words = db.segmentByFMM(sentence);
            for (String word : words) {
                if (word.length() < 2) continue; // 单字不作为热词
                String digitSeq = PinyinEngine.toDigitSeq(word);
                if (!digitSeq.isEmpty()) {
                    db.upsertPhrase(digitSeq, word);
                }
            }
        }
    }

    // 2026-07-29: 判断是否全中文
    private boolean isAllChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x4e00 || c > 0x9fff) return false;
        }
        return true;
    }

    // 2026-08-05 02:20: 已移除 isChineseOrDigit / isSentenceEnding / learnSentenceAsHot — 热句不再从用户输入自动学习，改为从剪切记录手动转化

    // ====== 2026-08-01: 语音输入模块 (Sherpa-ONNX 流式引擎) ======

    private void voiceToggle(View keyView) {
        try {
            Vibrator vb = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vb != null) vb.vibrate(50);
        } catch (Exception ignored) {}
        if (voiceInputActive) {
            stopVoiceInput();
        } else {
            startVoiceInput(keyView);
        }
    }

    private void ensureSherpaRecognizer() {
        if (sherpaRecognizer == null) {
            sherpaRecognizer = new SherpaOnnxVoiceRecognizer(this, new SherpaOnnxVoiceRecognizer.Listener() {
                @Override
                public void onReadyForSpeech() {
                    if (!voiceInputActive) return;
                    showVoiceStatus("正在聆听，请说话...", false);
                    resetVoiceTimeout();
                }

                @Override
                public void onPartialResult(String text) {
                    if (!voiceInputActive) return;
                    // partial 只预览到候选栏，不上屏，避免频繁删除重写导致卡顿
                    showVoiceStatus("🗣 " + text, false);
                    resetVoiceTimeout();
                }

                @Override
                public void onFinalResult(String text) {
                    if (!voiceInputActive) return;
                    // final 结果直接上屏
                    commitVoiceText(text);
                    showVoiceStatus("✓ " + text, false);
                    resetVoiceTimeout();
                }

                @Override
                public void onError(String message) {
                    if (!voiceInputActive) return;
                    showVoiceStatus(message, true);
                    stopVoiceInput();
                    delayedHideVoiceStatus(3000);
                }

                });
        }
    }

    private void startVoiceInput(View keyView) {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showVoiceStatus("需要录音权限，正在跳转授权...", true);
            try {
                Intent i = new Intent(PinyinIME.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
            delayedHideVoiceStatus(3000);
            return;
        }

        voiceInputActive = true;
        voiceKeyView = keyView;
        voiceCommittedLen = 0;

        if (voiceHandler == null) voiceHandler = new Handler(Looper.getMainLooper());

        startVoiceAnimation();
        ensureSherpaRecognizer();

        if (sherpaRecognizer.isReady()) {
            // 模型已就绪，直接开始监听
            showVoiceStatus("正在聆听，请说话...", false);
            sherpaRecognizer.startListening();
            resetVoiceTimeout();
        } else if (sherpaRecognizer.isInitializing()) {
            // 模型正在初始化（可能是下载中）
            showVoiceStatus("语音模型加载中，请稍候...", false);
        } else {
            // 需要初始化模型
            showVoiceStatus("正在初始化语音模型...", false);
            sherpaRecognizer.init();
        }
    }

    private void stopVoiceInput() {
        voiceInputActive = false;
        stopVoiceAnimation();
        cancelVoiceTimeout();

        if (sherpaRecognizer != null) {
            sherpaRecognizer.stopListening();
        }

        if (voiceCommittedLen > 0) {
            splitAndLearn();
            // 2026-08-05 02:20: 语音结束后不再自动学习热句，改为从剪切记录手动转化
        }
        voiceCommittedLen = 0;
    }

    private void commitVoiceText(String text) {
        if (text == null || text.isEmpty()) return;
        // 去除空格（中文不需要空格分隔）
        text = text.replaceAll("\\s+", "");
        if (text.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
        voiceCommittedLen += text.length();
    }

    // 2026-08-05 02:20: 已移除 learnVoiceSentenceAsHot — 语音结束后不再自动学习热句，改为从剪切记录手动转化
    private void resetVoiceTimeout() {
        cancelVoiceTimeout();
        if (voiceHandler == null) return;
        voiceTimeoutTask = () -> {
            showVoiceStatus("60秒无语音，语音输入已自动退出", true);
            stopVoiceInput();
            delayedHideVoiceStatus(3000);
        };
        voiceHandler.postDelayed(voiceTimeoutTask, VOICE_TIMEOUT_MS);
    }

    private void cancelVoiceTimeout() {
        if (voiceHandler != null && voiceTimeoutTask != null) {
            voiceHandler.removeCallbacks(voiceTimeoutTask);
            voiceTimeoutTask = null;
        }
    }

    private void startVoiceAnimation() {
        if (voiceKeyView == null) return;
        stopVoiceAnimation();
        voiceAnimator = ValueAnimator.ofArgb(COLOR_KEY_BG, COLOR_ACCENT, COLOR_KEY_BG);
        voiceAnimator.setDuration(800);
        voiceAnimator.setRepeatCount(ValueAnimator.INFINITE);
        voiceAnimator.setRepeatMode(ValueAnimator.RESTART);
        voiceAnimator.addUpdateListener(a -> {
            if (voiceKeyView != null && voiceInputActive) {
                voiceKeyView.setBackgroundColor((int) a.getAnimatedValue());
            }
        });
        voiceAnimator.start();
    }

    private void stopVoiceAnimation() {
        if (voiceAnimator != null) {
            voiceAnimator.cancel();
            voiceAnimator = null;
        }
        if (voiceKeyView != null) {
            voiceKeyView.setBackgroundColor(COLOR_KEY_BG);
        }
    }

    // 语音状态提示 — 直接显示在 pinyinText 上，不增加额外高度
    private String savedPinyinDisplay = "";
    private boolean voiceStatusActive = false;

    private void showVoiceStatus(String text, boolean isError) {
        if (pinyinText == null) return;
        if (voiceStatusHideTask != null && voiceHandler != null) {
            voiceHandler.removeCallbacks(voiceStatusHideTask);
            voiceStatusHideTask = null;
        }
        if (!voiceStatusActive) {
            savedPinyinDisplay = pinyinText.getText().toString();
            voiceStatusActive = true;
        }
        pinyinText.setText(text);
        pinyinText.setTextColor(isError ? 0xFFFF3B30 : COLOR_ACCENT);
    }

    private void delayedHideVoiceStatus(int delayMs) {
        if (pinyinText == null || voiceHandler == null) return;
        if (voiceStatusHideTask != null) {
            voiceHandler.removeCallbacks(voiceStatusHideTask);
        }
        voiceStatusHideTask = () -> {
            restorePinyinText();
            voiceStatusHideTask = null;
        };
        voiceHandler.postDelayed(voiceStatusHideTask, delayMs);
    }

    private void hideVoiceStatus() {
        restorePinyinText();
    }

    private void restorePinyinText() {
        if (!voiceStatusActive) return;
        voiceStatusActive = false;
        pinyinText.setText(savedPinyinDisplay);
        pinyinText.setTextColor(COLOR_TEXT_DIM);
    }

    private void doWordSearch() {
        if (pinyinDigits.length() < 2) return;
        startComposing();
    }

    // ====== 展开模式 ======
    private void toggleExpand() {
        if (pinyinDigits.length() == 0 && candidates.isEmpty() && !isComposing) {
            requestHideSelf(0);
            return;
        }
        if (isComposing && composingExpandMode) {
            composingExpandMode = false;
            if (expandPanel != null) expandPanel.setVisibility(View.GONE);
            if (currentKeyboard != null) currentKeyboard.setVisibility(View.VISIBLE);
            updateCandidates();
            return;
        }
        if (isComposing) {
            composingExpandMode = true;
            composingExpandPage = 0;
            if (expandPanel == null) {
                expandPanel = buildExpandPanel();
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                keyboardContainer.addView(expandPanel, flp);
            }
            currentKeyboard.setVisibility(View.GONE);
            expandPanel.setVisibility(View.VISIBLE);
            refreshComposingExpandPanel();
            return;
        }
        expandMode = !expandMode;
        if (expandMode) {
            expandPage = 0;
            if (expandPanel == null) {
                expandPanel = buildExpandPanel();
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                keyboardContainer.addView(expandPanel, flp);
            }
            currentKeyboard.setVisibility(View.GONE);
            expandPanel.setVisibility(View.VISIBLE);
            refreshExpandPanel();
        } else {
            expandPanel.setVisibility(View.GONE);
            currentKeyboard.setVisibility(View.VISIBLE);
        }
    }

    private View buildExpandPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(COLOR_BG);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(0, dp(4), 0, dp(4));
        header.setGravity(Gravity.CENTER_VERTICAL);

        // 2026-07-31: 修复构建新词时第二个词无法翻页——增加明确的上一页/下一页按钮，不再依赖滚动触发
        TextView prevBtn = new TextView(this);
        prevBtn.setText("\u25C0");
        prevBtn.setTag("expand_prev");
        prevBtn.setTextSize(14);
        prevBtn.setTextColor(COLOR_ACCENT);
        prevBtn.setGravity(Gravity.CENTER);
        prevBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        prevBtn.setOnClickListener(v -> onExpandPageChange(-1));
        header.addView(prevBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 状态指示器
        TextView statusTv = new TextView(this);
        statusTv.setTag("expand_status");
        statusTv.setTextSize(12);
        statusTv.setTextColor(COLOR_TEXT_DIM);
        statusTv.setGravity(Gravity.CENTER);
        statusTv.setPadding(dp(4), dp(2), dp(4), dp(2));
        header.addView(statusTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView nextBtn = new TextView(this);
        nextBtn.setText("\u25B6");
        nextBtn.setTag("expand_next");
        nextBtn.setTextSize(14);
        nextBtn.setTextColor(COLOR_ACCENT);
        nextBtn.setGravity(Gravity.CENTER);
        nextBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        nextBtn.setOnClickListener(v -> onExpandPageChange(1));
        header.addView(nextBtn, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.addView(header);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setTag("expand_scroll");
        LinearLayout listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setTag("expand_list");
        sv.addView(listContainer);

        panel.addView(sv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        panel.setVisibility(View.GONE);
        return panel;
    }

    // 2026-07-31: 展开面板上一页/下一页按钮翻页，修复内容不满一屏时无法滚动翻页的问题
    private void onExpandPageChange(int delta) {
        if (isComposing) {
            int total;
            if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
                total = composingPinyinOptions.get(composingPinyins.size()).size();
            } else if (composingPinyins != null && !composingPinyins.isEmpty() && composingIndex < composingPinyins.size()) {
                total = PinyinEngine.getCharCountByPinyin(composingPinyins.get(composingIndex));
            } else {
                return;
            }
            int perPage = COMPOSING_EXPAND_LOAD;
            int maxPage = total == 0 ? 0 : (total - 1) / perPage;
            int target = composingExpandPage + delta;
            if (target < 0 || target > maxPage) return;
            composingExpandPage = target;
            refreshComposingExpandPanel();
        } else {
            // 普通候选展开模式：真正分页
            int total = candidates.size();
            int perPage = EXPAND_LOAD;
            int maxPage = total == 0 ? 0 : (total - 1) / perPage;
            int target = expandPage + delta;
            if (target < 0 || target > maxPage) return;
            expandPage = target;
            refreshExpandPanel();
        }
    }

    private void resetExpandScroll() {
        ScrollView sv = expandPanel != null ? (ScrollView) expandPanel.findViewWithTag("expand_scroll") : null;
        if (sv != null) sv.scrollTo(0, 0);
    }

    // 2026-07-31: 更新展开面板页码/总数状态；currentPage 为 1-based
    private void updateExpandStatus(int end, int total, int perPage, int currentPage) {
        if (expandPanel == null) return;
        View status = expandPanel.findViewWithTag("expand_status");
        if (!(status instanceof TextView)) return;
        TextView tv = (TextView) status;
        int totalPages = total == 0 ? 0 : (total - 1) / perPage + 1;
        int shown = Math.min(Math.max(currentPage, 0), totalPages);
        tv.setText("第 " + shown + "/" + totalPages + " 页 (共" + total + "个)");
    }

    private void refreshExpandPanel() {
        if (expandPanel == null) return;
        View listView = expandPanel.findViewWithTag("expand_list");
        if (!(listView instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) listView;
        list.removeAllViews();

        int total = candidates.size();
        int perPage = EXPAND_LOAD;
        int maxPage = total == 0 ? 0 : (total - 1) / perPage;
        if (expandPage < 0) expandPage = 0;
        if (expandPage > maxPage) expandPage = maxPage;

        int start = expandPage * perPage;
        int end = Math.min(start + perPage, total);
        int cols = 4;

        for (int i = start; i < end; i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < cols && i + j < end; j++) {
                int idx = i + j;
                PinyinEngine.Candidate c = candidates.get(idx);
                TextView tv = new TextView(this);
                tv.setText(c.text);
                tv.setTextSize(20);
                tv.setTextColor(COLOR_TEXT_BRIGHT);
                tv.setBackgroundColor(COLOR_CANDIDATE_BG);
                tv.setPadding(dp(4), dp(8), dp(4), dp(8));
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

                final String text = c.text;
                tv.setOnClickListener(v -> {
                    commitText(text);
                    onCandidateSelected();
                });

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(dp(2), 0, dp(2), dp(4));
                row.addView(tv, lp);
            }
            list.addView(row);
        }

        updateExpandStatus(end, total, EXPAND_LOAD, expandPage + 1);

        ScrollView sv = (ScrollView) expandPanel.findViewWithTag("expand_scroll");
        if (sv != null) sv.scrollTo(0, 0);
    }

    private void refreshComposingExpandPanel() {
        if (expandPanel == null) return;
        View listView = expandPanel.findViewWithTag("expand_list");
        if (!(listView instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) listView;
        list.removeAllViews();

        int cols = 4;

        if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
            List<String> options = composingPinyinOptions.get(composingPinyins.size());
            int total = options.size();
            int perPage = COMPOSING_EXPAND_LOAD;
            int totalPages = total == 0 ? 0 : (total - 1) / perPage;
            if (composingExpandPage > totalPages) composingExpandPage = totalPages;
            int start = composingExpandPage * perPage;
            int end = Math.min(start + perPage, total);

            for (int i = start; i < end; i += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int j = 0; j < cols && i + j < end; j++) {
                    int idx = i + j;
                    final String syl = options.get(idx);
                    TextView tv = new TextView(this);
                    tv.setText(syl);
                    tv.setTextSize(20);
                    tv.setTextColor(COLOR_TEXT_BRIGHT);
                    tv.setBackgroundColor(COLOR_CANDIDATE_BG);
                    tv.setPadding(dp(4), dp(8), dp(4), dp(8));
                    tv.setGravity(Gravity.CENTER);
                    tv.setSingleLine(true);
                    tv.setOnClickListener(v -> {
                        composingPinyins.add(syl);
                        if (composingPinyins.size() >= composingPinyinOptions.size()) {
                            composingExpandMode = false;
                            expandPanel.setVisibility(View.GONE);
                            currentKeyboard.setVisibility(View.VISIBLE);
                            composingIndex = 0;
                            showComposingPhase();
                        } else {
                            composingExpandPage = 0;
                            refreshComposingExpandPanel();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    lp.setMargins(dp(2), 0, dp(2), dp(4));
                    row.addView(tv, lp);
                }
                list.addView(row);
            }

            updateExpandStatus(end, total, COMPOSING_EXPAND_LOAD, composingExpandPage + 1);
            resetExpandScroll();
            return;
        }

        if (composingPinyins != null && !composingPinyins.isEmpty() && composingIndex < composingPinyins.size()) {
            String curPinyin = composingPinyins.get(composingIndex);
            int total = PinyinEngine.getCharCountByPinyin(curPinyin);
            int perPage = COMPOSING_EXPAND_LOAD;
            int totalPages = total == 0 ? 0 : (total - 1) / perPage;
            if (composingExpandPage > totalPages) composingExpandPage = totalPages;
            int startIdx = composingExpandPage * perPage;
            List<DictDBHelper.CharEntry> chars = PinyinEngine.getCharsByPinyin(curPinyin, composingExpandPage, perPage);
            int endIdx = Math.min(startIdx + perPage, total);

            for (int i = 0; i < chars.size(); i += cols) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int j = 0; j < cols && i + j < chars.size(); j++) {
                    final char ch = chars.get(i + j).character;
                    TextView tv = new TextView(this);
                    tv.setText(String.valueOf(ch));
                    tv.setTextSize(20);
                    tv.setTextColor(COLOR_TEXT_BRIGHT);
                    tv.setBackgroundColor(COLOR_CANDIDATE_BG);
                    tv.setPadding(dp(4), dp(8), dp(4), dp(8));
                    tv.setGravity(Gravity.CENTER);
                    tv.setSingleLine(true);
                    tv.setOnClickListener(v -> {
                        composingChars.append(ch);
                        composingIndex++;
                        composingCharPage = 0;
                        if (composingIndex >= composingPinyins.size()) {
                            String finalText = composingChars.toString();
                            commitText(finalText);
                            PinyinEngine.addLearnedPhrase(composingDigitStr, finalText);
                            isComposing = false;
                            composingExpandMode = false;
                            expandPanel.setVisibility(View.GONE);
                            currentKeyboard.setVisibility(View.VISIBLE);
                            pinyinDigits.setLength(0);
                            candidates.clear();
                            candidatePage = 0;
                            updateCandidates();
                        } else {
                            composingExpandPage = 0;
                            refreshComposingExpandPanel();
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    lp.setMargins(dp(2), 0, dp(2), dp(4));
                    row.addView(tv, lp);
                }
                list.addView(row);
            }

            updateExpandStatus(endIdx, total, COMPOSING_EXPAND_LOAD, composingExpandPage + 1);
            resetExpandScroll();
        }
    }

    // ====== 候选区更新 ======
    private void updateCandidates() {
        if (isComposing) {
            showComposingPhase();
            return;
        }
        StringBuilder pyDisplay = new StringBuilder();
        if (pinyinDigits.length() > 0) {
            List<String> display = PinyinEngine.getPinyinDisplay(pinyinDigits.toString());
            if (!display.isEmpty()) {
                pyDisplay.append(String.join("'", display));
            } else {
                List<String> prefixes = PinyinEngine.getPrefixPinyin(pinyinDigits.toString());
                if (!prefixes.isEmpty()) {
                    pyDisplay.append(prefixes.get(0));
                } else {
                    pyDisplay.append(pinyinDigits.toString());
                }
            }
        }
        pinyinText.setText(pyDisplay.toString());

        candidatePage = 0;
        if (expandMode) {
            expandMode = false;
            if (expandPanel != null) expandPanel.setVisibility(View.GONE);
            if (currentKeyboard != null) currentKeyboard.setVisibility(View.VISIBLE);
        }
        showCandidatePage();
    }

    private void showCandidatePage() {
        candidateList.removeAllViews();
        int total = candidates.size();
        int totalPages = total == 0 ? 0 : (total - 1) / PAGE_SIZE;
        if (candidatePage > totalPages) candidatePage = totalPages;
        if (candidatePage < 0) candidatePage = 0;

        int start = candidatePage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        if (totalPages > 0) {
            TextView pageInfo = new TextView(this);
            pageInfo.setText((candidatePage + 1) + "/" + (totalPages + 1));
            pageInfo.setTextSize(11);
            pageInfo.setTextColor(COLOR_TEXT_DIM);
            pageInfo.setPadding(dp(4), dp(6), dp(6), dp(6));
            pageInfo.setGravity(Gravity.CENTER);
            candidateList.addView(pageInfo);
        }

        for (int i = start; i < end; i++) {
            PinyinEngine.Candidate c = candidates.get(i);
            TextView tv = new TextView(this);
            String label = c.text;
            tv.setText(label);
            tv.setTextSize(20);
            tv.setTextColor(COLOR_TEXT_BRIGHT);
            tv.setBackgroundColor(COLOR_CANDIDATE_BG);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setGravity(Gravity.CENTER);

            final String text = c.text;
            tv.setOnClickListener(v -> {
                commitText(text);
                onCandidateSelected();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            candidateList.addView(tv, lp);
        }

        // 组词入口
        if (pinyinDigits.length() > 2 && !hasKnownPhrase()) {
            TextView composeBtn = new TextView(this);
            composeBtn.setText("+组词");
            composeBtn.setTextSize(14);
            composeBtn.setTextColor(COLOR_ACCENT);
            composeBtn.setBackgroundColor(COLOR_CANDIDATE_BG);
            composeBtn.setPadding(dp(8), dp(6), dp(8), dp(6));
            composeBtn.setGravity(Gravity.CENTER);
            composeBtn.setOnClickListener(v -> startComposing());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(4), 0);
            candidateList.addView(composeBtn, lp);
        }
    }

    // ====== 三步上栏 ======

    private boolean hasKnownPhrase() {
        for (PinyinEngine.Candidate c : candidates) {
            if ("known".equals(c.type)) return true;
        }
        return false;
    }

    private void onCandidateSelected() {
        // [移除热句功能-2026-08-05 05:27:52] 已删除热句渐进式补全匹配逻辑，仅保留候选清空 + 邻接联想

        pinyinDigits.setLength(0);
        candidates.clear();
        candidatePage = 0;
        isComposing = false;
        composingDigitStr = null;
        composingPinyinOptions = null;
        composingPinyins = null;
        composingChars = null;
        composingIndex = 0;
        composingCharPage = 0;
        lastComposingIndex = 0;

        // 2026-08-02: 没有热句联想时，显示邻接高频词组联想
        if (candidates.isEmpty()) {
            tryShowAssociated();
        }

        updateCandidates();
    }

    // [移除热句功能-2026-08-05 05:27:52] 已删除 tryTriggerHotSentence 方法

    // [移除热句功能-2026-08-05 05:27:52] 已删除 clearHotSentenceState 方法

    /**
     * 2026-08-02: N-Gram 邻接联想 — 读取上文，查询高频跟随词组，追加到候选栏
     * 用 InputConnection 取光标前文本作为上下文，支持多级回溯
     */
    private void tryShowAssociated() {
        // 用 lastContext 作为查询key（commitText 时已维护）
        if (lastContext == null || lastContext.isEmpty()) return;
        if (isPunctuationOnly(lastContext)) return;

        DictDBHelper db = DictDBHelper.getInstance();
        if (db == null) return;

        List<DictDBHelper.PhraseEntry> associated = db.queryAdjacency(lastContext, 6);
        for (DictDBHelper.PhraseEntry pe : associated) {
            candidates.add(new PinyinEngine.Candidate(pe.text, "", "assoc", pe.frequency));
        }
    }

    private void startComposing() {
        composingDigitStr = pinyinDigits.toString();
        List<List<String>> options = PinyinEngine.getBestSegmentedPinyins(composingDigitStr);
        if (options == null || options.isEmpty()) return;
        isComposing = true;
        composingPinyinOptions = options;
        composingPinyins = new ArrayList<>();
        composingChars = new StringBuilder();
        composingIndex = 0;
        composingCharPage = 0;
        lastComposingIndex = 0;
        showComposingPhase();
    }

    private void showComposingPhase() {
        if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
            showPinyinSelectionPhase();
            return;
        }
        if (composingPinyins != null && !composingPinyins.isEmpty()) {
            if (composingIndex != lastComposingIndex) {
                composingCharPage = 0;
                lastComposingIndex = composingIndex;
            }
            showCharSelectionPhase();
        }
    }

    private void showPinyinSelectionPhase() {
        List<String> options = composingPinyinOptions.get(composingIndex);
        int totalPositions = composingPinyinOptions.size();

        StringBuilder progress = new StringBuilder("拼音");
        for (int i = 0; i < composingPinyins.size(); i++) {
            progress.append(" ").append(composingPinyins.get(i));
        }
        progress.append(" ▸ 位置").append(composingIndex + 1).append("/").append(totalPositions);
        pinyinText.setText(progress.toString());

        candidateList.removeAllViews();
        int showCount = Math.min(options.size(), 8);
        for (int i = 0; i < showCount; i++) {
            final String syl = options.get(i);
            TextView tv = new TextView(this);
            tv.setText((i + 1) + " " + syl);
            tv.setTextSize(20);
            tv.setTextColor(COLOR_TEXT_BRIGHT);
            tv.setBackgroundColor(COLOR_CANDIDATE_BG);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> selectPinyinSyllable(syl));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(4), 0);
            candidateList.addView(tv, lp);
        }
        if (options.size() > 8) {
            TextView expandBtn = new TextView(this);
            expandBtn.setText("\u25BC");
            expandBtn.setTextSize(14);
            expandBtn.setTextColor(COLOR_ACCENT);
            expandBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
            expandBtn.setGravity(Gravity.CENTER);
            expandBtn.setOnClickListener(v -> toggleExpand());
            candidateList.addView(expandBtn);
        }
    }

    private void selectPinyinSyllable(String syllable) {
        composingPinyins.add(syllable);
        composingIndex++;
        if (composingIndex >= composingPinyinOptions.size()) {
            composingIndex = 0;
            showComposingPhase();
        } else {
            showComposingPhase();
        }
    }

    private void showCharSelectionPhase() {
        String curPinyin = composingPinyins.get(composingIndex);

        // 进度显示
        StringBuilder progress = new StringBuilder();
        if (composingChars.length() > 0) progress.append(composingChars);
        for (int i = composingIndex; i < composingPinyins.size(); i++) {
            progress.append("_");
        }
        pinyinText.setText(curPinyin + "  " + progress);

        candidateList.removeAllViews();
        int pageSize = COMPOSING_PAGE_SIZE;
        List<DictDBHelper.CharEntry> chars = PinyinEngine.getCharsByPinyin(curPinyin, composingCharPage, pageSize);
        int total = PinyinEngine.getCharCountByPinyin(curPinyin);
        int totalPages = total == 0 ? 0 : (total - 1) / pageSize;

        if (totalPages > 0) {
            candidateList.addView(makeInfoText("字:" + (composingCharPage + 1) + "/" + (totalPages + 1)));
        }

        for (int i = 0; i < chars.size(); i++) {
            final char ch = chars.get(i).character;
            TextView tv = new TextView(this);
            tv.setText(String.valueOf(ch));
            tv.setTextSize(20);
            tv.setTextColor(COLOR_TEXT_BRIGHT);
            tv.setBackgroundColor(COLOR_CANDIDATE_BG);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> selectComposingChar(String.valueOf(ch)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(4), 0);
            candidateList.addView(tv, lp);
        }

        // 超过一页时添加展开按钮，进入展开面板可翻页查看全部
        if (total > pageSize) {
            TextView expandBtn = new TextView(this);
            expandBtn.setText("\u25BC");
            expandBtn.setTextSize(14);
            expandBtn.setTextColor(COLOR_ACCENT);
            expandBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
            expandBtn.setGravity(Gravity.CENTER);
            expandBtn.setOnClickListener(v -> toggleExpand());
            candidateList.addView(expandBtn);
        }
    }

    private void selectComposingChar(String ch) {
        composingChars.append(ch);
        composingIndex++;
        composingCharPage = 0;

        if (composingIndex >= composingPinyins.size()) {
            String finalText = composingChars.toString();
            commitText(finalText);
            PinyinEngine.addLearnedPhrase(composingDigitStr, finalText);
            isComposing = false;
            pinyinDigits.setLength(0);
            candidates.clear();
            candidatePage = 0;
            updateCandidates();
        } else {
            showComposingPhase();
        }
    }

    private TextView makeInfoText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(COLOR_TEXT_DIM);
        tv.setPadding(dp(4), dp(6), dp(6), dp(6));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private void nextCandidatePage() {
        if (isComposing) {
            if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
                return;
            }
            if (composingPinyins != null && !composingPinyins.isEmpty()) {
                String curPinyin = composingPinyins.get(composingIndex);
                int total = PinyinEngine.getCharCountByPinyin(curPinyin);
                int totalPages = total == 0 ? 0 : (total - 1) / COMPOSING_PAGE_SIZE;
                if (composingCharPage < totalPages) {
                    composingCharPage++;
                    showComposingPhase();
                }
            }
            return;
        }
        int totalPages = candidates.isEmpty() ? 0 : (candidates.size() - 1) / PAGE_SIZE;
        if (candidatePage < totalPages) {
            candidatePage++;
            showCandidatePage();
        }
    }

    private void prevCandidatePage() {
        if (isComposing) {
            if (composingPinyinOptions != null && composingPinyins.size() < composingPinyinOptions.size()) {
                return;
            }
            if (composingPinyins != null && !composingPinyins.isEmpty()) {
                if (composingCharPage > 0) {
                    composingCharPage--;
                    showComposingPhase();
                }
            }
            return;
        }
        if (candidatePage > 0) {
            candidatePage--;
            showCandidatePage();
        }
    }

    // ====== 剪切板和收藏夹 ======
    private void addToClipboard() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            CharSequence sel = ic.getSelectedText(0);
            String text = sel != null ? sel.toString() : "";
            if (TextUtils.isEmpty(text)) {
                text = ic.getTextBeforeCursor(100, 0).toString();
            }
            if (!TextUtils.isEmpty(text)) {
                clipDb.addClip(text);
                refreshClipboardList();
            }
        }
    }

    private void addToFavorites() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            String text = ic.getTextBeforeCursor(1000, 0).toString().trim();
            if (!TextUtils.isEmpty(text)) {
                clipDb.addFavorite(text);
                refreshFavoritesList();
            }
        }
    }

    // 2026-07-28 T016+T018: 确认弹窗+每条目独立操作按钮
    private void refreshClipboardList() {
        View listView = keyboardContainer.findViewWithTag("clip_list");
        if (!(listView instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) listView;
        list.removeAllViews();

        int screenW = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;

        List<ClipDBHelper.ClipEntry> items = clipDb.getAllClips();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("剪切板为空");
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            list.addView(empty);
        } else {
            for (final ClipDBHelper.ClipEntry item : items) {
                HorizontalScrollView rowWrapper = new HorizontalScrollView(this);
                rowWrapper.setHorizontalScrollBarEnabled(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(2));

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setMinimumWidth(screenW);

                TextView tv = new TextView(this);
                tv.setText(item.text);
                tv.setTextSize(14);
                tv.setTextColor(COLOR_TEXT);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                tv.setMaxLines(5);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setBackground(new DashedBorderDrawable(COLOR_DIVIDER, density));
                tv.setOnClickListener(v -> commitText(item.text));
                row.addView(tv, new LinearLayout.LayoutParams(
                    screenW - dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));

                View spacer1 = new View(this);
                spacer1.setLayoutParams(new LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer1);


                TextView favBtn = makeActionBtn(this, "收藏", COLOR_ACCENT);
                favBtn.setOnClickListener(v -> {
                    clipDb.addFavorite(item.text);
                    clipDb.deleteClip(item.id);
                    refreshClipboardList();
                });
                row.addView(favBtn);

                View spacer2 = new View(this);
                spacer2.setLayoutParams(new LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer2);

                TextView delBtn = makeActionBtn(this, "删除", 0xFFE53E3E);
                delBtn.setOnClickListener(v -> {
                    // 2026-08-05 02:40: 删除确认改用候选栏提示+确认，避免 IME 中 Dialog 无 token 闪退
                    showCandidateConfirm("确认删除此剪切记录？", () -> {
                        clipDb.deleteClip(item.id);
                        refreshClipboardList();
                    });
                });
                row.addView(delBtn);

                View tailSpacer = new View(this);
                tailSpacer.setLayoutParams(new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(tailSpacer);

                rowWrapper.addView(row, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                list.addView(rowWrapper, rowLp);
            }
        }
    }

    private void refreshFavoritesList() {
        View listView = keyboardContainer.findViewWithTag("fav_list");
        if (!(listView instanceof LinearLayout)) return;
        LinearLayout list = (LinearLayout) listView;
        list.removeAllViews();

        int screenW = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;

        List<ClipDBHelper.FavEntry> items = clipDb.getAllFavorites();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("收藏夹为空");
            empty.setTextColor(COLOR_TEXT_DIM);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            list.addView(empty);
        } else {
            for (final ClipDBHelper.FavEntry item : items) {
                HorizontalScrollView rowWrapper = new HorizontalScrollView(this);
                rowWrapper.setHorizontalScrollBarEnabled(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp(2));

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setMinimumWidth(screenW);

                TextView tv = new TextView(this);
                tv.setText(item.text);
                tv.setTextSize(14);
                tv.setTextColor(COLOR_TEXT);
                tv.setPadding(dp(12), dp(10), dp(12), dp(10));
                tv.setMaxLines(5);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setBackground(new DashedBorderDrawable(COLOR_DIVIDER, density));
                tv.setOnClickListener(v -> commitText(item.text));
                row.addView(tv, new LinearLayout.LayoutParams(
                    screenW - dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));

                View spacer1 = new View(this);
                spacer1.setLayoutParams(new LinearLayout.LayoutParams(dp(16), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer1);

                TextView pinBtn = makeActionBtn(this, item.pinned ? "取消置顶" : "置顶", COLOR_ACCENT);
                pinBtn.setOnClickListener(v -> {
                    clipDb.togglePinFavorite(item.id);
                    refreshFavoritesList();
                });
                row.addView(pinBtn);

                View spacer2 = new View(this);
                spacer2.setLayoutParams(new LinearLayout.LayoutParams(dp(12), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(spacer2);

                TextView delBtn = makeActionBtn(this, "删除", 0xFFE53E3E);
                delBtn.setOnClickListener(v -> {
                    // 2026-08-05 02:40: 删除确认改用候选栏提示+确认，避免 IME 中 Dialog 无 token 闪退
                    showCandidateConfirm("确认删除此收藏？", () -> {
                        clipDb.deleteFavorite(item.id);
                        refreshFavoritesList();
                    });
                });
                row.addView(delBtn);

                View tailSpacer = new View(this);
                tailSpacer.setLayoutParams(new LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(tailSpacer);

                rowWrapper.addView(row, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                list.addView(rowWrapper, rowLp);
            }
        }
    }
        
    

    // 2026-07-28 T018: 构建条目内操作按钮
    private TextView makeActionBtn(Context ctx, String text, int color) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(color);
        btn.setPadding(dp(6), dp(10), dp(6), dp(10));
        btn.setGravity(Gravity.CENTER);
        return btn;
    }

    // ====== 2026-08-05 02:40: IME 内确认改用候选栏 ======
    // InputMethodService 是 Service 上下文，Dialog.show() 无 Activity token 会抛 BadTokenException 闪退；
    // 独立 WindowManager 面板又依赖窗口 token、遮挡键盘，改用候选栏提示+确认最可靠
    private boolean confirmMode = false;
    private Runnable pendingConfirm;

    private void showCandidateConfirm(String message, Runnable onConfirm) {
        cancelCandidateConfirm();
        confirmMode = true;
        pendingConfirm = onConfirm;
        pinyinText.setText(message);
        pinyinText.setTextColor(COLOR_TEXT);
        candidateList.removeAllViews();

        TextView okBtn = makeCandidateConfirmBtn("确认", 0xFFFF3B30);
        okBtn.setOnClickListener(v -> {
            Runnable action = pendingConfirm;
            cancelCandidateConfirm();
            if (action != null) action.run();
        });
        candidateList.addView(okBtn, candidateConfirmBtnLp());

        TextView cancelBtn = makeCandidateConfirmBtn("取消", COLOR_TEXT_DIM);
        cancelBtn.setOnClickListener(v -> cancelCandidateConfirm());
        candidateList.addView(cancelBtn, candidateConfirmBtnLp());
    }

    private TextView makeCandidateConfirmBtn(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setBackgroundColor(COLOR_CANDIDATE_BG);
        tv.setPadding(dp(16), dp(6), dp(16), dp(6));
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private LinearLayout.LayoutParams candidateConfirmBtnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        return lp;
    }

    private void cancelCandidateConfirm() {
        if (!confirmMode && pendingConfirm == null) return;
        confirmMode = false;
        pendingConfirm = null;
        updateCandidates();
    }

    // ====== 辅助方法 ======
    private LinearLayout.LayoutParams keyLp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), weight);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        return lp;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ====== 自定义按键 View ======
    // 2026-07-28 T019: 列表条目虚线边框
    static class DashedBorderDrawable extends android.graphics.drawable.Drawable {
        private Paint paint;
        private float density;

        DashedBorderDrawable(int color, float density) {
            this.density = density;
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density * 1.5f);
            paint.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{density * 4, density * 3}, 0));
        }

        @Override
        public void draw(Canvas canvas) {
            paint.setStrokeWidth(density * 1.5f);
            canvas.drawRect(
                density * 1, density * 1,
                getBounds().width() - density * 1,
                getBounds().height() - density * 1,
                paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // 2026-07-28 T010: 支持自定义字号
    static class KeyButton extends android.view.View {
        private Paint bgPaint;
        private Paint textPaint;
        private Paint subPaint;
        private String mainLabel;
        private String subLabel;
        private int bgColor;
        private RectF rect;
        private boolean swapColors = false;
        private float mainTextSizeSp = 0;
        private float subTextSizeSp = 0;

        KeyButton(Context ctx) {
            super(ctx);
            bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(COLOR_TEXT_BRIGHT);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);
            subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            subPaint.setColor(COLOR_TEXT_DIM);
            subPaint.setTextAlign(Paint.Align.CENTER);
            rect = new RectF();
            setClickable(true);
        }

        public void setLabel(String main, String sub) {
            mainLabel = main;
            subLabel = sub;
            invalidate();
        }

        String getMainLabel() { return mainLabel; }

        // 2026-07-28 T010: 自定义主/副文字字号 (0 表示使用默认值)
        public void setTextSizes(float mainSp, float subSp) {
            mainTextSizeSp = mainSp;
            subTextSizeSp = subSp;
            invalidate();
        }

        public void setSwapColors(boolean swap) {
            swapColors = swap;
            invalidate();
        }

        public void setBackgroundColor(int color) {
            bgColor = color;
            invalidate();
        }

        public void setTextColor(int color) {
            textPaint.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            rect.set(0, 0, w, h);

            // 背景
            if (isPressed()) {
                bgPaint.setColor(COLOR_ACCENT);
                bgPaint.setAlpha(200);
            } else {
                bgPaint.setColor(bgColor);
            }
            canvas.drawRoundRect(rect, dp2(8), dp2(8), bgPaint);

            // 主文字
            if (mainLabel != null) {
                // 2026-07-28 T010: 支持自定义字号，0=使用默认
                float mainSize = mainTextSizeSp > 0
                    ? dp2(mainTextSizeSp)
                    : (mainLabel.length() > 3 ? dp2(14) : dp2(16));

                textPaint.setTextSize(mainSize);
                if (swapColors) {
                    textPaint.setColor(COLOR_TEXT_DIM);
                } else {
                    textPaint.setColor(COLOR_TEXT_BRIGHT);
                }
                float y = h / 2 + (subLabel != null ? -dp2(6) : dp2(6));
                canvas.drawText(mainLabel, w / 2, y, textPaint);
            }

            // 副文字
            if (subLabel != null && subLabel.length() > 0) {
                // 2026-07-28 T010: 支持自定义副字号
                subPaint.setTextSize(subTextSizeSp > 0 ? dp2(subTextSizeSp) : dp2(9));
                if (swapColors) {
                    subPaint.setColor(COLOR_TEXT_BRIGHT);
                } else {
                    subPaint.setColor(COLOR_TEXT_DIM);
                }
                canvas.drawText(subLabel, w / 2, h / 2 + dp2(16), subPaint);
            }
        }

        private float dp2(float dp) {
            return dp * getResources().getDisplayMetrics().density;
        }
    }
    
    // ====== 设置 Activity (占位) ======
    public static class SettingsActivity extends android.app.Activity {}
}
