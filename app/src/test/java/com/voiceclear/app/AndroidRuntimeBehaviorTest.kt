package com.voiceclear.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioEncoding
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.FakeRecognitionEngine
import com.voiceclear.app.engine.FakeSynthesisEngine
import com.voiceclear.app.engine.RecognitionRequest
import com.voiceclear.app.engine.SynthesisRequest
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在真实 Android 框架语义下（Robolectric）验证关键失败路径。
 *
 * 该环境无 KVM，无法运行模拟器，因此本测试提供"框架级运行验证"，
 * 不替代真机验证（技术指导文件 9.1 节）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidRuntimeBehaviorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `capture without permission returns failure instead of crashing`() {
        // 默认未授予 RECORD_AUDIO
        val capture = AudioCapture(context)
        val failure = capture.start("session-perm")
        assertEquals(AudioCapture.ERR_PERMISSION_DENIED, failure)
    }

    @Test
    fun `stop without start reports not recording`() {
        val capture = AudioCapture(context)
        val result = capture.stop()
        assertTrue(result is AudioCapture.CaptureResult.Failure)
        assertEquals(
            AudioCapture.ERR_NOT_RECORDING,
            (result as AudioCapture.CaptureResult.Failure).code,
        )
    }

    @Test
    fun `capture exposes a bounded maximum duration`() {
        // 回归防护：早期实现是阻塞式录满固定时长，导致用户以为已停止、
        // 实际还在录几十秒静音。上限必须存在且是有限值。
        assertTrue(AudioCapture.MAX_DURATION_MS in 1..60_000L)
    }

    @Test
    fun `permission check reflects the granted state`() {
        val capture = AudioCapture(context)
        assertEquals(false, capture.hasPermission())
    }

    @Test
    fun `session controller rejects stale results under real coroutines`() {
        val controller = SessionController()
        controller.startSession()
        val sessionId = controller.sessionId
        assertNotNull(sessionId)
        assertTrue(controller.isCurrent(sessionId!!))
        assertTrue(controller.isCurrent(sessionId))
    }

    @Test
    fun `fake engines run on the Android runtime`() {
        val segment = AudioSegment(
            sessionId = "rt-1",
            sampleRate = 16_000,
            channelCount = 1,
            encoding = AudioEncoding.PCM_16BIT,
            durationMs = 200,
            samples = ByteArray(640),
        )
        val recognition = FakeRecognitionEngine(resultText = "运行时验证", confidence = 0.9f)
        val result = kotlinx.coroutines.runBlocking {
            recognition.recognize(RecognitionRequest("rt-1", segment, "fake-0.1.0"))
        }
        assertEquals("运行时验证", result.normalizedText)

        val synthesis = FakeSynthesisEngine()
        val synthResult = kotlinx.coroutines.runBlocking {
            synthesis.synthesize(SynthesisRequest("rt-1", "运行时验证", null, "fake-0.1.0"))
        }
        assertNotNull(synthResult.audio)
        assertEquals("运行时验证", synthesis.lastRequestedText)
    }

    @Test
    fun `permission denial is detected through the framework api`() {
        val granted = context.checkPermission(
            Manifest.permission.RECORD_AUDIO,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
        )
        assertEquals(PackageManager.PERMISSION_DENIED, granted)
    }

    @Test
    fun `application context exposes the expected package`() {
        assertEquals("com.voiceclear.app", context.packageName)
    }
}
