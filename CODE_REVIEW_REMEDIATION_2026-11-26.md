# VoiceClear 全量审查整改记录（2026-11-26）

对应输入：`CODE_REVIEW_2026-11-26.md`。本文件记录当前工作树的代码整改与实际验证，不把静态修复等同于真机交付验收。

## 1. P0 停止交付级问题

| 编号 | 整改结果 |
|---|---|
| P0-1 | `SessionController.Token` 同时包含 sessionId 与单调递增 generation；取消/失败/完成使令牌失效。停止门闩按会话隔离，UI 事件也校验 ID + generation。 |
| P0-2 | AudioPlayback 共享并同步当前 AudioTrack，stop 可中断写入，只有完整写入才成功；TTS 在 speak 前取得焦点，取消/丢焦同时停止 PCM 和系统 TTS。 |
| P0-3 | AudioCapture 增加 Recording/Completed/Failed/Stopped 状态，所有退出统一幂等清理；read 错误与 join 超时返回 Failure，不再交付截断音频。 |
| P0-4 | ViewModel 统一持有资源，模型加载 IO 化并去重；临时模型页引擎在 finally 释放；onCleared 停止采集/播放/TTS，并等待 native 识别真正退出后释放 ASR。 |
| P0-5 | isReady 复验模型和 tokens 的精确大小与 SHA-256；下载使用独立 staging、完整校验后发布，不再接受同大小错误哈希。 |
| P0-6 | ViewModel 的 startRecording 内置真实模型门禁，权限回调与普通点击共用；Fake 默认 confidence=null，真实流程不会使用占位识别。 |
| P0-7 | 采集默认全部拒绝且无伪造 signedAt；录音和入库检查 research_use；撤回贯穿 App 导出、Python 校验和评测。 |
| P0-8 | 转写按 segmentId upsert；音频与元数据立即关联，Activity 销毁取消采集；重复确认/导出不重复追加。 |
| P0-9 | 导出改为数据集 ZIP，每段 PCM 是独立 entry，包含 manifest/transcripts/prompts/speakers/consent/splits 与逐段路径。 |
| P0-10 | 导入支持新目录；拒绝相同/包含路径；dry-run 完整只读校验；正式导入先源校验，再 staging 复验和原子发布，失败保留旧目标。 |
| P0-11 | 校验/评测严格处理授权、撤回、unconfirmed、empty_capture、重复 ID、音频存在/时长、空参考文本及 matched/missing/extra/覆盖率；任何数据错误非零退出。 |

## 2. P1 高优先级问题

| 编号 | 整改结果 |
|---|---|
| P1-1 | RecognitionResult 增加明确 errorCode/isSuccess；主层失败关闭，识别移入 IO；补齐采样率、声道和 PCM16 错误。 |
| P1-2 | Activity 不再向 ViewModel 注册捕获视图的回调；StateFlow 恢复持久状态，Channel 保留旋转/后台期间的一次性事件。 |
| P1-3 | 模型与会话可启动检查先于旧结果清理；确认页录音按钮禁用，不再先删音频后报“上次未完成”。 |
| P1-4 | 采集启动/停止失败回滚控制器；录音中禁止提示切换、模式切换、清空和撤回。 |
| P1-5 | 类别标签与点击共用同一个有序 entries 列表。 |
| P1-6 | TTS 回调校验 utteranceId；stop/release 结束等待者；初始化失败可重试并保留具体错误。 |
| P1-7 | AudioFocusManager 区分请求对象和实际持有状态，LOSS 后立即失效，GAIN 后恢复。 |
| P1-8 | 下载支持 408/429/5xx、网络异常和截断重试，处理 200/206/416；同模型目录进程内互斥并写独立临时文件。 |
| P1-9 | Benchmark Sample 携带 sessionId 并使用 amend(sessionId)；各 terminal 路径指标幂等记录，失败不再漏记。 |
| P1-10 | 冷启动只在 ViewModel 首次创建时写入；内存字段改为 used/free/max Java 堆快照；RTF 注释与公式一致。 |
| P1-11 | AudioCapture 保存真实 sessionId；PCM 与原声播放 Boolean 均进入结果处理；播放任务按 generation 隔离。 |
| P1-12 | 保留删除写入 audioAvailable=false 与删除原因，统计/导出/校验不再把无音频记录视为有效。 |
| P1-13 | 诊断 SAF 文件使用 DiagnosticExporter.suggestedFileName() 的 `.zip`；构建和写入移入 IO。 |
| P1-14 | 音源应用成功后才持久化；会话忙时拒绝且不改变设置。 |
| P1-15 | 保留清理由受控调度器执行；写入、导出和清理共享按数据集根目录区分的锁；测试使用固定时钟和直接执行器。 |

