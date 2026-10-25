#!/usr/bin/env bash
# ⚠️ **v0.37.0：客户端 JVM 单元测试闸门。**
#
# ## 为什么现在才有
#
# 这个项目的前 22 轮评审，客户端**从来没有过任何单测** ——
# 文案、几何、口径全靠人眼评审 + 评审员手工复算。
# 连续三轮都只能靠「读源码 + 算」发现问题：
#   · v0.34.0：爬升修正的「错段」+ 口径混用（双重计量）
#   · v0.36.0：「上次实测到站剩 Y%」在 6/35 个站取错场次
#   · v0.37.0：第一批单测**当场**抓到折叠态仍用「插枪电量」当「到站电量」
#
# ## 退出码
#   0 = 全部通过（或本机没有构建目录 → 跳过）
#   1 = 有测试失败
set -uo pipefail

CANON_APP="${APP_BUILD_DIR:-/c/tm-app/teslamate-app}"
GRADLE_BIN="${GRADLE_BIN:-/c/gradle/gradle-8.10.2/bin/gradle.bat}"

# 没有本地构建目录 → 跳过（远程验证场景）
if [ ! -d "$CANON_APP" ] || [ ! -f "$GRADLE_BIN" ]; then
  echo "跳过：本机没有构建目录（APP_BUILD_DIR=$CANON_APP）"
  exit 0
fi

export JAVA_HOME="${JAVA_HOME:-C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot}"
export ANDROID_HOME="${ANDROID_HOME:-${LOCALAPPDATA:-C:/Users/$USERNAME/AppData/Local}/Android/Sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-C:\\gradle\\gradle-home}"

LOG="${UNIT_TEST_LOG:-/tmp/unit-tests.log}"
"$GRADLE_BIN" -p "$CANON_APP" testDebugUnitTest --console=plain --offline > "$LOG" 2>&1
RC=$?

if [ "$RC" -eq 0 ]; then
  echo "✓ 客户端 JVM 单元测试通过"
  grep -E 'tests? completed|BUILD SUCCESSFUL' "$LOG" | sed 's/^/  /' | tail -3
  exit 0
fi

echo "✗ 客户端 JVM 单元测试失败"
grep -E 'FAILED|expected:|AssertionError|tests? completed' "$LOG" | sed 's/^/  /' | head -30
exit 1
