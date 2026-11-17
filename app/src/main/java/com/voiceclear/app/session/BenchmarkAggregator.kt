package com.voiceclear.app.session

/** 双引号字符，避免在 JSON 拼装里到处写转义。 */
private val QUOTE: String = Char(34).toString()

/** 拼一个整数键值对。 */
private fun jsonPairInt(key: String, value: Long): String =
    QUOTE + key + QUOTE + ":" + value

/** 拼一个小数键值对。 */
private fun jsonPairDouble(key: String, value: Double): String =
    QUOTE + key + QUOTE + ":" + String.format(java.util.Locale.US, "%.3f", value)

/** 拼一个字符串键值对。 */
private fun jsonPairText(key: String, value: String): String =
    QUOTE + key + QUOTE + ":" + QUOTE + value + QUOTE

/**
 * 性能基准聚合。
 *
 * **为什么需要**（审计报告 4.2）：
 * 单次识别耗时受温度、后台负载、音频长度影响，波动很大。
 * 拿单次数字比较「这版比上版快多少」或「低端机能否用」，结论不可靠——
 * 一次 99 ms 和一次 170 ms 都是真实记录，但代表不了常态。
 *
 * 本类保留最近 [windowSize] 次样本，输出**中位数与 P95**：
 * - 中位数反映常态体验；
 * - P95 反映最差的那一小撮，才是用户真正会觉得卡的时刻。
 *
 * **口径声明**：这里只度量**耗时**，不度量识别准确率。
 * 目标人群的准确率必须用真实录音数据集评测，本类无法代替。
 */
class BenchmarkAggregator(private val windowSize: Int = DEFAULT_WINDOW) {

    companion object {
        /** 默认窗口：审计建议「测试 50 次取中位数」。 */
        const val DEFAULT_WINDOW = 50
    }

    /** 一次会话的阶段耗时样本，单位毫秒。 */
    data class Sample(
        val captureMs: Long,
        val recognitionMs: Long,
        val synthesisMs: Long,
        val playbackMs: Long,
        val totalMs: Long,
        /** 音频时长，用于算实时率。 */
        val audioMs: Long,
    ) {
        /** 仅识别阶段的实时率。 */
        val recognitionRtf: Double
            get() = if (audioMs > 0) recognitionMs.toDouble() / audioMs else 0.0
    }

    /** 单个阶段的统计结果。 */
    data class StageStats(
        val count: Int,
        val minMs: Long,
        val medianMs: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val meanMs: Long,
    ) {
        fun toJson(): String {
            return "{" +
                jsonPairInt("count", count.toLong()) + "," +
                jsonPairInt("minMs", minMs) + "," +
                jsonPairInt("medianMs", medianMs) + "," +
                jsonPairInt("p95Ms", p95Ms) + "," +
                jsonPairInt("maxMs", maxMs) + "," +
                jsonPairInt("meanMs", meanMs) +
                "}"
        }
    }

    /** 全量快照。 */
    data class Snapshot(
        val sampleCount: Int,
        val windowSize: Int,
        val capture: StageStats?,
        val recognition: StageStats?,
        val synthesis: StageStats?,
        val playback: StageStats?,
        val total: StageStats?,
        val medianRtf: Double,
    ) {
        /** 导出 JSON；无样本时给出说明而不是空对象。 */
        fun toJson(): String {
            if (sampleCount == 0) {
                return "{" + jsonPairText("note", "尚无样本") + "," +
                    jsonPairInt("windowSize", windowSize.toLong()) + "}"
            }
            return "{" +
                jsonPairInt("sampleCount", sampleCount.toLong()) + "," +
                jsonPairInt("windowSize", windowSize.toLong()) + "," +
                jsonPairDouble("medianRtf", medianRtf) + "," +
                QUOTE + "capture" + QUOTE + ":" + capture!!.toJson() + "," +
                QUOTE + "recognition" + QUOTE + ":" + recognition!!.toJson() + "," +
                QUOTE + "synthesis" + QUOTE + ":" + synthesis!!.toJson() + "," +
                QUOTE + "playback" + QUOTE + ":" + playback!!.toJson() + "," +
                QUOTE + "total" + QUOTE + ":" + total!!.toJson() + "," +
                jsonPairText("note", "仅耗时统计，不含识别准确率") +
                "}"
        }
    }

    private val samples = ArrayDeque<Sample>()

    /** 已收集的样本数（最多 [windowSize]）。 */
    val size: Int
        get() = samples.size

    /**
     * 记录一次样本。超过窗口时丢弃最旧的。
     *
     * 丢弃最旧而非全部清空：滑动窗口能在不丢失近期趋势的前提下
     * 限制内存占用，也让统计持续反映最近的使用情况。
     */
    @Synchronized
    fun add(sample: Sample) {
        samples.addLast(sample)
        while (samples.size > windowSize) {
            samples.removeFirst()
        }
    }

    /** 清空所有样本。 */
    @Synchronized
    fun reset() {
        samples.clear()
    }

    /** 生成当前快照。 */
    @Synchronized
    fun snapshot(): Snapshot {
        if (samples.isEmpty()) {
            return Snapshot(0, windowSize, null, null, null, null, null, 0.0)
        }
        val rtfs = samples.map { it.recognitionRtf }.sorted()
        return Snapshot(
            sampleCount = samples.size,
            windowSize = windowSize,
            capture = statsOf(samples.map { it.captureMs }),
            recognition = statsOf(samples.map { it.recognitionMs }),
            synthesis = statsOf(samples.map { it.synthesisMs }),
            playback = statsOf(samples.map { it.playbackMs }),
            total = statsOf(samples.map { it.totalMs }),
            medianRtf = median(rtfs),
        )
    }

    private fun statsOf(values: List<Long>): StageStats {
        val sorted = values.sorted()
        val count = sorted.size
        val sum = sorted.sum()
        return StageStats(
            count = count,
            minMs = sorted.first(),
            medianMs = percentile(sorted, 50.0),
            p95Ms = percentile(sorted, 95.0),
            maxMs = sorted.last(),
            meanMs = if (count > 0) sum / count else 0L,
        )
    }

    /**
     * 最近秩法（nearest-rank）取分位数。
     *
     * 样本量小时（例如只有 3 次）不用插值：设备性能数据是离散的，
     * 插值出来的数不代表任何一次真实观测，容易给出虚假的精度感。
     */
    private fun percentile(sorted: List<Long>, percent: Double): Long {
        if (sorted.isEmpty()) return 0L
        if (sorted.size == 1) return sorted.first()
        val rank = kotlin.math.ceil(percent / 100.0 * sorted.size).toInt()
        val index = (rank - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun median(sortedDoubles: List<Double>): Double {
        if (sortedDoubles.isEmpty()) return 0.0
        val middle = sortedDoubles.size / 2
        return if (sortedDoubles.size % 2 == 1) {
            sortedDoubles[middle]
        } else {
            (sortedDoubles[middle - 1] + sortedDoubles[middle]) / 2.0
        }
    }
}
