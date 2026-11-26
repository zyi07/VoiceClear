package com.voiceclear.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/** 可替换的 AudioTrack 测试缝；生产实现仍只使用系统 AudioTrack。 */
internal interface PlaybackTrack {
    val state: Int
    fun play()
    fun write(buffer: ByteArray, offset: Int, size: Int): Int
    fun pause()
    fun flush()
    fun stop()
    fun release()
}

internal fun interface PlaybackTrackFactory {
    fun create(segment: AudioSegment): PlaybackTrack
}

/**
 * 语音播放控制。
 *
 * 当前轨道在播放线程与停止线程之间共享。停止会先递增 stopEpoch，再 pause/flush/stop/release，
 * 使阻塞中的 write 以失败退出，并覆盖轨道创建期间的取消；旧播放的 finally 不会误关新轨道。
 * 正常完成不 flush，而是调用 MODE_STREAM 的 stop：按 Android 文档，最后写入的缓冲区播放完后停止。
 *
 * API 来源：本地 /opt/android-sdk/platforms/android-36/android.jar（javap）与
 * https://developer.android.com/reference/android/media/AudioTrack 。
 * AudioTrack.play/stop/pause/flush/release/write(byte[],int,int) 均在 minSdk 26 可用。
 */
class AudioPlayback internal constructor(
    private val trackFactory: PlaybackTrackFactory,
) {
    constructor() : this(PlaybackTrackFactory { segment -> createSystemTrack(segment) })

    companion object {
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private fun createSystemTrack(segment: AudioSegment): PlaybackTrack {
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
            val track = AudioTrack.Builder()
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
            return object : PlaybackTrack {
                override val state: Int get() = track.state
                override fun play() = track.play()
                override fun write(buffer: ByteArray, offset: Int, size: Int): Int =
                    track.write(buffer, offset, size)
                override fun pause() = track.pause()
                override fun flush() = track.flush()
                override fun stop() = track.stop()
                override fun release() = track.release()
            }
        }
    }

    private class ActivePlayback(val track: PlaybackTrack) {
        val stopRequested = AtomicBoolean(false)
        private val cleanedUp = AtomicBoolean(false)

        fun interrupt() {
            stopRequested.set(true)
            cleanup(interrupt = true)
        }

        /** 正常完成不能 flush，否则可能丢弃已经写入但尚未播放的末尾缓冲。 */
        fun complete() {
            cleanup(interrupt = stopRequested.get())
        }

        private fun cleanup(interrupt: Boolean) {
            if (!cleanedUp.compareAndSet(false, true)) return
            if (interrupt) {
                runCatching { track.pause() }
                runCatching { track.flush() }
            }
            // 每一步都独立尝试，确保即使 stop 抛状态异常也仍执行 release。
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private val trackLock = Any()
    private var current: ActivePlayback? = null
    private var stopEpoch: Long = 0L

    private fun invalidateCurrent(): Pair<ActivePlayback?, Long> = synchronized(trackLock) {
        stopEpoch += 1
        (current.also { current = null }) to stopEpoch
    }

    /** 播放一段 PCM；只有全部字节写完且未被停止才返回 true。 */
    fun play(segment: AudioSegment): Boolean {
        // 先取得停止纪元：即使 stop 发生在 AudioTrack 创建期间，本次播放也不能注册。
        val (previousAtStart, requestEpoch) = invalidateCurrent()
        previousAtStart?.interrupt()
        val audioTrack = try {
            trackFactory.create(segment)
        } catch (error: IllegalArgumentException) {
            return false
        } catch (error: IllegalStateException) {
            return false
        }

        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { audioTrack.release() }
            return false
        }

        val active = ActivePlayback(audioTrack)
        var accepted = false
        val previous = synchronized(trackLock) {
            if (stopEpoch == requestEpoch) {
                accepted = true
                current.also { current = active }
            } else {
                null
            }
        }
        if (!accepted) {
            active.interrupt()
            return false
        }
        // 处理两个 play 并发注册的情况：后注册者拥有输出，前者必须退出。
        previous?.interrupt()

        return try {
            if (active.stopRequested.get()) return false
            audioTrack.play()
            var offset = 0
            while (offset < segment.samples.size) {
                if (active.stopRequested.get()) return false
                val written = audioTrack.write(
                    segment.samples,
                    offset,
                    segment.samples.size - offset,
                )
                if (written <= 0) return false
                offset += written
            }
            if (offset != segment.samples.size || active.stopRequested.get()) {
                false
            } else {
                // 成功与 stop 在同一锁上线性化；一旦标记完成，后来的 stop 不把成功改成取消。
                synchronized(trackLock) {
                    if (current !== active || active.stopRequested.get()) {
                        false
                    } else {
                        current = null
                        true
                    }
                }
            }
        } catch (error: IllegalStateException) {
            false
        } finally {
            synchronized(trackLock) {
                if (current === active) current = null
            }
            active.complete()
        }
    }

    /** 停止当前播放；可安全重复调用，并会中断正在阻塞的 write。 */
    fun stop() {
        // 即使尚未注册 current 也递增纪元，关闭“创建期间 stop 空跑”的窗口。
        invalidateCurrent().first?.interrupt()
    }
}
