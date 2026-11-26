package com.voiceclear.app.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.core.content.ContextCompat
import com.voiceclear.app.session.DiagnosticLog
import com.voiceclear.app.session.ErrorCodes
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 可替换的 AudioRecord 测试缝；生产实现仍只使用系统 AudioRecord。 */
internal interface CaptureRecorder {
    val state: Int
    val recordingState: Int
    fun startRecording()
    fun stop()
    fun read(buffer: ByteArray, offset: Int, size: Int): Int
    fun release()
}

internal fun interface CaptureRecorderFactory {
    fun create(source: Int, sampleRate: Int, channel: Int, format: Int, bufferSize: Int): CaptureRecorder
}

/**
 * 麦克风采集器。
 *
 * 每次会话都有明确的 Recording/Completed/Failed/Stopped 状态。自动停止、读取失败、
 * 显式停止和取消都经过同一个幂等清理入口；读取或 join 失败不会返回截断的 Success。
 *
 * API 来源：本地 /opt/android-sdk/platforms/android-36/android.jar（javap）。
 * AudioRecord(int,int,int,int,int)、getMinBufferSize、startRecording/stop/read/release、
 * STATE_INITIALIZED/RECORDSTATE_RECORDING 均兼容 minSdk 26。
 */
class AudioCapture(
    private val context: Context,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val sourceOption: AudioSourceOption = AudioSourceOption.DEFAULT,
) {
    internal constructor(
        context: Context,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        sourceOption: AudioSourceOption = AudioSourceOption.DEFAULT,
        recorderFactory: CaptureRecorderFactory,
        minBufferSizeProvider: () -> Int,
        clock: () -> Long,
        joinTimeoutMs: Long = 2_000L,
    ) : this(context, sampleRate, sourceOption) {
        this.recorderFactory = recorderFactory
        this.minBufferSizeProvider = minBufferSizeProvider
        this.clock = clock
        this.joinTimeoutMs = joinTimeoutMs
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4

        /** 单次录音上限，防止用户忘记停止导致无限占用内存。 */
        const val MAX_DURATION_MS = 30_000L

        private const val MIN_VOICE_DURATION_MS = 200L

        const val ERR_PERMISSION_DENIED = ErrorCodes.CAPTURE_PERMISSION_DENIED
        const val ERR_INVALID_BUFFER = ErrorCodes.CAPTURE_INVALID_BUFFER
        const val ERR_ILLEGAL_ARGUMENT = ErrorCodes.CAPTURE_ILLEGAL_ARGUMENT
        const val ERR_NOT_INITIALIZED = ErrorCodes.CAPTURE_NOT_INITIALIZED
        const val ERR_NOT_RECORDING = ErrorCodes.CAPTURE_NOT_RECORDING
        const val ERR_ILLEGAL_STATE = ErrorCodes.CAPTURE_ILLEGAL_STATE
        const val ERR_READ_FAILED = "capture_read_failed"
        const val ERR_JOIN_TIMEOUT = "capture_join_timeout"

        const val FLAG_EMPTY_CAPTURE = ErrorCodes.CAPTURE_EMPTY
        const val FLAG_TOO_SHORT = ErrorCodes.CAPTURE_TOO_SHORT
    }

    sealed interface CaptureResult {
        data class Success(val segment: AudioSegment) : CaptureResult
        data class Failure(val code: String) : CaptureResult
    }

    private enum class SessionState {
        RECORDING,
        COMPLETED,
        FAILED,
        STOPPED,
    }

    private inner class Session(
        val sessionId: String,
        val recorder: CaptureRecorder,
        val buffer: ByteArray,
        val samples: ByteArrayOutputStream,
        val startedAt: Long,
    ) {
        val stopRequested = AtomicBoolean(false)
        private val cleanedUp = AtomicBoolean(false)

        @Volatile
        var state = SessionState.RECORDING

        @Volatile
        var failureCode: String? = null

        @Volatile
        var readerThread: Thread? = null

        fun fail(code: String) {
            if (state == SessionState.RECORDING) {
                failureCode = code
                state = SessionState.FAILED
            }
        }

        fun finishFromReader() {
            if (state == SessionState.RECORDING) {
                state = if (stopRequested.get()) SessionState.STOPPED else SessionState.COMPLETED
            }
        }

        /** 所有退出路径共用的幂等资源清理。 */
        fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    // 工厂只在 start() 的两处显式权限检查后调用，且构造/启动均捕获 SecurityException。
    @SuppressLint("MissingPermission")
    private var recorderFactory = CaptureRecorderFactory { source, rate, channel, format, bufferSize ->
        val recorder = AudioRecord(source, rate, channel, format, bufferSize)
        object : CaptureRecorder {
            override val state: Int get() = recorder.state
            override val recordingState: Int get() = recorder.recordingState
            override fun startRecording() = recorder.startRecording()
            override fun stop() = recorder.stop()
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                recorder.read(buffer, offset, size)
            override fun release() = recorder.release()
        }
    }
    private var minBufferSizeProvider: () -> Int = {
        AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
    }
    private var clock: () -> Long = System::currentTimeMillis
    private var joinTimeoutMs: Long = 2_000L

    private val sessionLock = Any()

    @Volatile
    private var current: Session? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 自动停止或失败后虽保留结果供 stop 消费，但不再谎报正在录音。 */
    fun isRecording(): Boolean = current?.state == SessionState.RECORDING

    /** 开始录音。null 表示成功，否则返回错误码。 */
    @Synchronized
    fun start(sessionId: String): String? {
        if (current != null) return ERR_ILLEGAL_STATE
        if (!hasPermission()) return ERR_PERMISSION_DENIED

        val minBuffer = minBufferSizeProvider()
        if (minBuffer <= 0) {
            DiagnosticLog.e("Capture", "getMinBufferSize 失败: " + minBuffer)
            return ERR_INVALID_BUFFER
        }
        val bufferSize = minBuffer * BUFFER_SIZE_FACTOR
        val recorder = try {
            // Lint 需要在受保护 API 调用点直接看到权限检查。
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return ERR_PERMISSION_DENIED
            }
            recorderFactory.create(
                sourceOption.sourceConstant,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )
        } catch (error: IllegalArgumentException) {
            DiagnosticLog.e("Capture", "AudioRecord 构造失败: " + error.message)
            return ERR_ILLEGAL_ARGUMENT
        } catch (error: IllegalStateException) {
            DiagnosticLog.e("Capture", "AudioRecord 状态异常: " + error.message)
            return ERR_ILLEGAL_STATE
        } catch (error: SecurityException) {
            DiagnosticLog.e("Capture", "AudioRecord 权限被拒: " + error.message)
            return ERR_PERMISSION_DENIED
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            DiagnosticLog.e("Capture", "AudioRecord 未初始化")
            return ERR_NOT_INITIALIZED
        }

        val session = Session(
            sessionId = sessionId,
            recorder = recorder,
            buffer = ByteArray(bufferSize),
            samples = ByteArrayOutputStream(),
            startedAt = clock(),
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
            val thread = Thread({ readLoop(session) }, "VoiceClear-AudioReader")
            thread.isDaemon = true
            session.readerThread = thread
            thread.start()
            DiagnosticLog.i(
                "Capture",
                "录音已开始, 采样率=" + sampleRate + ", 缓冲区=" + bufferSize +
                    " 字节, 音源=" + sourceOption.id + ", sessionId=" + sessionId,
            )
            null
        } catch (error: SecurityException) {
            synchronized(sessionLock) { if (current === session) current = null }
            session.fail(ERR_PERMISSION_DENIED)
            session.cleanup()
            DiagnosticLog.e("Capture", "startRecording 权限被拒: " + error.message)
            ERR_PERMISSION_DENIED
        } catch (error: IllegalStateException) {
            synchronized(sessionLock) { if (current === session) current = null }
            session.fail(ERR_ILLEGAL_STATE)
            session.cleanup()
            DiagnosticLog.e("Capture", "startRecording 状态异常: " + error.message)
            ERR_ILLEGAL_STATE
        }
    }

    /** 停止并返回完整音频；读取失败或 join 超时均明确返回 Failure。 */
    fun stop(): CaptureResult {
        val session = synchronized(sessionLock) {
            current.also { current = null }
        } ?: return CaptureResult.Failure(ERR_NOT_RECORDING)

        session.stopRequested.set(true)
        // stop/release 会解除阻塞 read；cleanup 幂等，读线程 finally 可再次调用。
        session.cleanup()
        val thread = session.readerThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(joinTimeoutMs)
            if (thread.isAlive) {
                session.fail(ERR_JOIN_TIMEOUT)
                session.cleanup()
                DiagnosticLog.e("Capture", "读取线程 join 超时，丢弃本次音频")
                return CaptureResult.Failure(ERR_JOIN_TIMEOUT)
            }
        }
        session.cleanup()
        session.failureCode?.let { code ->
            DiagnosticLog.e("Capture", "录音失败，丢弃不完整音频: " + code)
            return CaptureResult.Failure(code)
        }

        val byteCount = session.samples.size()
        val durationMs = if (sampleRate > 0) {
            byteCount.toLong() * 1000L / (sampleRate.toLong() * 2L)
        } else {
            0L
        }
        val qualityFlags = mutableListOf<String>()
        if (byteCount == 0) qualityFlags.add(FLAG_EMPTY_CAPTURE)
        if (durationMs < MIN_VOICE_DURATION_MS) qualityFlags.add(FLAG_TOO_SHORT)

        DiagnosticLog.i(
            "Capture",
            "录音已停止, 时长=" + durationMs + "ms, 字节=" + byteCount +
                ", sessionId=" + session.sessionId,
        )
        return CaptureResult.Success(
            AudioSegment(
                sessionId = session.sessionId,
                sampleRate = sampleRate,
                channelCount = 1,
                encoding = AudioEncoding.PCM_16BIT,
                durationMs = durationMs,
                samples = session.samples.toByteArray(),
                qualityFlags = qualityFlags,
            ),
        )
    }

    /** 丢弃当前录音（用于取消），可与读线程清理安全重入。 */
    fun cancel() {
        val session = synchronized(sessionLock) {
            current.also { current = null }
        } ?: return
        session.stopRequested.set(true)
        session.cleanup()
        val thread = session.readerThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(joinTimeoutMs)
            if (thread.isAlive) {
                session.fail(ERR_JOIN_TIMEOUT)
                DiagnosticLog.e("Capture", "取消时读取线程 join 超时")
            }
        }
        session.cleanup()
        DiagnosticLog.i("Capture", "录音已取消, sessionId=" + session.sessionId)
    }

    private fun readLoop(session: Session) {
        try {
            while (!session.stopRequested.get()) {
                val elapsed = clock() - session.startedAt
                if (elapsed >= MAX_DURATION_MS) {
                    session.state = SessionState.COMPLETED
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
                    if (!session.stopRequested.get()) {
                        session.fail(ERR_READ_FAILED)
                        DiagnosticLog.e("Capture", "read 返回错误码: " + read)
                    }
                    break
                }
            }
        } catch (error: Throwable) {
            if (!session.stopRequested.get()) {
                session.fail(ERR_READ_FAILED)
                DiagnosticLog.e(
                    "Capture",
                    "读取循环异常: " + (error.message ?: error.javaClass.simpleName),
                )
            }
        } finally {
            session.finishFromReader()
            session.cleanup()
        }
    }
}
