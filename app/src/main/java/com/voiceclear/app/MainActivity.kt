package com.voiceclear.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.capture.CaptureActivity
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AndroidTtsEngine
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.engine.AudioSourceOption
import com.voiceclear.app.engine.AudioFocusManager
import com.voiceclear.app.session.DiagnosticExporter
import com.voiceclear.app.session.DiagnosticLog
import com.voiceclear.app.session.ErrorCodes
import com.voiceclear.app.session.PerformanceBenchmark
import com.voiceclear.app.session.ProcessingStage
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionMetrics
import com.voiceclear.app.session.SessionState
import java.util.Locale

/**
 * VoiceClear 主界面（P1 可测原型）。
 *
 * 交互遵循技术指导文件 1.3 节：
 * 录音 → 识别 → 可选确认文本 → 合成播放 → 可播放原始语音。
 * 低置信度时明确提示，默认不静默播放未经确认的内容。
 */
class MainActivity : ComponentActivity() {

    /**
     * 主流程 ViewModel。
     *
     * 承载会话状态机、引擎生命周期与流程编排；
     * Activity 只负责把状态映射到视图。
     * 跨配置变更存活，旋转屏幕不会中断正在进行的识别。
     */
    private val viewModel: MainViewModel by viewModels()

    private val sessionController get() = viewModel.sessionController
    private val audioCapture get() = viewModel.audioCapture
    private val audioPlayback get() = viewModel.audioPlayback

    /**
     * 真实识别引擎。模型未下载时为 null，界面会引导用户下载，
     * **不会退回假引擎**（否则用户会误以为看到的是真实识别结果）。
     */
    private val senseVoiceEngine get() = viewModel.senseVoiceEngine
    private val modelDownloadManager get() = viewModel.modelDownloadManager
    private val lastBenchmark get() = viewModel.lastBenchmark
    private val synthesisEngine get() = viewModel.synthesisEngine
    private val audioFocusManager get() = viewModel.audioFocusManager
    private val metricsCollector get() = viewModel.metricsCollector

    /** 计时锚点：记录各阶段耗时，用于端到端延迟测量。 */

    private lateinit var stateText: TextView
    private lateinit var warningText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private lateinit var originalButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var audioSettingsButton: MaterialButton
    private lateinit var textLabel: TextView
    private lateinit var recognizedText: EditText
    private lateinit var confidenceText: TextView

