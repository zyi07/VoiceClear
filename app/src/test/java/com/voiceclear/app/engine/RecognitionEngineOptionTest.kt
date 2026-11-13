package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionEngineOptionTest {

    @Test
    fun `fake engine is not evaluable`() {
        assertFalse(RecognitionEngineOption.FAKE.isEvaluable)
    }

    @Test
    fun `real engines are evaluable`() {
        assertTrue(RecognitionEngineOption.SYSTEM.isEvaluable)
        assertTrue(RecognitionEngineOption.ONNX_OFFLINE.isEvaluable)
        assertTrue(RecognitionEngineOption.PERSONALIZED.isEvaluable)
    }

    @Test
    fun `only offline model is first release candidate`() {
        assertTrue(RecognitionEngineOption.ONNX_OFFLINE.isFirstReleaseCandidate)
        assertFalse(RecognitionEngineOption.SYSTEM.isFirstReleaseCandidate)
        assertFalse(RecognitionEngineOption.FAKE.isFirstReleaseCandidate)
    }

    @Test
    fun `system engine is marked as owning microphone`() {
        assertTrue(RecognitionEngineOption.SYSTEM.capabilities.ownsMicrophone)
        assertFalse(RecognitionEngineOption.ONNX_OFFLINE.capabilities.ownsMicrophone)
    }

    @Test
    fun `offline candidate is marked offline`() {
        assertTrue(RecognitionEngineOption.ONNX_OFFLINE.capabilities.offline)
        assertFalse(RecognitionEngineOption.SYSTEM.capabilities.offline)
    }

    @Test
    fun `fake engine outcome flags missing evaluable engine`() {
        val metrics = com.voiceclear.app.session.SessionMetrics(sessionId = "s1")
        assertEquals(
            "evaluable_engine_missing",
            RecognitionEngineOption.FAKE.outcomeTag(metrics),
        )
    }

    @Test
    fun `real engine keeps original outcome`() {
        val metrics = com.voiceclear.app.session.SessionMetrics(
            sessionId = "s1",
            outcome = com.voiceclear.app.session.SessionMetrics.OUTCOME_SUCCESS,
        )
        assertEquals(
            com.voiceclear.app.session.SessionMetrics.OUTCOME_SUCCESS,
            RecognitionEngineOption.ONNX_OFFLINE.outcomeTag(metrics),
        )
    }
}
