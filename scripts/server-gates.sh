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
# 两层：
# ① **全量 tracked 树**扫描**项目明令禁止入库的 5 个密钥**
#    （API_TOKEN / AMAP_KEY / ta-secret / TESLA_REFRESH_TOKEN / TESLA_CLIENT_SECRET）
#    —— ⚠️ 原来只扫 diff，**历史提交里的密钥永远扫不到**；
# ② **diff** 扫描 `.env` 里的**全部**真实值（任何新值都不能进仓库）。
#
# ⚠️ 已知豁免：`.env` 里的 `POSTGRES_PASSWORD` / `TESLAMATE_DB_PASSWORD` /
# `GF_SECURITY_ADMIN_PASSWORD` 是**弱默认密码**（`ChangeMe_*`），
# `docs/operations.md` **有意记录**了它们（2026-10-16 决定维持现状，局域网自用）——
# 那条是**决策记录**，不是泄露，故不拦。
SEC=$(python - <<'PY' 2>/dev/null
import io, os, re, subprocess, sys
root = os.getcwd()
FORBIDDEN_KEYS = ("API_TOKEN", "AMAP_KEY", "ta-secret",
                  "TESLA_REFRESH_TOKEN", "TESLA_CLIENT_SECRET")
env = os.path.join(root, ".env")
if not os.path.exists(env):
    print("SKIP"); sys.exit(0)

secrets = []
for line in io.open(env, encoding="utf-8-sig", errors="replace"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    v = v.strip().strip('"').strip("'")
    if len(v) >= 12:
        secrets.append((k.strip(), v))

def git(*a):
    return subprocess.run(["git", "-C", root, *a], capture_output=True).stdout

hits = []

# ① 全量 tracked 树：只看**明令禁止**的密钥
tracked = [f.decode("utf-8", "replace")
           for f in git("ls-files", "-z").split(b"\0") if f]
for rel in tracked:
    fp = os.path.join(root, rel.replace("/", os.sep))
    if not os.path.isfile(fp):
        continue
    try:
        s = io.open(fp, encoding="utf-8", errors="replace").read()
    except Exception:
        continue
    for k, v in secrets:
        if k in FORBIDDEN_KEYS and v in s:
            hits.append("全量扫描命中 %s（%s）" % (k, rel))

# ② diff：任何 .env 真实值都不能进
diff = (git("diff", "HEAD") + git("diff", "--cached")).decode("utf-8", "replace")
for k, v in secrets:
    if v and v in diff:
        hits.append("改动里命中真实值 %s" % k)
for pat, label in [
    (r"API_TOKEN\s*=\s*[\"']?[A-Za-z0-9_\-\.]{16,}", "API_TOKEN 赋值"),
    (r"AMAP_KEY\s*=\s*[\"']?[A-Za-z0-9_\-]{12,}", "AMAP_KEY 赋值"),
    (r"ta-secret\s*=\s*[\"']?[A-Za-z0-9_\-]{12,}", "ta-secret 赋值"),
    (r"TESLA_REFRESH_TOKEN\s*=\s*[\"']?[A-Za-z0-9_\-\.]{12,}", "TESLA_REFRESH_TOKEN 赋值"),
    (r"TESLA_CLIENT_SECRET\s*=\s*[\"']?[A-Za-z0-9_\-\.]{12,}", "TESLA_CLIENT_SECRET 赋值"),
]:
    if re.search(pat, diff):
        hits.append("改动里命中赋值模式 %s" % label)

print("HITS:" + "; ".join(hits) if hits else "CLEAN")
PY
)
case "$SEC" in
  HITS:*) fail "密钥扫描 —— $(echo "$SEC" | sed 's/^HITS://')" ;;
  CLEAN) ok "密钥扫描（无真实密钥入库）" ;;
  *)     ok "密钥扫描跳过（无 .env）" ;;
esac

# ── ③ 仓库卫生（二进制产物不得入库）──────────────────────────────
# ⚠️ **v0.72.0**：这个检查是被一次真实疏漏逼出来的 ——
# `mcp/__pycache__/teslamate_mcp.cpython-313.pyc` 在 v0.66.0 就被提交进去了
# （`git add -A` 把编译产物一起带上了），v0.72.0 又带进去两个。
#
# 字节码进库的后果：
#   · 仓库体积被无意义地撑大；
#   · **它可能含字符串常量** —— 哪天哪个脚本里写了密钥，字节码里就有副本，
#     而密钥扫描扫的是**文本文件**，扫不到 .pyc ⇒ **绕过密钥闸门**；
#   · 每次改源码都会产生一个「变了」的二进制 ⇒ diff 全是噪声。
HYG=$(python - <<'PY' 2>/dev/null
import subprocess, sys
root = __import__("os").getcwd()
out = subprocess.run(["git", "-C", root, "ls-files"], capture_output=True).stdout
bad = []
for raw in out.decode("utf-8", "replace").splitlines():
    rel = raw.strip()
    if not rel:
        continue
    low = rel.lower()
    if ("__pycache__/" in low or low.endswith((".pyc", ".pyo", ".class", ".jar"))
            or low.endswith((".apk", ".aab", ".so", ".dll", ".exe"))):
        bad.append(rel)
print("HITS:" + "; ".join(bad[:8]) if bad else "CLEAN")
PY
)
case "$HYG" in
  HITS:*) fail "仓库卫生 —— 有二进制产物被跟踪：$(echo "$HYG" | sed 's/^HITS://')"
          echo "    ⇒ git rm --cached <文件>  并在 .gitignore 里加规则" ;;
  CLEAN) ok "仓库卫生（无二进制产物入库）" ;;
  *)     ok "仓库卫生跳过" ;;
esac

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ]