    private val lastCapturedSegment get() = viewModel.lastCapturedSegment
    private val lastRecognition get() = viewModel.lastRecognition

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                showError(getString(R.string.permission_denied))
            }
        }

    /**
     * 诊断日志导出：使用系统文件选择器（SAF），用户自行选择保存位置，
     * 因此不需要任何存储权限。
     */
    private val exportLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                DiagnosticLog.i("Log", "用户取消了诊断导出")
                return@registerForActivityResult
            }
            val saved = runCatching {
                val bytes = DiagnosticExporter.buildArchive(
                    context = applicationContext,
                    metricsProvider = object : DiagnosticExporter.MetricsProvider {
                        override fun exportMetrics(): String = metricsCollector.exportJson()
                    },
                    benchmarkProvider = object : DiagnosticExporter.BenchmarkProvider {
                        override fun exportBenchmark(): String {
                            val record = lastBenchmark
                            return if (record == null) {
                                "{\"note\":\"本次尚无识别记录\"}"
                            } else {
                                record.toJson()
                            }
                        }
                    },
                    benchmarkSummaryProvider =
                        object : DiagnosticExporter.BenchmarkSummaryProvider {
                            override fun exportBenchmarkSummary(): String =
                                viewModel.benchmarkAggregator.snapshot().toJson()
                        },
                )
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(bytes)
                } ?: error("无法打开输出流")
            }
            if (saved.isSuccess) {
                DiagnosticLog.i("Log", "诊断包已导出")
                AlertDialog.Builder(this)
                    .setTitle(R.string.export_log_title)
                    .setMessage(R.string.export_log_saved)
                    .setPositiveButton(R.string.capture_confirm, null)
                    .show()
            } else {
                DiagnosticLog.e("Log", "诊断导出失败: " + (saved.exceptionOrNull()?.message ?: "未知错误"))
                showError(getString(R.string.export_log_failed))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 引擎与状态由 ViewModel 持有；这里只做一次性准备。
        viewModel.coldStartMs = VoiceClearApplication.uptimeMs()
        viewModel.ensureRecognitionEngine()

        // 焦点被抢占（来电、其他媒体）时立即停止播放并回到可重试状态。
        audioFocusManager.onFocusLost = {
            runOnUiThread {
                audioPlayback.stop()
                if (sessionController.state.value.isBusy) {
                    sessionController.fail(ProcessingStage.PLAYBACK, AudioFocusManager.ERR_FOCUS_DENIED)
                    showError(getString(R.string.state_error))
                }
            }
        }

        stateText = findViewById(R.id.stateText)
        warningText = findViewById(R.id.warningText)
        recordButton = findViewById(R.id.recordButton)
        confirmButton = findViewById(R.id.confirmButton)
        originalButton = findViewById(R.id.originalButton)
        cancelButton = findViewById(R.id.cancelButton)
        audioSettingsButton = findViewById(R.id.audioSettingsButton)
        textLabel = findViewById(R.id.textLabel)
        recognizedText = findViewById(R.id.recognizedText)
        confidenceText = findViewById(R.id.confidenceText)

        recordButton.setOnClickListener { onRecordClicked() }
        findViewById<MaterialButton>(R.id.captureModeButton).setOnClickListener {
            startActivity(android.content.Intent(this, CaptureActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.exportLogButton).setOnClickListener {
            DiagnosticLog.i("Log", "请求导出诊断日志, 当前条数=" + DiagnosticLog.size())
            exportLogLauncher.launch("voiceclear-log-" + System.currentTimeMillis() + ".txt")
        }
        findViewById<MaterialButton>(R.id.modelSetupButton).setOnClickListener {
            startActivity(android.content.Intent(this, ModelSetupActivity::class.java))
        }
        refreshModelStatus()
        confirmButton.setOnClickListener { onConfirmClicked() }
        originalButton.setOnClickListener { onPlayOriginalClicked() }
        cancelButton.setOnClickListener { onCancelClicked() }
        audioSettingsButton.setOnClickListener { showAudioSourceDialog() }

        renderState(sessionController.state.value)
    }

    /**
     * 准备识别引擎。
     *
     * 模型未就绪时**不使用假引擎冒充真实识别**：
     * - 保持 activeEngine 为假引擎，但界面上会明确提示"模型未就绪"，
     *   并在点击录音时引导用户去下载；
     * - 这样既不会崩溃，也不会让用户误判结果来源。
     */
    private fun ensureRecognitionEngine() {
        viewModel.ensureRecognitionEngine()
    }

    /** 刷新界面上的模型状态提示。 */
    private fun refreshModelStatus() {
        val statusView = findViewById<TextView>(R.id.modelStatusText)
        val setupButton = findViewById<MaterialButton>(R.id.modelSetupButton)
        if (senseVoiceEngine != null) {
            statusView.text = getString(
                R.string.model_status_ready,
                String.format(
                    java.util.Locale.CHINA,
                    "%.1f MB",
                    modelDownloadManager.diskUsage() / 1024.0 / 1024.0,
                ),
            )
            setupButton.visibility = View.GONE
        } else if (viewModel.engineIssue == ErrorCodes.ASR_INSUFFICIENT_MEMORY) {
            // 内存不足时引导去下载页是错的——模型已经下载好了，缺的是内存。
            // 这里给可行动的提示，并隐藏下载按钮。
            statusView.setText(R.string.engine_memory_insufficient)
            setupButton.visibility = View.GONE
        } else {
            statusView.setText(R.string.model_status_missing)
            setupButton.visibility = View.VISIBLE
        }
    }

    private fun onRecordClicked() {
        val state = sessionController.state.value
        if (state == SessionState.RECORDING) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                showError(getString(R.string.permission_denied))
                return
            }
            // 防御：状态机说在录，但采集器其实已经空了。
            // 这通常意味着上一轮的状态没同步，直接调用 stop 只会得到
            // capture_not_recording 并弹出一个与用户操作无关的错误。
            if (!viewModel.audioCapture.isRecording()) {
                DiagnosticLog.w(
                    "Main",
                    "状态与采集实况不一致（state=RECORDING 但采集器空闲），已重置会话",
                )
                viewModel.cancelSession()
                renderState(sessionController.state.value)
                return
            }
            stopRecordingAndProcess()
            return
        }
        if (!hasRecordPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (senseVoiceEngine == null) {
            // 模型未就绪时不启动录音，避免产出无法识别的无效音频。
            DiagnosticLog.w(
                "Main",
                "模型未就绪，阻止录音, 原因=" + (viewModel.engineIssue ?: "unknown"),
            )
            if (viewModel.engineIssue == ErrorCodes.ASR_INSUFFICIENT_MEMORY) {
                // 模型齐备但内存不够：跳下载页没有意义，直接给出处理建议。
                showError(getString(R.string.engine_memory_insufficient))
                return
            }
            showError(getString(R.string.engine_not_ready_warning))
            startActivity(android.content.Intent(this, ModelSetupActivity::class.java))
            return
        }
        startRecording()
    }

    /** Lint 可识别的标准权限检查形式，避免运行时权限被撤销导致的崩溃。 */
    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val failure = viewModel.startRecording()
        if (failure != null) {
            DiagnosticLog.e("Main", "录音启动失败: " + failure)
            // 状态机不允许启动时，区分"确认页误点"与真实失败：
            // 前者给出可行动的指引，后者保持原提示。
            val state = sessionController.state.value
            val message = when (state) {
                SessionState.AWAITING_CONFIRMATION, SessionState.PLAYING ->
                    getString(R.string.session_in_progress)
                else -> getString(R.string.error_capture_failed)
            }
            showError(message)
            return
        }
        recognizedText.setText("")
        renderState(SessionState.RECORDING)
    }

    private fun stopRecordingAndProcess() {
        recordButton.isEnabled = false

        viewModel.stopRecordingAndProcess { outcome ->
            // 一律按状态机的真实状态渲染，不用字面量。
            // 曾经的写法在这里传 PROCESSING_RECOGNIZING，界面看着对，
            // 但状态机若没同步就会停在 RECORDING，
            // 用户再点一次按钮时又被当成"还在录音"去停止，弹出假的"录音失败"。
            renderState(sessionController.state.value)
            recordButton.isEnabled = true
            when (outcome) {
                is MainViewModel.RecognitionOutcome.Error -> {
                    // 按错误码给出**可行动**的提示，而不是笼统的「录音失败」。
                    val message = when (outcome.code) {
                        ErrorCodes.CAPTURE_SILENT -> getString(R.string.capture_silent)
                        AudioCapture.FLAG_EMPTY_CAPTURE ->
                            getString(R.string.no_speech_detected)
                        else -> getString(R.string.error_capture_failed)
                    }
                    DiagnosticLog.e("Main", "识别流程错误: " + outcome.code + " - " +
                        ErrorCodes.describe(outcome.code))
                    showError(message)
                }

                is MainViewModel.RecognitionOutcome.Success -> {
                    val recognition = outcome.recognition
                    // 录音达到上限会自动停止采集：用户往往不知道已停止、
                    // 继续说的话没被录进去，这里明确告知。
                    if (outcome.segment.durationMs >= AudioCapture.MAX_DURATION_MS - 200) {
                        showError(getString(R.string.recording_limit_reached))
                    }
                    recognizedText.setText(recognition.normalizedText)
                    renderRecognition(recognition)
                    if (recognition.normalizedText.isBlank()) {
                        // 区分「没录到声音」与「录到了但识别不出」：
                        // 前者由采集层的质量标记报告，后者是这个提示。
                        DiagnosticLog.w(
                            "Main",
                            "识别结果为空, 录音时长=" + outcome.segment.durationMs + "ms",
                        )
                        showError(getString(R.string.recognition_empty))
                    }
                }
            }
        }
    }

    private fun onConfirmClicked() {
        val sessionId = sessionController.sessionId ?: return
        val confirmed = recognizedText.text?.toString()?.trim().orEmpty()
        if (confirmed.isEmpty()) {
            showError(getString(R.string.no_speech_detected))
            return
        }
        if (!sessionController.onConfirmed()) return
        renderState(SessionState.PROCESSING_SYNTHESIZING)

        viewModel.synthesizeAndPlay(confirmed) { success ->
            runOnUiThread {
                if (!success) {
                    renderState(SessionState.ERROR)
                    return@runOnUiThread
                }
                renderState(SessionState.COMPLETED)
            }
        }
    }

    private fun onPlayOriginalClicked() {
        viewModel.playOriginal {
            runOnUiThread { renderState(sessionController.state.value) }
        }
    }

    /**
     * 显示麦克风音源选择对话框。
     *
     * **为什么要让用户自己选**：哪种音源对「发音不清」的人更好，
     * 无法由开发者替目标人群决定——AGC 对发音清晰的人是加分，
     * 对发音不清的人可能反而削弱了关键的辅音线索。
     * 只有让用户在真实设备上用同一句话对比测试，才能得出结论。
     */
    private fun showAudioSourceDialog() {
        val settings = viewModel.settings
        val options = AudioSourceOption.ALL
        val labels = options.map { it.displayName }.toTypedArray()
        val current = options.indexOf(settings.audioSource)

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_source_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val option = options[which]
                settings.audioSource = option
                if (!viewModel.applyAudioSource()) {
                    // 录音进行中被拒绝：提示用户先停止，避免以为已生效
                    showError(getString(R.string.audio_source_busy))
                    return@setSingleChoiceItems
                }
                audioSettingsButton.text = getString(
                    R.string.audio_source_current,
                    option.displayName,
                )
                Toast.makeText(
                    this,
                    getString(R.string.audio_source_saved, option.displayName),
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
            .setMessage(R.string.audio_source_help)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onCancelClicked() {
        // 停止播放与焦点释放属于会话取消的一部分，
        // 由 ViewModel 统一处理，Activity 不重复做一遍。
        viewModel.cancelSession()
        renderState(SessionState.CANCELLED)
        viewModel.recordMetrics(failed = false, cancelled = true)
    }

    private fun renderRecognition(recognition: RecognitionResult) {
        val confidence = recognition.confidence
        if (confidence == null) {
            confidenceText.text = getString(
                R.string.confidence_format,
                getString(R.string.label_confidence),
                getString(R.string.confidence_unknown),
            )
        } else {
            confidenceText.text = getString(
                R.string.confidence_format,
                getString(R.string.label_confidence),
                String.format(Locale.getDefault(), "%.0f%%", confidence * 100),
            )
        }
        confidenceText.visibility = View.VISIBLE

        val lowConfidence = confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD
        warningText.visibility = if (lowConfidence) View.VISIBLE else View.GONE
        if (lowConfidence) {
            warningText.text = getString(R.string.warning_low_confidence)
        }
        renderState(SessionState.AWAITING_CONFIRMATION)
    }

    private fun renderState(state: SessionState) {
        stateText.setText(
            when (state) {
                SessionState.IDLE -> R.string.state_idle
                SessionState.RECORDING -> R.string.state_recording
                SessionState.PROCESSING_RECOGNIZING,
                SessionState.PROCESSING_SYNTHESIZING,
                -> R.string.state_processing

                SessionState.AWAITING_CONFIRMATION -> R.string.state_awaiting
                SessionState.PLAYING -> R.string.state_playing
                SessionState.COMPLETED -> R.string.state_completed
                SessionState.CANCELLED, SessionState.ERROR -> R.string.state_error
            },
        )

        recordButton.setText(
            if (state == SessionState.RECORDING) R.string.btn_stop else R.string.btn_start,
        )
        recordButton.isEnabled = state.isBusy.not() ||
            state == SessionState.RECORDING

        val showConfirm = state == SessionState.AWAITING_CONFIRMATION
        confirmButton.visibility = if (showConfirm) View.VISIBLE else View.GONE
        textLabel.visibility = if (showConfirm) View.VISIBLE else View.GONE
        recognizedText.visibility = if (showConfirm) View.VISIBLE else View.GONE
        confidenceText.visibility = if (showConfirm) View.VISIBLE else View.GONE
        originalButton.visibility = if (lastCapturedSegment != null) View.VISIBLE else View.GONE
        cancelButton.visibility = if (state.isBusy || showConfirm) View.VISIBLE else View.GONE
    }

    /**
     * 记录一次会话的本地指标。
     *
     * 只保存耗时与结果状态，不包含原始音频和完整文本（技术指导文件 7.1 节）。
     */

    private fun showError(message: String) {
        DiagnosticLog.e("Error", message)
        warningText.text = message
        warningText.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 从模型设置页返回后，若引擎尚未就绪则重新尝试加载。
        if (senseVoiceEngine == null) {
            ensureRecognitionEngine()
        }
        refreshModelStatus()
    }

    override fun onDestroy() {
        audioFocusManager.abandonFocus()
        (synthesisEngine as? AndroidTtsEngine)?.release()
        super.onDestroy()
    }

    companion object {
        private const val ENGINE_VERSION = "fake-0.1.0"
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f
    }
}
