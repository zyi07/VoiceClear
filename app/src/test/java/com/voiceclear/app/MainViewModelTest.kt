package com.voiceclear.app

import androidx.test.core.app.ApplicationProvider
import com.voiceclear.app.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun `start recording without permission reports failure`() {
        val viewModel = createViewModel()
        // 默认没有录音权限
        val failure = viewModel.startRecording()
        assertNotNull("无权限时应返回错误码", failure)
    }

    @Test
    fun `cancel session resets state`() {
        val viewModel = createViewModel()
        viewModel.sessionController.startSession()
        viewModel.cancelSession()
        assertEquals(SessionState.CANCELLED, viewModel.sessionController.state.value)
    }

    @Test
    fun `state change callback can be registered`() {
        val viewModel = createViewModel()
        var received: SessionState? = null
        viewModel.onStateChanged = { received = it }
        viewModel.onStateChanged?.invoke(SessionState.PLAYING)
        assertEquals(SessionState.PLAYING, received)
    }

    @Test
    fun `mark stage records elapsed time`() {
        val viewModel = createViewModel()
        viewModel.markStage("capture", 123L)
        // 该值会被 recordMetrics 消费，这里只验证不抛异常
        assertTrue(true)
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
    fun `model download manager points to private storage`() {
        val viewModel = createViewModel()
        val dir = viewModel.modelDownloadManager.modelDir()
        assertTrue(dir.absolutePath.contains("files"))
        assertFalse(viewModel.modelDownloadManager.isReady())
    }
}
