# 山东方言采集与普通话 A/B 评测协议

## 1. 目标与模型边界

本协议为现有本地采集流程增加两种语料标记：`mandarin`（普通话）与 `shandong`（山东方言语料）。两者当前共用同一个 SenseVoice Small 模型、同一套特征参数和中文识别配置，`SpeechProfile.asrLanguage` 均为 `zh`，导出 `localeTag` 均为 `zh-CN`。

**山东方言 profile 不是方言专用模型，也不会切换成另一套声学模型。** 它用于选择采集句库、记录语料条件和分层评测，不代表模型已针对山东方言适配。不得把 profile 名称、句库切换或文本后处理写成识别率提升证据。本变更没有提供任何山东方言识别率提升的实测结论。

“山东方言”不是单一口音。采集时另记 `dialectGroup`，合法值为：

| 值 | 含义 |
|---|---|
| `jilu` | 冀鲁官话 |
| `zhongyuan` | 中原官话 |
| `jiaoliao` | 胶辽官话 |
| `unspecified` | 尚未由采集记录确认，允许暂存但必须提醒补录 |

分区只描述本次语料，不得写成虚构的语言标签；例如不能使用 `zh-SD` 代替 `zh-CN`。分区依据应来自说话人长期使用的本地话及采集记录，不能由 App 根据地理位置猜测。跨分区或语言背景复杂的说话人应按实际片段记录，无法确认时保留 `unspecified`。

## 2. 语料与导出协议

### 2.1 字段

`CaptureSegment` 与 `CaptureSpeaker` 均在原有字段末尾增加：

| 字段 | 默认值 | 约束 |
|---|---|---|
| `speechProfileId` | `mandarin` | 仅 `mandarin` / `shandong` |
| `localeTag` | `zh-CN` | 当前固定 `zh-CN` |
| `dialectGroup` | `unspecified` | 仅上表四种值 |

缺失字段的旧 JSON 按上述默认值读取。片段记录是评测分层的权威来源；说话人记录用于汇总，不能用说话人级标签覆盖同一说话人不同片段的实际条件。混合 profile 的数据必须逐段保留标记。

### 2.2 句库

- 普通话：`prompts/prompts.json`，沿用原类别与版本。
- 山东方言语料：`prompts/shandong.json`，独立 ID（`SD....`）、类别和版本。
- App assets 与 `tools/prompts/` 中同名文件必须逐字节一致，数据集导出同时携带两份句库。
- 山东句库当前 36 条，覆盖 `daily`、`negation`、`number`、`direction`、`medical`、`dialogue`。

提示文本使用自然书面中文，供本地人按自己真实、稳定的本地话自然朗读。**文本不是注音，不编写谐音字，不制造所谓统一山东词，也不要求说话人模仿其他地区或某种“标准山东话”。** 医疗句只是采集文本，不构成用药建议。

`referenceText` 保留预先批准的书面提示文本；ASR 的字符错误仍按该文本计算。若另行制作方言逐字转写或发音标注，应放在独立标注版本中，不得回写覆盖提示基准，也不得用同音替代字让 CER 人为降低。

### 2.3 采集操作

1. 录音开始前选择语料 profile；山东 profile 下再选择实际方言分区，可暂选“暂未确认”。
2. 选择当前句库中的提示句，说话人按本地自然发音朗读；自发表达仍需本人确认意图基准。
3. 录音中禁止切换 profile、分区或提示句。非 busy 状态切换后，App 重建会话控制器，避免新片段沿用旧元数据。
4. 同一次 A/B 记录设备、环境、日期、模型与配置；授权、撤回、音频保留及说话人划分继续遵循原采集协议。
5. 入库前运行 `python3 tools/validate_dataset.py DATASET`。山东分区为 `unspecified` 时保留数据并给出补录 WARN；非法 profile、locale 或分区是 ERROR。

## 3. 普通话与山东方言 A/B 方法

### 3.1 先区分两种问题

