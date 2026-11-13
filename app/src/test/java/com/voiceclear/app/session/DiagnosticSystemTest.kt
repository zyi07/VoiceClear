package com.voiceclear.app.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 诊断与日志系统测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticSystemTest {

    @Test
    fun logBufferRecordsAndExports() {
        DiagnosticLog.clear()
        DiagnosticLog.i("Test", "第一条")
        DiagnosticLog.w("Test", "第二条")
        DiagnosticLog.e("Test", "第三条")

        assertEquals(3, DiagnosticLog.size())
        val exported = DiagnosticLog.export()
        assertTrue(exported.contains("第一条"))
        assertTrue(exported.contains("W/Test"))
        assertTrue(exported.contains("E/Test"))
    }

    @Test
    fun logBufferIsBounded() {
        DiagnosticLog.clear()
        repeat(DiagnosticLog.MAX_ENTRIES + 50) { index ->
            DiagnosticLog.i("Bulk", "条目 " + index)
        }
        assertEquals(DiagnosticLog.MAX_ENTRIES, DiagnosticLog.size())
        // 最旧的条目应被淘汰，最新的仍在。
        val exported = DiagnosticLog.export()
        assertTrue(exported.contains("条目 " + (DiagnosticLog.MAX_ENTRIES + 49)))
        assertFalse(exported.contains("条目 0"))
    }

    @Test
    fun longMessagesAreTruncated() {
        DiagnosticLog.clear()
        DiagnosticLog.i("Long", "x".repeat(DiagnosticLog.MAX_MESSAGE_LENGTH + 100))
        val exported = DiagnosticLog.export()
        assertTrue(exported.contains("truncated"))
    }

    @Test
    fun crashHandlerSavesStackTrace() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val handler = CrashHandler(context)
        handler.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("模拟崩溃"),
        )

        val files = handler.crashFiles()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("模拟崩溃"))
        assertTrue(content.contains("IllegalStateException"))
        assertTrue(handler.lastCrashAt() > 0L)
    }

    @Test
    fun diagnosticArchiveContainsAllSections() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DiagnosticLog.clear()
        DiagnosticLog.i("Archive", "测试日志")

        val bytes = DiagnosticExporter.buildArchive(
            context = context,
            metricsProvider = object : DiagnosticExporter.MetricsProvider {
                override fun exportMetrics(): String = "[{\"a\":1}]"
            },
        )
        assertTrue(bytes.isNotEmpty())

        val names = mutableListOf<String>()
        val contents = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                contents[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertTrue(names.contains("app-log.txt"))
        assertTrue(names.contains("snapshot.txt"))
        assertTrue(names.contains("metrics.json"))
        assertTrue(names.contains("README.txt"))
        assertTrue(contents["app-log.txt"]!!.contains("测试日志"))
        assertTrue(contents["snapshot.txt"]!!.contains("manufacturer="))
    }

    @Test
    fun archiveNeverContainsRawAudioOrFullText() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = DiagnosticExporter.buildArchive(context)
        // 归档内容不应包含音频容器标记。
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("RIFF"))
        assertFalse(text.contains("OggS"))
    }

    @Test
    fun metricsExportProducesValidJsonShape() {
        val collector = MetricsCollector()
        collector.record(
            SessionMetrics(
                sessionId = "s1",
                captureMs = 10,
                recognitionMs = 20,
                synthesisMs = 30,
                playbackMs = 40,
                totalMs = 100,
                outcome = "success",
                modelVersion = "v1",
                qualityFlags = emptyList(),
            ),
        )
        val json = collector.exportJson()
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("\"sessionId\":\"s1\""))
        assertTrue(json.contains("\"totalMs\":100"))
    }
}
