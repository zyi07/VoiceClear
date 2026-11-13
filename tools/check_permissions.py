#!/usr/bin/env python3
"""APK 权限守卫。

检查构建出的 APK 是否声明了必需权限。

背景：曾因清单漏声明 INTERNET 权限，导致模型下载全部失败，
而错误信息是 "Permission denied (missing INTERNET permission?)"——
不看诊断日志很难定位。这个守卫把这类问题挡在构建阶段。

用法：
    python3 tools/check_permissions.py <apk 路径>

退出码：0 通过；1 缺少必需权限；2 无法解析 APK。
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

# 必需权限：
# - INTERNET：下载端侧识别模型
# - ACCESS_NETWORK_STATE：下载前判断网络可用性
# - RECORD_AUDIO：采集语音
REQUIRED = [
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.RECORD_AUDIO",
]


def find_aapt2() -> str | None:
    sdk = Path("/opt/android-sdk")
    if not sdk.exists():
        return None
    candidates = sorted((sdk / "build-tools").glob("*/aapt2"), reverse=True)
    return str(candidates[0]) if candidates else None


def main(argv: list) -> int:
    if len(argv) < 2:
        print("用法: python3 tools/check_permissions.py <apk 路径>")
        return 2

    apk = Path(argv[1])
    if not apk.exists():
        print("错误: APK 不存在: " + str(apk))
        return 2

    aapt2 = find_aapt2()
    if aapt2 is None:
        print("错误: 找不到 aapt2，无法校验权限")
        return 2

    try:
        result = subprocess.run(
            [aapt2, "dump", "badging", str(apk)],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError as error:
        print("错误: 无法执行 aapt2: " + str(error))
        return 2

    if result.returncode != 0:
        print("错误: aapt2 解析失败")
        print(result.stderr[:500])
        return 2

    declared = set(re.findall(r"uses-permission: name='([^']+)'", result.stdout))

    missing = [name for name in REQUIRED if name not in declared]

    print("APK: " + apk.name)
    print("已声明权限:")
    for name in sorted(declared):
        print("  " + name)

    if missing:
        print()
        print("缺少必需权限:")
        for name in missing:
            print("  " + name)
        print()
        print("请在 app/src/main/AndroidManifest.xml 中补充后重新构建。")
        return 1

    print()
    print("权限校验通过（" + str(len(REQUIRED)) + " 项必需权限齐备）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
