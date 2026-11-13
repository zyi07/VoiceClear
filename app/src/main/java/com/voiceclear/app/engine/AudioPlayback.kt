package com.voiceclear.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 语音播放控制。
 *
 * API 来源（本地 SDK /opt/android-sdk/platforms/android-34，javap 实测签名）：
 * - android.media.AudioTrack(int, int, int, int, int, int)
 * - android.media.AudioTrack.play() / stop() / release() / write(byte[], int, int)
 * - android.media.AudioAttributes.Builder().setUsage(int).setContentType(int).build()
 * - android.media.AudioFormat.ENCODING_PCM_16BIT = 2 / CHANNEL_OUT_MONO = 4
 */
class AudioPlayback {
    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var track: AudioTrack? = null

    /** 播放一段 PCM 音频；返回 false 表示无法播放，调用方应走降级路径。 */
    fun play(segment: AudioSegment): Boolean {
        val bytesPerSample = 2
        val minBuffer = AudioTrack.getMinBufferSize(
            segment.sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer else segment.samples.size
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(segment.sampleRate)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (error: IllegalArgumentException) {
            return false
        } catch (error: IllegalStateException) {
            return false
        }

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release()
            return false
        }

        return try {
            audioTrack.play()
            var offset = 0
            while (offset < segment.samples.size) {
                val written = audioTrack.write(
                    segment.samples,
                    offset,
                    segment.samples.size - offset,
                )
                if (written <= 0) break
                offset += written
            }
            true
        } catch (error: IllegalStateException) {
            false
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
        }
    }

    /** 停止当前播放。新会话开始时必须调用，避免与新输出重叠。 */
    fun stop() {
        val current = track ?: return
        runCatching { current.stop() }
        runCatching { current.release() }
        track = null
    }
}
