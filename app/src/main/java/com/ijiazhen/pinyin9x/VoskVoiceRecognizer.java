package com.ijiazhen.pinyin9x;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vosk 离线语音识别器
 * - 无需 Google 服务，无需网络（模型下载后）
 * - 支持中文实时识别（边说边写）
 * - 自动管理模型下载与初始化
 */
public class VoskVoiceRecognizer {

    private static final String TAG = "VoskVoiceRecognizer";
    private static final String MODEL_DIR_NAME = "vosk-model-small-cn-0.22";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * 识别回调接口
     */
    public interface Listener {
        /** 模型已就绪，可以开始说话 */
        void onReadyForSpeech();
        /** 实时部分识别结果（边说边写） */
        void onPartialResult(String text);
        /** 最终识别结果 */
        void onFinalResult(String text);
        /** 错误 */
        void onError(String message);
        /** 模型下载进度（0-100） */
        void onModelDownloadProgress(int percent);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Model model;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean isRecording = false;
    private volatile boolean isReady = false;
    private volatile boolean isInitializing = false;

    public VoskVoiceRecognizer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 初始化模型（如果尚未下载则自动下载）
     * 可安全多次调用，仅首次会真正初始化
     */
    public void init() {
        if (isReady || isInitializing) return;
        isInitializing = true;

        executor.execute(() -> {
            try {
                String modelPath = getModelPath();
                if (modelPath == null) {
                    // 尝试从 assets 复制（预置模型）
                    modelPath = copyModelFromAssets();
                }
                if (modelPath == null) {
                    // 需要下载模型
                    downloadAndExtractModel();
                    modelPath = getModelPath();
                }
                if (modelPath == null) {
                    isInitializing = false;
                    mainHandler.post(() -> listener.onError("模型加载失败"));
                    return;
                }
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                model = new Model(modelPath);
                isReady = true;
                isInitializing = false;
                mainHandler.post(() -> listener.onReadyForSpeech());
            } catch (Exception e) {
                Log.e(TAG, "Init failed", e);
                isInitializing = false;
                String msg = e.getMessage();
                mainHandler.post(() -> listener.onError("模型初始化失败: " + msg));
            }
        });
    }

