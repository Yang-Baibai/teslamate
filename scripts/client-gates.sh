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

# ── 闸门 4：动效一致性（第 28 轮 HIG 评审 P1-2/P1-3/P1-4）──────────
#
# ⚠️ **v0.49.0：踩到了才补上。**
# 第 28 轮 HIG 评审查出三个「规格漂移」，全都不是编译期能抓的：
#
# ① 同一个「展开」动作有**三套**动画规格：
#    `Widgets.CollapsibleSection` 与 `RoutePlan` 是 tween(280ms)，
#    `StatusScreen` 的地图展开**完全没传 animationSpec**（框架默认 spring + fadeIn 150ms）。
#    ⇒ 用户在两个页面展开同一个东西，手感**不是同一个动作**。
# ② 全 App 的曲线是 **Material 3 的 `FastOutSlowInEasing`**，
#    而 `Motion.kt` 里注释宣称要用的 iOS `EaseOut` **零引用** ——
#    等于整 App 的运动语言**换了血统**。
# ③ `Charts.kt` 硬编码 `tween(durationMillis = 520)`，
#    而 `Motion.ChartGrow = 520` 就在旁边躺着没人用。
#
# 这三条都**编译通过、单测全绿**，只有源码级正则能抓到 ⇒ 固化成闸门。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui')
files = {p.name: p.read_text(encoding='utf-8', errors='replace') for p in root.glob('*.kt')}
problems = []

# ① 三处「展开」的规格必须**逐字相同**
spec = {}
for name, s in files.items():
    for m in re.finditer(
            r'enter = fadeIn\(Motion\.FadeIn\) \+ expandVertically\(([^)]*)\),\s*\n'
            r'\s*exit = fadeOut\(tween\(Motion\.ExpandFadeOut\)\) \+ shrinkVertically\(([^)]*)\),', s):
        spec.setdefault((m.group(1).strip(), m.group(2).strip()), []).append(name)
if len(spec) != 1:
    problems.append('「展开」的动画规格不统一：%s' % spec)
elif len(list(spec.values())[0]) < 3:
    problems.append('「展开」的统一规格只出现在 %d 处（应至少 3 处）' % len(list(spec.values())[0]))
# 不得再有「完全没传 spec」的展开
for name, s in files.items():
    for i, line in enumerate(s.splitlines(), 1):
        st = line.strip()
        if st.startswith('//') or st.startswith('*'):
            continue
        if 'expandVertically() + fadeIn()' in line or 'shrinkVertically() + fadeOut()' in line:
            problems.append('%s:%d 展开没传 animationSpec（框架默认 ≠ 其它地方）' % (name, i))

# ② 不得再有 Material 曲线（注释里的历史说明不算）
for name, s in files.items():
    for i, line in enumerate(s.splitlines(), 1):
        st = line.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue
        if 'FastOutSlowInEasing' in line:
            problems.append('%s:%d 还在用 Material 曲线 FastOutSlowInEasing（应走 Motion.EaseOut）' % (name, i))

# ③ 不得有绕过 token 的硬编码时长
for name, s in files.items():
    for i, line in enumerate(s.splitlines(), 1):
        st = line.strip()
        if st.startswith('//') or st.startswith('*'):
            continue
        for m in re.finditer(r'tween\(\s*(?:durationMillis\s*=\s*)?([0-9]+)\s*[,)]', line):
            problems.append('%s:%d 硬编码时长 %s（应走 Motion 的 token）' % (name, i, m.group(1)))

# ④ 闲置 token：定义了却没有任何引用 = 规格漂移
#    ⚠️ 引用**包括 Motion.kt 内部**（比如 `FadeIn` 用的是 `ExpandFadeIn`，
#    它是裸名引用，不带 `Motion.` 前缀）—— 只算外部引用会误报。
motion = files.get('Motion.kt', '')
for tok in re.findall(r'\b(?:val|const val) ([A-Z][A-Za-z0-9_]*)\s*[:=]', motion):
    if tok in ('EaseOut', 'Linear'):
        continue
    used = sum(s.count('Motion.' + tok) for n, s in files.items() if n != 'Motion.kt')
    used += motion.count(tok)          # Motion.kt 内部的裸名引用
    if used <= 1:                      # 只剩定义本身那一次
        problems.append('Motion.%s 零引用（定义了但没人用 = 规格漂移）' % tok)

if problems:
    print('动效一致性不达标：')
    for p in problems[:12]:
        print('   ', p)
    sys.exit(1)
