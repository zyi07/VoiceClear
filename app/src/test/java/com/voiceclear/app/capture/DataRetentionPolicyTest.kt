package com.voiceclear.app.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.voiceclear.app.VoiceClearApplication
import com.voiceclear.app.session.ErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * 数据保留策略测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CaptureRetentionTestApplication::class)
class DataRetentionPolicyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `disabled policy does nothing`() {
        val policy = DataRetentionPolicy(context, DataRetentionPolicy.DISABLED)
        val result = policy.enforce()
        assertTrue(result.disabled)
        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun `expired audio is deleted`() {
        val store = CaptureStore(context)
        val sessionDir = store.segmentsDir("ses_old")
        val oldFile = File(sessionDir, "old.pcm")
        oldFile.writeBytes(ByteArray(1024))
        // 把修改时间设到 60 天前
        val sixtyDaysAgo = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        assertTrue(oldFile.setLastModified(sixtyDaysAgo))

        val policy = DataRetentionPolicy(context, retentionDays = 30)
        val result = policy.enforce()

        assertFalse(result.disabled)
        assertEquals(1, result.deletedFiles)
        assertFalse("过期录音应被删除", oldFile.exists())
    }

    @Test
    fun `recent audio is kept`() {
        val store = CaptureStore(context)
        val sessionDir = store.segmentsDir("ses_new")
        val recentFile = File(sessionDir, "recent.pcm")
        recentFile.writeBytes(ByteArray(1024))

        val policy = DataRetentionPolicy(context, retentionDays = 30)
        val result = policy.enforce()

        assertEquals(0, result.deletedFiles)
        assertTrue("未过期录音应保留", recentFile.exists())
    }

    @Test
    fun `metadata is never auto deleted`() {
        // 关键约束：元数据（转写/同意书）是研究记录与合规凭据，
        // 只能由用户显式清空，程序不得按时间偷偷删掉。
        val store = CaptureStore(context)
        val transcriptDir = store.transcriptsDir()
        val transcript = File(transcriptDir, "ses_1.jsonl")
        transcript.writeText("{\"segmentId\":\"seg_1\"}")
        val sixtyDaysAgo = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000
        transcript.setLastModified(sixtyDaysAgo)

        val consentDir = store.consentDir()
        val consent = File(consentDir, "spk_1.json")
        consent.writeText("{}")
        consent.setLastModified(sixtyDaysAgo)

        DataRetentionPolicy(context, retentionDays = 30).enforce()

        assertTrue("转写不得被自动删除", transcript.exists())
        assertTrue("同意书不得被自动删除", consent.exists())
    }

    @Test
    fun `cleanup result describes itself`() {
        val policy = DataRetentionPolicy(context, DataRetentionPolicy.DISABLED)
        assertEquals("自动清理未启用", policy.enforce().describe())

        val empty = DataRetentionPolicy.CleanupResult(
            disabled = false, deletedFiles = 0, deletedBytes = 0L,
        )
        assertEquals("没有需要清理的数据", empty.describe())

        val some = DataRetentionPolicy.CleanupResult(
            disabled = false, deletedFiles = 3, deletedBytes = 2048L,
        )
        assertTrue(some.describe().contains("3"))
    }

    @Test
    fun `retention default is thirty days`() {
        // 知情同意书里写明的保存期限必须与实现一致。
        assertEquals(30, DataRetentionPolicy.DEFAULT_RETENTION_DAYS)
    }

    @Test
    fun `capture errors map to unified codes`() {
        // 保证引擎常量与统一定义始终一致。
        assertEquals(
            ErrorCodes.CAPTURE_PERMISSION_DENIED,
            com.voiceclear.app.engine.AudioCapture.ERR_PERMISSION_DENIED,
        )
        assertEquals(
            ErrorCodes.CAPTURE_NOT_RECORDING,
            com.voiceclear.app.engine.AudioCapture.ERR_NOT_RECORDING,
        )
    }

    @Test
    fun `deleted audio leaves an invalid audit record and is not exported`() {
        val store = CaptureStore(context)
        store.clearAll()
        store.writeConsent(activeConsent("spk_retention"))
        val segment = sampleSegment("ses_retention", "seg_retention", "spk_retention")
        store.saveSegment(segment.sessionId, segment, ByteArray(1024))
        val audio = File(store.root(), segment.audioFileName)
        val now = System.currentTimeMillis()
        assertTrue(audio.setLastModified(now - 60L * 24 * 60 * 60 * 1000))

        val result = DataRetentionPolicy(context, retentionDays = 30, clock = { now }).enforce()

        assertEquals(1, result.deletedFiles)
        assertFalse(audio.exists())
        val retained = store.readAllSegments().single()
        assertFalse(retained.audioAvailable)
        assertEquals(CaptureStore.RETENTION_DELETION_REASON, retained.deletionReason)
        assertEquals(0, store.statistics().segments)
        val exported = store.exportMetadata()
        val exportedTranscript = File(exported, "transcripts/${segment.sessionId}.jsonl")
        assertFalse("删除后的记录不得进入有效数据包", exportedTranscript.exists())
    }

    @Test
    fun `cleanup never deletes an in progress partial file`() {
        val store = CaptureStore(context)
        store.clearAll()
        val partial = File(store.segmentsDir("ses_partial"), "raw/working.pcm.part")
        partial.parentFile?.mkdirs()
        partial.writeBytes(ByteArray(16))
        val now = System.currentTimeMillis()
        assertTrue(partial.setLastModified(now - 60L * 24 * 60 * 60 * 1000))

        val result = DataRetentionPolicy(context, retentionDays = 30, clock = { now }).enforce()

        assertEquals(0, result.deletedFiles)
        assertTrue(partial.exists())
    }

    @Test
    fun `startup scheduler can be controlled and run synchronously in tests`() {
        val store = CaptureStore(context)
        store.clearAll()
        val old = File(store.segmentsDir("ses_scheduled"), "old.pcm")
        old.writeBytes(ByteArray(8))
        val now = System.currentTimeMillis()
        assertTrue(old.setLastModified(now - 60L * 24 * 60 * 60 * 1000))
        var executions = 0
        val direct = Executor { task ->
            executions++
            task.run()
        }
        val scheduler = CaptureRetentionScheduler(
            context = context,
            executor = direct,
            policy = DataRetentionPolicy(context, retentionDays = 30, clock = { now }),
        )

        scheduler.enqueue()

        assertEquals(1, executions)
        assertFalse(old.exists())
    }

    private fun activeConsent(speakerId: String) = CaptureConsent(
        speakerId = speakerId,
        consentVersion = "v1.0",
        signedAt = "2026-11-20T10:00:00+08:00",
        grants = mapOf(ConsentFlags.RESEARCH_USE to true),
        withdrawnAt = null,
        withdrawalScopeNote = "note",
        compensation = "unspecified",
    )

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
}

/** 禁止 Robolectric 启动后台清理，避免与过期文件断言竞争。 */
class CaptureRetentionTestApplication : VoiceClearApplication() {
    override fun scheduleRetentionCleanup() = Unit
}
