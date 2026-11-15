package com.voiceclear.app.session

/**
 * 全局错误码。
 *
 * **为什么集中定义**：
 * 过去错误码分散在 5 个引擎文件里，命名风格也不一致
 * （`ERR_PERMISSION_DENIED` 与 `asr_model_missing` 混用），
 * 排障时无法从日志快速判断错误属于哪个环节、是否能重试。
 *
 * **命名约定**：
 * - 形如 `<域>_<原因>`，全小写下划线；
 * - 域取值：`capture`（采集）、`asr`（识别）、`tts`（合成）、`focus`（音频焦点）、
 *   `model`（模型分发）、`playback`（播放）、`session`（会话）；
 * - 以 `_retryable` 结尾表示**可重试**（网络抖动、临时不可用），
 *   其余为**不可重试**（配置错误、参数错误、状态错误）。
 *
 * 各引擎的 `companion object` 仍保留原有常量名（值指向这里），
 * 这样既统一了取值与分类，又不破坏既有调用点。
 */
object ErrorCodes {

    // ---------- 采集（麦克风） ----------

    /** 没有录音权限。不可重试，需用户授权。 */
    const val CAPTURE_PERMISSION_DENIED = "capture_permission_denied"

    /** 音频缓冲区大小非法。不可重试，设备不支持该配置。 */
    const val CAPTURE_INVALID_BUFFER = "capture_invalid_buffer"

    /** 录音参数非法。不可重试。 */
    const val CAPTURE_ILLEGAL_ARGUMENT = "capture_illegal_argument"

    /** 录音器未初始化。不可重试。 */
    const val CAPTURE_NOT_INITIALIZED = "capture_not_initialized"

    /** 当前不在录音状态。不可重试，调用顺序错误。 */
    const val CAPTURE_NOT_RECORDING = "capture_not_recording"

    /** 录音状态非法。不可重试。 */
    const val CAPTURE_ILLEGAL_STATE = "capture_illegal_state"

    /** 采集到空音频。 */
    const val CAPTURE_EMPTY = "capture_empty"

    /** 音频时长过短，视为无效语音。 */
    const val CAPTURE_TOO_SHORT = "capture_too_short"

    // ---------- 识别（ASR） ----------

    /** 模型文件缺失。不可重试，需先下载模型。 */
    const val ASR_MODEL_MISSING = "asr_model_missing"

    /** 模型加载失败。不可重试。 */
    const val ASR_MODEL_LOAD_FAILED = "asr_model_load_failed"

    /** 解码失败。不可重试。 */
    const val ASR_DECODE_FAILED = "asr_decode_failed"

    /** 输入音频为空。 */
    const val ASR_EMPTY_AUDIO = "asr_empty_audio"

    /** 采样率与模型要求不符。不可重试，需修采集配置。 */
    const val ASR_SAMPLE_RATE_MISMATCH = "asr_sample_rate_mismatch"

    /** 模型不可用（旧引擎保留）。 */
    const val ASR_MODEL_NOT_AVAILABLE = "asr_model_not_available"

    /** 模型非法（旧引擎保留）。 */
    const val ASR_MODEL_INVALID = "asr_model_invalid"

    // ---------- 合成（TTS） ----------

    /** 合成引擎未就绪。不可重试。 */
    const val TTS_NOT_READY = "tts_not_ready"

    /** 目标语言不可用。不可重试。 */
    const val TTS_LANGUAGE_UNAVAILABLE = "tts_language_unavailable"

    /** 朗读失败。 */
    const val TTS_SPEAK_FAILED = "tts_speak_failed"

    /** 朗读过程中出错。 */
    const val TTS_UTTERANCE_ERROR = "tts_utterance_error"

    /** 合成超时。可重试。 */
    const val TTS_SYNTHESIS_TIMEOUT = "tts_synthesis_timeout"

    /** 引擎初始化超时。可重试。 */
    const val TTS_INIT_TIMEOUT = "tts_init_timeout"

    // ---------- 音频焦点 ----------

