#!/usr/bin/env python3

"""VoiceClear 数据集校验工具。



按 docs/dataset-schema.md 第 4 节的规则校验数据集，任何规则失败即退出码非零。



用法：

    python3 tools/validate_dataset.py <dataset_dir>



设计原则：

- 只读校验，绝不修改数据；

- 报告分为 ERROR（必须修复）与 WARN（需要人工确认）；

- 退出码：0 表示通过，1 表示存在 ERROR。

"""



from __future__ import annotations



import json

import sys

from dataclasses import dataclass, field

from pathlib import Path

from typing import Any





@dataclass

class Findings:

    """收集校验过程中的问题。"""



    errors: list[str] = field(default_factory=list)

    warnings: list[str] = field(default_factory=list)



    def error(self, message: str) -> None:

        self.errors.append(message)



    def warn(self, message: str) -> None:

        self.warnings.append(message)





SEVERITY_LEVELS = {"L1", "L2", "L3"}

SCENES = {"read", "spontaneous", "dialogue"}

REFERENCE_SOURCES = {"approved_prompt", "user_confirmed", "unconfirmed"}

CONSENT_GRANTS = {

    "research_use",

    "train_use",

    "cloud_upload",

    "voice_profile",

    "anonymous_report",

}





def load_json(path: Path) -> Any:

    try:

        with path.open(encoding="utf-8") as handle:

            return json.load(handle)

    except FileNotFoundError:

        return None

    except json.JSONDecodeError as error:

        raise ValueError(f"{path} 不是合法 JSON: {error}") from error





def read_split(path: Path) -> set[str]:

    if not path.exists():

        return set()

    members: set[str] = set()

    with path.open(encoding="utf-8") as handle:

        for line in handle:

            stripped = line.strip()

            if stripped and not stripped.startswith("#"):

                members.add(stripped)

    return members





def check_manifest(dataset_dir: Path, findings: Findings) -> dict[str, Any]:

    """校验 manifest.json。"""

    manifest = load_json(dataset_dir / "manifest.json")

    if manifest is None:

        findings.error("缺少 manifest.json")

        return {}



    for field in ("datasetVersion", "cohort", "splits", "totals", "license", "limitations"):

        if field not in manifest:

            findings.error(f"manifest.json 缺少必填字段: {field}")



    limitations = manifest.get("limitations")

    if not limitations:

        findings.error("manifest.json 的 limitations 不能为空：数据集必须自述局限")



    license_info = manifest.get("license", {})

    if license_info.get("commercialUse"):

        findings.warn("manifest 声明可商用，但需确认数据来源授权是否真的覆盖商用")



    return manifest





def check_consent(dataset_dir: Path, findings: Findings) -> dict[str, dict[str, Any]]:

    """校验授权记录，返回 speakerId -> 记录。"""

    consent_dir = dataset_dir / "consent"

    records: dict[str, dict[str, Any]] = {}

    if not consent_dir.exists():

        findings.warn("缺少 consent/ 目录：无法验证授权范围")

        return records



    for path in sorted(consent_dir.glob("*.json")):

        record = load_json(path)

        if record is None:

            findings.error(f"{path.name} 为空或无法解析")

            continue



        speaker_id = record.get("speakerId")

        if not speaker_id:

            findings.error(f"{path.name} 缺少 speakerId")

            continue



        grants = record.get("grants")

        if not isinstance(grants, dict):

            findings.error(f"{speaker_id} 的 grants 缺失或类型错误")

            continue



        unknown = set(grants) - CONSENT_GRANTS

        if unknown:

            findings.error(f"{speaker_id} 存在未知授权项: {sorted(unknown)}")



        if not grants.get("research_use", False):

            findings.warn(f"{speaker_id} 未授权 research_use，该说话人数据不应出现在研究集中")



        records[speaker_id] = record



    return records





def check_speakers(dataset_dir: Path, findings: Findings) -> dict[str, dict[str, Any]]:

    """校验说话人元数据。"""

    speakers_dir = dataset_dir / "speakers"

    speakers: dict[str, dict[str, Any]] = {}

    if not speakers_dir.exists():

        findings.warn("缺少 speakers/ 目录")

        return speakers



    for path in sorted(speakers_dir.glob("*.json")):

        record = load_json(path)

        if record is None:

            findings.error(f"{path.name} 为空或无法解析")

            continue



        speaker_id = record.get("speakerId")

        if not speaker_id:

            findings.error(f"{path.name} 缺少 speakerId")

            continue



        severity = record.get("severityLevel")

        if severity not in SEVERITY_LEVELS:

            findings.error(f"{speaker_id} 的 severityLevel 非法: {severity!r}")



        if record.get("severitySource") != "professional_advisor":

            findings.warn(

                f"{speaker_id} 的严重度判定来源非专业顾问，可能影响分层结论"

        )



        if record.get("sessionCount", 0) < 3:

            findings.warn(

                f"{speaker_id} 会话数 {record.get('sessionCount')} < 3，跨会话稳定性无法评估"

        )



        speakers[speaker_id] = record



    return speakers





def check_splits(dataset_dir: Path, findings: Findings) -> dict[str, set[str]]:

    """校验划分文件。"""

    splits: dict[str, set[str]] = {}

    for name in ("train", "dev", "test"):

        splits[name] = read_split(dataset_dir / "splits" / f"{name}.txt")



    overlaps: list[str] = []

    train, dev, test = splits["train"], splits["dev"], splits["test"]

    overlaps += [f"train∩dev: {sorted(train & dev)}"] if train & dev else []

    overlaps += [f"train∩test: {sorted(train & test)}"] if train & test else []

    overlaps += [f"dev∩test: {sorted(dev & test)}"] if dev & test else []

    for item in overlaps:

        findings.error(f"说话人划分重叠，{item}")



    total = train | dev | test

    if not total:

        findings.warn("三个划分文件均为空")



    return splits





