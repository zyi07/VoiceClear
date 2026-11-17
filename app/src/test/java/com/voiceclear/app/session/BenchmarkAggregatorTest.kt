package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基准聚合测试（审计 4.2）。
 */
class BenchmarkAggregatorTest {

    private fun sample(capture: Long, recognition: Long, total: Long, audioMs: Long = 1000L) =
        BenchmarkAggregator.Sample(
            captureMs = capture,
            recognitionMs = recognition,
            synthesisMs = 10L,
            playbackMs = 20L,
            totalMs = total,
            audioMs = audioMs,
        )

    @Test
    fun `empty snapshot reports zero and explains itself`() {
        val aggregator = BenchmarkAggregator()
        val snapshot = aggregator.snapshot()
        assertEquals(0, snapshot.sampleCount);
        assertTrue(snapshot.toJson().contains("尚无样本"));
    }

    @Test
    fun `window keeps only newest samples`() {
        val aggregator = BenchmarkAggregator(windowSize = 3)
        repeat(5) { index ->
            aggregator.add(sample(capture = index.toLong(), recognition = index.toLong(), total = 100L));
        }
        assertEquals(3, aggregator.size);
        val snapshot = aggregator.snapshot()
        assertEquals(3, snapshot.sampleCount);
        // 保留的是 2、3、4，最小值应为 2
        assertEquals(2L, snapshot.capture!!.minMs);
        assertEquals(4L, snapshot.capture!!.maxMs);
    }

    @Test
    fun `median uses nearest rank without interpolation`() {
        val aggregator = BenchmarkAggregator()
        listOf(100L, 200L, 300L).forEach { value ->
            aggregator.add(sample(capture = value, recognition = value, total = value));
        }
        val stats = aggregator.snapshot().recognition!!
        assertEquals(200L, stats.medianMs);
        assertEquals(300L, stats.p95Ms);
        assertEquals(100L, stats.minMs);
        assertEquals(200L, stats.meanMs);
    }

    @Test
    fun `single sample has identical percentiles`() {
        val aggregator = BenchmarkAggregator()
        aggregator.add(sample(capture = 50L, recognition = 120L, total = 200L));
        val stats = aggregator.snapshot().recognition!!
        assertEquals(120L, stats.medianMs);
        assertEquals(120L, stats.p95Ms);
        assertEquals(1, stats.count);
    }

    @Test
    fun `p95 is not the max for large samples`() {
        // 窗口设 100 才能装下 100 个样本；默认窗口 50 会裁掉前一半。
        val aggregator = BenchmarkAggregator(windowSize = 100)
        // 100 个样本：0..99，P95 应落在 94 而不是 99
        repeat(100) { index ->
            aggregator.add(sample(capture = 1L, recognition = index.toLong(), total = 100L));
        }
        val stats = aggregator.snapshot().recognition!!
        // 最近秩法：rank = ceil(0.95 * 100) = 95，取第 95 小的值；
        // 样本是 0..99，第 95 小即 94（下标 94）。
        assertEquals(94L, stats.p95Ms);
        assertEquals(99L, stats.maxMs);
        // P95 必须严格小于最大值，否则说明退化成了取最大值
        assertTrue(stats.p95Ms < stats.maxMs);
    }

    @Test
    fun `real time factor is computed from audio length`() {
        val aggregator = BenchmarkAggregator()
        aggregator.add(sample(capture = 50L, recognition = 500L, total = 600L, audioMs = 1000L));
        val snapshot = aggregator.snapshot()
        assertEquals(0.5, snapshot.medianRtf, 0.001);
    }

    @Test
    fun `zero audio length does not divide by zero`() {
        val aggregator = BenchmarkAggregator()
        aggregator.add(sample(capture = 50L, recognition = 500L, total = 600L, audioMs = 0L));
        assertEquals(0.0, aggregator.snapshot().medianRtf, 0.0001);
    }

    @Test
    fun `json contains all stages and the caveat`() {
        val aggregator = BenchmarkAggregator()
        aggregator.add(sample(capture = 50L, recognition = 120L, total = 200L));
        val json = aggregator.snapshot().toJson();
        listOf("capture", "recognition", "synthesis", "playback", "total").forEach { stage ->
            assertTrue("缺少阶段: " + stage, json.contains(stage));
        }
        // 必须带上口径声明，避免被误读成准确率指标
        assertTrue(json.contains("不含识别准确率"));
    }

    @Test
    fun `reset clears samples`() {
        val aggregator = BenchmarkAggregator()
        aggregator.add(sample(capture = 1L, recognition = 1L, total = 1L));
        aggregator.reset();
        assertEquals(0, aggregator.size);
        assertEquals(0, aggregator.snapshot().sampleCount);
    }

    @Test
    fun `default window matches audit suggestion`() {
        assertEquals(50, BenchmarkAggregator.DEFAULT_WINDOW);
    }
}
