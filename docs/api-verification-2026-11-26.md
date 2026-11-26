# Android API 验证表（2026-11-26 审查修复）

目标：`minSdk 26` / `targetSdk 36` / `compileSdk 36`，Kotlin/JVM 17。

本轮主流程没有引入新的 Android 平台 API。下表条目均已由当前项目源码、现有依赖声明或 Java 17 标准库验证；平台行为仍需真机 Gate 3 复核。

| API / 签名 | 用途 | 验证来源 |
|---|---|---|
| `androidx.lifecycle.ViewModel.onCleared()` | 统一释放 ASR/TTS/采集/焦点资源 | 项目已有 `lifecycle-runtime-ktx:2.8.7` 与 `AndroidViewModel` 用法；`app/build.gradle.kts` |
| `androidx.lifecycle.viewModelScope` / `CoroutineScope.launch` | ViewModel 生命周期内异步任务 | 项目已有 `MainViewModel.kt`、`MainActivity.kt` 用法及 coroutines 依赖 |
| `kotlinx.coroutines.withContext(Dispatchers.IO)` | 模型加载、识别、音频与 ZIP IO | 项目已有 `MainViewModel.kt` 用法；`kotlinx-coroutines-test:1.9.0` |
| `MutableStateFlow` / `StateFlow` | 生命周期安全 UI 持久状态 | 项目已有 StateFlow 用法；同一 coroutines 依赖提供 Flow API |
| `Channel.UNLIMITED` / `receiveAsFlow` | 旋转/后台无订阅者期间保留一次性事件 | coroutines 1.9.0 标准 API；事件在 ViewModel 销毁时关闭 |
| `Job.invokeOnCompletion` | native 识别真正退出后释放引擎 | coroutines 1.9.0 标准 API；避免取消与 native release 并发 |
| `androidx.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED)` | 仅生命周期内收集 UI 事件 | `MainActivity.kt` 基线已使用；`lifecycle-runtime-ktx:2.8.7` |
| `ActivityResultContracts.RequestPermission` | 麦克风权限结果 | `MainActivity.kt` 基线已使用；`activity-ktx:1.9.3` |
| `ActivityResultContracts.CreateDocument("application/zip")` | SAF 诊断 ZIP 导出 | `MainActivity.kt` 基线已使用；仅修正扩展名和线程 |
| `android.content.ContentResolver.openOutputStream(Uri)` | 写入用户选择的 SAF 目标 | `MainActivity.kt` 基线已使用，minSdk 1 |
| `java.lang.Runtime.totalMemory/freeMemory/maxMemory` | 明确命名 Java 堆快照 | Java 17 标准库；不再命名为 native 峰值或系统可用内存 |
| `android.annotation.SuppressLint("MissingPermission")` | 告知 Lint 工厂 lambda 的调用点已受权限保护 | Android 标准注解；仅限 AudioCapture 工厂，调用点仍有显式检查和 SecurityException 处理 |
| `java.util.concurrent.atomic.AtomicBoolean` | 每会话停止与指标幂等 | Java 17 标准库；项目基线已使用 |
| `kotlinx.coroutines.sync.Mutex.withLock` | 引擎加载互斥去重 | 与项目现有 coroutines 1.9.0 同源 |

## AudioTrack 排空语义复核

官方 [AudioTrack.stop()](https://developer.android.com/reference/android/media/AudioTrack#stop()) 说明：MODE_STREAM 在最后写入的缓冲区播放完后停止；立即中断使用 pause()+flush()。因此正常完成路径不增加时长猜测或 sleep，保持 stop→release；中断路径才 pause→flush→stop→release。

## Gate 3 边界

静态检查、Robolectric 和完整 APK 构建不能证明真实麦克风、AudioTrack、系统 TTS、音频焦点、SAF 或 native 模型在设备上的行为。无连接设备时不得把本轮任务标为真机验收完成。
