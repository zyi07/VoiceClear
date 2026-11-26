package com.voiceclear.app.engine

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioCaptureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantMicrophonePermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private open class FakeRecorder : CaptureRecorder {
        override val state: Int = android.media.AudioRecord.STATE_INITIALIZED
        override var recordingState: Int = android.media.AudioRecord.RECORDSTATE_STOPPED
        val stopCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        val released = CountDownLatch(1)

        override fun startRecording() {
            recordingState = android.media.AudioRecord.RECORDSTATE_RECORDING
        }

        override fun stop() {
            recordingState = android.media.AudioRecord.RECORDSTATE_STOPPED
            stopCount.incrementAndGet()
        }

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int = 0

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private fun capture(
        recorder: FakeRecorder,
        clock: () -> Long = System::currentTimeMillis,
        joinTimeoutMs: Long = 1_000L,
    ) = AudioCapture(
        context = context,
        recorderFactory = CaptureRecorderFactory { _, _, _, _, _ -> recorder },
        minBufferSizeProvider = { 2 },
        clock = clock,
        joinTimeoutMs = joinTimeoutMs,
    )

    @Test
    fun `read error cleans up and never returns truncated success`() {
        val recorder = object : FakeRecorder() {
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                android.media.AudioRecord.ERROR_DEAD_OBJECT
        }
        val capture = capture(recorder)
        assertEquals(null, capture.start("failed-session"))
        assertTrue("读线程必须完成清理", recorder.released.await(1, TimeUnit.SECONDS))
        assertFalse(capture.isRecording())

        val result = capture.stop()
        assertTrue(result is AudioCapture.CaptureResult.Failure)
        assertEquals(
            AudioCapture.ERR_READ_FAILED,
            (result as AudioCapture.CaptureResult.Failure).code,
        )
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun `automatic stop releases recorder and later returns captured session id`() {
        var now = 0L
        val recorder = object : FakeRecorder() {
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                now = AudioCapture.MAX_DURATION_MS
                buffer[offset] = 7
                return 1
            }
        }
        val capture = capture(recorder, clock = { now })
        assertEquals(null, capture.start("auto-session"))
        assertTrue(recorder.released.await(1, TimeUnit.SECONDS))
        assertFalse(capture.isRecording())

        val result = capture.stop()
        assertTrue(result is AudioCapture.CaptureResult.Success)
        val segment = (result as AudioCapture.CaptureResult.Success).segment
        assertEquals("auto-session", segment.sessionId)
        assertEquals(1, segment.samples.size)
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun `explicit stop unblocks read and cleanup is idempotent`() {
        val readStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val recorder = object : FakeRecorder() {
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                readStarted.countDown()
                stopped.await(1, TimeUnit.SECONDS)
                return android.media.AudioRecord.ERROR_INVALID_OPERATION
            }

            override fun stop() {
                super.stop()
                stopped.countDown()
            }
        }
        val capture = capture(recorder)
        assertEquals(null, capture.start("explicit-session"))
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))

        val result = capture.stop()
        assertTrue(result is AudioCapture.CaptureResult.Success)
        assertEquals(
            "explicit-session",
            (result as AudioCapture.CaptureResult.Success).segment.sessionId,
        )
        assertEquals(1, recorder.releaseCount.get())
        capture.cancel()
        assertEquals(1, recorder.releaseCount.get())
    }

    @Test
    fun `join timeout is failure and does not copy buffer while reader lives`() {
        val readStarted = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        val readReturned = CountDownLatch(1)
        val recorder = object : FakeRecorder() {
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                readStarted.countDown()
                allowReturn.await(1, TimeUnit.SECONDS)
                readReturned.countDown()
                return android.media.AudioRecord.ERROR_INVALID_OPERATION
            }
        }
        val capture = capture(recorder, joinTimeoutMs = 20L)
        assertEquals(null, capture.start("timeout-session"))
        assertTrue(readStarted.await(1, TimeUnit.SECONDS))

        val result = capture.stop()
        assertTrue(result is AudioCapture.CaptureResult.Failure)
        assertEquals(
            AudioCapture.ERR_JOIN_TIMEOUT,
            (result as AudioCapture.CaptureResult.Failure).code,
        )
        assertEquals(1, recorder.releaseCount.get())
        allowReturn.countDown()
        assertTrue(readReturned.await(1, TimeUnit.SECONDS))
    }
}
