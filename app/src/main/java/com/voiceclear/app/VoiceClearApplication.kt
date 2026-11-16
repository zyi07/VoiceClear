package com.voiceclear.app

import android.app.Application
import android.os.SystemClock
import com.voiceclear.app.session.CrashHandler
import com.voiceclear.app.session.DiagnosticLog

/**
 * 应用入口。
 *
 * 承担两件事：
 * 1. **记录进程启动时刻**，用于测量冷启动耗时（从进程创建到首帧可交互）；
 * 2. **尽早安装崩溃处理器**——放在这里比放在 Activity 里更早，
 *    能捕获到 Activity 创建阶段发生的崩溃。
 */
class VoiceClearApplication : Application() {

    companion object {
        /** 进程创建时刻（elapsedRealtime 基准）。 */
        @Volatile
        var processStartAt: Long = SystemClock.elapsedRealtime()
            private set

        /**
         * 从进程创建到当前的耗时（毫秒）。
         */
        fun uptimeMs(): Long = SystemClock.elapsedRealtime() - processStartAt
    }

    override fun onCreate() {
        super.onCreate()
        processStartAt = SystemClock.elapsedRealtime()
        DiagnosticLog.i(
            "App",
            "进程启动, Android=" + android.os.Build.VERSION.RELEASE +
                ", 设备=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
        )
        // 尽早安装崩溃处理器。
        CrashHandler(this).install()

        // 数据保留策略在进程级执行一次（而非每次 Activity 重建都跑）：
        // 旋转屏幕会反复触发 Activity.onCreate，把清理放在那里既重复又在主线程做 IO。
        // 这里放到后台线程，避免拖慢启动。
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            runCatching {
                val result = com.voiceclear.app.capture.DataRetentionPolicy(this).enforce()
                if (!result.disabled && result.deletedFiles > 0) {
                    DiagnosticLog.i("Retention", "启动清理: " + result.describe())
                }
            }
        }
    }
}
