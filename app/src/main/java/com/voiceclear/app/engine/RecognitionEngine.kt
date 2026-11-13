package com.voiceclear.app.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 引擎能力声明。
 *
 * 不同识别方案的能力差异很大，UI 与降级逻辑必须知道当前引擎是否离线、
 * 是否提供置信度、是否自带麦克风占用（技术指导文件 5.6 节的验证表在代码中的落地）。
 */
data class EngineCapabilities(
    val engineId: String,
    /** 是否完全离线，不产生任何网络请求。 */
    val offline: Boolean,
    /** 是否提供可靠置信度；false 时上层不得依赖置信度做自动放行。 */
    val providesConfidence: Boolean,
    /** 是否自行占用麦克风（与本应用的 AudioCapture 互斥）。 */
    val ownsMicrophone: Boolean,
    /** 是否能保留用户本人音色。 */
    val preservesSpeakerIdentity: Boolean,
)

/**
 * 识别引擎能力契约。
 *
 * 本应用已确认的技术基线是"ASR→文本→TTS"级联（技术指导文件 3.2 节 ADR-001），
 * 但识别层必须可替换：不同方案在离线能力、置信度、麦克风占用上差异显著，
 * 且需支持同一数据集上的对照实验（技术指导文件 8.2 节的 A–F 分组）。
 */
interface RecognitionEngine {

    /** 引擎标识，用于模型版本记录与评测分组。 */
    val engineId: String

    /** 引擎能力声明。 */
    val capabilities: EngineCapabilities

    /** 识别一段已采集音频，返回文本候选与可用置信度。 */
    suspend fun recognize(request: RecognitionRequest): RecognitionResult
}
