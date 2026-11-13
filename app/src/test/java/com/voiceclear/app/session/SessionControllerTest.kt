package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {

    private fun controller() = SessionController(
        now = { 0L },
        idGenerator = { "session-1" },
    )

    @Test
    fun `starts in idle state`() {
        val controller = controller()
        assertEquals(SessionState.IDLE, controller.state.value)
        assertNull(controller.sessionId)
    }

    @Test
    fun `start session moves to recording`() {
        val controller = controller()
        assertTrue(controller.startSession())
        assertEquals(SessionState.RECORDING, controller.state.value)
        assertEquals("session-1", controller.sessionId)
    }

    @Test
    fun `cannot start a new session while recording`() {
        val controller = controller()
        controller.startSession()
        assertFalse(controller.startSession())
    }

    @Test
    fun `cannot start a new session while processing`() {
        val controller = controller()
        controller.startSession()
        controller.stopCapture()
        assertEquals(SessionState.PROCESSING_RECOGNIZING, controller.state.value)
        assertFalse(controller.startSession())
    }

    @Test
    fun `full happy path reaches completed`() {
        val controller = controller()
        controller.startSession()
        assertTrue(controller.stopCapture())
        assertTrue(controller.onRecognitionReady())
        assertTrue(controller.onConfirmed())
        assertTrue(controller.onSynthesisReady())
        assertTrue(controller.onPlaybackFinished())
        assertEquals(SessionState.COMPLETED, controller.state.value)
    }

    @Test
    fun `invalid transition is rejected`() {
        val controller = controller()
        controller.startSession()
        assertFalse(controller.onSynthesisReady())
        assertEquals(SessionState.RECORDING, controller.state.value)
    }

    @Test
    fun `cancel is allowed from recording`() {
        val controller = controller()
        controller.startSession()
        assertTrue(controller.cancel())
        assertEquals(SessionState.CANCELLED, controller.state.value)
    }

    @Test
    fun `failure records stage and moves to error`() {
        val controller = controller()
        controller.startSession()
        assertTrue(controller.fail(ProcessingStage.CAPTURE, "capture_failed"))
        assertEquals(SessionState.ERROR, controller.state.value)
        val event = controller.events.value
        assertTrue(event is SessionEvent.Error)
        assertEquals(ProcessingStage.CAPTURE, (event as SessionEvent.Error).stage)
        assertEquals("capture_failed", event.code)
    }

    @Test
    fun `stale session results are rejected`() {
        val controller = controller()
        controller.startSession()
        assertTrue(controller.isCurrent("session-1"))
        assertFalse(controller.isCurrent("session-2"))
    }

    @Test
    fun `new session can start after completion`() {
        val controller = controller()
        controller.startSession()
        controller.stopCapture()
        controller.onRecognitionReady()
        controller.onConfirmed()
        controller.onSynthesisReady()
        controller.onPlaybackFinished()
        assertTrue(controller.startSession())
        assertEquals(SessionState.RECORDING, controller.state.value)
    }

    @Test
    fun `finish clears the session id`() {
        val controller = controller()
        controller.startSession()
        controller.finish()
        assertNull(controller.sessionId)
        assertEquals(SessionState.IDLE, controller.state.value)
    }
}
