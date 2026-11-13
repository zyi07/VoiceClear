package com.voiceclear.app.capture

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.R
import com.voiceclear.app.session.DiagnosticLog
import java.io.File

/**
 * 音频导出界面。
 *
 * 音频属于生物特征信息，导出必须走**显式授权**：
 * - 页面明确说明将要导出什么、包含哪些片段；
 * - 用户确认后才写入；
 * - 写入目标由系统文件选择器决定，不指定路径。
 *
 * 音频以原始 PCM 形式导出，不做降噪或重采样，保持采集原始状态。
 */
class AudioExportActivity : ComponentActivity() {

    private lateinit var store: CaptureStore
    private lateinit var statusText: TextView
    private lateinit var summaryText: TextView

    /** 待导出的目标文件（由系统选择器返回）。 */
    private var exportTarget: android.net.Uri? = null

    private val createFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) {
                DiagnosticLog.i("Export", "用户取消了音频导出")
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

        findViewById<MaterialButton>(R.id.exportAudioButton).setOnClickListener {
            onExportClicked()
        }
        findViewById<MaterialButton>(R.id.exportBackButton).setOnClickListener {
            finish()
        }

        val stats = store.statistics()
        summaryText.text = getString(
            R.string.export_audio_summary,
            stats.segments,
            String.format(java.util.Locale.CHINA, "%.1f", stats.totalHours),
        )
        statusText.setText(R.string.export_audio_ready)
    }

    private fun onExportClicked() {
        val stats = store.statistics()
        if (stats.segments == 0) {
            statusText.setText(R.string.export_audio_empty)
            return
        }
        // 二次确认：音频是生物特征，导出必须是显式决定。
        AlertDialog.Builder(this)
            .setTitle(R.string.export_audio_confirm_title)
            .setMessage(
                getString(R.string.export_audio_confirm_message, stats.segments) +
                    "\n\n" + getString(R.string.export_audio_privacy_note),
            )
            .setPositiveButton(R.string.export_audio_confirm) { _, _ ->
                DiagnosticLog.i("Export", "用户确认导出音频, 片段数=" + stats.segments)
                createFileLauncher.launch("voiceclear-audio.pcm")
            }
            .setNegativeButton(R.string.capture_cancel, null)
            .show()
    }

    private fun performExport() {
        val uri = exportTarget ?: return
        var written = 0
        var bytes = 0L

        val result = runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                val segmentsRoot = File(store.root(), CaptureStore.SEGMENTS_DIR)
                segmentsRoot.listFiles()?.forEach { sessionDir ->
                    sessionDir.listFiles()?.forEach { audioFile ->
                        val content = audioFile.readBytes()
                        stream.write(content)
                        bytes += content.size
                        written++
                    }
                }
            } ?: error("无法打开输出流")
        }

        if (result.isSuccess) {
            DiagnosticLog.i("Export", "音频导出完成, 文件数=" + written + ", 字节=" + bytes)
            statusText.setText(getString(R.string.export_audio_done, written, bytes / 1024))
        } else {
            val message = result.exceptionOrNull()?.message ?: "未知错误"
            DiagnosticLog.e("Export", "音频导出失败: " + message)
            statusText.setText(R.string.export_audio_failed)
        }
    }
}
