#!/usr/bin/env python3
"""SenseVoice 配置参数对照检查。

把 Kotlin 源码里的关键推理参数与官方 sherpa-onnx Python API 的默认值逐项对照，
不一致就退出码非零。

背景：曾把 featureDim 误填 560（正确 80），native 侧抛维度不匹配异常后
**静默返回空结果**——不报错、不崩溃，只是识别不出任何内容。
这类错误在真机上极难定位，所以固化成自动检查。

用法：
    python3 tools/check_asr_config.py

退出码：0 全部一致；1 存在不一致；2 无法解析源码。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE = ROOT / "app/src/main/java/com/voiceclear/app/engine/SenseVoiceRecognitionEngine.kt"

# 参数名 -> 官方 Python API (sherpa-onnx) 的对应默认值
EXPECTED = {
    "SAMPLE_RATE": 16000,      # from_sense_voice(sample_rate=16000)
    "FEATURE_DIM": 80,         # from_sense_voice(feature_dim=80)
    "LANGUAGE": "",            # from_sense_voice(language="")
    "TOKENS_FILE": "tokens.txt",
    "MODEL_FILE": "model.int8.onnx",
}


def main() -> int:
    if not ENGINE.exists():
        print("错误：找不到引擎源码: " + str(ENGINE))
        return 2

    source = ENGINE.read_text(encoding="utf-8")

    problems = []
    print("SenseVoice 配置对照检查")
    print("=" * 56)
    print("对照基准: 官方 sherpa-onnx Python API 默认值")
    print()

    for name, expected in EXPECTED.items():
        pattern = r"const val " + name + r"\s*=\s*([^\n]+)"
        match = re.search(pattern, source)
        if not match:
            problems.append(name + "：源码中未找到常量定义")
            print("  %-14s 未找到" % name)
            continue

        raw = match.group(1).strip()

        if isinstance(expected, int):
            digits = re.search(r"-?\d+", raw)
            if not digits:
                problems.append(name + "：无法解析数值 " + raw)
                print("  %-14s 无法解析 %r" % (name, raw))
                continue
            actual = int(digits.group(0))
            ok = actual == expected
            print("  %-14s 实际=%-8s 期望=%-8s %s" % (name, actual, expected, "OK" if ok else "不一致"))
            if not ok:
                problems.append(name + "：实际 " + str(actual) + "，期望 " + str(expected))
        else:
            ok = raw.startswith(chr(34) + expected + chr(34))
            print("  %-14s 实际=%-14s 期望=%-14s %s" % (name, raw, repr(expected), "OK" if ok else "不一致"))
            if not ok:
                problems.append(name + "：实际 " + raw + "，期望 " + repr(expected))

    print()
    if problems:
        print("发现不一致:")
        for item in problems:
            print("  - " + item)
        print()
        print("这些参数错误会导致模型**静默返回空结果**，必须修正。")
        return 1

    print("配置校验通过（" + str(len(EXPECTED)) + " 项参数与官方一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
