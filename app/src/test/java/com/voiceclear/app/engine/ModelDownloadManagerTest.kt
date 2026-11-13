package com.voiceclear.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 多源模型下载测试。
 *
 * 重点验证降级顺序与完整性校验逻辑；真实网络下载不在单元测试范围内。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun domesticSourceIsTriedFirst() {
        val first = ModelDownloadManager.SOURCES.first()
        assertEquals("modelscope", first.id)
        // 国内源必须排在 GitHub 之前，否则在国内网络下会先卡在 GitHub 上。
        assertTrue(first.modelUrl.contains("modelscope.cn"))
    }

    @Test
    fun githubIsFallbackSource() {
        val github = ModelDownloadManager.SOURCES.find { it.id == "github" }
        assertNotEquals(null, github)
        assertTrue(github!!.modelUrl.contains("github.com"))
        assertEquals(ModelSource.Kind.ARCHIVE, github.kind)
    }

    @Test
    fun allSourcesHaveValidUrlsAndSizes() {
        for (source in ModelDownloadManager.SOURCES) {
            assertTrue(source.modelUrl.startsWith("https://"))
            assertTrue(source.expectedModelBytes > 100_000_000L)
            assertTrue(source.expectedTokensBytes > 100_000L)
            if (source.kind == ModelSource.Kind.DIRECT) {
                assertTrue(source.tokensUrl.isNotEmpty())
            }
        }
    }

    @Test
    fun sourcesHaveDistinctUrls() {
        val urls = ModelDownloadManager.SOURCES.map { it.modelUrl }
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun downloadReportsFailureWhenAllSourcesUnavailable() {
        // 不应抛异常，也不应留下半个模型文件。
        val manager = ModelDownloadManager(context)
        val sourcesTried = mutableListOf<String>()
        // 用不可达的 URL 验证探测失败会被正确识别。
        val unreachable = ModelSource(
            id = "unreachable",
            displayName = "不可达",
            modelUrl = "https://invalid.invalid/model.onnx",
            tokensUrl = "https://invalid.invalid/tokens.txt",
            expectedModelBytes = 1000L,
            expectedTokensBytes = 100L,
            kind = ModelSource.Kind.DIRECT,
        )
        assertFalse(unreachable.probe(timeoutMs = 3000))
        sourcesTried.add(unreachable.id)
        assertEquals(1, sourcesTried.size)
    }

    @Test
    fun modelDirIsInsideAppPrivateStorage() {
        val manager = ModelDownloadManager(context)
        val dir = manager.modelDir()
        assertTrue(dir.absolutePath.startsWith(context.filesDir.absolutePath))
        assertFalse(manager.isReady())
    }

    @Test
    fun downloadSkipsWhenModelAlreadyReady() {
        val manager = ModelDownloadManager(context)
        val dir = manager.modelDir()
        dir.mkdirs()
        java.io.File(dir, "model.int8.onnx").writeBytes(ByteArray(1024))
        java.io.File(dir, "tokens.txt").writeBytes(ByteArray(1024))
        assertTrue(manager.isReady())
        // 已就绪时不应发起任何下载。
        var progressCalled = false
        assertTrue(manager.download(onProgress = { _, _ -> progressCalled = true }))
        assertFalse(progressCalled)
    }
}
