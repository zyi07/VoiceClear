package com.voiceclear.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.voiceclear.app.session.DiagnosticLog

/**
 * 模型加载前的内存可行性评估。
 *
 * **为什么需要**（审计报告 4.1.2）：
 * SenseVoice Small int8 模型约 239 MB，由 sherpa-onnx 的 native 层分配。
 * native 分配**不受 Java 堆上限约束**，但受系统可用物理内存约束——
 * 在 2 GB 内存的低端机上，同时开着微信等应用就可能分配失败。
 * 失败形态是 native 层的 abort 或 std::bad_alloc，表现为**进程直接崩溃**，
 * 用户看到的是「闪退」，我们拿不到任何可读的错误信息。
 *
 * 因此加载前先估算：内存不够就**不尝试加载**，返回可读的错误给界面，
 * 而不是让进程崩在 native 层。
 *
 * **估算口径（保守）**：
 * - 模型常驻内存 ≈ 模型文件大小；
 * - 推理时需要额外的张量与工作区，按 [NATIVE_OVERHEAD_FACTOR] 放大；
 * - 再保留 [MIN_HEADROOM_MB] 的系统余量，避免把设备逼到 OOM 边缘。
 *
 * 这些系数是**工程估算**，不是实测结论。真机实测数据（见 docs/apk-testing-guide.md）
 * 应据实调整 [NATIVE_OVERHEAD_FACTOR]。
 */
object MemoryGuard {

    /**
     * native 层额外开销的估算倍数。
     *
     * 1.6 的来历：模型权重之外，ONNX Runtime 还要为每个算子分配
     * 输入/输出张量、注意力中间结果与内存池。对 int8 小模型，
     * 实测经验区间是 1.3-1.8 倍，取 1.6 居中偏保守。
     */
    const val NATIVE_OVERHEAD_FACTOR = 1.6

    /** 加载后要求保留的系统可用内存下限（MB）。 */
    const val MIN_HEADROOM_MB = 128L

    /** 判定结果。 */
    enum class Verdict {
        /** 内存充足，可以加载。 */
        OK,

        /** 偏紧：可以尝试，但要把失败处理准备好。 */
        TIGHT,

        /** 不足：不要尝试，直接给出可读错误。 */
        INSUFFICIENT,
    }

    /** 评估结果。 */
    data class Assessment(
        val verdict: Verdict,
        /** 模型文件大小（MB）。 */
        val modelMb: Long,
        /** 估算的加载所需内存（MB）。 */
        val requiredMb: Long,
        /** 当前系统可用内存（MB）。 */
        val availableMb: Long,
        /** 设备总内存（MB）。 */
        val totalRamMb: Long,
        /** Java 堆上限（MB）。 */
        val heapMaxMb: Long,
        /** 系统是否报告低内存状态。 */
        val lowMemoryDevice: Boolean,
    ) {
        fun describe(): String {
            return when (verdict) {
                Verdict.OK -> "内存充足（需要约 " + requiredMb + " MB，可用 " +
                    availableMb + " MB）"
                Verdict.TIGHT -> "内存偏紧（需要约 " + requiredMb + " MB，可用 " +
                    availableMb + " MB）"
                Verdict.INSUFFICIENT -> "内存不足（需要约 " + requiredMb + " MB，可用 " +
                    availableMb + " MB）"
            }
        }
    }

    /**
     * 评估能否加载指定大小的模型。
     *
     * @param modelBytes 模型文件字节数；传 0 或负数时按最保守的 0 处理
     */
    fun assess(context: Context, modelBytes: Long): Assessment {
        val modelMb = if (modelBytes > 0) modelBytes / (1024L * 1024L) else 0L
        val requiredMb = (modelMb * NATIVE_OVERHEAD_FACTOR).toLong() + MIN_HEADROOM_MB

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        var availableMb = 0L
        var totalRamMb = 0L
        var lowMemory = false
        if (activityManager != null) {
            val info = ActivityManager.MemoryInfo()
            runCatching { activityManager.getMemoryInfo(info) }
            availableMb = info.availMem / (1024L * 1024L)
            // totalMem 需要 API 16+；minSdk 26，可直接用
            totalRamMb = info.totalMem / (1024L * 1024L)
            lowMemory = info.lowMemory
        } else {
            // 拿不到系统内存信息时退回 Java 堆口径，宁可判紧也不要误判为充足
            val runtime = Runtime.getRuntime()
            availableMb =
                (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) /
                    (1024L * 1024L)
            lowMemory = true
        }

        val heapMaxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)

        val verdict = decide(availableMb = availableMb, requiredMb = requiredMb, lowMemory = lowMemory)

        val assessment = Assessment(
            verdict = verdict,
            modelMb = modelMb,
            requiredMb = requiredMb,
            availableMb = availableMb,
            totalRamMb = totalRamMb,
            heapMaxMb = heapMaxMb,
            lowMemoryDevice = lowMemory,
        )

        DiagnosticLog.i(
            "Memory",
            "加载前评估: " + verdict.name + ", " + assessment.describe() +
                ", 机型=" + Build.MANUFACTURER + " " + Build.MODEL +
                ", 总内存=" + totalRamMb + " MB, 堆上限=" + heapMaxMb + " MB" +
                ", 低内存状态=" + lowMemory,
        )
        return assessment
    }

    /**
     * 纯粹的判定规则，抽出来便于单元测试（不依赖 Context 与真实内存状态）。
     *
     * @param availableMb 系统可用内存
     * @param requiredMb 估算所需内存（已含安全垫）
     * @param lowMemory 系统是否已报告低内存
     */
    fun decide(availableMb: Long, requiredMb: Long, lowMemory: Boolean): Verdict {
        return when {
            // 可用内存连所需都不到：直接不尝试
            availableMb < requiredMb -> Verdict.INSUFFICIENT
            // 系统已报低内存，或余量不足一倍安全垫：可以试，但要兜住失败
            lowMemory || availableMb < requiredMb + MIN_HEADROOM_MB -> Verdict.TIGHT
            else -> Verdict.OK
        }
    }

    /**
     * 计算录音缓冲所需的 Java 堆字节数。
     *
     * 采集把整段音频留在内存里（ByteArrayOutputStream），
     * 因此 30 秒上限对应约 1 MB；乘以 2 是因为 toByteArray() 会再复制一份。
     */
    fun captureBufferBytes(
        maxDurationMs: Long = 30_000L,
        sampleRate: Int = 16_000,
        bytesPerSample: Int = 2,
    ): Long {
        val oneCopy = maxDurationMs * sampleRate * bytesPerSample / 1000L
        return oneCopy * 2
    }
}
