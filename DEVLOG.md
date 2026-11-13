# VoiceClear 开发日志

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
