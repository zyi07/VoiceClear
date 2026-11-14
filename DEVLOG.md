# VoiceClear 开发日志

## 2026-11-14 08:45 - 改用官方 sherpa-onnx AAR，修复引擎加载失败

**问题现象：**

用户实测（华为 PLA-AL10 / Android 16）报告：模型下载完成，但点「按住说话」仍提示未就绪。

**诊断包给出的确切错误：**

```
I/ModelSetup: 模型已就绪
E/SenseVoice: 模型加载失败: Unable to create directory: /lib/linux-aarch64
E/Main: 识别引擎加载失败
```

**根因（已通过 javap 反编译确认）：**

第三方 Maven 包 `com.litongjava:sherpa-onnx-java-api:1.0.1` 里的
`LibraryUtils.load()` 是**桌面平台**实现：

1. 读 `System.getProperty("user.home")` 拼 `$HOME/lib/<os>-<arch>/`；
2. 在该目录创建文件，从 jar 内资源解压 native 库；
3. 用 `System.load(绝对路径)` 加载。

Android 上 `user.home` 为 null → 路径退化成 `/lib/linux-aarch64` →
应用无权在根目录创建 `/lib` → 抛错。

官方 Android 用法（`kotlin-api/OfflineRecognizer.kt`）只有一行：
`System.loadLibrary("sherpa-onnx-jni")`，由系统从 APK 的 `lib/<abi>/` 加载，无需解压。

**更严重的问题：版本不匹配。**

- APK 里的 native 库是 **v2.0.1**（官方 Android release）；
- 第三方 jar 1.0.1 的 Java API 对应的是**更早版本**的 native 库
  （实测其 JNI 依赖 onnxruntime `VERS_1.17.1`，而 v2.0.1 用 `VERS_1.28.2`）。

两者配套关系不保证，是潜在的隐蔽故障。

**修复：改用官方 AAR**

- 从 GitHub Release 下载 `sherpa-onnx-2.0.1.aar`（49 MB，含 arm64-v8a / armeabi-v7a /
  x86 / x86_64 全部 native 库 + 官方 Kotlin API）；
- 存为 `libs/sherpa-onnx-2.0.1.aar`，Gradle 用 `implementation(files(...))` 引入；
- 删除手工维护的 `app/src/main/jniLibs/`（由 AAR 提供，版本天然一致）；
- 引擎改用官方 Kotlin API（data class 直接赋值，不再用 Builder）。

**顺带修好的两个真实配置错误（靠实测推理发现的）：**

1. `OfflineModelConfig.tokens` **必须**设为 tokens.txt 的绝对路径，
   否则 native 侧报 "Errors found in config!"、结果为空。
   （用官方 Python API 跑同一模型同一音频得到正确文本，才定位到是配置而非模型问题。）
2. native 库加载顺序：`libonnxruntime.so` 必须**先**加载，
   否则 `OrtGetApiBase` 符号未解析，`createStream` 直接段错误（本机实测到 core dump）。

**验证方式：**

- 用官方 Python API（sherpa-onnx 2.0.1）跑同一模型同一音频，
  识别出 `'开饭时间早上9点至下午5点。'`（0.448s），确认**模型与音频本身无问题**，
  问题在 Java 侧配置与加载方式。
- 官方 AAR 内的 native 库与原先手工放入的 jniLibs **字节级一致**
  （`libonnxruntime.so` 同为 22249560 字节），说明 native 库本身一直是对的，
  错的只是 Java 绑定。

**遗留限制：**

- Android 的 .so 依赖 `libandroid.so`，无法在本机 Linux 上运行完整推理，
  因此**最终识别结果仍需真机验证**（这是本次修复的主要不确定点）。
- 但相比之前，版本配套问题已从根上消除：Java API 与 native 库现在同属 v2.0.1。

---

## 2026-11-14 01:35 - 修复模型下载必然失败：清单漏声明 INTERNET 权限

**问题现象：**

用户实测（华为 PLA-AL10 / Android 16）报告模型完全无法下载，挂梯子不挂梯子都直接报错。

**定位过程：**

