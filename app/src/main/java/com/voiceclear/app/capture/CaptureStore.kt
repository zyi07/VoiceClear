package com.voiceclear.app.capture

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * 采集数据存储与导出。
 *
 * 目录结构与 [docs/dataset-schema.md] 第 1 节一致，导出结果可直接交给
 * [tools/validate_dataset.py] 校验，无需二次转换。
 *
 * 隐私要点：
 * - 全部数据只写入应用私有目录（context.filesDir），不进入公共存储；
 * - 说话人 ID 为本地随机生成，与任何个人标识无关；
 * - 不写原始音频以外的任何可识别文本；
 * - 导出不包含音频（体积大且含生物特征），音频单独按需拷贝。
 */
class CaptureStore(private val context: Context) {

    companion object {
        const val SEGMENTS_DIR = "segments"
        const val TRANSCRIPTS_DIR = "transcripts"
        const val CONSENT_DIR = "consent"
        const val SPEAKERS_DIR = "speakers"
        const val SPLITS_DIR = "splits"
        const val MANIFEST_FILE = "manifest.json"
        const val EXPORT_DIR = "export"
        const val GUIDELINE_VERSION = "v1.0"
        const val ANNOTATOR_ID = "self"
    }

    /** 数据集根目录。 */
    fun root(): File = File(context.filesDir, "dataset").apply { mkdirs() }

    fun segmentsDir(sessionId: String): File =
        File(root(), "$SEGMENTS_DIR/$sessionId").apply { mkdirs() }

    fun transcriptsDir(): File = File(root(), TRANSCRIPTS_DIR).apply { mkdirs() }

    fun consentDir(): File = File(root(), CONSENT_DIR).apply { mkdirs() }

    fun speakersDir(): File = File(root(), SPEAKERS_DIR).apply { mkdirs() }

    fun splitsDir(): File = File(root(), SPLITS_DIR).apply { mkdirs() }

    /** 保存一段音频。原始格式不重采样，另存 16 kHz 单声道副本用于统一评测。 */
    fun saveAudio(sessionId: String, segmentId: String, samples: ByteArray): String {
        val dir = segmentsDir(sessionId)
        val file = File(dir, "$segmentId.pcm")
        FileOutputStream(file).use { it.write(samples) }
        return file.absolutePath
    }

    /** 追加一条片段转写记录。 */
    fun appendTranscript(sessionId: String, segment: CaptureSegment) {
        val file = File(transcriptsDir(), "$sessionId.jsonl")
        val line = CaptureJson.segmentToJson(segment).toString()
        file.appendText(line + "\n", StandardCharsets.UTF_8)
    }

    /** 写入或更新授权记录。 */
    fun writeConsent(consent: CaptureConsent) {
        val file = File(consentDir(), "${consent.speakerId}.json")
        file.writeText(CaptureJson.consentToJson(consent).toString(2), StandardCharsets.UTF_8)
    }

    /** 写入或更新说话人元数据。 */
    fun writeSpeaker(speaker: CaptureSpeaker) {
        val file = File(speakersDir(), "${speaker.speakerId}.json")
        file.writeText(CaptureJson.speakerToJson(speaker).toString(2), StandardCharsets.UTF_8)
    }

    /**
     * 写入划分文件。
     *
     * 划分按**说话人**而非片段，同一说话人的全部会话必须落在同一划分，
     * 否则会构成训练/测试泄漏（schema 第 4 节规则 8）。
     */
    fun writeSplit(splitName: String, speakerIds: List<String>) {
        val file = File(splitsDir(), "$splitName.txt")
        val content = speakerIds.joinToString("\n") { it }
        file.writeText(if (content.isEmpty()) "" else content + "\n", StandardCharsets.UTF_8)
    }

    /** 写入数据集清单。 */
    fun writeManifest(manifest: CaptureManifest) {
        val file = File(root(), MANIFEST_FILE)
        file.writeText(CaptureJson.manifestToJson(manifest).toString(2), StandardCharsets.UTF_8)
    }

    /**
     * 导出可交付副本到压缩包目录。
     *
     * 导出内容为**元数据**（manifest / transcripts / speakers / consent / splits），
     * 不含音频文件：音频体积大且属生物特征，需在明确授权后单独拷贝。
     */
    fun exportMetadata(): File {
        val exportDir = File(root(), EXPORT_DIR).apply { mkdirs() }
        val manifestSource = File(root(), MANIFEST_FILE)
        if (manifestSource.exists()) {
            manifestSource.copyTo(File(exportDir, MANIFEST_FILE), overwrite = true)
        }
        listOf(TRANSCRIPTS_DIR, SPEAKERS_DIR, CONSENT_DIR, SPLITS_DIR).forEach { name ->
            val source = File(root(), name)
            if (!source.exists()) return@forEach
            val target = File(exportDir, name)
            target.mkdirs()
            source.listFiles()?.forEach { file ->
                file.copyTo(File(target, file.name), overwrite = true)
            }
        }
        return exportDir
    }

    /** 导出全部音频（需单独授权后调用）。 */
    fun exportAudio(targetDir: File): File {
        targetDir.mkdirs()
        val segmentsRoot = File(root(), SEGMENTS_DIR)
        if (!segmentsRoot.exists()) return targetDir
        segmentsRoot.listFiles()?.forEach { sessionDir ->
            val target = File(targetDir, sessionDir.name)
            target.mkdirs()
            sessionDir.listFiles()?.forEach { file ->
                file.copyTo(File(target, file.name), overwrite = true)
            }
        }
        return targetDir
    }

    /** 统计当前已采集的片段数与时长。 */
    fun statistics(): CaptureStatistics {
        var segments = 0
        var totalMs = 0L
        transcriptsDir().listFiles()?.forEach { file ->
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                segments++
                try {
                    val obj = JSONObject(line)
                    totalMs += obj.optLong("durationMs", 0L)
                } catch (error: Exception) {
                    // 单条解析失败不影响整体统计
                }
            }
        }
        return CaptureStatistics(segments = segments, totalMs = totalMs)
    }

    /** 清空所有采集数据（用户撤回授权时使用）。 */
    fun clearAll() {
        root().deleteRecursively()
        root().mkdirs()
    }
}
