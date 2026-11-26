package com.voiceclear.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 使用本机临时 HTTP 服务执行真实生产下载状态机，不访问外网。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var server: HttpServer? = null
    private val modelBytes = "model-content".toByteArray()
    private val tokensBytes = "tokens-content".toByteArray()

    @After
    fun tearDown() {
        server?.stop(0)
        ModelDownloadManager(context).deleteModel()
    }

    @Test
    fun domesticSourceIsTriedFirst() {
        val first = ModelDownloadManager.SOURCES.first()
        assertEquals("modelscope", first.id)
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
    fun modelDirIsInsideAppPrivateStorage() {
        val manager = ModelDownloadManager(context)
        assertTrue(manager.modelDir().absolutePath.startsWith(context.filesDir.absolutePath))
        assertFalse(manager.isReady())
    }

    @Test
    fun readinessRequiresExactSizeAndBothHashesAndDownloadSkipsOnlyWhenValid() {
        val manager = managerWith { exchange, _ -> respond(exchange, 404) }
        val dir = manager.modelDir().apply { mkdirs() }
        java.io.File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(modelBytes)
        java.io.File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(tokensBytes)
        assertTrue(manager.isReady())

        var progressCalled = false
        assertTrue(manager.download(onProgress = { _, _ -> progressCalled = true }))
        assertFalse(progressCalled)

        // 同大小替换也必须失败关闭。
        java.io.File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(
            modelBytes.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        assertFalse(manager.isReady())
        java.io.File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(modelBytes)
        java.io.File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(
            tokensBytes.copyOf().also { it[0] = (it[0] + 1).toByte() },
        )
        assertFalse(manager.isReady())
        java.io.File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(tokensBytes)
        java.io.File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(modelBytes.copyOf(2))
        assertFalse(manager.isReady())
    }

    @Test
    fun httpFailureIsRetriedAndVerifiedFilesArePublished() {
        val modelRequests = AtomicInteger(0)
        val manager = managerWith { exchange, path ->
            if (path == "/model" && modelRequests.incrementAndGet() == 1) {
                respond(exchange, 500)
            } else {
                respond(exchange, 200, if (path == "/model") modelBytes else tokensBytes)
            }
        }

        assertTrue(manager.download())
        assertEquals(2, modelRequests.get())
        assertTrue(manager.isReady())
        assertEquals(modelBytes.size.toLong(), manager.modelDir().let {
            java.io.File(it, SenseVoiceRecognitionEngine.MODEL_FILE).length()
        })
    }

    @Test
    fun truncatedDownloadIsRetriedAndCanRecoverWithFullHttp200() {
        val modelRequests = AtomicInteger(0)
        val manager = managerWith { exchange, path ->
            if (path == "/model") {
                val attempt = modelRequests.incrementAndGet()
                respond(exchange, 200, if (attempt == 1) modelBytes.copyOf(3) else modelBytes)
            } else {
                respond(exchange, 200, tokensBytes)
            }
        }

        assertTrue(manager.download())
        assertEquals(2, modelRequests.get())
        assertTrue(manager.isReady())
    }

    @Test
    fun sameSizeWrongDownloadedHashFailsClosedWithoutPublishing() {
        val manager = managerWith { exchange, path ->
            if (path == "/model") {
                respond(exchange, 200, modelBytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
            } else {
                respond(exchange, 200, tokensBytes)
            }
        }

        assertFalse(manager.download())
        assertFalse(manager.isReady())
        assertFalse(manager.modelDir().exists())
    }

    @Test
    fun concurrentManagersDoNotWriteOrDownloadTheSameFilesTwice() {
        val modelRequests = AtomicInteger(0)
        val tokensRequests = AtomicInteger(0)
        val manager = managerWith { exchange, path ->
            if (path == "/model") {
                modelRequests.incrementAndGet()
                Thread.sleep(75)
                respond(exchange, 200, modelBytes)
            } else {
                tokensRequests.incrementAndGet()
                respond(exchange, 200, tokensBytes)
            }
        }
        val secondManager = ModelDownloadManager(context, listOf(source()))
        val results = Collections.synchronizedList(mutableListOf<Boolean>())
        val start = CountDownLatch(1)
        val threads = listOf(manager, secondManager).map { item ->
            Thread {
                start.await()
                results.add(item.download())
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5_000) }

        assertTrue(threads.none { it.isAlive })
        assertEquals(2, results.size)
        assertTrue(results.all { it })
        assertEquals(1, modelRequests.get())
        assertEquals(1, tokensRequests.get())
        assertTrue(manager.isReady())
    }

    private fun managerWith(handler: (HttpExchange, String) -> Unit): ModelDownloadManager {
        startServer(handler)
        return ModelDownloadManager(context, listOf(source()))
    }

    private fun source(): ModelSource {
        val address = checkNotNull(server?.address) { "HTTP 服务尚未启动" }
        val prefix = "http://127.0.0.1:" + address.port
        return ModelSource(
            id = "local-test",
            displayName = "本机测试源",
            modelUrl = prefix + "/model",
            tokensUrl = prefix + "/tokens",
            expectedModelBytes = modelBytes.size.toLong(),
            expectedTokensBytes = tokensBytes.size.toLong(),
            kind = ModelSource.Kind.DIRECT,
            expectedModelSha256 = sha256(modelBytes),
            expectedTokensSha256 = sha256(tokensBytes),
        )
    }

    private fun startServer(handler: (HttpExchange, String) -> Unit) {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = created
        created.createContext("/") { exchange ->
            try {
                handler(exchange, exchange.requestURI.path)
            } catch (error: IOException) {
                exchange.close()
            }
        }
        created.start()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
        if (status == 200) {
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } else {
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
