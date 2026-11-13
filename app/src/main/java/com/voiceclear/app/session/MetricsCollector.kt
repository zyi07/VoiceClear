package com.voiceclear.app.session

import java.util.concurrent.ConcurrentHashMap

/**
 * 单次会话的处理指标。
 *
 * 默认只记录耗时与结果状态，不记录原始音频和完整文本（技术指导文件 7.1 节）。
 * 字段设计对应技术指导文件 4.4 节的 ProcessingMetrics 契约。
 */
data class SessionMetrics(
    val sessionId: String,
    val captureMs: Long = 0,
    val recognitionMs: Long = 0,
    val synthesisMs: Long = 0,
    val playbackMs: Long = 0,
    val totalMs: Long = 0,
    val outcome: String = OUTCOME_UNKNOWN,
    val modelVersion: String = "",
    val qualityFlags: List<String> = emptyList(),
) {
    companion object {
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_LOW_CONFIDENCE = "low_confidence"
        const val OUTCOME_UNKNOWN = "unknown"
    }
}

/**
 * 本地指标收集器。
 *
 * 指标只保存在内存中，进程结束即丢弃；如需持久化或上传，必须另行取得用户授权
 * （技术指导文件 7.1 节：崩溃与性能日志需字段白名单与上传前授权）。
 */
class MetricsCollector(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50

        /** 低于该置信度视为低置信度结果，需要用户确认。 */
        const val LOW_CONFIDENCE_THRESHOLD = 0.6f
    }

    private val entries = ConcurrentHashMap<String, SessionMetrics>()
    private val order = ArrayDeque<String>()

    /** 记录一次会话的最终指标。 */
    fun record(metrics: SessionMetrics) {
        synchronized(order) {
            entries[metrics.sessionId] = metrics
            order.remove(metrics.sessionId)
            order.addLast(metrics.sessionId)
            while (order.size > maxEntries) {
                val oldest = order.removeFirst()
                entries.remove(oldest)
            }
        }
    }

    /** 读取单次会话指标。 */
    fun get(sessionId: String): SessionMetrics? = entries[sessionId]

    /** 读取全部指标，按记录顺序从旧到新。 */
    fun snapshot(): List<SessionMetrics> = synchronized(order) {
        order.mapNotNull { entries[it] }
    }

    /** 清空本地指标。 */
    /**
     * 导出全部指标为 JSON 文本。
     *
     * 这里手工拼接而不是用 org.json：单元测试环境下 Android SDK 的 org.json
     * 是空实现（put 会静默失效），手工拼接可保证在任何环境下行为一致。
     */
    fun exportJson(): String {
        val items = snapshot()
        val builder = StringBuilder()
        builder.append("[")
        items.forEachIndexed { index, metrics ->
            if (index > 0) builder.append(",")
            builder.append("{")
            builder.append("\"sessionId\":\"").append(metrics.sessionId).append("\",")
            builder.append("\"captureMs\":").append(metrics.captureMs).append(",")
            builder.append("\"recognitionMs\":").append(metrics.recognitionMs).append(",")
            builder.append("\"synthesisMs\":").append(metrics.synthesisMs).append(",")
            builder.append("\"playbackMs\":").append(metrics.playbackMs).append(",")
            builder.append("\"totalMs\":").append(metrics.totalMs).append(",")
            builder.append("\"outcome\":\"").append(metrics.outcome).append("\",")
            builder.append("\"modelVersion\":\"").append(metrics.modelVersion).append("\",")
            builder.append("\"qualityFlags\":[")
            metrics.qualityFlags.forEachIndexed { flagIndex, flag ->
                if (flagIndex > 0) builder.append(",")
                builder.append("\"").append(flag).append("\"")
            }
            builder.append("]}")
        }
        builder.append("]")
        return builder.toString()
    }

    fun clear() {
        synchronized(order) {
            entries.clear()
            order.clear()
        }
    }

    /**
     * 根据各阶段耗时与识别置信度推导结果状态。
     * 低置信度单独标记，便于后续按人群分层分析。
     */
    fun deriveOutcome(confidence: Float?, failed: Boolean, cancelled: Boolean): String = when {
        failed -> SessionMetrics.OUTCOME_ERROR
        cancelled -> SessionMetrics.OUTCOME_CANCELLED
        confidence != null && confidence < LOW_CONFIDENCE_THRESHOLD ->
            SessionMetrics.OUTCOME_LOW_CONFIDENCE

        else -> SessionMetrics.OUTCOME_SUCCESS
    }
}
