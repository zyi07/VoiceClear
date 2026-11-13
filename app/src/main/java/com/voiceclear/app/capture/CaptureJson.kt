package com.voiceclear.app.capture

import org.json.JSONArray
import org.json.JSONObject

/** 授权记录。 */
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
    /** 数据集自述局限，强制项：避免下游误用小样本结论。 */
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

/** 采集统计。 */
data class CaptureStatistics(
    val segments: Int,
    val totalMs: Long,
) {
    val totalHours: Double get() = totalMs / 3_600_000.0
}

/**
 * 采集数据的 JSON 序列化。
 *
 * 单独成文件，便于用纯 JVM 单元测试验证输出格式与校验工具兼容，
 * 不必启动 Android 运行时。
 *
 * 注意：Android 的 org.json.JSONObject.put 声明为抛 JSONException，
 * 因此这里统一用 runCatching 包裹，避免每个调用点都写 try/catch。
 */
object CaptureJson {

    /** 写入一个键值，忽略异常（键名与值均来自内部常量或受控模型）。 */
    private fun JSONObject.putSafe(key: String, value: Any?) {
        runCatching { put(key, value) }
    }

    /** 片段记录。字段名与 schema 严格一致。 */
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
        return json
    }

    /** 授权记录。 */
    fun consentToJson(consent: CaptureConsent): JSONObject {
        val grantsJson = JSONObject()
        for ((key, value) in consent.grants) {
            grantsJson.putSafe(key, value)
        }
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

    /** 说话人元数据。 */
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

    /** 数据集清单。 */
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
        for ((name, members) in manifest.splits) {
            splitsJson.putSafe(name, JSONArray(members))
        }

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
