# VoiceClear-Android

面向**言语障碍者**的安卓端语音表达辅助应用：让发音不清的用户用**自己的声音**把话说清楚。

> 当前状态：**P1/P2 工程基线**。已具备可构建、可测试的应用骨架与完整技术方案；
> **尚无真实识别质量结论**（识别链路仍为假引擎），详见下文「诚实的边界」。

## 目标人群

首版聚焦 **听障相关发音不清的普通话成人**（已确认的项目决策）。
其他人群（神经源性构音障碍、声带/喉部病变、口吃）需要独立的数据协议与评测方法，
不在首版覆盖范围——把它们混在一起会掩盖严重用户的退化。

## 文档

| 文档 | 内容 |
|---|---|
| [可行性研究与技术指导](docs/VoiceClear-Android-feasibility-and-development-guide.md) | 技术选型、架构、数据与隐私治理、评测体系、风险登记、P0–P5 计划 |
| [目标人群数据采集协议](docs/target-cohort-data-collection-protocol.md) | 严重度分层、意图基准确认、防泄漏划分、伦理与合规阻塞项 |
| [开发日志](DEVLOG.md) | 每轮实现要点与三道门槛验证结果 |

## 技术路线

采用 **ASR → 最小文本处理 → TTS** 级联作为首版基线，端到端语音到语音（S2S）作为有退出条件的研究分支。
理由：级联可分模块定位错误、可审计、可纠错；S2S 是否更优必须在同一批用户、同一测试集上
用同一协议对照才能得出，不能由架构偏好决定。

```
麦克风采集 → 质量检查 → 识别引擎 → 置信度控制 → 最小文本规范化 → 合成引擎 → 播放控制
                  ↓ 低置信度                                    ↓ 焦点抢占即停
            原文回放/候选/重试                          原始语音随时可播
```

设计要点：

- **内容保真优先于音色**：宁可不够清楚，也不能「清晰地说错」。
- **低置信度不强行合成**：提供候选、原文回放、重试。
- **端侧优先**：识别与合成都走本地，不产生网络请求。
- **可替换的引擎层**：识别/合成通过接口调用，便于假引擎测试与后续换模型。

## 工程结构

```
app/src/main/java/com/voiceclear/app/
├── MainActivity.kt              # 录音 → 识别 → 确认 → 合成 → 播放 主流程
├── engine/                      # 引擎适配层
│   ├── RecognitionEngine.kt     #   识别能力契约（离线/置信度/麦克风占用/音色）
│   ├── RecognitionEngineOption.kt #  引擎选型策略
│   ├── OnnxOfflineRecognitionEngine.kt # 端侧离线接入层（模型待集成）
│   ├── AndroidTtsEngine.kt      #   原生 TTS 合成（通用声音基线）
│   ├── AudioCapture.kt          #   麦克风采集（含权限失败路径）
│   ├── AudioPlayback.kt         #   播放控制
│   └── AudioFocusManager.kt     #   音频焦点（抢占即停、会话结束释放）
└── session/                     # 会话与指标
    ├── SessionController.kt     #   状态机（拒绝过期会话结果）
    └── MetricsCollector.kt      #   分阶段耗时与结果状态（本地、不含内容）
```

## 端侧识别引擎

- **引擎**：sherpa-onnx + SenseVoice Small（int8 量化，约 239 MB）
- **许可**：sherpa-onnx 为 Apache-2.0；模型来自 ModelScope FunASR
- **模型获取**：首次启动时按需下载到应用私有目录，不打进 APK
  - **多源自动降级**：ModelScope（魔搭，国内直连）→ GitHub Release（官方）
  - 支持断点续传（Range 请求），移动网络中断后可接着传
  - 两源模型是同一 SenseVoice 的不同量化导出，已实测图结构、输入输出签名、
    词表（tokens.txt 字节完全一致）均相同，仅权重数值有细微差异
- **完全离线**：推理全在设备上完成，不上传任何音频