    /** 未能取得音频焦点。 */
    const val FOCUS_DENIED = "audio_focus_denied"

    // ---------- 模型分发 ----------

    /** 模型下载失败。可重试。 */
    const val MODEL_DOWNLOAD_FAILED = "model_download_failed"

    /** 模型下载源不可达。可重试。 */
    const val MODEL_SOURCE_UNREACHABLE = "model_source_unreachable"

    /** 模型文件大小或校验和不符。不可重试，需重新下载。 */
    const val MODEL_CHECKSUM_MISMATCH = "model_checksum_mismatch"

    /** 无可用网络。 */
    const val MODEL_NO_NETWORK = "model_no_network"

    // ---------- 会话 ----------

    /** 会话已被新的操作取代，结果作废。 */
    const val SESSION_STALE = "session_stale"

    /** 用户取消。 */
    const val SESSION_CANCELLED = "session_cancelled"

    /**
     * 判断错误码是否可重试。
     *
     * 不可重试的错误重试只会浪费时间，例如权限未授权、参数错误。
     */
    fun isRetryable(code: String): Boolean {
        return code.endsWith("_retryable") ||
            code == MODEL_DOWNLOAD_FAILED ||
            code == MODEL_SOURCE_UNREACHABLE ||
            code == MODEL_NO_NETWORK ||
            code == TTS_SYNTHESIS_TIMEOUT ||
            code == TTS_INIT_TIMEOUT ||
            code == FOCUS_DENIED
    }

    /**
     * 错误码所属环节，用于日志分组与统计。
     *
     * 取下划线前的第一段作为域。
     */
    fun domain(code: String): String {
        val separator = code.indexOf('_')
        return if (separator > 0) code.substring(0, separator) else "unknown"
    }

    /** 人类可读的简短说明（中文），用于诊断包汇总。 */
    fun describe(code: String): String {
        return when (code) {
            CAPTURE_PERMISSION_DENIED -> "没有录音权限"
            CAPTURE_INVALID_BUFFER -> "音频缓冲区配置不被设备支持"
            CAPTURE_ILLEGAL_ARGUMENT -> "录音参数非法"
            CAPTURE_NOT_INITIALIZED -> "录音器初始化失败"
            CAPTURE_NOT_RECORDING -> "当前不在录音状态"
            CAPTURE_ILLEGAL_STATE -> "录音状态非法"
            CAPTURE_EMPTY -> "没有采集到音频"
            CAPTURE_TOO_SHORT -> "录音过短"
            ASR_MODEL_MISSING -> "识别模型缺失"
            ASR_MODEL_LOAD_FAILED -> "识别模型加载失败"
            ASR_DECODE_FAILED -> "识别解码失败"
            ASR_EMPTY_AUDIO -> "输入音频为空"
            ASR_SAMPLE_RATE_MISMATCH -> "采样率与模型要求不符"
            ASR_MODEL_NOT_AVAILABLE -> "识别模型不可用"
            ASR_MODEL_INVALID -> "识别模型非法"
            TTS_NOT_READY -> "语音合成引擎未就绪"
            TTS_LANGUAGE_UNAVAILABLE -> "合成语言不可用"
            TTS_SPEAK_FAILED -> "朗读失败"
            TTS_UTTERANCE_ERROR -> "朗读过程出错"
            TTS_SYNTHESIS_TIMEOUT -> "合成超时"
            TTS_INIT_TIMEOUT -> "合成引擎初始化超时"
            FOCUS_DENIED -> "未取得音频焦点"
            MODEL_DOWNLOAD_FAILED -> "模型下载失败"
            MODEL_SOURCE_UNREACHABLE -> "下载源不可达"
            MODEL_CHECKSUM_MISMATCH -> "模型文件校验不通过"
            MODEL_NO_NETWORK -> "无可用网络"
            SESSION_STALE -> "会话已过期"
            SESSION_CANCELLED -> "用户已取消"
            else -> "未知错误"
        }
    }
}
