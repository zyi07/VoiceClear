package com.voiceclear.app.capture

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 采集数据保留策略。
 *
 * **背景**：语音是生物特征信息，留存期是合规审查的关注点。
 * 知情同意书里写明的保存期限必须能被应用实际执行，否则承诺落空。
 *
 * **策略**：
 * - **录音**（原始音频）：按天保留，默认 [DEFAULT_RETENTION_DAYS] 天，过期自动删除；
 *   这是最敏感的数据，优先清理。
 * - **元数据**（转写、同意书、说话人档案）：**不自动删除**。
 *   它们是研究记录与合规凭据，删除权属于用户（由「清空采集数据」显式执行），
 *   不能由程序按时间偷偷删掉——否则会破坏伦理审查要求的可追溯性。
 *
 * 每次清理都会写日志，记录删除了什么，便于在诊断包中核对。
 */
class DataRetentionPolicy(
    private val context: Context,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
) {

    companion object {
        /** 默认保留天数：30 天。 */
        const val DEFAULT_RETENTION_DAYS = 30

        /** 保留天数为 0 或负数表示不自动清理。 */
        const val DISABLED = 0

        /** 清理记录文件名，记录每次清理的明细。 */
        const val CLEANUP_LOG_FILE = "retention-cleanup.log"
    }

    /**
     * 执行一次保留策略清理。
     *
     * @return 清理结果摘要
     */
    fun enforce(): CleanupResult {
        if (retentionDays <= DISABLED) {
            return CleanupResult(disabled = true, deletedFiles = 0, deletedBytes = 0L)
        }

        val store = CaptureStore(context)
        val segmentsRoot = File(store.root(), CaptureStore.SEGMENTS_DIR)
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L

        var deletedFiles = 0
        var deletedBytes = 0L

        segmentsRoot.listFiles()?.forEach { sessionDir ->
            if (!sessionDir.isDirectory) return@forEach
            sessionDir.listFiles()?.forEach { audioFile ->
                if (audioFile.lastModified() < cutoff) {
                    val size = audioFile.length()
                    if (audioFile.delete()) {
                        deletedFiles++
                        deletedBytes += size
                    }
                }
            }
            // 会话目录空了就一并删掉
            val remaining = sessionDir.listFiles()?.size ?: 0
            if (remaining == 0) {
                sessionDir.delete()
            }
        }

        if (deletedFiles > 0) {
            val message = "已清理过期录音 " + deletedFiles + " 个文件, " +
                (deletedBytes / 1024) + " KB, 保留期 " + retentionDays + " 天"
            DiagnosticLog.i("Retention", message)
            writeCleanupLog(deletedFiles, deletedBytes)
        }

        return CleanupResult(
            disabled = false,
            deletedFiles = deletedFiles,
            deletedBytes = deletedBytes,
        )
    }

    /** 写出清理记录，供审计追溯。 */
    private fun writeCleanupLog(deletedFiles: Int, deletedBytes: Long) {
        val store = CaptureStore(context)
        val logFile = File(store.root(), CLEANUP_LOG_FILE)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        runCatching {
            logFile.appendText(
                formatter.format(Date()) + " 删除 " + deletedFiles + " 个录音文件 (" +
                    (deletedBytes / 1024) + " KB)\n",
            )
        }
    }

    /** 清理结果。 */
    data class CleanupResult(
        /** 是否因策略未启用而跳过。 */
        val disabled: Boolean,
        /** 删除的文件数。 */
        val deletedFiles: Int,
        /** 删除的总字节数。 */
        val deletedBytes: Long,
    ) {
        /** 供界面展示的简短描述。 */
        fun describe(): String {
            return when {
                disabled -> "自动清理未启用"
                deletedFiles == 0 -> "没有需要清理的数据"
                else -> "已清理 " + deletedFiles + " 个过期录音（" +
                    (deletedBytes / 1024) + " KB）"
            }
        }
    }
}
