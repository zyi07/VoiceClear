package com.voiceclear.app.capture

import android.os.Build

/**
 * 采集模式的数据模型。
 *
 * 字段严格对齐 [docs/dataset-schema.md] 第 2 节，保证 App 导出的数据
 * 可被 [tools/validate_dataset.py] 直接校验，不做二次转换。
 *
 * 设计约束：
 * - 不记录姓名、手机号等直接标识符；说话人用本地随机 ID；
 * - 障碍严重度由专业顾问判定，App 内只作为**记录字段**，不提供自评入口；
 * - 每条片段都带授权标记，未授权的用途在导出时被拒绝。
 */
data class CaptureSegment(
    val segmentId: String,
    val sessionId: String,
    val speakerId: String,
    val sessionDate: String,
    val scene: CaptureScene,
    val promptId: String?,
    val referenceText: String,
    val referenceSource: ReferenceSource,
    val intendedText: String?,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val encoding: String,
    val noiseEstimateDb: Double?,
    val distanceRange: String?,
    val fatigueLevel: Int?,
    val deviceModel: String,
    val environment: String,
    val qualityFlags: List<String>,
    val annotatorId: String,
    val guideVersion: String,
    val consentFlags: List<String>,
    /** 相对于数据集根目录的逐段音频路径。 */
    val audioFileName: String,
    /** 保留策略删除音频后置为 false；记录保留作审计，但不再计入有效数据。 */
    val audioAvailable: Boolean = true,
    val deletionReason: String? = null,
)

/** 场景类型，对应 schema 的 scene 字段。 */
enum class CaptureScene(val value: String) {
    READ("read"),
    SPONTANEOUS("spontaneous"),
    DIALOGUE("dialogue"),
}

/** 意图基准来源，决定该片段能否计入内容正确率统计。 */
enum class ReferenceSource(val value: String) {
    /** 朗读场景：使用预先批准的提示文本。 */
    APPROVED_PROMPT("approved_prompt"),

    /** 自发表达：用户确认过「我本来想说的是」。 */
    USER_CONFIRMED("user_confirmed"),

    /** 意图基准未确认：不得计入正确率统计。 */
    UNCONFIRMED("unconfirmed"),
}

/** 授权用途标记，与 consent/<speakerId>.json 的 grants 对应。 */
object ConsentFlags {
    const val RESEARCH_USE = "research_use"
    const val TRAIN_USE = "train_use"
    const val CLOUD_UPLOAD = "cloud_upload"
    const val VOICE_PROFILE = "voice_profile"
    const val ANONYMOUS_REPORT = "anonymous_report"

    /** 全部已知用途仅用于渲染选项；绝不代表默认授权。 */
    val ALL = listOf(RESEARCH_USE, TRAIN_USE, CLOUD_UPLOAD, VOICE_PROFILE, ANONYMOUS_REPORT)
}

/** 说话人元数据。 */
data class CaptureSpeaker(
    val speakerId: String,
    val ageBand: String,
    val gender: String,
    val severityLevel: String,
    val severitySource: String = SEVERITY_SOURCE_UNSPECIFIED,
    val hearingAidWorn: Boolean,
    val hearingAidType: String?,
    val firstSessionDate: String,
    val sessionCount: Int,
    val speakingEnvironment: List<String>,
) {
    companion object {
        const val SEVERITY_SOURCE_ADVISOR = "professional_advisor"
        const val SEVERITY_SOURCE_UNSPECIFIED = "unspecified"
        const val SEVERITY_LEVEL_UNSPECIFIED = "unspecified"

        /** 严重度合法取值。 */
        val SEVERITY_LEVELS = listOf("L1", "L2", "L3")
    }
}

/** 设备与环境的客观记录。 */
object DeviceContext {
    fun deviceModel(): String = Build.MODEL ?: "unknown"

    fun manufacturer(): String = Build.MANUFACTURER ?: "unknown"

    fun osVersion(): String = Build.VERSION.RELEASE ?: "unknown"

    fun sdkInt(): Int = Build.VERSION.SDK_INT
}
