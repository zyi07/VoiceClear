package com.voiceclear.app.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧离线中文识别引擎（sherpa-onnx + SenseVoice）。
 *
 * 模型：SenseVoice Small int8 量化版（多语种含中文），约 239 MB。
 * 公开基准：AISHELL-1 测试集 CER 7.81%（普通说话人数据，官方公布）。
 * **目标人群（听障相关发音不清）的真实准确率尚无实测数据，不能引用该基准代替。**
 *
 * API 依据（官方 AAR sherpa-onnx-2.0.1.aar，javap 实测签名）：
 * - OfflineRecognizerConfig(featConfig, modelConfig, decodingMethod, ...)
 * - FeatureConfig(sampleRate, featureDim)
 * - OfflineModelConfig(senseVoice, tokens, numThreads, provider, ...)
 * - OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization)
 * - OfflineRecognizer(assetManager, config).createStream()
 * - stream.acceptWaveform(FloatArray, Int) / recognizer.decode(stream)
 * - recognizer.getResult(stream).text / .lang
 *
 * 关键点（踩过的坑，勿删）：
 * 1. `tokens` 必须设置为 tokens.txt 的**绝对路径**，否则 native 侧报
 *    "Errors found in config!"，识别结果为空。
 * 2. native 库由官方 AAR 内部用 System.loadLibrary 加载，
 *    **不要**再自行加载或解压（第三方 jar 的桌面版加载器会失败）。
 *
 * 重要限制（诚实边界）：SenseVoice **不提供 token 级置信度**。
 * 因此本引擎返回的 confidence 恒为 null，上层不得把它当作高置信度自动放行；
 * 内容安全需依赖「用户确认文本」这一交互，而不是置信度阈值。
 */
