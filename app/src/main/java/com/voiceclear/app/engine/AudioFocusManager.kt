package com.voiceclear.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voiceclear.app.session.ErrorCodes

internal interface AudioFocusBackend {
    fun request(listener: AudioManager.OnAudioFocusChangeListener): Boolean
    fun abandon()
}

/**
 * 音频焦点管理。focusGranted 与是否存在请求对象分开记录，LOSS 后不会再谎报持有焦点。
 *
 * API 来源：本地 /opt/android-sdk/platforms/android-36/android.jar（javap）。
 * AudioManager.requestAudioFocus(AudioFocusRequest) 与 abandonAudioFocusRequest 自 API 26
 * 可用，正好等于项目 minSdk；AudioFocusRequest.Builder 及焦点常量均复用现有实现。
 */
class AudioFocusManager internal constructor(
    private val backend: AudioFocusBackend,
) {
    constructor(context: Context) : this(SystemAudioFocusBackend(context))

    companion object {
        const val ERR_FOCUS_DENIED = ErrorCodes.FOCUS_DENIED
    }

    private class SystemAudioFocusBackend(context: Context) : AudioFocusBackend {
        private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private var focusRequest: AudioFocusRequest? = null

        override fun request(listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener)
                .build()
            val granted = audioManager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusRequest = request.takeIf { granted }
            return granted
        }

        override fun abandon() {
            val request = focusRequest ?: return
            runCatching { audioManager.abandonAudioFocusRequest(request) }
            focusRequest = null
        }
    }

    private val stateLock = Any()

    @Volatile
    private var focusRequested = false

    @Volatile
    private var focusGranted = false

    /** 焦点被外部抢占时的回调，由使用方决定如何降级。 */
    var onFocusLost: (() -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        var lost = false
        var abandon = false
        synchronized(stateLock) {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (focusRequested) {
                        focusGranted = false
                        focusRequested = false
                        lost = true
                        abandon = true
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (focusRequested) {
                        focusGranted = false
                        lost = true
                    }
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (focusRequested) focusGranted = true
                }

                else -> Unit
            }
        }
        if (abandon) runCatching { backend.abandon() }
        if (lost) onFocusLost?.invoke()
    }

    /** 请求播放焦点；只有系统本次明确授权才返回 true。 */
    fun requestFocus(): Boolean {
        synchronized(stateLock) {
            if (focusRequested && focusGranted) return true
            if (focusRequested) runCatching { backend.abandon() }
            focusRequested = false
            focusGranted = false
            val granted = try {
                backend.request(focusListener)
            } catch (error: RuntimeException) {
                false
            }
            focusRequested = granted
            focusGranted = granted
            return granted
        }
    }

    /** 释放播放焦点并同步清除真实状态。 */
    fun abandonFocus() {
        synchronized(stateLock) {
            if (!focusRequested && !focusGranted) return
            runCatching { backend.abandon() }
            focusRequested = false
            focusGranted = false
        }
    }

    /** 是否当前真正持有焦点，而不是仅是否创建过请求。 */
    fun hasFocus(): Boolean = focusGranted
}
