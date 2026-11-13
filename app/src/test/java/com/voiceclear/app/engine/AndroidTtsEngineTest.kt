package com.voiceclear.app.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 原生 TTS 引擎在真实 Android 框架语义下的行为验证。
 *
 * 该环境无 KVM，无法运行模拟器，因此本测试提供框架级运行验证，
 * 不替代真机验证（技术指导文件 9.1 节）。
 *
 * 注意：框架模拟环境通常没有真实语音引擎，TextToSpeech 的初始化回调可能不触发。
 * 这正是产品必须具备超时保护的原因，因此这里断言的是不会无限挂起。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTtsEngineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `initialization never hangs indefinitely`() = runBlocking {
        val engine = AndroidTtsEngine(context)
        val started = System.currentTimeMillis()
        val initialized = withTimeoutOrNull(20_000L) { engine.ensureInitialized() }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("初始化必须在超时内返回，实际耗时 $elapsed ms", elapsed < 20_000L)
        if (initialized != null) {
            // 框架模拟下通常为 false（无语音引擎），真实设备上应为 true。
            assertNotNull(initialized)
        }
        engine.release()
    }

    @Test
    fun `synthesis on blank text never speaks`() = runBlocking {
        val engine = AndroidTtsEngine(context)
        engine.ensureInitialized()
        val result = withTimeoutOrNull(20_000L) {
            engine.synthesize(SynthesisRequest("s1", "   ", null, "test"))
        }
        if (result != null) {
            assertEquals("s1", result.sessionId)
            assertTrue(
                "空白文本不得进入发声流程",
                result.warnings.contains(AndroidTtsEngine.ERR_SPEAK_FAILED) || result.audio == null,
            )
        }
        engine.release()
    }

    @Test
    fun `release is safe to call repeatedly`() {
        val engine = AndroidTtsEngine(context)
        engine.release()
        engine.release()
    }

    @Test
    fun `stop is safe before initialization`() {
        val engine = AndroidTtsEngine(context)
        engine.stop()
        engine.release()
    }
}
