package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码体系测试。
 */
class ErrorCodesTest {

    @Test
    fun `all codes use domain prefix format`() {
        val codes = listOf(
            ErrorCodes.CAPTURE_PERMISSION_DENIED,
            ErrorCodes.CAPTURE_INVALID_BUFFER,
            ErrorCodes.CAPTURE_ILLEGAL_ARGUMENT,
            ErrorCodes.CAPTURE_NOT_INITIALIZED,
            ErrorCodes.CAPTURE_NOT_RECORDING,
            ErrorCodes.CAPTURE_ILLEGAL_STATE,
            ErrorCodes.CAPTURE_EMPTY,
            ErrorCodes.CAPTURE_TOO_SHORT,
            ErrorCodes.ASR_MODEL_MISSING,
            ErrorCodes.ASR_MODEL_LOAD_FAILED,
            ErrorCodes.ASR_DECODE_FAILED,
            ErrorCodes.ASR_EMPTY_AUDIO,
            ErrorCodes.ASR_SAMPLE_RATE_MISMATCH,
            ErrorCodes.ASR_MODEL_NOT_AVAILABLE,
            ErrorCodes.ASR_MODEL_INVALID,
            ErrorCodes.TTS_NOT_READY,
            ErrorCodes.TTS_LANGUAGE_UNAVAILABLE,
            ErrorCodes.TTS_SPEAK_FAILED,
            ErrorCodes.TTS_UTTERANCE_ERROR,
            ErrorCodes.TTS_SYNTHESIS_TIMEOUT,
            ErrorCodes.TTS_INIT_TIMEOUT,
            ErrorCodes.FOCUS_DENIED,
            ErrorCodes.MODEL_DOWNLOAD_FAILED,
            ErrorCodes.MODEL_SOURCE_UNREACHABLE,
            ErrorCodes.MODEL_CHECKSUM_MISMATCH,
            ErrorCodes.MODEL_NO_NETWORK,
            ErrorCodes.SESSION_STALE,
            ErrorCodes.SESSION_CANCELLED,
        )

        for (code in codes) {
            // 全部小写、下划线分隔、以域开头
            assertTrue("错误码应全小写: " + code, code == code.lowercase())
            assertTrue("错误码不应含空格: " + code, !code.contains(" "))
            assertTrue("错误码应有域前缀: " + code, code.contains("_"))
        }
    }

    @Test
    fun `codes are unique`() {
        val codes = listOf(
            ErrorCodes.CAPTURE_PERMISSION_DENIED,
            ErrorCodes.ASR_MODEL_MISSING,
            ErrorCodes.ASR_DECODE_FAILED,
            ErrorCodes.TTS_NOT_READY,
            ErrorCodes.FOCUS_DENIED,
            ErrorCodes.MODEL_DOWNLOAD_FAILED,
            ErrorCodes.SESSION_STALE,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `domain extracts the stage prefix`() {
        assertEquals("capture", ErrorCodes.domain(ErrorCodes.CAPTURE_PERMISSION_DENIED))
        assertEquals("asr", ErrorCodes.domain(ErrorCodes.ASR_DECODE_FAILED))
        assertEquals("tts", ErrorCodes.domain(ErrorCodes.TTS_NOT_READY))
        assertEquals("model", ErrorCodes.domain(ErrorCodes.MODEL_NO_NETWORK))
        assertEquals("session", ErrorCodes.domain(ErrorCodes.SESSION_STALE))
    }

    @Test
    fun `unknown code falls back safely`() {
        // 有下划线时取前缀段
        assertEquals("weird", ErrorCodes.domain("weird_code"))
        // 没有下划线时兜底
        assertEquals("unknown", ErrorCodes.domain("nounderscore"))
        assertEquals("未知错误", ErrorCodes.describe("totally_unknown"))
    }

    @Test
    fun `every code has a human readable description`() {
        val codes = listOf(
            ErrorCodes.CAPTURE_PERMISSION_DENIED,
            ErrorCodes.ASR_MODEL_MISSING,
            ErrorCodes.TTS_NOT_READY,
            ErrorCodes.FOCUS_DENIED,
            ErrorCodes.MODEL_CHECKSUM_MISMATCH,
            ErrorCodes.SESSION_CANCELLED,
        )
        for (code in codes) {
            val description = ErrorCodes.describe(code)
            assertFalse("缺少说明: " + code, description.isBlank())
            assertFalse("不应是未知: " + code, description == "未知错误")
        }
    }

    @Test
    fun `retryable classification is sensible`() {
        // 网络类与超时类可重试
        assertTrue(ErrorCodes.isRetryable(ErrorCodes.MODEL_DOWNLOAD_FAILED))
        assertTrue(ErrorCodes.isRetryable(ErrorCodes.MODEL_NO_NETWORK))
        assertTrue(ErrorCodes.isRetryable(ErrorCodes.TTS_SYNTHESIS_TIMEOUT))
        // 权限与配置类不可重试
        assertFalse(ErrorCodes.isRetryable(ErrorCodes.CAPTURE_PERMISSION_DENIED))
        assertFalse(ErrorCodes.isRetryable(ErrorCodes.ASR_MODEL_MISSING))
        assertFalse(ErrorCodes.isRetryable(ErrorCodes.CAPTURE_ILLEGAL_ARGUMENT))
    }
}