用户提供的诊断包（`voiceclear-log-*.zip`）中 `app-log.txt` 明确记录：

```
W/ModelDownload: 下载尝试 1 失败: Permission denied (missing INTERNET permission?)
E/ModelDownload: 归档下载失败: Permission denied (missing INTERNET permission?)
E/ModelDownload: 所有来源均失败
```

根因：**AndroidManifest.xml 只声明了 RECORD_AUDIO，没声明 INTERNET**。
所有网络请求在框架层就被拒绝，请求根本没有发出去——所以挂不挂梯子都一样。

**修复：**

1. `AndroidManifest.xml` 补充 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限，
   并加注释说明该权限仅用于一次性下载模型，语音推理完全本地。
2. `ModelSetupActivity` 增加网络可用性检查：无网络时直接提示，不做无谓的下载尝试。
3. 下载失败时的界面提示改为引导用户导出诊断信息。

**流程改进（防止同类问题复发）：**

这次故障暴露了一个真实缺陷：**APK 的权限从未在构建期校验过**。
此前所有功能都是纯本地，漏权限不会有任何症状，直到引入网络下载才暴露。

- 新增 `tools/check_permissions.py`：解析 APK，校验必需权限齐备，
  缺失则退出码非零。
- 新增 Gradle 任务 `:app:checkApkPermissions`，作为构建门禁；
  缺权限时**构建直接失败**。
- **已验证门禁有效**：临时移除 INTERNET 权限后，构建确实失败并报出
  「缺少必需权限: android.permission.INTERNET」；恢复后重新通过。
  没有验证过的门禁等于没有门禁。

**从诊断包读到的设备信息（来自 snapshot.txt）：**

- 机型：HUAWEI PLA-AL10（Mate 30 Pro 系列）
- Android 16 / SDK 36，ABI arm64-v8a（与内置 native 库匹配）
- 8 核，堆上限 384 MB
- 音频输出采样率 48 kHz（应用内部按 16 kHz 采集，不冲突）

**尚未验证：**

- 两个下载源在移动网络下的真实成功率（用户此前从未真正发出过请求）。
- 239 MB 模型在 384 MB 堆上限设备上的内存占用是否可接受。

---

## 2026-11-14 00:55 - 模型下载多源降级（ModelScope → GitHub）+ 断点续传

**做了什么：**

- 新增 `engine/ModelSource.kt`：下载源抽象，含 `Kind.DIRECT` / `Kind.ARCHIVE` 两种形态
  与 Range 请求连通性探测。
- 重构 `engine/ModelDownloadManager.kt`：
  - **多源自动降级**：ModelScope（国内直连，实测约 6 MB/s）→ GitHub Release（官方归档）；
    任一成功即停止，全部失败才报错。
  - **断点续传**：Range 请求 + `.part` 临时文件；服务端不支持续传（返回 200）时自动从头写。
  - 每源独立重试 2 次；按各源期望大小分别做完整性校验（阈值 95%）。
- `ModelSetupActivity`：新增 `onSourceChange` 回调，界面实时显示当前下载来源。
- 新增 `ModelDownloadManagerTest`（7 项），验证降级顺序、URL 合法性、
  已就绪时不再下载等行为。

**双源模型等价性实测（不是假设）：**

下载了 ModelScope 的 `model_q8.onnx`（239,234,116 字节）与 GitHub 的
`model.int8.onnx`（239,233,841 字节），用 ONNX Runtime 1.32.0 实测：

- **图结构完全一致**：输入 `x[N,T,560]` / `x_length[N]` / `language[N]` / `text_norm[N]`，
  输出 `logits[N,T,25055]`，两者签名逐项相同。
- **词表字节完全一致**：`tokens.txt` 均为 315,894 字节，`cmp` 判定 IDENTICAL。
- **两者均可正常推理**：随机输入下 logits 形状相同，
  mean -7.52 vs -7.63、std 4.54 vs 4.67，差异来自量化细节而非结构问题。
- 因两者不是同一份文件，**代码不能硬编码单一大小的期望值**，
  因此每个源各自记录 `expectedModelBytes`，分别校验。

**过程中修正的真实问题：**

