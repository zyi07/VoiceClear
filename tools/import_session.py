#!/usr/bin/env python3
"""VoiceClear 采集数据安全入库流水线。

用法：
    python3 tools/import_session.py --source <app导出的目录> --dest <标准数据集目录>
    python3 tools/import_session.py --source <src> --dest <dst> --dry-run

默认流程为：源目录完整只读校验 → staging 复制 → staging 复验 → 原子重命名发布。
源目录不会被修改；校验失败不会覆盖已有目标。--dry-run 执行同一套源校验但不写入。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
VALIDATOR = TOOLS_DIR / "validate_dataset.py"
COPY_DIRS = ("transcripts", "speakers", "consent", "splits", "segments", "prompts")


def local_now() -> str:
    """本地时间字符串（带时区），用于溯源记录。"""

    tz = datetime.now(timezone(timedelta(hours=8)))
    return tz.isoformat(timespec="seconds")


def paths_overlap(source: Path, dest: Path) -> bool:
    """相同路径或任一方向包含都不允许，避免自复制和递归删除。"""

    return source == dest or source in dest.parents or dest in source.parents


def run_validation(dataset_dir: Path, label: str) -> int:
    print("-" * 60)
    print(f"执行{label}完整性校验...")
    result = subprocess.run(
        [sys.executable, str(VALIDATOR), str(dataset_dir)],
        capture_output=False,
        check=False,
    )
    return result.returncode


def copy_to_staging(source: Path, staging: Path, manifest: dict) -> None:
    """在目标父目录中构造完整候选数据集，源 manifest 只读入内存。"""

    for name in COPY_DIRS:
        src = source / name
        if src.exists():
            if not src.is_dir():
                raise ValueError(f"源路径应为目录: {src}")
            shutil.copytree(src, staging / name)

    staged_manifest = dict(manifest)
    staged_manifest["provenance"] = {
        "importedAt": local_now(),
        "sourcePath": str(source),
        "importedBy": "tools/import_session.py",
    }
    with (staging / "manifest.json").open("w", encoding="utf-8") as handle:
        json.dump(staged_manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def publish_staging(staging: Path, dest: Path) -> None:
    """重命名发布；替换已有目录时保留备份直到新目录发布成功。"""

    backup: Path | None = None
    if dest.exists():
        if not dest.is_dir():
            raise ValueError(f"目标已存在且不是目录: {dest}")
        backup = dest.parent / f".{dest.name}.backup-{uuid.uuid4().hex}"
        os.replace(dest, backup)
    try:
        os.replace(staging, dest)
    except BaseException as publish_error:
        if backup is not None and backup.exists() and not dest.exists():
            try:
                os.replace(backup, dest)
            except OSError as restore_error:
                raise RuntimeError(
                    f"发布失败且旧目标仍保留在备份 {backup}；恢复失败: {restore_error}"
                ) from publish_error
        raise
    if backup is not None:
        try:
            shutil.rmtree(backup)
        except OSError as error:
            # 新目标已经发布，不能把这个后续清理问题误报成“发布未发生”。
            print(f"警告：新目标已发布，但旧目标备份 {backup} 清理失败: {error}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="VoiceClear 采集数据入库")
    parser.add_argument("--source", required=True, help="App 导出的数据集目录")
    parser.add_argument("--dest", required=True, help="标准数据集目录")
    parser.add_argument("--dry-run", action="store_true", help="完整只读校验，不写入")
    parser.add_argument(
        "--skip-validate",
        action="store_true",
        help="已停用的兼容参数；安全入库不允许绕过校验",
    )
    args = parser.parse_args(argv[1:])

    source = Path(args.source).resolve()
    dest = Path(args.dest).resolve()
    if paths_overlap(source, dest):
        print("错误：源目录与目标目录相同或互相包含，已拒绝以防止数据丢失")
        return 2
    if args.skip_validate:
        print("错误：--skip-validate 不能用于入库；源目录与 staging 均必须通过校验")
        return 2
    if not source.is_dir():
        print("错误：源目录不存在: " + str(source))
        return 2
    if dest.exists() and not dest.is_dir():
        print("错误：目标已存在且不是目录: " + str(dest))
        return 2

    manifest_path = source / "manifest.json"
    if not manifest_path.is_file():
        print("错误：源目录缺少 manifest.json，请确认是 App 导出的数据集")
        return 2
    try:
        with manifest_path.open(encoding="utf-8") as handle:
            manifest = json.load(handle)
        if not isinstance(manifest, dict):
            raise ValueError("manifest.json 顶层必须是对象")
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print("错误：manifest.json 读取或解析失败: " + str(error))
        return 2

    print("=" * 60)
    print("VoiceClear 数据入库")
    print("=" * 60)
    print("源目录: " + str(source))
    print("目标目录: " + str(dest))
    print("数据集版本: " + str(manifest.get("datasetVersion", "未知")))
    print("导入时间: " + local_now())
    print("-" * 60)

    try:
        for name in COPY_DIRS:
            src = source / name
            if not src.exists():
                continue
            if not src.is_dir():
                print(f"错误：源路径应为目录: {src}")
                return 2
            files = [item for item in src.rglob("*") if item.is_file()]
            size = sum(item.stat().st_size for item in files)
            print("  %-14s %4d 个文件  %8.1f KB" % (name, len(files), size / 1024.0))
    except OSError as error:
        print(f"错误：读取源目录失败: {error}")
        return 2

    if run_validation(source, "源目录") != 0:
        print("入库未完成：源目录校验未通过；目标目录未修改。")
        return 1

    if args.dry_run:
        print("-" * 60)
        print("dry-run：源目录完整校验通过，未写入任何数据")
        return 0

    staging: Path | None = None
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=f".{dest.name}.staging-", dir=dest.parent))
        copy_to_staging(source, staging, manifest)
        if run_validation(staging, "staging") != 0:
            print("入库未完成：staging 复验未通过；已有目标目录未修改。")
            return 1
        publish_staging(staging, dest)
        staging = None
    except (OSError, ValueError, RuntimeError) as error:
        print(f"错误：入库失败；请按错误信息检查目标及可能的备份: {error}")
        return 1
    finally:
        if staging is not None and staging.exists():
            shutil.rmtree(staging, ignore_errors=True)

    print("-" * 60)
    print("入库完成，staging 复验通过并已发布。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
