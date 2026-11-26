package com.voiceclear.app.engine

/**
 * 识别会话档案，用于持久化用户选择并标记识别结果与指标。
 *
 * 两种档案共用同一个 SenseVoice 多语种模型，native `asrLanguage` 都固定为 `zh`。
 * 山东方言档案用于记录和对比该模型在山东方言语料上的实际表现，**不是方言专用模型**；
 * 这里不增加热词、文本替换或任何会伪装成方言识别能力的后处理。
 */
enum class SpeechProfile(
    /** 稳定标识，用于设置、识别结果和指标导出。 */
    val id: String,
    /** 两处设置界面共用的中文显示名。 */
    val displayName: String,
    /** 传给 SenseVoice native 配置的语言。 */
    val asrLanguage: String,
) {
    MANDARIN(id = "mandarin", displayName = "普通话", asrLanguage = "zh"),
    SHANDONG(id = "shandong", displayName = "山东方言", asrLanguage = "zh"),
    ;

    companion object {
        val ALL = listOf(MANDARIN, SHANDONG)
        val DEFAULT = MANDARIN

        /** 未保存、空值或旧版本未知标识都回退到普通话。 */
        fun fromId(id: String?): SpeechProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}
