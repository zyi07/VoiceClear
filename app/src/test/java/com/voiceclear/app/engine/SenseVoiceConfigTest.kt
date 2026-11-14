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
    fun `language is empty for auto detection`() {
        // 官方默认值是 language=""（自动检测）。模型是多语种，
        // 显式限定中文反而会限制能力。
        assertEquals("", SenseVoiceRecognitionEngine.LANGUAGE)
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
