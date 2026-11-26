package com.voiceclear.app

import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionOperationGate
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接测试生产停止门闩，不再用同构 AtomicBoolean 模拟实现。 */
class StopIdempotencyTest {

    private val sessionA = SessionController.Token("session-a", 1L)
    private val sessionB = SessionController.Token("session-b", 2L)

    @Test
    fun `second concurrent stop in same session is rejected`() {
        val gate = SessionOperationGate()
        assertTrue(gate.tryBegin(sessionA))
        assertFalse(gate.tryBegin(sessionA))
        assertFalse(gate.tryBegin(sessionA))
        gate.end(sessionA)
        assertTrue(gate.tryBegin(sessionA))
    }

    @Test
    fun `new session is not blocked by previous in-flight stop`() {
        val gate = SessionOperationGate()
        assertTrue(gate.tryBegin(sessionA))
        assertTrue("A 识别中进行 B 时，B 必须能停止", gate.tryBegin(sessionB))
        assertFalse(gate.tryBegin(sessionB))
    }

    @Test
    fun `old session finally cannot release new session gate`() {
        val gate = SessionOperationGate()
        gate.tryBegin(sessionA)
        gate.tryBegin(sessionB)

        gate.end(sessionA)
        assertFalse("A 的 finally 不得释放 B", gate.tryBegin(sessionB))
        gate.end(sessionB)
        assertTrue(gate.tryBegin(sessionB))
    }

    @Test
    fun `only one of many concurrent attempts wins`() {
        val gate = SessionOperationGate()
        val winners = AtomicInteger(0)
        val threads = (1..16).map {
            Thread {
                if (gate.tryBegin(sessionA)) winners.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("并发下只应有一个赢家", winners.get() == 1)
    }
}
