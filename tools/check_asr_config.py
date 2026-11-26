#!/usr/bin/env python3
"""SenseVoice 配置参数对照检查。

把 Kotlin 源码里的关键推理参数与官方 sherpa-onnx Python API 的默认值逐项对照，
不一致就退出码非零。常量右值由受限解析器求值，不使用 eval/exec，也不接受
函数调用、属性访问或字符串模板。

用法：
    python3 tools/check_asr_config.py

退出码：0 全部一致；1 存在不一致或非法表达式；2 无法读取源码。
"""

from __future__ import annotations

import json
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

_TOKEN = re.compile(
    r"(?P<number>0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*[lL]?|\d(?:_?\d)*[lL]?)"
    r"|(?P<string>\"(?:\\[\s\S]|[^\"\\\r\n])*\")"
    r"|(?P<operator>[+\-*/%()])"
)


class KotlinConstantParser:
    """只解析 Kotlin const val 的安全子集，并要求完整消费输入。"""

    def __init__(self, source: str):
        self.source = source
        self.tokens = self._tokenize(source)
        self.position = 0

    @staticmethod
    def _tokenize(source: str) -> list[tuple[str, str]]:
        tokens: list[tuple[str, str]] = []
        position = 0
        while position < len(source):
            # 空白与注释不是表达式语义；字符串由下面的单一 token 完整处理。
            if source[position].isspace():
                position += 1
                continue
            if source.startswith("//", position):
                newline = source.find("\n", position + 2)
                position = len(source) if newline < 0 else newline + 1
                continue
            if source.startswith("/*", position):
                end = source.find("*/", position + 2)
                if end < 0:
                    raise ValueError("块注释未闭合")
                position = end + 2
                continue
            match = _TOKEN.match(source, position)
            if match is None:
                raise ValueError(f"不支持的 Kotlin 常量语法，位置 {position}: {source[position:position + 20]!r}")
            kind = match.lastgroup
            value = match.group(0)
            if kind == "string" and "$" in value:
                # 即使不执行，也不能把 Kotlin 字符串模板误当成普通字面量。
                raise ValueError("不支持字符串模板")
            tokens.append((kind, value))
            position = match.end()
        return tokens

    def parse(self) -> int | str:
        if not self.tokens:
            raise ValueError("常量表达式为空")
        value = self._additive()
        if self.position != len(self.tokens):
            raise ValueError(f"常量表达式含多余内容: {self.tokens[self.position][1]!r}")
        return value

    def _peek(self, operator: str) -> bool:
        return (
            self.position < len(self.tokens)
            and self.tokens[self.position] == ("operator", operator)
        )

    def _take(self, operator: str) -> bool:
        if self._peek(operator):
            self.position += 1
            return True
        return False

    def _additive(self) -> int | str:
        value = self._multiplicative()
        while self.position < len(self.tokens) and (
            self._peek("+") or self._peek("-")
        ):
            operator = self.tokens[self.position][1]
            self.position += 1
            right = self._multiplicative()
            if isinstance(value, str) or isinstance(right, str):
                if operator != "+" or not isinstance(value, str) or not isinstance(right, str):
                    raise ValueError("字符串仅支持与其他字符串进行 + 拼接")
                value = value + right
            else:
                value = value + right if operator == "+" else value - right
        return value

    def _multiplicative(self) -> int | str:
        value = self._unary()
        while self.position < len(self.tokens) and (
            self._peek("*") or self._peek("/") or self._peek("%")
        ):
            operator = self.tokens[self.position][1]
            self.position += 1
            right = self._unary()
            if isinstance(value, str) or isinstance(right, str):
                raise ValueError("字符串不支持乘除或取余")
            if operator == "*":
                value *= right
                continue
            if right == 0:
                raise ValueError("除数不能为零")
            # Kotlin 整数除法向零截断，余数符号跟随被除数；不能用 Python // 代替。
            dividend = value
            quotient = abs(dividend) // abs(right)
            if (dividend < 0) != (right < 0):
                quotient = -quotient
            value = quotient if operator == "/" else dividend - quotient * right
        return value

    def _unary(self) -> int | str:
        if self._take("+"):
            value = self._unary()
            if isinstance(value, str):
                raise ValueError("字符串不支持一元 +")
            return value
        if self._take("-"):
            value = self._unary()
            if isinstance(value, str):
                raise ValueError("字符串不支持一元 -")
            return -value
        return self._primary()

    def _primary(self) -> int | str:
        if self.position >= len(self.tokens):
            raise ValueError("常量表达式意外结束")
        kind, token = self.tokens[self.position]
        self.position += 1
        if kind == "number":
            cleaned = token.replace("_", "")
            if cleaned[-1:].lower() == "l":
                cleaned = cleaned[:-1]
            return int(cleaned, 0)
        if kind == "string":
            try:
                return json.loads(token)
            except json.JSONDecodeError as error:
                raise ValueError(f"字符串字面量非法: {error.msg}") from error
        if kind == "operator" and token == "(":
            value = self._additive()
            if not self._take(")"):
                raise ValueError("括号未闭合")
            return value
        raise ValueError(f"不支持的常量 token: {token!r}")


def parse_kotlin_constant(source: str) -> int | str:
    """安全求值整数/字符串常量表达式；任何未支持语法均明确失败。"""

    return KotlinConstantParser(source).parse()


def check_source(source: str) -> list[str]:
    """检查一份 Kotlin 源码，返回人类可读问题并打印逐项结果。"""

    problems: list[str] = []
    for name, expected in EXPECTED.items():
        pattern = r"\bconst\s+val\s+" + re.escape(name) + r"\s*=\s*([^\n]+)"
        match = re.search(pattern, source)
        if not match:
            problems.append(name + "：源码中未找到常量定义")
            print("  %-14s 未找到" % name)
            continue
        raw = match.group(1).strip()
        try:
            actual = parse_kotlin_constant(raw)
        except ValueError as error:
            problems.append(name + f"：无法解析 {raw!r}（{error}）")
            print("  %-14s 无法解析 %r" % (name, raw))
            continue
        type_matches = isinstance(actual, type(expected)) and not isinstance(actual, bool)
        ok = type_matches and actual == expected
        print("  %-14s 实际=%-14r 期望=%-14r %s" % (name, actual, expected, "OK" if ok else "不一致"))
        if not ok:
            problems.append(name + "：实际 " + repr(actual) + "，期望 " + repr(expected))
    return problems


def main() -> int:
    if not ENGINE.exists():
        print("错误：找不到引擎源码: " + str(ENGINE))
        return 2
    try:
        source = ENGINE.read_text(encoding="utf-8")
    except OSError as error:
        print(f"错误：无法读取引擎源码: {error}")
        return 2

    print("SenseVoice 配置对照检查")
    print("=" * 56)
    print("对照基准: 官方 sherpa-onnx Python API 默认值")
    print()
    problems = check_source(source)
    print()
    if problems:
        print("发现不一致:")
        for item in problems:
            print("  - " + item)
        print()
        print("这些参数错误或无法安全解析，必须修正后再运行识别。")
        return 1
    print("配置校验通过（" + str(len(EXPECTED)) + " 项参数与官方一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
