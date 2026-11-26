package com.voiceclear.app.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSpeechEnginesTest {

    private val segment = AudioSegment(
        sessionId = "s1",
        sampleRate = 16_000,
        channelCount = 1,
        encoding = AudioEncoding.PCM_16BIT,
        durationMs = 500,
        samples = ByteArray(16_000),
    )

    @Test
    fun `recognition returns text and confidence`() = runTest {
        val engine = FakeRecognitionEngine(resultText = "今天下雨", confidence = 0.75f)
        val result = engine.recognize(
            RecognitionRequest("s1", segment, "fake-0.1.0"),
        )
        assertEquals("今天下雨", result.normalizedText)
        assertEquals(0.75f, result.confidence)
    }

    @Test
    fun `default recognition does not invent confidence`() = runTest {
        val engine = FakeRecognitionEngine()
        val result = engine.recognize(
            RecognitionRequest("s1", segment, "fake-0.1.0"),
        )
        assertNull(result.confidence)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `synthesis echoes exactly the confirmed text`() = runTest {
        val engine = FakeSynthesisEngine()
        engine.synthesize(SynthesisRequest("s1", "我需要帮助", null, "fake-0.1.0"))
        assertEquals("我需要帮助", engine.lastRequestedText)
    }

    @Test
    fun `synthesis produces playable audio for the same session`() = runTest {
        val engine = FakeSynthesisEngine()
        val result = engine.synthesize(SynthesisRequest("s1", "测试", null, "fake-0.1.0"))
        assertNotNull(result.audio)
        assertEquals("s1", result.audio?.sessionId)
        assertEquals(16_000, result.audio?.sampleRate)
        assertTrue(result.durationMs >= 0)
        assertEquals(result.audio?.durationMs, result.durationMs)
    }
}
