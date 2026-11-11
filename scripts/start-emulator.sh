#!/usr/bin/env bash
# 启动本机的 Android 模拟器（版面复核用）—— **Git Bash 包装**
#
# 真正的逻辑在 scripts/start-emulator.ps1（PowerShell 7）；这里只做一层壳，参数原样透传。
# 为什么要这个脚本：emulator 在**含非 ASCII 字符的 SDK 根路径**下起不来（实测崩/卡死），
# 必须改从纯 ASCII 路径（C:/tm-sdk）启动 —— 详见 ps1 里的对照表。
#
# 用法：
#   bash scripts/start-emulator.sh                  # 起 tm_pixel 并等它 boot 完
#   bash scripts/start-emulator.sh -Avd tm_emu      # 换 AVD（其余参数见 ps1）
#   bash scripts/start-emulator.sh -Stop            # 只停
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ps1_win="$(cygpath -w "$here/start-emulator.ps1")"

exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$ps1_win" "$@"
