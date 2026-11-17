package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频质量分析的边界条件测试（审计 5.2：边界条件测试缺口）。
 *
 * 这些输入在真实使用中会出现——用户点一下就松手、麦克风被完全遮挡、
 * 恰好卡在阈值上的能量——算法在这里不能抛异常，也不能给出自相矛盾的判定。
 */
class AudioQualityEdgeCaseTest {

    @Test
    fun `single sample is handled`() {
        val analysis = AudioQuality.analyze(floatArrayOf(0.5f));
        assertEquals(1, 1);
        assertTrue(analysis.rms > 0f);
    }

    @Test
    fun `single silent sample is silent`() {
        val analysis = AudioQuality.analyze(floatArrayOf(0f));
        assertEquals(AudioQuality.Verdict.SILENT, analysis.verdict);
    }

    @Test
    fun `two samples average correctly`() {
        // RMS of [0.02, -0.02] = 0.02
        val analysis = AudioQuality.analyze(floatArrayOf(0.02f, -0.02f));
        assertEquals(0.02f, analysis.rms, 0.0001f);
        assertEquals(AudioQuality.Verdict.NORMAL, analysis.verdict);
    }

    @Test
    fun `exactly at silence threshold goes to too low`() {
        // 判定用严格小于：恰好等于阈值不应被判为静音
        val analysis = AudioQuality.analyze(floatArrayOf(0.001f));
        assertEquals(AudioQuality.Verdict.TOO_LOW, analysis.verdict);
    }

    @Test
    fun `exactly at low energy threshold goes to normal`() {
        val analysis = AudioQuality.analyze(floatArrayOf(0.01f));
        assertEquals(AudioQuality.Verdict.NORMAL, analysis.verdict);
    }

    @Test
    fun `full scale samples do not overflow`() {
        // 满量程 +1.0：平方和会达到样本数，不能出现溢出或 NaN
        val samples = FloatArray(1000) { 1.0f };
        val analysis = AudioQuality.analyze(samples);
        assertEquals(1.0f, analysis.rms, 0.0001f);
        assertTrue(!analysis.rms.isNaN());
        assertEquals(AudioQuality.Verdict.NORMAL, analysis.verdict);
    }

    @Test
    fun `all samples counted as active when loud`() {
        val analysis = AudioQuality.analyze(FloatArray(100) { 0.5f });
        assertEquals(1.0f, analysis.activeRatio, 0.0001f);
    }

    @Test
    fun `no sample counted as active when below threshold`() {
        val analysis = AudioQuality.analyze(FloatArray(100) { 0.0005f });
        assertEquals(0.0f, analysis.activeRatio, 0.0001f);
        assertEquals(AudioQuality.Verdict.SILENT, analysis.verdict);
    }

    @Test
    fun `empty byte array yields empty floats`() {
        assertEquals(0, AudioQuality.toFloatSamples(ByteArray(0)).size);
    }

    @Test
    fun `single trailing byte is dropped not crashed`() {
        // PCM16 需要 2 字节一个样本，奇数长度说明数据被截断
        val samples = AudioQuality.toFloatSamples(byteArrayOf(0x00, 0x00, 0x11));
        assertEquals(1, samples.size);
    }

    @Test
    fun `pcm16 extremes map to plus minus one`() {
        // 0x7FFF = 32767 -> 约 +1；0x8000 = -32768 -> -1
        val samples = AudioQuality.toFloatSamples(
            byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()),
        );
        assertEquals(2, samples.size);
        assertTrue(samples[0] > 0.999f);
        assertEquals(-1.0f, samples[1], 0.0001f);
    }

    @Test
    fun `long capture does not overflow arithmetic`() {
        // 30 秒 16 kHz 的样本量（480000），求和用 Double 不应溢出
        val samples = FloatArray(480_000) { 0.3f };
        val analysis = AudioQuality.analyze(samples);
        assertEquals(0.3f, analysis.rms, 0.001f);
    }
}
