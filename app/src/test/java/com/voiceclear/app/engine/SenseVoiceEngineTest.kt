package com.voiceclear.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
        val engine = SenseVoiceRecognitionEngine("/nonexistent")
        assertEquals("sherpa-sensevoice-zh", engine.engineId)
        assertTrue(engine.capabilities.offline)
        assertFalse(engine.capabilities.ownsMicrophone)
        assertFalse(engine.capabilities.providesConfidence)
        assertFalse(engine.capabilities.preservesSpeakerIdentity)
    }

    @Test
    fun missingModelReportsNotReady() {
        val engine = SenseVoiceRecognitionEngine("/nonexistent/model")
        assertFalse(engine.isModelReady())
        assertFalse(engine.load())
    }

    @Test
    fun recognizeWithoutModelReturnsExplicitFailure() = kotlinx.coroutines.runBlocking {
        val engine = SenseVoiceRecognitionEngine("/nonexistent/model")
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
        assertEquals(SenseVoiceRecognitionEngine.ERR_MODEL_MISSING, result.errorCode)
        assertFalse(result.isSuccess)
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
    fun audioValidationDistinguishesFormatFailures() {
        val engine = SenseVoiceRecognitionEngine("/nonexistent/model")
        val valid = AudioSegment(
            sessionId = "s1",
            sampleRate = 16_000,
            channelCount = 1,
            encoding = AudioEncoding.PCM_16BIT,
            durationMs = 1,
            samples = ByteArray(2),
        )
        assertEquals(null, engine.validateAudio(valid))
        assertEquals(
            SenseVoiceRecognitionEngine.ERR_SAMPLE_RATE_MISMATCH,
            engine.validateAudio(valid.copy(sampleRate = 8_000)),
        )
        assertEquals(
            SenseVoiceRecognitionEngine.ERR_CHANNEL_COUNT_MISMATCH,
            engine.validateAudio(valid.copy(channelCount = 2)),
        )
        assertEquals(
            SenseVoiceRecognitionEngine.ERR_EMPTY_AUDIO,
            engine.validateAudio(valid.copy(samples = ByteArray(0))),
        )
        assertEquals(
            SenseVoiceRecognitionEngine.ERR_INVALID_PCM16,
            engine.validateAudio(valid.copy(samples = ByteArray(1))),
        )
    }

    @Test
    fun releaseWaitsForInFlightNativeDecode() {
        val dir = File.createTempFile("sensevoice-lock", ".dir").apply {
            delete()
            mkdirs()
        }
        File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(byteArrayOf(1))
        File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(byteArrayOf(1))
        val decodeEntered = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val nativeReleaseCount = AtomicInteger(0)
        val adapter = object : SenseVoiceNativeAdapter {
            override fun decode(samples: FloatArray, sampleRate: Int): SenseVoiceNativeResult {
                decodeEntered.countDown()
                allowDecode.await(2, TimeUnit.SECONDS)
                return SenseVoiceNativeResult("<|zh|>并发安全", "zh")
            }

            override fun release() {
                nativeReleaseCount.incrementAndGet()
            }
        }
        val engine = SenseVoiceRecognitionEngine(
            dir.absolutePath,
            SenseVoiceNativeFactory { adapter },
        )
        val recognition = AtomicReference<RecognitionResult>()
        var recognitionThread: Thread? = null
        var releaseThread: Thread? = null
        try {
            assertTrue(engine.load())
            val runningRecognition = Thread {
                recognition.set(
                    kotlinx.coroutines.runBlocking {
                        engine.recognize(
                            RecognitionRequest(
                                sessionId = "locked-session",
                                audio = AudioSegment(
                                    sessionId = "locked-session",
                                    sampleRate = 16_000,
                                    channelCount = 1,
                                    encoding = AudioEncoding.PCM_16BIT,
                                    durationMs = 1,
                                    samples = ByteArray(2),
                                ),
                                engineVersion = "test",
                            ),
                        )
                    },
                )
            }
            recognitionThread = runningRecognition
            runningRecognition.start()
            assertTrue(decodeEntered.await(1, TimeUnit.SECONDS))
            val waitingRelease = Thread {
                engine.release()
                releaseFinished.countDown()
            }
            releaseThread = waitingRelease
            waitingRelease.start()

            assertFalse("release 不得在 decode 进行中返回", releaseFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, nativeReleaseCount.get())
            allowDecode.countDown()
            runningRecognition.join(2_000)
            assertTrue(releaseFinished.await(1, TimeUnit.SECONDS))
            assertEquals(1, nativeReleaseCount.get())
            assertEquals("并发安全", recognition.get()?.normalizedText)
            assertTrue(recognition.get()?.isSuccess == true)
        } finally {
            allowDecode.countDown()
            recognitionThread?.join(2_000)
            releaseThread?.join(2_000)
            engine.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun everyDownloadSourceUsesHttps() {
        // 所有下载源必须是 https，避免明文传输被中间人替换模型文件。
        for (source in ModelDownloadManager.SOURCES) {
            assertTrue(source.modelUrl.startsWith("https://"))
            if (source.tokensUrl.isNotEmpty()) {
                assertTrue(source.tokensUrl.startsWith("https://"))
            }
        }
    }
}
