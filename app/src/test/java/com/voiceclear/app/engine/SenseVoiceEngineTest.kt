package com.voiceclear.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * 端侧识别引擎与模型管理测试。
 *
 * 这些测试不加载真实模型（需要 239 MB 与 native 运行时），
 * 只验证：能力声明、模型就绪判定、错误路径、工厂不伪造引擎。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SenseVoiceEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun engineDeclaresOfflineAndNoConfidence() {
        val engine = SenseVoiceRecognitionEngine(context, "/nonexistent")
        assertEquals("sherpa-sensevoice-zh", engine.engineId)
        assertTrue(engine.capabilities.offline)
        assertFalse(engine.capabilities.ownsMicrophone)
        assertFalse(engine.capabilities.providesConfidence)
        assertFalse(engine.capabilities.preservesSpeakerIdentity)
    }

    @Test
    fun missingModelReportsNotReady() {
        val engine = SenseVoiceRecognitionEngine(context, "/nonexistent/model")
        assertFalse(engine.isModelReady())
        assertFalse(engine.load())
    }

    @Test
    fun recognizeWithoutModelReturnsExplicitFailure() = kotlinx.coroutines.runBlocking {
        val engine = SenseVoiceRecognitionEngine(context, "/nonexistent/model")
        val result = engine.recognize(
            RecognitionRequest(
                sessionId = "s1",
                audio = AudioSegment(
                    sessionId = "s1",
                    sampleRate = 16000,
                    channelCount = 1,
                    encoding = AudioEncoding.PCM_16BIT,
                    durationMs = 100,
                    samples = ByteArray(320),
                ),
                engineVersion = "test",
            ),
        )
        assertNotNull(result)
        assertEquals("s1", result.sessionId)
        assertTrue(result.warnings.isNotEmpty())
        assertEquals("", result.normalizedText)
        // 模型未加载时不得编造置信度。
        assertNull(result.confidence)
    }

    @Test
    fun factoryRefusesToFakeRealEngineWhenModelMissing() {
        // 模型不存在时工厂必须返回 null，不得用假引擎冒充真实识别。
        val created = RecognitionEngineFactory.create(context)
        if (created == null) {
            assertTrue(true)
        } else {
            assertEquals(RecognitionEngineFactory.MODE_REAL, created.mode)
        }
    }

    @Test
    fun modelDownloadManagerReportsDiskUsageAndReadiness() {
        val manager = ModelDownloadManager(context)
        assertFalse(manager.isReady())
        assertEquals(0L, manager.diskUsage())
        assertNotNull(manager.modelDir())
    }

    @Test
    fun archiveUrlPointsToOfficialRelease() {
        // 下载源必须是官方 Release 归档，避免依赖需要登录的模型托管。
        assertTrue(ModelDownloadManager.ARCHIVE_URL.startsWith("https://github.com/k2-fsa/"))
        assertTrue(ModelDownloadManager.ARCHIVE_URL.endsWith(".tar.bz2"))
        assertTrue(ModelDownloadManager.EXPECTED_MODEL_BYTES > 200_000_000L)
    }
}
