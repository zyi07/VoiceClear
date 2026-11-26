package com.voiceclear.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.capture.CaptureActivity
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioSourceOption
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.session.DiagnosticExporter
import com.voiceclear.app.session.DiagnosticLog
import com.voiceclear.app.session.ErrorCodes
import com.voiceclear.app.session.SessionState
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** VoiceClear 主界面：只负责权限、视图渲染与把用户意图交给 ViewModel。 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val sessionController get() = viewModel.sessionController
    private val senseVoiceEngine get() = viewModel.senseVoiceEngine
    private val modelDownloadManager get() = viewModel.modelDownloadManager

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

    private var renderedRecognition: RecognitionResult? = null
    private val focusLostCallback: () -> Unit = {
        runOnUiThread { viewModel.handleFocusLost() }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // 与普通点击走完全相同的模型门禁，禁止权限回调绕过。
                onRecordClicked()
            } else {
                showError(getString(R.string.permission_denied))
            }
        }

    private val exportLogLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                DiagnosticLog.i("Log", "用户取消了诊断导出")
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = DiagnosticExporter.buildArchive(
                            context = applicationContext,
                            metricsProvider = object : DiagnosticExporter.MetricsProvider {
                                override fun exportMetrics(): String =
                                    viewModel.metricsCollector.exportJson()
                            },
                            benchmarkProvider = object : DiagnosticExporter.BenchmarkProvider {
                                override fun exportBenchmark(): String {
                                    val record = viewModel.lastBenchmark
                                    return record?.toJson() ?: "{\"note\":\"本次尚无识别记录\"}"
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
                }
                if (saved.isSuccess) {
                    DiagnosticLog.i("Log", "诊断包已导出")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.export_log_title)
                        .setMessage(R.string.export_log_saved)
                        .setPositiveButton(R.string.capture_confirm, null)
                        .show()
                } else {
                    DiagnosticLog.e(
                        "Log",
                        "诊断导出失败: " + (saved.exceptionOrNull()?.message ?: "未知错误"),
                    )
                    showError(getString(R.string.export_log_failed))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (viewModel.coldStartMs == 0L) {
            viewModel.coldStartMs = VoiceClearApplication.uptimeMs()
        }
        viewModel.ensureRecognitionEngine()
        viewModel.audioFocusManager.onFocusLost = focusLostCallback

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
            exportLogLauncher.launch(DiagnosticExporter.suggestedFileName())
        }
        findViewById<MaterialButton>(R.id.modelSetupButton).setOnClickListener {
            startActivity(android.content.Intent(this, ModelSetupActivity::class.java))
        }
        confirmButton.setOnClickListener { onConfirmClicked() }
        originalButton.setOnClickListener { viewModel.playOriginal() }
        cancelButton.setOnClickListener { viewModel.cancelSession() }
        audioSettingsButton.setOnClickListener { showAudioSourceDialog() }
        refreshModelStatus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sessionController.state.collect { state -> renderState(state) }
                }
                launch {
                    viewModel.engineRevision.collect { refreshModelStatus() }
                }
                launch {
                    viewModel.recognitionEvents.collect { outcome ->
                        if (outcome.sessionId != sessionController.sessionId ||
                            outcome.generation != sessionController.generation
                        ) {
                            return@collect
                        }
                        when (outcome) {
                            is MainViewModel.RecognitionOutcome.Error -> showRecognitionError(outcome.code)
                            is MainViewModel.RecognitionOutcome.Success -> {
                                if (outcome.segment.durationMs >= AudioCapture.MAX_DURATION_MS - 200) {
                                    showError(getString(R.string.recording_limit_reached))
                                }
                                renderRecognition(outcome.recognition)
                            }
                        }
                    }
                }
                launch {
                    viewModel.playbackEvents.collect { outcome ->
                        if (outcome.sessionId != sessionController.sessionId ||
                            outcome.generation != sessionController.generation
                        ) {
                            return@collect
                        }
                        if (!outcome.success) {
                            showError(getString(R.string.state_error))
                        }
                        renderState(sessionController.state.value)
                    }
                }
            }
        }
    }

    private fun refreshModelStatus() {
        if (!::stateText.isInitialized) return
        val statusView = findViewById<TextView>(R.id.modelStatusText)
        val setupButton = findViewById<MaterialButton>(R.id.modelSetupButton)
        if (senseVoiceEngine != null) {
            statusView.text = getString(
                R.string.model_status_ready,
                String.format(
                    Locale.CHINA,
                    "%.1f MB",
                    modelDownloadManager.diskUsage() / 1024.0 / 1024.0,
                ),
            )
            setupButton.visibility = View.GONE
        } else if (viewModel.engineIssue == ErrorCodes.ASR_INSUFFICIENT_MEMORY) {
            statusView.setText(R.string.engine_memory_insufficient)
            setupButton.visibility = View.GONE
        } else {
            statusView.setText(R.string.model_status_missing)
            setupButton.visibility = View.VISIBLE
        }
    }

    private fun onRecordClicked() {
        if (sessionController.state.value == SessionState.RECORDING) {
            if (!hasRecordPermission()) {
                showError(getString(R.string.permission_denied))
                return
            }
            // 自动停止后仍应读取已完成的缓冲区；真正的状态/采集失败由
            // AudioCapture.stop() 返回明确错误，不能在这里直接丢弃音频。
            viewModel.stopRecordingAndProcess()
            return
        }
        if (!hasRecordPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        val failure = viewModel.startRecording()
        if (failure != null) {
            DiagnosticLog.e("Main", "录音启动失败: " + failure)
            when {
                failure == ErrorCodes.ASR_MODEL_MISSING ||
                    failure == ErrorCodes.ASR_MODEL_LOAD_FAILED -> {
                    showError(getString(R.string.engine_not_ready_warning))
                    startActivity(android.content.Intent(this, ModelSetupActivity::class.java))
                }
                failure == ErrorCodes.ASR_INSUFFICIENT_MEMORY ->
                    showError(getString(R.string.engine_memory_insufficient))
                sessionController.state.value == SessionState.AWAITING_CONFIRMATION ->
                    showError(getString(R.string.session_in_progress))
                else -> showError(getString(R.string.error_capture_failed))
            }
            return
        }
        renderedRecognition = null
        recognizedText.setText("")
        warningText.tag = null
        warningText.visibility = View.GONE
    }

    private fun showRecognitionError(code: String) {
        val message = when (code) {
            ErrorCodes.CAPTURE_SILENT -> getString(R.string.capture_silent)
            AudioCapture.FLAG_EMPTY_CAPTURE -> getString(R.string.no_speech_detected)
            else -> getString(R.string.error_capture_failed)
        }
        DiagnosticLog.e("Main", "识别流程错误: " + code + " - " + ErrorCodes.describe(code))
        showError(message)
    }

    private fun onConfirmClicked() {
        val confirmed = recognizedText.text?.toString()?.trim().orEmpty()
        if (confirmed.isEmpty()) {
            showError(getString(R.string.no_speech_detected))
            return
        }
        val token = sessionController.currentToken() ?: return
        if (!sessionController.onConfirmed(token)) return
        viewModel.synthesizeAndPlay(confirmed)
    }

    private fun showAudioSourceDialog() {
        val settings = viewModel.settings
        val options = AudioSourceOption.ALL
        val labels = options.map { it.displayName }.toTypedArray()
        val current = options.indexOf(settings.audioSource)

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_source_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val option = options[which]
                if (!viewModel.applyAudioSource(option)) {
                    showError(getString(R.string.audio_source_busy))
                    return@setSingleChoiceItems
                }
                audioSettingsButton.text = getString(R.string.audio_source_current, option.displayName)
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

    private fun renderRecognition(recognition: RecognitionResult) {
        if (renderedRecognition == recognition) return
        renderedRecognition = recognition
        recognizedText.setText(recognition.normalizedText)
        val confidence = recognition.confidence
        confidenceText.text = getString(
            R.string.confidence_format,
            getString(R.string.label_confidence),
            if (confidence == null) {
                getString(R.string.confidence_unknown)
            } else {
                String.format(Locale.getDefault(), "%.0f%%", confidence * 100)
            },
        )
        val lowConfidence = confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD
        if (lowConfidence) {
            warningText.tag = WARNING_LOW_CONFIDENCE
            warningText.text = getString(R.string.warning_low_confidence)
            warningText.visibility = View.VISIBLE
        } else if (warningText.tag == WARNING_LOW_CONFIDENCE) {
            // 只清理本函数拥有的低置信度提示，不得擦掉录音上限或错误事件。
            warningText.tag = null
            warningText.visibility = View.GONE
        }
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
        // 按钮可用性完全由真实状态决定，不在异步回调中手工恢复。
        recordButton.isEnabled = state == SessionState.RECORDING || state.canStartNewSession

        val showConfirm = state == SessionState.AWAITING_CONFIRMATION
        confirmButton.visibility = if (showConfirm) View.VISIBLE else View.GONE
        textLabel.visibility = if (showConfirm) View.VISIBLE else View.GONE
        recognizedText.visibility = if (showConfirm) View.VISIBLE else View.GONE
        confidenceText.visibility = if (showConfirm) View.VISIBLE else View.GONE
        if (showConfirm) viewModel.lastRecognition?.let { renderRecognition(it) }
        originalButton.visibility =
            if (viewModel.lastCapturedSegment != null && !state.isBusy) View.VISIBLE else View.GONE
        cancelButton.visibility = if (state.isBusy || showConfirm) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        DiagnosticLog.e("Error", message)
        warningText.tag = WARNING_ERROR
        warningText.text = message
        warningText.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (senseVoiceEngine == null) viewModel.ensureRecognitionEngine()
        refreshModelStatus()
    }

    override fun onDestroy() {
        if (viewModel.audioFocusManager.onFocusLost === focusLostCallback) {
            viewModel.audioFocusManager.onFocusLost = null
        }
        super.onDestroy()
    }

    companion object {
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f
        private const val WARNING_LOW_CONFIDENCE = "low_confidence"
        private const val WARNING_ERROR = "error"
    }
}
