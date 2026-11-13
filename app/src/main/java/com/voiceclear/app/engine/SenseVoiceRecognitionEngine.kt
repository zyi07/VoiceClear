package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧离线中文识别引擎（sherpa-onnx + SenseVoice）。
 *
 * 模型：SenseVoice Small int8 量化版（多语种含中文），约 239 MB。
 * 公开基准：AISHELL-1 测试集 CER 7.81%，CPU 实时率约 17 倍（按官方公布数据，
 * 本项目尚未在目标人群与目标机型上复测，不能直接套用）。
 *
 * 接口依据（sherpa-onnx-java-api 1.0.1，javap 实测签名）：
 * - FeatureConfig.Builder().setSampleRate(int).setFeatureDim(int).build()
 * - OfflineSenseVoiceModelConfig.builder().setModel(String).setLanguage(String).build()
 * - OfflineModelConfig.Builder().setSenseVoice(...).build()
 * - OfflineRecognizerConfig.Builder().setFeatureConfig(...).setModelConfig(...).build()
 * - OfflineRecognizer(config).createStream() / stream.acceptWaveform(float[], int) / decode(stream)
 * - OfflineRecognizerResult.getText() / getTokens() / getTimestamps()
 *
 * 重要限制（诚实边界）：SenseVoice **不提供 token 级置信度**。
 * 因此本引擎返回的 confidence 恒为 null，上层不得把它当作高置信度自动放行；
 * 内容安全需依赖"用户确认文本"这一交互，而不是置信度阈值。
 */
class SenseVoiceRecognitionEngine(
    private val context: Context,
    private val modelDirPath: String,
) : RecognitionEngine {

    companion object {
        const val ENGINE_ID = "sherpa-sensevoice-zh"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 560
        const val LANGUAGE = "zh"

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
     * 加载模型。加载较慢（数秒），应在后台线程调用并缓存实例。
     */
    fun load(): Boolean {
        if (recognizer != null) return true
        if (loadFailed) return false
        if (!isModelReady()) {
            DiagnosticLog.e("SenseVoice", "模型缺失, 目录=" + modelDirPath)
            return false
        }

        return try {
            val featureConfig = FeatureConfig.builder()
                .setSampleRate(SAMPLE_RATE)
                .setFeatureDim(FEATURE_DIM)
                .build()

            val senseVoiceConfig = OfflineSenseVoiceModelConfig.builder()
                .setModel(File(modelDir(), MODEL_FILE).absolutePath)
                .setLanguage(LANGUAGE)
                .setInverseTextNormalization(true)
                .build()

            val modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(senseVoiceConfig)
                .build()

            val config = OfflineRecognizerConfig.builder()
                .setFeatureConfig(featureConfig)
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build()

            recognizer = OfflineRecognizer(config)
            DiagnosticLog.i(
                "SenseVoice",
                "模型加载成功, 目录=" + modelDirPath + ", 语言=" + LANGUAGE,
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
                stream.acceptWaveform(samples, samples.size)
                engine.decode(stream)
                val result = engine.getResult(stream)
                val rawText = result.text.trim()

                // SenseVoice 可能输出带标签的形式（如 <|zh|><|NEUTRAL|>文本），
                // 这里只取可读文本部分，避免把标签透传给用户。
                val cleanText = stripTags(rawText)

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