1. Python 生成 Kotlin 代码时漏掉块注释的 `*/`，导致测试文件编译失败——
   已定位到第 14 行并补齐。
2. 旧测试引用了重构后已删除的常量（`ARCHIVE_URL` 等），改为断言
   「所有下载源必须为 https」，这个断言比原来更有价值。
3. 首次尝试用 sherpa-onnx 的 JNI 在本机跑推理失败（onnxruntime 版本不匹配：
   需要 1.17.1，本机只有其他版本）。改用 ONNX Runtime Python 直接验证模型，
   绕开了 JNI 依赖问题，验证结论等价。

**仍存在的限制：**

- 两个源的实际下载成功率**未在真机移动网络下验证**，需要用户实测。
- ModelScope 上的 `model_q8.onnx` 命名与 GitHub 的 `model.int8.onnx` 不同，
  但实测可用；代码按 `MODEL_FILE` 统一命名，落盘后与源无关。

**验证结果：**

- App 测试 65 项全通过；Lint 0 Error；debug 构建成功。

---

## 2026-11-14 00:00 - 接入端侧离线识别引擎（SenseVoice）+ 完整诊断导出

**做了什么：**

- **端侧真实识别引擎**：sherpa-onnx + SenseVoice Small int8 量化模型（239 MB）。
  - `engine/SenseVoiceRecognitionEngine.kt`：封装 OfflineRecognizer，PCM16 → 归一化浮点 →
    `acceptWaveform` → `decode` → `getResult`。
  - `engine/ModelDownloadManager.kt`：首次启动从 GitHub Release 下载归档 → `tar` 解压 →
    完整性校验（模型体积 ≥ 90% 期望值）→ 上提到模型目录根。
  - `engine/RecognitionEngineFactory.kt`：**模型未就绪时返回 null，不退回假引擎冒充真实识别**。
  - `ModelSetupActivity`：独立下载引导页，含进度显示与失败重试。
- **完整诊断系统**：
  - `session/CrashHandler.kt`：未捕获异常堆栈持久化（最多保留 10 份），闪退可追溯。
  - `session/DiagnosticSnapshot.kt`：设备/运行时/存储/权限/音频设备快照。
  - `session/DiagnosticExporter.kt`：**一键导出 zip**，含 app-log.txt、crash.txt、
    snapshot.txt、metrics.json、README.txt。
  - `MetricsCollector.exportJson()`：手工拼接 JSON（不用 org.json，单测环境下是空实现）。
- **构建配置**：
  - jniLibs 引入 sherpa-onnx Android native 库，ABI 过滤为 arm64-v8a + armeabi-v7a
    （去掉 x86/x86_64，省约 71 MB）。
  - packaging 排除 Maven jar 内的桌面端 native 库（osx/win/linux），APK 从 64 MB 降到 59.5 MB。

**过程中修正的真实问题：**

1. **`setModelConfig` 方法不存在**——正确是 `setOfflineModelConfig`。凭记忆写必然出错，
   已用 javap 实测 sherpa-onnx-java-api 1.0.1 的真实签名后才写对。
2. **Maven jar 内含桌面端 native 库**（osx-aarch64/osx-x64/win-x64/linux-*），
   被一并打进 APK 占 13 MB 且在 Android 上无用，已通过 packaging 规则排除。
3. **`AudioManager.propertyForSamplingRate` 不存在**——正确是
   `getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)`，已用 javap 核实。
4. **org.json 在单元测试下是空实现**，`put` 静默失效；`MetricsCollector.exportJson()`
   改为手工拼接，避免写出永远通过的假测试。

**关键边界（诚实说明）：**

- SenseVoice **不提供 token 级置信度**，引擎返回 `confidence = null`。
  内容安全必须依赖"用户确认文本"交互，不能用置信度阈值自动放行。
- SenseVoice 公开基准 AISHELL-1 CER 7.81%（普通说话人），
  **目标人群（听障相关发音不清）的准确率尚无数据**，不能引用通用基准代替。
- 模型下载依赖 GitHub Release；huggingface 与 hf-mirror 在本次开发环境返回 401，
  未验证国内网络环境下的实际下载成功率，需真机验证。

