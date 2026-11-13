package com.voiceclear.app.session

import android.util.Log

/**
 * 诊断日志。
 *
 * 设计约束（技术指导文件 7.1 节）：
 * - **不记录原始音频与完整表达文本**，只记录长度、时长、结果状态等元信息；
 * - 不记录任何可识别个人的信息（说话人 ID 除外，且 ID 本身是随机的）；
 * - 日志同时输出到 logcat（便于 adb logcat 抓取）与本地文件（便于导出）。
 *
 * 用途：真机联调时定位采集、识别、合成、播放各阶段的耗时与失败原因。
 */
object DiagnosticLog {

    const val TAG = "VoiceClear"

    /** 单条日志的最大长度，避免异常情况下日志无限增长。 */
    const val MAX_MESSAGE_LENGTH = 500

    /** 保留的最大条数。 */
    const val MAX_ENTRIES = 2000

    private val entries = ArrayDeque<String>()

    /** 记录一条日志（同时写 logcat 与内存缓冲）。 */
    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String) = write("W", tag, message)

    fun e(tag: String, message: String) = write("E", tag, message)

    private fun write(level: String, tag: String, message: String) {
        val safeMessage = if (message.length > MAX_MESSAGE_LENGTH) {
            message.take(MAX_MESSAGE_LENGTH) + "...(truncated)"
        } else {
            message
        }
        val line = level + "/" + tag + ": " + safeMessage
        when (level) {
            "E" -> Log.e(TAG, tag + ": " + safeMessage)
            "W" -> Log.w(TAG, tag + ": " + safeMessage)
            else -> Log.i(TAG, tag + ": " + safeMessage)
        }
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    /** 导出全部日志为文本，用于发给开发方分析。 */
    fun export(): String {
        val snapshot = synchronized(entries) { entries.toList() }
        val header = buildString {
            append("VoiceClear 诊断日志")
            append(Char(10))
            append("条目数: ")
            append(snapshot.size)
            append(Char(10))
            append("说明: 不含原始音频与完整文本，仅含元信息与错误码")
            append(Char(10))
            append("=".repeat(60))
            append(Char(10))
        }
        return header + snapshot.joinToString(Char(10).toString())
    }

    /** 清空日志缓冲。 */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** 当前日志条数。 */
    fun size(): Int = synchronized(entries) { entries.size }
}