```
app/src/main/java/com/voiceclear/app/engine/
  SenseVoiceRecognitionEngine.kt   # 识别引擎
  ModelDownloadManager.kt           # 模型下载与解压
  ModelSource.kt                    # 下载源定义与降级顺序
  RecognitionEngineFactory.kt       # 引擎选择（模型未就绪时拒绝伪造）
libs/sherpa-onnx-2.0.1.aar           # 官方 AAR（Java API + 全部 ABI 的 native 库）
```

**为什么用官方 AAR 而不是 Maven 上的 jar**：第三方重新打包版的 `LibraryUtils` 是
**桌面平台**的加载器，会在 `$HOME/lib/<os>-<arch>/` 解压 native 库；
Android 上 `user.home` 为 null，路径退化成 `/lib/linux-aarch64`，应用无权创建该目录，
于是抛 `Unable to create directory: /lib/linux-aarch64`。
官方 AAR 直接用 `System.loadLibrary`，由系统从 APK 的 `lib/<abi>/` 加载。
**Java API 与 native 库必须同版本**，官方 AAR 保证这一点。

**已知限制**：SenseVoice 不提供 token 级置信度，因此引擎返回 `confidence = null`，
上层不得以置信度阈值自动放行内容，内容安全依赖"用户确认文本"这一交互。

## 安装与真机测试

```bash
./gradlew :app:assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

要求 Android 8.0（API 26）及以上。首次启动需授予麦克风权限。

抓日志（两种方式）：

```bash
adb logcat -c
adb logcat -v threadtime -s VoiceClear:V > voiceclear-log.txt   # 实时
```

或在 App 内点「导出诊断日志」按钮直接保存文件（无需连电脑）。

**日志不含原始音频与完整表达文本**，只含各阶段耗时、置信度、错误码等元信息。

详细测试步骤与边界场景清单见 [APK 真机测试指引](docs/apk-testing-guide.md)。

## 构建与验证

```bash
./gradlew :app:assembleDebug          # 构建
./gradlew :app:checkApkPermissions     # APK 权限门禁（缺权限则构建失败）
./gradlew :app:testDebugUnitTest      # 单元测试（65 项）
./gradlew :app:lintDebug              # 静态检查
python3 tools/test_tools.py           # 工具测试（15 项）
```

三道验证门槛：

| 门槛 | 状态 |
|---|---|
| Gate 1 静态检查 | 通过，**0 个 Error** |
| Gate 2 完整构建 | 通过，debug/release APK 均产出 |
| Gate 3 运行验证 | 58 项测试全部通过（Robolectric 框架级）；**真机验证进行中** |

> Gate 3 说明：本仓库在无 KVM 的 aarch64 环境中开发，模拟器不可用，故用
> Robolectric（真实 Android 框架语义）验证。音频路由、来电中断、蓝牙断连、
> 连续会话温升与延迟仍需在真机上测量。

## 诚实的边界

- **无真实识别质量结论**：识别使用假引擎。真实中文 ASR 的接入需先解决模型分发方式、
  许可证核查，以及在目标人群上与通用 ASR 的对照。
- **未做真机验证**：APK 已交付待测，见 [测试指引](docs/apk-testing-guide.md)。
- **识别准确率未在目标人群上实测**：SenseVoice 公开基准 AISHELL-1 CER 7.81%，
  但那是普通说话人数据。目标人群（听障相关发音不清）的真实准确率**尚无数据**，
  不能引用通用基准代替。
- **尚无目标用户数据**：数据采集协议已就绪，但伦理与合规审查未完成，
  因此协议明确标注「不得据此实际采集」。
- **开源项目引用需谨慎**：原始需求提到的 VoiceRestore / SIDON 经核实均为
  **通用语音修复 / 数据清洗**模型，并非构音障碍专用方案，不能当作现成依赖。

## 许可与合规

- 尚未选定发布许可，待产品阶段确定。
- 数据处理涉及敏感个人信息与医疗健康信息，
  须遵守《中华人民共和国个人信息保护法》第 28、29、31、47、55、56 条的适用要求。
- 语音、声纹与障碍类别按高敏感资产处理：最小化采集、本地优先、可撤回可删除。
