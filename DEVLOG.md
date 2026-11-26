# VoiceClear 开发日志

## 2026-11-26 21:15 - 配置 GitHub Actions Debug APK 构建与 Release（0.12.0）

**做了什么：**

- 新增 `.github/workflows/android-release.yml`：支持 `workflow_dispatch` 与 `v*` 标签，构建 job 仅 `contents: read`，Release job 仅 `contents: write`。
- 云端执行 Python/Android 测试、Lint、Debug 构建、ASR/权限门禁、标签与版本一致性、Debug 签名验证、SHA-256、90 天 artifact 和 GitHub prerelease。
- 版本提升至 `versionCode=14 / versionName=0.12.0`，新增 `docs/releases/v0.12.0.md`，更新 README 与 CHANGELOG。
- 用户已明确选择：仅 Android Debug APK，使用调试签名并发布 Release；不作为正式上架包。
- 提交 hash: fc31404

**本地验证：**

- Python 51/51；Android 测试、Lint、Debug 构建、ASR/权限门禁全部通过。
- `apksigner verify` 通过；AAPT2 核实包名 `com.voiceclear.app`、versionCode 14、versionName 0.12.0。
- 工作流 YAML、权限结构与敏感信息扫描通过；Actions 使用核实的完整版本标签，不使用浮动分支。
- 仓库为 public，Linux runner 的 Actions 分钟按当前 GitHub 公共仓库规则不计私有额度。

**待做什么：**

- 推送 main 与 v0.12.0，跟踪云端 build/release job，核实 Release 附件、校验值和下载链接。
- Debug 包可安装测试，但真机 Gate 3 仍待执行；正式发布签名不在本次范围内。

---

## 2026-11-26 19:20 - 全量代码审查整改：会话、音频、模型与研究数据可信度

**做了什么：**

- 逐项处理 `CODE_REVIEW_2026-11-26.md` 的 11 项 P0、15 项 P1 和 9 项 P2，整改矩阵见 `CODE_REVIEW_REMEDIATION_2026-11-26.md`。
- 主流程：`SessionController` 增加 generation 与终态失效；生产 `SessionOperationGate` 隔离每会话停止锁；识别/模型加载移入 IO；ViewModel 统一释放资源；Activity 改用 StateFlow + Channel，事件同时校验 ID/generation。
- 音频与模型：修复 AudioRecord 自动停止/read/join 清理、AudioTrack 创建期 stop 空窗和部分写入；TTS utterance/初始化/停止语义、焦点真实状态、模型 SHA/staging/下载重试及 SenseVoice decode/release 串行化。
- 研究链路：授权默认拒绝、撤回贯穿导出/校验/评测、转写幂等、逐段 ZIP、真实元数据、保留删除墓碑和受控清理；导入器改为源校验→staging 复验→原子发布。
- 评测工具：严格处理 unconfirmed/empty/撤回、重复/缺失/多余 ID、音频存在与时长、空参考文本和覆盖率；ASR 配置检查改为安全的受限表达式解析。
- 二次只读复核追加修复了焦点丢失时尚未 speak、旧播放 finally 影响新播放、无订阅者事件丢失、合成/播放阶段误标和完成/停止线性化问题。
- 文档：更新 README、CHANGELOG、AUDIT_REPORT 的过期声明，新增 `docs/api-verification-2026-11-26.md`。
- 提交 hash: 5f32cfc

**实际验证：**

- `./gradlew :app:testDebugUnitTest --rerun-tasks`：33 个测试类，224 项，0 failure / 0 error / 0 skipped。
- `python3 -m unittest tools.test_tools -v`：51/51 通过。
- `./gradlew :app:lintDebug`：0 Error / 18 Warning；无 baseline，警告为版本、ABI、资源和 KTX 建议。
- `assembleDebug`、`assembleRelease`、`checkAsrConfig`、`checkApkPermissions`：全部通过。
- 产物：Debug APK 60,742,934 字节；Release 未签名 APK 58,534,825 字节。
- `git diff --cached --check`：通过；两份 docx、package 文件和 scripts/ 未纳入提交。

**待做什么：**

- Gate 3 未执行：移动设备桥无可用设备，且 `/dev/kvm` 不存在。真实麦克风、PCM/TTS 中断、焦点、native 并发、旋转/后台、SAF、真实模型源下载、温升与耗电仍需真机证据。
- 默认 severity=unspecified 的导出会按设计被研究校验拒绝；补录真实专业顾问记录后才可入库，不得为通过测试伪造 L2。
- 尚无目标人群真实数据或可信 ASR 质量基线；本轮只证明代码检查、自动化测试和构建结果，不宣称可交付或识别质量达标。

