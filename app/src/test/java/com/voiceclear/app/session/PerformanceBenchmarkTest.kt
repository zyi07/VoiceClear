package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能基准测试。
 */
class PerformanceBenchmarkTest {

    @Test
    fun `real time factor is computed from audio duration`() {
        val record = PerformanceBenchmark.Record(
            timestamp = 0L,
            deviceModel = "test",
            androidVersion = "test",
            coldStartMs = 0L,
            modelLoadMs = 0L,
            recognitionMs = 500L,
            audioSeconds = 2.0,
            peakMemoryMb = 384L,
            availableMemoryMb = 100L,
        )
        // 0.5s 音频用 0.5s 识别完 → 实时率 1.0
        assertEquals(0.25, record.realTimeFactor, 0.001)
    }

    @Test
    fun `zero audio duration yields zero factor`() {
        val record = PerformanceBenchmark.Record(
            timestamp = 0L,
            deviceModel = "test",
            androidVersion = "test",
            coldStartMs = 0L,
            modelLoadMs = 0L,
            recognitionMs = 500L,
            audioSeconds = 0.0,
            peakMemoryMb = 384L,
            availableMemoryMb = 100L,
        )
        assertEquals(0.0, record.realTimeFactor, 0.0001)
        assertEquals("未知", record.experienceTier)
    }

    @Test
    fun `experience tier reflects speed`() {
        fun tier(recognitionMs: Long, audioSeconds: Double): String {
            return PerformanceBenchmark.Record(
                timestamp = 0L,
                deviceModel = "test",
                androidVersion = "test",
                coldStartMs = 0L,
                modelLoadMs = 0L,
                recognitionMs = recognitionMs,
                audioSeconds = audioSeconds,
                peakMemoryMb = 384L,
                availableMemoryMb = 100L,
            ).experienceTier
        }

        // 比实时快 10 倍
        assertTrue(tier(100L, 1.0).startsWith("优秀"))
        // 比实时快 2 倍
        assertTrue(tier(500L, 1.0).startsWith("良好"))
        // 与实时相当
        assertTrue(tier(1000L, 1.0).startsWith("可用"))
        // 慢于实时
        assertTrue(tier(2000L, 1.0).startsWith("偏慢"))
    }

    @Test
    fun `json export contains key fields`() {
        val record = PerformanceBenchmark.Record(
            timestamp = 1700000000000L,
            deviceModel = "HUAWEI Test",
            androidVersion = "16 (API 36)",
            coldStartMs = 800L,
            modelLoadMs = 3000L,
            recognitionMs = 250L,
            audioSeconds = 2.5,
            peakMemoryMb = 384L,
            availableMemoryMb = 120L,
        )
        val json = record.toJson()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("recognitionMs"))
        assertTrue(json.contains("realTimeFactor"))
        assertTrue(json.contains("HUAWEI Test"))
    }

    @Test
    fun `record recognition produces valid factor`() {
        val record = PerformanceBenchmark.recordRecognition(
            recognitionMs = 400L,
            audioMs = 2000L,
            modelLoadMs = 2500L,
            coldStartMs = 900L,
        )
        assertEquals(0.2, record.realTimeFactor, 0.01)
        assertTrue(record.peakMemoryMb > 0)
        assertTrue(record.deviceModel.isNotBlank())
    }

    @Test
    fun `measure returns elapsed time`() {
        val elapsed = PerformanceBenchmark.measure {
            var sum = 0
            for (index in 0 until 100000) {
                sum += index
            }
            assertTrue(sum >= 0)
        }
        assertTrue("耗时应为非负", elapsed >= 0)
    }
}