**验证结果：**

- App 测试 58 项全通过；Lint 0 Error；debug 构建成功；APK 59.5 MB。

**待做什么：**

- 真机验证：模型下载成功率、真实识别准确率、端到端延迟、内存与温升。
- release 签名版仍未产出（安全策略禁止生成/读取密钥材料）。

---

## 2026-11-13 23:10 - 首代 APK、诊断日志与音频导出授权

**做了什么：**

- 新增 `session/DiagnosticLog.kt`：同时写 logcat 与内存缓冲，可导出为文本文件。
  **不记录原始音频与完整表达文本**，只记录各阶段耗时、置信度、文本长度与错误码。
- 主界面新增「导出诊断日志」按钮，走系统文件选择器（SAF），无需任何存储权限。
- 在采集、识别、合成、播放、指标各阶段埋点，真机联调可直接定位耗时与失败环节。
- 新增 `capture/AudioExportActivity.kt`：音频导出需**显式二次确认**（说明音频是生物特征），
  写入目标由系统文件选择器决定。补齐了此前标注为「尚未实现」的最后一环。
- 新增 `docs/apk-testing-guide.md`：安装方式、两种抓日志方式、建议测试路径、
  8 个边界场景（长停顿、极短语音、来电打断、蓝牙、静音、飞行模式、后台切换等）与已知限制。
- 构建首代 debug APK（6.85 MB），已用 apksigner 验证签名有效、
  aapt2 确认 minSdk 26 / targetSdk 36 / 采集 Activity 与 assets 提示句库均已打包。

**限制说明：**

- release 签名版**未产出**：安全策略禁止在项目目录生成或读取 .jks 密钥材料，
  因此交付的是 Gradle 自动签名的 debug APK（可直接安装）。如需 release 版，
  需由你提供签名配置或在本机用自有 keystore 构建。
- 该 APK **不含真实中文 ASR**（识别仍为假引擎），本轮测的是交互链路、
  音频采集、播放、状态机与日志，不是识别质量。
- **真机验证仍未完成**，待你安装后回传日志与使用反馈。

**验证结果：**

- App 测试 45 项全通过；工具测试 15 项全通过；Lint 0 Error；debug 构建成功。

---

## 2026-11-13 22:55 - 采集模式 App、入库流水线与知情同意草稿

**做了什么：**

- 实现采集模式（`capture/` 包，5 个文件）：数据模型、JSON 序列化、本地存储与导出、
  采集会话控制（意图确认状态机）、采集界面。
- 提示句库（45 条 / 9 类易错）打包进 `assets/prompts/prompts.json`，随 App 分发保证离线可用与可复现。
- 采集流程：选择提示句 → 录音 → 质量检查 → 自发表达需确认意图 → 入库；
  未确认意图的片段标记 `unconfirmed`，**不计入内容正确率统计**。
- 新增 `tools/import_session.py`：App 导出 → 标准数据集 → 自动校验，附来源溯源记录。
- 新增 `docs/consent-and-notice-draft.md`：知情同意书、分项授权勾选表、现场口头说明稿，
  含**撤回的技术边界**说明（已训练全局模型无法精确移除个人特征）。
- 主界面新增采集模式入口；清单注册 CaptureActivity（非导出）。

**测试与验证：**

- 新增 `CaptureJsonTest`（6 项）与 `CaptureExportCompatibilityTest`（2 项），
  其中包含**端到端兼容性验证**：App 导出的数据集真实调用 `tools/validate_dataset.py` 并要求通过。
- App 测试 45 项全通过；工具测试 15 项全通过；Lint 0 Error；debug/release 构建成功。

**过程中修正的真实问题：**

1. **Android 单元测试中 `org.json` 是空实现**——`JSONObject.put` 不抛异常但也不写入数据。
   通过探针测试确认后，将相关测试改用 Robolectric（提供真实实现），避免写出"永远通过"的假测试。
2. 采集会话的 `sessionId` 未公开、`selectPrompt` 不接受 null（自发表达模式）——均已修正。
3. `CaptureStore` 中一处 `forEach` 缺少闭合括号导致整文件解析失败，连带掩盖了其他真实错误。

