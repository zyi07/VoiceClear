package com.voiceclear.app.session

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一键诊断导出：把所有排障信息打包成一个 zip。
 *
 * 包含：
 * - `app-log.txt`：应用自身的结构化日志
 * - `crash.txt`：历史崩溃堆栈
 * - `snapshot.txt`：设备与运行时快照
 * - `metrics.json`：性能指标
 *
 * **不包含**：原始音频、完整表达文本、任何个人身份信息。
 *
 * 导出通过系统文件选择器由用户选择位置，因此不需要存储权限。
 */
object DiagnosticExporter {

    const val ZIP_NAME_PREFIX = "voiceclear-diagnostics-"

    /** 指标导出（可选）。 */
    interface MetricsProvider {
        fun exportMetrics(): String
    }

    /**
     * 生成诊断包内容，返回打包好的 zip 字节。
     *
     * 单独抽出来便于单元测试：不需要真实的 ContentResolver。
     */
    fun buildArchive(
        context: Context,
        metricsProvider: MetricsProvider? = null,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("app-log.txt"))
            zip.write(DiagnosticLog.export().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val crashDump = CrashHandler(context).crashDump()
            if (crashDump.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("crash.txt"))
                zip.write(crashDump.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("snapshot.txt"))
            zip.write(DiagnosticSnapshot.collect(context).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (metricsProvider != null) {
                runCatching {
                    zip.putNextEntry(ZipEntry("metrics.json"))
                    zip.write(metricsProvider.exportMetrics().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(README_TEXT.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        return output.toByteArray()
    }

    /** 建议的导出文件名。 */
    fun suggestedFileName(): String {
        return ZIP_NAME_PREFIX + System.currentTimeMillis() + ".zip"
    }

    /** 把诊断包写入指定目录（供测试与调试使用）。 */
    fun writeArchiveTo(context: Context, target: File): File {
        val file = File(target, suggestedFileName())
        FileOutputStream(file).use { stream ->
            stream.write(buildArchive(context))
        }
        return file
    }

    private val README_TEXT: String = buildString {
        append("VoiceClear 诊断包")
        append(Char(10))
        append("生成时间: ")
        append(System.currentTimeMillis())
        append(Char(10))
        append(Char(10))
        append("app-log.txt    应用结构化日志（各阶段耗时、错误码）")
        append(Char(10))
        append("crash.txt      崩溃堆栈（如有）")
        append(Char(10))
        append("snapshot.txt   设备与运行时快照")
        append(Char(10))
        append("metrics.json   性能指标（如有）")
        append(Char(10))
        append(Char(10))
        append("本包不含原始音频、完整表达文本或个人身份信息。")
        append(Char(10))
    }
}
