package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.MetricsCollector

/**
 * 端侧离线中文识别引擎（sherpa-onnx 方案）的接入占位与能力校验。
 *
 * 现状说明（诚实边界）：
 * 本工程当前**未集成** sherpa-onnx 原生库与模型文件。原因有三：
 * 1. 端侧模型需随包分发或在应用内下载，包体与下载策略属于待决策项（技术指导文件 6.4 节）；
 * 2. 模型对**目标人群（听障相关发音不清的普通话成人）**的效果完全未知，
 *    在没有该人群数据前无法判断是否优于通用 ASR（技术指导文件 5.3 节基线顺序）；
 * 3. 本机无 GPU 且磁盘仅余约 16 GB，训练与大批量评测不适合在此环境进行。
 *
 * 因此本类只负责：
 * - 声明该方案的引擎能力，便于上层在选型、统计与降级时正确区分；
 * - 在模型包缺失或校验失败时给出明确的、可处理的失败，而不是崩溃或静默降级；
 * - 保留与识别引擎契约一致的调用面，使后续接入真实模型时无需改动 UI 与状态机。
 *
 * 接入真实模型时必须完成（技术指导文件 5.6 节验证表）：
 * - 固定 sherpa-onnx 版本并核查代码与模型的许可证；
 * - 验证目标人群的 CER 与关键内容错误，且与通用 ASR 在同一数据集上对照；
 * - 在真实设备上测量模型包体、峰值内存、首字延迟与温升；
 * - 确认端侧推理不产生任何网络请求。
 */
class OnnxOfflineRecognitionEngine(
    private val context: Context,
    private val modelDirPath: String? = null,
) : RecognitionEngine {

    companion object {
        const val ERR_MODEL_NOT_AVAILABLE = "asr_model_not_available"
        const val ERR_MODEL_INVALID = "asr_model_invalid"
    }

    override val engineId: String = RecognitionEngineOption.ONNX_OFFLINE.engineId

    override val capabilities: EngineCapabilities = RecognitionEngineOption.ONNX_OFFLINE.capabilities

    /** 模型包是否已就绪。未就绪时上层应回退到可选引擎或提示用户。 */
    fun isModelReady(): Boolean {
        val path = modelDirPath ?: return false
        val dir = java.io.File(path)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    override suspend fun recognize(request: RecognitionRequest): RecognitionResult {
        if (!isModelReady()) {
            return RecognitionResult(
                sessionId = request.sessionId,
                rawText = "",
                normalizedText = "",
                confidence = null,
                warnings = listOf(ERR_MODEL_NOT_AVAILABLE),
            )
        }
        // 接入真实模型后在此调用 sherpa-onnx 离线识别接口。
        // 在完成上述验证前，不提供任何模拟结果，避免污染评测数据。
        return RecognitionResult(
            sessionId = request.sessionId,
            rawText = "",
            normalizedText = "",
            confidence = null,
            warnings = listOf(ERR_MODEL_INVALID),
        )
    }

    /** 释放模型占用的内存。 */
    fun release() {
        // 接入真实模型后在此释放原生资源。
    }
}
