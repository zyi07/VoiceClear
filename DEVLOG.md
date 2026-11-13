# VoiceClear 开发日志

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