---

## 2026-11-17 11:45 - 诊断包分析：音频质量已正常；定位并修复状态机失同步（0.11.2）

**输入**：用户第 7 轮真机诊断包（HUAWEI PLA-AL10 / Android 16，4 轮录音，3489 字节）。
先校验完整性：`sha256sum -c SHA256SUMS.txt` 全部 OK，说明哈希功能本身工作正常。

**第一个结论是好消息：音频质量已经正常了。**

| # | 判定 | RMS | 峰值 | 有效占比 | 语种 | 字数 |
|---|---|---|---|---|---|---|
| 1 | NORMAL | 0.0586 | 0.5494 | 0.92 | zh | 4 |
| 2 | NORMAL | 0.0304 | 0.1384 | 0.88 | zh | 4 |
| 3 | NORMAL | 0.0322 | 0.1553 | 0.90 | yue | 6 |
| 4 | NORMAL | 0.0349 | 0.1496 | 0.90 | zh | 6 |

对比第 6 轮（7 次里 5 次 RMS ≤ 0.0001、峰值 ≤ 0.0002）：
现在 4 轮全部 RMS ≥ 0.03、有效占比 ≥ 0.88、0 轮静音。
**换成 `UNPROCESSED` 音源这个改动是有效的**——上次「环境音影响大」
的判断方向对了，AGC 确实在削弱信号。

性能同样正常：识别 125-167 ms（中位 139 ms），实时率 0.07-0.09，
模型加载 1482 ms，冷启动 198 ms。内存评估 `OK`（需要 492 MB，可用 2031 MB，总内存 15.6 GB）。

**第二个结论：每次识别成功后都弹「录音失败」，这是我上一版没修对。**

日志形态（4 轮完全一致）：

```
I/Recognition: 识别完成, 耗时=167ms, 文本长度=4
E/Main: 采集失败, code=capture_not_recording
E/Main: 识别流程错误: capture_not_recording - 当前不在录音状态
E/Error: 录音失败
```

我一开始以为又是并发双击，但 0.11.0 已经加了 CAS 幂等保护，
而且日志里没有 `W/Main: 忽略重复的停止请求`——说明第二次调用
通过了 CAS，即发生在第一次**完成之后**。

顺着这个线索查状态机，发现真正的根因：
**主流程从未调用 `sessionController.stopCapture()`**（只有采集模式调了）。
于是：

1. 点「停止」后采集器停了，状态机仍是 `RECORDING`；
2. 状态机不允许 `RECORDING → AWAITING_CONFIRMATION` 直跳，
   识别成功时 `onRecognitionReady()` 被拒绝，状态卡在 `RECORDING`；
3. 界面按**字面量**渲染成「处理中」并把按钮重新启用——
   界面和状态机各说各话；
4. 用户再点按钮，`onRecordClicked` 看到 `state == RECORDING`，
   于是又调一次停止；采集器已空 → 报错。

这也解释了为什么「幂等保护」没用：它防的是**并发**，而这里是**串行的第二次调用**。
上一轮我把这个问题归因为并发，方向就错了。

**修复三处：**

1. `MainViewModel.stopRecordingAndProcess()` 开头先 `sessionController.stopCapture()`；
2. `MainActivity` 一律按 `sessionController.state.value` 渲染，不再传字面量；
3. 录音入口加防御：状态说在录但 `audioCapture.isRecording()` 为 false 时，
   重置会话而不是调用 stop（防止同类失同步再次导致误报）。

**顺带修掉第二个问题：`benchmark-summary.json` 一直是 `{"note":"尚无样本"}`。**

原因：聚合只在会话收尾（确认/取消）时投喂，而这轮实测 4 次录音**一次都没点确认**——
用户是「录一句、看结果、接着录」。我自己设计的采集时机与实际用法不匹配。
改为：识别完成立即记录一条，合成/播放发生后用 `amendLast` 回填同一条；
未走到的阶段序列化为 `null` 而不是 0 毫秒（否则中位数会被 0 拉平）。

**记录一个待观察项**：4 次里有 1 次语种被判为 `<|yue|>`（粤语）。
文本仍正常产出，暂不影响使用，但要在后续数据里看是否影响识别质量。

**新增测试**：

- `MainFlowStateContractTest`（5 项）：把「停止采集必须先推离 RECORDING」钉成契约，
  并显式写了一个「漏调用时状态卡住」的用例——直接复刻这次的缺陷形态。
- `BenchmarkAmendTest`（6 项）：回填语义、空聚合器、零值不覆盖、混合会话只统计已到达阶段。

