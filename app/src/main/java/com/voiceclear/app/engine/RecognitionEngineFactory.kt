package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog

/**
 * 识别引擎工厂：按模型可用性选择真实引擎或假引擎。
 *
 * 设计意图：
 * - 模型未下载时**不能静默退回假引擎**——那会让用户以为识别结果是真实识别，
 *   属于严重误导。必须显式告知"模型未就绪"。
 * - 因此这里返回的结果里带有明确的模式标记，UI 必须展示。
 */
object RecognitionEngineFactory {

    const val MODE_REAL = "real"
    const val MODE_FAKE = "fake"

    /** 创建引擎。真实引擎在模型未就绪时返回 null。 */
    fun create(context: Context): Created? {
        val downloadManager = ModelDownloadManager(context)
        if (!downloadManager.isReady()) {
            DiagnosticLog.w(
                "Engine",
                "模型未就绪，不创建真实引擎（不退回假引擎以免误导）",
            )
            return null
        }

        val engine = SenseVoiceRecognitionEngine(
            modelDirPath = downloadManager.modelDir().absolutePath,
        )
        return Created(
            engine = engine,
            mode = MODE_REAL,
            modelDownloadManager = downloadManager,
        )
    }

    /** 已创建的引擎及其元信息。 */
    class Created(
        val engine: SenseVoiceRecognitionEngine,
        val mode: String,
        val modelDownloadManager: ModelDownloadManager,
    )
}