- **语料条件比较**：同一个未改模型的识别器，分别处理普通话与山东方言录音，比较错误分布。这是本轮支持的 A/B。
- **模型改动效果比较**：同一批音频分别由基线模型和候选模型识别，做配对比较。这需要独立模型版本与执行记录，本轮没有进行。

现有两份句库主要承担类别覆盖，并非完整的逐句配对集，因此直接把两个句库的总体 CER 相减会混入文本长度、类别和内容难度差异。正式内容对照须另先批准一小组**完全相同的书面句子**，在两种朗读条件下使用；不得事后挑选有利句子。

### 3.2 推荐配对设计

1. **同一说话人重复测量**：每位参与者在普通话、本人本地话两种条件下朗读同一批配对句；两种条件分别生成独立 segmentId 和完整元数据。
2. **控制顺序与学习效应**：按说话人随机或平衡安排 A/B 顺序，中间休息；记录疲劳。不要所有人都固定先普通话后山东方言。
3. **控制设备与环境**：优先同一设备、麦克风设置、距离、环境和降噪开关；无法一致时逐项记录并分层，不把设备差异归因于方言。
4. **冻结识别配置**：同一轮比较使用相同模型文件、采样率、特征维数、解码参数和文本规范化脚本。profile 只作为采集条件标签，不在识别后修改文本来制造差异。
5. **防止数据泄漏**：训练/开发/测试仍按说话人划分；同一人的普通话与山东方言片段不得跨训练和测试。测试提示不得用于训练或个性化注册。
6. **保留失败与覆盖情况**：缺失 hyp、多余 hyp、空基准、未确认意图和撤回授权按工具规则处理，不能只保留识别成功片段。

### 3.3 报告口径

对每个 profile，以及 profile 内每个 `dialectGroup`，至少报告：有效片段数、参考字符数、CER（替换/删除/插入）、关键内容错误率、缺失与覆盖率、逐说话人结果和失败示例。否定、数字、时间与医疗剂量错误应单独抽查。

配对句设计下可同时给出每位说话人的 `CER_shandong - CER_mandarin` 及配对区间；分区样本不足时只列原始结果与探索性观察。不得只报合并均值，也不得把不同句库、不同设备或不同说话人的差异解释为纯方言效应。当前没有执行上述 A/B 实测，因此没有可宣称的提升幅度。

运行分层评测：

```bash
python3 tools/eval_asr.py \
  --hyp HYP.jsonl \
  --ref DATASET/transcripts/SESSION.jsonl \
  --manifest DATASET/manifest.json
```

旧参考数据缺少 `speechProfileId` 时归入 `mandarin`，缺少 `dialectGroup` 时归入 `unspecified`。hyp 若显式携带上述元数据，必须与 ref 一致，避免把不同条件的样本错配。

## 4. 实现验证边界

本轮使用的 Android API 均为项目现有基线：`AlertDialog.Builder.setItems`、`MaterialButton`、`SharedPreferences`、assets 流与 `org.json`；目标仍为 minSdk 26 / targetSdk 36。Python 使用标准库 `unittest`，生产句库另由测试检查条数、类别、唯一 ID/文本及 assets/tools 同步。

静态检查、Robolectric、Python 测试与 APK 构建只能证明代码和数据契约；真实麦克风交互、录音中禁止切换、自然朗读可执行性及模型效果仍需设备与真实采集验证。

## 5. 核验来源

- [sherpa-onnx SenseVoice 预训练模型说明](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/sense-voice/pretrained.rst)：支持 `auto/zh/en/ko/ja/yue`，中文为普通话；2025-09-09 模型是粤语定向微调，不是山东方言模型。
- [sherpa-onnx Hotwords 文档](https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html)：热词仅支持 transducer 模型和 `modified_beam_search`，不适用于当前 SenseVoice + `greedy_search` 链路。
- [《山东方言的文化特征及其演变》（立命馆大学）](https://www.ritsumei.ac.jp/ir/isaru/assets/file/journal/27-4_04_MA.pdf)：山东方言分区的学术参考，包括冀鲁官话、中原官话和胶辽官话。
