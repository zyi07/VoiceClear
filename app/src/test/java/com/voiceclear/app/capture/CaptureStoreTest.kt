package com.voiceclear.app.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class CaptureStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: CaptureStore

    @Before
    fun setUp() {
        store = CaptureStore(context)
        store.clearAll()
    }

    @Test
    fun `consent defaults to all denied without a fabricated signature date`() {
        val consent = store.ensureConsent("spk_default")
        assertEquals("", consent.signedAt)
        ConsentFlags.ALL.forEach { assertFalse(consent.grants[it] == true) }
        assertFalse(store.hasResearchConsent("spk_default"))

        val exported = store.exportMetadata()
        val persisted = JSONObject(File(exported, "consent/spk_default.json").readText())
        assertEquals("", persisted.getString("signedAt"))
        val grants = persisted.getJSONObject("grants")
        ConsentFlags.ALL.forEach { assertFalse(grants.getBoolean(it)) }
        assertEquals(0, JSONObject(File(exported, "manifest.json").readText())
            .getJSONObject("totals").getInt("segments"))
    }

    @Test
    fun `saveSegment immediately links audio and transcript and upsert is idempotent`() {
        grantResearch("spk_1")
        val segment = sampleSegment("session_1", "seg_1", "spk_1")
        store.saveSegment(segment.sessionId, segment, byteArrayOf(1, 2, 3, 4))

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(store.root(), segment.audioFileName).readBytes())
        assertEquals(1, store.readAllSegments().size)
        assertEquals(1, store.statistics().segments)

        store.appendTranscript(segment.sessionId, segment.copy(referenceText = "更新后的文本"))
        store.appendTranscript(segment.sessionId, segment.copy(referenceText = "更新后的文本"))

        val lines = File(store.transcriptsDir(), "session_1.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertEquals("更新后的文本", JSONObject(lines.single()).getString("referenceText"))
        assertEquals(segment.audioFileName, store.readAllSegments().single().audioFileName)
    }

    @Test
    fun `withdrawal removes audio and records from every export`() {
        grantResearch("spk_1")
        val segment = sampleSegment("session_1", "seg_1", "spk_1")
        store.saveSegment(segment.sessionId, segment, ByteArray(32))
        store.exportMetadata()

        store.withdrawResearchData("spk_1", "2026-11-26T09:00:00+08:00")

        assertEquals(0, store.statistics().segments)
        assertFalse(File(store.root(), segment.audioFileName).exists())
        assertEquals("2026-11-26T09:00:00+08:00", store.readConsent("spk_1")?.withdrawnAt)
        val exported = store.exportMetadata()
        assertFalse(File(exported, "transcripts/session_1.jsonl").exists())
        assertFalse(File(exported, "segments").exists())
        assertFalse(File(exported, "speakers/spk_1.json").exists())
        val consent = JSONObject(File(exported, "consent/spk_1.json").readText())
        assertEquals("2026-11-26T09:00:00+08:00", consent.getString("withdrawnAt"))
    }

    @Test
    fun `ZIP contains independent audio entries and complete metadata`() {
        grantResearch("spk_1")
        val first = sampleSegment("session_1", "seg_1", "spk_1")
        val second = sampleSegment("session_2", "seg_2", "spk_1")
        val firstBytes = byteArrayOf(1, 1, 2, 2)
        val secondBytes = byteArrayOf(9, 8, 7)
        store.saveSegment(first.sessionId, first, firstBytes)
        store.saveSegment(second.sessionId, second, secondBytes)
        val output = ByteArrayOutputStream()

        val summary = store.writeDatasetZip(output)

        assertEquals(2, summary.segments)
        assertEquals((firstBytes.size + secondBytes.size).toLong(), summary.audioBytes)
        val entries = readZip(output.toByteArray())
        assertArrayEquals(firstBytes, entries.getValue(first.audioFileName))
        assertArrayEquals(secondBytes, entries.getValue(second.audioFileName))
        listOf(
            "manifest.json",
            "transcripts/session_1.jsonl",
            "transcripts/session_2.jsonl",
            "prompts/prompts.json",
            "prompts/shandong.json",
            "speakers/spk_1.json",
            "consent/spk_1.json",
            "splits/train.txt",
        ).forEach { path -> assertTrue("缺少 ZIP entry: $path", entries.containsKey(path)) }
        assertEquals(2, JSONObject(entries.getValue("manifest.json").decodeToString())
            .getJSONObject("totals").getInt("segments"))
    }

    @Test
    fun `metadata export preserves Shandong profile and dialect group`() {
        grantResearch("spk_sd")
        val segment = sampleSegment("session_sd", "seg_sd", "spk_sd").copy(
            promptId = "SD0001",
            speechProfileId = "shandong",
            localeTag = "zh-CN",
            dialectGroup = "jilu",
        )
        store.saveSegment(segment.sessionId, segment, ByteArray(32))

        val exported = store.exportMetadata()
        val transcript = JSONObject(
            File(exported, "transcripts/session_sd.jsonl").readLines().single(),
        )
        assertEquals("shandong", transcript.getString("speechProfileId"))
        assertEquals("zh-CN", transcript.getString("localeTag"))
        assertEquals("jilu", transcript.getString("dialectGroup"))
        val speaker = JSONObject(File(exported, "speakers/spk_sd.json").readText())
        assertEquals("shandong", speaker.getString("speechProfileId"))
        assertEquals("zh-CN", speaker.getString("localeTag"))
        assertEquals("jilu", speaker.getString("dialectGroup"))
        assertTrue(File(exported, "prompts/shandong.json").isFile)
        val manifest = JSONObject(File(exported, "manifest.json").readText())
        assertEquals(
            "听障相关发音不清的山东方言成人",
            manifest.getJSONObject("cohort").getString("target"),
        )
    }

    @Test
    fun `mixed profile manifest names both cohorts`() {
        grantResearch("spk_mix")
        val mandarin = sampleSegment("session_m", "seg_m", "spk_mix")
        val shandong = sampleSegment("session_sd", "seg_sd", "spk_mix").copy(
            promptId = "SD0001",
            speechProfileId = "shandong",
            dialectGroup = "jiaoliao",
        )
        store.saveSegment(mandarin.sessionId, mandarin, ByteArray(32))
        store.saveSegment(shandong.sessionId, shandong, ByteArray(32))

        val exported = store.exportMetadata()
        val manifest = JSONObject(File(exported, "manifest.json").readText())
        assertEquals(
            "听障相关发音不清的普通话与山东方言成人",
            manifest.getJSONObject("cohort").getString("target"),
        )
    }

    @Test
    fun `metadata uses real sessions and environment and never invents severity`() {
        grantResearch("spk_1")
        val first = sampleSegment("session_1", "seg_1", "spk_1").copy(
            sessionDate = "2026-11-20", environment = "unspecified",
        )
        val second = sampleSegment("session_2", "seg_2", "spk_1").copy(
            sessionDate = "2026-11-21", environment = "outdoor",
        )
        store.saveSegment(first.sessionId, first, ByteArray(4))
        store.saveSegment(second.sessionId, second, ByteArray(4))

        store.refreshMetadata(createdAt = "2026-11-26T09:00:00+08:00")

        val speaker = store.readSpeaker("spk_1")!!
        assertEquals(CaptureSpeaker.SEVERITY_LEVEL_UNSPECIFIED, speaker.severityLevel)
        assertEquals(CaptureSpeaker.SEVERITY_SOURCE_UNSPECIFIED, speaker.severitySource)
        assertEquals(2, speaker.sessionCount)
        assertEquals("2026-11-20", speaker.firstSessionDate)
        assertEquals(listOf("outdoor", "unspecified"), speaker.speakingEnvironment)
        val manifest = JSONObject(File(store.root(), "manifest.json").readText())
        assertEquals(2, manifest.getJSONObject("totals").getInt("sessions"))
        assertEquals(2, manifest.getJSONArray("speakers").getJSONObject(0).getInt("sessions"))
        assertEquals("unspecified", manifest.getJSONArray("speakers").getJSONObject(0)
            .getString("severityLevel"))
        assertEquals(
            "听障相关发音不清的普通话成人",
            manifest.getJSONObject("cohort").getString("target"),
        )
    }

    @Test
    fun `malformed transcript lines are not counted as valid segments`() {
        File(store.transcriptsDir(), "broken.jsonl").writeText("not-json\n{\"bad\":true}\n")
        assertEquals(0, store.statistics().segments)
        assertEquals(0L, store.statistics().totalMs)
    }

    private fun grantResearch(speakerId: String) {
        store.writeConsent(
            CaptureConsent(
                speakerId = speakerId,
                consentVersion = "v1.0",
                signedAt = "2026-11-20T10:00:00+08:00",
                grants = ConsentFlags.ALL.associateWith { it == ConsentFlags.RESEARCH_USE },
                withdrawnAt = null,
                withdrawalScopeNote = "note",
                compensation = "unspecified",
            ),
        )
    }

    private fun sampleSegment(sessionId: String, segmentId: String, speakerId: String) = CaptureSegment(
        segmentId = segmentId,
        sessionId = sessionId,
        speakerId = speakerId,
        sessionDate = "2026-11-20",
        scene = CaptureScene.READ,
        promptId = "P0001",
        referenceText = "测试",
        referenceSource = ReferenceSource.APPROVED_PROMPT,
        intendedText = null,
        durationMs = 1000,
        sampleRate = 16000,
        channels = 1,
        encoding = "pcm_s16",
        noiseEstimateDb = null,
        distanceRange = null,
        fatigueLevel = null,
        deviceModel = "test",
        environment = "unspecified",
        qualityFlags = emptyList(),
        annotatorId = "self",
        guideVersion = "v1.0",
        consentFlags = listOf(ConsentFlags.RESEARCH_USE),
        audioFileName = CaptureStore.audioRelativePath(sessionId, segmentId),
    )

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}
