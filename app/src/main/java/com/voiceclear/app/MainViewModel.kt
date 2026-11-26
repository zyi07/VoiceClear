package com.voiceclear.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceclear.app.engine.AndroidTtsEngine
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioFocusManager
import com.voiceclear.app.engine.AudioPlayback
import com.voiceclear.app.engine.AudioQuality
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.AudioSourceOption
import com.voiceclear.app.engine.FakeRecognitionEngine
import com.voiceclear.app.engine.MemoryGuard
import com.voiceclear.app.engine.ModelDownloadManager
import com.voiceclear.app.engine.RecognitionEngine
import com.voiceclear.app.engine.RecognitionEngineFactory
import com.voiceclear.app.engine.RecognitionRequest
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.engine.SenseVoiceRecognitionEngine
import com.voiceclear.app.engine.SynthesisEngine
import com.voiceclear.app.engine.SynthesisRequest
import com.voiceclear.app.session.AppSettings
import com.voiceclear.app.session.BenchmarkAggregator
import com.voiceclear.app.session.DiagnosticLog
import com.voiceclear.app.session.ErrorCodes
import com.voiceclear.app.session.MetricsCollector
import com.voiceclear.app.session.PerformanceBenchmark
import com.voiceclear.app.session.ProcessingStage
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionMetrics
import com.voiceclear.app.session.SessionOperationGate
import com.voiceclear.app.session.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 主流程状态与资源所有者；Activity 只渲染状态并消费一次性事件。 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val sessionController = SessionController()
    val settings = AppSettings(application)

    var audioCapture = AudioCapture(application, sourceOption = settings.audioSource)
        private set

    val audioPlayback = AudioPlayback()
    val audioFocusManager = AudioFocusManager(application)
    val synthesisEngine: SynthesisEngine = AndroidTtsEngine(application)
    val modelDownloadManager = ModelDownloadManager(application)
    val metricsCollector = MetricsCollector()
    val benchmarkAggregator = BenchmarkAggregator()

    var senseVoiceEngine: SenseVoiceRecognitionEngine? = null
        private set

    /** 仅占位展示引擎能力；真实主流程在 startRecording 中明确拒绝使用它。 */
    var activeEngine: RecognitionEngine = FakeRecognitionEngine()
        private set

    var engineIssue: String? = ErrorCodes.ASR_MODEL_MISSING
        private set

    private val _engineRevision = MutableStateFlow(0L)
    val engineRevision: StateFlow<Long> = _engineRevision.asStateFlow()
    private val engineLoadMutex = Mutex()
    private var engineLoadJob: Job? = null

    var lastQuality: AudioQuality.Analysis? = null
        private set
    var lastBenchmark: PerformanceBenchmark.Record? = null
        private set
    var modelLoadMs: Long = 0L
        private set

    /** 只在进程首次创建 ViewModel 时由 Activity 写入，旋转不得覆盖。 */
    var coldStartMs: Long = 0L

    var lastCapturedSegment: AudioSegment? = null
        private set
    var lastRecognition: RecognitionResult? = null
        private set

    private data class SessionRuntime(
        val token: SessionController.Token,
        val engine: RecognitionEngine,
        val startedAt: Long,
        val stageStart: MutableMap<String, Long> = mutableMapOf(),
        val metricsRecorded: AtomicBoolean = AtomicBoolean(false),
        var recognitionJob: Job? = null,
        var playbackJob: Job? = null,
        var playbackGeneration: Long = 0L,
        var originalPlaying: Boolean = false,
    )

    @Volatile
    private var currentRuntime: SessionRuntime? = null

    private val stopGate = SessionOperationGate()

    // Channel 在无订阅者（旋转/后台）时保留事件；StateFlow 仍负责恢复持久状态。
    private val _recognitionEvents = Channel<RecognitionOutcome>(Channel.UNLIMITED)
    val recognitionEvents: Flow<RecognitionOutcome> = _recognitionEvents.receiveAsFlow()

    private val _playbackEvents = Channel<PlaybackOutcome>(Channel.UNLIMITED)
    val playbackEvents: Flow<PlaybackOutcome> = _playbackEvents.receiveAsFlow()

    data class PlaybackOutcome(
        val sessionId: String,
        val generation: Long,
        val success: Boolean,
        val original: Boolean,
    )

    /**
     * 在 IO 线程准备识别引擎，并通过 job + mutex 去重。模型未就绪或加载失败时
     * 不会把占位引擎用于真实录音。
     */
    fun ensureRecognitionEngine() {
        if (senseVoiceEngine != null || engineLoadJob?.isActive == true) return
        engineLoadJob = viewModelScope.launch {
            engineLoadMutex.withLock {
                if (senseVoiceEngine != null) return@withLock
                var unownedEngine: SenseVoiceRecognitionEngine? = null
                try {
                    val outcome = try {
                        withContext(Dispatchers.IO) {
                            val created = RecognitionEngineFactory.create(getApplication())
                                ?: return@withContext null
                            unownedEngine = created.engine

                            val assessment = MemoryGuard.assess(
                                getApplication(),
                                created.engine.modelBytes(),
                            )
                            if (assessment.verdict == MemoryGuard.Verdict.INSUFFICIENT) {
                                return@withContext LoadResult.Failure(
                                    ErrorCodes.ASR_INSUFFICIENT_MEMORY,
                                    assessment.describe(),
                                )
                            }

                            val loadStart = System.currentTimeMillis()
                            val loaded = created.engine.load()
                            val elapsed = System.currentTimeMillis() - loadStart
                            if (!loaded) {
                                val code = if (created.engine.outOfMemory) {
                                    ErrorCodes.ASR_INSUFFICIENT_MEMORY
                                } else {
                                    ErrorCodes.ASR_MODEL_LOAD_FAILED
                                }
                                LoadResult.Failure(code, "outOfMemory=" + created.engine.outOfMemory)
                            } else {
                                LoadResult.Success(created.engine, elapsed, assessment.describe())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        LoadResult.Failure(
                            ErrorCodes.ASR_MODEL_LOAD_FAILED,
                            error.javaClass.simpleName,
                        )
                    }

                    when (outcome) {
                        null -> {
                            activeEngine = FakeRecognitionEngine()
                            engineIssue = ErrorCodes.ASR_MODEL_MISSING
                            DiagnosticLog.w("Main", "识别模型未就绪，真实录音入口保持关闭")
                        }
                        is LoadResult.Failure -> {
                            senseVoiceEngine = null
                            activeEngine = FakeRecognitionEngine()
                            engineIssue = outcome.code
                            DiagnosticLog.e(
                                "Main",
                                "识别引擎加载失败: " + outcome.code + ", " + outcome.detail,
                            )
                        }
                        is LoadResult.Success -> {
                            senseVoiceEngine?.release()
                            senseVoiceEngine = outcome.engine
                            activeEngine = outcome.engine
                            modelLoadMs = outcome.loadMs
                            engineIssue = null
                            unownedEngine = null
                            DiagnosticLog.i(
                                "Main",
                                "识别引擎就绪: " + outcome.engine.engineId +
                                    ", 加载耗时=" + modelLoadMs + "ms",
                            )
                        }
                    }
                    _engineRevision.value += 1
                } finally {
                    // 失败、异常或 ViewModel 在 IO 加载期间销毁，都不能泄漏 native 引擎。
                    unownedEngine?.release()
                }
            }
        }
    }

    private sealed interface LoadResult {
        data class Success(
            val engine: SenseVoiceRecognitionEngine,
            val loadMs: Long,
            val memoryDetail: String,
        ) : LoadResult

        data class Failure(val code: String, val detail: String) : LoadResult
    }

    /** 开始会话前先做模型门禁，再推进状态，最后才清理上一轮结果。 */
    fun startRecording(): String? {
        val engine = senseVoiceEngine ?: return engineIssue ?: ErrorCodes.ASR_MODEL_MISSING
        if (!sessionController.state.value.canStartNewSession) return ErrorCodes.SESSION_STALE
        if (!sessionController.startSession()) return ErrorCodes.SESSION_STALE
        val token = sessionController.currentToken()
            ?: return ErrorCodes.SESSION_STALE
        val runtime = SessionRuntime(token, engine, System.currentTimeMillis())
        currentRuntime = runtime

        audioPlayback.stop()
        audioFocusManager.abandonFocus()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        lastCapturedSegment = null
        lastRecognition = null
        lastQuality = null

        val failure = audioCapture.start(token.sessionId)
        if (failure != null) {
            sessionController.fail(token, ProcessingStage.CAPTURE, failure)
            recordMetrics(runtime, failed = true, cancelled = false)
            return failure
        }

        DiagnosticLog.i("Session", "会话开始, id=" + token.sessionId.take(8))
        return null
    }

    /** 每次会话使用独立停止锁；所有异步结果都同时校验 sessionId 与 generation。 */
    fun stopRecordingAndProcess() {
        val runtime = currentRuntime ?: return
        val token = runtime.token
        if (!sessionController.isCurrent(token)) return
        if (!stopGate.tryBegin(token)) {
            DiagnosticLog.w("Main", "忽略同一会话的重复停止请求")
            return
        }
        if (!sessionController.stopCapture(token)) {
            stopGate.end(token)
            return
        }

        runtime.recognitionJob = viewModelScope.launch {
            try {
                val captureStart = System.currentTimeMillis()
                when (val result = withContext(Dispatchers.IO) { audioCapture.stop() }) {
                    is AudioCapture.CaptureResult.Failure -> {
                        failRecognition(runtime, ProcessingStage.CAPTURE, result.code)
                    }
                    is AudioCapture.CaptureResult.Success -> {
                        processCapture(runtime, result.segment, captureStart)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DiagnosticLog.e("Main", "识别流程异常: " + error.javaClass.simpleName)
                failRecognition(runtime, ProcessingStage.RECOGNITION, ErrorCodes.ASR_DECODE_FAILED)
            } finally {
                stopGate.end(token)
            }
        }
    }

    private suspend fun processCapture(runtime: SessionRuntime, segment: AudioSegment, captureStart: Long) {
        val token = runtime.token
        if (!sessionController.isCurrent(token)) return
        lastCapturedSegment = segment
        runtime.stageStart["capture"] = System.currentTimeMillis() - captureStart

        if (segment.qualityFlags.contains(AudioCapture.FLAG_EMPTY_CAPTURE)) {
            failRecognition(runtime, ProcessingStage.CAPTURE, AudioCapture.FLAG_EMPTY_CAPTURE)
            return
        }

        val quality = AudioQuality.analyze(AudioQuality.toFloatSamples(segment.samples))
        lastQuality = quality
        if (quality.verdict == AudioQuality.Verdict.SILENT) {
            failRecognition(runtime, ProcessingStage.CAPTURE, ErrorCodes.CAPTURE_SILENT)
            return
        }

        val recognitionStart = System.currentTimeMillis()
        val recognition = withContext(Dispatchers.IO) {
            runtime.engine.recognize(
                RecognitionRequest(
                    sessionId = token.sessionId,
                    audio = segment,
                    engineVersion = ENGINE_VERSION,
                ),
            )
        }
        val recognitionMs = System.currentTimeMillis() - recognitionStart
        if (!sessionController.isCurrent(token)) return
        if (recognition.sessionId != token.sessionId) {
            failRecognition(runtime, ProcessingStage.RECOGNITION, ErrorCodes.SESSION_STALE)
            return
        }
        if (!recognition.isSuccess) {
            failRecognition(
                runtime,
                ProcessingStage.RECOGNITION,
                recognition.errorCode ?: ErrorCodes.ASR_DECODE_FAILED,
            )
            return
        }
        if (recognition.warnings.isNotEmpty()) {
            failRecognition(runtime, ProcessingStage.RECOGNITION, recognition.warnings.first())
            return
        }
        if (recognition.normalizedText.isBlank()) {
            failRecognition(runtime, ProcessingStage.RECOGNITION, ErrorCodes.ASR_DECODE_FAILED)
            return
        }

        runtime.stageStart["recognition"] = recognitionMs
        lastBenchmark = PerformanceBenchmark.recordRecognition(
            recognitionMs = recognitionMs,
            audioMs = segment.durationMs,
            modelLoadMs = modelLoadMs,
            coldStartMs = coldStartMs,
        )
        lastRecognition = recognition
        if (!sessionController.onRecognitionReady(token)) return

        benchmarkAggregator.add(
            BenchmarkAggregator.Sample(
                captureMs = runtime.stageStart["capture"] ?: 0L,
                recognitionMs = recognitionMs,
                synthesisMs = 0L,
                playbackMs = 0L,
                totalMs = (runtime.stageStart["capture"] ?: 0L) + recognitionMs,
                audioMs = segment.durationMs,
                sessionId = token.sessionId,
            ),
        )
        _recognitionEvents.trySend(
            RecognitionOutcome.Success(token.sessionId, token.generation, recognition, segment),
        )
    }

    private fun failRecognition(runtime: SessionRuntime, stage: ProcessingStage, code: String) {
        if (!sessionController.fail(runtime.token, stage, code)) return
        DiagnosticLog.e("Main", "识别流程失败, stage=" + stage + ", code=" + code)
        recordMetrics(runtime, failed = true, cancelled = false)
        _recognitionEvents.trySend(
            RecognitionOutcome.Error(runtime.token.sessionId, runtime.token.generation, code),
        )
    }

    /** 合成前取得焦点；每次播放有独立 generation，旧任务不能清理新任务。 */
    fun synthesizeAndPlay(confirmedText: String) {
        val runtime = currentRuntime ?: return
        val token = runtime.token
        if (!sessionController.isCurrent(token)) return

        runtime.playbackJob?.cancel()
        val playbackGeneration = ++runtime.playbackGeneration
        runtime.originalPlaying = false
        audioPlayback.stop()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        runtime.playbackJob = viewModelScope.launch {
            var success = false
            try {
                if (!audioFocusManager.requestFocus()) {
                    failPlayback(runtime, playbackGeneration, ProcessingStage.PLAYBACK, ErrorCodes.FOCUS_DENIED)
                    return@launch
                }
                val synthesisStart = System.currentTimeMillis()
                val result = synthesisEngine.synthesize(
                    SynthesisRequest(
                        sessionId = token.sessionId,
                        confirmedText = confirmedText,
                        voiceProfileRef = null,
                        engineVersion = ENGINE_VERSION,
                    ),
                )
                runtime.stageStart["synthesis"] = System.currentTimeMillis() - synthesisStart
                if (!sessionController.isCurrent(token) || !ownsPlayback(runtime, playbackGeneration)) {
                    return@launch
                }
                if (result.sessionId != token.sessionId || result.warnings.isNotEmpty()) {
                    failPlayback(
                        runtime,
                        playbackGeneration,
                        ProcessingStage.SYNTHESIS,
                        result.warnings.firstOrNull() ?: ErrorCodes.SESSION_STALE,
                    )
                    return@launch
                }
                if (!sessionController.onSynthesisReady(token)) return@launch

                val playbackStart = System.currentTimeMillis()
                val audio = result.audio
                val played = if (audio == null) {
                    true // AndroidTtsEngine.synthesize 仅在系统 TTS onDone 后返回。
                } else {
                    withContext(Dispatchers.IO) { audioPlayback.play(audio) }
                }
                runtime.stageStart["playback"] = System.currentTimeMillis() - playbackStart
                if (!sessionController.isCurrent(token) || !ownsPlayback(runtime, playbackGeneration)) {
                    return@launch
                }
                if (!played) {
                    failPlayback(runtime, playbackGeneration, ProcessingStage.PLAYBACK, ErrorCodes.TTS_SPEAK_FAILED)
                    return@launch
                }

                audioFocusManager.abandonFocus()
                if (!sessionController.onPlaybackFinished(token)) return@launch
                recordMetrics(runtime, failed = false, cancelled = false)
                success = true
                sendPlayback(runtime, playbackGeneration, success = true, original = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DiagnosticLog.e("Main", "合成/播放异常: " + error.javaClass.simpleName)
                failPlayback(runtime, playbackGeneration, ProcessingStage.PLAYBACK, ErrorCodes.TTS_SPEAK_FAILED)
            } finally {
                if (currentRuntime === runtime && ownsPlayback(runtime, playbackGeneration)) {
                    audioFocusManager.abandonFocus()
                    if (!success) (synthesisEngine as? AndroidTtsEngine)?.stop()
                }
            }
        }
    }

    private fun ownsPlayback(runtime: SessionRuntime, generation: Long): Boolean =
        runtime.playbackGeneration == generation

    private fun sendPlayback(
        runtime: SessionRuntime,
        generation: Long,
        success: Boolean,
        original: Boolean,
    ) {
        if (!ownsPlayback(runtime, generation)) return
        _playbackEvents.trySend(
            PlaybackOutcome(runtime.token.sessionId, runtime.token.generation, success, original),
        )
    }

    private fun failPlayback(
        runtime: SessionRuntime,
        playbackGeneration: Long,
        stage: ProcessingStage,
        code: String,
    ) {
        if (!ownsPlayback(runtime, playbackGeneration)) return
        if (!sessionController.fail(runtime.token, stage, code)) return
        audioPlayback.stop()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        audioFocusManager.abandonFocus()
        recordMetrics(runtime, failed = true, cancelled = false)
        sendPlayback(runtime, playbackGeneration, success = false, original = false)
    }

    /** 播放原声也返回真实结果，且与系统 TTS 互斥。 */
    fun playOriginal() {
        if (sessionController.state.value.isBusy) return
        val segment = lastCapturedSegment ?: return
        val runtime = currentRuntime?.takeIf { it.token.sessionId == segment.sessionId } ?: return
        runtime.playbackJob?.cancel()
        val playbackGeneration = ++runtime.playbackGeneration
        runtime.originalPlaying = true
        runtime.playbackJob = viewModelScope.launch {
            try {
                (synthesisEngine as? AndroidTtsEngine)?.stop()
                audioPlayback.stop()
                if (!audioFocusManager.requestFocus()) {
                    sendPlayback(runtime, playbackGeneration, success = false, original = true)
                    return@launch
                }
                val played = withContext(Dispatchers.IO) { audioPlayback.play(segment) }
                sendPlayback(runtime, playbackGeneration, played, original = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                sendPlayback(runtime, playbackGeneration, success = false, original = true)
            } finally {
                if (currentRuntime === runtime && ownsPlayback(runtime, playbackGeneration)) {
                    audioFocusManager.abandonFocus()
                    runtime.originalPlaying = false
                }
            }
        }
    }

    /** 延迟焦点事件不得终止未持焦点的录音；真正丢焦先取消等待中的 TTS。 */
    fun handleFocusLost() {
        if (audioFocusManager.hasFocus()) return
        val runtime = currentRuntime ?: return
        when (sessionController.state.value) {
            SessionState.PROCESSING_SYNTHESIZING, SessionState.PLAYING -> {
                runtime.playbackJob?.cancel()
                failPlayback(
                    runtime,
                    runtime.playbackGeneration,
                    ProcessingStage.PLAYBACK,
                    ErrorCodes.FOCUS_DENIED,
                )
            }
            else -> if (runtime.originalPlaying) {
                val generation = runtime.playbackGeneration
                runtime.playbackJob?.cancel()
                runtime.originalPlaying = false
                audioPlayback.stop()
                (synthesisEngine as? AndroidTtsEngine)?.stop()
                audioFocusManager.abandonFocus()
                sendPlayback(runtime, generation, success = false, original = true)
            }
        }
    }

    /** 取消立即使令牌失效，再取消任务与释放资源；指标只记录一次。 */
    fun cancelSession() {
        val runtime = currentRuntime
        val token = runtime?.token ?: sessionController.currentToken() ?: return
        val transitioned = sessionController.cancel(token)
        runtime?.recognitionJob?.cancel()
        runtime?.playbackJob?.cancel()
        audioPlayback.stop()
        audioFocusManager.abandonFocus()
        audioCapture.cancel()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        if (transitioned) {
            runtime?.let { recordMetrics(it, failed = false, cancelled = true) }
            DiagnosticLog.i("Main", "会话已取消")
        }
    }

    /** 应用音源成功后才持久化，拒绝切换时设置保持不变。 */
    fun applyAudioSource(option: AudioSourceOption = settings.audioSource): Boolean {
        if (sessionController.state.value.isBusy) {
            DiagnosticLog.w("Main", "会话进行中，拒绝切换麦克风音源")
            return false
        }
        audioCapture.cancel()
        audioCapture = AudioCapture(getApplication(), sourceOption = option)
        settings.audioSource = option
        DiagnosticLog.i("Main", "已应用麦克风音源: " + option.id)
        return true
    }

    fun recordMetrics(confidence: Float? = null, failed: Boolean, cancelled: Boolean) {
        val runtime = currentRuntime ?: return
        recordMetrics(runtime, confidence, failed, cancelled)
    }

    private fun recordMetrics(
        runtime: SessionRuntime,
        confidence: Float? = null,
        failed: Boolean,
        cancelled: Boolean,
    ) {
        if (!runtime.metricsRecorded.compareAndSet(false, true)) return
        val metrics = SessionMetrics(
            sessionId = runtime.token.sessionId,
            captureMs = runtime.stageStart["capture"] ?: 0L,
            recognitionMs = runtime.stageStart["recognition"] ?: 0L,
            synthesisMs = runtime.stageStart["synthesis"] ?: 0L,
            playbackMs = runtime.stageStart["playback"] ?: 0L,
            totalMs = System.currentTimeMillis() - runtime.startedAt,
            outcome = metricsCollector.deriveOutcome(confidence, failed, cancelled),
            modelVersion = ENGINE_VERSION + "+" + runtime.engine.engineId,
            qualityFlags = lastCapturedSegment
                ?.takeIf { it.sessionId == runtime.token.sessionId }
                ?.qualityFlags
                .orEmpty(),
        )
        metricsCollector.record(metrics)
        benchmarkAggregator.amend(
            sessionId = runtime.token.sessionId,
            synthesisMs = metrics.synthesisMs,
            playbackMs = metrics.playbackMs,
            totalMs = metrics.totalMs,
        )
        runtime.stageStart.clear()
        DiagnosticLog.i("Metrics", "指标记录, outcome=" + metrics.outcome)
    }

    /** 兼容既有诊断调用；新代码应使用会话内部阶段表。 */
    fun markStage(name: String, elapsedMs: Long) {
        currentRuntime?.stageStart?.set(name, elapsedMs)
    }

    val engineId: String
        get() = activeEngine.engineId

    override fun onCleared() {
        cancelSession()
        engineLoadJob?.cancel()
        audioPlayback.stop()
        audioCapture.cancel()
        audioFocusManager.abandonFocus()
        (synthesisEngine as? AndroidTtsEngine)?.release()
        val engine = senseVoiceEngine
        senseVoiceEngine = null
        val recognitionJob = currentRuntime?.recognitionJob
        if (engine != null) {
            if (recognitionJob?.isActive == true) {
                // 协程取消不会中断 native decode；等任务真正退出后再释放 recognizer。
                recognitionJob.invokeOnCompletion { engine.release() }
            } else {
                engine.release()
            }
        }
        _recognitionEvents.close()
        _playbackEvents.close()
        super.onCleared()
    }

    sealed interface RecognitionOutcome {
        val sessionId: String
        val generation: Long

        data class Success(
            override val sessionId: String,
            override val generation: Long,
            val recognition: RecognitionResult,
            val segment: AudioSegment,
        ) : RecognitionOutcome

        data class Error(
            override val sessionId: String,
            override val generation: Long,
            val code: String,
        ) : RecognitionOutcome
    }

    companion object {
        const val ENGINE_VERSION = "v0.11"
    }
}
