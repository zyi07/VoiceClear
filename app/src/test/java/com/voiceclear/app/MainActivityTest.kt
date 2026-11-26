package com.voiceclear.app

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.engine.MemoryGuard
import com.voiceclear.app.engine.ModelDownloadManager
import com.voiceclear.app.engine.RecognitionEngineFactory
import com.voiceclear.app.engine.SenseVoiceNativeAdapter
import com.voiceclear.app.engine.SenseVoiceNativeFactory
import com.voiceclear.app.engine.SenseVoiceNativeResult
import com.voiceclear.app.engine.SenseVoiceRecognitionEngine
import com.voiceclear.app.engine.SpeechProfile
import com.voiceclear.app.session.AppSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("voiceclear_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `returning from capture reloads shared profile and refreshes button`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity).get(MainViewModel::class.java)
        val dir = File.createTempFile("main-profile-return", ".dir").apply {
            delete()
            mkdirs()
        }
        File(dir, SenseVoiceRecognitionEngine.MODEL_FILE).writeBytes(byteArrayOf(1))
        File(dir, SenseVoiceRecognitionEngine.TOKENS_FILE).writeBytes(byteArrayOf(1))

        try {
            viewModel.recognitionMemoryAssessor = { _, _ ->
                MemoryGuard.Assessment(
                    verdict = MemoryGuard.Verdict.OK,
                    modelMb = 0L,
                    requiredMb = 128L,
                    availableMb = 1_024L,
                    totalRamMb = 2_048L,
                    heapMaxMb = 512L,
                    lowMemoryDevice = false,
                )
            }
            viewModel.recognitionEngineCreator = { creatorContext, profile ->
                val engine = SenseVoiceRecognitionEngine(
                    dir.absolutePath,
                    SenseVoiceNativeFactory {
                        object : SenseVoiceNativeAdapter {
                            override fun decode(samples: FloatArray, sampleRate: Int) =
                                SenseVoiceNativeResult("测试", "zh")

                            override fun release() = Unit
                        }
                    },
                    profile,
                )
                RecognitionEngineFactory.Created(
                    engine = engine,
                    mode = RecognitionEngineFactory.MODE_REAL,
                    modelDownloadManager = ModelDownloadManager(creatorContext),
                )
            }
            viewModel.ensureRecognitionEngine()
            assertTrue(awaitProfile(viewModel, SpeechProfile.MANDARIN, retryEnsure = true))
            val button = activity.findViewById<MaterialButton>(R.id.speechSettingsButton)
            assertEquals(
                activity.getString(
                    R.string.speech_profile_current,
                    SpeechProfile.MANDARIN.displayName,
                ),
                button.text.toString(),
            )

            // 模拟「主页 → 采集页切换共享设置 → 返回主页」；这里直接复用真实持久化入口。
            AppSettings(activity).speechProfile = SpeechProfile.SHANDONG
            controller.pause().resume()

            assertTrue(awaitProfile(viewModel, SpeechProfile.SHANDONG))
            assertEquals(SpeechProfile.SHANDONG, viewModel.activeEngine.let {
                (it as SenseVoiceRecognitionEngine).profile
            })
            assertEquals(
                activity.getString(
                    R.string.speech_profile_current,
                    SpeechProfile.SHANDONG.displayName,
                ),
                button.text.toString(),
            )
        } finally {
            controller.destroy()
            dir.deleteRecursively()
        }
    }

    private fun awaitProfile(
        viewModel: MainViewModel,
        profile: SpeechProfile,
        retryEnsure: Boolean = false,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (viewModel.senseVoiceEngine?.profile != profile &&
            System.currentTimeMillis() < deadline
        ) {
            // 仅初始化时允许清掉 Activity setup 触发的无模型加载；返回路径不得借此掩盖漏调用。
            if (retryEnsure) viewModel.ensureRecognitionEngine()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return viewModel.senseVoiceEngine?.profile == profile
    }
}
