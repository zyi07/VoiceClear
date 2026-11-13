package com.voiceclear.app.engine

import com.voiceclear.app.session.SessionMetrics

/**
 * 识别引擎选择策略。
 *
 * 不同方案在离线能力、置信度、麦克风占用和包体上差异显著，
 * 必须在部署前显式选择，并让降级逻辑知道当前方案的边界（技术指导文件 5.6 节）。
 *
 * 关键约束（已核实）：
 * - Android 系统 SpeechRecognizer 会自行占用麦克风（VoiceCommunication 流），
 *   与本应用的 AudioCapture 互斥，不能与自有采集链并行使用；
 * - 系统 SpeechRecognizer 的离线能力因设备与语言包而异，无法保证在所有设备上离线可用；
 * - 因此正式方案应使用自带的端侧模型（sherpa-onnx 类）处理自有采集到的音频。
 */
enum class RecognitionEngineOption(
    val engineId: String,
    val capabilities: EngineCapabilities,
    val note: String,
) {
    /** 假引擎：仅用于确定性测试与联调，不产生任何真实识别。 */
    FAKE(
        engineId = "fake-0.1.0",
        capabilities = EngineCapabilities(
            engineId = "fake-0.1.0",
            offline = true,
            providesConfidence = false,
            ownsMicrophone = false,
            preservesSpeakerIdentity = false,
        ),
        note = "测试替身，不可用于评估真实识别质量",
    ),

    /** 系统 SpeechRecognizer：零依赖，但占用麦克风且离线能力不保证。 */
    SYSTEM(
        engineId = "android-speech",
        capabilities = EngineCapabilities(
            engineId = "android-speech",
            offline = false,
            providesConfidence = false,
            ownsMicrophone = true,
            preservesSpeakerIdentity = false,
        ),
        note = "占用麦克风，与自有采集链互斥；离线能力依设备而定",
    ),

    /** 端侧离线模型：可处理自有采集的音频，是正式方案的候选。 */
    ONNX_OFFLINE(
        engineId = "sherpa-onnx-zh",
        capabilities = EngineCapabilities(
            engineId = "sherpa-onnx-zh",
            offline = true,
            providesConfidence = false,
            ownsMicrophone = false,
            preservesSpeakerIdentity = false,
        ),
        note = "端侧离线方案候选；需验证目标人群效果、模型包体与许可证",
    ),

    /** 个性化识别：使用目标用户注册音频适配的模型。 */
    PERSONALIZED(
        engineId = "personalized-zh",
        capabilities = EngineCapabilities(
            engineId = "personalized-zh",
            offline = true,
            providesConfidence = false,
            ownsMicrophone = false,
            preservesSpeakerIdentity = false,
        ),
        note = "P3 阶段；依赖目标用户合规数据与注册流程",
    ),

    ;

    /** 是否可用于真实质量评估。 */
    val isEvaluable: Boolean
        get() = this != FAKE

    /** 是否适合作为首版默认：离线、不占用麦克风。 */
    val isFirstReleaseCandidate: Boolean
        get() = this == ONNX_OFFLINE

    /** 该方案在结果统计中应使用的 outcome 标记。 */
    fun outcomeTag(metrics: SessionMetrics): String = when (this) {
        FAKE -> "evaluable_engine_missing"
        else -> metrics.outcome
    }
}
