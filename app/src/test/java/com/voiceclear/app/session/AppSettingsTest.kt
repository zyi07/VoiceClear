package com.voiceclear.app.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voiceclear.app.engine.SpeechProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearSettings() {
        context.getSharedPreferences("voiceclear_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun cleanUp() = clearSettings()

    @Test
    fun `speech profile persists across settings instances and is exported`() {
        val settings = AppSettings(context)
        assertEquals(SpeechProfile.MANDARIN, settings.speechProfile)

        settings.speechProfile = SpeechProfile.SHANDONG

        val restored = AppSettings(context)
        assertEquals(SpeechProfile.SHANDONG, restored.speechProfile)
        assertTrue(restored.exportJson().contains("\"speechProfile\":\"shandong\""))
    }

    @Test
    fun `legacy unknown profile restores Mandarin`() {
        context.getSharedPreferences("voiceclear_settings", Context.MODE_PRIVATE)
            .edit().putString("speech_profile_id", "legacy-unknown").commit()
        assertEquals(SpeechProfile.MANDARIN, AppSettings(context).speechProfile)
    }
}