**验证**：180 项测试通过、lint 0 Error、构建 + 双门禁通过。

**待做什么**：装 0.11.2 复核「识别后不再弹录音失败」；
再跑 C 组音源对比，确认 UNPROCESSED 相对 VOICE_RECOGNITION 的优势幅度。

---

## 2026-11-17 11:20 - 出具真机测试手册；修正按钮文案与缓冲注释（0.11.1）

**触发**：用户要求「Espresso UI 这种需要真机做的，给我出具一个测试手册，写明测试方法和需要的测试结论，我来进行实测」。

**定位**：Espresso 在当前环境跑不了（无 `/dev/kvm`，无法起模拟器；也无可用真机）。
但更根本的是——**本项目的核心不确定性无法用断言表达**：
目标人群的识别可用性要靠人判断「这句话识别得对不对、能不能用」。
所以手册的价值不在于「替代自动化」，而在于把人的判断变成**可执行的步骤 + 可判定的标准 + 可比较的数据**。

**手册结构**（`docs/device-test-manual.md`，807 行）：

| 组 | 用例数 | 内容 | 谁能测 |
|---|---|---|---|
| A | 5 | 启动、模型加载、内存、权限、强杀恢复 | 任何人 |
| B | 6 | 完整流程、纠正、回放、取消、30 秒上限、误触 | 任何人 |
| C | 4 | 音源对比、距离梯度、噪声梯度 | 任何人 |
| D | 8 | 来电、蓝牙、耳机、双击、误点、后台、旋转、切换音源 | 任何人 |
| E | 3 | 20 次连续、30 分钟、诊断包校验 | 任何人 |
| F | 3 | 目标人群自然表达、关键信息保真、主观评分 | **仅目标用户 + 知情同意** |

每条用例统一写成：**目的 → 步骤 → 预期 → 要记录什么**，并配可打印的记录表。

**几个刻意的设计：**

1. **F 组不设通过线**。目标人群的识别可用性目前没有任何基线数据，
   本轮的任务是**建立第一个基线**，而不是去够一条凭空的分数线。
   硬给一个「准确率 ≥ X%」只会制造假进度。
2. **否定词与数字单独成一条用例（TC-F2）**，并规定无论其他用例多好，
   一旦「否定词丢失/数字改变」就单独报高危——这类错误会让表达的意思反转，
   风险远高于普通错字。有数据支撑这个担心：SenseVoice 不提供 token 级置信度，
   所以模型错了我们不会知道，只能靠用例兜住。
3. **测试顺序按价值密度排**，而不是按功能模块排：时间紧时可只做 B1×5 / C1C2 / E3 / D4D5D8 / A3。

**写手册过程中发现并修正的两个真问题**（都是核对代码时发现的，不是猜的）：

1. **按钮文案与实际行为不符**：文案是「按住说话」，实现却是两段式点击
   （`setOnClickListener` + `start()`/`stop()`）。按文案操作的人会长按后松手，
   以为录音坏了。这是 0.7.0 修为两段式时漏改的文案。已改为「开始录音」。
2. **缓冲倍数注释写错**：上一轮我按审计原文写「2 倍」，
   但实际常量是 `BUFFER_SIZE_FACTOR = 4`。已按实际值重写依据。

   —— 这两条说明：**审计报告里的代码片段也可能是过期的**，
   引用时必须回代码核对。上次我就是照着审计原文写了注释。

**验证**：169 项测试通过、lint 0 Error、`assembleDebug` + 双门禁通过；
APK 确认 `versionCode=12 / versionName=0.11.1`。

**待做什么**：等用户按手册实测回传诊断包；
重点看 `benchmark-summary.json` 与 C 组音源结论。

---

## 2026-11-17 10:20 - 落实审计 P1/P2 遗留项：内存保护、基准聚合、诊断包哈希、版本号

**触发**：用户要求「根据最新的 AUDIT_REPORT.md 意见，先调整一版出来」。

**先做时效性核对**，避免重复已完成的工作。审计报告列出的 P0/P1/P2 共 9 项，
其中 5 项在此前几轮已落地（真实 ASR 接入、MainActivity 重构、错误码统一、
数据过期清理、模型下载哈希校验）。本轮处理**剩下 4 项真没做的**：

| 审计条目 | 此前状态 | 本轮处置 |
|---|---|---|
| 4.1.2 低内存设备处理 | 未做 | `MemoryGuard` 加载前评估 |
| 4.2 性能基准（50 次取中位数） | 只有单次记录 | `BenchmarkAggregator` 滑动窗口 + P95 |
| 3.2 建议 3 导出哈希校验 | 只有模型下载校验 | 诊断包附 `SHA256SUMS.txt` |
| 4.1.1 缓冲倍数无说明 | 未做 | 补充依据注释 |

