package com.voiceclear.app.engine

import kotlinx.coroutines.delay

/**
 * 假识别引擎：用于 P1 原型的确定性测试与无模型环境下的端到端联调。
 *
 * 它不模拟真实识别质量，只保证接口契约、时序与失败路径可被测试。
 */
class FakeRecognitionEngine(
    private val resultText: String = "今天下午三点医院复诊",
    private val confidence: Float? = null,
    private val latencyMs: Long = 120,
) : RecognitionEngine {
    override val engineId: String = RecognitionEngineOption.FAKE.engineId

    override val capabilities: EngineCapabilities = RecognitionEngineOption.FAKE.capabilities

    override suspend fun recognize(request: RecognitionRequest): RecognitionResult {
        delay(latencyMs)
        return RecognitionResult(
            sessionId = request.sessionId,
            rawText = resultText,
            normalizedText = resultText,
            confidence = confidence,
        )
    }
}

/**
 * 假合成引擎：返回与请求文本等长的静音片段，并记录调用参数，
 * 便于验证"确认文本与合成文本一致"这一内容安全约束。
 */
class FakeSynthesisEngine(
    private val latencyMs: Long = 80,
) : SynthesisEngine {
    var lastRequestedText: String? = null
        private set

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        delay(latencyMs)
        lastRequestedText = request.confirmedText
        val sampleRate = 16_000
        val estimatedSamples = (sampleRate * 0.4).toInt()
        return SynthesisResult(
            sessionId = request.sessionId,
            audio = AudioSegment(
                sessionId = request.sessionId,
                sampleRate = sampleRate,
                channelCount = 1,
                encoding = AudioEncoding.PCM_16BIT,
                durationMs = 400,
                samples = ByteArray(estimatedSamples * 2),
            ),
            // 与 audio.durationMs 同口径，避免 400 ms 音频却报告 80 ms。
            durationMs = 400,
        )
    }
}