---

## 2026-11-13 21:15 - 识别引擎能力契约与端侧离线接入层

**做了什么：**

- 新增 `engine/RecognitionEngine.kt`：识别引擎能力契约，显式声明离线能力、置信度可用性、
  是否占用麦克风、是否保留音色，使选型、统计与降级逻辑不再依赖隐含假设。
- 新增 `engine/RecognitionEngineOption.kt`：引擎选型策略（FAKE / SYSTEM / ONNX_OFFLINE / PERSONALIZED），
  明确 Fake 引擎不可用于评估、只有端侧离线模型适合作为首版候选。
- 新增 `engine/OnnxOfflineRecognitionEngine.kt`：sherpa-onnx 方案的接入层与能力校验，
  模型包缺失或无效时返回明确失败码，**不返回任何模拟结果**以免污染评测数据。
- 联网核实一项关键约束：Android 系统 SpeechRecognizer 会自行占用麦克风（VoiceCommunication 流），
  与本应用的 AudioCapture 互斥，因此不能作为首版识别方案。
- 指标中记录引擎 ID（modelVersion 形如 fake-0.1.0+sherpa-onnx-zh），便于按引擎分组统计。
- 测试增至 37 项（新增 RecognitionEngineOptionTest 7 项）。

**诚实边界：**

当前版本**没有任何真实识别质量结论**——识别链路仍使用假引擎。真实中文 ASR 的接入需要先解决
模型分发方式、许可证核查与目标人群效果对照三件事。

**验证结果：**

- Gate 1：lintDebug BUILD SUCCESSFUL，0 个 Error。
- Gate 2：assembleDebug + assembleRelease 均成功。
- Gate 3：37 项测试全部通过（0 失败 0 错误）。

---

## 2026-11-13 21:00 - P2 工程基线：原生 TTS、音频焦点、指标采集与目标人群协议

**做了什么：**

- 按已确认的首批人群（听障相关发音不清的普通话成人）推进工程基线。
- 实现 `engine/AndroidTtsEngine.kt`：基于 Android 原生 TextToSpeech 的普通话合成，
  作为"通用声音基线"（不保留用户音色，符合 5.4 节先建基线再个性化的次序）。
  API 均由本地 SDK javap 实测核对：TextToSpeech(Context, OnInitListener)、setLanguage(Locale)、
  setSpeechRate/setPitch、speak(CharSequence,int,Bundle,String)、UtteranceProgressListener。
- 实现 `engine/AudioFocusManager.kt`：播放前申请 AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE，
  收到焦点丢失立即停止播放，会话结束释放焦点，避免影响用户正常使用设备。
  AudioFocusRequest 自 API 26 引入，与项目 minSdk 26 一致，无需兼容分支。
- 实现 `session/MetricsCollector.kt`：分阶段耗时（采集/识别/合成/播放/端到端）、结果状态、
  低置信度单独标记以便按人群分层分析；只存内存，不含原始音频与完整文本。
- 接入 MainActivity：真实合成引擎、焦点申请/释放、取消与错误路径的指标记录。
- 修正焦点申请时机缺陷：初版在播放之后才申请焦点，导致焦点逻辑失效，已改为播放前申请。
- 修正 TTS 挂死风险：框架模拟下 TextToSpeech 初始化回调不触发导致测试无限挂起，
  已加入初始化 8 秒与合成 15 秒双重超时保护——这同样保护真机上的引擎无响应场景。
- 新增 `docs/target-cohort-data-collection-protocol.md`：该人群的采集协议、严重度分层（L1/L2/L3）、
  场景分层、会话分层、逐条意图基准确认、防泄漏划分、标注质控、隐私生命周期与启动阻塞项。
- 测试扩至 30 项（新增 MetricsCollectorTest 5 项、AndroidTtsEngineTest 4 项）。

**验证结果：**

