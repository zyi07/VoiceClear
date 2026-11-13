package com.voiceclear.app.capture

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.R
import com.voiceclear.app.engine.AudioCapture
import com.voiceclear.app.engine.AudioSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集模式界面。
 *
 * 流程：选择提示句 → 录音 → 质量检查 → （自发表达）意图确认 → 入库。
 *
 * 隐私与合规约束：
 * - 数据只写入应用私有目录，不联网；
 * - 导出仅含元数据，音频需单独授权；
 * - 每条片段带授权标记，未授权用途会被拒绝；
 * - 「清空采集数据」提供完整删除入口（撤回授权的实现基础）。
 */
class CaptureActivity : ComponentActivity() {

    private lateinit var store: CaptureStore
    private lateinit var audioCapture: AudioCapture
    private lateinit var controller: CaptureSessionController

    private lateinit var speakerInfo: TextView
    private lateinit var statsText: TextView
    private lateinit var guidanceText: TextView
    private lateinit var promptText: TextView
    private lateinit var promptNote: TextView
    private lateinit var statusText: TextView
    private lateinit var recordButton: MaterialButton
    private lateinit var promptButton: MaterialButton

    private var prompts: List<CapturePrompt> = emptyList()
    private var speakerId: String = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                performCapture()
            } else {
                statusText.setText(R.string.capture_permission_needed)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        store = CaptureStore(applicationContext)
        audioCapture = AudioCapture(applicationContext)

        speakerId = loadOrCreateSpeakerId()
        controller = CaptureSessionController(
            speakerId = speakerId,
            sessionDate = currentDate(),
        )

        speakerInfo = findViewById(R.id.captureSpeakerInfo)
        statsText = findViewById(R.id.captureStats)
        guidanceText = findViewById(R.id.captureGuidance)
        promptText = findViewById(R.id.currentPromptText)
        promptNote = findViewById(R.id.currentPromptNote)
        statusText = findViewById(R.id.captureStatus)
        recordButton = findViewById(R.id.captureRecordButton)
        promptButton = findViewById(R.id.promptButton)

        findViewById<MaterialButton>(R.id.captureSpontaneousButton).setOnClickListener {
            onSpontaneousRequested()
        }
        findViewById<MaterialButton>(R.id.captureExportButton).setOnClickListener {
            onExportClicked()
        }
        findViewById<MaterialButton>(R.id.captureClearButton).setOnClickListener {
            onClearClicked()
        }
        promptButton.setOnClickListener { onPickPromptClicked() }
        recordButton.setOnClickListener { onRecordClicked() }

        prompts = loadPrompts()
        statusText.setText(R.string.capture_status_idle)
        refresh()
    }

    private fun loadPrompts(): List<CapturePrompt> {
        return try {
            val text = assets.open(PromptLibrary.ASSET_PATH).bufferedReader().use { it.readText() }
            val parsed = PromptLibrary.parse(text)
            if (parsed.isEmpty()) {
                statusText.setText(R.string.capture_no_prompts)
            }
            parsed
        } catch (error: Exception) {
            statusText.setText(R.string.capture_no_prompts)
            emptyList()
        }
    }

    /** 说话人 ID 为本地随机值，与个人身份无关，且跨会话保持一致。 */
    private fun loadOrCreateSpeakerId(): String {
        val prefs = getSharedPreferences("voiceclear_capture", MODE_PRIVATE)
        val existing = prefs.getString("speaker_id", null)
        if (existing != null) return existing
        val created = "spk_" + java.util.UUID.randomUUID().toString().take(8)
        prefs.edit().putString("speaker_id", created).apply()
        return created
    }

    private fun currentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    }

    private fun onPickPromptClicked() {
        if (prompts.isEmpty()) {
            statusText.setText(R.string.capture_no_prompts)
            return
        }
        val grouped = PromptLibrary.groupByCategory(prompts)
        val labels = grouped.entries.map { (category, items) ->
            category + "（" + items.size + " 条）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_prompt_pick_title)
            .setItems(labels) { _, which ->
                val category = grouped.keys.sorted()[which]
                val items = grouped[category].orEmpty()
                if (items.isEmpty()) return@setItems
                showPromptList(items)
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun showPromptList(items: List<CapturePrompt>) {
        val labels = items.map { it.text }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_prompt_pick_title)
            .setItems(labels) { _, which ->
                controller.selectPrompt(items[which])
                promptText.text = items[which].text
                promptNote.text = items[which].note + "（" + items[which].id + "）"
                promptText.visibility = View.VISIBLE
                promptNote.visibility = View.VISIBLE
                recordButton.isEnabled = true
                statusText.setText(R.string.capture_status_idle)
                refresh()
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun onSpontaneousRequested() {
        controller.selectPrompt(null)  // 暂不支持，保留接口
        promptText.visibility = View.GONE
        promptNote.visibility = View.GONE
        recordButton.isEnabled = true
        statusText.setText(R.string.capture_status_idle)
    }

    private fun onRecordClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        performCapture()
    }

    private fun performCapture() {
        controller.startRecording()
        statusText.setText(R.string.capture_status_recording)
        recordButton.isEnabled = false
        val sessionId = controller.sessionId

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                audioCapture.capture(sessionId)
            }
            recordButton.isEnabled = true

            when (result) {
                is AudioCapture.CaptureResult.Failure -> {
                    statusText.setText(R.string.capture_permission_needed)
                }

                is AudioCapture.CaptureResult.Success -> {
                    onCaptured(result.segment)
                }
            }
        }
    }

    private fun onCaptured(segment: AudioSegment) {
        val qualityFlags = segment.qualityFlags
        val saved = controller.submitSegment(
            durationMs = segment.durationMs,
            sampleRate = segment.sampleRate,
            qualityFlags = qualityFlags,
            intendedText = null,
            confirmed = false,
            fatigueLevel = null,
            environment = "quiet",
            consentFlags = ConsentFlags.ALL,
        )

        val audioName = saved.segmentId + ".pcm"
        store.saveAudio(controller.sessionId, saved.segmentId, segment.samples)

        if (controller.hasPendingIntent()) {
            statusText.setText(R.string.capture_status_intent_needed)
            showIntentDialog()
        } else {
            statusText.setText(R.string.capture_status_saved)
        }
        refresh()
    }

    /** 自发表达需要用户确认「我本来想说的是」，否则该片段不计入正确率统计。 */
    private fun showIntentDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.capture_intent_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_intent_title)
            .setView(input)
            .setPositiveButton(R.string.capture_intent_confirm) { _, _ ->
                val text = input.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    controller.confirmIntent(text)
                    statusText.setText(R.string.capture_status_saved)
                }
                persistPendingSegments()
                refresh()
            }
            .setNegativeButton(R.string.capture_intent_skip) { _, _ ->
                statusText.setText(R.string.capture_status_intent_needed)
                persistPendingSegments()
                refresh()
            }
            .show()
    }

    /** 把已采集片段写入本地数据集目录。 */
    private fun persistPendingSegments() {
        for (segment in controller.collectedSegments()) {
            store.appendTranscript(controller.sessionId, segment)
        }
    }

    private fun onExportClicked() {
        persistPendingSegments()
        val stats = store.statistics()
        val manifest = CaptureManifest(
            datasetVersion = "v0.1.0",
            createdAt = currentDate(),
            cohortTarget = "听障相关发音不清的普通话成人",
            speakers = listOf(
                ManifestSpeakerEntry(
                    speakerId = speakerId,
                    severityLevel = "L2",
                    sessions = 1,
                    segments = stats.segments,
                ),
            ),
            splits = mapOf("train" to listOf(speakerId), "dev" to emptyList(), "test" to emptyList()),
            totals = ManifestTotals(
                speakers = 1,
                sessions = 1,
                segments = stats.segments,
                hours = stats.totalHours,
            ),
            annotationGuideVersion = CaptureStore.GUIDELINE_VERSION,
            licenseCode = "internal-research-only",
            commercialUse = false,
            redistribution = false,
            limitations = listOf(
                "样本量极小，任何统计结论仅作探索性观察",
                "严重度分层由顾问判定，App 内不自评",
                "尚未跨会话稳定性数据",
            ),
        )
        store.writeManifest(manifest)
        store.writeSplit("train", listOf(speakerId))
        store.writeSplit("dev", emptyList())
        store.writeSplit("test", emptyList())
        store.writeConsent(
            CaptureConsent(
                speakerId = speakerId,
                consentVersion = "v1.0",
                signedAt = currentDate(),
                grants = mapOf(
                    ConsentFlags.RESEARCH_USE to true,
                    ConsentFlags.TRAIN_USE to true,
                    ConsentFlags.CLOUD_UPLOAD to false,
                    ConsentFlags.VOICE_PROFILE to false,
                    ConsentFlags.ANONYMOUS_REPORT to true,
                ),
                withdrawnAt = null,
                withdrawalScopeNote = "撤回后不再用于后续训练；已进入全局模型的部分无法保证移除",
                compensation = "pending",
            ),
        )
        store.writeSpeaker(
            CaptureSpeaker(
                speakerId = speakerId,
                ageBand = "unspecified",
                gender = "unspecified",
                severityLevel = "L2",
                hearingAidWorn = false,
                hearingAidType = null,
                firstSessionDate = currentDate(),
                sessionCount = 1,
                speakingEnvironment = listOf("quiet"),
            ),
        )
        store.exportMetadata()
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_export_title)
            .setMessage(R.string.capture_export_message)
            .setPositiveButton(R.string.capture_confirm, null)
            .show()
    }

    private fun onClearClicked() {
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_clear_title)
            .setMessage(R.string.capture_clear_message)
            .setPositiveButton(R.string.capture_confirm) { _, _ ->
                store.clearAll()
                controller = CaptureSessionController(
                    speakerId = speakerId,
                    sessionDate = currentDate(),
                )
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
    }

    /** 会话 ID，供入库流水线关联片段。 */
    val currentSessionId: String get() = controller.sessionId
}
