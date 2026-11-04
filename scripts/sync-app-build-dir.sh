#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# 把**仓库里**的 App 源码同步到 ASCII 构建目录（v0.131.0）
# ═══════════════════════════════════════════════════════════════════
#
# ## 为什么需要这个脚本
#
# Gradle **拒绝含非 ASCII 字符的项目路径**（Windows + 中文用户名），
# 所以构建必须在一个纯 ASCII 路径下做 —— 本机是 `C:\tm-app\teslamate-app`。
#
# ⚠️ **但那个目录只是一份副本，而副本会被写旧。**
#
# ## 一次真实的雷（2026-11-04 实测）
#
# 仓库已经到 **v0.130.0（code 139）**，而 `C:\tm-app\teslamate-app`
# 还停在 **v0.103.0（code 113）** —— **差 17 个版本**。
#
# 那一刻谁跑一次 `release.sh`：
#   ① 构建的是 **17 个版本前的源码** ⇒ 产出的 APK 直接是旧的；
#   ② 第 3 步 robocopy 把这份旧副本**拷回仓库** ⇒ 仓库里的 App 改动
#      **被覆盖掉**，而且**不报错**（robocopy 的退出码被 `|| true` 吞了）。
#
# ⇒ 结论：**同步方向只有一个 —— 仓库 → 构建目录。**
#   · **仓库（`app-android/`）是源码的唯一真相**；
#   · 构建目录只是**构建前的暂存**，由本脚本自动同步；
#   · **反向同步（构建目录 → 仓库）已取消** ⇒ 旧副本再也覆盖不了新代码。
#
# ## 防呆：构建目录里若有人改过代码 ⇒ 停下来问
#
# 同步会覆盖构建目录 ⇒ 如果那边有**比仓库更新的文件**，
# 说明有人直接在构建目录里改了代码 ⇒ 同步会把那些改动**悄悄抹掉**。
# ⇒ 检测到就**中止**，让人先把改动同步回仓库（或删掉构建目录，改在仓库里构建）。
#
# 用法：bash scripts/sync-app-build-dir.sh
#      APP_BUILD_DIR=... bash scripts/sync-app-build-dir.sh   # 换构建目录
#      SKIP_NEWER_CHECK=1 bash scripts/sync-app-build-dir.sh # 明确知道自己在干嘛时
set -uo pipefail
cd "$(dirname "$0")/.."

# ⚠️ 仓库侧用 **junction 路径** `C:\tm-ws`（纯 ASCII）——
#    直接用带中文的仓库路径，cmd.exe 的代码页会把路径解析坏。
REPO_APP="${REPO_APP:-/c/tm-ws/app-android}"
DST="${APP_BUILD_DIR:-/c/tm-app/teslamate-app}"

if [ ! -d "$REPO_APP" ]; then
  echo "❌ 找不到仓库里的 App 目录：$REPO_APP"
  exit 1
fi
if [ ! -d "$DST" ]; then
  echo "❌ 找不到构建目录：$DST（先手工建出来，或改 APP_BUILD_DIR）"
  exit 1
fi

# ── ① 防呆：构建目录里有比仓库更新的文件？──
if [ "${SKIP_NEWER_CHECK:-0}" != "1" ]; then
  NEWER=$(REPO_APP="$REPO_APP" DST="$DST" python - <<'PYEOF'
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

repo = os.environ["REPO_APP"]
dst = os.environ["DST"]
hits = []
for base, dirs, files in os.walk(os.path.join(dst, "app", "src", "main")):
    dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
    for name in files:
        d = os.path.join(base, name)
        rel = os.path.relpath(d, dst)
        r = os.path.join(repo, rel)
        if not os.path.exists(r):
            hits.append(rel + "（仓库里没有这个文件）")
        elif os.path.getmtime(d) - os.path.getmtime(r) > 2:
            hits.append(rel)
for b in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts"):
    d = os.path.join(dst, b)
    r = os.path.join(repo, b)
    if os.path.exists(d) and os.path.exists(r) and \
            os.path.getmtime(d) - os.path.getmtime(r) > 2:
        hits.append(b)
print("\n".join(hits[:12]))
PYEOF
)
  if [ -n "$NEWER" ]; then
    echo "❌ 构建目录里有比仓库更新的文件（有人可能直接在构建目录里改了代码）："
    echo "$NEWER" | sed 's/^/     /'
    echo ""
    echo "   ⇒ 继续同步会把这些改动**悄悄覆盖掉**。"
    echo "   请先把这些改动同步回仓库（app-android/），"
    echo "   或删掉构建目录、改在仓库里构建（仓库路径经 C:\\tm-ws 的 junction 也能构建）。"
    echo "   确实要覆盖：SKIP_NEWER_CHECK=1 bash scripts/sync-app-build-dir.sh"
    exit 1
  fi
fi

# ── ② 同步：仓库 → 构建目录（只此一个方向）──
# ⚠️ **必须临时关掉 MSYS_NO_PATHCONV**：开着它时 MSYS **不会**把 `//c` 转成 `/c`
#    ⇒ cmd.exe 收到的是 `//c` ⇒ **robocopy 根本没跑**，
#    而它的退出码看着还挺正常（<8）⇒ **静默失败**（本轮真踩过）。
#    （server-gates.sh 里也要设它 —— 那是给 docker 用的，别把那个习惯带到这里。）
unset MSYS_NO_PATHCONV
# ⚠️ robocopy 的退出码：0~7 = 成功（含「复制了文件」），≥8 = 真失败。
#   所以**不能**用 `set -e` 直接跑（复制了文件时退出码是 1 ⇒ 会被当成失败）。
# ⚠️ `/PURGE`：删掉构建目录里**仓库已经没有**的文件 ——
#   否则被删掉的 .kt 仍留在副本里 ⇒ 编译出重复类（而且很难查）。
cmd //c "robocopy C:\tm-ws\app-android $(cygpath -w "$DST" 2>/dev/null || echo "$DST") /E /PURGE /XD build .gradle .kotlin /NFL /NDL /NJH /NJS /NC /NS /NP /R:1 /W:1" >/dev/null 2>&1
RC=$?
if [ "$RC" -ge 8 ]; then
  echo "❌ 同步失败（robocopy 退出码 $RC）"
  exit 1
fi

# ── ③ 校验：两边的版本号必须一致（构建目录里的版本号会被发到手机上）──
V1=$(grep -oE 'versionCode = [0-9]+' "$REPO_APP/app/build.gradle.kts" | grep -oE '[0-9]+')
V2=$(grep -oE 'versionCode = [0-9]+' "$DST/app/build.gradle.kts" | grep -oE '[0-9]+')
if [ "$V1" != "$V2" ]; then
  echo "❌ 版本号对不上：仓库 $V1 vs 构建目录 $V2 —— 发出去会是另一个版本"
  exit 1
fi

echo "✓ 构建目录已同步（versionCode $V1）"
