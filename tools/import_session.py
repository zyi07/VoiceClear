#!/usr/bin/env python3
"""VoiceClear 采集数据入库流水线。

把采集 App 导出的数据集目录整理为标准数据集，并执行完整性校验。

用法：
    python3 tools/import_session.py --source <app导出的目录> --dest <标准数据集目录>
    python3 tools/import_session.py --source <src> --dest <dst> --dry-run

职责：
- 复制并规范化数据集结构（manifest / transcripts / speakers / consent / splits）；
- 生成来源溯源信息，记录导入时间与来源路径；
- 调用 validate_dataset.py 做完整校验，校验不通过则以非零码退出；
- --dry-run 只做检查与报告，不写入目标目录。

设计约束：
- 不修改源数据；
- 不做静默修复：发现问题直接报告并退出；
- 导入不改变说话人划分，避免破坏防泄漏规则。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
VALIDATOR = TOOLS_DIR / "validate_dataset.py"

COPY_DIRS = ("transcripts", "speakers", "consent", "splits", "segments")


def local_now() -> str:
    """本地时间字符串（带时区），用于溯源记录。"""
    tz = datetime.now(timezone(timedelta(hours=8)))
    return tz.isoformat(timespec="seconds")


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description="VoiceClear 采集数据入库")
    parser.add_argument("--source", required=True, help="App 导出的数据集目录")
    parser.add_argument("--dest", required=True, help="标准数据集目录")
    parser.add_argument("--dry-run", action="store_true", help="只检查不写入")
    parser.add_argument("--skip-validate", action="store_true", help="跳过校验（不推荐）")
    args = parser.parse_args(argv[1:])

    source = Path(args.source).resolve()
    dest = Path(args.dest).resolve()

    if not source.is_dir():
        print("错误：源目录不存在: " + str(source))
        return 2

    manifest_path = source / "manifest.json"
    if not manifest_path.exists():
        print("错误：源目录缺少 manifest.json，请确认是 App 导出的数据集")
        return 2

    try:
        with manifest_path.open(encoding="utf-8") as handle:
            manifest = json.load(handle)
    except json.JSONDecodeError as error:
        print("错误：manifest.json 解析失败: " + str(error))
        return 2

    print("=" * 60)
    print("VoiceClear 数据入库")
    print("=" * 60)
    print("源目录: " + str(source))
    print("目标目录: " + str(dest))
    print("数据集版本: " + str(manifest.get("datasetVersion", "未知")))
    print("导入时间: " + local_now())
    print("-" * 60)

    # 统计将要复制的内容
    for name in COPY_DIRS:
        src = source / name
        if not src.exists():
            continue
        files = [f for f in src.rglob("*") if f.is_file()]
        size = sum(f.stat().st_size for f in files)
        print("  %-14s %4d 个文件  %8.1f KB" % (name, len(files), size / 1024.0))

    if args.dry_run:
        print("-" * 60)
        print("dry-run：未写入任何数据")
        return 0

    dest.mkdir(parents=True, exist_ok=True)
    for name in COPY_DIRS:
        src = source / name
        if not src.exists():
            continue
        target = dest / name
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(src, target)

    # 写入溯源信息，不覆盖原 manifest 的业务字段
    provenance = {
        "importedAt": local_now(),
        "sourcePath": str(source),
        "importedBy": "tools/import_session.py",
    }
    with (dest / "manifest.json").open(encoding="utf-8") as handle:
        manifest["provenance"] = provenance
    with (dest / "manifest.json").open("w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)

    if args.skip_validate:
        print("已跳过校验（不推荐）")
        return 0

    print("-" * 60)
    print("执行完整性校验...")
    result = subprocess.run(
        [sys.executable, str(VALIDATOR), str(dest)],
        capture_output=False,
        check=False,
    )
    if result.returncode != 0:
        print()
        print("入库未完成：校验未通过，请修复后重试。数据已复制，但不应投入评测。")
        return result.returncode

    print("-" * 60)
    print("入库完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
