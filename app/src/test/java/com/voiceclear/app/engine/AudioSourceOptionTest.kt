package com.voiceclear.app.engine

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 麦克风音源选项测试。
 */
class AudioSourceOptionTest {

    @Test
    fun `each option maps to a distinct android source`() {
        val constants = AudioSourceOption.ALL.map { it.sourceConstant }
        assertEquals("音源常量不应重复", constants.size, constants.toSet().size)
        assertEquals(
            MediaRecorder.AudioSource.UNPROCESSED,
            AudioSourceOption.UNPROCESSED.sourceConstant,
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSourceOption.VOICE_RECOGNITION.sourceConstant,
        )
    }

    @Test
    fun `ids are unique and stable`() {
        val ids = AudioSourceOption.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // id 用于持久化，不能随意改动，否则用户设置会丢失
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `fromId resolves known ids`() {
        for (option in AudioSourceOption.ALL) {
            assertEquals(option, AudioSourceOption.fromId(option.id))
        }
    }

    @Test
    fun `fromId falls back for unknown or null`() {
        assertEquals(AudioSourceOption.DEFAULT, AudioSourceOption.fromId("nonexistent"))
        assertEquals(AudioSourceOption.DEFAULT, AudioSourceOption.fromId(null))
    }

    @Test
    fun `default is unprocessed`() {
        // 原始声音是默认选择：AGC 会削弱发音不清人群的微弱辅音
        assertEquals(AudioSourceOption.UNPROCESSED, AudioSourceOption.DEFAULT)
    }

    @Test
    fun `every option has user facing copy`() {
        for (option in AudioSourceOption.ALL) {
            assertTrue("缺少显示名: " + option.id, option.displayName.isNotBlank())
            assertTrue("缺少说明: " + option.id, option.description.isNotBlank())
        }
    }

    @Test
    fun `all options are listed`() {
        assertEquals(3, AudioSourceOption.ALL.size)
        assertNotEquals(
            AudioSourceOption.UNPROCESSED,
            AudioSourceOption.VOICE_RECOGNITION,
        )
    }
}
