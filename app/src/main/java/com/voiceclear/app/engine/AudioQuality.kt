package com.voiceclear.app.engine

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 音频质量分析。
 *
 * **为什么需要**：
 * 真机实测发现「识别结果为空」有两种完全不同的原因，
 * 但早期版本都只报同一个错，用户无法判断该做什么：
 *
 * 1. **麦克风几乎没收到声音**（RMS < 0.001）——设备静音、权限异常、
 *    路由错误，或用户根本没说话。此时应提示「没听到声音」。
 * 2. **录到了声音但模型没认出**（RMS 正常）——才是识别能力问题，
 *    需要换模型或改进采集质量。
 *
 * 把两者区分开，才能把「识别不准」这个笼统的问题拆成可行动的两类。
 */
object AudioQuality {

    /**
     * 近乎静音的 RMS 阈值。
     *
     * 实测 7 次录音中 5 次 RMS <= 0.0001（完全无信号），
     * 1 次 RMS = 0.0063（勉强有信号但极弱），1 次 RMS = 0.0446（正常说话）。
     * 取 0.001 作为「几乎肯定没有有效语音」的界线。
     */
    const val SILENCE_RMS_THRESHOLD = 0.001f

    /**
     * 能量偏低的 RMS 阈值。
     *
     * 低于此值说明有信号但很弱，用户可能离麦克风太远或在嘈杂环境中。
     */
    const val LOW_ENERGY_RMS_THRESHOLD = 0.01f

    /** 正常说话的 RMS 参考值（实测 0.0446）。 */
    const val NORMAL_RMS_REFERENCE = 0.04f

    /** 分析结果。 */
    data class Analysis(
        /** 均方根能量。 */
        val rms: Float,
        /** 峰值。 */
        val peak: Float,
        /** 非零采样占比。 */
        val activeRatio: Float,
        /** 质量判定。 */
        val verdict: Verdict,
    ) {
        /** 简短描述，用于日志与提示。 */
        fun describe(): String {
            return when (verdict) {
                Verdict.SILENT -> "没有检测到声音"
                Verdict.TOO_LOW -> "声音很弱"
                Verdict.NORMAL -> "声音正常"
            }
        }
    }

    /** 质量判定。 */
    enum class Verdict {
        /** 几乎完全静音。 */
        SILENT,

        /** 有信号但很弱。 */
        TOO_LOW,

        /** 正常。 */
        NORMAL,
    }

    /**
     * 分析音频质量。
     *
     * @param samples 归一化到 [-1, 1] 的采样值
    */
    fun analyze(samples: FloatArray): Analysis {
        if (samples.isEmpty()) {
            return Analysis(0f, 0f, 0f, Verdict.SILENT)
        }

        var sumSquares = 0.0
        var peak = 0f
        var activeSamples = 0

        for (value in samples) {
            val magnitude = abs(value)
            sumSquares += (magnitude * magnitude).toDouble()
            if (magnitude > peak) peak = magnitude
            // 阈值取近乎静音的界线，低于此值视为静音段
            if (magnitude > SILENCE_RMS_THRESHOLD) activeSamples++
        }

        val rms = sqrt(sumSquares / samples.size).toFloat()
        val activeRatio = activeSamples.toFloat() / samples.size

        val verdict = when {
            rms < SILENCE_RMS_THRESHOLD -> Verdict.SILENT
            rms < LOW_ENERGY_RMS_THRESHOLD -> Verdict.TOO_LOW
            else -> Verdict.NORMAL
        }

        return Analysis(rms, peak, activeRatio, verdict)
    }

    /**
     * 把 PCM16 字节转成归一化浮点采样。
     *
     * 与识别引擎中的同名函数重复，此处独立保留是为了让质量分析
     * 能在**不加载模型**的情况下运行（模型未就绪时也要能诊断麦克风）。
     */
    fun toFloatSamples(bytes: ByteArray): FloatArray {
        val count = bytes.size / 2
        if (count == 0) return FloatArray(0)
        val output = FloatArray(count)
        for (index in 0 until count) {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            output[index] = ((high shl 8) or low).toShort() / 32768.0f
        }
        return output
    }
}