PYEOF
if [ $? -eq 0 ]; then ok "动效一致性（三处展开同规格 + iOS 曲线 + 无硬编码时长 + 无闲置 token）"; else fail "动效一致性"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 5：可见长说明块（棘轮）──────────────────────────────────
#
# ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**。
#
# 车主原话：「界面美观性上没什么问题，但**太啰嗦了**，细节上的文字直接展示出来的太多了，
# 比如驾驶画像的平顺分的解释，应该隐藏起来，需要我点击一个小角标才会展示。
# 类似于每个指标的注释那种感觉。」
#
# 排查结论：App 里当时有 **36 块**「直接可见的长说明」（≥22 汉字的 Text 块），
# 合计 2211 字，分布在 10 个文件。本轮把它们折进 `InfoNote` / `TextWithNote` /
# `KeyValueWithNote`（ⓘ 角标 + 点开就地展开）⇒ 降到 **11 块 / 513 字**。
#
# 这条闸门是**棘轮**：把「可见长说明块数」钉在预算上。
# 以后再往界面上平铺一段解释，这道闸门立刻红 ——
# 逼着作者先想清楚「这是补充说明（该折）还是用户要马上知道的事（该留）」。
#
# ⚠️ 确实需要常驻长文时（如新的告警），**有意**调高 BUDGET 并在 CHANGELOG 写明理由。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys, os
CJK = re.compile(r'[\u4e00-\u9fff]')
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
# ⚠️ **v0.74.0**：模板占位符 —— `${…}` 与 `$var` 两种写法都要认。
TPL = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')
# ⚠️ **v0.74.0（模拟器真机实测发现的盲点）：从「数字预算」改成「基线棘轮」。**
#
# ## 为什么旧口径是假的
#
# 旧口径只数**汉字**（`len(CJK.findall(plain)) >= 22`）——
# 于是**数字很多**的句子它一概看不见，
# 而恰恰是这类句子最容易在手机上折行断句。
#
# 实锤（模拟器 1440×3200 截图）：
# 「停过 28 次 · 累计 1330 小时（最长一次 248 小时） · 上次 2026-10-25」
# 只有 **15 个汉字**（旧口径：看不见），视觉宽度 **50**（新口径：抓住）——
# 屏幕上的实际效果是「（最长一次 248 小 / 时）」，括号被折断。
#
# 改成**视觉宽度**（汉字/全角 = 2，半角 = 1，阈值 44 ≈ 22 个全角）后，
# 实测常驻长说明块 **18 块** —— 真机 UI 树实测对应 **14 块**渲染长文，同一量级。
# ⇒ 旧口径那个「2 块」是个**假数字**：闸门当时形同虚设。
#
# ## 新口径：基线棘轮
#
# 预算不再是魔法数字，而是**登记在册的基线**（`scripts/verbose-baseline.txt`）：
# - 基线里有 ⇒ 放行（已知且审议过）；
# - **基线里没有 ⇒ 闸门红**：必须先想清楚「这是补充说明（该折）还是用户要马上知道的事（该留）」，
#   确实该留就**显式**往基线里加一行 —— 等于一次**有记录的**决策；
# - 基线里有、代码里已经没了 ⇒ 提示可以删（收缩是好事）。
#
# 这样「不许再往界面上铺解释」这条约束**第一次真的成立**。
BASE = pathlib.Path('scripts/verbose-baseline.txt')
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')

# ⚠️ **v0.71.0**：折叠组件 —— 里面的内容**默认看不见**，不算「常驻可见」。
FOLDED_CALLS = ('InfoNote(', 'TextWithNote(', 'KeyValueWithNote(', 'NoteBody(',
                'CollapsibleSection(')

def is_comment(ln):
    s = ln.strip()
    return s.startswith('//') or s.startswith('*') or s.startswith('/*')

def folded_spans(lines):
    """折叠组件覆盖到的行号集合（含尾随 lambda 的内容体）。"""
    covered = set()
    i = 0
    while i < len(lines):
        st = lines[i].strip()
        if any(st.startswith(s) for s in FOLDED_CALLS):
            depth = 0
            j = i
            while j < len(lines):
                if not is_comment(lines[j]):
                    depth += lines[j].count('(') - lines[j].count(')')
                if j > i and depth <= 0:
                    break
                j += 1
            covered.update(range(i, j + 1))
            # ⚠️ `CollapsibleSection("x") { … }` 的**内容体在括号之外**
            tail = lines[j - 1].split(')')[-1] if j - 1 >= i else ''
            if '{' in tail:
                bd = 0
                k = j - 1
                while k < len(lines):
                    if not is_comment(lines[k]):
                        bd += lines[k].count('{') - lines[k].count('}')
                    if k > j - 1 and bd <= 0:
                        break
                    k += 1
                covered.update(range(j - 1, k + 1))
            i = j + 1
            continue
        i += 1
    return covered

