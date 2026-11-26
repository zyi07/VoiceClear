#!/usr/bin/env python3
"""VoiceClear 数据集只读校验工具。

按 docs/dataset-schema.md 校验数据集；任何 ERROR 都使退出码非零。

用法：
    python3 tools/validate_dataset.py <dataset_dir>

校验不会修复或改写数据。除元数据、授权与划分外，还会核对片段 ID 的
全局唯一性、生产音频路径、PCM/WAV 时长，以及 manifest 的关键交叉计数。
"""

from __future__ import annotations

import json
import sys
import wave
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
PUNCTUATION = set("，。！？；：、,.!?;:（）()《》〈〉【】[]{}…—～~·「」『』" + "\"")
PCM_ENCODINGS = {"pcm_s16", "pcm_s16le", "pcm16", "pcm_16"}


def normalize_reference(text: str) -> str:
    """与 eval_asr.normalize 使用同一口径，识别标点组成的空基准。"""

    return "".join(ch for ch in text.strip() if not ch.isspace() and ch not in PUNCTUATION)


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
    if not isinstance(manifest, dict):
        findings.error("manifest.json 顶层必须是对象")
        return {}

    required = ("datasetVersion", "cohort", "speakers", "splits", "totals", "license", "limitations")
    for name in required:
        if name not in manifest:
            findings.error(f"manifest.json 缺少必填字段: {name}")

    if not manifest.get("limitations"):
        findings.error("manifest.json 的 limitations 不能为空：数据集必须自述局限")

    license_info = manifest.get("license", {})
    if not isinstance(license_info, dict):
        findings.error("manifest.json 的 license 必须是对象")
    elif license_info.get("commercialUse"):
        findings.warn("manifest 声明可商用，但需确认数据来源授权是否真的覆盖商用")

    entries = manifest.get("speakers", [])
    if not isinstance(entries, list):
        findings.error("manifest.json 的 speakers 必须是数组")
    else:
        seen: set[str] = set()
        for index, entry in enumerate(entries):
            speaker_id = entry.get("speakerId") if isinstance(entry, dict) else None
            if not speaker_id:
                findings.error(f"manifest speakers[{index}] 缺少 speakerId")
            elif speaker_id in seen:
                findings.error(f"manifest 中说话人 ID 重复: {speaker_id}")
            seen.add(speaker_id)

    return manifest


def check_consent(dataset_dir: Path, findings: Findings) -> dict[str, dict[str, Any]]:
    """校验授权记录，返回 speakerId -> 记录；撤回不会被 grants 覆盖。"""

    consent_dir = dataset_dir / "consent"
    records: dict[str, dict[str, Any]] = {}
    if not consent_dir.exists():
        findings.warn("缺少 consent/ 目录：无法验证授权范围")
        return records

    for path in sorted(consent_dir.glob("*.json")):
        record = load_json(path)
        if not isinstance(record, dict):
            findings.error(f"{path.name} 为空、无法解析或顶层不是对象")
            continue
        speaker_id = record.get("speakerId")
        if not speaker_id:
            findings.error(f"{path.name} 缺少 speakerId")
            continue
        if speaker_id in records:
            findings.error(f"授权记录中的说话人 ID 重复: {speaker_id}")

        grants = record.get("grants")
        if not isinstance(grants, dict):
            findings.error(f"{speaker_id} 的 grants 缺失或类型错误")
            grants = {}
        unknown = set(grants) - CONSENT_GRANTS
        if unknown:
            findings.error(f"{speaker_id} 存在未知授权项: {sorted(unknown)}")

        if record.get("withdrawnAt"):
            findings.error(f"{speaker_id} 的授权已于 {record['withdrawnAt']} 撤回，其数据不得入库或评测")
        elif not grants.get("research_use", False):
            findings.warn(f"{speaker_id} 未授权 research_use，其片段不能计入有效研究集")
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
        if not isinstance(record, dict):
            findings.error(f"{path.name} 为空、无法解析或顶层不是对象")
            continue
        speaker_id = record.get("speakerId")
        if not speaker_id:
            findings.error(f"{path.name} 缺少 speakerId")
            continue
        if speaker_id in speakers:
            findings.error(f"说话人元数据 ID 重复: {speaker_id}")

        severity = record.get("severityLevel")
        if severity not in SEVERITY_LEVELS:
            findings.error(f"{speaker_id} 的 severityLevel 非法: {severity!r}")
        if record.get("severitySource") != "professional_advisor":
            findings.warn(f"{speaker_id} 的严重度判定来源非专业顾问，可能影响分层结论")
        if record.get("sessionCount", 0) < 3:
            findings.warn(f"{speaker_id} 会话数 {record.get('sessionCount')} < 3，跨会话稳定性无法评估")
        speakers[speaker_id] = record

    return speakers


