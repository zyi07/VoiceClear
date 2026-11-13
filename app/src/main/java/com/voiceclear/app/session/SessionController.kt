package com.voiceclear.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 会话控制器：维护当前会话 ID 与状态，并拒绝过期会话的迟到结果。
 *
 * 该类不依赖 Android API，可通过 JVM 单元测试覆盖状态流转与竞态行为。
 */
class SessionController(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableStateFlow<SessionEvent?>(null)
    val events: StateFlow<SessionEvent?> = _events.asStateFlow()

    private var currentSessionId: String? = null

    val sessionId: String?
        get() = currentSessionId

    /** 开始新会话；非可启动状态时返回 false，避免覆盖进行中的表达。 */
    fun startSession(): Boolean {
        if (!_state.value.canStartNewSession) return false
        currentSessionId = idGenerator()
        transition(SessionState.RECORDING, currentSessionId!!)
        return true
    }

    fun stopCapture(): Boolean = transitionFromCurrent(SessionState.PROCESSING_RECOGNIZING)

    fun onRecognitionReady(): Boolean = transitionFromCurrent(SessionState.AWAITING_CONFIRMATION)

    fun onConfirmed(): Boolean = transitionFromCurrent(SessionState.PROCESSING_SYNTHESIZING)

    fun onSynthesisReady(): Boolean = transitionFromCurrent(SessionState.PLAYING)

    fun onPlaybackFinished(): Boolean = transitionFromCurrent(SessionState.COMPLETED)

    fun cancel(): Boolean = transitionFromCurrent(SessionState.CANCELLED)

    fun fail(stage: ProcessingStage, code: String): Boolean {
        val id = currentSessionId ?: return false
        if (!transitionFromCurrent(SessionState.ERROR)) return false
        _events.value = SessionEvent.Error(id, stage, code)
        return true
    }

    /** 丢弃不属于当前会话的迟到结果。 */
    fun isCurrent(sessionId: String): Boolean = currentSessionId == sessionId

    /** 会话结束后清除当前会话标识，避免旧结果被误认为有效。 */
    fun finish() {
        currentSessionId = null
        _state.value = SessionState.IDLE
    }

    private fun transitionFromCurrent(target: SessionState): Boolean {
        val id = currentSessionId ?: return false
        if (!isTransitionAllowed(_state.value, target)) return false
        transition(target, id)
        return true
    }

    private fun transition(target: SessionState, sessionId: String) {
        _state.value = target
        _events.value = SessionEvent.StateChanged(sessionId, target)
    }

    private fun isTransitionAllowed(from: SessionState, to: SessionState): Boolean = when (from) {
        SessionState.RECORDING -> to == SessionState.PROCESSING_RECOGNIZING ||
            to == SessionState.CANCELLED ||
            to == SessionState.ERROR

        SessionState.PROCESSING_RECOGNIZING -> to == SessionState.AWAITING_CONFIRMATION ||
            to == SessionState.PROCESSING_SYNTHESIZING ||
            to == SessionState.CANCELLED ||
            to == SessionState.ERROR

        SessionState.AWAITING_CONFIRMATION -> to == SessionState.PROCESSING_SYNTHESIZING ||
            to == SessionState.CANCELLED ||
            to == SessionState.ERROR

        SessionState.PROCESSING_SYNTHESIZING -> to == SessionState.PLAYING ||
            to == SessionState.CANCELLED ||
            to == SessionState.ERROR

        SessionState.PLAYING -> to == SessionState.COMPLETED ||
            to == SessionState.CANCELLED ||
            to == SessionState.ERROR

        SessionState.IDLE, SessionState.COMPLETED, SessionState.CANCELLED, SessionState.ERROR ->
            to == SessionState.RECORDING ||
                to == SessionState.CANCELLED ||
                to == SessionState.ERROR
    }

    /** 供测试与诊断使用的耗时计量。 */
    fun elapsedMs(startedAt: Long): Long = now() - startedAt
}
