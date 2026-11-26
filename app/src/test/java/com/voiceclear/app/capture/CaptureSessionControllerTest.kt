package com.voiceclear.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class CaptureSessionControllerTest {

    private val prompt = CapturePrompt("P1", "daily", "今天的文本", "note")

    @Test
    fun `recording start failure restores the exact prior phase`() {
        val controller = CaptureSessionController("spk", "session", "2026-11-26")
        controller.selectPrompt(prompt)
        controller.startRecording()
        controller.rollbackRecordingStart()
        assertEquals(CaptureSessionController.Phase.PROMPT_SELECTED, controller.phase)

        controller.startRecording()
        submit(controller)
        controller.startRecording()
        controller.rollbackRecordingStart()
        assertEquals(CaptureSessionController.Phase.SAVED, controller.phase)
        controller.startRecording()
        assertEquals(CaptureSessionController.Phase.RECORDING, controller.phase)
    }

    @Test
    fun `recording rejects dangerous prompt and mode switches`() {
        val controller = CaptureSessionController("spk", "session", "2026-11-26")
        controller.selectPrompt(prompt)
        controller.startRecording()
        assertThrows(IllegalStateException::class.java) { controller.selectPrompt(null) }
        assertThrows(IllegalStateException::class.java) { controller.selectPrompt(prompt.copy(id = "P2")) }
        assertEquals(prompt, controller.currentPrompt)
    }

    @Test
    fun `empty consent cannot be submitted as a research segment`() {
        val controller = CaptureSessionController("spk", "session", "2026-11-26")
        controller.selectPrompt(prompt)
        controller.startRecording()
        assertThrows(IllegalArgumentException::class.java) {
            controller.submitSegment(
                durationMs = 1000,
                sampleRate = 16000,
                qualityFlags = emptyList(),
                intendedText = null,
                confirmed = false,
                fatigueLevel = null,
                environment = "unspecified",
                consentFlags = emptyList(),
            )
        }
        assertTrue(controller.collectedSegments().isEmpty())
    }

    @Test
    fun `submitted segment has an independent raw audio path`() {
        val controller = CaptureSessionController("spk", "session", "2026-11-26")
        controller.selectPrompt(prompt)
        controller.startRecording()
        val segment = submit(controller)
        assertEquals("segments/session/raw/${segment.segmentId}.pcm", segment.audioFileName)
        assertTrue(segment.audioAvailable)
        assertEquals("mandarin", segment.speechProfileId)
        assertEquals("zh-CN", segment.localeTag)
        assertEquals("unspecified", segment.dialectGroup)
    }

    @Test
    fun `Shandong segment exports profile and dialect group`() {
        val controller = CaptureSessionController(
            speakerId = "spk",
            sessionId = "session",
            sessionDate = "2026-11-26",
            speechProfileId = "shandong",
            localeTag = "zh-CN",
            dialectGroup = "zhongyuan",
        )
        controller.selectPrompt(prompt.copy(id = "SD0001"))
        controller.startRecording()
        val segment = submit(controller)
        assertEquals("shandong", segment.speechProfileId)
        assertEquals("zh-CN", segment.localeTag)
        assertEquals("zhongyuan", segment.dialectGroup)
    }

    @Test
    fun `Mandarin rejects a dialect subgroup`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSessionController(
                speakerId = "spk",
                sessionId = "session",
                sessionDate = "2026-11-26",
                speechProfileId = "mandarin",
                dialectGroup = "jilu",
            )
        }
    }

    @Test
    fun `intent confirmation updates both reference and intended text`() {
        val controller = CaptureSessionController("spk", "session", "2026-11-26")
        controller.selectPrompt(null)
        controller.startRecording()
        submit(controller)
        val updated = controller.confirmIntent("  我原本想说的话  ")
        assertEquals("我原本想说的话", updated?.referenceText)
        assertEquals("我原本想说的话", updated?.intendedText)
        assertEquals(ReferenceSource.USER_CONFIRMED, updated?.referenceSource)
    }

    private fun submit(controller: CaptureSessionController): CaptureSegment =
        controller.submitSegment(
            durationMs = 1000,
            sampleRate = 16000,
            qualityFlags = emptyList(),
            intendedText = null,
            confirmed = false,
            fatigueLevel = null,
            environment = "unspecified",
            consentFlags = listOf(ConsentFlags.RESEARCH_USE),
        )
}
