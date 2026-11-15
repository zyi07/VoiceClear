package com.voiceclear.app.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voiceclear.app.session.ErrorCodes

/**
 * 音频焦点管理。
 *
 * 语音表达场景的焦点规则：
 * - 播放合成语音时请求短暂独占焦点，提示其他应用暂停；
 * - 收到 AUDIOFOCUS_LOSS 时立即停止播放，避免与来电等高优先级音频叠加；
 * - 收到 TRANSIENT 抖动时暂停播放并等待恢复；
 * - 每次会话结束都必须释放焦点，否则会影响用户正常使用设备。
 *
 * API 来源（本地 SDK android-34，javap 实测签名）：
 * - android.media.AudioManager.requestAudioFocus(AudioFocusRequest) / abandonAudioFocusRequest(AudioFocusRequest)
 * - android.media.AudioFocusRequest.Builder(int) / setAudioAttributes(AudioAttributes) / setWillPauseWhenDucked(boolean) / build()
 * - android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE / AUDIOFOCUS_LOSS / AUDIOFOCUS_LOSS_TRANSIENT
 */
class AudioFocusManager(context: Context) {

    companion object {
        const val ERR_FOCUS_DENIED = ErrorCodes.FOCUS_DENIED
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** 焦点被外部抢占时的回调，由使用方决定如何降级。 */
    var onFocusLost: (() -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> onFocusLost?.invoke()

            else -> Unit
        }
    }

    /**
     * 请求播放焦点。返回 false 表示未获得焦点，调用方应放弃播放而不是强行发声。
     */
    fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        focusRequest = request
        return true
    }

    /** 释放播放焦点。会话结束时必须调用。 */
    fun abandonFocus() {
        val request = focusRequest ?: return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        focusRequest = null
    }

    /** 是否当前持有焦点。 */
    fun hasFocus(): Boolean = focusRequest != null
}
