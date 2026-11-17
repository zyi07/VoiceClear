package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基准聚合的「识别时记录、收尾时回填」语义测试。
 *
 * **背景（真机缺陷，2026-11-17）**：诊断包里的 `benchmark-summary.json` 一直是
 * `{"note":"尚无样本"}`。原因是聚合只在会话收尾（确认/取消）时投喂，
 * 而实测中用户常常「录一句、看一眼、接着录下一句」，从不点确认，
 * 于是聚合器永远收不到样本——刚建的基准功能等于没用。
 *
 * 现在的语义：识别完成立即记录一条，合成/播放发生后回填同一条。
 */
class BenchmarkAmendTest {

    private fun recognitionOnly(recognitionMs: Long = 150L) =
        BenchmarkAggregator.Sample(
            captureMs = 20L,
            recognitionMs = recognitionMs,
            synthesisMs = 0L,
            playbackMs = 0L,
            totalMs = 170L,
            audioMs = 2000L,
        )

    @Test
    fun `recognition only sample counts toward sample count`() {
        val aggregator = BenchmarkAggregator();
        aggregator.add(recognitionOnly());

        val snapshot = aggregator.snapshot();
        assertEquals(1, snapshot.sampleCount);
        assertEquals(150L, snapshot.recognition!!.medianMs);
    }

    @Test
    fun `synthesis and playback are null when never reached`() {
        val aggregator = BenchmarkAggregator();
        aggregator.add(recognitionOnly());

        val snapshot = aggregator.snapshot();
        // 未走到合成/播放：应为 null，而不是误报成 0 毫秒
        assertNull(snapshot.synthesis);
        assertNull(snapshot.playback);
        assertTrue(snapshot.toJson().contains(
            Char(34).toString() + "synthesis" + Char(34).toString() + ":null",
        ));
    }

    @Test
    fun `amendLast fills in synthesis and playback`() {
        val aggregator = BenchmarkAggregator();
        aggregator.add(recognitionOnly());
        aggregator.amendLast(synthesisMs = 300L, playbackMs = 400L, totalMs = 870L);

        val snapshot = aggregator.snapshot();
        // 仍然只有一条样本，不能被算成两次
        assertEquals(1, snapshot.sampleCount);
        assertNotNull(snapshot.synthesis);
        assertEquals(300L, snapshot.synthesis!!.medianMs);
        assertEquals(400L, snapshot.playback!!.medianMs);
        assertEquals(870L, snapshot.total!!.medianMs);
    }

    @Test
    fun `amendLast on empty aggregator is a no-op`() {
        val aggregator = BenchmarkAggregator();
        aggregator.amendLast(synthesisMs = 300L, playbackMs = 400L, totalMs = 870L);
        assertEquals(0, aggregator.size);
    }

    @Test
    fun `zero values do not overwrite existing measurements`() {
        val aggregator = BenchmarkAggregator();
        aggregator.add(
            BenchmarkAggregator.Sample(
                captureMs = 20L,
                recognitionMs = 150L,
                synthesisMs = 300L,
                playbackMs = 400L,
                totalMs = 870L,
                audioMs = 2000L,
            ),
        );
        aggregator.amendLast(synthesisMs = 0L, playbackMs = 0L, totalMs = 0L);

        val snapshot = aggregator.snapshot();
        assertEquals(300L, snapshot.synthesis!!.medianMs);
        assertEquals(400L, snapshot.playback!!.medianMs);
    }

    @Test
    fun `mixed sessions only count reached stages`() {
        val aggregator = BenchmarkAggregator();
        // 两条只到识别，一条走完全程
        aggregator.add(recognitionOnly(100L));
        aggregator.add(recognitionOnly(200L));
        aggregator.add(
            BenchmarkAggregator.Sample(
                captureMs = 20L,
                recognitionMs = 300L,
                synthesisMs = 500L,
                playbackMs = 600L,
                totalMs = 1420L,
                audioMs = 2000L,
            ),
        );

        val snapshot = aggregator.snapshot();
        assertEquals(3, snapshot.sampleCount);
        // 合成阶段只有 1 条真实样本，不能被两条 0 拉低
        assertEquals(1, snapshot.synthesis!!.count);
        assertEquals(500L, snapshot.synthesis!!.medianMs);
    }
}
