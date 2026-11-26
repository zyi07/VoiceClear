package com.voiceclear.app.capture

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.R
import com.voiceclear.app.session.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 导出完整 ZIP 数据集；每段 PCM 独立保存，绝不拼接成无边界文件。 */
class AudioExportActivity : ComponentActivity() {

    private lateinit var store: CaptureStore
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var exportButton: MaterialButton
    private var exportTarget: android.net.Uri? = null
    private var exportJob: Job? = null

    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                DiagnosticLog.i("Export", "用户取消了数据集导出")
                return@registerForActivityResult
            }
            exportTarget = uri
            performExport()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_export)
        store = CaptureStore(applicationContext)
        statusText = findViewById(R.id.exportStatus)
        summaryText = findViewById(R.id.exportSummary)
        exportButton = findViewById(R.id.exportAudioButton)
        exportButton.setOnClickListener { onExportClicked() }
        findViewById<MaterialButton>(R.id.exportBackButton).setOnClickListener { finish() }
        refreshSummary()
        statusText.setText(R.string.export_audio_ready)
    }

    private fun refreshSummary() {
        val stats = store.statistics()
        summaryText.text = getString(
            R.string.export_audio_summary,
            stats.segments,
            String.format(Locale.CHINA, "%.1f", stats.totalHours),
        )
    }

    private fun onExportClicked() {
        if (exportJob?.isActive == true) return
        val stats = store.statistics()
        if (stats.segments == 0) {
            statusText.setText(R.string.export_audio_empty)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.export_audio_confirm_title)
            .setMessage(
                getString(R.string.export_audio_confirm_message, stats.segments) +
                    "\n\n" + getString(R.string.export_audio_privacy_note),
            )
            .setPositiveButton(R.string.export_audio_confirm) { _, _ ->
                DiagnosticLog.i("Export", "用户确认导出数据集, 有效片段数=" + stats.segments)
                createFileLauncher.launch("voiceclear-dataset.zip")
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun performExport() {
        val uri = exportTarget ?: return
        exportButton.isEnabled = false
        exportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openOutputStream(uri) ?: error("无法打开输出流")
                    stream.use { store.writeDatasetZip(it) }
                }
            }
            if (result.isSuccess) {
                val summary = result.getOrThrow()
                DiagnosticLog.i(
                    "Export",
                    "数据集导出完成, 片段=" + summary.segments + ", 音频字节=" + summary.audioBytes,
                )
                statusText.text = if (summary.segments > 0) {
                    getString(R.string.export_audio_done, summary.segments, summary.audioBytes / 1024)
                } else {
                    getString(R.string.export_audio_empty)
                }
            } else {
                val message = result.exceptionOrNull()?.message ?: "未知错误"
                DiagnosticLog.e("Export", "数据集导出失败: " + message)
                statusText.setText(R.string.export_audio_failed)
            }
            exportButton.isEnabled = true
            refreshSummary()
        }
    }

    override fun onDestroy() {
        exportJob?.cancel()
        super.onDestroy()
    }
}
