package com.voiceclear.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文件完整性校验与性能基准测试。
 */
class FileIntegrityTest {

    @Test
    fun `sha256 of known content matches expected value`() {
        val file = File.createTempFile("integrity", ".txt")
        try {
            file.writeText("abc")
            // 已知值：SHA-256("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileIntegrity.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `empty file has known digest`() {
        val file = File.createTempFile("integrity-empty", ".txt")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FileIntegrity.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing file returns empty digest`() {
        assertEquals("", FileIntegrity.sha256(File("/nonexistent/path/x.bin")))
    }

    @Test
    fun `matches accepts correct hash regardless of case`() {
        val file = File.createTempFile("integrity-case", ".txt")
        try {
            file.writeText("abc")
            val upper = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"
            assertTrue(FileIntegrity.matches(file, upper))
            assertFalse(FileIntegrity.matches(file, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `empty expected hash skips verification`() {
        val file = File.createTempFile("integrity-skip", ".txt")
        try {
            file.writeText("whatever")
            assertTrue("期望值为空时应跳过校验", FileIntegrity.matches(file, ""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `different content produces different digest`() {
        val first = File.createTempFile("integrity-a", ".txt")
        val second = File.createTempFile("integrity-b", ".txt")
        try {
            first.writeText("abc")
            second.writeText("abd")
            assertNotEquals(FileIntegrity.sha256(first), FileIntegrity.sha256(second))
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `all download sources declare expected checksums`() {
        // 模型下载后必须做完整性校验，否则损坏的模型会表现为"识别不出来"。
        for (source in ModelDownloadManager.SOURCES) {
            assertTrue(
                "来源 " + source.id + " 缺少模型校验和",
                source.expectedModelSha256.length == 64,
            )
            assertTrue(
                "来源 " + source.id + " 缺少词表校验和",
                source.expectedTokensSha256.length == 64,
            )
        }
    }

    @Test
    fun `token checksums are identical across sources`() {
        // 实测两个来源的 tokens.txt 字节完全一致。
        val checksums = ModelDownloadManager.SOURCES.map { it.expectedTokensSha256 }
        assertEquals(1, checksums.toSet().size)
    }
}
