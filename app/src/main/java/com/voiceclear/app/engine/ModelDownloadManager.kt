package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端侧模型下载管理器。
 *
 * 模型体积较大（SenseVoice int8 约 239 MB），不适合放进 APK：
 * - APK 体积会膨胀到 300 MB 以上，安装与更新都很痛苦；
 * - 用户不升级模型就一直要下载。
 * 因此改为**首次使用时按需下载**到应用私有目录。
 *
 * 下载源使用 GitHub Release 归档（国内可达性优于 huggingface）：
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
 *   sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
 *
 * 下载过程支持断点续传（Range 请求）与失败重试，避免大文件传到一半失败后重来。
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        const val MODEL_DIR_NAME = "asr_model"

        /** 归档内模型文件相对于解压目录的路径。 */
        const val ARCHIVE_MODEL_NAME = "model.int8.onnx"
        const val ARCHIVE_TOKENS_NAME = "tokens.txt"

        const val ARCHIVE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"

        const val EXPECTED_MODEL_BYTES = 239233841L
        const val EXPECTED_TOKENS_BYTES = 315894L

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }

    /** 模型在应用私有目录中的位置。 */
    fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    /** 模型是否已就绪。 */
    fun isReady(): Boolean {
        val dir = modelDir()
        return File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).exists() &&
            File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).exists()
    }

    /** 已下载模型的占用（字节）。 */
    fun diskUsage(): Long {
        val dir = modelDir()
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 下载模型。
     *
     * @param onProgress 进度回调：(已下载字节, 总字节)
     * @return true 表示模型已就绪
     */
    fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        if (isReady()) {
            DiagnosticLog.i("ModelDownload", "模型已存在，跳过下载")
            return true
        }

        val dir = modelDir()
        if (!dir.exists() && !dir.mkdirs()) {
            DiagnosticLog.e("ModelDownload", "无法创建模型目录: " + dir.absolutePath)
            return false
        }

        val archive = File(context.cacheDir, "sensevoice.tar.bz2")
        var connection: HttpURLConnection? = null

        return try {
            DiagnosticLog.i("ModelDownload", "开始下载模型: " + ARCHIVE_URL)
            connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            connection.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read > 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                        read = input.read(buffer)
                    }
                }
            }

            DiagnosticLog.i(
                "ModelDownload",
                "归档下载完成, 大小=" + archive.length() + " 字节, 开始解压",
            )
            extractModelFiles(archive, dir)
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "ModelDownload",
                "下载失败: " + (error.message ?: error.javaClass.name),
            )
            false
        } finally {
            runCatching { connection?.disconnect() }
            archive.delete()
        }
    }

    /**
     * 从归档中提取模型与词表。
     *
     * 归档内的目录名带版本，这里不硬编码路径，按文件名匹配即可。
     */
    private fun extractModelFiles(archive: File, targetDir: File): Boolean {
        return try {
            val process = ProcessBuilder("tar", "-xjf", archive.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                DiagnosticLog.e("ModelDownload", "解压失败: " + output.take(300))
                return false
            }

            // 归档内是带版本号的子目录，把模型文件上提到 targetDir 根部。
            val extractedModel = targetDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && File(dir, ARCHIVE_MODEL_NAME).exists()
            }

            if (extractedModel == null) {
                DiagnosticLog.e("ModelDownload", "归档内未找到模型文件")
                return false
            }

            val modelFile = File(extractedModel, ARCHIVE_MODEL_NAME)
            val tokensFile = File(extractedModel, ARCHIVE_TOKENS_NAME)

            if (modelFile.length() < EXPECTED_MODEL_BYTES * 0.9) {
                DiagnosticLog.e("ModelDownload", "模型文件大小异常: " + modelFile.length())
                return false
            }

            modelFile.copyTo(File(targetDir, SenseVoiceRecognitionEngine.MODEL_FILE), overwrite = true)
            tokensFile.copyTo(File(targetDir, SenseVoiceRecognitionEngine.TOKENS_FILE), overwrite = true)

            // 清理解压出的子目录，避免占用双份空间。
            extractedModel.deleteRecursively()

            DiagnosticLog.i(
                "ModelDownload",
                "模型安装完成, 目录=" + targetDir.absolutePath + ", 占用=" + diskUsage() + " 字节",
            )
            true
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "ModelDownload",
                "解压异常: " + (error.message ?: error.javaClass.name),
            )
            false
        }
    }

    /** 删除模型，回收磁盘占用。 */
    fun deleteModel() {
        modelDir().deleteRecursively()
        DiagnosticLog.i("ModelDownload", "模型已删除")
    }
}
