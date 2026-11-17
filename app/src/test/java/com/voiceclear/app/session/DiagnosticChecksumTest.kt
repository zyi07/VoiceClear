package com.voiceclear.app.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 诊断包完整性测试（审计 3.2 建议 3：导出添加哈希校验）。
 *
 * 重点验证 SHA256SUMS.txt 里记录的哈希与包内实际内容一致——
 * 否则这份清单毫无意义，还会让接收方误以为数据可信。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticChecksumTest {

    private fun readEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>();
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry;
            while (entry != null) {
                entries[entry.name] = zip.readBytes();
                zip.closeEntry();
                entry = zip.nextEntry;
            }
        }
        return entries;
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val raw = MessageDigest.getInstance("SHA-256").digest(bytes);
        val builder = StringBuilder(raw.size * 2);
        for (byte in raw) {
            builder.append(String.format(java.util.Locale.US, "%02x", byte.toInt() and 0xFF));
        }
        return builder.toString();
    }

    @Test
    fun `archive includes checksum manifest`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>();
        val bytes = DiagnosticExporter.buildArchive(context);
        val entries = readEntries(bytes);
        assertTrue(
            "缺少哈希清单",
            entries.containsKey(DiagnosticExporter.CHECKSUMS_ENTRY),
        );
    }

    @Test
    fun `every manifest line matches actual content`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>();
        val bytes = DiagnosticExporter.buildArchive(context);
        val entries = readEntries(bytes);

        val manifest = String(
            entries.getValue(DiagnosticExporter.CHECKSUMS_ENTRY),
            Charsets.UTF_8,
        );
        val lines = manifest.split(Char(10)).filter { it.isNotBlank() };
        assertTrue("清单不应为空", lines.isNotEmpty());

        for (line in lines) {
            // 标准 sha256sum 格式：<64 位哈希><两个空格><文件名>
            val hash = line.substring(0, 64);
            val name = line.substring(66);
            val actual = entries[name];
            assertNotNull("清单引用了不存在的文件: " + name, actual);
            assertEquals("哈希不符: " + name, hash, sha256Hex(actual!!));
        }
    }

    @Test
    fun `manifest does not list itself`() {
        // 清单无法包含自身的哈希（自指），明确定义为不列入，避免调用方困惑
        val context = ApplicationProvider.getApplicationContext<android.content.Context>();
        val entries = readEntries(DiagnosticExporter.buildArchive(context));
        val manifest = String(
            entries.getValue(DiagnosticExporter.CHECKSUMS_ENTRY),
            Charsets.UTF_8,
        );
        assertTrue(
            "清单不应包含自身",
            !manifest.contains(DiagnosticExporter.CHECKSUMS_ENTRY),
        );
    }

    @Test
    fun `benchmark summary is included when provider returns data`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>();
        val aggregator = BenchmarkAggregator();
        aggregator.add(
            BenchmarkAggregator.Sample(
                captureMs = 50L,
                recognitionMs = 120L,
                synthesisMs = 10L,
                playbackMs = 20L,
                totalMs = 200L,
                audioMs = 1000L,
            ),
        );
        val bytes = DiagnosticExporter.buildArchive(
            context = context,
            benchmarkSummaryProvider = object : DiagnosticExporter.BenchmarkSummaryProvider {
                override fun exportBenchmarkSummary(): String = aggregator.snapshot().toJson();
            },
        );
        val entries = readEntries(bytes);
        assertTrue(entries.containsKey("benchmark-summary.json"));
        val json = String(entries.getValue("benchmark-summary.json"), Charsets.UTF_8);
        assertTrue(json.contains("sampleCount"));
        assertTrue(json.contains("recognition"));
    }

    @Test
    fun `readme documents the verification step`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>();
        val entries = readEntries(DiagnosticExporter.buildArchive(context));
        val readme = String(entries.getValue("README.txt"), Charsets.UTF_8);
        assertTrue("README 应说明校验方法", readme.contains("sha256sum -c"));
    }
}