**为什么把「内存保护」排在最前**：
上一轮真机快照里 `vmMaxHeapMb=384`，而模型是 239 MB。
native 层分配失败不会给出可读错误，直接 abort 进程——
用户看到的是「闪退」，我们什么也拿不到。
现在加载前评估系统可用内存，不足就拒绝加载并提示「关闭后台应用后重试」，
并且**隐藏下载按钮**：模型已经下好了，缺的不是文件。
同时区分 `outOfMemory` 与普通加载失败——前者关后台可恢复，后者必须重下模型。

**基准聚合的口径**：审计建议「测试 50 次取中位数」。
单次识别耗时从 99 ms 到 170 ms 都出现过，都是真实记录但代表不了常态。
所以保留最近 50 次样本，同时输出中位数与 **P95**——
中位数是常态体验，P95 才是用户真正会觉得卡的那一刻。
明确标注「仅耗时统计，不含识别准确率」，避免被误读。

**诊断包哈希**：诊断包经聊天工具转手，没有校验就无法排除「分析的是被改过的数据」。
`SHA256SUMS.txt` 用标准 `sha256sum` 格式，接收方一条命令即可核对。
清单不包含自身（自指无法成立），这条在测试里钉住了。

**顺手修掉的一个真 bug**：`versionCode` 一直是 1，而内部已迭代到第十轮。
Android 用 `versionCode` 判断升级，一直是 1 会导致系统无法区分版本。
已改为 11，`versionName` 0.1.0 → 0.11.0，内部 `ENGINE_VERSION` 同步为 `v0.11`。

**新增文件**：

- `engine/MemoryGuard.kt` — 内存可行性评估（判定规则抽成纯函数便于测试）
- `session/BenchmarkAggregator.kt` — 滑动窗口 + 最近秩分位数
- `CHANGELOG.md` — 版本变更记录
- 测试 4 组：`MemoryGuardTest`、`BenchmarkAggregatorTest`、
  `DiagnosticChecksumTest`、`AudioQualityEdgeCaseTest`

**测试中发现的两个自身错误**（记下来避免重犯）：

1. P95 期望值写错：最近秩法下 `ceil(0.95*100)=95`，取第 95 小值即 94，
   我最初断言 95。是测试写得不对，实现是对的。
2. 同一测试里忘了默认窗口是 50——100 个样本被裁到 50 个，
   分位数自然对不上。改为显式 `windowSize = 100`。

**验证**：见本轮门禁输出（全量测试、lint、构建、双门禁、工具测试）。

---
## 2026-11-16 17:10 - 全量代码 review：定位并修复 10 处问题

**触发**：用户要求「跑一遍全量 review 代码，处理发现的问题点，重新打包」。

**Review 方法**：逐文件通读 MainViewModel（469 行）、MainActivity、AudioCapture、
ModelDownloadManager（校验路径）、DataRetentionPolicy、AppSettings、
VoiceClearApplication、PerformanceBenchmark，并用脚本核对每个 import 的实际使用次数。

**发现并修复的问题**（按严重度）：

| # | 严重度 | 问题 | 修复 |
|---|---|---|---|
| 1 | P0 | **stopInFlight 死锁**：`isCurrent==false` 提前返回与异常路径都不释放标志，识别中取消一次后，后续所有停止请求被 `忽略重复的停止请求` 拦截，**录音永远停不下来** | launch 体改 try/finally 统一释放，删除 4 处分散的 `set(false)` |
| 2 | P0 | **applyAudioSource 泄漏 AudioRecord**：直接替换实例，旧采集器持有的麦克风不释放，新采集器会初始化失败 | 替换前先 `cancel()` 旧采集器 |
| 3 | P0 | **录音中可切换音源**：进行中的录音被无声丢弃 | `applyAudioSource()` 改返回 Boolean，RECORDING 状态拒绝切换并提示 |
| 4 | P1 | 30 秒上限静默截断，用户继续说话丢字无提示 | 识别成功后检查 `durationMs >= MAX_DURATION_MS-200`，提示「超出部分未被录入」 |
| 5 | P1 | 确认页点「开始」得到误导性的「录音失败」 | 按状态机区分提示：AWAITING_CONFIRMATION/PLAYING → 「上一次表达还没完成」 |
| 6 | P1 | `cancel()` 与 `cancelSession()` 完全重复 | 删除 `cancel()`，取消时的播放停止/焦点释放并入 `cancelSession()` |
| 7 | P1 | 保留策略清理在 Activity.onCreate 主线程跑，**每次旋转屏幕重复执行** | 移到 `VoiceClearApplication.onCreate`，进程级一次 + 单线程后台执行 |
| 8 | P1 | `onCancelClicked` 里 `val sessionId = sessionController.sessionId` 赋值后未使用（死代码） | 随 #6 一并清理 |
| 9 | P2 | MainActivity 18 个未使用 import（ViewModel 拆分遗留） | 脚本核对使用次数后删除（误删 2 个在用符号，编译期发现并恢复） |
| 10 | P2 | `AudioCapture.stop()` 先 join 再停 recorder，阻塞中的 `read()` 可能吃满 2 秒超时 | 先 `recorder.stop()`（让 read 立即返回）再 join |

