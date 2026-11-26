package com.voiceclear.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 采集数据序列化测试。
 *
 * 关键要求：App 导出的 JSON 必须能被 [tools/validate_dataset.py] 直接接受，
 * 因此这里断言字段名与 schema 完全一致，而不是仅断言有值。
 *
 * 必须使用 Robolectric：Android SDK 的 android.jar 中 org.json 是空实现，
 * 普通 JVM 单测下 put 不会真正写入数据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureJsonTest {

    private fun sampleSegment() = CaptureSegment(
        segmentId = "seg_001",
        sessionId = "ses_001",
        speakerId = "spk_001",
        sessionDate = "2026-11-20",
        scene = CaptureScene.READ,
        promptId = "P0001",
        referenceText = "今天天气不错。",
        referenceSource = ReferenceSource.APPROVED_PROMPT,
        intendedText = null,
        durationMs = 1500,
        sampleRate = 16000,
        channels = 1,
        encoding = "pcm_s16",
        noiseEstimateDb = null,
        distanceRange = null,
        fatigueLevel = 2,
        deviceModel = "Pixel 7",
        environment = "quiet",
        qualityFlags = emptyList(),
        annotatorId = "self",
        guideVersion = "v1.0",
        consentFlags = listOf("research_use", "train_use"),
        audioFileName = "seg_001.pcm",
    )

    @Test
    fun segmentJsonContainsEverySchemaRequiredField() {
        val json = CaptureJson.segmentToJson(sampleSegment())
        val required = listOf(
            "segmentId", "sessionId", "speakerId", "sessionDate", "scene", "promptId",
            "referenceText", "referenceSource", "intendedText", "durationMs", "sampleRate",
            "channels", "encoding", "deviceModel", "environment", "qualityFlags",
            "annotatorId", "guideVersion", "consentFlags", "audioFileName", "audioAvailable",
            "speechProfileId", "localeTag", "dialectGroup",
        )
        for (field in required) {
            assertTrue("缺少字段: " + field, json.has(field))
        }
    }

    @Test
    fun segmentJsonUsesSchemaEnumValues() {
        val json = CaptureJson.segmentToJson(sampleSegment())
        assertEquals("read", json.getString("scene"))
        assertEquals("approved_prompt", json.getString("referenceSource"))
    }

    @Test
    fun dialectProfileRoundTripsAndLegacyJsonDefaultsToMandarin() {
        val shandong = sampleSegment().copy(
            speechProfileId = "shandong",
            localeTag = "zh-CN",
            dialectGroup = "jilu",
        )
        val json = CaptureJson.segmentToJson(shandong)
        assertEquals("shandong", json.getString("speechProfileId"))
        assertEquals("zh-CN", json.getString("localeTag"))
        assertEquals("jilu", json.getString("dialectGroup"))
        assertEquals(
            shandong.copy(audioFileName = "segments/ses_001/seg_001.pcm"),
            CaptureJson.segmentFromJson(json),
        )

        json.remove("speechProfileId")
        json.remove("localeTag")
        json.remove("dialectGroup")
        val legacy = CaptureJson.segmentFromJson(json)
        assertEquals("mandarin", legacy.speechProfileId)
        assertEquals("zh-CN", legacy.localeTag)
        assertEquals("unspecified", legacy.dialectGroup)
    }

    @Test
    fun unconfirmedIntentIsRepresentedExplicitly() {
        val segment = sampleSegment().copy(
            scene = CaptureScene.SPONTANEOUS,
            promptId = null,
            referenceText = "",
            referenceSource = ReferenceSource.UNCONFIRMED,
        )
        val json = CaptureJson.segmentToJson(segment)
        assertEquals("unconfirmed", json.getString("referenceSource"))
        assertEquals("", json.getString("referenceText"))
    }

    @Test
    fun consentJsonSeparatesEachGrant() {
        val consent = CaptureConsent(
            speakerId = "spk_001",
            consentVersion = "v1.0",
            signedAt = "2026-11-20T10:00:00+08:00",
            grants = mapOf(
                "research_use" to true,
                "train_use" to true,
                "cloud_upload" to false,
                "voice_profile" to false,
                "anonymous_report" to true,
            ),
            withdrawnAt = null,
            withdrawalScopeNote = "note",
            compensation = "pending",
        )
        val json = CaptureJson.consentToJson(consent)
        val grants = json.getJSONObject("grants")
        assertTrue(grants.getBoolean("research_use"))
        assertTrue(grants.getBoolean("train_use"))
        assertEquals(false, grants.getBoolean("cloud_upload"))
        assertEquals(false, grants.getBoolean("voice_profile"))
    }

    @Test
    fun manifestJsonAlwaysContainsLimitations() {
        val manifest = CaptureManifest(
            datasetVersion = "v0.1.0",
            createdAt = "2026-11-25",
            cohortTarget = "听障相关发音不清的普通话成人",
            speakers = listOf(ManifestSpeakerEntry("spk_001", "L2", 1, 10)),
            splits = mapOf("train" to listOf("spk_001")),
            totals = ManifestTotals(1, 1, 10, 0.5),
            annotationGuideVersion = "v1.0",
            licenseCode = "internal-research-only",
            commercialUse = false,
            redistribution = false,
            limitations = listOf("样本量极小"),
        )
        val json = CaptureJson.manifestToJson(manifest)
        assertTrue(json.getJSONArray("limitations").length() > 0)
        assertEquals(false, json.getJSONObject("license").getBoolean("commercialUse"))
        assertEquals("听障相关发音不清的普通话成人", json.getJSONObject("cohort").getString("target"))
    }

    @Test
    fun speakerJsonRecordsHearingAidState() {
        val speaker = CaptureSpeaker(
            speakerId = "spk_001",
            ageBand = "30-39",
            gender = "female",
            severityLevel = "L2",
            severitySource = CaptureSpeaker.SEVERITY_SOURCE_ADVISOR,
            hearingAidWorn = true,
            hearingAidType = "behind_the_ear",
            firstSessionDate = "2026-11-20",
            sessionCount = 3,
            speakingEnvironment = listOf("quiet"),
            speechProfileId = "shandong",
            localeTag = "zh-CN",
            dialectGroup = "jiaoliao",
        )
        val json = CaptureJson.speakerToJson(speaker)
        val hearingAid = json.getJSONObject("hearingAid")
        assertTrue(hearingAid.getBoolean("worn"))
        assertEquals("behind_the_ear", hearingAid.getString("type"))
        assertEquals("professional_advisor", json.getString("severitySource"))
        assertEquals("shandong", json.getString("speechProfileId"))
        assertEquals("zh-CN", json.getString("localeTag"))
        assertEquals("jiaoliao", json.getString("dialectGroup"))
        assertEquals(speaker, CaptureJson.speakerFromJson(json))
    }
}
