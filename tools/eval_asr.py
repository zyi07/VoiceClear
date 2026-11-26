#!/usr/bin/env python3
"""VoiceClear 识别质量评测脚本。

计算中文 CER 与关键内容错误率，按说话人、严重度和提示类别分层输出。

用法：
    python3 tools/eval_asr.py --hyp hyp.jsonl --ref ref.jsonl [--manifest manifest.json]

unconfirmed 与 empty_capture 不进入正确率；重复 ID、缺失/多余 hyp、空规范化
参考文本、撤回/未授权以及数据集音频缺失均使评测非零退出。报告始终给出
matched / missing / extra 与有效参考覆盖率，不再把漏识别静默跳过。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

try:  # 支持 python3 tools/eval_asr.py 与包内 import 两种调用方式。
    from validate_dataset import Findings, check_consent, check_speakers, find_audio_file, load_json
except ModuleNotFoundError:  # pragma: no cover - 包导入路径由测试与真实 CLI 共同覆盖
    from tools.validate_dataset import Findings, check_consent, check_speakers, find_audio_file, load_json


NEGATION_MARKERS = ["不", "没", "没有", "别", "勿", "无", "未", "非", "莫"]
NUMBER_PATTERN = r"[0-9]+|[零一二三四五六七八九十百千万亿两]+"
PUNCTUATION = set("，。！？；：、,.!?;:（）()《》〈〉【】[]{}…—～~·「」『』" + "\"")
MIN_SAMPLES_FOR_CLAIM = 30
NUMBER_CONTEXT_PATTERN = r"[\u4e00-\u9fa5]{0,2}[零一二三四五六七八九十百千万亿两0-9]{1,6}[\u4e00-\u9fa5]{0,2}"


def normalize(text: str) -> str:
    """去除空白与标点，仅保留参与 CER 计算的字符。"""

    return "".join(ch for ch in text.strip() if not ch.isspace() and ch not in PUNCTUATION)


def levenshtein(ref: list, hyp: list):
    """返回 (距离, 替换数, 删除数, 插入数)。"""

    rows, cols = len(ref) + 1, len(hyp) + 1
    dist = [[0] * cols for _ in range(rows)]
    sub = [[0] * cols for _ in range(rows)]
    dele = [[0] * cols for _ in range(rows)]
    ins = [[0] * cols for _ in range(rows)]
    for i in range(1, rows):
        dist[i][0] = i
        dele[i][0] = i
    for j in range(1, cols):
        dist[0][j] = j
        ins[0][j] = j

    for i in range(1, rows):
        for j in range(1, cols):
            if ref[i - 1] == hyp[j - 1]:
                dist[i][j] = dist[i - 1][j - 1]
                sub[i][j] = sub[i - 1][j - 1]
                dele[i][j] = dele[i - 1][j - 1]
                ins[i][j] = ins[i - 1][j - 1]
            else:
                candidates = [
                    (dist[i - 1][j - 1] + 1, sub[i - 1][j - 1] + 1, dele[i - 1][j - 1], ins[i - 1][j - 1]),
                    (dist[i - 1][j] + 1, sub[i - 1][j], dele[i - 1][j] + 1, ins[i - 1][j]),
                    (dist[i][j - 1] + 1, sub[i][j - 1], dele[i][j - 1], ins[i][j - 1] + 1),
                ]
                best = min(candidates, key=lambda item: item[0])
                dist[i][j], sub[i][j], dele[i][j], ins[i][j] = best
    return dist[-1][-1], sub[-1][-1], dele[-1][-1], ins[-1][-1]


@dataclass
class Bucket:
    """某一分层下的累计统计。"""

    chars: int = 0
    errors: int = 0
    substitutions: int = 0
    deletions: int = 0
    insertions: int = 0
    segments: int = 0
    critical_total: int = 0
    critical_errors: int = 0
    examples: list = field(default_factory=list)

    def cer(self) -> float:
        return self.errors / self.chars if self.chars else 0.0

    def critical_error_rate(self) -> float:
        return self.critical_errors / self.critical_total if self.critical_total else 0.0


@dataclass
class SegmentOutcome:
    segment_id: str
    speaker_id: str
    severity: str
    category: str
    cer: float
    critical_error: bool
    ref_text: str
    hyp_text: str


@dataclass
class EvaluationResult:
    """对齐与指标共用的一份事实，避免主程序事后重新猜测覆盖率。"""

    outcomes: list[SegmentOutcome] = field(default_factory=list)
    eligible: int = 0
    matched: list[str] = field(default_factory=list)
    missing: list[str] = field(default_factory=list)
    extra: list[str] = field(default_factory=list)
    excluded: dict[str, int] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    @property
    def coverage(self) -> float:
        return len(self.matched) / self.eligible if self.eligible else 0.0


def extract_number_context(text: str) -> list:
    """提取数字及邻近限定词，用于识别上午/下午等语义变化。"""

    return [match.group(0) for match in re.finditer(NUMBER_CONTEXT_PATTERN, text)]


def has_critical_error(ref_text: str, hyp_text: str) -> bool:
    """判断是否发生关键内容错误：否定词或数字被改动。"""

    ref_norm = normalize(ref_text)
    hyp_norm = normalize(hyp_text)
    ref_negations = sum(ref_norm.count(marker) for marker in NEGATION_MARKERS)
    hyp_negations = sum(hyp_norm.count(marker) for marker in NEGATION_MARKERS)
    if ref_negations != hyp_negations:
        return True
    ref_numbers = re.findall(NUMBER_PATTERN, ref_text)
    hyp_numbers = re.findall(NUMBER_PATTERN, hyp_text)
    if ref_numbers != hyp_numbers:
        return True
    if ref_numbers and extract_number_context(ref_text) != extract_number_context(hyp_text):
        return True
    return False


def read_jsonl(path: Path) -> dict[str, dict[str, Any]]:
    """读取 JSONL；重复 segmentId 必须失败，不能由字典静默覆盖。"""

    records: dict[str, dict[str, Any]] = {}
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                record = json.loads(stripped)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_no} JSON 解析失败: {error}") from error
            if not isinstance(record, dict):
                raise ValueError(f"{path}:{line_no} 记录必须是 JSON 对象")
            segment_id = record.get("segmentId")
            if not isinstance(segment_id, str) or not segment_id.strip():
                raise ValueError(f"{path}:{line_no} 缺少有效 segmentId")
            if segment_id in records:
                raise ValueError(f"{path}:{line_no} segmentId 重复: {segment_id}")
            records[segment_id] = record
    return records


def evaluate_detailed(
    hyp: dict[str, dict[str, Any]],
    ref: dict[str, dict[str, Any]],
    *,
    consent: dict[str, dict[str, Any]] | None = None,
    dataset_dir: Path | None = None,
    speakers: dict[str, dict[str, Any]] | None = None,
    prompt_categories: dict[str, str] | None = None,
) -> EvaluationResult:
    """严格筛选并对齐 hyp/ref；旧式独立 JSONL 在未提供数据集时仍可评测。"""

    result = EvaluationResult()
    eligible_ids: set[str] = set()
    speakers = speakers or {}
    prompt_categories = prompt_categories or {}

    for segment_id, ref_record in ref.items():
        speaker_id = ref_record.get("speakerId") or "unknown"
        excluded = False

        # 撤回/未授权是数据合规错误，即使同时还有 unconfirmed/empty 也不能漏报。
        if ref_record.get("withdrawnAt"):
            result.errors.append(f"{segment_id} 的参考记录已撤回授权，不得评测")
            result.excluded["withdrawn"] = result.excluded.get("withdrawn", 0) + 1
            excluded = True
        if consent is not None:
            speaker_consent = consent.get(speaker_id)
            if speaker_consent is None:
                result.errors.append(f"{segment_id} 的说话人 {speaker_id} 缺少授权记录")
                excluded = True
            else:
                if speaker_consent.get("withdrawnAt"):
                    result.errors.append(f"{segment_id} 的说话人 {speaker_id} 已撤回授权，不得评测")
                    result.excluded["withdrawn"] = result.excluded.get("withdrawn", 0) + 1
                    excluded = True
                grants = speaker_consent.get("grants") or {}
                if not grants.get("research_use", False):
                    result.errors.append(f"{segment_id} 的说话人 {speaker_id} 未授权 research_use")
                    excluded = True

        source = ref_record.get("referenceSource")
        if source == "unconfirmed":
            result.excluded["unconfirmed"] = result.excluded.get("unconfirmed", 0) + 1
            excluded = True
        elif source not in (None, "approved_prompt", "user_confirmed"):
            result.errors.append(f"{segment_id} referenceSource 非法: {source!r}")
            excluded = True
        flags = ref_record.get("qualityFlags") or []
        if not isinstance(flags, list):
            result.errors.append(f"{segment_id} qualityFlags 必须是数组")
            excluded = True
            flags = []
        if "empty_capture" in flags:
            result.excluded["empty_capture"] = result.excluded.get("empty_capture", 0) + 1
            excluded = True

        if not excluded:
            eligible_ids.add(segment_id)

    result.eligible = len(eligible_ids)
    result.missing = sorted(eligible_ids - set(hyp))
    result.extra = sorted(set(hyp) - set(ref))
    result.matched = sorted(eligible_ids & set(hyp))
    for segment_id in result.missing:
        result.errors.append(f"参考片段缺少 hyp: {segment_id}")
    for segment_id in result.extra:
        result.errors.append(f"hyp 中存在多余 segmentId: {segment_id}")
    if not eligible_ids:
        result.errors.append("没有可评测片段：有效参考集为空")

    for segment_id in sorted(eligible_ids & set(hyp)):
        ref_record = ref[segment_id]
        hyp_record = hyp[segment_id]
        ref_text = ref_record.get("referenceText") or ""
        hyp_text = hyp_record.get("text") or ""
        if not isinstance(ref_text, str) or not isinstance(hyp_text, str):
            result.errors.append(f"{segment_id} 的 referenceText/text 必须是字符串")
            continue
        if not normalize(ref_text):
            result.errors.append(f"{segment_id} 的参考文本规范化后为空，拒绝计算虚假 CER=0")
            continue
        if dataset_dir is not None and find_audio_file(dataset_dir, ref_record) is None:
            result.errors.append(f"{segment_id} 对应音频不存在，不得纳入有效评测")
            continue
        hyp_speaker = hyp_record.get("speakerId")
        if hyp_speaker and ref_record.get("speakerId") and hyp_speaker != ref_record["speakerId"]:
            result.errors.append(f"{segment_id} 的 hyp/ref speakerId 不一致")
            continue

        speaker_id = ref_record.get("speakerId") or "unknown"
        speaker = speakers.get(speaker_id, {})
        severity = ref_record.get("severityLevel") or speaker.get("severityLevel") or "unknown"
        category = (
            ref_record.get("category")
            or prompt_categories.get(ref_record.get("promptId"))
            or "unknown"
        )
        ref_chars = list(normalize(ref_text))
        hyp_chars = list(normalize(hyp_text))
        distance, _, _, _ = levenshtein(ref_chars, hyp_chars)
        result.outcomes.append(SegmentOutcome(
            segment_id=segment_id,
            speaker_id=speaker_id,
            severity=severity,
            category=category,
            cer=distance / len(ref_chars),
            critical_error=has_critical_error(ref_text, hyp_text),
            ref_text=ref_text,
            hyp_text=hyp_text,
        ))

    return result


def evaluate(hyp: dict, ref: dict) -> list:
    """兼容原有生产函数：完整且无数据错误时返回 outcomes，否则失败关闭。"""

    result = evaluate_detailed(hyp, ref)
    if result.errors:
        raise ValueError("；".join(result.errors))
    return result.outcomes


def summarize(outcomes: list) -> Bucket:
    bucket = Bucket()
    for outcome in outcomes:
        ref_chars = len(normalize(outcome.ref_text))
        distance, subs, dels, ins = levenshtein(
            list(normalize(outcome.ref_text)), list(normalize(outcome.hyp_text))
        )
        bucket.chars += ref_chars
        bucket.errors += distance
        bucket.substitutions += subs
        bucket.deletions += dels
        bucket.insertions += ins
        bucket.segments += 1
        bucket.critical_total += 1
        if outcome.critical_error:
            bucket.critical_errors += 1
        if outcome.critical_error and len(bucket.examples) < 5:
            bucket.examples.append(f"    {outcome.segment_id}: 参考={outcome.ref_text} | 识别={outcome.hyp_text}")
    return bucket


def report_alignment(result: EvaluationResult) -> None:
    print(
        f"对齐：matched={len(result.matched)}  missing={len(result.missing)}  "
        f"extra={len(result.extra)}  eligible={result.eligible}"
    )
    print(f"有效参考覆盖率 = {result.coverage:.2%} ({len(result.matched)}/{result.eligible})")
    if result.missing:
        print("  missing IDs: " + ", ".join(result.missing))
    if result.extra:
        print("  extra IDs: " + ", ".join(result.extra))
    if result.excluded:
        excluded = "  ".join(f"{name}={count}" for name, count in sorted(result.excluded.items()))
        print("排除片段：" + excluded)


def report(outcomes: list) -> None:
    if not outcomes:
        print("没有可评测的片段。")
        return
    print("=" * 72)
    print("VoiceClear 识别质量评测报告")
    print("=" * 72)
    overall = summarize(outcomes)
    print(f"总体：片段 {overall.segments}  参考字数 {overall.chars}")
    print(f"  CER = {overall.cer():.4f}  ({overall.errors} 错 / {overall.chars} 字)")
    print(f"    替换 {overall.substitutions}  删除 {overall.deletions}  插入 {overall.insertions}")
    print(f"  关键内容错误率 = {overall.critical_error_rate():.4f} ({overall.critical_errors} / {overall.critical_total})")
    if overall.segments < MIN_SAMPLES_FOR_CLAIM:
        print(f"  [注意] 样本量 {overall.segments} < {MIN_SAMPLES_FOR_CLAIM}，结论仅为探索性观察。")

    by_speaker = defaultdict(list)
    for outcome in outcomes:
        by_speaker[outcome.speaker_id].append(outcome)
    print("-" * 72)
    print("按说话人分层（不得只看合并均值）")
    print("-" * 72)
    for speaker_id in sorted(by_speaker):
        bucket = summarize(by_speaker[speaker_id])
        severities = "/".join(sorted({item.severity for item in by_speaker[speaker_id]}))
        print(f"  {speaker_id} [{severities}]  片段 {bucket.segments}      CER {bucket.cer():.4f}  关键错误率 {bucket.critical_error_rate():.4f}")

    by_severity = defaultdict(list)
    for outcome in outcomes:
        by_severity[outcome.severity].append(outcome)
    print("-" * 72)
    print("按严重度分层")
    print("-" * 72)
    for severity in sorted(by_severity):
        bucket = summarize(by_severity[severity])
        print(f"  {severity}  片段 {bucket.segments}      CER {bucket.cer():.4f}  关键错误率 {bucket.critical_error_rate():.4f}")

    by_category = defaultdict(list)
    for outcome in outcomes:
        by_category[outcome.category].append(outcome)
    print("-" * 72)
    print("按提示类别分层")
    print("-" * 72)
    for category in sorted(by_category):
        bucket = summarize(by_category[category])
        print(f"  {category}  片段 {bucket.segments}      CER {bucket.cer():.4f}  关键错误率 {bucket.critical_error_rate():.4f}")

    failures = [item for item in outcomes if item.critical_error]
    if failures:
        print("-" * 72)
        print(f"关键内容错误示例（共 {len(failures)} 条，展示前 {min(5, len(failures))} 条）")
        print("-" * 72)
        for outcome in failures[:5]:
            print(f"  {outcome.segment_id}: 参考={outcome.ref_text} | 识别={outcome.hyp_text}")
        print()


def load_prompt_categories(dataset_dir: Path) -> dict[str, str]:
    path = dataset_dir / "prompts" / "prompts.json"
    if not path.is_file():
        return {}
    payload = load_json(path)
    prompts = payload.get("prompts", []) if isinstance(payload, dict) else []
    return {
        item["id"]: item["category"]
        for item in prompts
        if isinstance(item, dict) and item.get("id") and item.get("category")
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="VoiceClear 识别质量评测")
    parser.add_argument("--hyp", required=True, help="识别结果 JSONL")
    parser.add_argument("--ref", required=True, help="参考答案 JSONL")
    parser.add_argument("--manifest", help="数据集 manifest.json（启用授权、音频与分层元数据校验）")
    args = parser.parse_args(argv[1:])

    ref_path = Path(args.ref).resolve()
    try:
        hyp = read_jsonl(Path(args.hyp))
        ref = read_jsonl(ref_path)
    except (OSError, ValueError) as error:
        print(f"错误：{error}")
        return 2

    context_errors: list[str] = []
    consent: dict[str, dict[str, Any]] | None = None
    speakers: dict[str, dict[str, Any]] = {}
    prompt_categories: dict[str, str] = {}
    dataset_dir: Path | None = None
    try:
        if args.manifest:
            manifest_path = Path(args.manifest)
            manifest = load_json(manifest_path)
            if not isinstance(manifest, dict):
                raise ValueError(f"manifest 不存在或顶层不是对象: {manifest_path}")
            dataset_dir = manifest_path.resolve().parent
            limitations = manifest.get("limitations") or []
            if limitations:
                print("数据集自述局限（必须随结论一起引用）：")
                for item in limitations:
                    print(f"  - {item}")
                print()
        elif ref_path.parent.name == "transcripts":
            dataset_dir = ref_path.parent.parent

        if dataset_dir is not None:
            findings = Findings()
            consent = check_consent(dataset_dir, findings)
            speakers = check_speakers(dataset_dir, findings)
            context_errors.extend(findings.errors)
            prompt_categories = load_prompt_categories(dataset_dir)
    except (OSError, ValueError) as error:
        print(f"错误：读取评测数据集上下文失败: {error}")
        return 2

    result = evaluate_detailed(
        hyp,
        ref,
        consent=consent,
        dataset_dir=dataset_dir,
        speakers=speakers,
        prompt_categories=prompt_categories,
    )
    result.errors = context_errors + result.errors
    report_alignment(result)
    if result.errors:
        print(f"评测数据 ERROR ({len(result.errors)})：")
        for error in result.errors:
            print(f"  [E] {error}")
    report(result.outcomes)
    return 1 if result.errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
