package com.voiceclear.app.capture

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.voiceclear.app.R
import com.voiceclear.app.engine.SpeechProfile
import com.voiceclear.app.session.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class CaptureActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.getSharedPreferences("voiceclear_capture", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("voiceclear_settings", Context.MODE_PRIVATE).edit().clear().commit()
        CaptureStore(context).clearAll()
        ShadowAlertDialog.reset()
    }

    @Test
    fun `real Activity starts with denied consent and saving defaults adds no date`() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        val activity = controller.get()
        val speakerId = activity.getSharedPreferences("voiceclear_capture", Context.MODE_PRIVATE)
            .getString("speaker_id", null)!!
        val store = CaptureStore(context)

        val initial = store.readConsent(speakerId)!!
        assertEquals("", initial.signedAt)
        ConsentFlags.ALL.forEach { assertFalse(initial.grants[it] == true) }

        activity.findViewById<MaterialButton>(R.id.captureRecordButton).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertFalse(dialog.listView.isItemChecked(0))
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val saved = store.readConsent(speakerId)!!
        assertEquals("", saved.signedAt)
        ConsentFlags.ALL.forEach { assertFalse(saved.grants[it] == true) }
        assertEquals(
            activity.getString(R.string.capture_consent_needed),
            activity.findViewById<TextView>(R.id.captureStatus).text.toString(),
        )
        controller.destroy()
    }

    @Test
    fun `real category dialog opens the same category it displays`() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        val activity = controller.get()
        val prompts = PromptLibrary.parse(
            activity.assets.open(PromptLibrary.ASSET_PATH).bufferedReader().use { it.readText() },
        )
        val firstCategory = PromptLibrary.groupByCategory(prompts).entries.first()

        activity.findViewById<MaterialButton>(R.id.promptButton).performClick()
        val categoryDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val displayed = categoryDialog.listView.adapter.getItem(0).toString()
        assertTrue(displayed.startsWith(firstCategory.key + "（"))
        categoryDialog.listView.performItemClick(null, 0, 0)

        val promptDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals(firstCategory.value.first().text, promptDialog.listView.adapter.getItem(0))
        promptDialog.listView.performItemClick(null, 0, 0)

        assertEquals(
            firstCategory.value.first().text,
            activity.findViewById<TextView>(R.id.currentPromptText).text.toString(),
        )
        assertTrue(
            activity.findViewById<TextView>(R.id.currentPromptNote).text.contains(firstCategory.value.first().id),
        )
        controller.destroy()
    }

    @Test
    fun `profile switch rebuilds session and selects Shandong prompts and dialect group`() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        val activity = controller.get()
        val originalSession = activity.currentSessionId

        activity.findViewById<MaterialButton>(R.id.speechProfileButton).performClick()
        val profileDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        profileDialog.listView.performItemClick(null, 1, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SpeechProfile.SHANDONG, AppSettings(context).speechProfile)
        assertNotEquals(originalSession, activity.currentSessionId)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.dialectGroupButton).visibility)

        activity.findViewById<MaterialButton>(R.id.dialectGroupButton).performClick()
        val dialectDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("暂未确认", dialectDialog.listView.adapter.getItem(0).toString())
        dialectDialog.listView.performItemClick(null, 1, 1)
        assertTrue(
            activity.findViewById<MaterialButton>(R.id.dialectGroupButton).text.contains("冀鲁官话"),
        )

        activity.findViewById<MaterialButton>(R.id.promptButton).performClick()
        val categoryDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val shandongPrompts = PromptLibrary.parse(
            activity.assets.open(PromptLibrary.SHANDONG_ASSET_PATH).bufferedReader().use { it.readText() },
        )
        val firstCategory = PromptLibrary.groupByCategory(shandongPrompts).entries.first()
        assertTrue(categoryDialog.listView.adapter.getItem(0).toString().startsWith(firstCategory.key))
        controller.destroy()
    }
}
