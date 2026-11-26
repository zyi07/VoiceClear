package com.voiceclear.app.engine

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 端侧模型下载管理器（多源降级）。
 *
 * 所有下载先写入独立 staging 目录，大小与两个 SHA-256 全部通过后才原子切换为正式目录。
 * 同一模型目录在进程内共享互斥锁，避免多个 Activity/线程写同一 .part 或归档。
 * isReady 每次复验文件类型、精确大小和哈希，损坏内容不会永久跳过下载。
 */
class ModelDownloadManager internal constructor(
    private val context: Context,
    private val sources: List<ModelSource>,
) {
    constructor(context: Context) : this(context, SOURCES)

    companion object {
        const val MODEL_DIR_NAME = "asr_model"
        const val ARCHIVE_MODEL_NAME = "model.int8.onnx"
        const val ARCHIVE_TOKENS_NAME = "tokens.txt"

        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RETRIES_PER_SOURCE = 2

        private val DOWNLOAD_LOCKS = ConcurrentHashMap<String, Any>()

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
                expectedModelSha256 =
                    "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
                expectedTokensSha256 =
                    "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        )
    }

    private val downloadLock = DOWNLOAD_LOCKS.computeIfAbsent(modelDir().absolutePath) { Any() }

    /** 模型在应用私有目录中的位置。 */
    fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    /** 模型是否已就绪：必须匹配至少一个可信来源的精确大小与两个 SHA-256。 */
    fun isReady(): Boolean = synchronized(downloadLock) { isReadyLocked() }

    private fun isReadyLocked(): Boolean {
        val dir = modelDir()
        val model = File(dir, SenseVoiceRecognitionEngine.MODEL_FILE)
        val tokens = File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE)
        return sources.any { source -> verifyFiles(source, model, tokens, logFailures = false) }
    }

    fun diskUsage(): Long {
        val dir = modelDir()
        if (!dir.exists()) return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** 下载并校验模型；仅正式目录完整可用时返回 true。 */
    fun download(
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onSourceChange: (String) -> Unit = {},
    ): Boolean = synchronized(downloadLock) {
        if (isReadyLocked()) {
            DiagnosticLog.i("ModelDownload", "模型已存在且复验通过，跳过下载")
            return true
        }

        for (source in sources) {
            DiagnosticLog.i(
                "ModelDownload",
                "尝试来源: " + source.id + " (" + source.displayName + ")",
            )
            onSourceChange(source.displayName)
            val staging = createStagingDirectory()
            if (staging == null) {
                DiagnosticLog.e("ModelDownload", "无法创建 staging 目录")
                continue
            }

            val ok = try {
                when (source.kind) {
                    ModelSource.Kind.ARCHIVE -> downloadFromArchive(source, staging, onProgress)
                    ModelSource.Kind.DIRECT -> downloadDirect(source, staging, onProgress)
                }
            } catch (error: Throwable) {
                DiagnosticLog.e(
                    "ModelDownload",
                    "来源异常: " + (error.message ?: error.javaClass.name),
                )
                false
            }

            if (ok && publishModel(staging, modelDir())) {
                DiagnosticLog.i("ModelDownload", "来源 " + source.id + " 下载并安装成功")
                return true
            }
            staging.deleteRecursively()
            DiagnosticLog.w("ModelDownload", "来源 " + source.id + " 失败，尝试下一个")
        }

        DiagnosticLog.e("ModelDownload", "所有来源均失败")
        return false
    }

    private fun createStagingDirectory(): File? {
        val parent = modelDir().parentFile ?: return null
        val staging = File(parent, MODEL_DIR_NAME + ".staging-" + UUID.randomUUID())
        return staging.takeIf { staging.mkdirs() }
    }

    /** 用同文件系统 rename 发布；失败时恢复旧目录。 */
    private fun publishModel(staging: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        val backup = File(parent, MODEL_DIR_NAME + ".backup-" + UUID.randomUUID())
        if (target.exists() && !target.renameTo(backup)) {
            DiagnosticLog.e("ModelDownload", "无法移走旧模型目录")
            return false
        }
        if (staging.renameTo(target)) {
            backup.deleteRecursively()
            return true
        }
        if (backup.exists()) backup.renameTo(target)
        DiagnosticLog.e("ModelDownload", "模型目录原子发布失败")
        return false
    }

    /** 直接下载两个文件到 staging，不在正式文件上原地覆盖。 */
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
            onProgress = onProgress,
        )
        if (!modelOk) return false

        val tokensOk = downloadFile(
            url = source.tokensUrl,
            target = tokensTarget,
            expectedBytes = source.expectedTokensBytes,
            onProgress = { _, _ -> },
        )
        if (!tokensOk) return false

        return verifyChecksums(source, modelTarget, tokensTarget)
    }

    /** 大小和任一 SHA-256 不符都失败关闭，不接受“同大小但哈希不同”。 */
    private fun verifyChecksums(source: ModelSource, modelFile: File, tokensFile: File): Boolean =
        verifyFiles(source, modelFile, tokensFile, logFailures = true).also { verified ->
            if (verified) DiagnosticLog.i("ModelDownload", "大小与校验和全部通过, source=" + source.id)
        }

    private fun verifyFiles(
        source: ModelSource,
        modelFile: File,
        tokensFile: File,
        logFailures: Boolean,
    ): Boolean {
        val modelSizeOk = modelFile.isFile && modelFile.length() == source.expectedModelBytes
        val tokensSizeOk = tokensFile.isFile && tokensFile.length() == source.expectedTokensBytes
        if (!modelSizeOk || !tokensSizeOk) {
            if (logFailures) {
                DiagnosticLog.e(
                    "ModelDownload",
                    "文件大小不符, source=" + source.id +
                        ", model=" + modelFile.length() + "/" + source.expectedModelBytes +
                        ", tokens=" + tokensFile.length() + "/" + source.expectedTokensBytes,
                )
            }
            return false
        }
        val modelMatches = FileIntegrity.matches(modelFile, source.expectedModelSha256)
        val tokensMatches = FileIntegrity.matches(tokensFile, source.expectedTokensSha256)
        if ((!modelMatches || !tokensMatches) && logFailures) {
            DiagnosticLog.e(
                "ModelDownload",
                "SHA-256 不符, source=" + source.id +
                    ", modelMatches=" + modelMatches + ", tokensMatches=" + tokensMatches,
            )
        }
        return modelMatches && tokensMatches
    }

    /** 归档下载、HTTP/截断重试、解压和校验均在 staging 中完成。 */
    private fun downloadFromArchive(
        source: ModelSource,
        targetDir: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        repeat(MAX_RETRIES_PER_SOURCE) { attempt ->
            val archive = try {
                File.createTempFile("sensevoice-" + attempt + "-", ".tar.bz2", context.cacheDir)
            } catch (error: Throwable) {
                DiagnosticLog.e("ModelDownload", "无法创建归档临时文件: " + error.message)
                return false
            }
            try {
                val downloaded = downloadFile(
                    url = source.modelUrl,
                    target = archive,
                    expectedBytes = 0L,
                    onProgress = onProgress,
                )
                if (downloaded && extractModelFiles(archive, targetDir, source)) return true
                targetDir.deleteRecursively()
                if (!targetDir.mkdirs()) return false
            } finally {
                archive.delete()
                File(archive.absolutePath + ".part").delete()
            }
        }
        return false
    }

    /** 下载单个文件；可重试 HTTP 与截断，支持 Range 续传及 416 重新开始。 */
    private fun downloadFile(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val partial = File(target.absolutePath + ".part")
        repeat(MAX_RETRIES_PER_SOURCE) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                if (expectedBytes > 0 && partial.length() > expectedBytes) partial.delete()
                val existing = if (partial.exists()) partial.length() else 0L
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    if (existing > 0) setRequestProperty("Range", "bytes=" + existing + "-")
                }

                val code = connection.responseCode
                if (code == 416) {
                    DiagnosticLog.w("ModelDownload", "HTTP 416，清除无效断点后重试")
                    partial.delete()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    DiagnosticLog.w(
                        "ModelDownload",
                        "HTTP " + code + ", 尝试 " + (attempt + 1) + ", url=" + url.take(80),
                    )
                    if (isRetryableHttpStatus(code)) return@repeat
                    return false
                }
                if (code == HttpURLConnection.HTTP_PARTIAL && existing == 0L) {
                    DiagnosticLog.w("ModelDownload", "无断点却收到 HTTP 206，拒绝并重试")
                    return@repeat
                }

                // 服务端忽略 Range 返回 200 时必须从头覆盖，不能追加。
                val resuming = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
                val totalBytes = (connection.contentLengthLong + (if (resuming) existing else 0L))
                    .takeIf { it > 0 } ?: expectedBytes
                connection.inputStream.use { input ->
                    FileOutputStream(partial, resuming).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = if (resuming) existing else 0L
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
                if (expectedBytes > 0 && actual != expectedBytes) {
                    DiagnosticLog.w(
                        "ModelDownload",
                        "文件大小不符，准备重试: " + actual + " != " + expectedBytes,
                    )
                    if (actual > expectedBytes) partial.delete()
                    return@repeat
                }
                if (target.exists() && !target.delete()) return@repeat
                if (!partial.renameTo(target)) {
                    DiagnosticLog.w("ModelDownload", "临时文件改名失败，准备重试")
                    return@repeat
                }
                return true
            } catch (error: Throwable) {
                DiagnosticLog.w(
                    "ModelDownload",
                    "下载尝试 " + (attempt + 1) + " 失败: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
        return false
    }

    private fun isRetryableHttpStatus(code: Int): Boolean =
        code == HttpURLConnection.HTTP_CLIENT_TIMEOUT || code == 429 || code >= 500

    /** 从归档提取到 staging，并对两个文件执行同一套严格校验。 */
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
            val installedModel = File(targetDir, SenseVoiceRecognitionEngine.MODEL_FILE)
            val installedTokens = File(targetDir, SenseVoiceRecognitionEngine.TOKENS_FILE)
            modelFile.copyTo(installedModel, overwrite = true)
            tokensFile.copyTo(installedTokens, overwrite = true)
            if (!verifyChecksums(source, installedModel, installedTokens)) return false

            extractedModel.deleteRecursively()
            DiagnosticLog.i("ModelDownload", "模型 staging 校验完成")
            true
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "ModelDownload",
                "解压异常: " + (error.message ?: error.javaClass.name),
            )
            false
        }
    }

    /** 删除正式模型；与下载使用同一互斥锁。 */
    fun deleteModel() {
        synchronized(downloadLock) {
            modelDir().deleteRecursively()
            DiagnosticLog.i("ModelDownload", "模型已删除")
        }
    }
}