def check_splits(dataset_dir: Path, findings: Findings) -> dict[str, set[str]]:
    """校验说话人划分文件。"""

    splits = {name: read_split(dataset_dir / "splits" / f"{name}.txt") for name in ("train", "dev", "test")}
    train, dev, test = splits["train"], splits["dev"], splits["test"]
    overlaps = []
    if train & dev:
        overlaps.append(f"train∩dev: {sorted(train & dev)}")
    if train & test:
        overlaps.append(f"train∩test: {sorted(train & test)}")
    if dev & test:
        overlaps.append(f"dev∩test: {sorted(dev & test)}")
    for item in overlaps:
        findings.error(f"说话人划分重叠，{item}")
    if not (train | dev | test):
        findings.warn("三个划分文件均为空")
    return splits


def _audio_candidates(dataset_dir: Path, record: dict[str, Any]) -> list[Path]:
    """返回候选路径，优先使用显式字段，再使用 App 的实际生产布局。"""

    session_id = record.get("sessionId") or ""
    segment_id = record.get("segmentId") or ""
    explicit = record.get("audioFileName") or record.get("audioPath")
    names = [explicit] if isinstance(explicit, str) and explicit.strip() else []
    if not names and session_id and segment_id:
        names.append(f"{segment_id}.pcm")

    candidates: list[Path] = []
    for name in names:
        relative = Path(name)
        if relative.is_absolute():
            candidates.append(relative)
            continue
        if relative.parts and relative.parts[0] == "segments":
            candidates.append(dataset_dir / relative)
        else:
            session_root = dataset_dir / "segments" / str(session_id)
            candidates.extend((session_root / relative, session_root / "raw" / relative, session_root / "16k" / relative))
            candidates.append(dataset_dir / relative)
    return candidates


def find_audio_file(dataset_dir: Path, record: dict[str, Any]) -> Path | None:
    """只返回数据集内真实存在的普通文件，拒绝路径逃逸与目录伪装。"""

    root = dataset_dir.resolve()
    for candidate in _audio_candidates(dataset_dir, record):
        try:
            resolved = candidate.resolve(strict=True)
        except (FileNotFoundError, OSError):
            continue
        if resolved != root and root not in resolved.parents:
            continue
        if resolved.is_file():
            return resolved
    return None


def audio_duration_ms(path: Path, record: dict[str, Any]) -> float:
    """返回可核验音频的实际时长；不支持的格式或参数会抛出 ValueError。"""

    if path.suffix.lower() == ".wav":
        try:
            with wave.open(str(path), "rb") as handle:
                rate = handle.getframerate()
                if rate <= 0:
                    raise ValueError("WAV 采样率非法")
                return handle.getnframes() * 1000.0 / rate
        except (wave.Error, EOFError) as error:
            raise ValueError(f"WAV 无法解析: {error}") from error

    if path.suffix.lower() not in {".pcm", ".raw"}:
        raise ValueError(f"不支持的音频格式 {path.suffix!r}，无法核验实际时长")
    encoding = str(record.get("encoding") or "").lower()
    if encoding not in PCM_ENCODINGS:
        raise ValueError(f"PCM encoding 缺失或不支持: {record.get('encoding')!r}")
    sample_rate = record.get("sampleRate")
    channels = record.get("channels")
    if isinstance(sample_rate, bool) or not isinstance(sample_rate, int) or sample_rate <= 0:
        raise ValueError(f"PCM sampleRate 非法: {sample_rate!r}")
    if isinstance(channels, bool) or not isinstance(channels, int) or channels <= 0:
        raise ValueError(f"PCM channels 非法: {channels!r}")
    frame_size = channels * 2
    size = path.stat().st_size
    if size % frame_size:
        raise ValueError(f"PCM 字节数 {size} 不是帧大小 {frame_size} 的整数倍")
    return size * 1000.0 / (sample_rate * frame_size)


