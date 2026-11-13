package com.voiceclear.app.session

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃捕获。
 *
 * 作用：把未捕获异常（闪退）的完整堆栈持久化到应用私有目录。
 * 没有它的话，闪退后日志缓冲会随进程一起消失，用户也无从反馈。
 *
 * 注意：**不收集任何个人数据**，只记录异常类型、消息与堆栈。
 */
class CrashHandler(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {

    companion object {
        const val CRASH_DIR_NAME = "crashes"
        const val MAX_CRASH_FILES = 10
    }

    private val previousHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private fun crashDir(): File = File(context.filesDir, CRASH_DIR_NAME)

    /** 安装处理器。 */
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        DiagnosticLog.i("Crash", "崩溃处理器已安装")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrash(thread, throwable)
        } catch (error: Throwable) {
            // 捕获失败不能阻断原始崩溃流程。
            Log.e(DiagnosticLog.TAG, "保存崩溃信息失败", error)
        } finally {
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        val dir = crashDir()
        if (!dir.exists() && !dir.mkdirs()) return

        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))

        val builder = StringBuilder()
        builder.append("time=").append(System.currentTimeMillis()).append(Char(10))
        builder.append("thread=").append(thread.name).append(Char(10))
        builder.append("device=").append(Build.MANUFACTURER).append(Char(10))
        builder.append("model=").append(Build.MODEL).append(Char(10))
        builder.append("android=").append(Build.VERSION.RELEASE).append(Char(10))
        builder.append("sdk=").append(Build.VERSION.SDK_INT).append(Char(10))
        builder.append(Char(10))
        builder.append(writer.toString())

        val file = File(dir, "crash-" + System.currentTimeMillis() + ".txt")
        file.writeText(builder.toString(), Charsets.UTF_8)

        DiagnosticLog.e("Crash", "检测到崩溃: " + throwable.javaClass.name + ", 已保存")

        pruneOldCrashes(dir)
    }

    /** 只保留最近的若干个崩溃文件。 */
    private fun pruneOldCrashes(dir: File) {
        val files = dir.listFiles()?.filter { it.name.startsWith("crash-") } ?: return
        if (files.size <= MAX_CRASH_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CRASH_FILES)
            .forEach { it.delete() }
    }

    /** 已保存的崩溃文件。 */
    fun crashFiles(): List<File> {
        return crashDir().listFiles()?.filter { it.name.startsWith("crash-") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 崩溃文件总内容。 */
    fun crashDump(): String {
        val builder = StringBuilder()
        crashFiles().forEach { file ->
            builder.append("===== ").append(file.name).append(" =====").append(Char(10))
            runCatching { builder.append(file.readText(Charsets.UTF_8)) }
            builder.append(Char(10))
        }
        return builder.toString()
    }

    /** 最近一次崩溃的时间戳（毫秒），无崩溃返回 0。 */
    fun lastCrashAt(): Long {
        return crashFiles().firstOrNull()?.lastModified() ?: 0L
    }
}

/**
 * 诊断快照：采集设备与运行时环境信息，用于排查问题。
 *
 * 全部为技术信息，不含个人数据、不含音频、不含完整文本。
 */
object DiagnosticSnapshot {

    fun collect(context: Context): String {
        val builder = StringBuilder()
        builder.append("===== 诊断快照 =====").append(Char(10))
        builder.append("time=").append(System.currentTimeMillis()).append(Char(10))
        builder.append("manufacturer=").append(Build.MANUFACTURER).append(Char(10))
        builder.append("model=").append(Build.MODEL).append(Char(10))
        builder.append("device=").append(Build.DEVICE).append(Char(10))
        builder.append("android=").append(Build.VERSION.RELEASE).append(Char(10))
        builder.append("sdk=").append(Build.VERSION.SDK_INT).append(Char(10))
        builder.append("abi=").append(Build.SUPPORTED_ABIS.joinToString(",")).append(Char(10))
        builder.append("available64Abi=")
            .append(Build.SUPPORTED_64_BIT_ABIS.joinToString(",")).append(Char(10))
        builder.append("package=").append(context.packageName).append(Char(10))

        builder.append(Char(10)).append("--- 运行时 ---").append(Char(10))
        builder.append("vmMaxHeapMb=")
            .append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(Char(10))
        builder.append("vmTotalHeapMb=")
            .append(Runtime.getRuntime().totalMemory() / 1024 / 1024).append(Char(10))
        builder.append("vmFreeHeapMb=")
            .append(Runtime.getRuntime().freeMemory() / 1024 / 1024).append(Char(10))
        builder.append("processors=").append(Runtime.getRuntime().availableProcessors())
            .append(Char(10))

        builder.append(Char(10)).append("--- 存储 ---").append(Char(10))
        runCatching {
            val files = context.filesDir.listFiles() ?: emptyArray()
            files.forEach { file ->
                builder.append("file=").append(file.name)
                    .append(", bytes=").append(file.length()).append(Char(10))
            }
        }
        runCatching {
            val external = context.getExternalFilesDir(null)
            builder.append("externalFilesDir=")
                .append(external?.absolutePath ?: "null").append(Char(10))
        }

        builder.append(Char(10)).append("--- 权限 ---").append(Char(10))
        builder.append("recordAudio=")
            .append(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
            .append(Char(10))

        builder.append(Char(10)).append("--- 音频设备 ---").append(Char(10))
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            builder.append("outputSampleRate=")
                .append(manager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
                .append(Char(10))
            builder.append("outputFramesPerBuffer=")
                .append(manager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
                .append(Char(10))
            builder.append("isBluetoothA2dpOn=").append(manager.isBluetoothA2dpOn).append(Char(10))
            builder.append("isSpeakerphoneOn=").append(manager.isSpeakerphoneOn).append(Char(10))
            builder.append("isWiredHeadsetOn=").append(manager.isWiredHeadsetOn).append(Char(10))
        }

        return builder.toString()
    }
}