def vwidth(s):
    """**视觉宽度**：汉字/全角算 2，半角（ASCII 数字、字母）算 1，空格不算。

    ⚠️ **v0.74.0（模拟器真机实测发现的盲点）**：
    原来这条闸门只数**汉字**（`len(CJK.findall(plain)) >= 22`），
    于是**数字很多**的句子它一概看不见 ——
    而恰恰是这类句子最容易在手机上折行断句。

    实锤（模拟器 1440×3200 截图）：「停过 28 次 · 累计 1330 小时（最长一次 248 小时） · 上次 2026-10-25」
    只有 **15 个汉字**（旧口径：看不见），
    但视觉宽度 **50**（新口径：抓住）——
    屏幕上的实际效果是「（最长一次 248 小 / 时）」，括号被折断。

    ⇒ 度量改成**视觉宽度**，阈值 44（= 22 个全角，与旧口径等价）。
    """
    w = 0
    for ch in s:
        if ch.isspace():
            continue
        w += 2 if ('\u2e80' <= ch <= '\u9fff' or '\uff00' <= ch <= '\uffef') else 1
    return w


def visible_long_blocks(src):
    lines = src.splitlines()
    folded = folded_spans(lines)
    out = []
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith('Text('):
            depth = 0
            lits = []
            j = i
            while j < len(lines):
                ln = lines[j]
                # ⚠️ **v0.71.0 修闸门计数 bug**：**注释行不参与括号配平**。
                # 原来注释里的括号被当成代码括号 —— 例如 ChargesScreen.kt
                # 的注释「`$` 后直接跟 `(` 不是合法的字符串模板」，
                # 那个 `(` 让配平永远归不了零，
                # 于是后面十几行被吞进同一个「块」，那一块被算成 64 字。
                if not is_comment(ln):
                    # ⚠️ **v0.74.0**：先把模板占位符**整段替换掉**，再取字面量 ——
                    # 否则 `${if (x) "国标直流" else ""}` 这种**表达式里**的字面量
                    # 会被当成界面上真的显示出来的字（实测把「充电中」误判成超长块）。
                    lits += LIT.findall(TPL.sub('x' * 5, ln))
                    depth += ln.count('(') - ln.count(')')
                if j > i and depth <= 0:
                    break
                j += 1
            if i not in folded:
                # ⚠️ **v0.74.0**：`${…}` 占位符按 **5 个单位**算，
                # 而不是 1 个 —— 运行时它会渲染成「1661」「19.2%」「120–130」这种长度。
                # 按 1 个算 ⇒ 「充电中${if (…)} else …}」这种**渲染后只有 3 个字**的块
                # 会被误判成超长。
                plain = ''.join(lits)
                if vwidth(plain) >= 44:
                    # ⚠️ 基线里存**可读形态**（占位符还原成 …），人能看懂、能手改。
                    shown = plain.replace('x' * 5, '…')
                    out.append((i + 1, shown, shown[:50]))
            i = j + 1
            continue
        i += 1
    return out


# ── 当前实际存在的常驻长说明块 ──
cur = {}          # key -> (file, line, 显示文本)
for p in sorted(root.rglob('*.kt')):
    for ln, shown, disp in visible_long_blocks(p.read_text(encoding='utf-8', errors='replace')):
        cur['%s::%s' % (p.name, shown)] = (p.name, ln, disp)

# ── 基线 ──
base_lines = []
if BASE.exists():
    for ln in BASE.read_text(encoding='utf-8', errors='replace').splitlines():
        s = ln.strip()
        if s and not s.startswith('#'):
            base_lines.append(s)
base = set(base_lines)

new_keys = [k for k in cur if k not in base]
stale = [b for b in base_lines if b not in cur]

# ⚠️ 维护用：TM_BASELINE_DUMP=1 时把当前清单导出到 .new（**不覆盖基线**），
# 由人看过之后再决定要不要挪进 verbose-baseline.txt —— 不做「一键放行」。
if os.environ.get('TM_BASELINE_DUMP') == '1':
    with open(str(BASE) + '.new', 'w', encoding='utf-8') as f:
        f.write('# ⚠️ v0.74.0：常驻可见长说明块基线（每行一条，已审议过、允许常驻）。\n')
        f.write('# 新增一条 = 一次有记录的决策：为什么它该常驻而不是折进 ⓘ。\n')
        for k in sorted(cur):
            f.write(k + '\n')
    print('已导出 %d 条到 %s.new（人工审阅后再挪进基线）' % (len(cur), BASE))
    sys.exit(0)

if new_keys:
    print('新增未登记的常驻长说明块 %d 处 —— 要么折进 ⓘ，要么显式登记进基线：' % len(new_keys))
    for k in new_keys[:14]:
        f, ln, shown = cur[k]
        print('   %s:%d  %s' % (f, ln, shown))
    print('   ⇒ 口径说明请折进 InfoNote / TextWithNote / KeyValueWithNote（ⓘ 角标）')
    print('   ⇒ 确实该常驻，就把这一行**原样**加进 %s' % BASE)
    print('     （加基线 = 一次有记录的决策，CHANGELOG 里要写明理由）')
    sys.exit(1)

