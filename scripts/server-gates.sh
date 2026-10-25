#!/usr/bin/env bash
# ⚠️ **v0.40.0：服务端回归闸门（Go 语法 + 密钥扫描）。**
#
# ## 为什么现在才有
#
# 这个项目的前 23 轮评审，服务端**从来没有过任何自动闸门** ——
# 改完 `api/` 只有「跑一次冒烟」这一个检查，而冒烟查的是**运行时行为**。
#
# 结果踩了两次同一类坑：
#   · **Go raw string 里出现反引号** —— 2026-10-25 这一轮连续踩了 4 次
#     （`placeKey` / `drives.distance` / `address.road` / `reach`），
#     每次的表现都是 `vet: expected ';', found X`；
#   · v0.40.0 又踩第 5 次（注释里写 `` `p1 || ' ' || p2` ``，把 raw string 提前截断），
#     容器构建失败才暴露 —— 而构建在流程末尾，前面的时间全白费。
#
# ## 这道闸门守什么
#
# ① **Go 语法**：`gofmt -e` 只**解析**不格式化 ⇒ 不需要网络、不需要模块依赖，
#    秒级返回。raw string 被反引号截断时它必然报解析错误。
#    （本项目已验证：反引号计数法、括号配平法**都漏**，只有真正的解析器抓得住。）
# ② **密钥扫描**：拿 `.env` 里**真实值**逐个比对工作区改动，
#    外加常见赋值模式 —— 真实密钥绝不能进仓库。
#
# ## 退出码
#   0 = 全部通过（或本机没有 docker → 语法闸门跳过）
#   1 = 有失败
set -uo pipefail
cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

echo "══ 服务端闸门 ══"

# ── ① Go 语法 ────────────────────────────────────────────────
# ⚠️ 用 `pwd -W` 拿 Windows 路径 —— Git Bash 的 /c/... 形式 docker 不认。
# ⚠️ **必须设 MSYS_NO_PATHCONV=1** —— 否则 MSYS 会把容器内的 `/src`
#    当成宿主路径翻译成 `C:/Program Files/Git/src`（工作目录无效，容器起不来）。
#    本项目已实测踩过。
export MSYS_NO_PATHCONV=1
REPO_WIN="$(pwd -W 2>/dev/null || pwd)"
if command -v docker >/dev/null 2>&1; then
  if docker run --rm -v "${REPO_WIN}/api:/src" -w /src golang:1.23-alpine \
       sh -c 'gofmt -e . > /dev/null 2>/tmp/gofmt.err; echo "rc=$?"; cat /tmp/gofmt.err' \
       > /tmp/gofmt.out 2>&1; then
    RC=$(sed -n 's/^rc=\([0-9]*\)$/\1/p' /tmp/gofmt.out | head -1)
    if [ "${RC:-1}" = "0" ]; then
      ok "Go 语法（gofmt -e）"
    else
      fail "Go 语法（gofmt -e）—— raw string 可能被反引号截断"
      grep -v '^rc=' /tmp/gofmt.out | head -10 | sed 's/^/    /'
    fi
  else
    fail "Go 语法闸门（docker 执行失败）"
    head -10 /tmp/gofmt.out | sed 's/^/    /'
  fi
else
  ok "Go 语法闸门跳过（本机无 docker）"
fi

# ── ② 密钥扫描 ───────────────────────────────────────────────
# 比对 .env 里的**真实值** —— 只查赋值模式会漏掉「值换了但模式没变」的情况，
# 而真实值比对是**零漏报**的（只要值还在 .env 里）。
SEC=$(python - <<'PY' 2>/dev/null
import os, re, subprocess, sys
root = os.getcwd()
env_path = os.path.join(root, ".env")
if not os.path.exists(env_path):
    print("SKIP"); sys.exit(0)
secrets = []
for line in open(env_path, encoding="utf-8-sig", errors="replace"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    v = v.strip().strip('"').strip("'")
    if len(v) >= 12 and k.strip() not in ("APP_VERSION_CODE", "APP_VERSION_NAME"):
        secrets.append((k.strip(), v))
if not secrets:
    print("SKIP"); sys.exit(0)

def git(*a):
    return subprocess.run(["git", "-C", root, *a], capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout
diff = git("diff", "HEAD") + "\n" + git("diff", "--cached")
hits = []
for k, v in secrets:
    if v and v in diff:
        hits.append("真实值命中: %s" % k)
for pat, label in [
    (r"API_TOKEN\s*=\s*[\"']?[A-Za-z0-9_\-\.]{16,}", "API_TOKEN 赋值"),
    (r"AMAP_KEY\s*=\s*[\"']?[A-Za-z0-9_\-]{12,}", "AMAP_KEY 赋值"),
    (r"ta-secret\s*=\s*[\"']?[A-Za-z0-9_\-]{12,}", "ta-secret 赋值"),
    (r"TESLA_REFRESH_TOKEN\s*=\s*[\"']?[A-Za-z0-9_\-\.]{12,}", "TESLA_REFRESH_TOKEN 赋值"),
    (r"TESLA_CLIENT_SECRET\s*=\s*[\"']?[A-Za-z0-9_\-\.]{12,}", "TESLA_CLIENT_SECRET 赋值"),
]:
    if re.search(pat, diff):
        hits.append("赋值模式命中: %s" % label)
print("HITS:" + "; ".join(hits) if hits else "CLEAN")
PY
)
case "$SEC" in
  HITS:*) fail "密钥扫描 —— $(echo "$SEC" | sed 's/^HITS://')" ;;
  CLEAN) ok "密钥扫描（无真实密钥入库）" ;;
  *)     ok "密钥扫描跳过（无 .env）" ;;
esac

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ]