**回归测试**：新增 `StopReleaseTest`（4 项）——验证标志在「正常完成 / 会话过期提前返回 / 抛异常」三种路径下都被释放，防止死锁复发。这正是 #1 的缺陷形态。

**验证**：App 测试 129 → **133 项**全通过；Lint 0 Error；`assembleDebug` + `checkAsrConfig` + `checkApkPermissions` 通过；工具测试 15 项通过。

**Review 中看过但未改的**（判断为无问题）：`verifyChecksums` 的双策略（大小不符拒绝/校验和不符放行）、归档下载的 `finally { archive.delete() }`、`AppSettings` 的 SharedPreferences 用法、`PerformanceBenchmark` 的 RTF 计算。

---

## 2026-11-16 07:45 - 真机日志分析：环境音影响的三类根因定位与修复

**输入**：用户反馈「环境音的影响还是比较大的」，附第 6 轮真机诊断包
（华为 PLA-AL10 / Android 16，7 次录音）。

**先看数据，不猜**：

| 录音 | 语言 | 字数 | 时长 | RMS | 峰值 |
|---|---|---|---|---|---|
| 1 | en | 5 | 960ms | 0.0000 | 0.0001 |
| 2 | en | 2 | 1440ms | 0.0446 | 0.3828 |
| 3 | ko | 2 | 3200ms | 0.0000 | 0.0000 |
| 4 | en | 2 | 2560ms | 0.0000 | 0.0001 |
| 5 | zh | 2 | 1280ms | 0.0063 | 0.0580 |
| 6 | zh | 2 | 640ms | 0.0000 | 0.0000 |
| 7 | en | 2 | 2720ms | 0.0000 | 0.0002 |

**性能完全达标**（新增的基准工具起效了）：
实时率 0.06-0.13（比实时快 8-16 倍）、识别 80-170ms、模型加载 1158ms、冷启动 138ms。
→ **瓶颈不在算力，全在音频质量。**

**定位到三个独立问题：**

### 问题 1：麦克风几乎收不到声音（7 次里 5 次）

RMS ≤ 0.0001，峰值 ≤ 0.0002——这不是「环境音干扰」，是**几乎完全无信号**。
模型在这种输入上产生**幻觉输出**：识别出 `<|en|>`、`<|ko|>` 这类无意义内容。
用户看到的「识别不准」，有一大半其实是**在跟幻觉较劲**。

唯一一次真正录到声音（RMS=0.0446、峰值=0.3828）识别出的是 2 个字——
说明即使信号正常，识别质量也仍有问题。

**修复**：
- 新增 `AudioQuality`（`engine/AudioQuality.kt`）：
  RMS / 峰值 / 有效占比三项指标 + 三档判定（静音 / 偏弱 / 正常）。
  阈值**直接来自这批实测数据**：静音线 0.001、偏弱线 0.01、正常参考 0.0446。
- 识别前先做质量分析，**静音就不送模型**，直接提示
  「没有听到声音。请检查麦克风是否被遮挡，或换个安静的环境再试」。
  这样用户看到的是可行动的建议，而不是一句莫名其妙的乱码。
- 新增错误码 `capture_silent`。

### 问题 2：音源的 AGC 可能正在削弱目标人群的辅音

查 Android 官方文档确认三个音源的语义：
- `UNPROCESSED`："tuned for unprocessed (raw) sound if available" —— 原始声音
- `VOICE_RECOGNITION`："tuned for voice recognition" —— 为识别调优，通常启用 AGC 与降噪
- `MIC`：普通麦克风

