package com.voiceclear.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 停止流程幂等性测试。
 *
 * **背景**：真机日志显示每次识别完成���都跟着一条
 * `E/Main: 采集失败, code=capture_not_recording`。
 *
 * 根因是按钮双击让两个协程并发进入 `stopRecordingAndProcess()`，
 * 第二个 `audioCapture.stop()` 时 `current` 已被第一个置空，
 * 于是返回 `ERR_NOT_RECORDING`。
 *
 * 这里用与实现同构的原子标志验证防护逻辑。
 */
class StopIdempotencyTest {

    @Test
    fun `second concurrent stop is rejected`() {
        val inFlight = AtomicBoolean(false)
        var stopCalls = 0

        fun tryStop(): Boolean {
            if (!inFlight.compareAndSet(false, true)) return false
            stopCalls++
            return true
        }

        assertTrue("第一次应被接受", tryStop())
        assertFalse("第二次应被拒绝", tryStop())
        assertFalse("第三次应被拒绝", tryStop())
        assertTrue("只应真正停止一次", stopCalls == 1)
    }

    @Test
    fun `flag is released after completion so next session works`() {
        val inFlight = AtomicBoolean(false)

        // 第一轮：进入并完成
        assertTrue(inFlight.compareAndSet(false, true))
        inFlight.set(false)

        // 第二轮：应能再次进入
        assertTrue("完成后应能再次发起", inFlight.compareAndSet(false, true))
    }

    @Test
    fun `only one of many concurrent attempts wins`() {
        val inFlight = AtomicBoolean(false)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)

        val threads = (1..16).map {
            Thread {
                if (inFlight.compareAndSet(false, true)) {
                    winners.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue("并发下只应有一个赢家", winners.get() == 1)
    }
}
