package com.voiceclear.app.engine

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 文件校验工具。
 *
 * 用途：检测模型下载是否损坏、文件是否被替换。
 * 模型是运行时下载的（239 MB），不校验就可能把损坏的模型当成好的用，
 * 表现为「识别不出来」这类极难定位的问题。
 */
object FileIntegrity {

    /**
     * 计算文件的 SHA-256。
     *
     * @return 十六进制小写字符串；文件不存在或读取失败时返回空串。
     */
    fun sha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        } catch (error: Throwable) {
            ""
        }
    }

    /**
     * 校验文件与期望的 SHA-256 是否一致。
     *
     * 期望值为空时视为通过（跳过校验）。
     */
    fun matches(file: File, expectedSha256: String): Boolean {
        if (expectedSha256.isEmpty()) return true
        if (!file.exists()) return false
        val actual = sha256(file)
        if (actual.isEmpty()) return false
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}
