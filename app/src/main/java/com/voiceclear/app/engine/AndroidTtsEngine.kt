package com.voiceclear.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voiceclear.app.session.ErrorCodes
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** 系统 TTS 的可替换测试缝。 */
internal interface TtsAdapter {
    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun setListener(listener: UtteranceProgressListener): Int
    fun speak(text: CharSequence, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

internal fun interface TtsAdapterFactory {
    fun create(context: Context, onInit: (Int) -> Unit): TtsAdapter
}

/**
 * 基于 Android 原生 TextToSpeech 的合成引擎。
 *
 * 初始化失败会清除旧尝试并释放适配器，后续可以重试；语言缺失、初始化超时不再折叠为
 * 同一种错误。回调按唯一 utteranceId 精确匹配，stop/release 会完成全部等待者。
 *
 * API 来源：本地 /opt/android-sdk/platforms/android-36/android.jar（javap）。
 * TextToSpeech(Context,OnInitListener)、setLanguage/setSpeechRate/setPitch、
 * speak(CharSequence,int,Bundle,String)、setOnUtteranceProgressListener、stop/shutdown，
 * 以及 UtteranceProgressListener.onStart/onDone/onError 均兼容 minSdk 26。
 */
class AndroidTtsEngine internal constructor(
    private val context: Context,
    private val speechRate: Float,
    private val adapterFactory: TtsAdapterFactory,
    private val initTimeoutMs: Long,
    private val synthesisTimeoutMs: Long,
) : SynthesisEngine {

    constructor(
        context: Context,
        speechRate: Float = DEFAULT_SPEECH_RATE,
    ) : this(
        context = context,
        speechRate = speechRate,
        adapterFactory = TtsAdapterFactory { applicationContext, onInit ->
            val engine = TextToSpeech(applicationContext) { status -> onInit(status) }
            object : TtsAdapter {
                override fun setLanguage(locale: Locale): Int = engine.setLanguage(locale)
                override fun setSpeechRate(rate: Float): Int = engine.setSpeechRate(rate)
                override fun setPitch(pitch: Float): Int = engine.setPitch(pitch)
                override fun setListener(listener: UtteranceProgressListener): Int =
                    engine.setOnUtteranceProgressListener(listener)
                override fun speak(text: CharSequence, utteranceId: String): Int =
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                override fun stop(): Int = engine.stop()
                override fun shutdown() = engine.shutdown()
            }
        },
        initTimeoutMs = INIT_TIMEOUT_MS,
        synthesisTimeoutMs = SYNTHESIS_TIMEOUT_MS,
    )

    companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val ERR_NOT_READY = ErrorCodes.TTS_NOT_READY
        const val ERR_LANGUAGE_UNAVAILABLE = ErrorCodes.TTS_LANGUAGE_UNAVAILABLE
        const val ERR_SPEAK_FAILED = ErrorCodes.TTS_SPEAK_FAILED
        const val ERR_UTTERANCE_ERROR = ErrorCodes.TTS_UTTERANCE_ERROR
        const val ERR_SYNTHESIS_TIMEOUT = ErrorCodes.TTS_SYNTHESIS_TIMEOUT
        const val ERR_INIT_TIMEOUT = ErrorCodes.TTS_INIT_TIMEOUT
        const val ERR_STOPPED = "tts_stopped"
        const val ERR_RELEASED = "tts_released"

        const val SYNTHESIS_TIMEOUT_MS = 15_000L
        const val INIT_TIMEOUT_MS = 8_000L
    }

    private class InitAttempt {
        val deferred = CompletableDeferred<Boolean>()
        var adapter: TtsAdapter? = null
        var pendingStatus: Int? = null
        var terminal = false
    }

    private data class UtteranceCompletion(val warning: String?)

    private class UtteranceWaiter(
        val continuation: kotlinx.coroutines.CancellableContinuation<UtteranceCompletion>,
    )

    private val initLock = Any()
    private val utteranceLock = Any()
    private val utteranceCounter = AtomicLong(0L)
    private val activeUtterances = mutableMapOf<String, UtteranceWaiter>()

    @Volatile
    private var ttsAdapter: TtsAdapter? = null

    @Volatile
    private var ready = false

    private var initAttempt: InitAttempt? = null

    /** 最近一次初始化失败的明确错误码。 */
    @Volatile
    var lastInitializationError: String? = null
        private set

    /** 系统 onError(id, errorCode) 的原始错误码，供诊断避免信息丢失。 */
    @Volatile
    var lastUtteranceErrorCode: Int? = null
        private set

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit

        override fun onDone(id: String?) {
            if (id != null) completeUtterance(id, UtteranceCompletion(null))
        }

        override fun onError(id: String?) {
            if (id != null) completeUtterance(id, UtteranceCompletion(ERR_UTTERANCE_ERROR))
        }

        override fun onError(id: String?, errorCode: Int) {
            if (id != null && completeUtterance(id, UtteranceCompletion(ERR_UTTERANCE_ERROR))) {
                lastUtteranceErrorCode = errorCode
            }
        }
    }

