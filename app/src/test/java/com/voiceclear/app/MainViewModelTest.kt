package com.voiceclear.app

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.voiceclear.app.engine.SpeechProfile
import com.voiceclear.app.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * MainViewModel 测试。
 *
 * 覆盖重构后 ViewModel 承担的核心职责：引擎选择、会话启动、状态回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    private fun createViewModel(): MainViewModel {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        return MainViewModel(application)
    }

    private fun awaitWhilePumpingMain(latch: CountDownLatch): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (latch.count > 0 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return latch.count == 0L
    }

    @Test
    fun `viewmodel exposes session controller`() {
        val viewModel = createViewModel()
        assertNotNull(viewModel.sessionController)
        assertNotNull(viewModel.audioCapture)
        assertNotNull(viewModel.audioPlayback)
        assertNotNull(viewModel.synthesisEngine)
        assertNotNull(viewModel.metricsCollector)
    }

    @Test
    fun `engine is placeholder until model is ready`() {
        val viewModel = createViewModel()
        // 模型未下载时不应有真实引擎
        assertNull(viewModel.senseVoiceEngine)
        assertNotNull("占位引擎必须存在", viewModel.activeEngine)
    }

    @Test
    fun `ensure engine does not crash when model missing`() {
        val viewModel = createViewModel()
        // 模型不存在时应安全降级，不能抛异常
        viewModel.ensureRecognitionEngine()
        assertNull(viewModel.senseVoiceEngine)
    }

    @Test
    fun `start recording without model fails closed before starting session`() {
        val viewModel = createViewModel()
        val failure = viewModel.startRecording()
        assertEquals(com.voiceclear.app.session.ErrorCodes.ASR_MODEL_MISSING, failure)
        assertEquals(SessionState.IDLE, viewModel.sessionController.state.value)
        assertNull(viewModel.sessionController.sessionId)
        assertNull(viewModel.lastRecognition)
    }

    @Test
    fun `cancel session resets state`() {
        val viewModel = createViewModel()
        viewModel.sessionController.startSession()
        viewModel.cancelSession()
        assertEquals(SessionState.CANCELLED, viewModel.sessionController.state.value)
    }

    @Test
    fun `ui events and state are lifecycle safe streams`() {
        val viewModel = createViewModel()
        assertNotNull(viewModel.recognitionEvents)
        assertNotNull(viewModel.playbackEvents)
        assertNotNull(viewModel.engineRevision)
    }

    @Test
    fun `mark stage without active session does not fabricate metrics`() {
        val viewModel = createViewModel()
        viewModel.markStage("capture", 123L)
        viewModel.recordMetrics(failed = false, cancelled = true)
        assertTrue(viewModel.metricsCollector.snapshot().isEmpty())
    }

    @Test
    fun `engine id reflects active engine`() {
        val viewModel = createViewModel()
        assertEquals(viewModel.activeEngine.engineId, viewModel.engineId)
    }

    @Test
    fun `benchmark is null before any recognition`() {
        val viewModel = createViewModel()
        assertNull(viewModel.lastBenchmark)
    }

    @Test
    fun `busy audio source change is rejected without persisting`() {
        val viewModel = createViewModel()
        val original = viewModel.settings.audioSource
        val replacement = com.voiceclear.app.engine.AudioSourceOption.ALL.first { it != original }
        viewModel.sessionController.startSession()

        assertFalse(viewModel.applyAudioSource(replacement))
        assertEquals(original, viewModel.settings.audioSource)
        viewModel.cancelSession()
    }

    @Test
    fun `busy speech profile change is rejected without persisting`() {
        val viewModel = createViewModel()
        val original = viewModel.settings.speechProfile
        val replacement = SpeechProfile.ALL.first { it != original }
        viewModel.sessionController.startSession()

        assertFalse(viewModel.applySpeechProfile(replacement))
        assertEquals(original, viewModel.settings.speechProfile)
        viewModel.cancelSession()
    }

    @Test
    fun `stale engine load cannot replace newly selected profile`() {
        val viewModel = createViewModel()
        val dir = java.io.File.createTempFile("profile-race", ".dir").apply {
            delete()
            mkdirs()
        }
        java.io.File(dir, com.voiceclear.app.engine.SenseVoiceRecognitionEngine.MODEL_FILE)
            .writeBytes(byteArrayOf(1))
        java.io.File(dir, com.voiceclear.app.engine.SenseVoiceRecognitionEngine.TOKENS_FILE)
            .writeBytes(byteArrayOf(1))
        val oldLoadStarted = CountDownLatch(1)
        val finishOldLoad = CountDownLatch(1)
        val oldReleased = CountDownLatch(1)
        val newLoadFinished = CountDownLatch(1)

        try {
            viewModel.settings.speechProfile = SpeechProfile.MANDARIN
            viewModel.recognitionMemoryAssessor = { _, _ ->
                com.voiceclear.app.engine.MemoryGuard.Assessment(
                    verdict = com.voiceclear.app.engine.MemoryGuard.Verdict.OK,
                    modelMb = 0L,
                    requiredMb = 128L,
                    availableMb = 1_024L,
                    totalRamMb = 2_048L,
                    heapMaxMb = 512L,
                    lowMemoryDevice = false,
                )
            }
            viewModel.recognitionEngineCreator = { context, profile ->
                val engine = com.voiceclear.app.engine.SenseVoiceRecognitionEngine(
                    dir.absolutePath,
                    com.voiceclear.app.engine.SenseVoiceNativeFactory {
                        if (profile == SpeechProfile.MANDARIN) {
                            oldLoadStarted.countDown()
                            finishOldLoad.await(5, TimeUnit.SECONDS)
                        }
                        object : com.voiceclear.app.engine.SenseVoiceNativeAdapter {
                            override fun decode(samples: FloatArray, sampleRate: Int) =
                                com.voiceclear.app.engine.SenseVoiceNativeResult("测试", "zh")

                            override fun release() {
                                if (profile == SpeechProfile.MANDARIN) oldReleased.countDown()
                            }
                        }
                    },
                    profile,
                )
                if (profile == SpeechProfile.SHANDONG) newLoadFinished.countDown()
                com.voiceclear.app.engine.RecognitionEngineFactory.Created(
                    engine = engine,
                    mode = com.voiceclear.app.engine.RecognitionEngineFactory.MODE_REAL,
                    modelDownloadManager = com.voiceclear.app.engine.ModelDownloadManager(context),
                )
            }

            viewModel.ensureRecognitionEngine()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue("旧档案应已进入 native 加载", awaitWhilePumpingMain(oldLoadStarted))

            assertTrue(viewModel.applySpeechProfile(SpeechProfile.SHANDONG))
            finishOldLoad.countDown()
            assertTrue("旧 native 实例应被释放", awaitWhilePumpingMain(oldReleased))
            assertTrue("新档案应完成加载", awaitWhilePumpingMain(newLoadFinished))

            val deadline = System.currentTimeMillis() + 2_000
            while (viewModel.senseVoiceEngine?.profile != SpeechProfile.SHANDONG &&
                System.currentTimeMillis() < deadline
            ) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SpeechProfile.SHANDONG, viewModel.senseVoiceEngine?.profile)
            assertEquals(SpeechProfile.SHANDONG, viewModel.settings.speechProfile)
            assertEquals(SpeechProfile.SHANDONG, viewModel.senseVoiceEngine?.profile)
            assertTrue(viewModel.activeEngine === viewModel.senseVoiceEngine)
        } finally {
            finishOldLoad.countDown()
            viewModel.senseVoiceEngine?.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `model download manager points to private storage`() {
        val viewModel = createViewModel()
        val dir = viewModel.modelDownloadManager.modelDir()
        assertTrue(dir.absolutePath.contains("files"))
        assertFalse(viewModel.modelDownloadManager.isReady())
    }
}
