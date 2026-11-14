package com.voiceclear.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.voiceclear.app.session.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集器。
 *
 * API 来源（本地 SDK /opt/android-sdk/platforms/android-34，javap 实测签名）：
 * - android.media.AudioRecord(int, int, int, int, int)
 * - android.media.AudioRecord.getMinBufferSize(int, int, int)
 * - android.media.AudioRecord.startRecording() / stop() / read(byte[], int, int) / release()
 * - android.media.AudioRecord.STATE_INITIALIZED / RECORDSTATE_RECORDING
 * - android.media.AudioFormat.ENCODING_PCM_16BIT = 2
 * - android.media.AudioFormat.CHANNEL_IN_MONO = 16
 * - android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
 *
 * 首版使用「点击开始、再次点击结束」的显式端点，不使用 VAD 自动切分，
 * 避免把长停顿、重复与阻塞误判为结束（技术指导文件 5.5 节）。
 *
 * **重要设计（曾因实现错误导致 60 秒空转）**：
 * 录音必须**立即开始**，由调用方主动调用 [stop] 结束。
 * 早期实现把采集写成阻塞方法 `capture()`，在后台线程里一直读到 60 秒上限，
 * 造成「用户以为已停止，实际还在录满 60 秒」，识别结果自然是空的。
 * 现在改为 [start] / [stop] 两段式：start 立即返回并开始后台读取，
 * stop 停止读取并返回音频。
 */
