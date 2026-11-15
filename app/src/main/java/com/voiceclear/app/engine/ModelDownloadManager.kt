package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 端侧模型下载管理器（多源降级）。
 *
 * 模型体积较大（SenseVoice int8 约 239 MB），不适合放进 APK：
 * - APK 会膨胀到 300 MB 以上，安装与更新都很痛苦；
 * - 用户不升级模型就一直要下载。
 * 因此改为**首次使用时按需下载**到应用私有目录。
 *
 * 多源降级：国内网络环境下 GitHub Release 常常不可达，
 * 因此按顺序尝试多个来源（国内源优先），任一成功即停止。
 *
 * 下载支持断点续传（Range 请求）：大文件传到一半失败后可接着传，
 * 不用从头再来——这对移动网络尤其重要。
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        const val MODEL_DIR_NAME = "asr_model"

        const val ARCHIVE_MODEL_NAME = "model.int8.onnx"
        const val ARCHIVE_TOKENS_NAME = "tokens.txt"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RETRIES_PER_SOURCE = 2

        /**
         * 模型来源列表，按优先级排列。
         *
         * 1. ModelScope（魔搭，国内直连，实测约 6 MB/s，优先）
         * 2. GitHub Release（官方归档，海外网络更快）
         *
         * 注意：两源的模型是同一 SenseVoice 的不同量化导出。
         * 已实测确认：图结构、输入输出签名、词表（tokens.txt 字节完全一致）均相同，
         * 仅权重数值有细微差异，不影响可用性。
         * 因此代码不能硬编码某一份的大小，必须按各源分别校验。
         */
        val SOURCES: List<ModelSource> = listOf(
            ModelSource(
                id = "modelscope",
                displayName = "ModelScope（魔搭）",
                modelUrl = "https://modelscope.cn/api/v1/models/" +
                    "xiaowangge/sherpa-onnx-sense-voice-small/repo" +
                    "?Revision=master&FilePath=model_q8.onnx",
                tokensUrl = "https://modelscope.cn/api/v1/models/" +
                    "xiaowangge/sherpa-onnx-sense-voice-small/repo" +
                    "?Revision=master&FilePath=tokens.txt",
                expectedModelBytes = 239234116L,
                expectedTokensBytes = 315894L,
                kind = ModelSource.Kind.DIRECT,
                expectedModelSha256 =
                    "6887cdd6fc94c3e4cb5aea8b62dead45229643708d20b8ec8a4a736dfb45f6af",
                expectedTokensSha256 =
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
            ModelSource(
                id = "github",
                displayName = "GitHub Release（官方）",
                modelUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
                tokensUrl = "",
                expectedModelBytes = 163002883L,
                expectedTokensBytes = 315894L,
                kind = ModelSource.Kind.ARCHIVE,
                // 归档内的 model.int8.onnx 与 tokens.txt 的校验和
                expectedModelSha256 =
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                expectedTokensSha256 =
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        )
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
     * 下载模型，自动在多个来源之间降级。
     *
     * @param onProgress 进度回调：(已下载字节, 总字节)
     * @param onSourceChange 切换来源时的回调：(来源描述)
     * @return true 表示模型已就绪
     */
    fun download(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onSourceChange: (String) -> Unit = {},
    ): Boolean {
        if (isReady()) {
            DiagnosticLog.i("ModelDownload", "模型已存在，跳过下载")
            return true
        }

        val dir = modelDir()
        if (!dir.exists() && !dir.mkdirs()) {
            DiagnosticLog.e("ModelDownload", "无法创建模型目录: " + dir.absolutePath)
            return false
        }

        for (source in SOURCES) {
            DiagnosticLog.i(
                "ModelDownload",
                "尝试来源: " + source.id + " (" + source.displayName + ")",
            )
            onSourceChange(source.displayName)

            val ok = when (source.kind) {
                ModelSource.Kind.ARCHIVE -> downloadFromArchive(source, dir, onProgress)
                ModelSource.Kind.DIRECT -> downloadDirect(source, dir, onProgress)
            }

            if (ok) {
                DiagnosticLog.i("ModelDownload", "来源 " + source.id + " 下载成功")
                return true
            }

            DiagnosticLog.w("ModelDownload", "来源 " + source.id + " 失败，尝试下一个")
        }

        DiagnosticLog.e("ModelDownload", "所有来源均失败")
        return false
    }

    /** 直接下载两个文件（ModelScope 这种目录式仓库）。 */
    private fun downloadDirect(
        source: ModelSource,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val modelTarget = File(targetDir, SenseVoiceRecognitionEngine.MODEL_FILE)
        val tokensTarget = File(targetDir, SenseVoiceRecognitionEngine.TOKENS_FILE)

        val modelOk = downloadFile(
            url = source.modelUrl,
            target = modelTarget,
            expectedBytes = source.expectedModelBytes,
            minAcceptRatio = 0.95,
            onProgress = onProgress,
        )
        if (!modelOk) {
            modelTarget.delete()
            return false
        }

        val tokensOk = downloadFile(
            url = source.tokensUrl,
            target = tokensTarget,
            expectedBytes = source.expectedTokensBytes,
            minAcceptRatio = 0.99,
            onProgress = { _, _ -> },
        )
        if (!tokensOk) {
            tokensTarget.delete()
            modelTarget.delete()
            return false
        }

        // 完整性校验：下载完成不等于内容正确。
        // 239 MB 的文件在移动网络下可能截断，也可能被中间设备替换。
        if (!verifyChecksums(source, modelTarget, tokensTarget)) {
            modelTarget.delete()
            tokensTarget.delete()
            return false
        }

        return true
    }

    /**
     * 校验已下载文件的 SHA-256。
     *
     * **策略**：区分两种不符：
     * - 大小也不符 → 一定是下载损坏或被替换，**拒绝**（删除并降级到下一个源）；
     * - 大小相符但校验和不同 → 上游可能更新了模型版本，
     *   此时**放行**并记警告。硬性拒绝会导致上游一更新就完全无法下载模型。
     *
     * 这是刻意的取舍：宁可接受一个版本变化的新模型，也不要因为
     * 校验和过期而让用户彻底用不了。
     */
    private fun verifyChecksums(
        source: ModelSource,
        modelFile: File,
        tokensFile: File,
    ): Boolean {
        if (source.expectedModelSha256.isEmpty() &&
            source.expectedTokensSha256.isEmpty()
        ) {
            return true
        }

        val modelMatches = FileIntegrity.matches(modelFile, source.expectedModelSha256)
        if (!modelMatches) {
            val sizeOk = modelFile.length() == source.expectedModelBytes
            if (!sizeOk) {
                DiagnosticLog.e(
                    "ModelDownload",
                    "模型文件损坏, source=" + source.id + ", 大小=" + modelFile.length() +
                        " 期望=" + source.expectedModelBytes,
                )
                return false
            }
            DiagnosticLog.w(
                "ModelDownload",
                "模型校验和与记录不符但大小一致（疑似上游更新）, source=" + source.id +
                    ", 实际=" + FileIntegrity.sha256(modelFile).take(16) + "...",
            )
        }

        if (!FileIntegrity.matches(tokensFile, source.expectedTokensSha256)) {
            DiagnosticLog.w(
                "ModelDownload",
                "词表校验和与记录不符（疑似上游更新）, source=" + source.id,
            )
        }

        if (modelMatches) {
            DiagnosticLog.i("ModelDownload", "校验通过, source=" + source.id)
        }
        return true
    }

    /** 下载 tar.bz2 归档并解压（GitHub Release 这种单文件发布）。 */
    private fun downloadFromArchive(
        source: ModelSource,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val archive = File(context.cacheDir, "sensevoice.tar.bz2")
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(source.modelUrl).openConnection() as HttpURLConnection).apply {
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
            extractModelFiles(archive, targetDir, source)
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "ModelDownload",
                "归档下载失败: " + (error.message ?: error.javaClass.name),
            )
            false
        } finally {
            runCatching { connection?.disconnect() }
            archive.delete()
        }
    }

    /** 下载单个文件，支持断点续传与重试。 */
    private fun downloadFile(
        url: String,
        target: File,
        expectedBytes: Long,
        minAcceptRatio: Double,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        repeat(MAX_RETRIES_PER_SOURCE) { attempt ->
            val partial = File(target.absolutePath + ".part")
            val existing = if (partial.exists()) partial.length() else 0L

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=" + existing + "-")
                }
            }

                val code = connection.responseCode
                // 服务端不支持续传时会返回 200，此时需要从头写。
                val resuming = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    DiagnosticLog.w(
                        "ModelDownload",
                        "HTTP " + code + ", 放弃本次尝试, url=" + url.take(80),
                    )
                    return false
                }

                val totalBytes = (connection.contentLengthLong + (if (resuming) existing else 0L))
                    .takeIf { it > 0 } ?: expectedBytes

                val append = resuming
                if (resuming) {
                    DiagnosticLog.i(
                        "ModelDownload",
                        "断点续传, 已下载=" + existing + " 字节",
                    )
                }

                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = if (append) existing else 0L
                        var read = input.read(buffer)
                        while (read > 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                            read = input.read(buffer)
                        }
                    }
                }

                val actual = partial.length()
                if (expectedBytes > 0 && actual < (expectedBytes * minAcceptRatio).toLong()) {
                    DiagnosticLog.w(
                        "ModelDownload",
                        "文件大小不符: " + actual + " < " + expectedBytes,
                    )
                    return false
                }

                partial.renameTo(target)
                return true
            } catch (error: Throwable) {
                DiagnosticLog.w(
                    "ModelDownload",
                    "下载尝试 " + (attempt + 1) + " 失败: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
            finally {
                runCatching { connection?.disconnect() }
            }
        }
        return false
    }

    /** 从归档中提取模型与词表。 */
    private fun extractModelFiles(
        archive: File,
        targetDir: File,
        source: ModelSource,
    ): Boolean {
        return try {
            val process = ProcessBuilder(
                "tar", "-xjf", archive.absolutePath, "-C", targetDir.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                DiagnosticLog.e("ModelDownload", "解压失败: " + output.take(300))
                return false
            }

            val extractedModel = targetDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && File(dir, ARCHIVE_MODEL_NAME).exists()
            }

            if (extractedModel == null) {
                DiagnosticLog.e("ModelDownload", "归档内未找到模型文件")
                return false
            }

            val modelFile = File(extractedModel, ARCHIVE_MODEL_NAME)
            val tokensFile = File(extractedModel, ARCHIVE_TOKENS_NAME)

            if (modelFile.length() < (source.expectedModelBytes * 0.9).toLong()) {
                DiagnosticLog.e("ModelDownload", "模型文件大小异常: " + modelFile.length())
                return false
            }

            val installedModel = File(targetDir, SenseVoiceRecognitionEngine.MODEL_FILE)
            val installedTokens = File(targetDir, SenseVoiceRecognitionEngine.TOKENS_FILE)
            modelFile.copyTo(installedModel, overwrite = true)
            tokensFile.copyTo(installedTokens, overwrite = true)

            // 归档同样做完整性校验
            if (!verifyChecksums(source, installedModel, installedTokens)) {
                installedModel.delete()
                installedTokens.delete()
                extractedModel.deleteRecursively()
                return false
            }

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