原实现固定用 `VOICE_RECOGNITION`。**AGC 会把微弱的辅音当成噪声压掉**——
对发音清晰的人是优点，对**发音不清**的人恰恰是有害的。
这可能正是「环境音影响大」的机制之一：环境稍吵 → AGC 判定为噪声 → 一起压掉 → 连人声也被削弱。

**修复**：把音源做成**用户可切换**（新增 `AudioSourceOption` + `AppSettings` + 设置界面）。
默认改为 `UNPROCESSED`。

**为什么让用户自己选而不是开发者定**：哪种音源对「发音不清」的人更好，
无法由开发者替目标人群决定——必须在他们自己的设备上、用他们自己的语音实测对比。
这是把「谁更适合」的决定权交还给使用者。

### 问题 3：每次识别后都报 capture_not_recording

日志中每条 `I/Recognition: 识别完成` 后面都跟着 `E/Main: 采集失败, code=capture_not_recording`。

根因：按钮双击让**两个协程并发**进入 `stopRecordingAndProcess()`，
第二个 `audioCapture.stop()` 时 `current` 已被第一个置空 → 返回 `ERR_NOT_RECORDING`。

**修复**：用 `AtomicBoolean` 做幂等保护，第二次请求直接忽略并记日志。
测试 3 项，含 16 线程并发下「只有一个赢家」。

**本轮新增：**

| 文件 | 作用 |
|---|---|
| `engine/AudioQuality.kt` | 音频质量分析与三档判定 |
| `engine/AudioSourceOption.kt` | 三种音源选项与官方语义说明 |
| `session/AppSettings.kt` | 设置持久化（音源、降噪开关） |
| `capture/DataRetentionPolicy.kt` | （上一轮）数据保留 |
| `MainViewModel.kt` | 接入质量分析与幂等保护 |
| `MainActivity.kt` | 设置对话框、按错误码提示 |
| `res/layout/activity_main.xml` | 新增「麦克风设置」按钮 |

**测试**：App 109 → 128 项（新增 19 项），全部通过；Lint 0 Error；双门禁通过。

**下一步需要用户配合**：用**同一句话**在两种音源下各录 3-5 次，
对比哪个识别更准。这是在真实设备上确定最佳音源的唯一可靠办法。

---

## 2026-11-15 16:25 - 依据审计报告整改：错误码统一、数据保留、完整性校验、性能基准、MainActivity 拆分

**依据**：`AUDIT_REPORT.md`（2026-11-15）。

**先做时效性核实**——审计报告写于端侧引擎接入之前，若照单全收会做无用功：

| 审计结论 | 核实结果 | 处置 |
|---|---|---|
| 🔴 还在用假引擎 | **已过时**，已接入 SenseVoice 官方 AAR | 跳过 |
| 30 项测试 | **已过时**，实际 72 项 | 跳过 |
| 缺少真机验证 | **部分过时**，已做 5 轮联调 | 持续 |
| MainActivity 过长 | **成立**，642 行 | 实施 |
| 统一错误码体系 | **成立**，29 个码分散 5 个文件 | 实施 |
| 数据过期清理 | **成立** | 实施 |
| 导出哈希校验 | **成立** | 实施 |
| 证书锁定 | 成立但**有意暂缓**（见下） | 不实施 |
| 文件加密 | 成立但需与撤回机制一并设计 | 暂缓 |

**本轮实施的五项：**

1. **统一错误码体系**（`session/ErrorCodes.kt`）
   - 29 个错误码集中定义，命名统一为 `<域>_<原因>`（capture/asr/tts/focus/model/session）。
   - 各引擎的 `companion object` 保留原常量名但指向集中定义，**不破坏既有调用点**。
   - 附带能力：`isRetryable()` 区分可重试与不可重试；`domain()` 提取所属环节；
     `describe()` 给中文说明，用于诊断包汇总。
   - 测试 6 项，含「所有码唯一」「所有码有说明」「可重试分类合理」。

2. **数据保留策略**（`capture/DataRetentionPolicy.kt`）
   - 录音按天保留（默认 30 天），过期自动删除并写清理日志留痕。
   - **元数据（转写/同意书/说话人档案）不自动删除**——它们是研究记录与合规凭据，
     删除权属于用户，由「清空采集数据」显式执行。
   - 程序按时间偷偷删掉合规凭据会破坏伦理审查要求的可追溯性，因此这条是硬约束。
   - 测试 7 项，含「元数据永不被自动删除」。

3. **模型完整性校验**（`engine/FileIntegrity.kt`）
   - 下载后校验 SHA-256。两个来源的实测值：
     ModelScope `6887cdd6...`、GitHub `c71f0ce0...`；
     两者的 `tokens.txt` **完全相同**（`f449eb28...`）。
   - **策略刻意区分两种不符**：大小也不符 → 一定是损坏，删除并降级到下一个源；
     大小相符但校验和不同 → 可能是上游更新，**放行并记警告**。
     硬性拒绝会导致上游一更新模型就完全无法下载。