    /** 初始化引擎并确认普通话可用；失败后可以再次调用重试。 */
    suspend fun ensureInitialized(): Boolean {
        if (ready) return true
        val attempt: InitAttempt
        val createAdapter: Boolean
        synchronized(initLock) {
            val existing = initAttempt
            if (existing != null) {
                attempt = existing
                createAdapter = false
            } else {
                attempt = InitAttempt()
                initAttempt = attempt
                createAdapter = true
                lastInitializationError = null
            }
        }

        if (createAdapter) {
            val created = try {
                adapterFactory.create(context) { status -> onInitialization(attempt, status) }
            } catch (error: Throwable) {
                failInitialization(attempt, ERR_NOT_READY)
                null
            }
            if (created != null) {
                val pending = synchronized(initLock) {
                    if (attempt.terminal) {
                        null
                    } else {
                        attempt.adapter = created
                        ttsAdapter = created
                        attempt.pendingStatus.also { attempt.pendingStatus = null }
                    }
                }
                if (attempt.terminal) {
                    runCatching { created.shutdown() }
                } else if (pending != null) {
                    configureInitialization(attempt, created, pending)
                }
            }
        }

        return try {
            val initialized = withTimeout(initTimeoutMs) { attempt.deferred.await() }
            if (!initialized) failInitialization(attempt, lastInitializationError ?: ERR_NOT_READY)
            initialized
        } catch (error: TimeoutCancellationException) {
            failInitialization(attempt, ERR_INIT_TIMEOUT)
            false
        }
    }

    private fun onInitialization(attempt: InitAttempt, status: Int) {
        val adapter = synchronized(initLock) {
            if (attempt.terminal) return
            attempt.adapter ?: run {
                // 工厂可能在返回适配器前同步回调，先保存状态，返回后再配置。
                attempt.pendingStatus = status
                return
            }
        }
        configureInitialization(attempt, adapter, status)
    }

