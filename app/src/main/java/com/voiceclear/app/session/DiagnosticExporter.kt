package com.voiceclear.app.session

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    /** 哈希清单文件名。 */
    const val CHECKSUMS_ENTRY = "SHA256SUMS.txt"

    /** 指标导出（可选）。 */
    interface MetricsProvider {
        fun exportMetrics(): String
    }

    /** 性能基准导出（可选）。 */
    interface BenchmarkProvider {
        fun exportBenchmark(): String
    }

    /** 多轮基准聚合导出（可选）。 */
    interface BenchmarkSummaryProvider {
        fun exportBenchmarkSummary(): String
    }

    /**
     * 生成诊断包内容，返回打包好的 zip 字节。
     *
     * 单独抽出来便于单元测试：不需要真实的 ContentResolver。
     */
    fun buildArchive(
        context: Context,
        metricsProvider: MetricsProvider? = null,
        benchmarkProvider: BenchmarkProvider? = null,
        benchmarkSummaryProvider: BenchmarkSummaryProvider? = null,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        // 记录每个条目写入的实际字节与其哈希，最后统一写入清单。
        val checksums = LinkedHashMap<String, String>()

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            writeEntry(
                zip, checksums, "app-log.txt",
                DiagnosticLog.export().toByteArray(Charsets.UTF_8),
            )

            val crashDump = CrashHandler(context).crashDump()
            if (crashDump.isNotEmpty()) {
                writeEntry(zip, checksums, "crash.txt", crashDump.toByteArray(Charsets.UTF_8))
            }

            writeEntry(
                zip, checksums, "snapshot.txt",
                DiagnosticSnapshot.collect(context).toByteArray(Charsets.UTF_8),
            )

            if (metricsProvider != null) {
                runCatching {
                    writeEntry(
                        zip, checksums, "metrics.json",
                        metricsProvider.exportMetrics().toByteArray(Charsets.UTF_8),
                    )
                }
            }

            if (benchmarkProvider != null) {
                runCatching {
                    writeEntry(
                        zip, checksums, "benchmark.json",
                        benchmarkProvider.exportBenchmark().toByteArray(Charsets.UTF_8),
                    )
                }
            }

            if (benchmarkSummaryProvider != null) {
                runCatching {
                    writeEntry(
                        zip, checksums, "benchmark-summary.json",
                        benchmarkSummaryProvider.exportBenchmarkSummary()
                            .toByteArray(Charsets.UTF_8),
                    )
                }
            }

            writeEntry(
                zip, checksums, "README.txt",
                README_TEXT.toByteArray(Charsets.UTF_8),
            )

            // 清单必须最后写：它要覆盖前面所有条目。
            writeChecksums(zip, checksums)
        }

        return output.toByteArray()
    }

    /**
     * 写入一个条目，同时算好哈希。
     *
     * 同一份字节既写进 zip 也算哈希，避免「算的和写的不一致」。
     */
    private fun writeEntry(
        zip: ZipOutputStream,
        checksums: MutableMap<String, String>,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        checksums[name] = sha256Hex(bytes)
    }

    /** 把哈希清单本身写入 zip。 */
    private fun writeChecksums(zip: ZipOutputStream, checksums: Map<String, String>) {
        val builder = StringBuilder()
        for ((name, hash) in checksums) {
            // 采用 sha256sum 的标准格式：<哈希>  <文件名>
            builder.append(hash).append("  ").append(name).append(Char(10))
        }
        zip.putNextEntry(ZipEntry(CHECKSUMS_ENTRY))
        zip.write(builder.toString().toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** 计算字节的 SHA-256 十六进制串。 */
    private fun sha256Hex(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(raw.size * 2)
        for (byte in raw) {
            builder.append(String.format(java.util.Locale.US, "%02x", byte.toInt() and 0xFF))
        }
        return builder.toString()
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
        append("app-log.txt              应用结构化日志（各阶段耗时、错误码）")
        append(Char(10))
        append("crash.txt                崩溃堆栈（如有）")
        append(Char(10))
        append("snapshot.txt             设备与运行时快照")
        append(Char(10))
        append("metrics.json             性能指标（如有）")
        append(Char(10))
        append("benchmark.json           最近一次性能基准（如有）")
        append(Char(10))
        append("benchmark-summary.json   多轮基准聚合，中位数与 P95（如有）")
        append(Char(10))
        append("SHA256SUMS.txt           包内各文件的 SHA-256，用于校验完整性")
        append(Char(10))
        append(Char(10))
        append("校验方法（在解压目录中执行）:")
        append(Char(10))
        append("    sha256sum -c SHA256SUMS.txt")
        append(Char(10))
        append(Char(10))
        append("本包不含原始音频、完整表达文本或个人身份信息。")
        append(Char(10))
    }
}