4. **性能基准采集**（`session/PerformanceBenchmark.kt`）
   - 输出实时率（RTF = 推理耗时 / 音频时长）与体验分档（优秀/良好/可用/偏慢）。
   - 诊断包新增 `benchmark.json`，每次测试都能得到**可比较**的数字，
     不再依赖主观感受判断「快了多少」。
   - 新增 `VoiceClearApplication` 记录进程启动时刻，用于测冷启动；
     崩溃处理器也移到这里安装（比放在 Activity 里更早，能覆盖 Activity 创建期崩溃）。

5. **MainActivity 拆分**（`MainViewModel.kt`）
   - 抽出 ViewModel 承载会话状态机、引擎生命周期、协程编排与指标采集；
     Activity 只负责把状态映射到视图。
   - **642 → 435 行**（ViewModel 395 行）。
   - ViewModel 跨配置变更存活，旋转屏幕不会中断正在进行的识别。
   - 测试 10 项。

**有意暂缓的两项（并说明理由）：**

- **证书锁定**：模型来自 GitHub Release 与 ModelScope，两者都可能出现证书轮换。
  pin 一旦过期而未同步更新，会导致模型**完全无法下载**——这是比"中间人攻击"
  更现实的风险。当前用 SHA-256 校验替代，覆盖了「文件被篡改」这一主要风险。
- **文件加密**：采集数据目前明文存储在应用私有目录（依赖系统级文件加密）。
  加密需与导出流程、同意书撤回机制一并设计，否则会出现「导出的文件解不开」
  或「撤回时删不干净」的新问题。建议在合规主体与伦理审查路径确定后一并处理。

**验证结果**：

- App 测试 **109 → 119 项**全通过；Lint 0 Error；双构建门禁通过。

---

## 2026-11-14 13:50 - 修正 SenseVoice featureDim（560→80），识别终于能出文本

**问题现象：**

用户实测报告仍无法正常使用。诊断包显示录音与模型都正常，但识别结果为空。

**诊断包的证据链：**

```
I/Capture: 录音已开始, 采样率=16000, 缓冲区=5120 字节
I/Capture: 录音已停止, 时长=2560ms, 字节=81920          ← 录音正常，2.56 秒
I/SenseVoice: 识别完成, 语言=, 文本长度=0, 音频时长=2ms, RMS=0.0440, 峰值=0.4175
```

- `RMS=0.0440`、`峰值=0.4175` → **确实录到了声音**（不是静音）；
- `语言=` 为空、`文本长度=0` → 模型连语言标签都没输出；
- 录音流程修复**已生效**（2.56 秒，不是 60 秒）。

**根因（用官方 Python API 做参数对照定位）：**

官方 `sherpa_onnx.OfflineRecognizer.from_sense_voice` 的默认参数：

```
feature_dim = 80          ← 我写的是 560
language   = ''          ← 我写的是 "zh"
```

实测四种组合（同一模型、同一音频）：

```
feature_dim= 80 language=''     -> '开饭时间早上9点至下午5点。'   ✅
feature_dim= 80 language='zh'   -> '开饭时间早上9点至下午5点。'   ✅
feature_dim=560 language=''     -> ''                              ❌
feature_dim=560 language='zh'   -> ''                              ❌
```

native 侧的真实报错（在官方 Python 里能看到，Android 上被吞掉了）：

```
Got invalid dimensions for input: x for the following indices
 index: 2 Got: 3920 Expected: 560
Return an empty result
```

**异常被 native 层捕获后静默返回空结果**——不崩溃、不报错、不打日志。
这就是它在真机上表现为「完全无法使用」而毫无线索的原因。

**修复：**

- `FEATURE_DIM`：560 → **80**（sherpa-onnx 对 SenseVoice 的特征维度约定，
  与模型 ONNX 内部的 560 不是同一个概念）；
- `LANGUAGE`："zh" → **""**（空表示自动检测，与官方默认一致，
  保留模型的多语种能力）；
- 顺带修正日志里的音频时长计算：`samples.size / SAMPLE_RATE` 是整数除法，
  40960 点 ÷ 16000 得 2（被截断），应写成 `samples.size * 1000L / SAMPLE_RATE`。

**修复后的配置已用官方 Python API 完整复现验证：**

