package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存可行性判定测试。
 *
 * 对应审计报告 4.1.2「低内存设备处理逻辑」。
 * 这些边界值是刻意钉住的：判定规则一旦改动，必须让本测试失败，
 * 否则真机上「闪退」与「可读提示」的分界会悄悄移动。
 */
class MemoryGuardTest {

    @Test
    fun `enough memory is ok`() {
        val required = 400L
        assertEquals(
            MemoryGuard.Verdict.OK,
            MemoryGuard.decide(availableMb = 900L, requiredMb = required, lowMemory = false),
        );
    }

    @Test
    fun `just at required is tight not ok`() {
        // 刚好等于所需：没有安全垫，判紧而不是判充足
        val required = 400L
        assertEquals(
            MemoryGuard.Verdict.TIGHT,
            MemoryGuard.decide(availableMb = required, requiredMb = required, lowMemory = false),
        );
    }

    @Test
    fun `below required is insufficient`() {
        assertEquals(
            MemoryGuard.Verdict.INSUFFICIENT,
            MemoryGuard.decide(availableMb = 399L, requiredMb = 400L, lowMemory = false),
        );
    }

    @Test
    fun `low memory flag forces tight even with plenty of ram`() {
        // 系统已报低内存说明整体吃紧，此时即使数字看起来够，也不给「充足」结论
        assertEquals(
            MemoryGuard.Verdict.TIGHT,
            MemoryGuard.decide(availableMb = 5000L, requiredMb = 400L, lowMemory = true),
        );
    }

    @Test
    fun `zero required is still judged by headroom`() {
        assertEquals(
            MemoryGuard.Verdict.TIGHT,
            MemoryGuard.decide(availableMb = 0L, requiredMb = 0L, lowMemory = false),
        );
    }

    @Test
    fun `overhead factor is in a defensible range`() {
        // 1.3-1.8 是 int8 小模型的经验区间，越界说明估算口径被改坏了
        assertTrue(
            "倍数应大于 1（否则等于假设模型零开销）",
            MemoryGuard.NATIVE_OVERHEAD_FACTOR > 1.0,
        );
        assertTrue(
            "倍数不应过于保守",
            MemoryGuard.NATIVE_OVERHEAD_FACTOR <= 2.0,
        );
    }

    @Test
    fun `headroom is positive`() {
        assertTrue(MemoryGuard.MIN_HEADROOM_MB > 0L);
    }

    @Test
    fun `capture buffer for 30 seconds is about one megabyte per copy`() {
        // 30s * 16000 Hz * 2 字节 = 960 KB，两副本约 1.9 MB
        val bytes = MemoryGuard.captureBufferBytes(
            maxDurationMs = 30_000L,
            sampleRate = 16_000,
            bytesPerSample = 2,
        );
        assertEquals(1_920_000L, bytes);
    }

    @Test
    fun `capture buffer scales with duration`() {
        val short = MemoryGuard.captureBufferBytes(maxDurationMs = 5_000L);
        val long = MemoryGuard.captureBufferBytes(maxDurationMs = 60_000L);
        assertTrue(long > short);
    }
}
