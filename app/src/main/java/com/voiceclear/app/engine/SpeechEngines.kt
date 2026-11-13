package com.voiceclear.app.engine

/**
 * 语音引擎适配层（技术指导文件 4.1 节）。
 *
 * 识别与合成必须通过接口调用，UI 层不直接依赖具体模型，
 * 以便用假引擎做确定性测试，并在后续阶段替换为端侧运行时。
 */
interface RecognitionEngine {
    /** 识别一段已采集音频，返回文本候选与可用置信度。 */
    suspend fun recognize(request: RecognitionRequest): RecognitionResult
}

interface SynthesisEngine {
    /** 按已确认文本合成语音。 */
    suspend fun synthesize(request: SynthesisRequest): SynthesisResult
}

data class RecognitionRequest(
    val sessionId: String,
    val audio: AudioSegment,
    val engineVersion: String,
)

data class RecognitionResult(
    val sessionId: String,
    val rawText: String,
    val normalizedText: String,
    /** null 表示该引擎未提供可靠置信度，禁止当作高置信度使用。 */
    val confidence: Float?,
    val warnings: List<String> = emptyList(),
)

data class SynthesisRequest(
    val sessionId: String,
    val confirmedText: String,
    val voiceProfileRef: String?,
    val engineVersion: String,
)

data class SynthesisResult(
    val sessionId: String,
    val audio: AudioSegment?,
    val durationMs: Long,
    val warnings: List<String> = emptyList(),
)

/**
 * 采集到的音频片段。原始格式与处理后格式分开保存，便于消融对照。
 */
data class AudioSegment(
    val sessionId: String,
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: AudioEncoding,
    val durationMs: Long,
    val samples: ByteArray,
    val qualityFlags: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSegment) return false
        return sessionId == other.sessionId &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            encoding == other.encoding &&
            durationMs == other.durationMs &&
            samples.contentEquals(other.samples) &&
            qualityFlags == other.qualityFlags
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + encoding.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + qualityFlags.hashCode()
        return result
    }
}

enum class AudioEncoding {
    PCM_16BIT,
}
