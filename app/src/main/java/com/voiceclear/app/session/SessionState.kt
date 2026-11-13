package com.voiceclear.app.session

/**
 * VoiceClear 会话状态机。
 *
 * 状态流转遵循技术指导文件 4.3 节：任何异步结果都必须携带 sessionId，
 * 过期会话的结果不得覆盖当前状态。
 */
enum class SessionState {
    IDLE,
    RECORDING,
    PROCESSING_RECOGNIZING,
    AWAITING_CONFIRMATION,
    PROCESSING_SYNTHESIZING,
    PLAYING,
    COMPLETED,
    CANCELLED,
    ERROR,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED

    val isBusy: Boolean
        get() = this == RECORDING ||
            this == PROCESSING_RECOGNIZING ||
            this == PROCESSING_SYNTHESIZING ||
            this == PLAYING

    /** 是否可以安全开始新的录音会话。 */
    val canStartNewSession: Boolean
        get() = this == IDLE ||
            this == COMPLETED ||
            this == CANCELLED ||
            this == ERROR
}