print('常驻长说明块 %d 块（基线 %d 条）—— 没有新增' % (len(cur), len(base)))
if stale:
    print('   ℹ 基线里有 %d 条代码里已经没有了 —— 可以从 %s 删掉（收缩是好事）' % (len(stale), BASE))
PYEOF
if [ $? -eq 0 ]; then ok "啰嗦棘轮（常驻长说明块必须登记在基线里）"; else fail "啰嗦棘轮（新增未登记的常驻长说明块）"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 6：孤儿长文字（堵住闸门 5 的盲点）────────────────────────
#
# ⚠️ **v0.69.0（真机 UI 树实测发现的审计盲点）**。
#
# 闸门 5 只统计 `Text(` 块**内部**的字面量。但 `BatteryDecayText.lines(d)`
# 这种**辅助函数**产出的长文字，经 `Text(line)` 渲染后**一样直接铺在界面上**，
# 闸门 5 却完全看不见 ——
# 真机实测时，电池卡底部那三段灰字合计 **101 个汉字**，正是这样漏掉的。
#
# 本闸门补这一类：
#   ① 一个 .kt 文件若**不含 `Text(` 调用** ⇒ 它不直接画界面，是「显示文本提供者」；
#   ② 这样的文件里，≥22 汉字的字面量必须落在**折叠提供器**里
#      （函数名以 note/caveat/hint/detail/desc/summary/explain 开头）；
#   ③ 这样「这段文字是折的」从**靠自觉**变成**可静态检查的契约** ——
#      把长文字挪回 `fun lines()` 就会被这条闸门抓出来。
#
# 豁免：注释行；**错误消息**（throw / raise）——
#       按约定「错误 / 空态保持直接可见」，不受此闸门管。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys
CJK = re.compile(r'[\u4e00-\u9fff]')
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')
FOLD_NAMES = ('note', 'caveat', 'hint', 'detail', 'desc', 'summary', 'explain')

def throw_spans(lines):
    """throw / raise 表达式覆盖到的行号集合（错误消息按约定直接可见）。

    ⚠️ **v0.71.0**：注释行不参与括号配平 —— 否则注释里写着一段代码示例时，
    它的括号会把后面几十行都算进「异常消息」里。
    """
    out = set()
    for i, ln in enumerate(lines):
        if ln.strip().startswith('//'):
            continue
        if 'throw ' in ln or 'raise ' in ln:
            depth = ln.count('(') - ln.count(')')
            j = i
            while depth > 0 and j + 1 < len(lines):
                j += 1
                nxt = lines[j]
                if not nxt.strip().startswith('//'):
                    depth += nxt.count('(') - nxt.count(')')
            out.update(range(i, j + 1))
    return out

def enclosing_func(lines, idx):
    for i in range(idx, -1, -1):
        m = re.match(r'\s*(?:private |public |internal |protected |suspend |inline |operator )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', lines[i])
        if m:
            return m.group(1)
    return None

bad = []
for p in sorted(root.rglob('*.kt')):
    src = p.read_text(encoding='utf-8', errors='replace')
    if 'Text(' in src:
        continue          # 直接画界面的文件 —— 归闸门 5 管
    lines = src.splitlines()
    exc = throw_spans(lines)
    for idx, ln in enumerate(lines):
        st = ln.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue
        if idx in exc:
            continue      # 错误消息：按约定保持直接可见
        for lit in LIT.findall(ln):
            n = len(CJK.findall(lit))
            if n < 22:
                continue
            fn = enclosing_func(lines, idx)
            if fn and fn.lower().startswith(FOLD_NAMES):
                continue  # 折叠提供器
            bad.append('%s:%d  %2d字  fun=%s  %s' % (p.name, idx + 1, n, fn or '(无)', lit[:48]))

if bad:
    print('孤儿长文字 %d 处 —— 这些字**直接铺在界面上**，闸门 5（Text( 块）看不见：' % len(bad))
    for b in bad[:14]:
        print('   ', b)
    print('   ⇒ 折进 InfoNote / TextWithNote / KeyValueWithNote（ⓘ 角标）')
    print('   ⇒ 或放进折叠提供器（函数名以 note/caveat/hint/detail/desc/summary/explain 开头）')
    print('   ⇒ 错误消息（throw / raise）按约定保持直接可见，不受此闸门管')
    sys.exit(1)
print('孤儿长文字 0 处（显示文本提供者的长文字都在折叠提供器里）')
PYEOF
if [ $? -eq 0 ]; then ok "孤儿长文字（辅助函数渲染的可见长文也受管）"; else fail "孤儿长文字"; cat "$TMP"; fi
rm -f "$TMP"

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ 客户端闸门通过" || echo "❌ 客户端闸门失败"
exit "$FAIL"
