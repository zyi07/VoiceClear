package com.voiceclear.app.engine

import android.media.MediaRecorder

/**
 * 麦克风音源选项。
 *
 * **背景**：真机实测（华为 PLA-AL10 / Android 16）发现 7 次录音中 5 次近乎完全静音
 *（RMS < 0.001），模型在噪声上产生幻觉输出（识别出 `<|en|>` 等无意义内容）。
 * 用户反馈「环境音影响大」。
 *
 * **Android 各音源的差异**（官方文档措辞）：
 *
 * - `UNPROCESSED`：
 *   "tuned for unprocessed (raw) sound if available, behaves like DEFAULT otherwise"
 *   —— 原始声音，**不经过厂商的语音增强链路**。
 * - `VOICE_RECOGNITION`：
 *   "tuned for voice recognition" —— 为识别调优，通常**启用 AGC 与降噪**。
 * - `MIC`：普通麦克风，行为依设备而定。
 *
 * **对目标人群的取舍**：AGC 会把**微弱的辅音当成噪声压掉**。
 * 对发音清晰的人这是优点，对发音不清的人恰恰是有害的——
 * 这正是「环境音影响大」的可能机制之一。
 * 因此把音源做成**可切换**，让用户在自己的设备上实测哪种更合适，
 * 而不是由开发者替目标人群做这个假设。
 */
enum class AudioSourceOption(
    /** 稳定标识，用于持久化。 */
    val id: String,

    /** 显示名（中文），用于设置界面。 */
    val displayName: String,

    /** 说明文字，解释该音源的特点。 */
    val description: String,

    /** 对应的 MediaRecorder.AudioSource 常量。 */
    val sourceConstant: Int,
) {

    /**
     * 原始声音（不经厂商语音增强）。
     *
     * 对**发音不清**的人群通常更安全：不会被 AGC 把微弱辅音当成噪声压掉。
     * 但部分设备不支持，此时会退化为 DEFAULT 行为。
     */
    UNPROCESSED(
        id = "unprocessed",
        displayName = "原始声音（推荐）",
        description = "不经过厂商的自动增益与降噪，保留最原始的音量细节，" +
            "对发音偏弱的人更友好。部分设备不支持时会自动回退。",
        sourceConstant = MediaRecorder.AudioSource.UNPROCESSED,
    ),

    /**
     * 为语音识别调优（通常含 AGC 与降噪）。
     *
     * 在嘈杂环境中对**发音清晰**的说话人效果通常更好；
     * 但可能削弱目标人群的微弱辅音。
     */
    VOICE_RECOGNITION(
        id = "voice_recognition",
        displayName = "语音识别优化",
        description = "针对语音识别调优，嘈杂环境下更稳，但可能削弱较轻的发音。",
        sourceConstant = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    ),

    /** 普通麦克风，行为依设备而定。 */
    MIC(
        id = "mic",
        displayName = "普通麦克风",
        description = "系统默认的麦克风处理方式，由设备厂商决定。",
        sourceConstant = MediaRecorder.AudioSource.MIC,
    );

    companion object {
        /** 可选项列表（按推荐顺序）。 */
        val ALL = listOf(UNPROCESSED, VOICE_RECOGNITION, MIC)

        /** 默认选项。 */
        val DEFAULT = UNPROCESSED

        /** 按标识查找；找不到时回退到默认项。 */
        fun fromId(id: String?): AudioSourceOption {
            return ALL.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
