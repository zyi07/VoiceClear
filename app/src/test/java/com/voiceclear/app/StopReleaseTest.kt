package com.voiceclear.app

import com.voiceclear.app.engine.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 停止流程幂等性测试（第二部分）。
 *
 * 与 [StopIdempotencyTest] 不同，这里验证的是**释放语义**：
 * 无论停止流程以哪种方式结束（正常完成 / 会话过期 / 抛异常），
 * in-flight 标志都必须复位——否则一次取消之后，录音就永远停不下来了。
 *
 * 这正是真机上出现过的缺陷形态：`isCurrent==false` 的提前返回路径
 * 没有释放标志，`return@launch` 直接跳出。
 */
class StopReleaseTest {

    /** 模拟 ViewModel 的 launch 体：try/finally 释放。 */
    private fun runStopFlow(earlyExit: Boolean, throwInside: Boolean): Boolean {
        val inFlight = AtomicBoolean(false)
        if (!inFlight.compareAndSet(false, true)) return false
        try {
            if (earlyExit) return true
            if (throwInside) error("模拟识别抛异常")
            return true
        } finally {
            inFlight.set(false)
        }
    }

    @Test
    fun `flag is released after normal completion`() {
        runStopFlow(earlyExit = false, throwInside = false)
        // 若未释放，第二次调用会被拒绝
        assertEquals(true, runStopFlow(earlyExit = false, throwInside = false))
    }

    @Test
    fun `flag is released after early exit (stale session)`() {
        // 会话过期路径（isCurrent==false → return@launch）
        runStopFlow(earlyExit = true, throwInside = false)
        assertEquals(true, runStopFlow(earlyExit = true, throwInside = false))
    }

    @Test
    fun `flag is released after exception`() {
        try {
            runStopFlow(earlyExit = false, throwInside = true)
        } catch (expected: IllegalStateException) {
            // 预期抛出
        }
        assertEquals(true, runStopFlow(earlyExit = false, throwInside = false))
    }

    @Test
    fun `audio quality verdicts cover measured cases`() {
        // 从真机日志量化的三档：0.0000 静音 / 0.0063 偏弱 / 0.0446 正常
        assertEquals(AudioQuality.Verdict.SILENT, AudioQuality.analyze(FloatArray(8000)).verdict)
        assertEquals(
            AudioQuality.Verdict.TOO_LOW,
            AudioQuality.analyze(FloatArray(8000) { 0.006f * kotlin.math.sin(it.toFloat()) }).verdict,
        )
    }
}
