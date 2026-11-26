package com.voiceclear.app.capture

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.R
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioSegment
import com.voiceclear.app.engine.SpeechProfile
import com.voiceclear.app.session.AppSettings
import com.voiceclear.app.session.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 研究采集界面。所有研究用途默认拒绝，只有用户逐项明确选择后才可录音和导出。 */
class CaptureActivity : ComponentActivity() {

    companion object {
        private val DIALECT_GROUPS = listOf("unspecified", "jilu", "zhongyuan", "jiaoliao")
    }

    private lateinit var store: CaptureStore
    private lateinit var audioCapture: AudioCapture
    private lateinit var controller: CaptureSessionController
    private lateinit var settings: AppSettings

    private lateinit var speakerInfo: TextView
    private lateinit var statsText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var promptText: TextView
    private lateinit var promptNote: TextView
    private lateinit var statusText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var speechProfileButton: MaterialButton
    private lateinit var dialectGroupButton: MaterialButton
    private lateinit var promptButton: MaterialButton
    private lateinit var spontaneousButton: MaterialButton
    private lateinit var exportButton: MaterialButton
    private lateinit var audioExportButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var withdrawButton: MaterialButton

    private var prompts: List<CapturePrompt> = emptyList()
    private var speakerId: String = ""
    private var dialectGroup: String = "unspecified"
    private var captureBusy = false
    private var stopping = false
    private var captureJob: Job? = null
    private var exportJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) performCapture() else statusText.setText(R.string.capture_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        store = CaptureStore(applicationContext)
        audioCapture = AudioCapture(applicationContext)
        settings = AppSettings(applicationContext)
        speakerId = loadOrCreateSpeakerId()
        dialectGroup = getSharedPreferences("voiceclear_capture", MODE_PRIVATE)
            .getString("dialect_group", null)
            ?.takeIf { it in DIALECT_GROUPS }
            ?: "unspecified"
        store.ensureConsent(speakerId)
        controller = newController()

        speakerInfo = findViewById(R.id.captureSpeakerInfo)
        statsText = findViewById(R.id.captureStats)
        guidanceText = findViewById(R.id.captureGuidance)
        promptText = findViewById(R.id.currentPromptText)
        promptNote = findViewById(R.id.currentPromptNote)
        statusText = findViewById(R.id.captureStatus)
        recordButton = findViewById(R.id.captureRecordButton)
        speechProfileButton = findViewById(R.id.speechProfileButton)
        dialectGroupButton = findViewById(R.id.dialectGroupButton)
        promptButton = findViewById(R.id.promptButton)
        spontaneousButton = findViewById(R.id.captureSpontaneousButton)
        exportButton = findViewById(R.id.captureExportButton)
        audioExportButton = findViewById(R.id.captureAudioExportButton)
        clearButton = findViewById(R.id.captureClearButton)
        withdrawButton = findViewById(R.id.captureWithdrawButton)

        spontaneousButton.setOnClickListener { onSpontaneousRequested() }
        exportButton.setOnClickListener { onExportClicked() }
        audioExportButton.setOnClickListener {
            if (!captureBusy) startActivity(android.content.Intent(this, AudioExportActivity::class.java))
        }
        clearButton.setOnClickListener { onClearClicked() }
        withdrawButton.setOnClickListener { onWithdrawClicked() }
        promptButton.setOnClickListener { onPickPromptClicked() }
        speechProfileButton.setOnClickListener { onSpeechProfileClicked() }
        dialectGroupButton.setOnClickListener { onDialectGroupClicked() }
        recordButton.setOnClickListener { onRecordClicked() }

        prompts = loadPrompts()
        statusText.setText(R.string.capture_status_idle)
        refresh()
    }

    private fun newController(): CaptureSessionController {
        val profile = settings.speechProfile
        return CaptureSessionController(
            speakerId = speakerId,
            sessionDate = currentDate(),
            speechProfileId = profile.id,
            localeTag = "zh-CN",
            dialectGroup = if (profile == SpeechProfile.SHANDONG) dialectGroup else "unspecified",
        )
    }

    private fun loadPrompts(): List<CapturePrompt> {
        return try {
            val profileId = settings.speechProfile.id
            val text = assets.open(PromptLibrary.assetPathFor(profileId)).bufferedReader().use { it.readText() }
            val parsed = PromptLibrary.parse(text)
            if (parsed.isEmpty() || PromptLibrary.missingCategories(parsed, profileId).isNotEmpty()) {
                statusText.setText(R.string.capture_no_prompts)
            }
            parsed
        } catch (error: Exception) {
            statusText.setText(R.string.capture_no_prompts)
            emptyList()
        }
    }

    private fun loadOrCreateSpeakerId(): String {
        val prefs = getSharedPreferences("voiceclear_capture", MODE_PRIVATE)
        val existing = prefs.getString("speaker_id", null)
        if (existing != null) return existing
        val created = "spk_" + java.util.UUID.randomUUID().toString().take(8)
        prefs.edit { putString("speaker_id", created) }
        return created
    }

    private fun currentDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    private fun currentTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun onSpeechProfileClicked() {
        if (captureBusy) return
        val profiles = SpeechProfile.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_speech_profile_title)
            .setItems(profiles.map { it.displayName }.toTypedArray()) { _, which ->
                if (captureBusy) return@setItems
                val selected = profiles[which]
                if (selected == settings.speechProfile) return@setItems
                settings.speechProfile = selected
                prompts = loadPrompts()
                rebuildControllerAfterMetadataChange()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun onDialectGroupClicked() {
        if (captureBusy || settings.speechProfile != SpeechProfile.SHANDONG) return
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_dialect_group_title)
            .setItems(DIALECT_GROUPS.map { dialectGroupLabel(it) }.toTypedArray()) { _, which ->
                if (captureBusy) return@setItems
                val selected = DIALECT_GROUPS[which]
                if (selected == dialectGroup) return@setItems
                dialectGroup = selected
                getSharedPreferences("voiceclear_capture", MODE_PRIVATE).edit {
                    putString("dialect_group", selected)
                }
                rebuildControllerAfterMetadataChange()
                if (selected == "unspecified") {
                    statusText.setText(R.string.capture_dialect_group_unspecified)
                }
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun rebuildControllerAfterMetadataChange() {
        controller = newController()
        promptText.visibility = View.GONE
        promptNote.visibility = View.GONE
        statusText.setText(R.string.capture_status_idle)
        refresh()
    }

    private fun dialectGroupLabel(id: String): String = getString(
        when (id) {
            "jilu" -> R.string.dialect_group_jilu
            "zhongyuan" -> R.string.dialect_group_zhongyuan
            "jiaoliao" -> R.string.dialect_group_jiaoliao
            else -> R.string.dialect_group_unspecified
        },
    )

    private fun onPickPromptClicked() {
        if (captureBusy) return
        if (prompts.isEmpty()) {
            statusText.setText(R.string.capture_no_prompts)
            return
        }
        val categories = PromptLibrary.groupByCategory(prompts).entries.toList()
        val labels = categories.map { (category, items) ->
            category + "（" + items.size + " 条）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_prompt_pick_title)
            .setItems(labels) { _, which ->
                if (!captureBusy) showPromptList(categories[which].value)
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun showPromptList(items: List<CapturePrompt>) {
        if (captureBusy || items.isEmpty()) return
        val labels = items.map { it.text }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_prompt_pick_title)
            .setItems(labels) { _, which ->
                if (captureBusy) return@setItems
                controller.selectPrompt(items[which])
                promptText.text = items[which].text
                promptNote.text = getString(
                    R.string.capture_prompt_note,
                    items[which].note,
                    items[which].id,
                )
                promptText.visibility = View.VISIBLE
                promptNote.visibility = View.VISIBLE
                statusText.setText(R.string.capture_status_idle)
                refresh()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun onSpontaneousRequested() {
        if (captureBusy) return
        controller.selectPrompt(null)
        promptText.visibility = View.GONE
        promptNote.visibility = View.GONE
        statusText.setText(R.string.capture_status_idle)
        refresh()
    }

    private fun onRecordClicked() {
        // 停止是安全操作；即使录音期间权限被系统撤回，也不能用权限请求挡住停止。
        if (controller.phase == CaptureSessionController.Phase.RECORDING) {
            performCapture()
            return
        }
        if (!ensureResearchConsent()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        performCapture()
    }

    /** 返回 true 表示已有明确研究授权；否则显示默认不勾选的授权设置。 */
    private fun ensureResearchConsent(): Boolean {
        if (store.hasResearchConsent(speakerId)) return true
        val existing = store.readConsent(speakerId) ?: store.ensureConsent(speakerId)
        if (existing.withdrawnAt != null) {
            statusText.setText(R.string.capture_withdrawn)
            return false
        }
        showConsentDialog(existing)
        return false
    }

    private fun showConsentDialog(existing: CaptureConsent) {
        val labels = arrayOf(
            getString(R.string.consent_research_use),
            getString(R.string.consent_train_use),
            getString(R.string.consent_cloud_upload),
            getString(R.string.consent_voice_profile),
            getString(R.string.consent_anonymous_report),
        )
        val checked = BooleanArray(ConsentFlags.ALL.size) {
            existing.grants[ConsentFlags.ALL[it]] == true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_consent_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.capture_consent_save) { _, _ ->
                val grants = ConsentFlags.ALL.mapIndexed { index, key ->
                    key to checked[index]
                }.toMap()
                val anyGranted = grants.any { it.value }
                val signedAt = when {
                    existing.signedAt.isNotBlank() -> existing.signedAt
                    anyGranted -> currentTimestamp()
                    else -> ""
                }
                store.writeConsent(existing.copy(grants = grants, signedAt = signedAt))
                statusText.setText(
                    if (grants[ConsentFlags.RESEARCH_USE] == true) {
                        R.string.capture_status_idle
                    } else {
                        R.string.capture_consent_needed
                    },
                )
                refresh()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun performCapture() {
        if (controller.phase == CaptureSessionController.Phase.RECORDING) {
            stopCapture()
            return
        }
        if (!store.hasResearchConsent(speakerId)) {
            statusText.setText(R.string.capture_consent_needed)
            return
        }
        startCapture()
    }

    private fun startCapture() {
        controller.startRecording()
        val failure = audioCapture.start(controller.sessionId)
        if (failure != null) {
            controller.rollbackRecordingStart()
            captureBusy = false
            DiagnosticLog.e("Capture", "录音启动失败, code=" + failure)
            statusText.setText(R.string.capture_start_failed)
            refresh()
            return
        }
        captureBusy = true
        stopping = false
        statusText.setText(R.string.capture_status_recording)
        refresh()
        DiagnosticLog.i("Capture", "录音已开始")
    }

    private fun stopCapture() {
        stopping = true
        refresh()
        captureJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { audioCapture.stop() }
            captureBusy = false
            stopping = false
            when (result) {
                is AudioCapture.CaptureResult.Failure -> {
                    controller.rollbackRecordingStart()
                    DiagnosticLog.e("Capture", "采集失败, code=" + result.code)
                    statusText.setText(R.string.capture_start_failed)
                }
                is AudioCapture.CaptureResult.Success -> onCaptured(result.segment)
            }
            refresh()
        }
    }

    private fun onCaptured(segment: AudioSegment) {
        val consentFlags = store.activeConsentFlags(speakerId)
        if (ConsentFlags.RESEARCH_USE !in consentFlags) {
            controller.rollbackRecordingStart()
            statusText.setText(R.string.capture_consent_needed)
            return
        }
        val saved = controller.submitSegment(
            durationMs = segment.durationMs,
            sampleRate = segment.sampleRate,
            qualityFlags = segment.qualityFlags,
            intendedText = null,
            confirmed = false,
            fatigueLevel = null,
            environment = "unspecified",
            consentFlags = consentFlags,
        )
        try {
            store.saveSegment(controller.sessionId, saved, segment.samples)
        } catch (error: Exception) {
            controller.discardSegment(saved.segmentId)
            statusText.setText(R.string.capture_save_failed)
            DiagnosticLog.e("Capture", "片段入库失败: " + error.message)
            return
        }
        DiagnosticLog.i(
            "Capture",
            "片段已入库, id=" + saved.segmentId + ", 场景=" + saved.scene.value +
                ", 参考来源=" + saved.referenceSource.value,
        )
        if (controller.hasPendingIntent()) {
            statusText.setText(R.string.capture_status_intent_needed)
            showIntentDialog()
        } else {
            statusText.setText(R.string.capture_status_saved)
        }
    }

    private fun showIntentDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.capture_intent_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_intent_title)
            .setView(input)
            .setPositiveButton(R.string.capture_intent_confirm) { _, _ ->
                val updated = controller.confirmIntent(input.text?.toString().orEmpty())
                if (updated != null) {
                    store.upsertTranscript(controller.sessionId, updated)
                    statusText.setText(R.string.capture_status_saved)
                }
                refresh()
            }
            .setNegativeButton(R.string.capture_intent_skip) { _, _ ->
                statusText.setText(R.string.capture_status_intent_needed)
                refresh()
            }
            .show()
    }

    private fun onExportClicked() {
        if (captureBusy || exportJob?.isActive == true) return
        exportButton.isEnabled = false
        exportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.exportMetadata() } }
            if (result.isSuccess) {
                val stats = store.statistics()
                DiagnosticLog.i("Capture", "元数据已导出, 有效片段数=" + stats.segments)
                AlertDialog.Builder(this@CaptureActivity)
                    .setTitle(R.string.capture_export_title)
                    .setMessage(R.string.capture_export_message)
                    .setPositiveButton(R.string.capture_confirm, null)
                    .show()
            } else {
                DiagnosticLog.e("Capture", "元数据导出失败: " + result.exceptionOrNull()?.message)
                statusText.setText(R.string.capture_export_failed)
            }
            refresh()
        }
    }

    private fun onWithdrawClicked() {
        if (captureBusy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_withdraw_title)
            .setMessage(R.string.capture_withdraw_message)
            .setPositiveButton(R.string.capture_confirm) { _, _ ->
                store.withdrawResearchData(speakerId, currentTimestamp())
                controller = newController()
                promptText.visibility = View.GONE
                promptNote.visibility = View.GONE
                statusText.setText(R.string.capture_withdrawn)
                refresh()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun onClearClicked() {
        if (captureBusy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_clear_title)
            .setMessage(R.string.capture_clear_message)
            .setPositiveButton(R.string.capture_confirm) { _, _ ->
                store.clearAll()
                store.ensureConsent(speakerId)
                controller = newController()
                statusText.setText(R.string.capture_status_idle)
                refresh()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun refresh() {
        speakerInfo.text = getString(R.string.capture_speaker_default, speakerId)
        val stats = store.statistics()
        statsText.text = getString(
            R.string.capture_stats_format,
            stats.segments,
            String.format(Locale.CHINA, "%.1f 小时", stats.totalHours),
        )
        guidanceText.text = controller.guidanceNote()
        val profile = settings.speechProfile
        speechProfileButton.text = getString(R.string.capture_speech_profile_format, profile.displayName)
        val shandong = profile == SpeechProfile.SHANDONG
        dialectGroupButton.visibility = if (shandong) View.VISIBLE else View.GONE
        if (shandong) {
            dialectGroupButton.text = getString(
                R.string.capture_dialect_group_format,
                dialectGroupLabel(dialectGroup),
            )
        }
        val withdrawn = store.readConsent(speakerId)?.withdrawnAt != null
        val selectable = controller.phase == CaptureSessionController.Phase.PROMPT_SELECTED ||
            controller.phase == CaptureSessionController.Phase.SAVED
        recordButton.isEnabled = when {
            stopping -> false
            controller.phase == CaptureSessionController.Phase.RECORDING -> true
            else -> !captureBusy && selectable && !withdrawn
        }
        recordButton.setText(
            if (controller.phase == CaptureSessionController.Phase.RECORDING) {
                R.string.capture_stop
            } else {
                R.string.capture_record
            },
        )
        listOf(
            promptButton,
            speechProfileButton,
            dialectGroupButton,
            spontaneousButton,
            exportButton,
            audioExportButton,
            clearButton,
            withdrawButton,
        ).forEach { it.isEnabled = !captureBusy }
        dialectGroupButton.isEnabled = !captureBusy && shandong
    }

    override fun onDestroy() {
        captureJob?.cancel()
        exportJob?.cancel()
        audioCapture.cancel()
        super.onDestroy()
    }

    val currentSessionId: String get() = controller.sessionId
}
