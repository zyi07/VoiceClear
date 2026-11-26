package com.voiceclear.app.session

import android.os.Build
import android.os.SystemClock

/**
 * 性能基准采集。
 *
 * **为什么需要**：此前所有性能判断都来自主观感受或单次日志，
 * 无法回答「比上一版快了多少」「在华为 Mate 30 Pro 上要等多久」这类问题。
 * 基准把测量口径固定下来，让不同机型、不同版本的数字可比。
 *
 * **采集的指标**：
 * - 冷启动到首帧（ms）
 * - 模型加载耗时（ms）
 * - 端到端识别延迟（ms，按音频秒数折算）
 * - 实时率（RTF）：推理秒数 / 音频秒数，越小越快（1.0 表示与实时持平）
 * - Java 堆快照（MB）：已用、空闲与最大堆；不冒充 native 峰值或系统可用内存
 *
 * 这些数字**只反映本机本模型**，不构成对目标人群识别准确率的任何断言。
 */
object PerformanceBenchmark {

    /** 一条基准记录。 */
    data class Record(
        val timestamp: Long,
        val deviceModel: String,
        val androidVersion: String,
        val coldStartMs: Long,
        val modelLoadMs: Long,
        val recognitionMs: Long,
        val audioSeconds: Double,
        val usedHeapMemoryMb: Long,
        val freeHeapMemoryMb: Long,
        val maxHeapMemoryMb: Long,
    ) {
        /**
         * 实时率（Real-Time Factor）。
         *
         * = 推理耗时 / 音频时长。小于 1 表示比实时快。
         */
        val realTimeFactor: Double
            get() = if (audioSeconds > 0) recognitionMs / 1000.0 / audioSeconds else 0.0

        /** 端到端体验分档，便于横向比较。 */
        val experienceTier: String
            get() {
                val factor = realTimeFactor
                return when {
                    factor <= 0.0 -> "未知"
                    factor < 0.3 -> "优秀（快于实时 3 倍以上）"
                    factor < 0.6 -> "良好（快于实时）"
                    factor <= 1.0 -> "可用（与实时相当）"
                    else -> "偏慢（慢于实时）"
                }
            }

        /** 导出为单行 JSON，便于汇总统计。 */
        fun toJson(): String {
            return "{" +
                "\"timestamp\":" + timestamp + "," +
                "\"device\":\"" + deviceModel + "\"," +
                "\"android\":\"" + androidVersion + "\"," +
                "\"coldStartMs\":" + coldStartMs + "," +
                "\"modelLoadMs\":" + modelLoadMs + "," +
                "\"recognitionMs\":" + recognitionMs + "," +
                "\"audioSeconds\":" + audioSeconds + "," +
                "\"realTimeFactor\":" + String.format(java.util.Locale.US, "%.3f", realTimeFactor) + "," +
                "\"usedHeapMemoryMb\":" + usedHeapMemoryMb + "," +
                "\"freeHeapMemoryMb\":" + freeHeapMemoryMb + "," +
                "\"maxHeapMemoryMb\":" + maxHeapMemoryMb + "," +
                "\"tier\":\"" + experienceTier + "\"" +
                "}"
        }
    }

    /**
     * 记录一次识别耗时。
     */
    fun recordRecognition(
        recognitionMs: Long,
        audioMs: Long,
        modelLoadMs: Long = 0L,
        coldStartMs: Long = 0L,
    ): Record {
        val runtime = Runtime.getRuntime()
        val record = Record(
            timestamp = System.currentTimeMillis(),
            deviceModel = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            coldStartMs = coldStartMs,
            modelLoadMs = modelLoadMs,
            recognitionMs = recognitionMs,
            audioSeconds = audioMs / 1000.0,
            usedHeapMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            freeHeapMemoryMb = runtime.freeMemory() / 1024 / 1024,
            maxHeapMemoryMb = runtime.maxMemory() / 1024 / 1024,
        )
        DiagnosticLog.i(
            "Benchmark",
            "识别 " + recognitionMs + "ms / 音频 " + (audioMs / 1000.0) + "s" +
                ", 实时率=" + String.format(java.util.Locale.US, "%.2f", record.realTimeFactor) +
                ", 分档=" + record.experienceTier,
        )
        return record
    }

    /**
     * 测量一段代码的执行耗时（毫秒）。
     */
    fun measure(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }
}
