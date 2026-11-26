package com.voiceclear.app.capture

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 采集数据存储。所有读取、写入、导出和保留清理由同一数据集锁串行化。 */
class CaptureStore(private val context: Context) {

    companion object {
        const val SEGMENTS_DIR = "segments"
        const val RAW_DIR = "raw"
        const val TRANSCRIPTS_DIR = "transcripts"
        const val CONSENT_DIR = "consent"
        const val SPEAKERS_DIR = "speakers"
        const val SPLITS_DIR = "splits"
        const val PROMPTS_DIR = "prompts"
        const val MANIFEST_FILE = "manifest.json"
        const val EXPORT_DIR = "export"
        const val GUIDELINE_VERSION = "v1.0"
        const val ANNOTATOR_ID = "self"
        const val RETENTION_DELETION_REASON = "retention_expired"

        fun audioRelativePath(sessionId: String, segmentId: String): String =
            "$SEGMENTS_DIR/$sessionId/$RAW_DIR/$segmentId.pcm"
    }

    private val storeLock = CaptureStoreLocks.forRoot(File(context.filesDir, "dataset"))

    fun root(): File = File(context.filesDir, "dataset").apply { mkdirs() }

    fun segmentsDir(sessionId: String): File =
        File(root(), "$SEGMENTS_DIR/$sessionId").apply { mkdirs() }

    fun transcriptsDir(): File = File(root(), TRANSCRIPTS_DIR).apply { mkdirs() }
    fun consentDir(): File = File(root(), CONSENT_DIR).apply { mkdirs() }
    fun speakersDir(): File = File(root(), SPEAKERS_DIR).apply { mkdirs() }
    fun splitsDir(): File = File(root(), SPLITS_DIR).apply { mkdirs() }

    /** 供 DataRetentionPolicy 使用；ReentrantLock 允许同一线程内组合存储操作。 */
    internal fun <T> locked(block: () -> T): T {
        storeLock.lock()
        return try {
            block()
        } finally {
            storeLock.unlock()
        }
    }