    /**
     * 开始语音识别
     */
    public void startListening() {
        if (!isReady || model == null) {
            mainHandler.post(() -> listener.onError("语音模型未就绪，正在初始化..."));
            init();
            return;
        }
        if (isRecording) return;

        try {
            // 每次监听创建新的 Recognizer
            if (recognizer != null) {
                recognizer.close();
            }
            recognizer = new Recognizer(model, SAMPLE_RATE);
            recognizer.setWords(true);
            recognizer.setPartialWords(true);

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            // AudioRecord 内部缓冲区用系统推荐大小，但每次读取小块（400 samples ≈ 25ms）
            final int readBufferSize = 400;

            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                Math.max(minBuf, readBufferSize) * 2
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                mainHandler.post(() -> listener.onError("音频录制初始化失败"));
                return;
            }

            isRecording = true;
            audioRecord.startRecording();

            recordThread = new Thread(() -> {
                short[] buffer = new short[readBufferSize];
                String lastPartialText = "";
                long lastPartialTime = 0;
                while (isRecording) {
                    int count = audioRecord.read(buffer, 0, buffer.length);
                    if (count > 0) {
                        if (recognizer.acceptWaveForm(buffer, count)) {
                            // 一句话结束，获取最终结果
                            String result = recognizer.getResult();
                            String text = extractText(result);
                            if (text != null && !text.isEmpty()) {
                                lastPartialText = "";
                                final String finalText = text;
                                mainHandler.post(() -> listener.onFinalResult(finalText));
                            }
                        } else {
                            // 获取部分结果（预览），节流：文本变化且距上次≥80ms才回调
                            long now = System.currentTimeMillis();
                            if (now - lastPartialTime < 80) continue;
                            String partial = recognizer.getPartialResult();
                            String text = extractPartial(partial);
                            if (text != null && !text.isEmpty() && !text.equals(lastPartialText)) {
                                lastPartialText = text;
                                lastPartialTime = now;
                                final String partialText = text;
                                mainHandler.post(() -> listener.onPartialResult(partialText));
                            }
                        }
                    }
                }
                // 停止时获取最后的结果
                if (recognizer != null) {
                    String finalResult = recognizer.getFinalResult();
                    String text = extractText(finalResult);
                    if (text != null && !text.isEmpty()) {
                        final String finalText = text;
                        mainHandler.post(() -> listener.onFinalResult(finalText));
                    }
                }
            });
            recordThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Start listening failed", e);
            mainHandler.post(() -> listener.onError("启动录音失败: " + e.getMessage()));
        }
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        isRecording = false;
        if (recordThread != null) {
            try { recordThread.join(1000); } catch (InterruptedException ignored) {}
            recordThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    /**
     * 销毁，释放所有资源
     */
    public void destroy() {
        stopListening();
        if (model != null) {
            model.close();
            model = null;
        }
        isReady = false;
        executor.shutdown();
    }

    public boolean isReady() {
        return isReady;
    }

    public boolean isListening() {
        return isRecording;
    }

    public boolean isInitializing() {
        return isInitializing;
    }

    // ====== 内部方法 ======

    /**
     * 获取模型路径，如果模型已存在则返回路径，否则返回 null
     */
    private String getModelPath() {
        File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
        if (modelDir.exists() && modelDir.isDirectory()) {
            // 检查模型文件是否完整
            if (new File(modelDir, "am").exists() || new File(modelDir, "conf").exists()) {
                return modelDir.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * 从 assets 复制预置模型到内部存储（首次使用）
     * 返回模型路径，如果 assets 中没有模型则返回 null
     */
    private String copyModelFromAssets() {
        try {
            String[] assetsFiles = context.getAssets().list(MODEL_DIR_NAME);
            if (assetsFiles == null || assetsFiles.length == 0) {
                return null; // assets 中没有预置模型
            }

            File modelDir = new File(context.getFilesDir(), MODEL_DIR_NAME);
            modelDir.mkdirs();

            copyAssetsDir(MODEL_DIR_NAME, modelDir);

            // 验证复制结果
            if (new File(modelDir, "am").exists() || new File(modelDir, "conf").exists()) {
                return modelDir.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Copy model from assets failed", e);
        }
        return null;
    }

    /**
     * 递归复制 assets 目录到目标路径
     */
    private void copyAssetsDir(String assetPath, File targetDir) throws IOException {
        String[] files = context.getAssets().list(assetPath);
        if (files == null) return;

        for (String file : files) {
            String childPath = assetPath + "/" + file;
            String[] subFiles = context.getAssets().list(childPath);
            if (subFiles != null && subFiles.length > 0) {
                // 是目录
                File subDir = new File(targetDir, file);
                subDir.mkdirs();
                copyAssetsDir(childPath, subDir);
            } else {
                // 是文件
                File outFile = new File(targetDir, file);
                InputStream in = context.getAssets().open(childPath);
                FileOutputStream out = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
        }
    }

    /**
     * 下载并解压模型
     */
    private void downloadAndExtractModel() throws Exception {
        File targetDir = context.getFilesDir();
        File modelDir = new File(targetDir, MODEL_DIR_NAME);
        modelDir.mkdirs();

        mainHandler.post(() -> listener.onModelDownloadProgress(0));

        // 下载 zip 文件
        URL url = new URL(MODEL_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "PinyinIME/1.0");
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new Exception("下载失败: HTTP " + responseCode);
        }

        int totalSize = conn.getContentLength();
        if (totalSize <= 0) totalSize = 42 * 1024 * 1024; // 回退值 42MB

        InputStream input = conn.getInputStream();
        File tempZip = new File(targetDir, "vosk-model-temp.zip");
        FileOutputStream output = new FileOutputStream(tempZip);

        byte[] buf = new byte[8192];
        int len;
        long downloaded = 0;
        int lastPercent = 0;

        while ((len = input.read(buf)) > 0) {
            output.write(buf, 0, len);
            downloaded += len;
            int percent = (int) (downloaded * 100 / totalSize);
            if (percent > lastPercent + 5) {
                lastPercent = percent;
                final int p = percent;
                mainHandler.post(() -> listener.onModelDownloadProgress(p));
            }
        }
        output.close();
        input.close();
        conn.disconnect();

        mainHandler.post(() -> listener.onModelDownloadProgress(100));

        // 解压 zip
        ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip));
        ZipEntry entry;
        byte[] zbuf = new byte[8192];

        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;

            String name = entry.getName();
            // zip 内顶层目录为 vosk-model-small-cn-0.22/，去掉它
            String relativePath;
            if (name.startsWith(MODEL_DIR_NAME + "/")) {
                relativePath = name.substring(MODEL_DIR_NAME.length() + 1);
            } else {
                relativePath = name;
            }

            File outFile = new File(modelDir, relativePath);
            outFile.getParentFile().mkdirs();

            FileOutputStream fos = new FileOutputStream(outFile);
            while ((len = zis.read(zbuf)) > 0) {
                fos.write(zbuf, 0, len);
            }
            fos.close();
            zis.closeEntry();
        }
        zis.close();

        // 删除临时 zip
        tempZip.delete();
    }

    /**
     * 从最终结果 JSON 中提取文本
     * JSON 格式: {"text": "识别文字"}
     */
    private String extractText(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("text", "").trim().replaceAll("\\s+", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 从部分结果 JSON 中提取文本
     * JSON 格式: {"partial": "部分识别文字"}
     */
    private String extractPartial(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            return obj.optString("partial", "").trim().replaceAll("\\s+", "");
        } catch (Exception e) {
            return "";
        }
    }
}
