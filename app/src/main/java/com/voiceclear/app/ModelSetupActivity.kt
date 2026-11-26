package com.voiceclear.app

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.engine.ModelDownloadManager
import com.voiceclear.app.engine.RecognitionEngineFactory
import com.voiceclear.app.engine.SenseVoiceRecognitionEngine
import com.voiceclear.app.session.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型准备界面：负责下载端侧识别模型并加载引擎。
 *
 * 为什么单独一个界面：
 * - 模型约 239 MB，下载可能耗时数分钟，需要明确的进度与可取消反馈；
 * - 模型未就绪时应用**不具备识别能力**，界面必须说清楚，不能让用户误以为可用；
 * - 下载与加载属于一次性准备，与日常使用流程分离，避免每次启动都重复处理。
 */
class ModelSetupActivity : ComponentActivity() {

    private lateinit var downloadManager: ModelDownloadManager
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_setup)

        downloadManager = ModelDownloadManager(applicationContext)
        statusText = findViewById(R.id.modelStatus)
        detailText = findViewById(R.id.modelDetail)
        progressBar = findViewById(R.id.modelProgress)
        downloadButton = findViewById(R.id.modelDownloadButton)

        findViewById<MaterialButton>(R.id.modelSkipButton).setOnClickListener {
            DiagnosticLog.i("ModelSetup", "用户选择跳过模型下载")
            finish()
        }

        downloadButton.setOnClickListener { onDownloadClicked() }

        refreshState()
    }

    private fun refreshState() {
        lifecycleScope.launch {
            // isReady 会复验大模型的大小与 SHA-256，不能阻塞 Activity 主线程。
            val ready = withContext(Dispatchers.IO) { downloadManager.isReady() }
            if (ready) {
                statusText.setText(R.string.model_ready)
                detailText.text = getString(
                    R.string.model_detail_ready,
                    formatSize(downloadManager.diskUsage()),
                )
                progressBar.visibility = View.GONE
                downloadButton.visibility = View.GONE
                DiagnosticLog.i("ModelSetup", "模型已就绪")
                finish()
                return@launch
            }

            statusText.setText(R.string.model_not_ready)
            detailText.setText(R.string.model_detail_not_ready)
            progressBar.visibility = View.GONE
            downloadButton.visibility = View.VISIBLE
        }
    }

    /** 网络是否可用。 */
    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onDownloadClicked() {
        if (!isNetworkAvailable()) {
            DiagnosticLog.w("ModelSetup", "网络不可用，提示用户")
            statusText.setText(R.string.model_no_network)
            detailText.setText(R.string.model_detail_no_network)
            return
        }
        downloadButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = false
        statusText.setText(R.string.model_downloading)
        DiagnosticLog.i("ModelSetup", "开始下载端侧识别模型")

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                downloadManager.download(
                    onProgress = { downloaded, total ->
                        runOnUiThread {
                            if (total > 0) {
                                progressBar.max = 100
                                progressBar.progress = ((downloaded * 100) / total).toInt()
                                detailText.text = getString(
                                    R.string.model_progress,
                                    formatSize(downloaded),
                                    formatSize(total),
                                )
                            } else {
                                progressBar.isIndeterminate = true
                                detailText.text = getString(
                                    R.string.model_progress_unknown,
                                    formatSize(downloaded),
                                )
                            }
                        }
                    },
                    onSourceChange = { sourceName ->
                        runOnUiThread {
                            detailText.text = getString(R.string.model_source, sourceName)
                        }
                    },
                )
            }

            if (ok) {
                DiagnosticLog.i("ModelSetup", "模型下载完成，开始加载引擎")
                statusText.setText(R.string.model_loading)
                progressBar.isIndeterminate = true

                val loaded = withContext(Dispatchers.IO) {
                    loadEngineBlocking()
                }
                if (loaded) {
                    DiagnosticLog.i("ModelSetup", "引擎加载成功")
                    setResult(RESULT_OK)
                    finish()
                } else {
                    DiagnosticLog.e("ModelSetup", "引擎加载失败")
                    statusText.setText(R.string.model_load_failed)
                    progressBar.visibility = View.GONE
                    downloadButton.isEnabled = true
                }
            } else {
                DiagnosticLog.e("ModelSetup", "模型下载失败")
                statusText.setText(R.string.model_download_failed)
                detailText.setText(R.string.model_detail_download_failed)
                progressBar.visibility = View.GONE
                downloadButton.isEnabled = true
            }
        }
    }

    /** 同步加载引擎（供后台线程调用）；临时 native 资源无论成败都必须释放。 */
    private fun loadEngineBlocking(): Boolean {
        val created = RecognitionEngineFactory.create(applicationContext) ?: return false
        return try {
            created.engine.load()
        } finally {
            created.engine.release()
        }
    }

    private fun formatSize(bytes: Long): String {
        return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
