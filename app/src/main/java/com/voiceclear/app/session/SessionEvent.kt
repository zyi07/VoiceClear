package com.voiceclear.app.session

/** 会话状态的对外事件，用于 UI 订阅与埋点。 */
sealed interface SessionEvent {
    data class StateChanged(val sessionId: String, val state: SessionState) : SessionEvent

    data class Error(val sessionId: String, val stage: ProcessingStage, val code: String) : SessionEvent

    data class Metrics(
        val sessionId: String,
        val stage: ProcessingStage,
        val durationMs: Long,
    ) : SessionEvent
}

/** 处理阶段，用于区分失败发生在哪一环节（技术指导文件 4.5 节）。 */
enum class ProcessingStage {
    CAPTURE,
    RECOGNITION,
    CONFIRMATION,
    SYNTHESIS,
    PLAYBACK,
}