    /** 音频先写临时文件，再与转写在共享锁内关联；失败时删除音频，避免孤儿 PCM。 */
    fun saveSegment(sessionId: String, segment: CaptureSegment, samples: ByteArray): String =
        locked {
            require(sessionId == segment.sessionId) { "片段与目录的 sessionId 不一致" }
            val target = File(root(), segment.audioFileName)
            require(target.parentFile?.parentFile?.let { it == segmentsDir(sessionId) } == true) {
                "audioFileName 必须指向当前会话 raw 目录"
            }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, target.name + ".part")
            try {
                writeBytesAtomically(temporary, samples, renameTarget = false)
                if (target.exists() && !target.delete()) error("无法替换旧音频")
                if (!temporary.renameTo(target)) error("无法发布音频文件")
                upsertTranscriptLocked(sessionId, segment)
                invalidateGeneratedMetadataLocked()
                target.absolutePath
            } catch (error: Throwable) {
                temporary.delete()
                target.delete()
                throw error
            }
        }

    /** 兼容旧调用点：只保存音频；新采集必须使用 [saveSegment] 同步保存元数据。 */
    fun saveAudio(sessionId: String, segmentId: String, samples: ByteArray): String = locked {
        val file = File(root(), audioRelativePath(sessionId, segmentId))
        file.parentFile?.mkdirs()
        writeBytesAtomically(file, samples)
        file.absolutePath
    }

    /** 名称保留用于兼容；实现已按 segmentId 原地更新，不再重复追加。 */
    fun appendTranscript(sessionId: String, segment: CaptureSegment) = upsertTranscript(sessionId, segment)

    fun upsertTranscript(sessionId: String, segment: CaptureSegment): Unit = locked {
        require(sessionId == segment.sessionId) { "片段与转写文件的 sessionId 不一致" }
        upsertTranscriptLocked(sessionId, segment)
        invalidateGeneratedMetadataLocked()
    }

    private fun upsertTranscriptLocked(sessionId: String, segment: CaptureSegment) {
        val file = File(transcriptsDir(), "$sessionId.jsonl")
        val replacement = CaptureJson.segmentToJson(segment).toString()
        val output = mutableListOf<String>()
        var replaced = false
        if (file.exists()) {
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val sameId = try {
                    JSONObject(line).optString("segmentId") == segment.segmentId
                } catch (error: Exception) {
                    false
                }
                if (sameId) {
                    if (!replaced) output.add(replacement)
                    replaced = true
                } else {
                    output.add(line)
                }
            }
        }
        if (!replaced) output.add(replacement)
        writeTextAtomically(file, output.joinToString("\n", postfix = "\n"))
    }

    fun writeConsent(consent: CaptureConsent): Unit = locked {
        val file = File(consentDir(), "${consent.speakerId}.json")
        writeTextAtomically(file, CaptureJson.consentToJson(consent).toString(2))
    }

    fun readConsent(speakerId: String): CaptureConsent? = locked {
        val file = File(consentDir(), "$speakerId.json")
        if (!file.isFile) return@locked null
        runCatching { CaptureJson.consentFromJson(JSONObject(file.readText())) }.getOrNull()
    }

    /** 首次使用只创建全拒绝记录；signedAt 为空，不伪造签署事实。 */
    fun ensureConsent(speakerId: String): CaptureConsent = locked {
        readConsent(speakerId) ?: CaptureConsent(
            speakerId = speakerId,
            consentVersion = "v1.0",
            signedAt = "",
            grants = ConsentFlags.ALL.associateWith { false },
            withdrawnAt = null,
            withdrawalScopeNote = "撤回后不再导出或用于后续研究；已进入全局模型的部分无法保证移除",
            compensation = "unspecified",
        ).also { writeConsent(it) }
    }

    fun hasResearchConsent(speakerId: String): Boolean = locked {
        val consent = readConsent(speakerId) ?: return@locked false
        consent.withdrawnAt == null && consent.grants[ConsentFlags.RESEARCH_USE] == true
    }

    fun activeConsentFlags(speakerId: String): List<String> = locked {
        val consent = readConsent(speakerId) ?: return@locked emptyList()
        if (consent.withdrawnAt != null) return@locked emptyList()
        ConsentFlags.ALL.filter { consent.grants[it] == true }
    }

    /** 撤回立即生效，并删除研究音频/转写；保留撤回凭据供审计。 */
    fun withdrawResearchData(speakerId: String, withdrawnAt: String): Unit = locked {
        require(withdrawnAt.isNotBlank()) { "撤回时间不能为空" }
        val consent = ensureConsent(speakerId).copy(withdrawnAt = withdrawnAt)
        writeConsent(consent)
        listOf(SEGMENTS_DIR, TRANSCRIPTS_DIR, SPEAKERS_DIR, SPLITS_DIR, EXPORT_DIR).forEach {
            File(root(), it).deleteRecursively()
        }
        File(root(), MANIFEST_FILE).delete()
    }

    fun writeSpeaker(speaker: CaptureSpeaker): Unit = locked {
        val file = File(speakersDir(), "${speaker.speakerId}.json")
        writeTextAtomically(file, CaptureJson.speakerToJson(speaker).toString(2))
    }

    fun readSpeaker(speakerId: String): CaptureSpeaker? = locked {
        val file = File(speakersDir(), "$speakerId.json")
        if (!file.isFile) return@locked null
        runCatching { CaptureJson.speakerFromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun writeSplit(splitName: String, speakerIds: List<String>): Unit = locked {
        val file = File(splitsDir(), "$splitName.txt")
        val content = speakerIds.joinToString("\n")
        writeTextAtomically(file, if (content.isEmpty()) "" else content + "\n")
    }

    fun writeManifest(manifest: CaptureManifest): Unit = locked {
        writeTextAtomically(File(root(), MANIFEST_FILE), CaptureJson.manifestToJson(manifest).toString(2))
    }

    fun readAllSegments(): List<CaptureSegment> = locked {
        transcriptsDir().listFiles()?.sortedBy { it.name }.orEmpty().flatMap { file ->
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching { CaptureJson.segmentFromJson(JSONObject(line)) }.getOrNull()
            }
        }
    }

    /** 有效数据必须同时满足：授权未撤回、research_use 明确同意、音频未删除且文件存在。 */
    private fun eligibleSegmentsLocked(): List<CaptureSegment> {
        val consentCache = mutableMapOf<String, CaptureConsent?>()
        return readAllSegments().filter { segment ->
            val consent = consentCache.getOrPut(segment.speakerId) { readConsent(segment.speakerId) }
            segment.audioAvailable &&
                segment.durationMs > 0 &&
                "empty_capture" !in segment.qualityFlags &&
                segment.audioFileName.isNotBlank() &&
                audioFileLocked(segment)?.let { it.isFile && it.length() > 0L } == true &&
                ConsentFlags.RESEARCH_USE in segment.consentFlags &&
                consent != null &&
                consent.withdrawnAt == null &&
                consent.grants[ConsentFlags.RESEARCH_USE] == true
        }.sortedWith(compareBy({ it.sessionId }, { it.segmentId }))
    }

    private fun audioFileLocked(segment: CaptureSegment): File? {
        if (segment.audioFileName.isBlank()) return null
        return runCatching {
            val datasetRoot = root().canonicalFile
            val candidate = File(datasetRoot, segment.audioFileName).canonicalFile
            candidate.takeIf {
                it.path.startsWith(datasetRoot.path + File.separator) && it != datasetRoot
            }
        }.getOrNull()
    }

    /** 从真实转写、会话 ID、环境和已有专业顾问记录重建元数据。 */
    fun refreshMetadata(createdAt: String = currentTimestamp()): Unit = locked {
        val segments = eligibleSegmentsLocked()
        val grouped = segments.groupBy { it.speakerId }
        val speakerIds = grouped.keys.sorted()
        speakerIds.forEach { speakerId ->
            val owned = grouped.getValue(speakerId)
            val existing = readSpeaker(speakerId)
            val sessionDates = owned.map { it.sessionDate }.filter { it.isNotBlank() }.distinct().sorted()
            writeSpeaker(
                CaptureSpeaker(
                    speakerId = speakerId,
                    ageBand = existing?.ageBand ?: "unspecified",
                    gender = existing?.gender ?: "unspecified",
                    severityLevel = existing?.severityLevel ?: CaptureSpeaker.SEVERITY_LEVEL_UNSPECIFIED,
                    severitySource = existing?.severitySource ?: CaptureSpeaker.SEVERITY_SOURCE_UNSPECIFIED,
                    hearingAidWorn = existing?.hearingAidWorn ?: false,
                    hearingAidType = existing?.hearingAidType,
                    firstSessionDate = sessionDates.firstOrNull() ?: "unspecified",
                    sessionCount = owned.map { it.sessionId }.distinct().size,
                    speakingEnvironment = owned.map { it.environment }.distinct().sorted(),
                ),
            )
        }
        val split = mapOf("train" to speakerIds, "dev" to emptyList(), "test" to emptyList())
        split.forEach { (name, members) -> writeSplit(name, members) }
        writeManifest(
            CaptureManifest(
                datasetVersion = "v0.1.0",
                createdAt = createdAt,
                cohortTarget = "听障相关发音不清的普通话成人",
                speakers = speakerIds.map { speakerId ->
                    val owned = grouped.getValue(speakerId)
                    ManifestSpeakerEntry(
                        speakerId = speakerId,
                        severityLevel = readSpeaker(speakerId)?.severityLevel
                            ?: CaptureSpeaker.SEVERITY_LEVEL_UNSPECIFIED,
                        sessions = owned.map { it.sessionId }.distinct().size,
                        segments = owned.size,
                    )
                },
                splits = split,
                totals = ManifestTotals(
                    speakers = speakerIds.size,
                    sessions = segments.map { it.sessionId }.distinct().size,
                    segments = segments.size,
                    hours = segments.sumOf { it.durationMs } / 3_600_000.0,
                ),
                annotationGuideVersion = GUIDELINE_VERSION,
                licenseCode = "internal-research-only",
                commercialUse = false,
                redistribution = false,
                limitations = listOf(
                    "仅包含明确授权且音频仍存在的片段",
                    "未经专业顾问录入的严重度为 unspecified，不得用于严重度分层",
                    "未经人工确认的环境为 unspecified，不得据此推断录音环境",
                ),
            ),
        )
    }

    /** 导出纯元数据包；目录每次重建，避免旧片段残留。 */
    fun exportMetadata(): File = buildExport(includeAudio = false)

    private fun buildExport(includeAudio: Boolean): File = locked {
        refreshMetadata()
        val staging = File(root(), EXPORT_DIR + ".staging")
        val exportDir = File(root(), EXPORT_DIR)
        staging.deleteRecursively()
        if (!staging.mkdirs()) error("无法创建导出临时目录")

        val segments = eligibleSegmentsLocked()
        File(staging, MANIFEST_FILE).writeText(File(root(), MANIFEST_FILE).readText())
        val splitSource = File(root(), SPLITS_DIR)
        if (splitSource.exists()) splitSource.copyRecursively(File(staging, SPLITS_DIR), overwrite = true)
        val speakerTarget = File(staging, SPEAKERS_DIR).apply { mkdirs() }
        segments.map { it.speakerId }.distinct().forEach { speakerId ->
            val source = File(speakersDir(), "$speakerId.json")
            if (source.isFile) source.copyTo(File(speakerTarget, source.name), overwrite = true)
        }
        // 撤回凭据可随元数据保留，但其音频、转写和 speaker 研究记录不会进入导出。
        val consentSource = File(root(), CONSENT_DIR)
        if (consentSource.exists()) {
            consentSource.copyRecursively(File(staging, CONSENT_DIR), overwrite = true)
        }

        val transcriptTarget = File(staging, TRANSCRIPTS_DIR).apply { mkdirs() }
        segments.groupBy { it.sessionId }.forEach { (sessionId, owned) ->
            val content = owned.joinToString("\n", postfix = "\n") {
                CaptureJson.segmentToJson(it).toString()
            }
            File(transcriptTarget, "$sessionId.jsonl").writeText(content)
        }

        val promptTarget = File(staging, "$PROMPTS_DIR/prompts.json")
        promptTarget.parentFile?.mkdirs()
        context.assets.open(PromptLibrary.ASSET_PATH).use { input ->
            FileOutputStream(promptTarget).use { output -> input.copyTo(output) }
        }

        if (includeAudio) {
            segments.forEach { segment ->
                val source = audioFileLocked(segment) ?: error("音频路径无效: ${segment.segmentId}")
                if (!source.isFile) error("音频不存在: ${segment.segmentId}")
                val target = File(staging, segment.audioFileName)
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }

        exportDir.deleteRecursively()
        if (!staging.renameTo(exportDir)) error("无法发布导出目录")
        exportDir
    }

    /** 完整研究包：每段 PCM 是独立 ZIP entry，并附 transcripts/prompts/manifest 等元数据。 */
    fun writeDatasetZip(output: OutputStream): CaptureExportSummary = locked {
        val exportDir = buildExport(includeAudio = true)
        val segments = eligibleSegmentsLocked()
        val bytes = segments.sumOf { audioFileLocked(it)?.length() ?: 0L }
        var files = 0
        ZipOutputStream(output).use { zip ->
            exportDir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(exportDir).path }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(exportDir).invariantSeparatorsPath))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    files++
                }
        }
        CaptureExportSummary(segments = segments.size, audioBytes = bytes, files = files)
    }

    /** 兼容旧 API：现在导出的是带元数据的目录，而不是仅复制无索引 PCM。 */
    fun exportAudio(targetDir: File): File = locked {
        val source = buildExport(includeAudio = true)
        targetDir.deleteRecursively()
        if (!source.copyRecursively(targetDir, overwrite = true)) error("导出目录复制失败")
        targetDir
    }

    fun statistics(): CaptureStatistics = locked {
        val segments = eligibleSegmentsLocked()
        CaptureStatistics(segments = segments.size, totalMs = segments.sumOf { it.durationMs })
    }

    /** 音频删除后保留转写审计记录，但显式标记无效并废弃旧清单/导出。 */
    internal fun markAudioUnavailable(
        sessionId: String,
        segmentId: String,
        reason: String,
    ): Unit = locked {
        val transcript = File(transcriptsDir(), "$sessionId.jsonl")
        if (!transcript.isFile) return@locked
        transcript.readLines().forEach { line ->
            val segment = runCatching {
                CaptureJson.segmentFromJson(JSONObject(line))
            }.getOrNull() ?: return@forEach
            if (segment.segmentId == segmentId) {
                upsertTranscriptLocked(
                    sessionId,
                    segment.copy(audioAvailable = false, deletionReason = reason),
                )
            }
        }
        invalidateGeneratedMetadataLocked()
    }

    private fun invalidateGeneratedMetadataLocked() {
        File(root(), MANIFEST_FILE).delete()
        File(root(), EXPORT_DIR).deleteRecursively()
    }

    fun clearAll(): Unit = locked {
        root().deleteRecursively()
        root().mkdirs()
    }

    private fun writeTextAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(content)
        if (target.exists() && !target.delete()) error("无法替换 ${target.name}")
        if (!temporary.renameTo(target)) error("无法写入 ${target.name}")
    }

    private fun writeBytesAtomically(
        target: File,
        content: ByteArray,
        renameTarget: Boolean = true,
    ) {
        target.parentFile?.mkdirs()
        val temporary = if (renameTarget) {
            File(target.parentFile, target.name + ".tmp")
        } else {
            target
        }
        FileOutputStream(temporary).use { stream ->
            stream.write(content)
            stream.fd.sync()
        }
        if (renameTarget) {
            if (target.exists() && !target.delete()) error("无法替换旧音频")
            if (!temporary.renameTo(target)) error("无法发布音频文件")
        }
    }

    private fun currentTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}

data class CaptureExportSummary(
    val segments: Int,
    val audioBytes: Long,
    val files: Int,
)

private object CaptureStoreLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun forRoot(root: File): ReentrantLock =
        locks.computeIfAbsent(root.absolutePath) { ReentrantLock(true) }
}
