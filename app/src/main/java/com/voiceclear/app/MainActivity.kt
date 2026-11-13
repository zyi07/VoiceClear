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
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioPlayback
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.FakeRecognitionEngine
import com.voiceclear.app.engine.FakeSynthesisEngine
import com.voiceclear.app.engine.RecognitionResult
import com.voiceclear.app.engine.SynthesisRequest
import com.voiceclear.app.engine.RecognitionRequest
import com.voiceclear.app.session.ProcessingStage
import com.voiceclear.app.session.SessionController
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

    private val recognitionEngine = FakeRecognitionEngine()
    private val synthesisEngine = FakeSynthesisEngine()

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
        lastCapturedSegment = null
        lastRecognition = null
        if (!sessionController.startSession()) {
            showError(getString(R.string.error_capture_failed))
            return
        }
        recognizedText.setText("")
        renderState(SessionState.RECORDING)
    }

    private fun stopRecordingAndProcess() {
        val sessionId = sessionController.sessionId ?: return
        recordButton.isEnabled = false
        renderState(SessionState.PROCESSING_RECOGNIZING)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!hasRecordPermission()) {
                    AudioCapture.CaptureResult.Failure(AudioCapture.ERR_PERMISSION_DENIED)
                } else {
                    audioCapture.capture(sessionId)
                }
            }
            recordButton.isEnabled = true

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
                    val recognition = recognitionEngine.recognize(
                        RecognitionRequest(
                            sessionId = sessionId,
                            audio = segment,
                            engineVersion = ENGINE_VERSION,
                        ),
                    )
                    if (!sessionController.isCurrent(sessionId)) return@launch
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
            val result = synthesisEngine.synthesize(
                SynthesisRequest(
                    sessionId = sessionId,
                    confirmedText = confirmed,
                    voiceProfileRef = null,
                    engineVersion = ENGINE_VERSION,
                ),
            )
            if (!sessionController.isCurrent(sessionId)) return@launch
            val audio = result.audio
            if (audio == null) {
                sessionController.fail(ProcessingStage.SYNTHESIS, "synthesis_empty_audio")
                showError(getString(R.string.state_error))
                return@launch
            }
            sessionController.onSynthesisReady()
            renderState(SessionState.PLAYING)
            withContext(Dispatchers.IO) { audioPlayback.play(audio) }
            if (sessionController.isCurrent(sessionId)) {
                sessionController.onPlaybackFinished()
                renderState(SessionState.COMPLETED)
            }
        }
    }

    private fun onPlayOriginalClicked() {
        val segment = lastCapturedSegment ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { audioPlayback.play(segment) }
        }
    }

    private fun onCancelClicked() {
        audioPlayback.stop()
        sessionController.cancel()
        renderState(SessionState.CANCELLED)
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

    private fun showError(message: String) {
        warningText.text = message
        warningText.visibility = View.VISIBLE
    }

    companion object {
        private const val ENGINE_VERSION = "fake-0.1.0"
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f
    }
}
