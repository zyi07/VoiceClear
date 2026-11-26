package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SenseVoice 配置参数回归测试。
 *
 * 这些参数**必须**与官方 sherpa-onnx 保持一致，否则模型会静默返回空结果。
 * 曾经踩过的坑：`featureDim` 误填 560（正确是 80），
 * native 侧报 "Got invalid dimensions for input: x ... Expected: 560"
 * 异常被捕获后**不报错**，只是识别不出任何内容。
 */
class SenseVoiceConfigTest {

    @Test
    fun `feature dim must be 80 not 560`() {
        // 官方 Python API 的默认值是 feature_dim=80。
        assertEquals(80, SenseVoiceRecognitionEngine.FEATURE_DIM)
    }

    @Test
    fun `language is fixed to Chinese for both profiles`() {
        assertEquals("zh", SenseVoiceRecognitionEngine.LANGUAGE)
        assertEquals(listOf("zh", "zh"), SpeechProfile.ALL.map { it.asrLanguage })
    }

    @Test
    fun `unknown profile id falls back to Mandarin`() {
        assertEquals(SpeechProfile.MANDARIN, SpeechProfile.fromId(null))
        assertEquals(SpeechProfile.MANDARIN, SpeechProfile.fromId("unknown"))
        assertEquals(SpeechProfile.SHANDONG, SpeechProfile.fromId("shandong"))
    }

    @Test
    fun `both profiles pass zh to native config`() {
        SpeechProfile.ALL.forEach { profile ->
            val dir = java.io.File.createTempFile("sensevoice-profile", ".dir").apply {
                delete()
                mkdirs()
            }
            try {
                java.io.File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(byteArrayOf(1))
                java.io.File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(byteArrayOf(1))
                var nativeConfig: com.k2fsa.sherpa.onnx.OfflineRecognizerConfig? = null
                val engine = SenseVoiceRecognitionEngine(
                    dir.absolutePath,
                    SenseVoiceNativeFactory { config ->
                        nativeConfig = config
                        object : SenseVoiceNativeAdapter {
                            override fun decode(samples: FloatArray, sampleRate: Int) =
                                SenseVoiceNativeResult("测试", "zh")

                            override fun release() = Unit
                        }
                    },
                    profile,
                )

                assertTrue(engine.load())
                assertEquals(profile, engine.profile)
                assertEquals("zh", nativeConfig?.modelConfig?.senseVoice?.language)
                val result = kotlinx.coroutines.runBlocking {
                    engine.recognize(
                        RecognitionRequest(
                            sessionId = "profile-test",
                            audio = AudioSegment(
                                sessionId = "profile-test",
                                sampleRate = 16_000,
                                channelCount = 1,
                                encoding = AudioEncoding.PCM_16BIT,
                                durationMs = 1,
                                samples = ByteArray(2),
                            ),
                            engineVersion = "test",
                        ),
                    )
                }
                assertEquals(profile.id, result.speechProfileId)
                assertEquals("zh", result.detectedLanguage)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `recognition metadata defaults preserve old callers`() {
        val result = RecognitionResult(
            sessionId = "legacy",
            rawText = "文本",
            normalizedText = "文本",
            confidence = null,
        )
        assertEquals(SpeechProfile.MANDARIN.id, result.speechProfileId)
        assertEquals(null, result.detectedLanguage)
    }

    @Test
    fun `sample rate matches model requirement`() {
        assertEquals(16000, SenseVoiceRecognitionEngine.SAMPLE_RATE)
    }

    @Test
    fun `token file name is the one sherpa expects`() {
        // native 侧按此文件名查找词表，名字错了会报 "Errors found in config!"。
        assertEquals("tokens.txt", SenseVoiceRecognitionEngine.TOKENS_FILE)
    }

    @Test
    fun `engine must not claim to provide confidence`() {
        // SenseVoice 不提供 token 级置信度，
        // 若谎称提供，上层可能用不存在的置信度做自动放行决策。
        assertTrue(!SenseVoiceRecognitionEngine("/tmp").capabilities.providesConfidence)
    }
}
