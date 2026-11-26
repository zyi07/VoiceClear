package com.voiceclear.app.capture

import org.json.JSONArray
import org.json.JSONObject

/** 授权记录。signedAt 为空表示从未签署；withdrawnAt 非空时所有用途均失效。 */
data class CaptureConsent(
    val speakerId: String,
    val consentVersion: String,
    val signedAt: String,
    val grants: Map<String, Boolean>,
    val withdrawnAt: String?,
    val withdrawalScopeNote: String,
    val compensation: String,
)

/** 数据集清单。 */
data class CaptureManifest(
    val datasetVersion: String,
    val createdAt: String,
    val cohortTarget: String,
    val speakers: List<ManifestSpeakerEntry>,
    val splits: Map<String, List<String>>,
    val totals: ManifestTotals,
    val annotationGuideVersion: String,
    val licenseCode: String,
    val commercialUse: Boolean,
    val redistribution: Boolean,
    val limitations: List<String>,
)

data class ManifestSpeakerEntry(
    val speakerId: String,
    val severityLevel: String,
    val sessions: Int,
    val segments: Int,
)

data class ManifestTotals(
    val speakers: Int,
    val sessions: Int,
    val segments: Int,
    val hours: Double,
)

data class CaptureStatistics(
    val segments: Int,
    val totalMs: Long,
) {
    val totalHours: Double get() = totalMs / 3_600_000.0
}

/** 采集 JSON 序列化与读取。序列化失败会显式抛出，不能静默丢失授权或关联字段。 */
object CaptureJson {

    private fun JSONObject.putSafe(key: String, value: Any?) {
        put(key, value)
    }

    private fun JSONObject.nullableString(key: String): String? {
        return if (!has(key) || isNull(key)) null else getString(key)
    }

    private fun JSONArray.stringList(): List<String> {
        return buildList {
            for (index in 0 until length()) add(getString(index))
        }
    }

    fun segmentToJson(segment: CaptureSegment): JSONObject {
        val json = JSONObject()
        json.putSafe("segmentId", segment.segmentId)
        json.putSafe("sessionId", segment.sessionId)
        json.putSafe("speakerId", segment.speakerId)
        json.putSafe("sessionDate", segment.sessionDate)
        json.putSafe("scene", segment.scene.value)
        json.putSafe("promptId", segment.promptId ?: JSONObject.NULL)
        json.putSafe("referenceText", segment.referenceText)
        json.putSafe("referenceSource", segment.referenceSource.value)
        json.putSafe("intendedText", segment.intendedText ?: JSONObject.NULL)
        json.putSafe("durationMs", segment.durationMs)
        json.putSafe("sampleRate", segment.sampleRate)
        json.putSafe("channels", segment.channels)
        json.putSafe("encoding", segment.encoding)
        segment.noiseEstimateDb?.let { json.putSafe("noiseEstimateDb", it) }
        segment.distanceRange?.let { json.putSafe("distanceRange", it) }
        segment.fatigueLevel?.let { json.putSafe("fatigueLevel", it) }
        json.putSafe("deviceModel", segment.deviceModel)
        json.putSafe("environment", segment.environment)
        json.putSafe("qualityFlags", JSONArray(segment.qualityFlags))
        json.putSafe("annotatorId", segment.annotatorId)
        json.putSafe("guideVersion", segment.guideVersion)
        json.putSafe("consentFlags", JSONArray(segment.consentFlags))
        json.putSafe("audioFileName", segment.audioFileName)
        json.putSafe("audioAvailable", segment.audioAvailable)
        json.putSafe("deletionReason", segment.deletionReason ?: JSONObject.NULL)
        return json
    }

    fun segmentFromJson(json: JSONObject): CaptureSegment {
        val scene = CaptureScene.entries.firstOrNull { it.value == json.getString("scene") }
            ?: throw IllegalArgumentException("未知 scene")
        val source = ReferenceSource.entries.firstOrNull {
            it.value == json.getString("referenceSource")
        } ?: throw IllegalArgumentException("未知 referenceSource")
        val segmentId = json.getString("segmentId")
        val sessionId = json.getString("sessionId")
        val storedAudioPath = json.optString("audioFileName", "")
        val audioPath = when {
            storedAudioPath.isBlank() -> "segments/$sessionId/$segmentId.pcm"
            !storedAudioPath.contains('/') -> "segments/$sessionId/$storedAudioPath"
            else -> storedAudioPath
        }
        return CaptureSegment(
            segmentId = segmentId,
            sessionId = sessionId,
            speakerId = json.getString("speakerId"),
            sessionDate = json.getString("sessionDate"),
            scene = scene,
            promptId = json.nullableString("promptId"),
            referenceText = json.optString("referenceText", ""),
            referenceSource = source,
            intendedText = json.nullableString("intendedText"),
            durationMs = json.optLong("durationMs", 0L),
            sampleRate = json.optInt("sampleRate", 0),
            channels = json.optInt("channels", 1),
            encoding = json.optString("encoding", "pcm_s16"),
            noiseEstimateDb = if (json.has("noiseEstimateDb") && !json.isNull("noiseEstimateDb")) {
                json.getDouble("noiseEstimateDb")
            } else {
                null
            },
            distanceRange = json.nullableString("distanceRange"),
            fatigueLevel = if (json.has("fatigueLevel") && !json.isNull("fatigueLevel")) {
                json.getInt("fatigueLevel")
            } else {
                null
            },
            deviceModel = json.optString("deviceModel", "unknown"),
            environment = json.optString("environment", "unspecified"),
            qualityFlags = json.optJSONArray("qualityFlags")?.stringList().orEmpty(),
            annotatorId = json.optString("annotatorId", CaptureStore.ANNOTATOR_ID),
            guideVersion = json.optString("guideVersion", CaptureStore.GUIDELINE_VERSION),
            consentFlags = json.optJSONArray("consentFlags")?.stringList().orEmpty(),
            audioFileName = audioPath,
            audioAvailable = json.optBoolean("audioAvailable", true),
            deletionReason = json.nullableString("deletionReason"),
        )
    }