```
zh.wav: dur=5.59s rms=0.1321 decode=262ms text='开饭时间早上9点至下午5点。'
en.wav: dur=7.15s rms=0.1782 decode=348ms text='The tribal chieftain called for the boy...'
```

中英文都正确识别，耗时 262–348 ms。

**流程改进：把这类错误挡在构建期。**

这类「静默失败」的配置错误靠人工 review 几乎不可能发现，因此固化两道保障：

1. `tools/check_asr_config.py`：把 Kotlin 源码里的推理参数与官方 Python API
   默认值逐项对照，不一致就退出码非零；
2. Gradle 任务 `:app:checkAsrConfig` 作为构建门禁。

**两道保障都实测有效**：

- 单元测试 `SenseVoiceConfigTest`（5 项）锁定关键参数；
- 故意把 FEATURE_DIM 改回 560 后，检查脚本报「实际 560，期望 80」并退出码 1，
  恢复后重新通过。

**遗留：**

- 目标人群（听障相关发音不清）的识别准确率仍无数据——
  这次修的是「完全识别不出」，不是「识别不准」。

---

## 2026-11-14 12:10 - 修复录音流程：改为「点击开始/再次点击停止」，消除 60 秒空转

**问题现象：**

用户实测（华为 PLA-AL10 / Android 16）报告：模型下载完成、引擎加载成功，
但识别结果始终为空。

**诊断包给出的关键证据：**

```
I/SenseVoice: 模型加载成功, 模型=.../model.int8.onnx, 词表=.../tokens.txt
I/Main: 识别引擎就绪: sherpa-sensevoice-zh
I/Capture: 采集完成, 耗时=60253ms, 时长=59840ms, 采样率=16000
I/SenseVoice: 识别完成, 语言=, 文本长度=0
I/Recognition: 识别完成, 耗时=172ms, 置信度=unknown, 文本长度=0
```

**根因：录音流程的架构缺陷（不是小 bug）。**

`采集耗时=60253ms` 直接暴露了问题：一次「说一句话」不该花 60 秒。

原实现把采集写成了**阻塞方法**：

```kotlin
fun capture(sessionId: String, maxDurationMs: Long = 60_000L): CaptureResult {
    while (System.currentTimeMillis() - startedAt < maxDurationMs) {
        val read = recorder.read(readBuffer, 0, readBuffer.size)   // 一直读到 60 秒
        ...
    }
}
```

而 `MainActivity.startRecording()` 只切换 UI 状态、**根本不启动录音**；
真正的录音发生在 `stopRecordingAndProcess()` 里——也就是用户点「停止」时
**才开始录**，然后**阻塞录满 60 秒**。

后果：

1. 用户点「停止」后还要再等一分钟才有结果；
2. 录进去 59840ms 的音频，其中绝大部分是用户已经说完后的静音；
3. 模型对大段静音返回空文本（`文本长度=0`）；
4. 于是表现为「识别完全不可用」。

**修复：改为两段式异步采集。**

- `AudioCapture` 重写为 `start()` / `stop()` / `cancel()`：
  - `start()` 立即启动 `AudioRecord.startRecording()` 并开后台读取线程，**立即返回**；
  - `stop()` 置停止标志、等待读取线程收尾（最多 2 秒）、返回音频；
  - 保留 30 秒上限，防止用户忘记停止。
- `MainActivity.startRecording()` 现在**真正启动录音**；
  `stopRecordingAndProcess()` 只负责停止并取回音频。
- 采集模式 `CaptureActivity` 同样改为两段式（按钮文案在「开始录音/停止录音」间切换）。

**顺带补充的诊断能力：**

- 引擎识别时记录音频的 **RMS 与峰值**。下次若结果为空，可据此区分
  「根本没录到声音」（RMS≈0）与「录到了但模型没认出」（RMS 正常）。
- 识别结果为空时给出专门提示（区别于「未采集到有效语音」）。

**过程中修正的真实问题：**

1. 生成 Kotlin 代码时在字符串拼接末尾多了一个 `+`，导致
   `Syntax error: Expecting an element`——已定位到 AudioCapture 第 213 行修正。
2. 能量计算里 `peak` 声明为 Double 却赋了 Float——改为全程 Double。
3. 旧的 `AndroidRuntimeBehaviorTest` 仍调用已删除的 `capture()`，
   改为测试新的 `start()` / `stop()` 语义，并**新增回归防护**：
   断言 `MAX_DURATION_MS` 是有限值，防止再次出现「无上限阻塞」。

**仍未验证：**

- 真机上实际录到的时长与 RMS——这需要用户复测。
- 目标人群的识别准确率仍无数据。

---

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
