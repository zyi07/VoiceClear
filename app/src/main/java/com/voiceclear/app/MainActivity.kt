package com.voiceclear.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.capture.CaptureActivity
import com.voiceclear.app.engine.AndroidTtsEngine
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioFocusManager
import com.voiceclear.app.engine.AudioPlayback
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.FakeRecognitionEngine
import com.voiceclear.app.engine.RecognitionEngine
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.engine.RecognitionRequest
import com.voiceclear.app.engine.SynthesisEngine
import com.voiceclear.app.engine.SynthesisRequest
import com.voiceclear.app.session.MetricsCollector
import com.voiceclear.app.session.ProcessingStage
import com.voiceclear.app.session.SessionController
import com.voiceclear.app.session.SessionMetrics
import com.voiceclear.app.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * VoiceClear 主界面（P1 可测原型）。
 *
 * 交互遵循技术指导文件 1.3 节：
 * 录音 → 识别 → 可选确认文本 → 合成播放 → 可播放原始语音。
 * 低置信度时明确提示，默认不静默播放未经确认的内容。
 */
class MainActivity : ComponentActivity() {

    private lateinit var sessionController: SessionController
    private lateinit var audioCapture: AudioCapture
    private lateinit var audioPlayback: AudioPlayback

    private val recognitionEngine: RecognitionEngine = FakeRecognitionEngine()
    private lateinit var synthesisEngine: SynthesisEngine
    private lateinit var audioFocusManager: AudioFocusManager
    private val metricsCollector = MetricsCollector()

    /** 计时锚点：记录各阶段耗时，用于端到端延迟测量。 */
    private val stageStart = mutableMapOf<String, Long>()
    private var sessionStartAt = 0L

    private lateinit var stateText: TextView
    private lateinit var warningText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var confirmButton: MaterialButton
    private lateinit var originalButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var textLabel: TextView
    private lateinit var recognizedText: EditText
    private lateinit var confidenceText: TextView

    private var lastCapturedSegment: AudioSegment? = null
    private var lastRecognition: RecognitionResult? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                showError(getString(R.string.permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionController = SessionController()
        audioCapture = AudioCapture(applicationContext)
        audioPlayback = AudioPlayback()
        audioFocusManager = AudioFocusManager(applicationContext)
        synthesisEngine = AndroidTtsEngine(applicationContext)

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
        textLabel = findViewById(R.id.textLabel)
        recognizedText = findViewById(R.id.recognizedText)
        confidenceText = findViewById(R.id.confidenceText)

        recordButton.setOnClickListener { onRecordClicked() }
        findViewById<MaterialButton>(R.id.captureModeButton).setOnClickListener {
            startActivity(android.content.Intent(this, CaptureActivity::class.java))
        }
        confirmButton.setOnClickListener { onConfirmClicked() }
        originalButton.setOnClickListener { onPlayOriginalClicked() }
        cancelButton.setOnClickListener { onCancelClicked() }

        renderState(sessionController.state.value)
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
            stopRecordingAndProcess()
            return
        }
        if (!hasRecordPermission()) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    /** Lint 可识别的标准权限检查形式，避免运行时权限被撤销导致的崩溃。 */
    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        audioPlayback.stop()
        audioFocusManager.abandonFocus()
        (synthesisEngine as? AndroidTtsEngine)?.stop()
        lastCapturedSegment = null
        lastRecognition = null
        if (!sessionController.startSession()) {
            showError(getString(R.string.error_capture_failed))
            return
        }
        recognizedText.setText("")
        sessionStartAt = System.currentTimeMillis()
        stageStart.clear()
        renderState(SessionState.RECORDING)
    }

