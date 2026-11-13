package com.voiceclear.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

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
 * 首版使用"按住/点击开始、明确结束"的显式端点，不使用 VAD 自动切分，
 * 避免把长停顿、重复与阻塞误判为结束（技术指导文件 5.5 节）。
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
        private const val DEFAULT_MAX_DURATION_MS = 60_000L
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

    /**
     * 标准形式的运行时权限检查。
     *
     * 保持为直接调用 ContextCompat.checkSelfPermission 的形式，便于静态检查工具
     * 在调用受保护 API 的位置识别到"已检查权限"的事实。
     */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 采集一段音频。调用方负责在结束时停止，采集期间保持读取以避免缓冲区溢出。
     *
     * 运行时权限可被随时撤销，因此这里既做显式检查，也捕获 SecurityException，
     * 保证权限缺失时返回可处理的失败而不是崩溃。
     */
    fun capture(sessionId: String, maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): CaptureResult {
        if (!hasPermission()) {
            return CaptureResult.Failure(ERR_PERMISSION_DENIED)
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        if (minBufferSize <= 0) {
            return CaptureResult.Failure(ERR_INVALID_BUFFER)
        }

        val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR
        // 再次就地确认权限：运行时权限可能在检查之后、构造之前被撤销。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CaptureResult.Failure(ERR_PERMISSION_DENIED)
        }
        val recorder = try {
            AudioRecord(
                AUDIO_SOURCE,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )
        } catch (error: IllegalArgumentException) {
            return CaptureResult.Failure(ERR_ILLEGAL_ARGUMENT)
        } catch (error: SecurityException) {
            return CaptureResult.Failure(ERR_PERMISSION_DENIED)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return CaptureResult.Failure(ERR_NOT_INITIALIZED)
        }

        val output = ByteArrayOutputStream()
        val readBuffer = ByteArray(bufferSize)
        val startedAt = System.currentTimeMillis()

        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                CaptureResult.Failure(ERR_NOT_RECORDING)
            } else {
                while (System.currentTimeMillis() - startedAt < maxDurationMs) {
                    val read = recorder.read(readBuffer, 0, readBuffer.size)
                    if (read > 0) {
                        output.write(readBuffer, 0, read)
                    } else if (read < 0) {
                        break
                    }
                }
                val samples = output.toByteArray()
                val durationMs = if (sampleRate > 0) {
                    samples.size.toLong() * 1000L / (sampleRate.toLong() * 2L)
                } else {
                    0L
                }
                val flags = buildList {
                    if (samples.isEmpty()) add(FLAG_EMPTY_CAPTURE)
                    if (durationMs < MIN_VOICE_DURATION_MS) add(FLAG_TOO_SHORT)
                }
                CaptureResult.Success(
                    AudioSegment(
                        sessionId = sessionId,
                        sampleRate = sampleRate,
                        channelCount = 1,
                        encoding = AudioEncoding.PCM_16BIT,
                        durationMs = durationMs,
                        samples = samples,
                        qualityFlags = flags,
                    ),
                )
            }
        } catch (error: IllegalStateException) {
            CaptureResult.Failure(ERR_ILLEGAL_STATE)
        } catch (error: SecurityException) {
            CaptureResult.Failure(ERR_PERMISSION_DENIED)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
