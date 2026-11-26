# VoiceClear 全量代码审查报告

> 审查日期：2026-11-26
> 审查对象：当前工作区（不是只看最近一次提交）
> 版本：versionCode 13 / versionName 0.11.2
> 基线提交：`28b9489 fix: sync UI with session state machine using repeatOnLifecycle`，另有 1 处未提交的 `MainActivity.kt` import 修复
> 审查方式：逐文件静态审查 + 现有测试/构建验证 + 针对关键问题的最小复现

---

## 1. 执行摘要

当前项目已经具备**可构建、可安装、真实端侧 ASR 接入、主流程原型、采集原型和诊断工具链**，但仍处于 **P1/P2 工程原型阶段**，尚未达到“可交付主流程”或“可信研究基线”的状态。

本次最重要的结论有四点：

1. **构建是绿的，不等于行为正确。** 首次组合任务中的 Android 测试为 UP-TO-DATE；随后强制重跑 180 项测试有 1 项失败，Python 15 项测试通过，Lint 为 0 Error / 21 Warning，Debug/Release 构建及两项构建门禁通过。测试仍没有覆盖多条真实失败路径。
2. **主流程存在停止交付级正确性问题。** 取消后迟到结果仍可能进入 UI，跨会话停止会被全局锁拒绝，PCM 播放停止方法实际为空操作，录音自动停止/读取失败未释放采集器，模型及 TTS 生命周期也有泄漏与重复加载。
3. **研究采集链路当前不可用。** 默认授权、撤回不生效、转写重复、音频无边界拼接、元数据硬编码，且导入器在新目标目录崩溃；这些会直接破坏授权真实性和数据可信度。
4. **尚无可信 ASR 质量基线。** 目标人群真实数据仍缺失，而现有校验/评测会纳入 unconfirmed、跳过缺失识别结果且不检查音频存在性，不能据此形成可信指标。

### 当前阶段判断

| 维度 | 结论 |
|---|---|
| 工程可构建性 | **通过（Debug/Release 见验证结果）** |
| 主流程可用性 | **未达标，需先修取消/停止/生命周期问题** |
| 端侧真实 ASR 接入 | **已接入 SenseVoice，但模型下载与加载尚不稳健** |
| 目标人群质量基线 | **未建立，当前数据链路不能支撑可信结论** |
| 研究采集合规性 | **未达标，默认授权与撤回失效是硬阻断** |
| 真机 Gate 3 | **本次未执行：无连接设备、无 `/dev/kvm`** |

---

## 2. 审查范围

已逐文件审查：

- 主流程：`MainActivity.kt`、`MainViewModel.kt`、`ModelSetupActivity.kt`、`VoiceClearApplication.kt`
- `engine/` 下全部 17 个 Kotlin 文件
- `session/` 下全部 11 个 Kotlin 文件
- `capture/` 下全部 8 个 Kotlin 文件
- 27 个 Android 测试类
- 6 个 Python 工具：`validate_dataset.py`、`eval_asr.py`、`import_session.py`、`test_tools.py`、`check_asr_config.py`、`check_permissions.py`
- 关键布局、清单、资源与构建配置，以及 README、CHANGELOG、DEVLOG、AUDIT_REPORT 和采集协议/Schema/测试手册

主 Kotlin 源码约 6294 行；连 Python 工具共约 8145 行（不含 build、node_modules 和二进制产物）。

### 工作区状态说明

- 分支：`main`，比 `origin/main` 超前 1 个提交。
- 未提交修改：[MainActivity.kt](app/src/main/java/com/voiceclear/app/MainActivity.kt) 增加 `kotlinx.coroutines.launch` import。
- 未跟踪内容：两份生成的 docx、`scripts/`、`package.json`、`package-lock.json`、`node_modules/`。
- 本报告将未提交源码一并纳入审查，因此结论对应“当前工作树”，不完全等同于提交 `28b9489`。

---

## 3. 验证结果

### 3.1 构建、检查与测试执行结果

| 项目 | 命令/证据 | 结果 |
|---|---|---|
| Android 单测（强制重跑） | `./gradlew :app:testDebugUnitTest --rerun-tasks` | **180 项完成，1 项失败**：`DataRetentionPolicyTest > expired audio is deleted` |
| Python 工具测试 | `python3 -m unittest tools.test_tools -v` | 15/15 通过 |
| 静态检查 | `./gradlew :app:lintDebug` | 0 Error，21 Warning |
| Debug 构建 | `./gradlew :app:assembleDebug` | 通过 |
| ASR 配置门禁 | `./gradlew :app:checkAsrConfig` | 5 项参数一致 |
| APK 权限门禁 | `./gradlew :app:checkApkPermissions` | INTERNET / ACCESS_NETWORK_STATE / RECORD_AUDIO 齐备 |
| Release 构建 | `./gradlew :app:assembleRelease` | 通过，产物为 [app-release-unsigned.apk](app/build/outputs/apk/release/app-release-unsigned.apk)（约 58.5 MB，未签名）；另有 3 条弃用 API 编译警告及 native 库无法 strip 提示 |

Lint 报告：[lint-results-debug.html](app/build/reports/lint-results-debug.html)。

### 3.2 本次针对性复现

1. `import_session.py` 使用不存在的新目标目录：
   - 结果：退出码 1，`FileNotFoundError: .../new-dest/manifest.json`。
   - 根因：manifest 未复制便先读取目标 manifest。
