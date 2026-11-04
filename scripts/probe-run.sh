#!/usr/bin/env bash
#
# 在**隔离副本**里跑闸门 —— 用来跑「负向探针」（故意改坏 ⇒ 必须精确报红）。
#
# ⚠️ **为什么要有这个脚本**（AGENTS.md「交付前」区的规矩：新建闸门 ⇒ 先跑负向探针，再提交）：
#   ① 探针要**改坏源码** ⇒ 直接改共享工作树的话，两个并行会话会同时看到改坏的状态
#      （后写的直接覆盖先写的，**不报错**）；
#   ② 跑完要**还原** ⇒ 手工还原容易漏 ⇒ 而副本是**重建**出来的，天然干净。
#
# 用法（**共享工作树全程不动**）：
#   1) 写一个探针脚本 probe.py：它在**副本根目录**下改坏源码（路径写相对路径即可）；
#   2) bash scripts/probe-run.sh client-gates.sh /path/to/probe.py
#      ⇒ 先重建副本 → 在副本里跑探针 → 在副本里跑闸门；
#      ⇒ 输出里哪些闸门变红，就是这次探针要咬住的那几道；
#   3) 不用还原（副本是重建的，共享工作树没被碰过）。
#
# 参数：闸门脚本名（默认 client-gates.sh，可传 hermes-gates.sh）[可选：探针脚本]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE="$ROOT/.git/probe_repo"
GATE="${1:-client-gates.sh}"
PROBE_SCRIPT="${2:-}"

rm -rf -- "$PROBE"
mkdir -p "$PROBE/app-android/app"
cp -r "$ROOT/api" "$ROOT/docs" "$ROOT/scripts" "$ROOT/mcp" "$PROBE/"
cp -r "$ROOT/app-android/app/src" "$PROBE/app-android/app/src"
cp "$ROOT/app-android/app/build.gradle.kts" "$PROBE/app-android/app/"
[ -f "$ROOT/.env" ] && cp "$ROOT/.env" "$PROBE/"

cd "$PROBE" || exit 1
if [ ! -f "scripts/$GATE" ]; then
  echo "找不到 scripts/$GATE"
  exit 1
fi
if [ -n "$PROBE_SCRIPT" ]; then
  echo "（先在副本里跑探针：$PROBE_SCRIPT）"
  python "$PROBE_SCRIPT" || { echo "探针脚本自己报错了"; exit 1; }
fi
echo "（隔离副本：$PROBE —— 共享工作树没被动过）"
bash "scripts/$GATE"