def check_transcripts(
    dataset_dir: Path,
    findings: Findings,
    speakers: dict[str, dict[str, Any]],
    consent: dict[str, dict[str, Any]],
    splits: dict[str, set[str]],
    manifest: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """校验全部转写；空采集/未确认只排除出有效集，不跳过后续检查。"""

    stats: dict[str, Any] = {
        "segments": 0,
        "valid": 0,
        "unconfirmed": 0,
        "empty_capture": 0,
        "withdrawn": 0,
        "duplicate_ids": 0,
        "missing_audio": 0,
        "sessions": set(),
    }
    transcripts_dir = dataset_dir / "transcripts"
    if not transcripts_dir.exists():
        findings.warn("缺少 transcripts/ 目录")
        return stats

    speaker_split = {member: name for name, members in splits.items() for member in members}
    manifest_ids = {
        entry.get("speakerId")
        for entry in (manifest or {}).get("speakers", [])
        if isinstance(entry, dict) and entry.get("speakerId")
    }
    segment_locations: dict[str, str] = {}
    duplicate_ids: set[str] = set()
    valid_ids: set[str] = set()
    session_splits: dict[str, str] = {}
    session_speakers: dict[str, str] = {}
    audio_owners: dict[Path, str] = {}

    for path in sorted(transcripts_dir.glob("*.jsonl")):
        with path.open(encoding="utf-8") as handle:
            for line_no, line in enumerate(handle, 1):
                stripped = line.strip()
                if not stripped:
                    continue
                location = f"{path.name}:{line_no}"
                try:
                    record = json.loads(stripped)
                except json.JSONDecodeError as error:
                    findings.error(f"{location} JSON 解析失败: {error}")
                    continue
                if not isinstance(record, dict):
                    findings.error(f"{location} 片段记录必须是 JSON 对象")
                    continue

                stats["segments"] += 1
                record_ok = True
                eligible = True
                segment_id = record.get("segmentId")
                speaker_id = record.get("speakerId") or ""
                session_id = record.get("sessionId") or ""

                if not isinstance(segment_id, str) or not segment_id.strip():
                    findings.error(f"{location} 缺少有效 segmentId")
                    record_ok = False
                    segment_id = None
                else:
                    if segment_id in segment_locations:
                        duplicate_ids.add(segment_id)
                        stats["duplicate_ids"] += 1
                        findings.error(
                            f"segmentId 重复: {segment_id}（{segment_locations[segment_id]} 与 {location}）"
                        )
                        record_ok = False
                    else:
                        segment_locations[segment_id] = location
                if not session_id:
                    findings.error(f"{location} 缺少 sessionId")
                    record_ok = False
                else:
                    stats["sessions"].add(session_id)
                if not speaker_id:
                    findings.error(f"{location} 缺少 speakerId")
                    record_ok = False

                if speaker_id not in speakers:
                    findings.error(f"{location} 说话人 {speaker_id} 在 speakers/ 中不存在")
                    record_ok = False
                if manifest_ids and speaker_id not in manifest_ids:
                    findings.error(f"{location} 说话人 {speaker_id} 在 manifest speakers 中不存在")
                    record_ok = False
                if record.get("scene") not in SCENES:
                    findings.error(f"{location} scene 非法: {record.get('scene')!r}")
                    record_ok = False

                reference_source = record.get("referenceSource")
                if reference_source not in REFERENCE_SOURCES:
                    findings.error(f"{location} referenceSource 非法: {reference_source!r}")
                    record_ok = False
                    eligible = False
                reference_text = record.get("referenceText")
                if reference_text is not None and not isinstance(reference_text, str):
                    findings.error(f"{location} referenceText 必须是字符串")
                    reference_text = ""
                    record_ok = False
                reference_text = reference_text or ""
                if reference_source == "unconfirmed":
                    stats["unconfirmed"] += 1
                    eligible = False
                    if reference_text.strip():
                        findings.warn(f"{location} 标为 unconfirmed 但有 referenceText，请确认")
                elif reference_source in REFERENCE_SOURCES:
                    if not reference_text.strip():
                        findings.error(f"{location} referenceSource={reference_source} 但 referenceText 为空")
                        record_ok = False
                    elif not normalize_reference(reference_text):
                        findings.error(f"{location} referenceText 规范化后为空，不能作为 CER 基准")
                        record_ok = False

                duration = record.get("durationMs", 0)
                if isinstance(duration, bool) or not isinstance(duration, int) or duration <= 0:
                    findings.error(f"{location} durationMs 非法: {duration!r}")
                    record_ok = False

                flags = record.get("qualityFlags") or []
                if not isinstance(flags, list):
                    findings.error(f"{location} qualityFlags 必须是数组")
                    flags = []
                    record_ok = False
                if "empty_capture" in flags:
                    stats["empty_capture"] += 1
                    findings.warn(f"{location} 空采集片段不计入有效集")
                    eligible = False

                speaker_consent = consent.get(speaker_id)
                if speaker_consent is None:
                    findings.error(f"{location} 缺少说话人 {speaker_id} 的授权记录")
                    record_ok = False
                    grants = {}
                else:
                    grants = speaker_consent.get("grants") or {}
                    if speaker_consent.get("withdrawnAt"):
                        stats["withdrawn"] += 1
                        findings.error(f"{location} 说话人 {speaker_id} 已撤回授权，片段无效")
                        record_ok = False
                        eligible = False
                    if not grants.get("research_use", False):
                        findings.error(f"{location} 说话人 {speaker_id} 未授权 research_use")
                        record_ok = False

                consent_flags = record.get("consentFlags") or []
                if not isinstance(consent_flags, list):
                    findings.error(f"{location} consentFlags 必须是数组")
                    consent_flags = []
                    record_ok = False
                for flag in consent_flags:
                    if flag not in CONSENT_GRANTS:
                        findings.error(f"{location} 未知 consentFlags: {flag}")
                        record_ok = False
                    elif not grants.get(flag, False):
                        findings.error(f"{location} 使用了未授权的用途 {flag}")
                        record_ok = False

                expected_split = speaker_split.get(speaker_id)
                if not expected_split:
                    findings.error(f"{location} 说话人 {speaker_id} 未出现在任何划分中")
                    record_ok = False
                elif session_id:
                    previous_split = session_splits.setdefault(session_id, expected_split)
                    if previous_split != expected_split:
                        findings.error(f"会话 {session_id} 跨划分出现，违反防泄漏规则")
                        record_ok = False
                    previous_speaker = session_speakers.setdefault(session_id, speaker_id)
                    if previous_speaker != speaker_id:
                        findings.error(f"会话 {session_id} 关联了多个说话人")
                        record_ok = False

                audio_path = find_audio_file(dataset_dir, record) if segment_id and session_id else None
                if audio_path is None:
                    stats["missing_audio"] += 1
                    findings.error(
                        f"{location} 音频不存在（期望 segments/{session_id}/{segment_id}.pcm 或有效 audioFileName）"
                    )
                    record_ok = False
                else:
                    previous_owner = audio_owners.setdefault(audio_path, segment_id)
                    if previous_owner != segment_id:
                        findings.error(f"音频被多个 segmentId 复用: {audio_path.relative_to(dataset_dir.resolve())}")
                        record_ok = False
                    try:
                        actual_ms = audio_duration_ms(audio_path, record)
                    except (OSError, ValueError) as error:
                        findings.error(f"{location} 音频无法核验: {error}")
                        record_ok = False
                    else:
                        if isinstance(duration, int) and not isinstance(duration, bool) and duration > 0:
                            difference = abs(actual_ms - duration)
                            if difference >= 50:
                                findings.error(
                                    f"{location} durationMs={duration} 与音频实际时长 {actual_ms:.1f}ms 不一致"
                                )
                                record_ok = False

                if record_ok and eligible and segment_id:
                    valid_ids.add(segment_id)

    stats["valid"] = len(valid_ids - duplicate_ids)
    return stats


def cross_check_manifest(
    manifest: dict[str, Any],
    speakers: dict[str, dict[str, Any]],
    splits: dict[str, set[str]],
    stats: dict[str, Any],
    findings: Findings,
) -> None:
    """核对 manifest 中的说话人、划分和声明总数。"""

    if not manifest:
        return
    entries = manifest.get("speakers", [])
    if isinstance(entries, list):
        manifest_ids = {
            entry.get("speakerId") for entry in entries if isinstance(entry, dict) and entry.get("speakerId")
        }
        for speaker_id in sorted(set(speakers) - manifest_ids):
            findings.error(f"speakers/ 中的 {speaker_id} 未登记到 manifest")
        for speaker_id in sorted(manifest_ids - set(speakers)):
            findings.error(f"manifest 中的 {speaker_id} 缺少 speakers/ 元数据")

    declared_splits = manifest.get("splits", {})
    if isinstance(declared_splits, dict):
        for name, members in splits.items():
            declared = declared_splits.get(name)
            if isinstance(declared, list) and set(declared) != members:
                findings.error(f"manifest 的 {name} 划分与 splits/{name}.txt 不一致")

    totals = manifest.get("totals", {})
    if not isinstance(totals, dict):
        findings.error("manifest.json 的 totals 必须是对象")
        return
    comparisons = (("speakers", len(speakers)), ("sessions", len(stats["sessions"])), ("segments", stats["segments"]))
    for name, actual in comparisons:
        declared = totals.get(name)
        if isinstance(declared, int) and not isinstance(declared, bool) and declared != actual:
            findings.error(f"manifest 声明 {name}={declared}，实际为 {actual}")


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    dataset_dir = Path(argv[1])
    if not dataset_dir.is_dir():
        print(f"错误：{dataset_dir} 不是目录")
        return 2

    findings = Findings()
    stats: dict[str, Any] = {
        "segments": 0,
        "valid": 0,
        "unconfirmed": 0,
        "empty_capture": 0,
        "withdrawn": 0,
        "duplicate_ids": 0,
        "missing_audio": 0,
        "sessions": set(),
    }
    speakers: dict[str, dict[str, Any]] = {}
    manifest: dict[str, Any] = {}
    try:
        manifest = check_manifest(dataset_dir, findings)
        consent = check_consent(dataset_dir, findings)
        speakers = check_speakers(dataset_dir, findings)
        splits = check_splits(dataset_dir, findings)
        stats = check_transcripts(dataset_dir, findings, speakers, consent, splits, manifest)
        cross_check_manifest(manifest, speakers, splits, stats, findings)
    except (OSError, ValueError) as error:
        findings.error(str(error))

    print("=" * 60)
    print("VoiceClear 数据集校验报告")
    print("=" * 60)
    print(f"数据集: {dataset_dir}")
    print(f"说话人: {len(speakers)}  会话片段: {stats['segments']}  有效: {stats['valid']}")
    print(f"未确认意图(不计入正确率): {stats['unconfirmed']}")
    print(
        f"空采集: {stats['empty_capture']}  撤回: {stats['withdrawn']}  "
        f"重复 ID: {stats['duplicate_ids']}  缺失音频: {stats['missing_audio']}"
    )
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
