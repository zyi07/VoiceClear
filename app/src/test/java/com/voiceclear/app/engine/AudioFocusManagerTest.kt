package com.voiceclear.app.engine

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusManagerTest {

    private class FakeBackend : AudioFocusBackend {
        var listener: AudioManager.OnAudioFocusChangeListener? = null
        var requestCount = 0
        var abandonCount = 0
        var grant = true

        override fun request(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            requestCount += 1
            this.listener = listener
            return grant
        }

        override fun abandon() {
            abandonCount += 1
        }

        fun dispatch(change: Int) {
            listener?.onAudioFocusChange(change)
        }
    }

    @Test
    fun `denied request does not report focus`() {
        val backend = FakeBackend().apply { grant = false }
        val manager = AudioFocusManager(backend)
        assertFalse(manager.requestFocus())
        assertFalse(manager.hasFocus())
    }

    @Test
    fun `permanent loss clears state and a later request asks system again`() {
        val backend = FakeBackend()
        val manager = AudioFocusManager(backend)
        var lostCount = 0
        manager.onFocusLost = { lostCount += 1 }
        assertTrue(manager.requestFocus())
        assertTrue(manager.requestFocus())
        assertEquals(1, backend.requestCount)

        backend.dispatch(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(manager.hasFocus())
        assertEquals(1, lostCount)
        assertTrue(manager.requestFocus())
        assertEquals(2, backend.requestCount)
        assertTrue(manager.hasFocus())
    }

    @Test
    fun `transient loss is not focus until gain and abandon always clears state`() {
        val backend = FakeBackend()
        val manager = AudioFocusManager(backend)
        assertTrue(manager.requestFocus())
        backend.dispatch(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(manager.hasFocus())

        backend.dispatch(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(manager.hasFocus())
        manager.abandonFocus()
        assertFalse(manager.hasFocus())
        assertEquals(1, backend.abandonCount)
    }
}
