package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsCollectorTest {

    @Test
    fun `records metrics in insertion order`() {
        val collector = MetricsCollector(maxEntries = 10)
        collector.record(SessionMetrics(sessionId = "a", totalMs = 10))
        collector.record(SessionMetrics(sessionId = "b", totalMs = 20))
        val snapshot = collector.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("a", snapshot[0].sessionId)
        assertEquals("b", snapshot[1].sessionId)
    }

    @Test
    fun `evicts oldest entries beyond capacity`() {
        val collector = MetricsCollector(maxEntries = 2)
        collector.record(SessionMetrics(sessionId = "a"))
        collector.record(SessionMetrics(sessionId = "b"))
        collector.record(SessionMetrics(sessionId = "c"))
        assertEquals(2, collector.snapshot().size)
        assertNull("最旧的记录应被淘汰", collector.get("a"))
        assertTrue(collector.get("c") != null)
    }

    @Test
    fun `derives outcome from confidence and failure state`() {
        val collector = MetricsCollector()
        assertEquals(
            SessionMetrics.OUTCOME_SUCCESS,
            collector.deriveOutcome(confidence = 0.9f, failed = false, cancelled = false),
        )
        assertEquals(
            SessionMetrics.OUTCOME_LOW_CONFIDENCE,
            collector.deriveOutcome(confidence = 0.4f, failed = false, cancelled = false),
        )
        assertEquals(
            SessionMetrics.OUTCOME_ERROR,
            collector.deriveOutcome(confidence = 0.9f, failed = true, cancelled = false),
        )
        assertEquals(
            SessionMetrics.OUTCOME_CANCELLED,
            collector.deriveOutcome(confidence = 0.9f, failed = false, cancelled = true),
        )
    }

    @Test
    fun `unknown confidence is not treated as low confidence`() {
        val collector = MetricsCollector()
        assertEquals(
            SessionMetrics.OUTCOME_SUCCESS,
            collector.deriveOutcome(confidence = null, failed = false, cancelled = false),
        )
    }

    @Test
    fun `clear removes all metrics`() {
        val collector = MetricsCollector()
        collector.record(SessionMetrics(sessionId = "a"))
        collector.clear()
        assertTrue(collector.snapshot().isEmpty())
        assertNull(collector.get("a"))
    }
}