2. 评测输入 `referenceSource = unconfirmed`：
   - 结果：仍被纳入评测，且否定词变化被计为关键错误。
3. 参考文本仅为 `"。"`，识别文本为 `"错误"`：
   - 结果：CER 被错误地输出为 0.0。
4. ref 有片段、hyp 为空：
   - 结果：0 个片段进入报告，主程序仍可返回成功，而不是把缺失识别结果作为失败或覆盖率不足处理。

### 3.3 未完成验证（诚实边界）

- 本次没有连接 Android 真机，`adb devices` 为空；`/dev/kvm` 不存在。
- 未做真实麦克风采集、PCM/TTS 发声、旋转、后台、音频焦点、蓝牙、SAF 写入及温升/耗电验证。
- Robolectric 通过不等于 Gate 3 真机验证通过。下文所有“触发路径”均由代码推导，除非明确写“本次复现”，不声称已在设备上实测。

---

## 4. 停止交付级问题（P0）

### P0-1：取消不清除会话令牌，迟到结果仍被当成当前结果；跨会话停止又会被全局锁拒绝

**证据：**

- [SessionController.kt:46-62](app/src/main/java/com/voiceclear/app/session/SessionController.kt#L46-L62)：`cancel()` 只切状态，`isCurrent()` 只比较 sessionId；`finish()` 清 ID，但主流程未调用。
- [MainViewModel.kt:336-361](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L336-L361)：识别后只检查 ID，忽略 `onRecognitionReady()` 返回 false，仍写结果、加样本并回调成功。
- [MainViewModel.kt:242-245](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L242-L245)：整个 ViewModel 共用一个 `stopInFlight`，重复停止直接返回且无回调。
- [MainActivity.kt:306-315](app/src/main/java/com/voiceclear/app/MainActivity.kt#L306-L315)：按钮只在回调中恢复。

**触发与影响：**

- A 识别中点击取消，A 的结果仍可把 UI 带回确认页；真实状态仍可能为 CANCELLED。
- A 识别未结束时开始 B，B 的停止会被 A 的锁拒绝，B 继续录音且按钮可能一直禁用。
- 合成/播放路径也存在同类问题，可把取消指标覆盖为成功指标。

**修复方向：** 会话使用单调递增 generation 或明确失效令牌；取消、失败、完成均使旧任务失效。每个 session 独立持有 Job/停止令牌，检查所有状态迁移返回值；按钮可用性只由真实状态流决定。

---

### P0-2：PCM 播放的 `stop()` 永远为空操作；TTS 焦点与取消也未生效

**证据：**

- [AudioPlayback.kt:22-25](app/src/main/java/com/voiceclear/app/engine/AudioPlayback.kt#L22-L25) 与 [AudioPlayback.kt:38-80](app/src/main/java/com/voiceclear/app/engine/AudioPlayback.kt#L38-L80)：实际创建的是局部 `audioTrack`，从未赋给字段 `track`。
- [AudioPlayback.kt:83-89](app/src/main/java/com/voiceclear/app/engine/AudioPlayback.kt#L83-L89)：`track ?: return`，因此停止永远直接返回。
- [AudioPlayback.kt:62-74](app/src/main/java/com/voiceclear/app/engine/AudioPlayback.kt#L62-L74)：`write()` 返回 0/负数时只 break，仍返回 true。
- [AndroidTtsEngine.kt:141-151](app/src/main/java/com/voiceclear/app/engine/AndroidTtsEngine.kt#L141-L151)：系统 TTS 在 `synthesize()` 内已经发声。
- [MainViewModel.kt:450-478](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L450-L478)：却在 `synthesize()` 返回后才申请焦点，焦点丢失也没有停止系统 TTS。
- [MainViewModel.kt:501-508](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L501-L508)：取消没有调用 `AndroidTtsEngine.stop()`。

**影响：** 旧音频可能与录音、新会话、来电或其他媒体叠加；播放被截断也可能记为成功。

**修复方向：** 共享并同步当前 AudioTrack，stop 能中断阻塞 write；只有完整写完才返回成功。TTS 在 speak 前取得焦点，焦点丢失/取消同时停止 PCM 与系统 TTS。

---

### P0-3：录音自动停止或读取失败不释放 AudioRecord，且可能把截断音频当成功

**证据：**

- [AudioCapture.kt:274-299](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L274-L299)：30 秒上限和 read 错误仅退出循环，无 finally 停止/释放，也不记录失败。
- [AudioCapture.kt:97-108](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L97-L108)：`current` 仍非空，`isRecording()` 仍 true，后续 start 返回 ERR_ILLEGAL_STATE。
- [AudioCapture.kt:213-253](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L213-L253)：join 超时不检查存活便 release 并复制缓冲区，读线程仍可能继续写；最终仍返回 Success。

**影响：** 麦克风与隐私资源可能持续占用，UI 与采集实况不一致，读取失败产生不完整 PCM 也会送识别。

**修复方向：** 为每次 Session 增加 Recording/Completed/Failed/Stopped 状态和错误字段；所有退出路径统一幂等清理；读取失败必须返回 Failure，join 超时不得照常返回成功。

---

### P0-4：模型与 TTS 生命周期所有权错误，旋转可重复加载并泄漏 native 资源

**证据：**

- [MainActivity.kt:143-150](app/src/main/java/com/voiceclear/app/MainActivity.kt#L143-L150)：每次 Activity.onCreate 都准备引擎；onResume 也可重试。
- [MainViewModel.kt:144-170](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L144-L170)：直接创建新引擎并在调用线程 load，无并发去重、无先释放旧实例。
- [ModelSetupActivity.kt:128-160](app/src/main/java/com/voiceclear/app/ModelSetupActivity.kt#L128-L160)：下载成功后临时创建并加载一个引擎，仅返回 Boolean，没有 release。
- [SenseVoiceRecognitionEngine.kt:244-248](app/src/main/java/com/voiceclear/app/engine/SenseVoiceRecognitionEngine.kt#L244-L248)：已有 release 入口但主流程未使用。
- [MainActivity.kt:505-508](app/src/main/java/com/voiceclear/app/MainActivity.kt#L505-L508)：Activity 每次 onDestroy 都 shutdown ViewModel 持有的 TTS，包括旋转时；ViewModel 没有 onCleared 统一清理。

**影响：** 旋转可打断正在使用的 TTS；native recognizer 可重复分配大额内存；真正退出又没有完整释放。主线程模型加载还会带来启动卡顿/ANR 风险。

**修复方向：** 引擎所有权集中在应用级/ViewModel；加载放到 IO 并做互斥去重；onCleared 统一停止采集、PCM/TTS、释放 ASR 和焦点；ModelSetup 临时引擎在 finally 释放。

---

### P0-5：模型完整性校验失败仍放行，损坏文件会被永久视为“已就绪”

**证据：**

- [ModelDownloadManager.kt:87-114](app/src/main/java/com/voiceclear/app/engine/ModelDownloadManager.kt#L87-L114)：isReady 只看两个文件 exists，download 随即跳过。
- [ModelDownloadManager.kt:202-241](app/src/main/java/com/voiceclear/app/engine/ModelDownloadManager.kt#L202-L241)：模型 SHA 不符但大小一致只警告；tokens SHA 不符也无条件放行。
- [ModelDownloadManagerTest.kt:87-99](app/src/test/java/com/voiceclear/app/engine/ModelDownloadManagerTest.kt#L87-L99)：测试甚至用两个 1 KB 文件断言已就绪并跳过下载。

**影响：** 损坏/被替换的模型和词表可进入 native 加载并持续失败，且无法通过正常下载自愈。“同大小放行”也使 SHA-256 失去防篡改意义。

**修复方向：** 所有哈希不符均失败关闭；完成标记和目录原子切换，启动时复验大小和 SHA；上游更新通过更新可信哈希发布，不接受未知内容。

---

### P0-6：权限回调绕过模型就绪检查，可在真实 UI 中产出占位识别结果

**证据：**

- [MainActivity.kt:85-92](app/src/main/java/com/voiceclear/app/MainActivity.kt#L85-L92)：权限授予后直接调用 `startRecording()`，未检查 `senseVoiceEngine`。
- [MainActivity.kt:254-278](app/src/main/java/com/voiceclear/app/MainActivity.kt#L254-L278)：常规点击路径才会检查模型并跳转下载。
- [MainViewModel.kt:83-88](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L83-L88)：模型未就绪时 activeEngine 是 FakeRecognitionEngine。
- [MainViewModel.kt:200-225](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L200-L225)：startRecording 不再验证真实引擎是否可用。
- [FakeSpeechEngines.kt:10-26](app/src/main/java/com/voiceclear/app/engine/FakeSpeechEngines.kt#L10-L26)：默认还返回固定文本和 0.82 置信度，与其 providesConfidence=false 声明冲突。

**触发：** 首次安装且模型未下载、麦克风权限未授予时，点击录音并允许权限，会绕过模型页直接录音并可能得到固定占位文本。

**修复方向：** 把模型就绪校验放在 ViewModel 的 startRecording 内，所有入口共用；Fake 默认 confidence 改为 null，占位引擎不得用于真实主流程。

---

### P0-7：授权被默认写成同意，撤回在校验和评测中完全不生效

**证据：**

- [CaptureActivity.kt:247-258](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L247-L258)：每条片段固定 `ConsentFlags.ALL`。
- [CaptureActivity.kt:343-359](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L343-L359)：导出时固定把 research/train/anonymous 写 true，signedAt 是导出当天。
- [CaptureSessionController.kt:73-125](app/src/main/java/com/voiceclear/app/capture/CaptureSessionController.kt#L73-L125)：没有拒绝空授权或未授权用途的实现。
- [validate_dataset.py:187-253](tools/validate_dataset.py#L187-L253) 与 [validate_dataset.py:525-540](tools/validate_dataset.py#L525-L540)：只看 grants，不看 withdrawnAt。
- [eval_asr.py:311-371](tools/eval_asr.py#L311-L371)：完全不读同意记录。
- [consent-and-notice-draft.md:109-123](docs/consent-and-notice-draft.md#L109-L123)：草稿要求逐项独立选择、默认不同意，代码与之相反。

**影响：** 授权事实被伪造，已撤回数据仍可导出、校验和评测。此问题在真实研究采集前必须阻断，不是一般 UI 缺陷。

**修复方向：** 默认全部拒绝；录音前验证真实授权，导出不得重写授权事实；建立撤回状态机并贯穿 App、导出、入库、校验、评测和 splits。

---

### P0-8：转写重复追加、朗读元数据延迟落盘，采集数据会膨胀或丢失关联

**证据：**

- [CaptureActivity.kt:277-305](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L277-L305)：每次确认/跳过/导出都遍历全部历史片段。
- [CaptureStore.kt:59-64](app/src/main/java/com/voiceclear/app/capture/CaptureStore.kt#L59-L64)：appendTranscript 永远追加，不去重、不更新。
- [CaptureActivity.kt:247-274](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L247-L274)：朗读音频立即写入，但 transcript 直到导出才写；旋转/进程死亡会留下孤儿 PCM。
- [CaptureActivity.kt:65-76](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L65-L76)：重建时新 controller/sessionId，未恢复内存状态，也未取消旧 AudioRecord。

**影响：** 同一片段可写多次，片段数、时长、CER 样本量和分层统计全部失真；朗读片段可能只有音频没有元数据。

**修复方向：** 按 segmentId 幂等写入；确认意图更新原记录；音频和元数据原子落盘并支持恢复；Activity 销毁前显式取消采集。

---

### P0-9：音频导出不可用于逐片段评测，元数据也无法形成完整可交付数据集

**证据：**

- [AudioExportActivity.kt:80-104](app/src/main/java/com/voiceclear/app/capture/AudioExportActivity.kt#L80-L104)：所有 PCM 无边界拼成一个文件，无索引、长度表、顺序保证和 segment 对照。
- [CaptureSessionController.kt:98-122](app/src/main/java/com/voiceclear/app/capture/CaptureSessionController.kt#L98-L122)：audioFileName 永远为空。
- [CaptureStore.kt:51-56](app/src/main/java/com/voiceclear/app/capture/CaptureStore.kt#L51-L56)：注释承诺原始+16k 两份，实际只有一个平铺 PCM，没有 Schema 的 raw/16k 结构。
- [CaptureStore.kt:96-117](app/src/main/java/com/voiceclear/app/capture/CaptureStore.kt#L96-L117)：metadata 只写应用私有 dataset/export，没有 SAF 分享；不复制 prompts，旧 export 目录也不清理。
- [import_session.py:35-36](tools/import_session.py#L35-L36)：入库同样不包含 prompts。

**影响：** 无法知道每段音频的说话人、会话、文本和边界，无法支撑逐片段 ASR 评测；现有“导出成功”提示并不代表可交付。

**修复方向：** 用 ZIP/目录保留每段独立音频及 manifest/transcripts/prompts，记录相对路径、采样率、编码和字节数；提供完整数据集包与纯元数据包两种明确交付物。

---

### P0-10：导入器存在确定性崩溃、 dry-run 假成功和源目录被删除风险

**证据：**

- [import_session.py:94-113](tools/import_session.py#L94-L113)：COPY_DIRS 不含 manifest，却读取 `dest/manifest.json`。
- 同段在复制和校验前删除目标目录；`--source == --dest` 时源即被删。
- [import_session.py:89-92](tools/import_session.py#L89-L92)：dry-run 直接返回 0，不运行校验。
- [import_session.py:119-129](tools/import_session.py#L119-L129)：正式导入先覆盖旧数据再校验。

**本次复现：** 新目标目录退出码 1，`FileNotFoundError`。

**影响：** 新目录无法正常入库；无效数据可能被误判可导入；源目標相同可造成真实数据丢失。

**修复方向：** 直接写入内存 manifest；拒绝相同/包含路径；dry-run 完整只读校验；正式流程先校验源，再 staging 复制、复验并原子发布。

---

### P0-11：校验/评测口径不能形成可信 ASR 基线

**证据：**

- [eval_asr.py:311-371](tools/eval_asr.py#L311-L371)：不过滤 unconfirmed、empty_capture；缺 hyp 直接 continue。
- [eval_asr.py:275-305](tools/eval_asr.py#L275-L305)：重复 segmentId 被字典覆盖。
- [eval_asr.py:605-619](tools/eval_asr.py#L605-L619)：没有可评测片段仍可返回 0。
- [eval_asr.py:339-345](tools/eval_asr.py#L339-L345)：规范化后空 ref 会得到虚假 CER=0。
- [validate_dataset.py:421-560](tools/validate_dataset.py#L421-L560)：不检查音频存在、实际时长、重复 ID、manifest 交叉一致性；empty_capture 还会跳过后续授权/划分检查。
- [CaptureJson.kt:72-96](app/src/main/java/com/voiceclear/app/capture/CaptureJson.kt#L72-L96)：App 不输出 severityLevel/category，真实分层会落为 unknown。

**影响：** 不可信意图、空采集、重复导出、漏识别和缺失音频均可能进入或绕过指标，无法支撑“可信基线”。

**修复方向：** 严格过滤未授权/未确认/空采集；重复/缺失/多余 ID 直接失败；报告 matched/missing/extra 和覆盖率；校验音频存在、唯一性、格式、时长；按 speaker/prompt 关联分层元数据。

---

## 5. 高优先级问题（P1）

### P1-1：ASR 失败 warning 被当作成功，异常时 UI 可能永久等待

- [SenseVoiceRecognitionEngine.kt:174-195](app/src/main/java/com/voiceclear/app/engine/SenseVoiceRecognitionEngine.kt#L174-L195) 与 [SenseVoiceRecognitionEngine.kt:229-240](app/src/main/java/com/voiceclear/app/engine/SenseVoiceRecognitionEngine.kt#L229-L240)：未加载、空音频、解码失败通过 warnings 返回。
- [MainViewModel.kt:319-361](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L319-L361)：不检查 warnings，仍进入确认页并添加普通基准。
- [MainViewModel.kt:319-327](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L319-L327)：识别还直接在 ViewModel 主协程调用，没有 IO 调度，慢设备会阻塞 UI。
- 其他错误映射也失真：未加载模型返回 load_failed 而非 missing；采样率错误被 pcm16ToFloat 转为空数组后报 empty_audio，而不是已有的 `ASR_SAMPLE_RATE_MISMATCH`。

**建议：** warnings 中的失败码统一映射到 RecognitionOutcome.Error；识别调度到 IO；补齐模型、采样率、声道、编码的独立错误码。

### P1-2：Activity 回调捕获旧视图，旋转后识别文本/界面状态可能丢失

- [MainActivity.kt:309-350](app/src/main/java/com/voiceclear/app/MainActivity.kt#L309-L350) 和 [MainActivity.kt:363-371](app/src/main/java/com/voiceclear/app/MainActivity.kt#L363-L371)：长寿命 ViewModel 持有捕获 Activity 的回调。
- [MainActivity.kt:190-195](app/src/main/java/com/voiceclear/app/MainActivity.kt#L190-L195)：新 Activity 只收集 SessionState，不按 lastRecognition 恢复文本和置信度。

**建议：** ViewModel 发布完整不可变 UI 状态和一次性事件；Activity 仅在生命周期内收集，不向 ViewModel 传捕获视图的回调。

### P1-3：开始新会话前就清空上一轮音频与识别结果

- [MainViewModel.kt:200-209](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L200-L209)：先 stop、清空 lastCapturedSegment/lastRecognition，再检查 startSession。
- [MainActivity.kt:472-473](app/src/main/java/com/voiceclear/app/MainActivity.kt#L472-L473)：确认页不属于 busy，录音按钮仍可点击。

**影响：** 确认页误点后提示“上次未完成”，但原始音频已被清空。

### P1-4：采集控制器的失败路径可让下一次点击直接崩溃

- [CaptureActivity.kt:206-217](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L206-L217)：先推进 controller 为 RECORDING，再启动 AudioRecord，失败不回滚。
- [CaptureSessionController.kt:57-63](app/src/main/java/com/voiceclear/app/capture/CaptureSessionController.kt#L57-L63)：下一次点击会触发 require 非法状态异常。
- [CaptureActivity.kt:87-100](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L87-L100)：录音中仍可换提示/清空；可造成错标、孤儿音频或状态机崩溃。

### P1-5：提示类别显示项与实际打开项不一致

- [CaptureActivity.kt:140-150](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L140-L150)：标签按 grouped.entries 顺序，点击却用 grouped.keys.sorted() 取类别。当前 assets 非字典序，确实会打开错误类别。

### P1-6：TTS 回调不校验 utteranceId，初始化失败被缓存且错误码丢失

- [AndroidTtsEngine.kt:120-155](app/src/main/java/com/voiceclear/app/engine/AndroidTtsEngine.kt#L120-L155)：每次覆盖全局 listener，onDone/onError 不检查 id；stop/release 不结束等待者。
- [AndroidTtsEngine.kt:56-110](app/src/main/java/com/voiceclear/app/engine/AndroidTtsEngine.kt#L56-L110)：initDeferred 失败后仍缓存 false/挂起，后续无法重试；语言不可用和初始化超时最终都折叠成 ERR_NOT_READY。

### P1-7：音频焦点状态失效后仍谎报持有焦点

- [AudioFocusManager.kt:35-49](app/src/main/java/com/voiceclear/app/engine/AudioFocusManager.kt#L35-L49)：AUDIOFOCUS_LOSS 后不清 request，requestFocus 见非 null 就 true。
- [AudioFocusManager.kt:68-75](app/src/main/java/com/voiceclear/app/engine/AudioFocusManager.kt#L68-L75)：hasFocus 实际只是“存在请求对象”，不是当前持有状态。

### P1-8：下载重试对 HTTP 错误和大小不足无效，下载任务可并发写同一临时文件

- [ModelDownloadManager.kt:292-375](app/src/main/java/com/voiceclear/app/engine/ModelDownloadManager.kt#L292-L375)：HTTP 状态不接受及大小不足都直接 return false，不会进入 repeat 的第二次尝试。
- [ModelSetupActivity.kt:92-126](app/src/main/java/com/voiceclear/app/ModelSetupActivity.kt#L92-L126)：旋转后旧阻塞下载仍可运行，新页面可再次下载；共享 .part / sensevoice.tar.bz2。

**建议：** 应用级单例互斥、独立临时目录、协作取消；明确可重试状态码、退避和 416 处理。

### P1-9：指标回填可串会话，失败会话又大量缺失

- [BenchmarkAggregator.kt:131-150](app/src/main/java/com/voiceclear/app/session/BenchmarkAggregator.kt#L131-L150)：amendLast 没有 sessionId。
- [MainViewModel.kt:375-399](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L375-L399)：任何 recordMetrics 都无条件 amendLast，取消一个尚未产生识别样本的新会话也会改旧样本。
- [MainViewModel.kt:263-317](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L263-L317) 和 [MainActivity.kt:316-328](app/src/main/java/com/voiceclear/app/MainActivity.kt#L316-L328)：空采集、静音、采集失败不写指标，导出的成功率系统性偏高。

**建议：** 样本携带 sessionId，使用 amend(sessionId, ...)；所有 terminal 路径统一幂等 finalizeSession。

### P1-10：性能字段名不副实，冷启动会被旋转覆盖

- [PerformanceBenchmark.kt:92-93](app/src/main/java/com/voiceclear/app/session/PerformanceBenchmark.kt#L92-L93)：peakMemoryMb 是最大堆上限，availableMemoryMb 是已分配堆总量，不是峰值/可用。
- [MainActivity.kt:148](app/src/main/java/com/voiceclear/app/MainActivity.kt#L148)：每次 Activity.onCreate 都覆盖 coldStartMs。
- [PerformanceBenchmark.kt:17](app/src/main/java/com/voiceclear/app/session/PerformanceBenchmark.kt#L17)：RTF 注释写反，代码本身为识别耗时/音频时长，公式正确。

### P1-11：采集 sessionId 被丢弃，播放失败也被静默当成功

- [AudioCapture.kt:105](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L105) 接收 sessionId 但不保存；[AudioCapture.kt:245-253](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L245-L253) 永远写空 ID。
- [MainViewModel.kt:473-498](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L473-498)：AudioPlayback.play 的 Boolean 返回值被忽略；原声播放可并发、与合成互相干扰。

### P1-12：保留策略删除音频后，统计/导出/校验仍把片段视为有效

- [DataRetentionPolicy.kt:51-74](app/src/main/java/com/voiceclear/app/capture/DataRetentionPolicy.kt#L51-L74)：只删 PCM，不更新 transcript/manifest/有效状态。
- [CaptureStore.kt:135-151](app/src/main/java/com/voiceclear/app/capture/CaptureStore.kt#L135-L151)：统计完全基于 transcripts。
- [AudioExportActivity.kt:58-69](app/src/main/java/com/voiceclear/app/capture/AudioExportActivity.kt#L58-69)：可显示导出成功，但实际可能 0 文件。
- [validate_dataset.py:421-560](tools/validate_dataset.py#L421-560)：完全不检查音频是否存在。

### P1-13：诊断 ZIP 文件名错误且导出在主线程执行

- [MainActivity.kt:98-100](app/src/main/java/com/voiceclear/app/MainActivity.kt#L98-L100) 内容是 ZIP；[MainActivity.kt:177-180](app/src/main/java/com/voiceclear/app/MainActivity.kt#L177-L180) 却命名 `.txt`。
- [MainActivity.kt:104-129](app/src/main/java/com/voiceclear/app/MainActivity.kt#L104-L129)：buildArchive 和 SAF 写入同步执行；已有 [DiagnosticExporter.kt:156-159](app/src/main/java/com/voiceclear/app/session/DiagnosticExporter.kt#L156-L159) 的正确命名函数未使用。

### P1-14：录音中切换音源被拒绝，但新设置已持久化

- [MainActivity.kt:396-403](app/src/main/java/com/voiceclear/app/MainActivity.kt#L396-L403)：先写设置再 apply。
- [MainViewModel.kt:520-526](app/src/main/java/com/voiceclear/app/MainViewModel.kt#L520-L526)：录音中返回 false 不回滚。
- 当前实例继续旧音源，重启后才突然用新设置。

### P1-15：保留策略的后台清理与调用方存在竞态，强制全量测试已暴露

- [VoiceClearApplication.kt:41-51](app/src/main/java/com/voiceclear/app/VoiceClearApplication.kt#L41-51)：进程启动即在单线程 Executor 中异步执行 `DataRetentionPolicy.enforce()`。
- [DataRetentionPolicy.kt:46-87](app/src/main/java/com/voiceclear/app/capture/DataRetentionPolicy.kt#L46-87)：删除音频没有与调用方、录音写入或另一次清理共享锁/快照。
- [DataRetentionPolicyTest.kt:33-47](app/src/test/java/com/voiceclear/app/capture/DataRetentionPolicyTest.kt#L33-47)：测试先创建旧文件，再调用 enforce 并断言 `deletedFiles=1`。

**本次强制重跑的实际失败：**

```text
DataRetentionPolicyTest > expired audio is deleted FAILED
expected:<1> but was:<0>
```

测试 XML 同时记录后台 `Retention: 已清理过期录音 1 个文件`，说明后台任务先删除了旧文件，测试自己的 enforce 因此返回 0。直接原因是**测试没有隔离启动清理**，不能据此声称“过期删除完全失效”；但它同时证明生产实现无法防止清理与其他写入/清理并发，可能误删正在使用或未完整写入的音频。

**建议：** 用应用级单例调度器串行化清理，按稳定快照和完整文件规则删除；测试需可注入时钟与清理执行器，禁止启动后台任务与测试用例竞争。

---

## 6. 其他确认问题（P2）

1. [CaptureJson.kt:66-69](app/src/main/java/com/voiceclear/app/capture/CaptureJson.kt#L66-L69)：`putSafe` 吞掉所有序列化异常，可能静默缺字段。
2. [CaptureStore.kt:139-149](app/src/main/java/com/voiceclear/app/capture/CaptureStore.kt#L139-L149)：坏 JSON 行在解析前已计入片段数。
3. [CaptureActivity.kt:310-372](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L310-372)：每次导出 sessions=1、sessionCount=1、firstSessionDate=导出当天、severity=L2、environment=quiet，无法支撑真实跨会话与分层分析。
4. [CaptureActivity.kt:247-255](app/src/main/java/com/voiceclear/app/capture/CaptureActivity.kt#L247-L255) 与 [CaptureSessionController.kt:137-140](app/src/main/java/com/voiceclear/app/capture/CaptureSessionController.kt#L137-140)：intendedText 永远为 null，确认只更新 referenceText，字段语义未统一。
5. [RecognitionEngineOption.kt:76-82](app/src/main/java/com/voiceclear/app/engine/RecognitionEngineOption.kt#L76-L82)：除 FAKE 外一律 isEvaluable=true；[OnnxOfflineRecognitionEngine.kt:49-67](app/src/main/java/com/voiceclear/app/engine/OnnxOfflineRecognitionEngine.kt#L49-L67) 即使目录非空也始终 ERR_MODEL_INVALID，声明与实现不符。
6. [ErrorCodes.kt:152-159](app/src/main/java/com/voiceclear/app/session/ErrorCodes.kt#L152-L159)：domain 只取第一个下划线前内容，`audio_focus_denied` 归为 `audio`，与定义的 focus 域不一致。
7. [FakeSpeechEngines.kt:43-56](app/src/main/java/com/voiceclear/app/engine/FakeSpeechEngines.kt#L43-56)：假合成音频固定 400 ms，但 SynthesisResult.durationMs 是协程延迟（默认 80 ms），字段口径不一致。
8. [check_asr_config.py:49-74](tools/check_asr_config.py#L49-L74)：只取首个数字/字符串前缀，不是真正解析 Kotlin 表达式；例如 `80 * 7` 也会误判为 80。
9. [VoiceClearApplication.kt:44-51](app/src/main/java/com/voiceclear/app/VoiceClearApplication.kt#L44-51)：每次进程启动创建单线程 Executor 且不关闭，属于次要资源管理问题；ApplicationContext 本身不是 Activity 泄漏。

---

## 7. 测试有效性与盲区

本次验证结果是“Python 15 项全绿；Android 180 项中 179 通过、1 失败”。这些结果只能证明已有断言的执行结果，不能证明主流程可靠。

1. **停止测试没有执行生产代码。**
   [StopIdempotencyTest.kt:22-66](app/src/test/java/com/voiceclear/app/StopIdempotencyTest.kt#L22-L66) 和 [StopReleaseTest.kt:20-54](app/src/test/java/com/voiceclear/app/StopReleaseTest.kt#L20-54) 自行创建 AtomicBoolean/模拟流程，无法发现跨会话锁问题。
2. **所谓 stale 测试没有测试取消后的同 ID 迟到结果。**
   [AndroidRuntimeBehaviorTest.kt:69-77](app/src/test/java/com/voiceclear/app/AndroidRuntimeBehaviorTest.kt#L69-77) 对同一有效 ID 连续断言两次 true；[SessionControllerTest.kt:87-93](app/src/test/java/com/voiceclear/app/session/SessionControllerTest.kt#L87-93) 只比较两个不同字符串。
3. **ViewModel 主异步流程基本未测。**
   [MainViewModelTest.kt:80-85](app/src/test/java/com/voiceclear/app/MainViewModelTest.kt#L80-85) 甚至以 `assertTrue(true)` 收尾；未执行 stopRecordingAndProcess、synthesizeAndPlay、playOriginal 的真实竞态。
4. **没有真实 AudioRecord/AudioPlayback/TTS 成功路径。**
   未覆盖 30 秒自动停止、read 错误、join 超时、stop 中断、部分 write、utterance 乱序回调、焦点永久丢失。
5. **没有 Activity 生命周期测试。**
   未覆盖主界面/模型页旋转、权限回调、后台、退出、SAF 文件名和下载并发。
6. **模型下载测试没有验证下载状态机。**
   [ModelDownloadManagerTest.kt:59-77](app/src/test/java/com/voiceclear/app/engine/ModelDownloadManagerTest.kt#L59-77) 只对 invalid.invalid 做 probe，没有 download 的 200/206/416、截断、哈希、安装中断、并发下载。
7. **采集兼容测试是合成数据，不是端到端。**
   [CaptureExportCompatibilityTest.kt:113-128](app/src/test/java/com/voiceclear/app/capture/CaptureExportCompatibilityTest.kt#L113-L128) 不调用 Activity/exportMetadata，不含音频和 prompts，且校验脚本不存在时直接 return。
8. **Python 没有 import_session 测试。**
   因而新目标崩溃、dry-run 假成功、源目標相同删数据长期未被发现。
9. **真实 App 字段形状与评测测试不一致。**
   [CaptureJson.kt:72-96](app/src/main/java/com/voiceclear/app/capture/CaptureJson.kt#L72-96) 不输出 severity/category，Python 测试却手工提供，掩盖真实分层失败。
10. **没有 androidTest 真机用例。**
    本次也无设备/KVM，无法补齐 Gate 3。

---

## 8. 文档与真实进度核验

| 文档声明 | 当前核验 |
|---|---|
| [README.md:5-6](README.md#L5-L6)、[README.md:134-138](README.md#L134-L138)：“识别链路仍为假引擎” | **已过时**。真实 SenseVoice 已接入，见 [RecognitionEngineFactory.kt:19-37](app/src/main/java/com/voiceclear/app/engine/RecognitionEngineFactory.kt#L19-37)。但目标人群质量仍无结论。 |
| [README.md:117-128](README.md#L117-L128)：65 项单测、58 项 Gate 3 | **已过时**。本次为 Android 180、Python 15；Gate 3 真机仍未复核。 |
| [README.md:152](README.md#L152)：“可撤回可删除” | **过度承诺**。只有全量清空，撤回数据仍能通过校验和评测。 |
| [p0-execution-checklist.md:45-53](docs/p0-execution-checklist.md#L45-L53)：A4/A5“待做” | 文件已存在，但应标为“有实现，端到端未验收”，不能标完成。 |
| [p0-execution-checklist.md:123-125](docs/p0-execution-checklist.md#L123-L125)：“A 组全部做完” | 与同文件待做项冲突，且 A1/A3 的校验/评测仍有缺口。 |
| [AUDIT_REPORT.md:120-147](AUDIT_REPORT.md#L120-147)：隐私 10/10 | 与默认授权、撤回失效、导出问题不符，不应继续作为当前评级。 |
| [AUDIT_REPORT.md:586](AUDIT_REPORT.md#L586)：缓冲 2 倍 | 实际为 4 倍，见 [AudioCapture.kt:49](app/src/main/java/com/voiceclear/app/engine/AudioCapture.kt#L49)。 |
| [OnnxOfflineRecognitionEngine.kt:8-26](app/src/main/java/com/voiceclear/app/engine/OnnxOfflineRecognitionEngine.kt#L8-L26)：“未集成 sherpa-onnx” | 对真实 SenseVoice 而言已过时；该类本身仍是未实现占位，且注释需明确区分。 |
| [CHANGELOG.md:11-15](CHANGELOG.md#L11-L15)：0.11.2 | 与 [app/build.gradle.kts:14-15](app/build.gradle.kts#L14-L15) 一致。 |
| [DEVLOG.md:78-81](DEVLOG.md#L78-L81)：180 项测试、修复后待真机复核 | 数量与历史记录一致，但本次强制重跑为 179 通过、1 失败；日志止于 2026-11-17，之后又有新提交和未提交修改，不能视为完整当前进度。 |

---

## 9. 建议修复顺序与验收标准

### 阶段 0：主流程停止交付级修复（先做）

1. 会话 generation/取消语义、每 session 独立 Job，统一所有 terminal 路径。
2. 修复 AudioPlayback.stop、AudioRecord 自动停止/读取失败清理、TTS/焦点停止。
3. 模型加载 IO 化、互斥去重、onCleared 资源所有权；权限回调模型门禁。
4. 将 ASR warnings、播放 false、异常都接入统一失败处理。

**验收：** 新增生产代码级测试覆盖取消→迟到、A 识别中启动 B、30 秒自动停止、read 错误、旋转、权限回调无模型、PCM/TTS 中断；之后真机复测核心路径。

### 阶段 1：研究数据与合规修复

1. 默认拒绝授权，撤回贯穿全链路；禁止伪造签署时间、严重度、会话数和环境。
2. 转写按 segmentId 幂等、音频+元数据原子落盘；修控制器失败回滚。
3. 导出 ZIP/目录，保留独立音频、transcripts、prompts 和索引；明确 SAF 交付。
4. import 先校验、staging、原子发布，拒绝源目標相同/包含。
5. 校验/评测严格处理 unconfirmed、empty、重复/缺失/多余 ID、音频存在与覆盖率。

**验收：** 用真实 CaptureActivity 产出做多轮采集+重复导出+撤回+旋转，校验数据不重复、不丢失；import dry-run 与正式结果一致；缺 hyp 和缺音频返回非零。

### 阶段 2：性能、诊断与文档

1. 指标携带 sessionId，统一 capture/recognition/synthesis/playback/total 口径，失败会话全记录。
2. 修正冷启动、内存字段、RTF 注释和导出 ZIP 文件名；IO 移出主线程。
3. 更新 README/AUDIT/P0 checklist，明确“真实 ASR 已接入但质量未建立”“采集实现未验收”。
4. 真机 Gate 3：核心流程、取消、旋转、后台、音频焦点、模型下载、温升与长时使用。

---

## 10. 最终结论

**VoiceClear 已具备真实端侧 ASR 原型和完整工程骨架，但当前不能把“构建通过”视为“可交付”。**

- 主流程：需先修取消/停止、资源生命周期和模型下载/加载的正确性。
- 研究链路：授权、撤回、转写幂等、完整导出、导入安全和评测口径是硬阻断。
- 质量进度：真实目标人群数据与可信 ASR 基线仍未建立，不应引用普通说话人 CER 代替。
- 验证进度：Python 测试为绿；Android 强制重跑有 1 项保留策略测试失败；Debug/Release 可构建，但本次没有真机 Gate 3 证据。

建议把下一阶段目标从“继续堆功能”改为 **先修正确性与数据可信度，再做真实设备验收和基线采集**。
