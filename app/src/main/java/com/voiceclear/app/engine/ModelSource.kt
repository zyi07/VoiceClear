package com.voiceclear.app.engine

import com.voiceclear.app.session.DiagnosticLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一个模型下载源。
 *
 * 模型文件较大（SenseVoice int8 约 239 MB），单一来源在某些网络环境下不可用，
 * 因此定义多个来源并按顺序降级。
 */
class ModelSource(
    /** 来源标识，用于日志。 */
    val id: String,

    /** 人类可读描述，用于界面提示。 */
    val displayName: String,

    /** 模型文件 URL（DIRECT 类型）或归档 URL（ARCHIVE 类型）。 */
    val modelUrl: String,

    /** 词表文件 URL，ARCHIVE 类型不需要（归档内自带）。 */
    val tokensUrl: String,

    /** 期望的文件大小（字节），用于完整性校验。 */
    val expectedModelBytes: Long,

    /** 期望的词表大小（字节）。 */
    val expectedTokensBytes: Long,

    /** 来源类型。 */
    val kind: Kind,

    /**
     * 模型文件的期望 SHA-256。
     *
     * 用途：检测下载损坏或文件被替换。
     * 留空表示跳过校验（仅用于临时测试源）。
     *
     * 实测值：
     * - ModelScope model_q8.onnx：6887cdd6...f45f6af
     * - GitHub model.int8.onnx：c71f0ce0...2cd51
     */
    val expectedModelSha256: String = "",

    /**
     * 词表文件的期望 SHA-256。
     *
     * 两个来源的 tokens.txt 实测**完全相同**（f449eb28...29a1dc）。
     */
    val expectedTokensSha256: String = "",
) {

    /** 来源类型。 */
    enum class Kind {
        /** 直接下载模型与词表两个文件。 */
        DIRECT,

        /** 下载 tar.bz2 归档后本地解压。 */
        ARCHIVE,
    }

    /**
     * 连通性探测：只取文件头部若干字节，不下载整个模型。
     *
     * 用 Range 请求实现，这样探测本身很快（几百毫秒）。
     */
    fun probe(timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-1023")
            }
            val code = connection.responseCode
            // 206 Partial Content 或 200 OK 都算可达。
            val ok = code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK
            if (!ok) {
                DiagnosticLog.w(
                    "ModelSource",
                    id + " 探测失败, HTTP " + code + ", 地址=" + modelUrl.take(60),
                )
            }
            ok
        } catch (error: Throwable) {
            DiagnosticLog.w(
                "ModelSource",
                id + " 探测异常: " + (error.message ?: error.javaClass.simpleName),
            )
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 12_000
    }
}
