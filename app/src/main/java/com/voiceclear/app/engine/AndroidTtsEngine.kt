package com.voiceclear.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voiceclear.app.session.ErrorCodes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 基于 Android 原生 TextToSpeech 的合成引擎。
 *
 * 选用理由：系统自带、无需下发模型体积、覆盖全部设备；
 * 缺点是声音为通用音色，不保留用户本人音色（技术指导文件 5.4 节：先用通用声音建立基线）。
 * 个性化音色留待 P3 的个性化 TTS 阶段验证。
 *
 * API 来源（本地 SDK android-34，javap 实测签名）：
 * - android.speech.tts.TextToSpeech(Context, TextToSpeech.OnInitListener)
 * - TextToSpeech.setLanguage(Locale) / setSpeechRate(float) / setPitch(float)
 * - TextToSpeech.speak(CharSequence, int, Bundle, String)
 * - TextToSpeech.isLanguageAvailable(Locale)
 * - TextToSpeech.setOnUtteranceProgressListener(UtteranceProgressListener)
 * - android.speech.tts.UtteranceProgressListener.onStart/onDone/onError
 */
class AndroidTtsEngine(
    private val context: Context,
    private val speechRate: Float = DEFAULT_SPEECH_RATE,
) : SynthesisEngine {

    companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val ERR_NOT_READY = ErrorCodes.TTS_NOT_READY
        const val ERR_LANGUAGE_UNAVAILABLE = ErrorCodes.TTS_LANGUAGE_UNAVAILABLE
        const val ERR_SPEAK_FAILED = ErrorCodes.TTS_SPEAK_FAILED
        const val ERR_UTTERANCE_ERROR = ErrorCodes.TTS_UTTERANCE_ERROR
        const val ERR_SYNTHESIS_TIMEOUT = ErrorCodes.TTS_SYNTHESIS_TIMEOUT
        const val ERR_INIT_TIMEOUT = "tts_init_timeout"

        /** 合成超时上限：超过则视为失败并回退，避免用户长时间等待。 */
        const val SYNTHESIS_TIMEOUT_MS = 15_000L

        /** 初始化超时上限：引擎无响应时必须失败返回，不能让界面一直等待。 */
        const val INIT_TIMEOUT_MS = 8_000L
    }

    private var textToSpeech: TextToSpeech? = null
    @Volatile
    private var ready = false
    private var initDeferred: CompletableDeferred<Boolean>? = null

    /** 初始化引擎并确认普通话可用。 */
    suspend fun ensureInitialized(): Boolean {
        if (ready) return true
        val pending = initDeferred
        if (pending != null) {
            return try {
                withTimeout(INIT_TIMEOUT_MS) { pending.await() }
            } catch (error: TimeoutCancellationException) {
                ready = false
                false
            }
        }

        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        textToSpeech = TextToSpeech(context) { status ->
            val engine = textToSpeech
            if (status != TextToSpeech.SUCCESS || engine == null) {
                ready = false
                deferred.complete(false)
                return@TextToSpeech
            }
            val languageResult = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            val available = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                languageResult != TextToSpeech.LANG_NOT_SUPPORTED
            if (available) {
                engine.setSpeechRate(speechRate)
                engine.setPitch(1.0f)
                ready = true
            }
            deferred.complete(available)
        }
        // 引擎初始化回调可能不触发（缺少语音包或引擎异常），必须有超时兜底。
        return try {
            withTimeout(INIT_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            ready = false
            false
        }
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        if (!ensureInitialized()) {
            return SynthesisResult(
                sessionId = request.sessionId,
                audio = null,
                durationMs = 0,
                warnings = listOf(ERR_NOT_READY),
            )
        }
        val engine = textToSpeech ?: return SynthesisResult(
            sessionId = request.sessionId,
            audio = null,
            durationMs = 0,
            warnings = listOf(ERR_NOT_READY),
        )
        if (request.confirmedText.isBlank()) {
            return SynthesisResult(
                sessionId = request.sessionId,
                audio = null,
                durationMs = 0,
                warnings = listOf(ERR_SPEAK_FAILED),
            )
        }

        val utteranceId = "vc-" + request.sessionId
        val startedAt = System.currentTimeMillis()
        // 超时保护：引擎异常或回调丢失时不能让用户无限等待。
        val completion = try {
            withTimeout(SYNTHESIS_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit

                        override fun onDone(id: String?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(id: String?) {
                            if (continuation.isActive) continuation.resume(false)
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    val status = engine.speak(
                        request.confirmedText,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        utteranceId,
                    )
                    if (status != TextToSpeech.SUCCESS) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            runCatching { engine.stop() }
            false
        }

        val durationMs = System.currentTimeMillis() - startedAt
        if (!completion) {
            val timedOut = durationMs >= SYNTHESIS_TIMEOUT_MS
            return SynthesisResult(
                sessionId = request.sessionId,
                audio = null,
                durationMs = durationMs,
                warnings = listOf(
                    if (timedOut) ERR_SYNTHESIS_TIMEOUT else ERR_UTTERANCE_ERROR,
                ),
            )
        }
        return SynthesisResult(
            sessionId = request.sessionId,
            audio = null,
            durationMs = durationMs,
            warnings = emptyList(),
        )
    }

    /** 停止当前朗读，用于用户打断或新会话开始。 */
    fun stop() {
        runCatching { textToSpeech?.stop() }
    }

    /** 释放系统 TTS 资源。 */
    fun release() {
        ready = false
        runCatching { textToSpeech?.shutdown() }
        textToSpeech = null
        initDeferred = null
    }
}
