package com.voiceclear.app.session

import android.content.Context
import com.voiceclear.app.engine.AudioSourceOption

/**
 * 应用设置。
 *
 * 用 SharedPreferences 存储，进程重启后仍生效。
 *
 * **目前只有一项设置**：麦克风音源。
 * 之所以做成可切换，是因为「哪种音源对发音不清的人更好」
 * 无法由开发者替目标人群决定——必须在他们的真实设备上实测对比。
 */
class AppSettings(private val context: Context) {

    companion object {
        private const val PREF_NAME = "voiceclear_settings"
        private const val KEY_AUDIO_SOURCE = "audio_source_id"
    }

    private val prefs
        get() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 当前麦克风音源。 */
    var audioSource: AudioSourceOption
        get() = AudioSourceOption.fromId(prefs.getString(KEY_AUDIO_SOURCE, null))
        set(value) {
            prefs.edit().putString(KEY_AUDIO_SOURCE, value.id).apply()
            DiagnosticLog.i("Settings", "麦克风音源切换为: " + value.id)
        }

    /** 是否启用系统降噪。 */
    var noiseSuppressionEnabled: Boolean
        get() = prefs.getBoolean("noise_suppression", false)
        set(value) {
            prefs.edit().putBoolean("noise_suppression", value).apply()
            DiagnosticLog.i("Settings", "降噪开关: " + value)
        }

    /** 导出设置，便于诊断包核对。 */
    fun exportJson(): String {
        return "{" +
            "\"audioSource\":\"" + audioSource.id + "\"," +
            "\"audioSourceDisplayName\":\"" + audioSource.displayName + "\"," +
            "\"noiseSuppression\":" + noiseSuppressionEnabled +
            "}"
    }
}
