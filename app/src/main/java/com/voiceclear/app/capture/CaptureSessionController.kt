package com.voiceclear.app.capture

import java.util.UUID

/**
 * 采集会话控制器。
 *
 * 负责采集流程的状态推进：
 * 选择提示 → 录音 → 质量检查 → 意图确认 → 入库。
 *
 * 关键规则（协议第 4.2 节）：
 * - 自发表达必须由用户确认「我本来想说什么」，否则标记为 UNCONFIRMED，不计入正确率统计；
 * - 朗读场景使用批准的提示文本，参考来源为 APPROVED_PROMPT；
 * - 每条片段都必须带授权标记，未授权用途在入库时拒绝。
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

    var phase: Phase = Phase.IDLE
        private set

    var currentPrompt: CapturePrompt? = null
        private set

    /** 是否处于自发表达模式（无提示句）。 */
    val isSpontaneous: Boolean get() = currentPrompt == null

    /** 本次会话采集到的片段。 */
    fun collectedSegments(): List<CaptureSegment> = segments.toList()

    fun segmentCount(): Int = segments.size

    /**
     * 选择一条提示句并进入待录状态。
     *
     * 传入 null 表示进入**自发表达**模式：此时没有提示文本，
     * 录音后必须由用户确认意图基准，否则该片段不计入内容正确率统计。
     */
    fun selectPrompt(prompt: CapturePrompt?) {
        currentPrompt = prompt
        phase = Phase.PROMPT_SELECTED
    }

    /** 进入录音状态。 */
    fun startRecording() {
        require(phase == Phase.PROMPT_SELECTED || phase == Phase.SAVED) {
            "必须先选择提示句或进入自发表达模式才能开始录音"
        }
        phase = Phase.RECORDING
    }

    /**
     * 提交一条录音结果。
     *
     * @param durationMs 录音时长
     * @param qualityFlags 质量标记，如过短、空采集
     * @param intendedText 自发表达场景下用户确认的原话（朗读场景为 null）
     * @param confirmed 用户是否确认了意图基准
     */
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

        val segment = CaptureSegment(
            segmentId = "seg_" + UUID.randomUUID().toString().take(8),
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
            annotatorId = "self",
            guideVersion = "v1.0",
            consentFlags = consentFlags,
            audioFileName = "",
        )
        segments.add(segment)
        phase = if (scene == CaptureScene.SPONTANEOUS) Phase.AWAITING_INTENT else Phase.SAVED
        return segment
    }

    /** 确认自发表达的意图基准。 */
    fun confirmIntent(intendedText: String): CaptureSegment? {
        val last = segments.lastOrNull() ?: return null
        if (last.scene != CaptureScene.SPONTANEOUS) return null
        if (last.referenceSource != ReferenceSource.UNCONFIRMED) return last

        val trimmed = intendedText.trim()
        if (trimmed.isEmpty()) return null

        val updated = last.copy(
            referenceText = trimmed,
            referenceSource = ReferenceSource.USER_CONFIRMED,
        )
        segments[segments.lastIndex] = updated
        phase = Phase.SAVED
        return updated
    }

    /** 当前是否有待确认意图的片段。 */
    fun hasPendingIntent(): Boolean {
        return segments.any {
            it.scene == CaptureScene.SPONTANEOUS &&
                it.referenceSource == ReferenceSource.UNCONFIRMED
        }
    }

    /** 待确认意图的片段。 */
    fun pendingIntentSegment(): CaptureSegment? {
        return segments.lastOrNull {
            it.scene == CaptureScene.SPONTANEOUS &&
                it.referenceSource == ReferenceSource.UNCONFIRMED
        }
    }

    /** 采集是否达到协议要求的最低会话数提示（3 个不同日期）。 */
    fun guidanceNote(): String {
        val count = segments.size
        return when {
            count == 0 -> "请选择一条提示句开始采集"
            count < 5 -> "已采集 $count 条，建议每个会话至少 10 条（朗读 5 条 + 自发 5 条）"
            else -> "已采集 $count 条。注意：同一会话的片段必须整体留在同一划分中"
        }
    }
}
