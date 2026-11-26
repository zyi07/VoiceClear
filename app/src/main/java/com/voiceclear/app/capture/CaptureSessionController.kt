package com.voiceclear.app.capture

import java.util.UUID

/**
 * 采集会话控制器。
 *
 * 负责采集流程的状态推进：选择提示 → 录音 → 质量检查 → 意图确认 → 入库。
 * 自发表达必须由用户确认意图基准；研究采集必须具有明确的 research_use 授权。
 */
class CaptureSessionController(
    val speakerId: String,
    val sessionId: String = UUID.randomUUID().toString(),
    val sessionDate: String,
) {

    enum class Phase {
        IDLE,
        PROMPT_SELECTED,
        RECORDING,
        AWAITING_INTENT,
        SAVED,
    }

    private val segments = mutableListOf<CaptureSegment>()
    private var phaseBeforeRecording: Phase = Phase.PROMPT_SELECTED

    var phase: Phase = Phase.IDLE
        private set

    var currentPrompt: CapturePrompt? = null
        private set

    val isSpontaneous: Boolean get() = currentPrompt == null

    fun collectedSegments(): List<CaptureSegment> = segments.toList()

    fun segmentCount(): Int = segments.size

    fun selectPrompt(prompt: CapturePrompt?) {
        check(phase != Phase.RECORDING) { "录音中不能切换提示句或采集模式" }
        currentPrompt = prompt
        phase = Phase.PROMPT_SELECTED
    }

    /** 进入录音状态；启动失败时调用 [rollbackRecordingStart]。 */
    fun startRecording() {
        require(phase == Phase.PROMPT_SELECTED || phase == Phase.SAVED) {
            "必须先选择提示句或进入自发表达模式才能开始录音"
        }
        phaseBeforeRecording = phase
        phase = Phase.RECORDING
    }

    /** AudioRecord 启动失败或停止失败时恢复，避免下一次点击触发非法状态崩溃。 */
    fun rollbackRecordingStart() {
        if (phase == Phase.RECORDING) {
            phase = phaseBeforeRecording
        }
    }

    fun submitSegment(
        durationMs: Long,
        sampleRate: Int,
        qualityFlags: List<String>,
        intendedText: String?,
        confirmed: Boolean,
        fatigueLevel: Int?,
        environment: String,
        consentFlags: List<String>,
    ): CaptureSegment {
        check(phase == Phase.RECORDING) { "只有录音结束后才能提交片段" }
        require(ConsentFlags.RESEARCH_USE in consentFlags) {
            "没有明确 research_use 授权，不得入库研究片段"
        }
        require(consentFlags.all { it in ConsentFlags.ALL }) { "包含未知授权用途" }

        val prompt = currentPrompt
        val scene = if (prompt == null) CaptureScene.SPONTANEOUS else CaptureScene.READ
        val referenceSource = when {
            prompt != null -> ReferenceSource.APPROVED_PROMPT
            confirmed -> ReferenceSource.USER_CONFIRMED
            else -> ReferenceSource.UNCONFIRMED
        }
        val referenceText = when {
            prompt != null -> prompt.text
            confirmed -> intendedText.orEmpty().trim()
            else -> ""
        }
        require(referenceSource != ReferenceSource.USER_CONFIRMED || referenceText.isNotEmpty()) {
            "已确认的自发表达必须有意图文本"
        }

        val segmentId = "seg_" + UUID.randomUUID().toString().take(8)
        val segment = CaptureSegment(
            segmentId = segmentId,
            sessionId = sessionId,
            speakerId = speakerId,
            sessionDate = sessionDate,
            scene = scene,
            promptId = prompt?.id,
            referenceText = referenceText,
            referenceSource = referenceSource,
            intendedText = intendedText,
            durationMs = durationMs,
            sampleRate = sampleRate,
            channels = 1,
            encoding = "pcm_s16",
            noiseEstimateDb = null,
            distanceRange = null,
            fatigueLevel = fatigueLevel,
            deviceModel = DeviceContext.deviceModel(),
            environment = environment,
            qualityFlags = qualityFlags,
            annotatorId = CaptureStore.ANNOTATOR_ID,
            guideVersion = CaptureStore.GUIDELINE_VERSION,
            consentFlags = consentFlags.distinct(),
            audioFileName = CaptureStore.audioRelativePath(sessionId, segmentId),
        )
        segments.add(segment)
        phase = if (scene == CaptureScene.SPONTANEOUS) Phase.AWAITING_INTENT else Phase.SAVED
        return segment
    }

    /** 存储失败时撤销内存中的片段，防止 UI 把未落盘音频显示为已保存。 */
    fun discardSegment(segmentId: String) {
        val index = segments.indexOfLast { it.segmentId == segmentId }
        if (index >= 0) {
            segments.removeAt(index)
            phase = Phase.PROMPT_SELECTED
        }
    }

    fun confirmIntent(intendedText: String): CaptureSegment? {
        val last = segments.lastOrNull() ?: return null
        if (last.scene != CaptureScene.SPONTANEOUS) return null
        if (last.referenceSource != ReferenceSource.UNCONFIRMED) return last

        val trimmed = intendedText.trim()
        if (trimmed.isEmpty()) return null

        val updated = last.copy(
            referenceText = trimmed,
            intendedText = trimmed,
            referenceSource = ReferenceSource.USER_CONFIRMED,
        )
        segments[segments.lastIndex] = updated
        phase = Phase.SAVED
        return updated
    }

    fun hasPendingIntent(): Boolean = pendingIntentSegment() != null

    fun pendingIntentSegment(): CaptureSegment? {
        return segments.lastOrNull {
            it.scene == CaptureScene.SPONTANEOUS &&
                it.referenceSource == ReferenceSource.UNCONFIRMED
        }
    }

    fun guidanceNote(): String {
        val count = segments.size
        return when {
            count == 0 -> "请选择一条提示句开始采集"
            count < 5 -> "已采集 $count 条，建议每个会话至少 10 条（朗读 5 条 + 自发 5 条）"
            else -> "已采集 $count 条。注意：同一会话的片段必须整体留在同一划分中"
        }
    }
}
