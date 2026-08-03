package com.ijiazhen.pinyin9x

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sherpa-ONNX 流式语音识别器
 * - 基于 FunASR Paraformer 流式模型（达摩院）
 * - 原生流式逐字输出（边说边写）
 * - 内置端点检测（VAD/Endpoint）
 * - 无需 Google 服务，离线推理
 */
class SherpaOnnxVoiceRecognizer(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onReadyForSpeech()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SherpaOnnxVoice"
        private const val MODEL_DIR_NAME = "sherpa-onnx-streaming-paraformer-bilingual-zh-en"
        private const val SAMPLE_RATE = 16000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile private var _recording = false
    @Volatile private var _ready = false
    @Volatile private var _initializing = false

    // ====== 公开 API ======

    fun init() {
        if (_ready || _initializing) return
        _initializing = true

        executor.execute {
            try {
                val modelDir = File(context.filesDir, MODEL_DIR_NAME)

                // 1. 检查模型文件是否完整
                if (!modelFilesExist(modelDir)) {
                    // 2. 尝试从 assets 复制（预置模型）
                    if (!copyModelFromAssets(modelDir)) {
                        _initializing = false
                        mainHandler.post { listener.onError("语音模型未预置，请确保模型文件打包在 assets 中") }
                        return@execute
                    }
                }

                if (!modelFilesExist(modelDir)) {
                    _initializing = false
                    mainHandler.post { listener.onError("模型文件不完整，请确保 assets 中包含模型文件") }
                    return@execute
                }

                // 4. 初始化 OnlineRecognizer（使用文件路径，不使用 AssetManager）
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80
                    ),
                    modelConfig = OnlineModelConfig(
                        paraformer = OnlineParaformerModelConfig(
                            encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelDir, "decoder.int8.onnx").absolutePath
                        ),
                        tokens = File(modelDir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        modelType = "paraformer",
                        provider = "cpu",
                        debug = false
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search"
                )

                recognizer = OnlineRecognizer(config = config)
                _ready = true
                _initializing = false
                mainHandler.post { listener.onReadyForSpeech() }

            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                _initializing = false
                val msg = e.message ?: "未知错误"
                mainHandler.post { listener.onError("模型初始化失败: $msg") }
            }
        }
    }

    fun startListening() {
        if (!_ready || recognizer == null) {
            mainHandler.post { listener.onError("语音模型未就绪，正在初始化...") }
            init()
            return
        }
        if (_recording) return

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // 每次读取 100ms 音频（1600 samples），内部缓冲至少 2x
            val bufferSize = maxOf(minBuf, 3200) * 2

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                mainHandler.post { listener.onError("音频录制初始化失败") }
                return
            }

            _recording = true
            audioRecord?.startRecording()

            recordThread = Thread {
                val rec = recognizer ?: return@Thread
                val stream = rec.createStream()
                val buffer = ShortArray(1600) // 100ms @ 16kHz
                var lastPartialText = ""
                var lastPartialTime = 0L

                while (_recording) {
                    val ret = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (ret <= 0) continue

                    // short[] → float[] 归一化到 [-1, 1]
                    val samples = FloatArray(ret) { buffer[it] / 32768.0f }

                    // 喂入音频流
                    stream.acceptWaveform(samples, SAMPLE_RATE)

                    // 解码（只要 isReady 就持续 decode）
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }

                    // 检测端点（断句）
                    val isEndpoint = rec.isEndpoint(stream)
                    var text = rec.getResult(stream).text

                    if (isEndpoint) {
                        // Paraformer 流式模型：端点时需补充 0.8s 尾部静音
                        val tailPaddings = FloatArray((0.8 * SAMPLE_RATE).toInt())
                        stream.acceptWaveform(tailPaddings, SAMPLE_RATE)
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                        }
                        text = rec.getResult(stream).text

                        // 重置流，开始新句子
                        rec.reset(stream)

                        val finalText = text.trim()
                        if (finalText.isNotEmpty()) {
                            lastPartialText = ""
                            mainHandler.post { listener.onFinalResult(finalText) }
                        }
                    } else if (text.isNotBlank()) {
                        // 部分结果（边说边写预览），节流 80ms
                        val now = System.currentTimeMillis()
                        val partialText = text.trim()
                        if (partialText != lastPartialText && now - lastPartialTime >= 80) {
                            lastPartialText = partialText
                            lastPartialTime = now
                            mainHandler.post { listener.onPartialResult(partialText) }
                        }
                    }
                }

                // 停止时获取最后的结果
                try {
                    val tailPaddings = FloatArray((0.8 * SAMPLE_RATE).toInt())
                    stream.acceptWaveform(tailPaddings, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty() && text != lastPartialText) {
                        mainHandler.post { listener.onFinalResult(text) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Final result error", e)
                }

                stream.release()
            }
            recordThread?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Start listening failed", e)
            mainHandler.post { listener.onError("启动录音失败: ${e.message}") }
        }
    }

    fun stopListening() {
        _recording = false
        recordThread?.let {
            try { it.join(1500) } catch (e: InterruptedException) {}
        }
        recordThread = null
        audioRecord?.let { ar ->
            try {
                if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop()
                }
            } catch (e: Exception) {}
            ar.release()
        }
        audioRecord = null
    }

    fun destroy() {
        stopListening()
        recognizer?.release()
        recognizer = null
        _ready = false
        executor.shutdown()
    }

    fun isReady(): Boolean = _ready
    fun isListening(): Boolean = _recording
    fun isInitializing(): Boolean = _initializing

    // ====== 内部方法 ======

    private fun modelFilesExist(dir: File): Boolean {
        return File(dir, "encoder.int8.onnx").exists() &&
               File(dir, "decoder.int8.onnx").exists() &&
               File(dir, "tokens.txt").exists()
    }

    private fun copyModelFromAssets(targetDir: File): Boolean {
        return try {
            val assets = context.assets.list(MODEL_DIR_NAME) ?: return false
            if (assets.isEmpty()) return false
            targetDir.mkdirs()
            for (file in assets) {
                val assetPath = "$MODEL_DIR_NAME/$file"
                val outFile = File(targetDir, file)
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            modelFilesExist(targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Copy model from assets failed", e)
            false
        }
    }
}