    private fun configureInitialization(attempt: InitAttempt, adapter: TtsAdapter, status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failInitialization(attempt, ERR_NOT_READY)
            return
        }
        try {
            val languageResult = adapter.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                failInitialization(attempt, ERR_LANGUAGE_UNAVAILABLE)
                return
            }
            adapter.setSpeechRate(speechRate)
            adapter.setPitch(1.0f)
            adapter.setListener(utteranceListener)
            synchronized(initLock) {
                if (attempt.terminal) return
                attempt.terminal = true
                ready = true
                lastInitializationError = null
                ttsAdapter = adapter
                if (initAttempt === attempt) initAttempt = null
                attempt.deferred.complete(true)
            }
        } catch (error: Throwable) {
            failInitialization(attempt, ERR_NOT_READY)
        }
    }

    private fun failInitialization(attempt: InitAttempt, code: String) {
        val adapterToClose: TtsAdapter?
        synchronized(initLock) {
            if (attempt.terminal) return
            attempt.terminal = true
            ready = false
            lastInitializationError = code
            adapterToClose = attempt.adapter
            if (ttsAdapter === adapterToClose) ttsAdapter = null
            if (initAttempt === attempt) initAttempt = null
            attempt.deferred.complete(false)
        }
        adapterToClose?.let { adapter -> runCatching { adapter.shutdown() } }
    }

    override suspend fun synthesize(request: SynthesisRequest): SynthesisResult {
        if (request.confirmedText.isBlank()) {
            return failure(request.sessionId, ERR_SPEAK_FAILED, 0L)
        }
        if (!ensureInitialized()) {
            return failure(request.sessionId, lastInitializationError ?: ERR_NOT_READY, 0L)
        }
        val adapter = ttsAdapter ?: return failure(request.sessionId, ERR_NOT_READY, 0L)
        val utteranceId = "vc-" + request.sessionId + "-" + utteranceCounter.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        lastUtteranceErrorCode = null

        // QUEUE_FLUSH 会取代旧朗读，先结束旧等待者，避免它永久挂起。
        completeAllUtterances(ERR_STOPPED)
        val completion = try {
            withTimeout(synthesisTimeoutMs) {
                suspendCancellableCoroutine<UtteranceCompletion> { continuation ->
                    val waiter = UtteranceWaiter(continuation)
                    synchronized(utteranceLock) {
                        if (!ready || ttsAdapter !== adapter) {
                            continuation.resume(UtteranceCompletion(ERR_RELEASED))
                            return@suspendCancellableCoroutine
                        }
                        activeUtterances[utteranceId] = waiter
                        continuation.invokeOnCancellation {
                            completeUtterance(utteranceId, UtteranceCompletion(ERR_STOPPED))
                        }
                        // 与 stop 同锁：stop 要么发生在 speak 前并使 continuation 失效，
                        // 要么在 speak 后停止它，不会出现 stop 后漏停的新朗读。
                        if (continuation.isActive) {
                            val status = adapter.speak(request.confirmedText, utteranceId)
                            if (status != TextToSpeech.SUCCESS) {
                                activeUtterances.remove(utteranceId)
                                continuation.resume(UtteranceCompletion(ERR_SPEAK_FAILED))
                            }
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            stopAdapterAndComplete(adapter, ERR_SYNTHESIS_TIMEOUT)
            UtteranceCompletion(ERR_SYNTHESIS_TIMEOUT)
        }

        val durationMs = System.currentTimeMillis() - startedAt
        completion.warning?.let { warning ->
            return failure(request.sessionId, warning, durationMs)
        }
        return SynthesisResult(
            sessionId = request.sessionId,
            audio = null,
            durationMs = durationMs,
            warnings = emptyList(),
        )
    }

    private fun completeUtterance(id: String, completion: UtteranceCompletion): Boolean {
        val waiter = synchronized(utteranceLock) { activeUtterances.remove(id) } ?: return false
        if (waiter.continuation.isActive) {
            waiter.continuation.resume(completion)
        }
        return true
    }

    private fun completeAllUtterances(warning: String) {
        val waiters = synchronized(utteranceLock) {
            activeUtterances.values.toList().also { activeUtterances.clear() }
        }
        resumeAll(waiters, warning)
    }

    /** speak 也持有 utteranceLock，保证 stop 不可能在 speak 前空跑、随后漏停新朗读。 */
    private fun stopAdapterAndComplete(adapter: TtsAdapter?, warning: String) {
        val waiters = synchronized(utteranceLock) {
            runCatching { adapter?.stop() }
            activeUtterances.values.toList().also { activeUtterances.clear() }
        }
        resumeAll(waiters, warning)
    }

    private fun resumeAll(waiters: List<UtteranceWaiter>, warning: String) {
        for (waiter in waiters) {
            if (waiter.continuation.isActive) {
                waiter.continuation.resume(UtteranceCompletion(warning))
            }
        }
    }

    private fun failure(sessionId: String, code: String, durationMs: Long) = SynthesisResult(
        sessionId = sessionId,
        audio = null,
        durationMs = durationMs,
        warnings = listOf(code),
    )

    /** 停止当前朗读并立即结束等待者。 */
    fun stop() {
        stopAdapterAndComplete(ttsAdapter, ERR_STOPPED)
    }

    /** 释放系统 TTS 资源，同时结束初始化与朗读等待者。 */
    fun release() {
        val attemptToFail: InitAttempt?
        val adapter: TtsAdapter?
        synchronized(initLock) {
            ready = false
            lastInitializationError = ERR_NOT_READY
            attemptToFail = initAttempt
            initAttempt = null
            attemptToFail?.terminal = true
            attemptToFail?.deferred?.complete(false)
            adapter = ttsAdapter ?: attemptToFail?.adapter
            ttsAdapter = null
        }
        stopAdapterAndComplete(adapter, ERR_RELEASED)
        runCatching { adapter?.shutdown() }
    }
}