class SenseVoiceRecognitionEngine(
    private val modelDirPath: String,
) : RecognitionEngine {

    companion object {
        const val ENGINE_ID = "sherpa-sensevoice-zh"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 560
        const val LANGUAGE = "zh"
        const val NUM_THREADS = 2

        const val ERR_MODEL_MISSING = "asr_model_missing"
        const val ERR_MODEL_LOAD_FAILED = "asr_model_load_failed"
        const val ERR_DECODE_FAILED = "asr_decode_failed"
        const val ERR_EMPTY_AUDIO = "asr_empty_audio"
    }

    private var recognizer: OfflineRecognizer? = null
    private var loadFailed = false

    override val engineId: String = ENGINE_ID

    override val capabilities: EngineCapabilities = EngineCapabilities(
        engineId = ENGINE_ID,
        offline = true,
        providesConfidence = false,
        ownsMicrophone = false,
        preservesSpeakerIdentity = false,
    )

    /** 模型文件是否齐备。 */
    fun isModelReady(): Boolean {
        val dir = File(modelDirPath)
        return File(dir, MODEL_FILE).exists() && File(dir, TOKENS_FILE).exists()
    }

    /** 模型目录。 */
    fun modelDir(): File = File(modelDirPath)

    /**
     * 加载模型。加载较慢（数秒），应在后台线程调用。
     */
    fun load(): Boolean {
        if (recognizer != null) return true
        if (loadFailed) return false
        if (!isModelReady()) {
            DiagnosticLog.e("SenseVoice", "模型缺失, 目录=" + modelDirPath)
            return false
        }

        return try {
            val modelPath = File(modelDir(), MODEL_FILE).absolutePath
            val tokensPath = File(modelDir(), TOKENS_FILE).absolutePath

            val featureConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = FEATURE_DIM,
            )
            val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                model = modelPath,
                language = LANGUAGE,
                useInverseTextNormalization = true,
            )
            val modelConfig = OfflineModelConfig(
                senseVoice = senseVoiceConfig,
                // 必须给绝对路径，否则 native 侧校验失败、结果为空。
                tokens = tokensPath,
                numThreads = NUM_THREADS,
                provider = "cpu",
            )
            val config = OfflineRecognizerConfig(
                featConfig = featureConfig,
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )

            recognizer = OfflineRecognizer(null, config)
            DiagnosticLog.i(
                "SenseVoice",
                "模型加载成功, 模型=" + modelPath + ", 词表=" + tokensPath,
            )
            true
        } catch (error: Throwable) {
            loadFailed = true
            DiagnosticLog.e(
                "SenseVoice",
                "模型加载失败: " + (error.message ?: error.javaClass.name),
            )
            false
        }
    }

    override suspend fun recognize(request: RecognitionRequest): RecognitionResult {
        val engine = recognizer
        if (engine == null) {
            return RecognitionResult(
                sessionId = request.sessionId,
                rawText = "",
                normalizedText = "",
                confidence = null,
                warnings = listOf(ERR_MODEL_LOAD_FAILED),
            )
        }

        val samples = pcm16ToFloat(request.audio.samples, request.audio.sampleRate)
        if (samples.isEmpty()) {
            return RecognitionResult(
                sessionId = request.sessionId,
                rawText = "",
                normalizedText = "",
                confidence = null,
                warnings = listOf(ERR_EMPTY_AUDIO),
            )
        }

        return try {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, request.audio.sampleRate)
                engine.decode(stream)
                val result = engine.getResult(stream)
                val rawText = result.text.trim()

                // SenseVoice 输出带语言/情绪标签（如 <|zh|><|NEUTRAL|>文本），
                // 这里只取可读文本部分，避免把标签透传给用户。
                val cleanText = stripTags(rawText)

                // 记录音频能量：用于区分「没录到声音」与「录到了但识别不出」。
                val (rms, peak) = audioEnergy(samples)
                DiagnosticLog.i(
                    "SenseVoice",
                    "识别完成, 语言=" + result.lang + ", 文本长度=" + cleanText.length +
                        ", 音频时长=" + (samples.size / SAMPLE_RATE) + "ms" +
                        ", RMS=" + String.format(java.util.Locale.US, "%.4f", rms) +
                        ", 峰值=" + String.format(java.util.Locale.US, "%.4f", peak),
                )
                RecognitionResult(
                    sessionId = request.sessionId,
                    rawText = rawText,
                    normalizedText = cleanText,
                    // SenseVoice 不提供置信度，必须返回 null 而不是编造数值。
                    confidence = null,
                    warnings = emptyList(),
                )
            } finally {
                stream.release()
            }
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "SenseVoice",
                "解码失败: " + (error.message ?: error.javaClass.name),
            )
            RecognitionResult(
                sessionId = request.sessionId,
                rawText = "",
                normalizedText = "",
                confidence = null,
                warnings = listOf(ERR_DECODE_FAILED),
            )
        }
    }

    /** 释放 native 资源。 */
    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /**
     * 把 PCM16 字节转成归一化浮点采样。
     *
     * 若设备采样率不是 16 kHz，这里不做重采样——采集层已固定为 16 kHz；
     * 采样率不符时返回空结果，避免用错误速率喂给模型产生乱码。
     */
    private fun pcm16ToFloat(bytes: ByteArray, sampleRate: Int): FloatArray {
        if (sampleRate != SAMPLE_RATE) {
            DiagnosticLog.w(
                "SenseVoice",
                "采样率不匹配: " + sampleRate + "，期望 " + SAMPLE_RATE + "，本次跳过识别",
            )
            return FloatArray(0)
        }
        val count = bytes.size / 2
        if (count == 0) return FloatArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val output = FloatArray(count)
        for (index in 0 until count) {
            output[index] = buffer.short.toFloat() / 32768.0f
        }
        return output
    }

    /**
     * 计算音频能量。
     *
     * 返回 (RMS, 峰值)。RMS 接近 0 说明基本是静音——用户可能没说话、
     * 麦克风被占用，或录音路由有问题；RMS 正常但识别为空则说明模型
     * 没认出这段发音，需要进一步排查模型或采集质量。
     */
    private fun audioEnergy(samples: FloatArray): Pair<Double, Double> {
        if (samples.isEmpty()) return Pair(0.0, 0.0)
        var sum = 0.0
        var peak = 0.0
        for (value in samples) {
            val abs = if (value < 0) -value.toDouble() else value.toDouble()
            sum += abs * abs
            if (abs > peak) peak = abs
        }
        return Pair(Math.sqrt(sum / samples.size), peak)
    }

    /** 去掉模型输出中的标签，只保留可读文本。 */
    private fun stripTags(text: String): String {
        if (!text.contains("<|")) return text
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("<|", index)
            if (start < 0) {
                builder.append(text, index, text.length)
                break
            }
            builder.append(text, index, start)
            val end = text.indexOf("|>", start + 2)
            if (end < 0) break
            index = end + 2
        }
        return builder.toString().trim()
    }
}
