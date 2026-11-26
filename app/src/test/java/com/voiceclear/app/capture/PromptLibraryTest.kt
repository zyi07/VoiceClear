package com.voiceclear.app.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class PromptLibraryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `both production libraries match declared versions and categories`() {
        listOf("mandarin", "shandong").forEach { profileId ->
            val json = context.assets.open(PromptLibrary.assetPathFor(profileId))
                .bufferedReader().use { it.readText() }
            val prompts = PromptLibrary.parse(json)
            assertEquals(PromptLibrary.versionFor(profileId), PromptLibrary.parseVersion(json))
            assertEquals(emptyList<String>(), PromptLibrary.missingCategories(prompts, profileId))
            assertEquals(prompts.size, prompts.map { it.id }.distinct().size)
            assertEquals(prompts.size, prompts.map { it.text }.distinct().size)
        }
    }

    @Test
    fun `Shandong library has at least thirty natural text prompts in six categories`() {
        val json = context.assets.open(PromptLibrary.SHANDONG_ASSET_PATH)
            .bufferedReader().use { it.readText() }
        val prompts = PromptLibrary.parse(json)
        assertTrue(prompts.size >= 30)
        assertEquals(PromptLibrary.SHANDONG_CATEGORIES.toSet(), prompts.map { it.category }.toSet())
        prompts.forEach { prompt ->
            assertTrue(prompt.id.startsWith("SD"))
            assertTrue(prompt.text.isNotBlank())
            assertTrue(prompt.text.none { it.isLetter() && it.code < 128 })
        }
    }

    @Test
    fun `profile paths and categories stay independent`() {
        assertEquals("prompts/prompts.json", PromptLibrary.assetPathFor("mandarin"))
        assertEquals("prompts/shandong.json", PromptLibrary.assetPathFor("shandong"))
        assertTrue("direction" in PromptLibrary.categoriesFor("shandong"))
        assertTrue("dialogue" in PromptLibrary.categoriesFor("shandong"))
        assertTrue("direction" !in PromptLibrary.categoriesFor("mandarin"))
    }
}
