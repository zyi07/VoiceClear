package com.voiceclear.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTtsEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeAdapter : TtsAdapter {
        var listener: UtteranceProgressListener? = null
        var languageResult: Int = TextToSpeech.LANG_AVAILABLE
        var speakResult: Int = TextToSpeech.SUCCESS
        var spokenText: String? = null
        var utteranceId: String? = null
        var stopCount = 0
        var shutdownCount = 0

        override fun setLanguage(locale: Locale): Int = languageResult
        override fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS
        override fun setPitch(pitch: Float): Int = TextToSpeech.SUCCESS
        override fun setListener(listener: UtteranceProgressListener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }

        override fun speak(text: CharSequence, utteranceId: String): Int {
            spokenText = text.toString()
            this.utteranceId = utteranceId
            return speakResult
        }

        override fun stop(): Int {
            stopCount += 1
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            shutdownCount += 1
        }
    }

    private fun engineFor(
        adapter: FakeAdapter,
        initStatus: Int = TextToSpeech.SUCCESS,
        initTimeoutMs: Long = 100L,
    ) = AndroidTtsEngine(
        context = context,
        speechRate = 1.0f,
        adapterFactory = TtsAdapterFactory { _, onInit ->
            // 故意同步回调，覆盖工厂尚未返回 adapter 的初始化竞态。
            onInit(initStatus)
            adapter
        },
        initTimeoutMs = initTimeoutMs,
        synthesisTimeoutMs = 1_000L,
    )

    @Test
    fun `language failure is preserved and initialization can retry`() = runBlocking {
        val first = FakeAdapter().apply { languageResult = TextToSpeech.LANG_NOT_SUPPORTED }
        val second = FakeAdapter()
        var creates = 0
        val engine = AndroidTtsEngine(
            context = context,
            speechRate = 1.0f,
            adapterFactory = TtsAdapterFactory { _, onInit ->
                creates += 1
                onInit(TextToSpeech.SUCCESS)
                if (creates == 1) first else second
            },
            initTimeoutMs = 100L,
            synthesisTimeoutMs = 100L,
        )

        assertFalse(engine.ensureInitialized())
        assertEquals(AndroidTtsEngine.ERR_LANGUAGE_UNAVAILABLE, engine.lastInitializationError)
        assertEquals(1, first.shutdownCount)
        assertTrue(engine.ensureInitialized())
        assertEquals(2, creates)
        assertNull(engine.lastInitializationError)
        engine.release()
    }

    @Test
    fun `synthesize preserves language initialization failure`() = runBlocking {
        val adapter = FakeAdapter().apply { languageResult = TextToSpeech.LANG_NOT_SUPPORTED }
        val engine = engineFor(adapter)
        val result = engine.synthesize(SynthesisRequest("language", "测试", null, "test"))
        assertEquals(listOf(AndroidTtsEngine.ERR_LANGUAGE_UNAVAILABLE), result.warnings)
        assertNull(adapter.utteranceId)
        engine.release()
    }

    @Test
    fun `initialization timeout is not cached and next attempt succeeds`() = runBlocking {
        val first = FakeAdapter()
        val second = FakeAdapter()
        var creates = 0
        val engine = AndroidTtsEngine(
            context = context,
            speechRate = 1.0f,
            adapterFactory = TtsAdapterFactory { _, onInit ->
                creates += 1
                if (creates > 1) onInit(TextToSpeech.SUCCESS)
                if (creates == 1) first else second
            },
            initTimeoutMs = 20L,
            synthesisTimeoutMs = 100L,
        )

        assertFalse(engine.ensureInitialized())
        assertEquals(AndroidTtsEngine.ERR_INIT_TIMEOUT, engine.lastInitializationError)
        assertTrue(engine.ensureInitialized())
        assertEquals(2, creates)
        assertEquals(1, first.shutdownCount)
        engine.release()
    }

    @Test
    fun `callbacks with another utterance id cannot complete synthesis`() = runBlocking {
        val adapter = FakeAdapter()
        val engine = engineFor(adapter)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            engine.synthesize(SynthesisRequest("s1", "测试", null, "test"))
        }
        val actualId = adapter.utteranceId
        assertTrue(actualId != null)

        adapter.listener?.onDone("vc-other-session")
        assertFalse(pending.isCompleted)
        adapter.listener?.onDone(actualId)
        val result = withTimeout(1_000L) { pending.await() }
        assertTrue(result.warnings.isEmpty())
        assertEquals("测试", adapter.spokenText)
        engine.release()
    }

    @Test
    fun `stop completes the current waiter`() = runBlocking {
        val adapter = FakeAdapter()
        val engine = engineFor(adapter)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            engine.synthesize(SynthesisRequest("s2", "停止", null, "test"))
        }
        engine.stop()

        val result = withTimeout(1_000L) { pending.await() }
        assertEquals(listOf(AndroidTtsEngine.ERR_STOPPED), result.warnings)
        assertEquals(1, adapter.stopCount)
        engine.release()
    }

    @Test
    fun `release completes the current waiter and is idempotent`() = runBlocking {
        val adapter = FakeAdapter()
        val engine = engineFor(adapter)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            engine.synthesize(SynthesisRequest("s3", "释放", null, "test"))
        }
        engine.release()
        engine.release()

        val result = withTimeout(1_000L) { pending.await() }
        assertEquals(listOf(AndroidTtsEngine.ERR_RELEASED), result.warnings)
        assertEquals(1, adapter.shutdownCount)
    }

    @Test
    fun `system utterance error code is retained`() = runBlocking {
        val adapter = FakeAdapter()
        val engine = engineFor(adapter)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            engine.synthesize(SynthesisRequest("s4", "错误", null, "test"))
        }
        adapter.listener?.onError(adapter.utteranceId, TextToSpeech.ERROR_SYNTHESIS)

        val result = withTimeout(1_000L) { pending.await() }
        assertEquals(listOf(AndroidTtsEngine.ERR_UTTERANCE_ERROR), result.warnings)
        assertEquals(TextToSpeech.ERROR_SYNTHESIS, engine.lastUtteranceErrorCode)
        engine.release()
    }

    @Test
    fun `blank text fails without creating a system engine`() = runBlocking {
        var creates = 0
        val engine = AndroidTtsEngine(
            context = context,
            speechRate = 1.0f,
            adapterFactory = TtsAdapterFactory { _, _ ->
                creates += 1
                FakeAdapter()
            },
            initTimeoutMs = 20L,
            synthesisTimeoutMs = 20L,
        )
        val result = engine.synthesize(SynthesisRequest("s5", "   ", null, "test"))
        assertEquals(listOf(AndroidTtsEngine.ERR_SPEAK_FAILED), result.warnings)
        assertEquals(0, creates)
        engine.release()
    }
}
