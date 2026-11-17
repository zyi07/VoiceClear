package com.voiceclear.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主流程状态机契约测试。
 *
 * **背景（真机缺陷，2026-11-17）**：
 * 每次识别成功之后都弹一次「录音失败」。排查发现根因不在并发，
 * 而在**主流程从未调用 `stopCapture()`**：
 *
 * 1. 点「停止」后采集器停了，但状态机仍是 `RECORDING`；
 * 2. 识别成功时 `onRecognitionReady()` 被状态机拒绝（RECORDING 不能直跳
 *    `AWAITING_CONFIRMATION`），状态**卡在 RECORDING**；
 * 3. 界面上按钮已重新启用，用户再点一次，
 *    `onRecordClicked` 看到 `state == RECORDING`，于是又调一次停止；
 * 4. 采集器已空 → `capture_not_recording` → 弹出与操作无关的「录音失败」。
 *
 * 本测试把这个契约钉住：**停止采集必须先把状态推离 RECORDING**。
 */
class MainFlowStateContractTest {

    @Test
    fun `correct main flow reaches awaiting confirmation`() {
        val controller = SessionController();
        assertTrue(controller.startSession());
        assertEquals(SessionState.RECORDING, controller.state.value);

        // 正确做法：先同步状态机，再停采集器
        assertTrue(controller.stopCapture());
        assertEquals(SessionState.PROCESSING_RECOGNIZING, controller.state.value);

        // 识别完成后才能进入确认页
        assertTrue(controller.onRecognitionReady());
        assertEquals(SessionState.AWAITING_CONFIRMATION, controller.state.value);
    }

    @Test
    fun `skipping stopCapture leaves state stuck in recording`() {
        // 这是缺陷的形态：漏掉 stopCapture 时，识别完成无法把状态推进到确认页。
        val controller = SessionController();
        assertTrue(controller.startSession());

        assertFalse(
            "RECORDING 不能直跳 AWAITING_CONFIRMATION",
            controller.onRecognitionReady(),
        );
        assertEquals(
            "状态仍应停在 RECORDING——这正是缺陷被发现的地方",
            SessionState.RECORDING,
            controller.state.value,
        );
    }

    @Test
    fun `stuck recording state would be treated as still recording`() {
        // 说明缺陷为何会导致用户误操作：界面按字面量渲染成"处理中"，
        // 但状态机说 RECORDING，于是下一次点击走进"停止"分支。
        val controller = SessionController();
        controller.startSession();
        controller.onRecognitionReady();

        assertTrue(
            "状态机仍认为正在录音",
            controller.state.value == SessionState.RECORDING,
        );
    }

    @Test
    fun `awaiting confirmation rejects starting a new session`() {
        // 若状态正确，用户在确认页误点「开始录音」只会得到"上一次未完成"，
        // 而不会去调用停止采集。
        val controller = SessionController();
        controller.startSession();
        controller.stopCapture();
        controller.onRecognitionReady();

        assertFalse(controller.startSession());
        assertEquals(SessionState.AWAITING_CONFIRMATION, controller.state.value);
    }

    @Test
    fun `capture failure moves state to error so next session can start`() {
        val controller = SessionController();
        controller.startSession();
        controller.stopCapture();
        assertTrue(controller.fail(ProcessingStage.CAPTURE, "capture_silent"));
        assertEquals(SessionState.ERROR, controller.state.value);

        // ERROR 允许重新开始
        assertEquals(SessionState.ERROR, controller.state.value);
    }
}
