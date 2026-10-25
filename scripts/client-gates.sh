#!/usr/bin/env bash
# 客户端回归闸门 —— 界面文案与几何的**源码级**闸门。
#
# ⚠️ v0.37.0 起，客户端**已经有 JVM 单元测试**了
# （`app-android/app/src/test/`，接进 `scripts/unit-tests.sh` + `scripts/smoke.sh`）。
# 那边的闸门**直接调用**纯函数（比这里的正则解析源码更可靠）；
# 这里保留两条**覆盖面不同**的检查：
#   ① 界面文案里不许出现 Markdown 星号（要扫**全部** .kt 源码，JVM 测试做不到）；
#   ② 电量环几何金标准（正则从源码里抠常量，编译期也做不到）。
#
# 用法：bash scripts/client-gates.sh      （在仓库根）
set -uo pipefail
cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

PY=$(command -v python || command -v python3 || true)
export PYTHONIOENCODING=utf-8
if [ -z "$PY" ]; then
  echo "  ✗ 找不到 python，跳过客户端闸门"
  exit 1
fi

echo "══ 客户端闸门 ══"

# ── 闸门 1：界面文案里不许出现 Markdown 星号 ──────────────────────
#
# ⚠️ 仓库自己在 `ui/RoutePlan.kt` 写过这条教训：
#   「Compose 的 Text **不解析 Markdown** —— 之前写了 \*\*粗体\*\*，
#     结果界面上直接出现两个星号。」
# 而 v0.30.0 又在同一文件里写了 `**最长一段 348 公里**` —— 同一类错误。
#
# 所以把它固化成闸门：**字符串字面量里出现 `**` 就失败**（注释不算）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')
bad = []
for p in root.rglob('*.kt'):
    for i, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            continue
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
            if '**' in lit:
                bad.append('%s:%d: %s' % (p.name, i, lit[:70]))
if bad:
    print('界面文案里出现 Markdown 星号（Compose 不解析，会原样显示）:')
    for b in bad[:8]:
        print('   ', b)
    sys.exit(1)
PYEOF
if [ $? -eq 0 ]; then ok "界面文案无 Markdown 星号"; else fail "界面文案出现 Markdown 星号（Compose 不解析，会原样显示）"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 2：电量环几何金标准 ────────────────────────────────────
#
# v0.30.0 把电量环数字固定成 28sp、环直径改成由文字反推，
# 目的是让车况页**只剩一个** 44sp 主角（原来 39.48sp 的环内数字
# 与 44sp 主角只差 10.3%，层级不成立）。
#
# 这条闸门把「环在各种字号下的直径」与「主角不被裁」两条不变量钉死：
# - 主角「204」在 393dp / 360dp 两种屏宽、fs=1.34 下都**不被裁**；
# - 环的直径与 v0.30.0 的取值一致（改了公式就会红）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, sys
src = open('app-android/app/src/main/java/com/teslamate/app/ui/Widgets.kt', encoding='utf-8').read()
m = re.search(r'val ringTextSp = ([0-9.]+)f', src)
text_sp = float(m.group(1)) if m else None
body = src.split('fun socRingDiameterDp')[1][:800] if 'fun socRingDiameterDp' in src else ''
c = re.search(r'\(textRenderDp \* ([0-9.]+)f\)', body)
coef = float(c.group(1)) if c else None
hi = re.search(r'coerceAtMost\(([0-9.]+)f\)', body)
maxs = float(hi.group(1)) if hi else None
# 最小直径是**参数**（coerceAtLeast(minSizeDp.toFloat())），
# 它的默认值在 SocRing 的签名里：`baseSizeDp: Int = 140`。
d = re.search(r'baseSizeDp: Int = ([0-9]+)', src)
mins = float(d.group(1)) if d else None
if None in (text_sp, coef, mins, maxs):
    print('解析不到 socRingDiameterDp 的常量（公式被改了？）')
    sys.exit(1)
def ring(fs):
    return min(max(text_sp * fs * coef, mins), maxs)
expect = {1.0: 140.0, 1.2: 140.0, 1.35: 140.0, 1.5: 142.8, 1.75: 166.6, 2.0: 190.4}
for fs, want in sorted(expect.items()):
    got = round(ring(fs), 1)
    if abs(got - want) > 0.05:
        print('socRingDiameterDp(%s) = %s，期望 %s' % (fs, got, want))
        sys.exit(1)
hero_w = 3 * 0.56 * 44
for screen in (393, 360):
    avail = screen - 32 - ring(1.34) - 28
    if avail < hero_w * 1.34:
        print('%ddp 屏在 fs=1.34 主角会被裁（可用 %.1fdp < 需要 %.1fdp）' % (screen, avail, hero_w * 1.34))
        sys.exit(1)
PYEOF
if [ $? -eq 0 ]; then ok "电量环几何金标准（环直径 + 主角不裁切）"; else fail "电量环几何金标准"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 3：源码里不许有 U+FFFD 替换字符 ────────────────────────
#
# ⚠️ **v0.40.0：踩到了才补上。**
# 编辑中文源码时（尤其是含全角标点、破折号的注释），
# 个别字符会被写成 **U+FFFD（U+FFFD）** —— 它是「无法解码的字节」的替身。
#
# 危害：① 编译**照样通过**（在注释里），② 但界面上/文档里会出现「[U+FFFD]」；
# ③ 更糟的是**字符串字面量**里出现它 ⇒ 界面直接印出「[U+FFFD]」。
#
# 已实测抓到 6 处历史损坏（api/battery_decay.go、api/climb.go、
# Haptics.kt、DrivesScreen.kt、RecordsScreen.kt、docs/operations.md）——
# 全在注释里，但都是**人读**的东西。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import io, os, pathlib, sys
root = pathlib.Path('.')
exts = {'.kt', '.kts', '.go', '.sh', '.md', '.yaml', '.yml', '.json', '.xml', '.sql'}
skip = {'.git', 'build', '.gradle', 'node_modules', '.mimosa', '.zcode', 'apk'}
bad = []
n = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in skip]
    for fn in filenames:
        if os.path.splitext(fn)[1].lower() not in exts:
            continue
        try:
            s = io.open(os.path.join(dirpath, fn), encoding='utf-8').read()
        except Exception:
            continue
        n += 1
        if '\ufffd' in s:
            i = s.index('\ufffd')
            ln = s.count('\n', 0, i) + 1
            bad.append('%s:%d' % (os.path.relpath(os.path.join(dirpath, fn), root), ln))
if bad:
    print('源码里出现 U+FFFD 替换字符（编辑中文时字符损坏）：')
    for b in bad[:10]:
        print('   ', b)
    sys.exit(1)
print('scanned %d files' % n)
PYEOF
if [ $? -eq 0 ]; then ok "源码无 U+FFFD 损坏字符"; else fail "源码出现 U+FFFD 损坏字符"; cat "$TMP"; fi
rm -f "$TMP"

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ 客户端闸门通过" || echo "❌ 客户端闸门失败"
exit "$FAIL"
