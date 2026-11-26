# VoiceClear 数据集 Schema（目标人群：听障相关发音不清的普通话成人）

> 版本：v1.0  

> 配套文档：[数据采集协议](target-cohort-data-collection-protocol.md)；[山东方言采集与 A/B 评测补充协议](shandong-dialect-capture-evaluation.md)

> 状态：设计基线，待伦理/合规审查后使用

## 1. 文件组织

```

dataset/

├── manifest.json              # 数据集总索引：版本、划分、说话人清单、校验值

├── consent/                   # 授权记录（与音频分离，按受控字段存放）

│   └── <speakerId>.json

├── speakers/                  # 说话人元数据

│   └── <speakerId>.json

├── prompts/                   # 朗读提示句库

│   ├── prompts.json

│   └── shandong.json           # 山东方言语料句库；独立 ID、类别和版本

├── transcripts/               # 转写与意图基准

│   └── <sessionId>.jsonl

├── segments/                  # 音频片段（原始与 16k 单声道副本分开）

│   └── <sessionId>/

│       ├── raw/               # 原始设备格式，不做降噪

│       └── 16k/               # 16 kHz 单声道副本

└── splits/                    # 划分文件

    ├── train.txt

    ├── dev.txt

    └── test.txt

```

**硬性规则**：`splits/` 按**说话人**划分，`transcripts/` 按**会话**记录；

同一会话的所有片段必须整体落在同一个划分中。

## 2. 核心 Schema

### 2.1 说话人（speakers/<speakerId>.json）

```json

{

  "speakerId": "spk_001",

  "ageBand": "30-39",

  "gender": "female",

  "dialect": "标准普通话",

  "severityLevel": "L2",

  "severitySource": "professional_advisor",

  "hearingAid": { "worn": true, "type": "behind_the_ear" },

  "firstSessionDate": "2026-11-20",

  "sessionCount": 3,

  "speakingEnvironment": ["quiet", "mild_noise"],

  "speechProfileId": "mandarin",

  "localeTag": "zh-CN",

  "dialectGroup": "unspecified"

}

```

| 字段 | 必填 | 说明 |

|---|---|---|

| `speakerId` | 是 | 匿名标识，与姓名/手机号无关 |

| `severityLevel` | 是 | L1/L2/L3，由专业顾问判定 |

| `severitySource` | 是 | 判定来源 |

| `hearingAid` | 是 | 影响麦克风输入，需记录 |

| `ageBand` | 是 | 使用年龄段而非精确年龄 |

### 2.2 会话与片段（transcripts/<sessionId>.jsonl）

每行一个片段：

```json

{

  "segmentId": "seg_001",

  "sessionId": "ses_001",

  "speakerId": "spk_001",

  "sessionDate": "2026-11-20",

  "scene": "read",

  "promptId": "P0012",

  "referenceText": "我明天上午十点去医院复诊。",

  "referenceSource": "approved_prompt",

  "intendedText": null,

  "durationMs": 2450,

  "sampleRate": 48000,

  "channels": 1,

  "encoding": "pcm_s16",

  "noiseEstimateDb": 32.5,

  "distanceRange": "30-50cm",

  "fatigueLevel": 2,

  "deviceModel": "Pixel 7",

  "environment": "quiet",

  "qualityFlags": [],

  "annotatorId": "tr_01",

  "guideVersion": "v1.0",

  "consentFlags": ["research_use", "train_use"],

  "speechProfileId": "mandarin",

  "localeTag": "zh-CN",

  "dialectGroup": "unspecified"

}

```

**`scene` 取值**：`read`（朗读）、`spontaneous`（自发表达）、`dialogue`（对话片段）。

**语料分层字段**：

- `speechProfileId`：`mandarin` / `shandong`；旧数据缺失时按 `mandarin`；
- `localeTag`：当前统一为 `zh-CN`，不把方言分区编码成虚构 locale；
- `dialectGroup`：`jilu` / `zhongyuan` / `jiaoliao` / `unspecified`，旧数据缺失时按 `unspecified`；
- 山东语料分区暂缺可保留，但校验器会 WARN 提醒补录；非法值会使片段无效。
- 片段字段是分层评测的权威来源，speaker 中的同名字段只作汇总，不能覆盖逐段事实。