- Gate 1：`lintDebug` BUILD SUCCESSFUL，0 个 Error。
- Gate 2：assembleDebug + assembleRelease 均成功，debug 5.81 MB / release 4.67 MB。
- Gate 3：30 项测试全部通过（5 个测试类，0 失败 0 错误），含 Robolectric 框架级验证。
  仍受限于本机 aarch64 无 /dev/kvm，真机验证待补。

**待做什么：**

- 接入真实中文 ASR（当前识别仍为假引擎），先跑通用中文 ASR 在目标人群上的基线。
- 采集协议需完成伦理与合规审查后才能启动实际采集。
- 取得真机环境后补做音频路由、来电中断、蓝牙断连、连续会话温升与延迟测量。

---

## 2026-11-13 20:30 - P1 可测原型搭建与三道门槛验证

**做了什么：**

- 依据《VoiceClear-Android 软件开发可行性研究与技术指导》v1.0 搭建首个可构建的 Android 原型。
- 建立 API 验证表：所有音频 API 均用本地 SDK（/opt/android-sdk/platforms/android-34）的 `javap` 实测签名核对后使用，
  包括 `AudioRecord(int,int,int,int,int)`、`AudioRecord.getMinBufferSize`、`AudioFormat.ENCODING_PCM_16BIT`、
  `AudioFormat.CHANNEL_IN_MONO`、`AudioTrack.MODE_STREAM`、`AudioAttributes.USAGE_ASSISTANCE_SONIFICATION`。
- 实现模块：
  - `session/`：SessionState、SessionEvent、SessionController（纯 Kotlin，可单测，绑定 sessionId 拒绝过期结果）
  - `engine/`：识别/合成适配接口、AudioSegment 数据契约、FakeRecognitionEngine、FakeSynthesisEngine、
    AudioCapture（权限失败路径显式返回而非崩溃）、AudioPlayback
  - `MainActivity`：录音 → 识别 → 可编辑确认 → 合成播放 → 可播放原始语音，含低置信度提示与取消
  - 资源与清单：字符串占位符、无障碍标签、autofill 声明、备份与数据提取规则（默认全部排除）
- 测试：SessionControllerTest 11 项、FakeSpeechEnginesTest 4 项、AndroidRuntimeBehaviorTest 6 项，共 21 项全部通过。
- 修正了 Lint 暴露的真实缺陷：`AudioTrack.MODE_STREAM` 误写为 0（正确为 1）、
  `MissingPermission` 权限标注缺失、`String.format` 未指定 Locale、字符串拼接未用资源占位符。
- 文档更新：更正原始需求 v0.2 给出的 VoiceRestore / SIDON 仓库地址（实为 `skirdey/voicerestore` 与
  `sarulab-speech/Sidon`），并确认两者均为**通用语音修复/数据清洗**模型而非构音障碍专用方案；
  补充三条构音障碍专用 S2S 研究线索；按官方口径更新 CDSD 规模表述。

**验证结果：**

- Gate 1 静态检查：`gradle :app:lintDebug` BUILD SUCCESSFUL，**0 个 Error**（剩余为版本升级建议）。
- Gate 2 完整构建：`assembleDebug` + `assembleRelease` 均成功，产出 debug/release APK；
  `aapt2 dump badging` 确认包名 com.voiceclear.app、minSdk 26、targetSdk 36、RECORD_AUDIO 权限与启动 Activity 正确。
- Gate 3 运行验证：本机为 aarch64 且**无 /dev/kvm，无法运行 Android 模拟器**，也无连接的真机；
  改用 Robolectric 4.18-beta-1（首个包含 Linux arm64 原生库的版本）在真实 Android 框架语义下运行，
  21 项测试全部通过，含"未授予权限时采集返回失败而不崩溃"的框架级验证。

**待做什么：**

- 取得真机或可运行模拟器的环境后，补做真机 Gate 3：音频路由、来电中断、蓝牙断连、连续会话温升与延迟测量。
- 接入真实 ASR/TTS（当前为假引擎），先跑通用中文 ASR/TTS 基线（P2）。
- 建立合规的目标用户数据采集流程与评测协议（受限于尚未招募用户与专业顾问）。
- 补充音频焦点与录音焦点管理、模型包版本管理与回滚机制。

---
