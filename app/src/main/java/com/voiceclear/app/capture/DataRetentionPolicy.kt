package com.voiceclear.app.capture

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 录音保留策略；元数据保留为审计墓碑，但删除后不再计入统计或导出。 */
class DataRetentionPolicy(
    context: Context,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30
        const val DISABLED = 0
        const val CLEANUP_LOG_FILE = "retention-cleanup.log"
    }

    private val store = CaptureStore(context)

    /**
     * 在 CaptureStore 的同一把公平锁内拍摄稳定快照并删除。
     * 只处理已发布的 .pcm，不碰 .part/.tmp；因此不会清理写入中的音频。
     */
    fun enforce(): CleanupResult {
        if (retentionDays <= DISABLED) {
            return CleanupResult(disabled = true, deletedFiles = 0, deletedBytes = 0L)
        }
        return store.locked {
            val segmentsRoot = File(store.root(), CaptureStore.SEGMENTS_DIR)
            val cutoff = clock() - retentionDays * DAY_MS
            val snapshot = if (segmentsRoot.exists()) {
                segmentsRoot.walkTopDown().filter {
                    it.isFile && it.extension == "pcm" && it.lastModified() < cutoff
                }.toList()
            } else {
                emptyList()
            }

            var deletedFiles = 0
            var deletedBytes = 0L
            snapshot.forEach { audioFile ->
                val size = audioFile.length()
                if (audioFile.delete()) {
                    deletedFiles++
                    deletedBytes += size
                    val relative = runCatching { audioFile.relativeTo(segmentsRoot).path }.getOrNull()
                    val sessionId = relative?.split('/')?.firstOrNull()
                    val segmentId = audioFile.nameWithoutExtension
                    if (!sessionId.isNullOrBlank()) {
                        store.markAudioUnavailable(
                            sessionId,
                            segmentId,
                            CaptureStore.RETENTION_DELETION_REASON,
                        )
                    }
                }
            }
            if (segmentsRoot.exists()) {
                segmentsRoot.walkBottomUp().filter { it.isDirectory }.forEach { directory ->
                    if (directory.listFiles()?.isEmpty() == true) directory.delete()
                }
            }
            if (deletedFiles > 0) {
                val message = "已清理过期录音 " + deletedFiles + " 个文件, " +
                    (deletedBytes / 1024) + " KB, 保留期 " + retentionDays + " 天"
                DiagnosticLog.i("Retention", message)
                writeCleanupLog(deletedFiles, deletedBytes)
            }
            CleanupResult(disabled = false, deletedFiles = deletedFiles, deletedBytes = deletedBytes)
        }
    }

    private fun writeCleanupLog(deletedFiles: Int, deletedBytes: Long) {
        val logFile = File(store.root(), CLEANUP_LOG_FILE)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        runCatching {
            logFile.appendText(
                formatter.format(Date(clock())) + " 删除 " + deletedFiles + " 个录音文件 (" +
                    (deletedBytes / 1024) + " KB)\n",
            )
        }
    }

    data class CleanupResult(
        val disabled: Boolean,
        val deletedFiles: Int,
        val deletedBytes: Long,
    ) {
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

private const val DAY_MS = 24L * 60L * 60L * 1000L