    private fun stopRecordingAndProcess() {
        val sessionId = sessionController.sessionId ?: return
        recordButton.isEnabled = false
        renderState(SessionState.PROCESSING_RECOGNIZING)

        lifecycleScope.launch {
            val captureStart = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                if (!hasRecordPermission()) {
                    AudioCapture.CaptureResult.Failure(AudioCapture.ERR_PERMISSION_DENIED)
                } else {
                    audioCapture.capture(sessionId)
                }
            }
            recordButton.isEnabled = true
            stageStart["capture"] = System.currentTimeMillis() - captureStart

            when (result) {
                is AudioCapture.CaptureResult.Failure -> {
                    sessionController.fail(ProcessingStage.CAPTURE, result.code)
                    showError(getString(R.string.error_capture_failed))
                }

                is AudioCapture.CaptureResult.Success -> {
                    val segment = result.segment
                    lastCapturedSegment = segment
                    if (segment.qualityFlags.contains(AudioCapture.FLAG_EMPTY_CAPTURE)) {
                        sessionController.fail(ProcessingStage.CAPTURE, AudioCapture.FLAG_EMPTY_CAPTURE)
                        showError(getString(R.string.no_speech_detected))
                        return@launch
                    }
                    val recognitionStart = System.currentTimeMillis()
                    val recognition = recognitionEngine.recognize(
                        RecognitionRequest(
                            sessionId = sessionId,
                            audio = segment,
                            engineVersion = ENGINE_VERSION,
                        ),
                    )
                    val recognitionMs = System.currentTimeMillis() - recognitionStart
                    if (!sessionController.isCurrent(sessionId)) return@launch
                    stageStart["recognition"] = recognitionMs
                    lastRecognition = recognition
                    sessionController.onRecognitionReady()
                    recognizedText.setText(recognition.normalizedText)
                    renderRecognition(recognition)
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

        lifecycleScope.launch {
            val synthesisStart = System.currentTimeMillis()
            val result = synthesisEngine.synthesize(
                SynthesisRequest(
                    sessionId = sessionId,
                    confirmedText = confirmed,
                    voiceProfileRef = null,
                    engineVersion = ENGINE_VERSION,
                ),
            )
            val synthesisMs = System.currentTimeMillis() - synthesisStart
            if (!sessionController.isCurrent(sessionId)) return@launch
            stageStart["synthesis"] = synthesisMs

            // 播放前先申请焦点：原生 TTS 直接发声，没有焦点会让来电等高优先级音频叠加。
            if (!audioFocusManager.requestFocus()) {
                sessionController.fail(ProcessingStage.PLAYBACK, AudioFocusManager.ERR_FOCUS_DENIED)
                showError(getString(R.string.state_error))
                recordMetrics(
                    sessionId = sessionId,
                    failed = true,
                    cancelled = false,
                    confidence = lastRecognition?.confidence,
                )
                return@launch
            }

            sessionController.onSynthesisReady()
            renderState(SessionState.PLAYING)
            val playbackStart = System.currentTimeMillis()

            val audio = result.audio
            if (audio == null) {
                // 原生 TTS 已在 synthesize() 内完成发声，此处等待引擎回调返回。
                if (result.warnings.isNotEmpty()) {
                    sessionController.fail(ProcessingStage.SYNTHESIS, result.warnings.first())
                    showError(getString(R.string.state_error))
                    audioFocusManager.abandonFocus()
                    recordMetrics(
                        sessionId = sessionId,
                        failed = true,
                        cancelled = false,
                        confidence = lastRecognition?.confidence,
                    )
                    return@launch
                }
            } else {
                withContext(Dispatchers.IO) { audioPlayback.play(audio) }
            }

            stageStart["playback"] = System.currentTimeMillis() - playbackStart
            audioFocusManager.abandonFocus()
            if (sessionController.isCurrent(sessionId)) {
                sessionController.onPlaybackFinished()
                renderState(SessionState.COMPLETED)
            }
            recordMetrics(
                sessionId = sessionId,
                failed = false,
                cancelled = false,
                confidence = lastRecognition?.confidence,
            )
        }
    }

    private fun onPlayOriginalClicked() {
        val segment = lastCapturedSegment ?: return
        lifecycleScope.launch {
            if (!audioFocusManager.requestFocus()) {
                showError(getString(R.string.state_error))
                return@launch
            }
            withContext(Dispatchers.IO) { audioPlayback.play(segment) }
            audioFocusManager.abandonFocus()
        }
    }

    private fun onCancelClicked() {
        audioPlayback.stop()
        audioFocusManager.abandonFocus()
        val sessionId = sessionController.sessionId
        sessionController.cancel()
        renderState(SessionState.CANCELLED)
        if (sessionId != null) {
            recordMetrics(
                sessionId = sessionId,
                failed = false,
                cancelled = true,
                confidence = lastRecognition?.confidence,
            )
        }
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
    private fun recordMetrics(
        sessionId: String,
        failed: Boolean,
        cancelled: Boolean,
        confidence: Float?,
    ) {
        val recognitionMs = stageStart["recognition"] ?: 0L
        val synthesisMs = stageStart["synthesis"] ?: 0L
        val totalMs = if (sessionStartAt > 0) System.currentTimeMillis() - sessionStartAt else 0L
        metricsCollector.record(
            SessionMetrics(
                sessionId = sessionId,
                captureMs = stageStart["capture"] ?: 0L,
                recognitionMs = recognitionMs,
                synthesisMs = synthesisMs,
                playbackMs = stageStart["playback"] ?: 0L,
                totalMs = totalMs,
                outcome = metricsCollector.deriveOutcome(confidence, failed, cancelled),
                modelVersion = ENGINE_VERSION + "+" + recognitionEngine.engineId,
                qualityFlags = lastCapturedSegment?.qualityFlags ?: emptyList(),
            ),
        )
        stageStart.clear()
        sessionStartAt = 0L
    }

    private fun showError(message: String) {
        warningText.text = message
        warningText.visibility = View.VISIBLE
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