class AudioCapture(
    private val context: Context,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 使用 VOICE_RECOGNITION 而非 MIC，避免部分设备的 AGC/降噪影响微弱发音线索。 */
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        private const val BUFFER_SIZE_FACTOR = 4

        /** 单次录音上限，防止用户忘记停止导致无限占用内存。 */
        const val MAX_DURATION_MS = 30_000L

        /** 低于此时长视为没有有效语音。 */
        private const val MIN_VOICE_DURATION_MS = 200L

        const val ERR_PERMISSION_DENIED = "capture_permission_denied"
        const val ERR_INVALID_BUFFER = "capture_invalid_buffer"
        const val ERR_ILLEGAL_ARGUMENT = "capture_illegal_argument"
        const val ERR_NOT_INITIALIZED = "capture_not_initialized"
        const val ERR_NOT_RECORDING = "capture_not_recording"
        const val ERR_ILLEGAL_STATE = "capture_illegal_state"

        const val FLAG_EMPTY_CAPTURE = "empty_capture"
        const val FLAG_TOO_SHORT = "too_short"
    }

    sealed interface CaptureResult {
        data class Success(val segment: AudioSegment) : CaptureResult
        data class Failure(val code: String) : CaptureResult
    }

    /** 正在录音的会话。 */
    private class Session(
        val recorder: AudioRecord,
        val buffer: ByteArray,
        val samples: ByteArrayOutputStream,
        val startedAt: Long,
        val stopRequested: AtomicBoolean,
    )

    private var current: Session? = null
    private var readerThread: Thread? = null

    /**
     * 标准形式的运行时权限检查。
     *
     * 保持为直接调用 ContextCompat.checkSelfPermission 的形式，便于静态检查工具
     * 在调用受保护 API 的位置识别到「已检查权限」的事实。
     */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 是否正在录音。 */
    fun isRecording(): Boolean = current != null

    /**
     * 开始录音。立即返回，音频在后台线程持续读取。
     *
     * @return null 表示启动成功，否则返回失败原因码。
     */
    fun start(sessionId: String): String? {
        if (current != null) {
            return ERR_ILLEGAL_STATE
        }
        if (!hasPermission()) {
            return ERR_PERMISSION_DENIED
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        if (minBuffer <= 0) {
            DiagnosticLog.e("Capture", "getMinBufferSize 失败: " + minBuffer)
            return ERR_INVALID_BUFFER
        }
        val bufferSize = minBuffer * BUFFER_SIZE_FACTOR

        val recorder = try {
            // 在构造点直接调用标准的 ContextCompat.checkSelfPermission：
            // Lint 只认这种直接调用形式，自定义包装函数无法被静态识别。
            // 权限也可能在检查后被撤销，因此后续仍有 SecurityException 兜底。
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return ERR_PERMISSION_DENIED
            }
            AudioRecord(AUDIO_SOURCE, sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        } catch (error: IllegalArgumentException) {
            DiagnosticLog.e("Capture", "AudioRecord 构造失败: " + error.message)
            return ERR_ILLEGAL_ARGUMENT
        } catch (error: IllegalStateException) {
            DiagnosticLog.e("Capture", "AudioRecord 状态异常: " + error.message)
            return ERR_ILLEGAL_STATE
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            DiagnosticLog.e("Capture", "AudioRecord 未初始化")
            return ERR_NOT_INITIALIZED
        }

        val session = Session(
            recorder = recorder,
            buffer = ByteArray(bufferSize),
            samples = ByteArrayOutputStream(),
            startedAt = System.currentTimeMillis(),
            stopRequested = AtomicBoolean(false),
        )

        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { recorder.release() }
                return ERR_PERMISSION_DENIED
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.release() }
                DiagnosticLog.e("Capture", "startRecording 后状态异常")
                return ERR_NOT_RECORDING
            }
            current = session

            val thread = Thread({
                readLoop(session)
            }, "VoiceClear-AudioReader")
            thread.isDaemon = true
            readerThread = thread
            thread.start()

            DiagnosticLog.i(
                "Capture",
                "录音已开始, 采样率=" + sampleRate + ", 缓冲区=" + bufferSize + " 字节",
            )
            null
        } catch (error: SecurityException) {
            runCatching { recorder.release() }
            DiagnosticLog.e("Capture", "startRecording 权限被拒: " + error.message)
            ERR_PERMISSION_DENIED
        } catch (error: IllegalStateException) {
            runCatching { recorder.release() }
            DiagnosticLog.e("Capture", "startRecording 状态异常: " + error.message)
            ERR_ILLEGAL_STATE
        }
    }

    /**
     * 停止录音并返回音频。
     *
     * 会等待读取线程收尾，因此返回时音频已完整。
     */
    fun stop(): CaptureResult {
        val session = current ?: return CaptureResult.Failure(ERR_NOT_RECORDING)
        current = null

        session.stopRequested.set(true)

        // 等待读取线程结束，最多等 2 秒，避免 read 阻塞导致界面卡住。
        readerThread?.join(2000)
        readerThread = null

        runCatching { session.recorder.stop() }
        runCatching { session.recorder.release() }

        val durationMs = if (sampleRate > 0) {
            session.samples.size().toLong() * 1000L / (sampleRate.toLong() * 2L)
        } else {
            0L
        }

        val qualityFlags = mutableListOf<String>()
        if (session.samples.size() == 0) {
            qualityFlags.add(FLAG_EMPTY_CAPTURE)
        }
        if (durationMs < MIN_VOICE_DURATION_MS) {
            qualityFlags.add(FLAG_TOO_SHORT)
        }

        DiagnosticLog.i(
            "Capture",
            "录音已停止, 时长=" + durationMs + "ms, 字节=" + session.samples.size(),
        )

        return CaptureResult.Success(
            AudioSegment(
                sessionId = "",
                sampleRate = sampleRate,
                channelCount = 1,
                encoding = AudioEncoding.PCM_16BIT,
                durationMs = durationMs,
                samples = session.samples.toByteArray(),
            ),
        )
    }

    /** 丢弃当前录音（用于取消）。 */
    fun cancel() {
        val session = current ?: return
        current = null
        session.stopRequested.set(true)
        readerThread?.join(1000)
        readerThread = null
        runCatching { session.recorder.stop() }
        runCatching { session.recorder.release() }
        DiagnosticLog.i("Capture", "录音已取消")
    }

    /**
     * 后台读取循环。
     *
     * 持续读取直到被请求停止或达到时长上限。
     */
    private fun readLoop(session: Session) {
        try {
            while (!session.stopRequested.get()) {
                val elapsed = System.currentTimeMillis() - session.startedAt
                if (elapsed >= MAX_DURATION_MS) {
                    DiagnosticLog.w(
                        "Capture",
                        "达到录音时长上限 " + MAX_DURATION_MS + "ms，自动停止",
                    )
                    break
                }
                val read = session.recorder.read(session.buffer, 0, session.buffer.size)
                if (read > 0) {
                    session.samples.write(session.buffer, 0, read)
                } else if (read < 0) {
                    DiagnosticLog.e("Capture", "read 返回错误码: " + read)
                    break
                }
            }
        } catch (error: Throwable) {
            DiagnosticLog.e(
                "Capture",
                "读取循环异常: " + (error.message ?: error.javaClass.simpleName),
            )
        }
    }
}