    fun consentToJson(consent: CaptureConsent): JSONObject {
        val grantsJson = JSONObject()
        for ((key, value) in consent.grants) grantsJson.putSafe(key, value)
        val json = JSONObject()
        json.putSafe("speakerId", consent.speakerId)
        json.putSafe("consentVersion", consent.consentVersion)
        json.putSafe("signedAt", consent.signedAt)
        json.putSafe("grants", grantsJson)
        json.putSafe("withdrawnAt", consent.withdrawnAt ?: JSONObject.NULL)
        json.putSafe("withdrawalScopeNote", consent.withdrawalScopeNote)
        json.putSafe("compensation", consent.compensation)
        return json
    }

    fun consentFromJson(json: JSONObject): CaptureConsent {
        val grantsJson = json.optJSONObject("grants") ?: JSONObject()
        val grants = mutableMapOf<String, Boolean>()
        val keys = grantsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            grants[key] = grantsJson.optBoolean(key, false)
        }
        return CaptureConsent(
            speakerId = json.getString("speakerId"),
            consentVersion = json.optString("consentVersion", "v1.0"),
            signedAt = json.optString("signedAt", ""),
            grants = grants,
            withdrawnAt = json.nullableString("withdrawnAt"),
            withdrawalScopeNote = json.optString("withdrawalScopeNote", ""),
            compensation = json.optString("compensation", "unspecified"),
        )
    }

    fun speakerToJson(speaker: CaptureSpeaker): JSONObject {
        val hearingAid = JSONObject()
        hearingAid.putSafe("worn", speaker.hearingAidWorn)
        hearingAid.putSafe("type", speaker.hearingAidType ?: JSONObject.NULL)
        val json = JSONObject()
        json.putSafe("speakerId", speaker.speakerId)
        json.putSafe("ageBand", speaker.ageBand)
        json.putSafe("gender", speaker.gender)
        json.putSafe("severityLevel", speaker.severityLevel)
        json.putSafe("severitySource", speaker.severitySource)
        json.putSafe("hearingAid", hearingAid)
        json.putSafe("firstSessionDate", speaker.firstSessionDate)
        json.putSafe("sessionCount", speaker.sessionCount)
        json.putSafe("speakingEnvironment", JSONArray(speaker.speakingEnvironment))
        return json
    }

    fun speakerFromJson(json: JSONObject): CaptureSpeaker {
        val hearingAid = json.optJSONObject("hearingAid")
        return CaptureSpeaker(
            speakerId = json.getString("speakerId"),
            ageBand = json.optString("ageBand", "unspecified"),
            gender = json.optString("gender", "unspecified"),
            severityLevel = json.optString(
                "severityLevel",
                CaptureSpeaker.SEVERITY_LEVEL_UNSPECIFIED,
            ),
            severitySource = json.optString(
                "severitySource",
                CaptureSpeaker.SEVERITY_SOURCE_UNSPECIFIED,
            ),
            hearingAidWorn = hearingAid?.optBoolean("worn", false) ?: false,
            hearingAidType = hearingAid?.nullableString("type"),
            firstSessionDate = json.optString("firstSessionDate", ""),
            sessionCount = json.optInt("sessionCount", 0),
            speakingEnvironment = json.optJSONArray("speakingEnvironment")?.stringList().orEmpty(),
        )
    }

    fun manifestToJson(manifest: CaptureManifest): JSONObject {
        val speakerArray = JSONArray()
        for (entry in manifest.speakers) {
            val item = JSONObject()
            item.putSafe("speakerId", entry.speakerId)
            item.putSafe("severityLevel", entry.severityLevel)
            item.putSafe("sessions", entry.sessions)
            item.putSafe("segments", entry.segments)
            speakerArray.put(item)
        }
        val splitsJson = JSONObject()
        for ((name, members) in manifest.splits) splitsJson.putSafe(name, JSONArray(members))
        val licenseJson = JSONObject()
        licenseJson.putSafe("code", manifest.licenseCode)
        licenseJson.putSafe("commercialUse", manifest.commercialUse)
        licenseJson.putSafe("redistribution", manifest.redistribution)
        val totalsJson = JSONObject()
        totalsJson.putSafe("speakers", manifest.totals.speakers)
        totalsJson.putSafe("sessions", manifest.totals.sessions)
        totalsJson.putSafe("segments", manifest.totals.segments)
        totalsJson.putSafe("hours", manifest.totals.hours)
        val cohortJson = JSONObject()
        cohortJson.putSafe("target", manifest.cohortTarget)
        cohortJson.putSafe("minSdk", 26)
        val json = JSONObject()
        json.putSafe("datasetVersion", manifest.datasetVersion)
        json.putSafe("createdAt", manifest.createdAt)
        json.putSafe("cohort", cohortJson)
        json.putSafe("speakers", speakerArray)
        json.putSafe("splits", splitsJson)
        json.putSafe("totals", totalsJson)
        json.putSafe("annotationGuideVersion", manifest.annotationGuideVersion)
        json.putSafe("license", licenseJson)
        json.putSafe("limitations", JSONArray(manifest.limitations))
        return json
    }
}
