package com.voiceclear.app.capture

import android.content.Context
import com.voiceclear.app.session.DiagnosticLog
import java.util.concurrent.Executor

/**
 * 受控的启动清理入口。测试可注入直接执行 Executor 和固定时钟，应用无需常驻线程池。
 * 即使被重复调度，CaptureStore 的共享锁也会与录音写入及其他清理互斥。
 */
class CaptureRetentionScheduler(
    context: Context,
    private val executor: Executor = Executor { task ->
        Thread(task, "VoiceClear-Retention").apply { isDaemon = true }.start()
    },
    private val policy: DataRetentionPolicy = DataRetentionPolicy(context),
) {
    fun enqueue() {
        executor.execute {
            runCatching {
                val result = policy.enforce()
                if (!result.disabled && result.deletedFiles > 0) {
                    DiagnosticLog.i("Retention", "启动清理: " + result.describe())
                }
            }.onFailure {
                DiagnosticLog.e("Retention", "启动清理失败: " + (it.message ?: it.javaClass.simpleName))
            }
        }
    }
}