**`referenceText` 与 `intendedText` 的区别**（关键）：

- `referenceText`：朗读场景的标准文本，或自发表达中用户确认过的意图基准；

- `intendedText`：自发表达场景下用户口述的原话（未经规范化），用于观察识别错误；

- `referenceSource`：`approved_prompt` / `user_confirmed` / `unconfirmed`。

**`referenceSource = "unconfirmed"` 的片段不得纳入内容正确率统计**，

因为没有可信的意图基准（协议第 4.2 节）。

### 2.3 授权记录（consent/<speakerId>.json）

```json

{

  "speakerId": "spk_001",

  "consentVersion": "v1.0",

  "signedAt": "2026-11-20T10:00:00+08:00",

  "grants": {

    "research_use": true,

    "train_use": true,

    "cloud_upload": false,

    "voice_profile": false,

    "anonymous_report": true

  },

  "withdrawnAt": null,

  "withdrawalScopeNote": "撤回 train_use 后不再用于后续训练；已进入全局模型的部分无法保证移除",

  "compensation": "fixed_amount_agreed"

}

```

**`grants` 各字段独立**，不得默认全为 true。基本使用不依赖任何研究授权。

### 2.4 划分文件（splits/*.txt）

每行一个 speakerId：

```

# train.txt

spk_001

```

## 3. 清单文件（manifest.json）

```json

{

  "datasetVersion": "v0.1.0",

  "createdAt": "2026-11-25T15:00:00+08:00",

  "cohort": { "target": "听障相关发音不清的普通话成人", "minSdk": 26 },

  "speakers": [

    { "speakerId": "spk_001", "severityLevel": "L2", "sessions": 3, "segments": 42 }

  ],

  "splits": { "train": ["spk_001"], "dev": [], "test": [] },

  "totals": { "speakers": 1, "sessions": 3, "segments": 42, "hours": 0.4 },

  "annotationGuideVersion": "v1.0",

  "license": {

    "code": "internal-research-only",

    "commercialUse": false,

    "redistribution": false

  },

  "limitations": [

    "样本量极小，任何统计结论仅作探索性观察",

    "严重度分层覆盖不足，L3 层缺失",

    "尚无跨会话稳定性数据"

  ]

}

```

**`limitations` 字段是强制项**：数据集必须自述其局限，避免下游误用。

## 4. 质量校验规则

入库前自动校验，任一失败即拒绝：

1. `speakerId` 在 manifest 中存在；

2. `sessionDate` 存在且说话人已同意该场景；

3. `referenceSource != "unconfirmed"` 的片段必须有非空 `referenceText`；

4. `durationMs` > 0 且与音频实际时长一致（误差 < 50 ms）；

5. `consentFlags` 全部包含在 `consent/<speakerId>.json` 的 `grants` 中；

6. 片段所属说话人与划分文件一致；

7. `qualityFlags` 中的 `empty_capture` 片段不计入有效集；

8. 同一 `sessionId` 不跨划分出现。

9. `speechProfileId` / `localeTag` / `dialectGroup` 必须为上述合法值；山东分区未确认时给出补录提醒。

## 5. 与产品指标的衔接

| 字段 | 支撑的指标 |

|---|---|

| `referenceText` + `intendedText` | 中文 CER、关键内容错误率（8.3 节） |

| `severityLevel` | 分层退化分析（8.3 节公平性） |

| `scene` | 场景分层结果 |

| `speechProfileId` / `dialectGroup` | 普通话/山东语料及山东内部分区的 CER、关键错误分层 |

| `sessionDate` / `speakerId` | 跨会话稳定性（5.3 节消融） |

| `noiseEstimateDb` / `distanceRange` | 条件化结果（环境鲁棒性） |

| `fatigueLevel` | 疲劳对识别的影响 |

| `qualityFlags` | 失败案例归因 |
