package com.voiceclear.app.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App 导出格式与 Python 校验工具的一致性测试。
 *
 * 这是防止格式漂移的核心保障：采集 App 产出的数据必须能被
 * [tools/validate_dataset.py] 直接接受，否则采集的数据无法进入评测流水线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class CaptureExportCompatibilityTest {

    private val projectRoot: File = File(System.getProperty("user.dir") ?: ".").parentFile
        ?: File(".").absoluteFile

    private val validatorScript: File = File(projectRoot, "tools/validate_dataset.py")

    private fun buildDataset(dir: File) {
        File(dir, "consent").mkdirs()
        File(dir, "speakers").mkdirs()
        File(dir, "transcripts").mkdirs()
        File(dir, "splits").mkdirs()

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
        File(dir, "consent/spk_001.json").writeText(
            CaptureJson.consentToJson(consent).toString(2), StandardCharsets.UTF_8)

        val speaker = CaptureSpeaker(
            speakerId = "spk_001",
            ageBand = "30-39",
            gender = "female",
            severityLevel = "L2",
            severitySource = CaptureSpeaker.SEVERITY_SOURCE_ADVISOR,
            hearingAidWorn = true,
            hearingAidType = null,
            firstSessionDate = "2026-11-20",
            sessionCount = 3,
            speakingEnvironment = listOf("quiet"),
        )
        File(dir, "speakers/spk_001.json").writeText(
            CaptureJson.speakerToJson(speaker).toString(2), StandardCharsets.UTF_8)

        val segment = CaptureSegment(
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
        File(dir, "transcripts/ses_001.jsonl").writeText(
            CaptureJson.segmentToJson(segment).toString() + "\n", StandardCharsets.UTF_8)
        File(dir, "segments/ses_001").mkdirs()
        // 1500 ms、16 kHz、单声道 PCM16 = 48000 字节，供校验器交叉核验时长。
        File(dir, "segments/ses_001/seg_001.pcm").writeBytes(ByteArray(48_000))

        File(dir, "splits/train.txt").writeText("spk_001\n", StandardCharsets.UTF_8)
        File(dir, "splits/dev.txt").writeText("", StandardCharsets.UTF_8)
        File(dir, "splits/test.txt").writeText("", StandardCharsets.UTF_8)

        val manifest = CaptureManifest(
            datasetVersion = "v0.1.0",
            createdAt = "2026-11-25",
            cohortTarget = "听障相关发音不清的普通话成人",
            speakers = listOf(ManifestSpeakerEntry("spk_001", "L2", 1, 1)),
            splits = mapOf("train" to listOf("spk_001")),
            totals = ManifestTotals(1, 1, 1, 0.01),
            annotationGuideVersion = "v1.0",
            licenseCode = "internal-research-only",
            commercialUse = false,
            redistribution = false,
            limitations = listOf("样本量极小"),
        )
        File(dir, "manifest.json").writeText(
            CaptureJson.manifestToJson(manifest).toString(2), StandardCharsets.UTF_8)
    }

    @Test
    fun exportedDatasetPassesPythonValidator() {
        if (!validatorScript.exists()) return
        val tempDir = createTempDataset()
        try {
            buildDataset(tempDir)
            val process = ProcessBuilder(
                "python3", validatorScript.absolutePath, tempDir.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals("校验器退出码异常，输出: " + output, 0, exitCode)
            assertTrue("校验器输出: " + output, output.contains("校验通过"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun productionStoreExportPassesPythonValidator() {
        if (!validatorScript.exists()) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CaptureStore(context)
        store.clearAll()
        val tempDir = createTempDataset()
        try {
            store.writeConsent(
                CaptureConsent(
                    speakerId = "spk_001",
                    consentVersion = "v1.0",
                    signedAt = "2026-11-20T10:00:00+08:00",
                    grants = ConsentFlags.ALL.associateWith { it == ConsentFlags.RESEARCH_USE },
                    withdrawnAt = null,
                    withdrawalScopeNote = "note",
                    compensation = "unspecified",
                ),
            )
            val segment = CaptureSegment(
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
                fatigueLevel = null,
                deviceModel = "test",
                environment = "unspecified",
                qualityFlags = emptyList(),
                annotatorId = "self",
                guideVersion = "v1.0",
                consentFlags = listOf(ConsentFlags.RESEARCH_USE),
                audioFileName = CaptureStore.audioRelativePath("ses_001", "seg_001"),
            )
            store.saveSegment(segment.sessionId, segment, ByteArray(48_000))

            // 默认导出没有专业严重度，必须明确失败；不得靠测试静默填 L2 掩盖缺口。
            store.exportAudio(tempDir)
            val unclassifiedProcess = ProcessBuilder(
                "python3", validatorScript.absolutePath, tempDir.absolutePath,
            ).redirectErrorStream(true).start()
            val unclassifiedOutput = unclassifiedProcess.inputStream.bufferedReader().use { it.readText() }
            val unclassifiedExit = unclassifiedProcess.waitFor()
            assertTrue("unspecified 默认导出不得成为正式研究集: " + unclassifiedOutput, unclassifiedExit != 0)
            assertTrue(unclassifiedOutput.contains("unspecified"))

            // 显式补录真实专业顾问记录后，同一生产导出才允许通过。
            store.writeSpeaker(
                CaptureSpeaker(
                    speakerId = "spk_001",
                    ageBand = "unspecified",
                    gender = "unspecified",
                    severityLevel = "L2",
                    severitySource = CaptureSpeaker.SEVERITY_SOURCE_ADVISOR,
                    hearingAidWorn = false,
                    hearingAidType = null,
                    firstSessionDate = "2026-11-20",
                    sessionCount = 1,
                    speakingEnvironment = listOf("unspecified"),
                ),
            )

            store.exportAudio(tempDir)
            val process = ProcessBuilder(
                "python3", validatorScript.absolutePath, tempDir.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            assertEquals("生产导出校验失败: " + output, 0, exitCode)
            assertTrue(output.contains("校验通过"))
            assertTrue(File(tempDir, "prompts/prompts.json").isFile)
            assertTrue(File(tempDir, "prompts/shandong.json").isFile)
        } finally {
            tempDir.deleteRecursively()
            store.clearAll()
        }
    }

    @Test
    fun unconfirmedSegmentIsExplicitlyMarked() {
        val segment = CaptureSegment(
            segmentId = "seg_002",
            sessionId = "ses_001",
            speakerId = "spk_001",
            sessionDate = "2026-11-20",
            scene = CaptureScene.SPONTANEOUS,
            promptId = null,
            referenceText = "",
            referenceSource = ReferenceSource.UNCONFIRMED,
            intendedText = null,
            durationMs = 1200,
            sampleRate = 16000,
            channels = 1,
            encoding = "pcm_s16",
            noiseEstimateDb = null,
            distanceRange = null,
            fatigueLevel = null,
            deviceModel = "Pixel 7",
            environment = "quiet",
            qualityFlags = emptyList(),
            annotatorId = "self",
            guideVersion = "v1.0",
            consentFlags = listOf("research_use"),
            audioFileName = "seg_002.pcm",
        )
        val json = CaptureJson.segmentToJson(segment)
        assertEquals("unconfirmed", json.getString("referenceSource"))
        assertTrue(json.getJSONArray("consentFlags").length() > 0)
    }

    private fun createTempDataset(): File {
        val dir = File.createTempFile("voiceclear-capture", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
