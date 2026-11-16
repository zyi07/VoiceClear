package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频质量分析测试。
 *
 * 阈值来自真机实测数据（华为 PLA-AL10 / Android 16，7 次录音）：
 * - RMS 0.0000 / 峰值 0.0000 —— 完全无信号（5 次）
 * - RMS 0.0063 / 峰值 0.0580 —— 有信号但极弱（1 次）
 * - RMS 0.0446 / 峰值 0.3828 —— 正常说话（1 次）
 */
class AudioQualityTest {

    /** 构造指定 RMS 的正弦波。 */
    private fun sine(rms: Float, samples: Int = 16000): FloatArray {
        val output = FloatArray(samples)
        val amplitude = rms * 1.4142f
        for (index in output.indices) {
            output[index] = amplitude * kotlin.math.sin(2.0 * Math.PI * 440 * index / 16000.0).toFloat()
        }
        return output
    }

    @Test
    fun `empty samples are silent`() {
        val analysis = AudioQuality.analyze(FloatArray(0))
        assertEquals(AudioQuality.Verdict.SILENT, analysis.verdict)
        assertEquals(0f, analysis.rms, 0.0001f)
    }

    @Test
    fun `all zero samples are silent`() {
        val analysis = AudioQuality.analyze(FloatArray(16000))
        assertEquals(AudioQuality.Verdict.SILENT, analysis.verdict)
        assertEquals(0f, analysis.rms, 0.0001f)
    }

    @Test
    fun `normal speech level is normal verdict`() {
        val analysis = AudioQuality.analyze(sine(0.0446f))
        assertEquals(AudioQuality.Verdict.NORMAL, analysis.verdict)
        assertEquals(0.0446f, analysis.rms, 0.003f)
    }

    @Test
    fun `very weak signal is too low`() {
        // 实测那次 RMS=0.0063 的录音：能量很低，但不是完全静音
        val analysis = AudioQuality.analyze(sine(0.0063f))
        assertEquals(AudioQuality.Verdict.TOO_LOW, analysis.verdict)
    }

    @Test
    fun `signal below silence threshold is silent`() {
        val analysis = AudioQuality.analyze(sine(0.0001f))
        assertEquals(AudioQuality.Verdict.SILENT, analysis.verdict)
    }

    @Test
    fun `peak is tracked`() {
        val analysis = AudioQuality.analyze(sine(0.0446f))
        // 正弦波峰值约为 RMS * sqrt(2)
        assertTrue("峰值应大于 RMS", analysis.peak > analysis.rms)
        assertTrue("峰值应在合理范围", analysis.peak < 0.1f)
    }

    @Test
    fun `pcm16 conversion normalizes range`() {
        // 0x0000 -> 0.0, 0x7FFF -> ~1.0, 0x8000 -> -1.0
        val bytes = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val samples = AudioQuality.toFloatSamples(bytes)
        assertEquals(3, samples.size)
        assertEquals(0f, samples[0], 0.0001f)
        assertTrue("应为接近 1 的正数", samples[1] > 0.99f)
        assertTrue("应为接近 -1 的负数", samples[2] < -0.99f)
    }

    @Test
    fun `odd byte count is handled`() {
        val samples = AudioQuality.toFloatSamples(byteArrayOf(0x01, 0x00, 0x02))
        assertEquals(1, samples.size)
    }

    @Test
    fun `describe gives distinct messages`() {
        assertEquals(
            "没有检测到声音",
            AudioQuality.analyze(FloatArray(100)).describe(),
        )
        assertEquals(
            "声音很弱",
            AudioQuality.analyze(sine(0.005f)).describe(),
        )
        assertEquals(
            "声音正常",
            AudioQuality.analyze(sine(0.05f)).describe(),
        )
    }

    @Test
    fun `silence threshold matches measured data`() {
        // 阈值必须与实测数据对齐，否则会把正常语音判成静音
        assertEquals(0.001f, AudioQuality.SILENCE_RMS_THRESHOLD, 0.0000001f)
        assertTrue(AudioQuality.LOW_ENERGY_RMS_THRESHOLD > AudioQuality.SILENCE_RMS_THRESHOLD)
        assertEquals(0.01f, AudioQuality.LOW_ENERGY_RMS_THRESHOLD, 0.0000001f)
    }
}