def check_transcripts(

    dataset_dir: Path,

    findings: Findings,

    speakers: dict[str, dict[str, Any]],

    consent: dict[str, dict[str, Any]],

    splits: dict[str, set[str]],

) -> dict[str, int]:

    """校验转写与片段记录。"""

    transcripts_dir = dataset_dir / "transcripts"

    stats = {"segments": 0, "valid": 0, "unconfirmed": 0}



    if not transcripts_dir.exists():

        findings.warn("缺少 transcripts/ 目录")

        return stats



    speaker_split = {}

    for split_name, members in splits.items():

        for member in members:

            speaker_split[member] = split_name



    session_splits: dict[str, str] = {}



    for path in sorted(transcripts_dir.glob("*.jsonl")):

        with path.open(encoding="utf-8") as handle:

            for line_no, line in enumerate(handle, 1):

                stripped = line.strip()

                if not stripped:

                    continue



                try:

                    record = json.loads(stripped)

                except json.JSONDecodeError as error:

                    findings.error(f"{path.name}:{line_no} JSON 解析失败: {error}")

                    continue



                stats["segments"] += 1

                speaker_id = record.get("speakerId", "")

                session_id = record.get("sessionId", "")



                if speaker_id not in speakers:

                    findings.error(

                        f"{path.name}:{line_no} 说话人 {speaker_id} 在 speakers/ 中不存在")

                    continue



                if record.get("scene") not in SCENES:

                    findings.error(

                        f"{path.name}:{line_no} scene 非法: {record.get('scene')!r}")



                reference_source = record.get("referenceSource")

                if reference_source not in REFERENCE_SOURCES:

                    findings.error(

                        f"{path.name}:{line_no} referenceSource 非法: {reference_source!r}")





                has_reference = bool((record.get("referenceText") or "").strip())

                if reference_source == "unconfirmed":

                    stats["unconfirmed"] += 1

                    if has_reference:

                        findings.warn(

                            f"{path.name}:{line_no} 标为 unconfirmed 但有 referenceText，请确认")

                elif not has_reference:

                    findings.error(

                        f"{path.name}:{line_no} referenceSource={reference_source} 但 referenceText 为空")

                    continue



                duration = record.get("durationMs", 0)

                if not isinstance(duration, int) or duration <= 0:

                    findings.error(f"{path.name}:{line_no} durationMs 非法: {duration!r}")



                flags = record.get("qualityFlags") or []

                if "empty_capture" in flags:

                    findings.warn(f"{path.name}:{line_no} 空采集片段不应计入有效集")

                    continue



                speaker_consent = consent.get(speaker_id, {})

                grants = speaker_consent.get("grants", {})

                for flag in record.get("consentFlags") or []:

                    if flag not in CONSENT_GRANTS:

                        findings.error(f"{path.name}:{line_no} 未知 consentFlags: {flag}")

                    elif not grants.get(flag, False):

                        findings.error(

                            f"{path.name}:{line_no} 使用了未授权的用途 {flag}"
                        )



                expected_split = speaker_split.get(speaker_id)

                if expected_split:

                    previous = session_splits.setdefault(session_id, expected_split)

                    if previous != expected_split:

                        findings.error(

                            f"会话 {session_id} 跨划分出现，违反防泄漏规则")



                stats["valid"] += 1



    return stats





def main(argv: list[str]) -> int:

    if len(argv) != 2:

        print(__doc__)

        return 2



    dataset_dir = Path(argv[1])

    if not dataset_dir.is_dir():

        print(f"错误：{dataset_dir} 不是目录")

        return 2



    findings = Findings()



    manifest = check_manifest(dataset_dir, findings)

    consent = check_consent(dataset_dir, findings)

    speakers = check_speakers(dataset_dir, findings)

    splits = check_splits(dataset_dir, findings)

    stats = check_transcripts(dataset_dir, findings, speakers, consent, splits)



    if manifest:

        declared = manifest.get("totals", {}).get("speakers")

        if isinstance(declared, int) and declared != len(speakers):

            findings.warn(

                f"manifest 声明 {declared} 名说话人，实际 {len(speakers)} 名")



    print("=" * 60)

    print("VoiceClear 数据集校验报告")

    print("=" * 60)

    print(f"数据集: {dataset_dir}")

    print(f"说话人: {len(speakers)}  会话片段: {stats['segments']}  有效: {stats['valid']}")

    print(f"未确认意图(不计入正确率): {stats['unconfirmed']}")

    print("-" * 60)



    if findings.errors:

        print(f"ERROR ({len(findings.errors)})：")

        for item in findings.errors:

            print(f"  [E] {item}")

        print()



    if findings.warnings:

        print(f"WARN ({len(findings.warnings)})：")

        for item in findings.warnings:

            print(f"  [W] {item}")

        print()



    if findings.errors:

        print("校验未通过：必须先修复 ERROR。")

        return 1



    print("校验通过。")

    if findings.warnings:

        print("存在 WARN，需人工确认后再进入评测。")

    return 0





if __name__ == "__main__":

    sys.exit(main(sys.argv))
