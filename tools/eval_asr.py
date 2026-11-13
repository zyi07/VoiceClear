#!/usr/bin/env python3

"""VoiceClear 识别质量评测脚本。



计算中文 CER 与关键内容错误率，按说话人与严重度分层输出。



用法：

    python3 tools/eval_asr.py --hyp hyp.jsonl --ref ref.jsonl [--manifest manifest.json]



设计要点：

- CER 按字符计算，去除标点与空白；

- 关键内容错误单独统计（否定词、数字），不与普通字符错误混同；

- 严格按说话人分层输出，不合并均值掩盖退化；

- 样本量小于阈值时明确标注为探索性观察，不作统计推断。

"""



from __future__ import annotations



import argparse

import json

import re

import sys

from collections import defaultdict

from dataclasses import dataclass, field

from pathlib import Path





# 需要保持一致的否定词：命中数量变化即视为关键内容错误

NEGATION_MARKERS = ["不", "没", "没有", "别", "勿", "无", "未", "非", "莫"]



# 高风险数字：数量、金额、时刻、剂量

NUMBER_PATTERN = r"[0-9]+|[零一二三四五六七八九十百千万亿两]+"



PUNCTUATION = set("，。！？；：、,.!?;:（）()《》〈〉【】[]{}…—～~·「」『』" + "\"")



MIN_SAMPLES_FOR_CLAIM = 30





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

                dist[i][j] = best[0]

                sub[i][j] = best[1]

                dele[i][j] = best[2]

                ins[i][j] = best[3]



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





NUMBER_CONTEXT_PATTERN = r"[\u4e00-\u9fa5]{0,2}[零一二三四五六七八九十百千万亿两0-9]{1,6}[\u4e00-\u9fa5]{0,2}"


def extract_number_context(text: str) -> list:
    """提取数字及其后紧邻的限定词，用于识别「上午/下午」这类语义变化。"""
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



    # 中文数字串相同但语境词变化（如「上午」->「下午」）同样是关键错误。
    # 仅比对数字串会漏检这类时间与方位错误，因此额外比对数字前后的限定词。
    if ref_numbers and ref_numbers == hyp_numbers:
        if extract_number_context(ref_text) != extract_number_context(hyp_text):
            return True

    return False





def read_jsonl(path: Path) -> dict:

    records = {}

    with path.open(encoding="utf-8") as handle:

        for line_no, line in enumerate(handle, 1):

            stripped = line.strip()

            if not stripped:

                continue

            try:

                record = json.loads(stripped)

            except json.JSONDecodeError as error:

                raise ValueError(f"{path}:{line_no} JSON 解析失败: {error}") from error

            segment_id = record.get("segmentId")

            if not segment_id:

                raise ValueError(f"{path}:{line_no} 缺少 segmentId")

            records[segment_id] = record

    return records





def evaluate(hyp: dict, ref: dict) -> list:

    outcomes = []



    for segment_id, ref_record in ref.items():

        hyp_record = hyp.get(segment_id)

        if hyp_record is None:

            continue



        ref_text = ref_record.get("referenceText") or ""

        hyp_text = hyp_record.get("text") or ""



        if not ref_text.strip():

            continue



        ref_chars = list(normalize(ref_text))

        hyp_chars = list(normalize(hyp_text))

        distance, _, _, _ = levenshtein(ref_chars, hyp_chars)

        cer = distance / len(ref_chars) if ref_chars else 0.0



        outcomes.append(SegmentOutcome(

            segment_id=segment_id,

            speaker_id=ref_record.get("speakerId", "unknown"),

            severity=ref_record.get("severityLevel", "unknown"),

            category=ref_record.get("category", "unknown"),

            cer=cer,

            critical_error=has_critical_error(ref_text, hyp_text),

            ref_text=ref_text,

            hyp_text=hyp_text,

        ))



    return outcomes





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

            bucket.examples.append(

                f"    {outcome.segment_id}: 参考={outcome.ref_text} | 识别={outcome.hyp_text}"

            )

    return bucket





def report(outcomes: list) -> None:

    if not outcomes:

        print("没有可评测的片段（缺少意图基准或 hyp/ref 无法对齐）。")

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





def main(argv: list) -> int:

    parser = argparse.ArgumentParser(description="VoiceClear 识别质量评测")

    parser.add_argument("--hyp", required=True, help="识别结果 JSONL")

    parser.add_argument("--ref", required=True, help="参考答案 JSONL")

    parser.add_argument("--manifest", help="数据集 manifest.json（可选）")

    args = parser.parse_args(argv[1:])



    try:

        hyp = read_jsonl(Path(args.hyp))

        ref = read_jsonl(Path(args.ref))

    except (OSError, ValueError) as error:

        print(f"错误：{error}")

        return 2



    if args.manifest:

        manifest_path = Path(args.manifest)

        if manifest_path.exists():

            try:

                with manifest_path.open(encoding="utf-8") as handle:

                    manifest = json.load(handle)

                limitations = manifest.get("limitations") or []

                if limitations:

                    print("数据集自述局限（必须随结论一起引用）：")

                    for item in limitations:

                        print(f"  - {item}")

                    print()

            except (OSError, json.JSONDecodeError):

                pass



    outcomes = evaluate(hyp, ref)

    report(outcomes)



    missing = sorted(set(ref) - set(hyp))

    if missing:

        print(f"注意：{len(missing)} 个参考片段在识别结果中缺失，已跳过。")



    return 0





if __name__ == "__main__":

    sys.exit(main(sys.argv))