## 3. P2 与其他确认问题

| 编号 | 整改结果 |
|---|---|
| P2-1 | CaptureJson 序列化不再静默吞异常；必要字段失败可见。 |
| P2-2 | 统计基于成功解析且仍有效的转写记录，坏 JSON 不再提前计入有效片段。 |
| P2-3 | session 数、首次日期、环境和总时长从真实记录计算；未知严重度写 unspecified，不伪造 L2/quiet。 |
| P2-4 | 自发表达确认同步 referenceText 与 intendedText，字段语义一致。 |
| P2-5 | 旧 SYSTEM/ONNX/PERSONALIZED 选项按实际能力标为不可评测；SenseVoice 由真实引擎独立接入。 |
| P2-6 | `audio_focus_denied` 明确归入 focus 域。 |
| P2-7 | Fake 合成的结果时长与实际 400 ms 音频一致。 |
| P2-8 | ASR 配置门禁使用受限表达式解析器，支持算术/括号/字符串拼接，必须消费完整表达式且不执行任意代码。 |
| P2-9 | Application 使用受控保留调度器；测试可禁用启动清理，避免后台竞争与无管理 Executor。 |

## 4. 二次整合复核追加修复

- 焦点丢失先取消等待中的播放 Job，防止 stop 之后才 speak；延迟焦点事件不能终止未持焦点的新录音。
- 每次播放有独立 generation；旧 Job 的 finally 不得释放新播放焦点、停止新 TTS 或改写原声状态。
- SessionController 保留的旧入口也同步化，确认使用带 Token 的状态迁移。
- 一次性 UI 事件从无订阅者不保留的 SharedFlow 改为 Channel；事件携带 generation。
- 合成 warning 记为 SYNTHESIS，PCM/发声失败记为 PLAYBACK。
- 录音上限/错误提示不再被正常置信度渲染立即隐藏。
- 停止门闩提取为生产 `SessionOperationGate`，原“同构 AtomicBoolean 模拟测试”改为直接测试生产组件。
- AudioPlayback 增加 stopEpoch，创建期间的 stop 不再空跑；正常 MODE_STREAM stop 按官方文档播完最后缓冲区，新增创建期取消与正常 stop/不 flush 回归测试。
- 生产导出兼容测试显式断言默认 `unspecified` 失败、补录专业顾问严重度后通过，不用测试填值掩盖合规门禁。

## 5. 实际验证

| 项目 | 结果 |
|---|---|
| Android 全量单测 | `./gradlew :app:testDebugUnitTest --rerun-tasks`：33 个测试类，224 项，0 failure / 0 error / 0 skipped |
| Python 工具 | `python3 -m unittest tools.test_tools -v`：51/51 通过 |
| ASR 配置 | 5 项参数一致；受限表达式解析测试通过 |
| Lint | `lintDebug` 通过，0 Error / 18 Warning；警告为版本、ABI、资源和 KTX 建议，无 Lint baseline |
| Debug/Release | `assembleDebug`、`assembleRelease` 均通过 |
| APK 权限 | INTERNET / ACCESS_NETWORK_STATE / RECORD_AUDIO 三项必需权限齐备 |
| 差异格式 | `git diff --check` 通过 |

## 6. 尚未完成的交付门槛

- 当前无连接 Android 设备，`/dev/kvm` 不存在；**Gate 3 真机/模拟器验证未执行**。
- 真实麦克风、AudioTrack 阻塞中断、系统 TTS、音频焦点、native decode/release、旋转/后台、SAF、真实模型源下载及温升/耗电仍需设备证据。
- 默认 severity=`unspecified` 会被严格研究校验拒绝；只有真实专业顾问记录才能形成合规研究集，不能填默认值绕过。
- 尚无目标人群真实数据和可信 ASR 质量基线；本轮整改不构成识别准确率或可交付性的结论。
