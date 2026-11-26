package com.voiceclear.app

import com.voiceclear.app.engine.AudioQuality
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionOperationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证生产停止门闩在正常、提前退出和异常后的释放语义。 */
class StopReleaseTest {

    private val token = SessionController.Token("session", 1L)

    private fun runStopFlow(gate: SessionOperationGate, earlyExit: Boolean, throwInside: Boolean): Boolean {
        if (!gate.tryBegin(token)) return false
        try {
            if (earlyExit) return true
            if (throwInside) error("模拟识别抛异常")
            return true
        } finally {
            gate.end(token)
        }
    }

    @Test
    fun `flag is released after normal completion`() {
        val gate = SessionOperationGate()
        assertTrue(runStopFlow(gate, earlyExit = false, throwInside = false))
        assertTrue(runStopFlow(gate, earlyExit = false, throwInside = false))
    }

    @Test
    fun `flag is released after early exit`() {
        val gate = SessionOperationGate()
        assertTrue(runStopFlow(gate, earlyExit = true, throwInside = false))
        assertTrue(runStopFlow(gate, earlyExit = true, throwInside = false))
    }

    @Test
    fun `flag is released after exception`() {
        val gate = SessionOperationGate()
        try {
            runStopFlow(gate, earlyExit = false, throwInside = true)
        } catch (expected: IllegalStateException) {
            // 预期抛出；finally 仍必须释放生产门闩。
        }
        assertTrue(runStopFlow(gate, earlyExit = false, throwInside = false))
    }

    @Test
    fun `gate rejects reentry while running`() {
        val gate = SessionOperationGate()
        assertTrue(gate.tryBegin(token))
        assertFalse(gate.tryBegin(token))
    }

    @Test
    fun `audio quality verdicts cover measured cases`() {
        assertEquals(AudioQuality.Verdict.SILENT, AudioQuality.analyze(FloatArray(8000)).verdict)
        assertEquals(
            AudioQuality.Verdict.TOO_LOW,
            AudioQuality.analyze(FloatArray(8000) { 0.006f * kotlin.math.sin(it.toFloat()) }).verdict,
        )
    }
}
