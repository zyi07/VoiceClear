package com.voiceclear.app.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackTest {

    private val segment = AudioSegment(
        sessionId = "playback-session",
        sampleRate = 16_000,
        channelCount = 1,
        encoding = AudioEncoding.PCM_16BIT,
        durationMs = 1,
        samples = byteArrayOf(1, 2, 3, 4, 5, 6),
    )

    private open class FakeTrack : PlaybackTrack {
        override val state: Int = android.media.AudioTrack.STATE_INITIALIZED
        val playCount = AtomicInteger(0)
        val pauseCount = AtomicInteger(0)
        val flushCount = AtomicInteger(0)
        val stopCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        override fun play() { playCount.incrementAndGet() }
        override fun write(buffer: ByteArray, offset: Int, size: Int): Int = size
        override fun pause() { pauseCount.incrementAndGet() }
        override fun flush() { flushCount.incrementAndGet() }
        override fun stop() { stopCount.incrementAndGet() }
        override fun release() { releaseCount.incrementAndGet() }
    }

    @Test
    fun `partial or failed write is not success and resources close once`() {
        val track = object : FakeTrack() {
            private var calls = 0
            override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
                calls += 1
                return if (calls == 1) 2 else 0
            }
        }
        val playback = AudioPlayback(PlaybackTrackFactory { track })

        assertFalse(playback.play(segment))
        assertEquals(1, track.stopCount.get())
        assertEquals(1, track.releaseCount.get())
        playback.stop()
        assertEquals(1, track.releaseCount.get())
    }

    @Test
    fun `only complete write returns true`() {
        val track = object : FakeTrack() {
            override fun write(buffer: ByteArray, offset: Int, size: Int): Int = minOf(2, size)
        }
        assertTrue(AudioPlayback(PlaybackTrackFactory { track }).play(segment))
        // 正常完成不 flush；MODE_STREAM stop 会播完最后写入的缓冲区。
        assertEquals(0, track.flushCount.get())
        assertEquals(1, track.stopCount.get())
        assertEquals(1, track.releaseCount.get())
    }

    @Test
    fun `stop during track creation prevents playback registration`() {
        val createStarted = CountDownLatch(1)
        val allowCreate = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val track = FakeTrack()
        val playback = AudioPlayback(
            PlaybackTrackFactory {
                createStarted.countDown()
                allowCreate.await(2, TimeUnit.SECONDS)
                track
            },
        )
        val thread = Thread { result.set(playback.play(segment)) }
        thread.start()

        assertTrue(createStarted.await(1, TimeUnit.SECONDS))
        playback.stop()
        allowCreate.countDown()
        thread.join(1_000)

        assertFalse(thread.isAlive)
        assertFalse(result.get())
        assertEquals("创建期间已取消，不得再 play", 0, track.playCount.get())
        assertEquals(1, track.releaseCount.get())
    }

    @Test
    fun `stop interrupts blocking write and result is failure`() {
        val writeStarted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val track = object : FakeTrack() {
            override fun write(buffer: ByteArray, offset: Int, size: Int): Int {
                writeStarted.countDown()
                released.await(2, TimeUnit.SECONDS)
                return android.media.AudioTrack.ERROR_DEAD_OBJECT
            }

            override fun release() {
                super.release()
                released.countDown()
            }
        }
        val playback = AudioPlayback(PlaybackTrackFactory { track })
        val thread = Thread { result.set(playback.play(segment)) }
        thread.start()

        assertTrue("播放必须进入阻塞 write", writeStarted.await(1, TimeUnit.SECONDS))
        playback.stop()
        thread.join(1_000)

        assertFalse("stop 后播放线程仍存活", thread.isAlive)
        assertFalse(result.get())
        assertEquals(1, track.stopCount.get())
        assertEquals(1, track.flushCount.get())
        assertEquals(1, track.releaseCount.get())
    }
}
