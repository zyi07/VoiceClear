package com.voiceclear.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voiceclear.app.engine.AndroidTtsEngine
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioQuality
import com.voiceclear.app.engine.AudioFocusManager
import com.voiceclear.app.engine.AudioPlayback
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.FakeRecognitionEngine
import com.voiceclear.app.engine.ModelDownloadManager
import com.voiceclear.app.engine.RecognitionEngine
import com.voiceclear.app.engine.RecognitionEngineFactory
import com.voiceclear.app.engine.RecognitionRequest
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.engine.SenseVoiceRecognitionEngine
import com.voiceclear.app.engine.SynthesisEngine
import com.voiceclear.app.engine.SynthesisRequest
import com.voiceclear.app.session.AppSettings
import com.voiceclear.app.session.DiagnosticLog
import com.voiceclear.app.session.ErrorCodes
import com.voiceclear.app.session.MetricsCollector
import com.voiceclear.app.session.ProcessingStage
import com.voiceclear.app.session.PerformanceBenchmark
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionMetrics
import com.voiceclear.app.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主流程 ViewModel。
 *
 * **为什么抽出来**：`MainActivity` 一度增长到 642 行，混合了视图绑定、
 * 会话状态机、引擎生命周期、协程编排与指标采集五类职责，
 * 任何一处改动都要在长方法里定位，阅读与测试成本都很高。
 *
 * 本类承载**流程与状态**，Activity 只负责把状态映射到视图。
 *
 * **生命周期约定**：ViewModel 跨配置变更（旋转屏幕）存活，
 * 因此引擎持有与录音会话在这里是安全的——旋转屏幕不会中断正在进行的识别。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /** 会话状态机。 */
    val sessionController = SessionController()

    /** 应用设置。 */
    val settings = AppSettings(application)

    /**
     * 麦克风采集器。
     *
     * 音源由用户设置决定，因此是 var——切换音源后需要重建。
     */
    var audioCapture = AudioCapture(application, sourceOption = settings.audioSource)
        private set

    /** 音频播放。 */
    val audioPlayback = AudioPlayback()

    /** 音频焦点管理。 */
    val audioFocusManager = AudioFocusManager(application)

    /** 语音合成引擎。 */
    val synthesisEngine: SynthesisEngine = AndroidTtsEngine(application)

    /** 模型下载管理。 */
    val modelDownloadManager = ModelDownloadManager(application)

    /** 指标收集。 */
    val metricsCollector = MetricsCollector()

    /** 真实识别引擎；模型未就绪时为 null。 */
    var senseVoiceEngine: SenseVoiceRecognitionEngine? = null
        private set

    /** 占位引擎，仅在模型未就绪时使用，界面会明确提示。 */
    private val fallbackEngine: RecognitionEngine = FakeRecognitionEngine()

    /** 当前生效的识别引擎。 */
    var activeEngine: RecognitionEngine = FakeRecognitionEngine()
        private set

    /** 停止流程是否正在执行（防止双击导致并发停止）。 */
    private val stopInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近一次采集的音频质量分析。 */
    var lastQuality: AudioQuality.Analysis? = null
        private set

    /** 最近一次识别的性能基准。 */
    var lastBenchmark: PerformanceBenchmark.Record? = null
        private set

    /** 模型加载耗时（毫秒）。 */
    var modelLoadMs: Long = 0L
        private set

    /** 冷启动耗时（毫秒）。 */
    var coldStartMs: Long = 0L

    /** 各阶段起始时刻。 */
    private val stageStart = mutableMapOf<String, Long>()

    /** 会话开始时刻。 */
    private var sessionStartAt = 0L

    /** 最近一次采集的音频。 */
    var lastCapturedSegment: AudioSegment? = null
        private set

    /** 最近一次识别结果。 */
    var lastRecognition: RecognitionResult? = null
        private set

    /**
     * 准备识别引擎。
     *
     * 模型未就绪时**不使用假引擎冒充真实识别**：
     * 保持 activeEngine 为占位引擎，但界面会明确提示"模型未就绪"。
     */
    fun ensureRecognitionEngine() {
        val created = RecognitionEngineFactory.create(getApplication())
        if (created == null) {
            activeEngine = fallbackEngine
            DiagnosticLog.w("Main", "识别模型未就绪，当前为占位引擎（界面会提示）")
            return
        }
        val loadStart = System.currentTimeMillis()
        val loaded = created.engine.load()
        modelLoadMs = System.currentTimeMillis() - loadStart
        if (loaded) {
            senseVoiceEngine = created.engine
            activeEngine = created.engine
            DiagnosticLog.i(
                "Main",
                "识别引擎就绪: " + created.engine.engineId + ", 加载耗时=" + modelLoadMs + "ms",
            )
        } else {
            activeEngine = fallbackEngine
            DiagnosticLog.e("Main", "识别引擎加载失败")
        }
    }

    /**
     * 开始一次会话并立即开始录音。
     *
     * @return 失败时的错误码；null 表示成功。
     */
    fun startRecording(): String? {
        audioPlayback.stop()
        audioFocusManager.abandonFocus()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        lastCapturedSegment = null
        lastRecognition = null

        if (!sessionController.startSession()) {
            return ErrorCodes.SESSION_STALE
        }

        sessionStartAt = System.currentTimeMillis()
        stageStart.clear()

        // 立即开始录音，调用方负责在用户再次点击时停止。
        val failure = audioCapture.start(sessionController.sessionId ?: "")
        if (failure != null) {
            sessionController.fail(ProcessingStage.CAPTURE, failure)
            return failure
        }

        DiagnosticLog.i(
            "Session",
            "会话开始, id=" + (sessionController.sessionId ?: "none").take(8),
        )
        return null
    }

    /**
     * 停止录音并执行识别。
     *
     * 流程：停止采集 → 识别 → 产出可确认的文本。
     *
     * @param onRecognized 识别完成回调（在主线程）
     */
    fun stopRecordingAndProcess(onRecognized: (RecognitionOutcome) -> Unit) {
        val sessionId = sessionController.sessionId ?: return

        // 幂等保护：按钮双击会让两个协程并发进入，
        // 第二个 audioCapture.stop() 会因 current 已被置空而返回
        // "capture_not_recording"，表现为识别完成后弹出一个莫名的错误。
        // 这里用原子标志保证同一时刻只有一个停止流程。
        if (!stopInFlight.compareAndSet(false, true)) {
            DiagnosticLog.w("Main", "忽略重复的停止请求")
            return
        }

        viewModelScope.launch {
            val captureStart = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                audioCapture.stop()
            }

            when (result) {
                is AudioCapture.CaptureResult.Failure -> {
                    DiagnosticLog.e("Main", "采集失败, code=" + result.code)
                    sessionController.fail(ProcessingStage.CAPTURE, result.code)
                    onRecognized(RecognitionOutcome.Error(result.code))
                    stopInFlight.set(false)
                }

                is AudioCapture.CaptureResult.Success -> {
                    val segment = result.segment
                    lastCapturedSegment = segment
                    stageStart["capture"] = System.currentTimeMillis() - captureStart

                    DiagnosticLog.i(
                        "Capture",
                        "采集完成, 耗时=" + stageStart["capture"] + "ms, 时长=" +
                            segment.durationMs + "ms, 采样率=" + segment.sampleRate +
                            ", 质量标记=" + segment.qualityFlags,
                    )

                    if (segment.qualityFlags.contains(AudioCapture.FLAG_EMPTY_CAPTURE)) {
                        sessionController.fail(
                            ProcessingStage.CAPTURE,
                            AudioCapture.FLAG_EMPTY_CAPTURE,
                        )
                        onRecognized(RecognitionOutcome.Error(AudioCapture.FLAG_EMPTY_CAPTURE))
                        stopInFlight.set(false)
                        return@launch
                    }

                    // **先做质量分析再送模型**。
                    // 实测发现大量录音几乎是静音，模型在这种输入上会产生
                    // 幻觉输出（识别出 <|en|> 等无意义内容），用户完全无法理解。
                    // 这里提前判定并给出可行动的中文提示。
                    val quality = AudioQuality.analyze(
                        AudioQuality.toFloatSamples(segment.samples),
                    )
                    lastQuality = quality
                    DiagnosticLog.i(
                        "Quality",
                        "音频质量: " + quality.verdict + ", RMS=" +
                            String.format(java.util.Locale.US, "%.4f", quality.rms) +
                            ", 峰值=" + String.format(java.util.Locale.US, "%.4f", quality.peak) +
                            ", 有效占比=" +
                            String.format(java.util.Locale.US, "%.2f", quality.activeRatio),
                    )

                    if (quality.verdict == AudioQuality.Verdict.SILENT) {
                        // 几乎肯定没有有效语音：不送去识别，
                        // 否则只会得到一句莫名其妙的输出。
                        sessionController.fail(
                            ProcessingStage.CAPTURE,
                            ErrorCodes.CAPTURE_SILENT,
                        )
                        onRecognized(RecognitionOutcome.Error(ErrorCodes.CAPTURE_SILENT))
                        stopInFlight.set(false)
                        return@launch
                    }

                    val recognitionStart = System.currentTimeMillis()
                    val recognition = activeEngine.recognize(
                        RecognitionRequest(
                            sessionId = sessionId,
                            audio = segment,
                            engineVersion = ENGINE_VERSION,
                        ),
                    )
                    val recognitionMs = System.currentTimeMillis() - recognitionStart

                    lastBenchmark = PerformanceBenchmark.recordRecognition(
                        recognitionMs = recognitionMs,
                        audioMs = segment.durationMs,
                        modelLoadMs = modelLoadMs,
                        coldStartMs = coldStartMs,
                    )

                    if (!sessionController.isCurrent(sessionId)) return@launch
                    stageStart["recognition"] = recognitionMs
                    lastRecognition = recognition
                    sessionController.onRecognitionReady()

                    DiagnosticLog.i(
                        "Recognition",
                        "识别完成, 耗时=" + recognitionMs + "ms, 文本长度=" +
                            recognition.normalizedText.length,
                    )
                    onRecognized(RecognitionOutcome.Success(recognition, segment))
                    stopInFlight.set(false)
                }
            }
        }
    }

    /** 取消当前会话。 */
    fun cancel() {
        audioCapture.cancel()
        sessionController.cancel()
        DiagnosticLog.i("Main", "会话已取消")
    }

    /**
     * 记录一次会话指标。
     */
    fun recordMetrics(confidence: Float? = null, failed: Boolean, cancelled: Boolean) {
        val recognitionMs = stageStart["recognition"] ?: 0L
        val synthesisMs = stageStart["synthesis"] ?: 0L
        val totalMs = if (sessionStartAt > 0) System.currentTimeMillis() - sessionStartAt else 0L

        val metrics = SessionMetrics(
            sessionId = sessionController.sessionId ?: "",
            captureMs = stageStart["capture"] ?: 0L,
            recognitionMs = recognitionMs,
            synthesisMs = synthesisMs,
            playbackMs = stageStart["playback"] ?: 0L,
            totalMs = totalMs,
            outcome = metricsCollector.deriveOutcome(confidence, failed, cancelled),
            modelVersion = ENGINE_VERSION + "+" + activeEngine.engineId,
            qualityFlags = lastCapturedSegment?.qualityFlags ?: emptyList(),
        )
        metricsCollector.record(metrics)

        DiagnosticLog.i(
            "Metrics",
            "指标记录, outcome=" + metrics.outcome + ", total=" + metrics.totalMs +
                "ms, capture=" + metrics.captureMs + "ms, recognition=" +
                metrics.recognitionMs + "ms, synthesis=" + metrics.synthesisMs + "ms",
        )

        stageStart.clear()
        sessionStartAt = 0L
    }

    /** 记录阶段耗时。 */
    fun markStage(name: String, elapsedMs: Long) {
        stageStart[name] = elapsedMs
    }

    /** 当前生效引擎的 ID（用于指标记录）。 */
    val engineId: String
        get() = activeEngine.engineId

    /**
     * 合成并播放确认后的文本。
     *
     * 流程：合成 → 申请音频焦点 → 播放 → 释放焦点 → 记录指标。
     * 焦点申请失败必须中止播放，否则来电等高优先级音频会与本应用叠加。
     *
     * @param confirmedText 用户确认的文本
     * @param onFinished 流程结束回调（主线程），携带是否成功
     */
    fun synthesizeAndPlay(confirmedText: String, onFinished: (Boolean) -> Unit) {
        val sessionId = sessionController.sessionId ?: return
        viewModelScope.launch {
            val synthesisStart = System.currentTimeMillis()
            val result = synthesisEngine.synthesize(
                SynthesisRequest(
                    sessionId = sessionId,
                    confirmedText = confirmedText,
                    voiceProfileRef = null,
                    engineVersion = ENGINE_VERSION,
                ),
            )
            val synthesisMs = System.currentTimeMillis() - synthesisStart
            DiagnosticLog.i(
                "Synthesis",
                "合成完成, 耗时=" + synthesisMs + "ms, 警告=" + result.warnings.joinToString(),
            )
            if (!sessionController.isCurrent(sessionId)) return@launch
            stageStart["synthesis"] = synthesisMs

            // 播放前先申请焦点：原生 TTS 直接发声，没有焦点会让来电等高优先级音频叠加。
            if (!audioFocusManager.requestFocus()) {
                sessionController.fail(ProcessingStage.PLAYBACK, AudioFocusManager.ERR_FOCUS_DENIED)
                recordMetrics(failed = true, cancelled = false)
                onFinished(false)
                return@launch
            }

            sessionController.onSynthesisReady()
            onStateChanged?.invoke(SessionState.PLAYING)
            val playbackStart = System.currentTimeMillis()

            val audio = result.audio
            if (audio == null) {
                // 原生 TTS 已在 synthesize() 内完成发声，此处等待引擎回调返回。
                if (result.warnings.isNotEmpty()) {
                    sessionController.fail(ProcessingStage.SYNTHESIS, result.warnings.first())
                    audioFocusManager.abandonFocus()
                    recordMetrics(failed = true, cancelled = false)
                    onFinished(false)
                    return@launch
                }
            } else {
                withContext(Dispatchers.IO) { audioPlayback.play(audio) }
            }

            stageStart["playback"] = System.currentTimeMillis() - playbackStart
            DiagnosticLog.i("Playback", "播放结束, 耗时=" + stageStart["playback"] + "ms")
            audioFocusManager.abandonFocus()
            if (sessionController.isCurrent(sessionId)) {
                sessionController.onPlaybackFinished()
            }
            recordMetrics(failed = false, cancelled = false)
            onFinished(true)
        }
    }

    /** 播放最近一次采集的原始语音。 */
    fun playOriginal(onFinished: () -> Unit) {
        val segment = lastCapturedSegment ?: return
        viewModelScope.launch {
            if (!audioFocusManager.requestFocus()) {
                onFinished()
                return@launch
            }
            withContext(Dispatchers.IO) { audioPlayback.play(segment) }
            audioFocusManager.abandonFocus()
            onFinished()
        }
    }

    /** 取消当前会话。 */
    fun cancelSession() {
        audioCapture.cancel()
        sessionController.cancel()
        DiagnosticLog.i("Main", "会话已取消")
    }

    /**
     * 应用新的麦克风音源设置。
     *
     * 需要重建采集器——AudioRecord 的音源在构造时确定，无法运行时切换。
     */
    fun applyAudioSource() {
        audioCapture = AudioCapture(
            getApplication(),
            sourceOption = settings.audioSource,
        )
        DiagnosticLog.i(
            "Main",
            "已应用麦克风音源: " + settings.audioSource.id,
        )
    }

    /** 状态变化回调，由 Activity 注册以驱动界面刷新。 */
    var onStateChanged: ((SessionState) -> Unit)? = null

    /** 识别流程的结果。 */
    sealed interface RecognitionOutcome {
        /** 识别成功。 */
        data class Success(
            val recognition: RecognitionResult,
            val segment: AudioSegment,
        ) : RecognitionOutcome

        /** 识别失败，携带错误码。 */
        data class Error(val code: String) : RecognitionOutcome
    }

    companion object {
        const val ENGINE_VERSION = "v0.7"
    }
}
