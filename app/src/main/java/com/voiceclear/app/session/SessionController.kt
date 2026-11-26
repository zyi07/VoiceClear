package com.voiceclear.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 会话控制器：维护当前会话令牌与状态，并拒绝过期或已取消会话的迟到结果。
 *
 * 会话 ID 用于业务关联，单调递增的 generation 用于并发校验。即使测试或自定义
 * ID 生成器重复产生相同 ID，旧任务也不能凭相同字符串写入新会话。
 */
class SessionController(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    data class Token(
        val sessionId: String,
        val generation: Long,
    )

    private val _state = MutableStateFlow(SessionState.IDLE)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableStateFlow<SessionEvent?>(null)
    val events: StateFlow<SessionEvent?> = _events.asStateFlow()

    private var currentSessionId: String? = null
    private var currentGeneration: Long = 0L
    private var currentValid: Boolean = false

    val sessionId: String?
        @Synchronized get() = currentSessionId

    val generation: Long
        @Synchronized get() = currentGeneration

    /** 开始新会话；非可启动状态时返回 false，避免覆盖进行中的表达。 */
    @Synchronized
    fun startSession(): Boolean {
        if (!_state.value.canStartNewSession) return false
        currentSessionId = idGenerator()
        currentGeneration += 1
        currentValid = true
        transition(SessionState.RECORDING, currentSessionId!!)
        return true
    }

    @Synchronized
    fun currentToken(): Token? = currentSessionId?.let { Token(it, currentGeneration) }

    @Synchronized
    fun stopCapture(): Boolean = transitionFromCurrent(SessionState.PROCESSING_RECOGNIZING)

    fun stopCapture(token: Token): Boolean =
        transitionFromToken(token, SessionState.PROCESSING_RECOGNIZING)

    @Synchronized
    fun onRecognitionReady(): Boolean = transitionFromCurrent(SessionState.AWAITING_CONFIRMATION)

    fun onRecognitionReady(token: Token): Boolean =
        transitionFromToken(token, SessionState.AWAITING_CONFIRMATION)

    @Synchronized
    fun onConfirmed(): Boolean = transitionFromCurrent(SessionState.PROCESSING_SYNTHESIZING)

    fun onConfirmed(token: Token): Boolean =
        transitionFromToken(token, SessionState.PROCESSING_SYNTHESIZING)

    @Synchronized
    fun onSynthesisReady(): Boolean = transitionFromCurrent(SessionState.PLAYING)

    fun onSynthesisReady(token: Token): Boolean = transitionFromToken(token, SessionState.PLAYING)

    @Synchronized
    fun onPlaybackFinished(): Boolean = transitionFromCurrent(SessionState.COMPLETED)

    fun onPlaybackFinished(token: Token): Boolean = transitionFromToken(token, SessionState.COMPLETED)

    @Synchronized
    fun cancel(): Boolean = transitionFromCurrent(SessionState.CANCELLED)

    fun cancel(token: Token): Boolean = transitionFromToken(token, SessionState.CANCELLED)

    @Synchronized
    fun fail(stage: ProcessingStage, code: String): Boolean {
        val token = currentToken() ?: return false
        return fail(token, stage, code)
    }

    @Synchronized
    fun fail(token: Token, stage: ProcessingStage, code: String): Boolean {
        if (!isCurrentLocked(token)) return false
        if (!transitionFromCurrent(SessionState.ERROR)) return false
        _events.value = SessionEvent.Error(token.sessionId, stage, code)
        return true
    }

    /** 丢弃不属于当前有效会话的迟到结果。 */
    @Synchronized
    fun isCurrent(sessionId: String): Boolean =
        currentValid && currentSessionId == sessionId

    /** 同时校验 ID 与 generation，取消、失败和完成都会使旧令牌失效。 */
    @Synchronized
    fun isCurrent(sessionId: String, generation: Long): Boolean =
        isCurrentLocked(Token(sessionId, generation))

    @Synchronized
    fun isCurrent(token: Token): Boolean = isCurrentLocked(token)

    /** 会话结束后清除当前会话标识，避免旧结果被误认为有效。 */
    @Synchronized
    fun finish() {
        currentValid = false
        currentSessionId = null
        _state.value = SessionState.IDLE
    }

    @Synchronized
    private fun transitionFromToken(token: Token, target: SessionState): Boolean {
        if (!isCurrentLocked(token)) return false
        return transitionFromCurrent(target)
    }

    @Synchronized
    private fun transitionFromCurrent(target: SessionState): Boolean {
        val id = currentSessionId ?: return false
        if (!currentValid || !isTransitionAllowed(_state.value, target)) return false
        transition(target, id)
        if (target == SessionState.COMPLETED ||
            target == SessionState.CANCELLED ||
            target == SessionState.ERROR
        ) {
            currentValid = false
        }
        return true
    }

    private fun isCurrentLocked(token: Token): Boolean =
        currentValid &&
            currentSessionId == token.sessionId &&
            currentGeneration == token.generation

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
