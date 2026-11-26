package com.voiceclear.app.session

/**
 * 会话级停止门闩。
 *
 * 同一会话只允许一个停止流程；切到新 generation 时立即获得独立门闩。
 * 旧会话的 finally 只能释放自己持有的门闩，不能清除新会话状态。
 */
internal class SessionOperationGate {
    private var owner: SessionController.Token? = null
    private var running: Boolean = false

    @Synchronized
    fun tryBegin(token: SessionController.Token): Boolean {
        if (owner != token) {
            owner = token
            running = false
        }
        if (running) return false
        running = true
        return true
    }

    @Synchronized
    fun end(token: SessionController.Token) {
        if (owner == token) running = false
    }
}
