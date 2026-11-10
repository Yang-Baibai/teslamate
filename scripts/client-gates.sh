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
#
# ⚠️ **v0.90.0：这道闸门原来只扫 `.kt`，漏了服务端。**
#
# App 有一批文案**不在 App 里**，而是服务端下发、客户端原样印出来的
# （电池衰减的 `caveat` / `method`、能耗档位的 `Note`……）。
# 服务端那些中文里也带着 Markdown 粗体标记 —— 真机实测（模拟器 1440×3200）：
# 电池卡「怎么算的」里整段印着 `它**可能不是**电池变化`、`⇒ **检不出**`。
# **客户端闸门全是绿的，而手机上有星号。**
#
# ⇒ 这道闸门现在同时扫两侧：
#   · 客户端 `.kt` 的字符串字面量；
#   · 服务端 `.go` 的字符串字面量（它会原样进到界面上）。
#
# ⚠️ **例外**：`***` 是密钥打码用的掩码（api/tesla.go、api/tesla_command.go），
# 那是**故意的**，不是 Markdown —— 所以先去掉 `***` 再判 `**`。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys

# ── 客户端 .kt ──────────────────────────────────────────────
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')
bad = []
for p in root.rglob('*.kt'):
    for i, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            continue
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
            if '**' in lit:
                bad.append('app-android/%s:%d: %s' % (p.name, i, lit[:70]))

# ── 服务端 .go（它下发的中文会被 App 原样印出来）─────────────
groot = pathlib.Path('api')
for p in groot.rglob('*.go'):
    for i, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        s = line.strip()
        if s.startswith('//') or s.startswith('*') or s.startswith('/*'):
            continue
        for lit in re.findall(r'"((?:[^"\\]|\\.)*)"', line):
            # `***` 是打码掩码，不是 Markdown ⇒ 先摘掉再判
            if '**' in lit.replace('***', ''):
                bad.append('api/%s:%d: %s' % (p.name, i, lit[:70]))

if bad:
    print('界面文案里出现 Markdown 星号（Compose 不解析，会原样显示）:')
    for b in bad[:8]:
        print('   ', b)
    sys.exit(1)
PYEOF
if [ $? -eq 0 ]; then ok "界面文案无 Markdown 星号（客户端 .kt + 服务端 .go）"; else fail "界面文案出现 Markdown 星号（Compose 不解析，会原样显示）"; cat "$TMP"; fi
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
    # ⚠️ **v0.138.0**：`val` 里**被渲染**的长句也要算进来 ——
    #
    # 原来这条闸门只扫 `Text(` 块 ⇒ 「`val x = "…长句…"` + 后面 `Text(x)`」
    # 这种写法**完全看不见** ⇒ 啰嗦棘轮的预算被低估（这类长句是裸奔的）。
    #
    # 判据：**只有真的被 `Text(` 引用**的 val 才算 —— 否则会误报一堆非界面字符串。
    # ⚠️ 注释行不参与（否则注释里提一句 `Text(x)` 就会误判）。
    rendered = set()
    for ln in lines:
        if is_comment(ln):
            continue
        rendered.update(re.findall(r'Text\(\s*([A-Za-z_][A-Za-z0-9_]*)', ln))
    if rendered:
        for ln in lines:
            if is_comment(ln):
                continue
            m = re.match(r'[ \t]*(?:private |internal |public )?val ([A-Za-z_][A-Za-z0-9_]*)[^=\n]*=[ \t]*("(?:[^"\\]|\\.)*")', ln)
            if not m:
                continue
            name, lit = m.group(1), m.group(2)
            if name not in rendered:
                continue
            plain = ''.join(LIT.findall(TPL.sub('x' * 5, lit)))
            if vwidth(plain) >= 44:
                shown = plain.replace('x' * 5, '…')
                out.append((lines.index(ln) + 1, shown, shown[:50]))
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

# ── 闸门 7：Kotlin 字符串模板里的「$对象.属性」──────────────────────
#
# ⚠️ **v0.74.0（模拟器 UI 树实测发现的真 bug）**
#
# Kotlin 的**简单模板** `$identifier` **只取标识符** ——
# 后面那个 `.prop` 是**字面文本**，不是属性访问：
#
#     val s = RouteTemplate(...)            // data class
#     "$s.stopCount 次沿途充电"
#     ⇒ 渲染成「<RouteTemplate 的整个 toString()>.stopCount 次沿途充电」
#
# 而 `"${s.stopCount}"`（带花括号）才是属性访问。
#
# **实锤**：线路余量表的**决策句**那个 TextView 高 **1574px**，
# 内容是「RouteTemplate(key=…, legs=[…], stops=[JourneyStop(charge=Charge(id=66, …)])）」
# —— 3000+ 字符的**数据转储**被直接打进界面；TalkBack 会把这一整坨念出来。
#
# ⚠️ 这类写法**在任何情况下都是 bug**（想拿属性访问必须写 `${…}`），
# 所以本闸门**零容忍**：出现即红。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
# 简单模板 `$标识符` 后面紧跟 `.属性名`
PAT = re.compile(r'\$([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)')
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')

bad = []
for p in sorted(root.rglob('*.kt')):
    for idx, ln in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        st = ln.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue
        for lit in LIT.findall(ln):
            # 跳过被转义的 \$（那是字面的美元符号）
            for m in PAT.finditer(lit):
                start = m.start()
                if start > 0 and lit[start - 1] == '\\':
                    continue
                bad.append('%s:%d  $%s.%s  ⇒ 写成 ${%s.%s}' % (
                    p.name, idx, m.group(1), m.group(2), m.group(1), m.group(2)))
                break

if bad:
    print('字符串模板里的「$对象.属性」%d 处 —— Kotlin 只会替换 $对象，' % len(bad))
    print('后面那段是**字面文本** ⇒ 整个 data class 的 toString() 会被打进界面：')
    for b in bad[:14]:
        print('   ', b)
    print('   ⇒ 改用花括号："${对象.属性}"')
    sys.exit(1)
print('字符串模板 0 处「$对象.属性」（Kotlin 里那永远是字面文本，不是属性访问）')
PYEOF
if [ $? -eq 0 ]; then ok "字符串模板（没有 $对象.属性 这种字面文本写法）"; else fail "字符串模板「\$对象.属性」"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 8：APK 的 versionCode 必须与服务端通告的版本对齐 ──────────
#
# ⚠️ **v0.77.0（一次真实的发版事故）**
#
# App 的更新检查是 `if (remote > BuildConfig.VERSION_CODE)` ——
# `remote` 来自服务端 `/api/v1/app/latest` 的 `APP_VERSION_CODE`，
# 而 `BuildConfig.VERSION_CODE` 来自 **APK 里编译进去的** `build.gradle.kts`。
#
# v0.76.0 只改了服务端（发布通告 0.76.0/code 91），
# 却**忘了改 `build.gradle.kts`**（APK 仍是 code 90 / v0.75.0）——
# 于是 App 每次启动都判定「有新版 0.76.0」，
# 用户点更新下载回来的**还是同一个 code 90 的包**，然后继续提示。
# 一个**永远关不掉的更新弹窗**。
#
# 这道闸门把「记得改两处」变成「改一处就会被抓住」：
#   build.gradle.kts 的 versionCode 必须等于 .env 的 APP_VERSION_CODE。
# 没有 .env（远程验证场景）时跳过。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys, os

gradle = pathlib.Path('app-android/app/build.gradle.kts')
if not gradle.exists():
    print('找不到 app-android/app/build.gradle.kts')
    sys.exit(1)
src = gradle.read_text(encoding='utf-8', errors='replace')

def grab(pattern):
    m = re.search(pattern, src)
    return m.group(1).strip() if m else None

code = grab(r'versionCode\s*=\s*(\d+)')
name = grab(r'versionName\s*=\s*"([^"]*)"')
if code is None or name is None:
    print('build.gradle.kts 里读不到 versionCode / versionName')
    sys.exit(1)

env_path = pathlib.Path('.env')
if not env_path.exists():
    print('SKIP')
    sys.exit(0)

remote = None
for line in env_path.read_text(encoding='utf-8-sig', errors='replace').splitlines():
    line = line.strip()
    if line.startswith('APP_VERSION_CODE='):
        remote = line.split('=', 1)[1].strip().strip('"').strip("'")
        break

if remote is None:
    print('SKIP')
    sys.exit(0)

if remote != code:
    print('版本脱节：APK 的 versionCode = %s（versionName %s），' % (code, name))
    print('但服务端 /app/latest 通告的是 %s。' % remote)
    print('后果：App 每次启动都提示「有更新」，下载回来的还是同一个包 —— 弹窗关不掉。')
    print('⇒ 两处必须一起改：app-android/app/build.gradle.kts 与 .env 的 APP_VERSION_CODE。')
    sys.exit(1)
print('APK versionCode=%s 与服务端通告的 %s 一致（versionName %s）' % (code, remote, name))
PYEOF
RC=$?
if grep -q '^SKIP$' "$TMP"; then
  ok "版本对齐闸门跳过（无 .env）"
elif [ $RC -eq 0 ]; then
  ok "版本对齐（APK versionCode 与服务端通告一致）"
else
  fail "版本对齐（APK versionCode 与服务端通告脱节）"
  cat "$TMP" | sed 's/^/    /'
fi
rm -f "$TMP"

# ── 闸门 9：充电结局的**文案**只能来自服务端 ──────────────────────
#
# ⚠️ **v0.78.0（一次真实的假成功）**
#
# 旧版通知里，充电结束**一律**说「✅ 充电完成」——
# 实测本机 2026-10-28 那次超充只充到 80%（上限 100%），通知却说「完成」。
# 假成功的危害：用户看到「完成」就不会再看第二眼。
#
# 修法是**判定与文案都收归服务端**（`charge_outcome.verdict` / `.detail`），
# App / 推送脚本 / Hermes 工具**只读不写**。
#
# ⚠️ 三处各写一份文案，迟早漂移（v0.76.0 已经吃过一次亏：
#    「完成后是什么样」在服务端与客户端各写了一份）。
# 所以这道闸门把「App 不许自己写结局文案」变成契约。
#
# 豁免：注释行不算（那是在解释，不是在界面上显示）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys
LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')

# 服务端拥有的结局文案（api/charge_outcome.go 里的 verdict 常量）。
# ⚠️ v0.79.0：verdict 词表跟着服务端一起改 ——
#   旧词「已充到上限」「没充到上限就停了」「没到充电上限」都拿掉了，
#   现在是「充到上限了 / 充到上限附近 / 没充到上限就结束了 /
#   枪还插着，但充不了了 / 充电桩没有供电 / 结束方式判断不了」。
OWNED = (
    '充到上限了',
    '充到上限附近',
    '没充到上限就结束了',
    '枪还插着，但充不了了',
    '充电桩没有供电',
    '结束方式判断不了',
    # ⚠️ 「正在充电」**不在**这里 —— 它是小组件显示的**状态**，
    #    不是「这次充电的结局」⇒ 属于正常界面文案，不该被这道闸门拦。
    '充电完成',      # ← 旧版那句假成功，永远不该回来
)

bad = []
for p in sorted(root.rglob('*.kt')):
    for idx, ln in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        st = ln.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue
        for lit in LIT.findall(ln):
            for w in OWNED:
                if w in lit:
                    bad.append('%s:%d  含服务端专属文案「%s」: %s' % (p.name, idx, w, lit[:60]))

if bad:
    print('App 自己写了充电结局文案 %d 处 —— 结论必须**只**来自服务端：' % len(bad))
    for b in bad[:14]:
        print('   ', b)
    print('   ⇒ 用 charge_outcome.verdict / charge_outcome.detail（服务端给的原文）')
    print('   ⇒ 「充电完成」更不许出现 —— 没充满时说它就是**假成功**')
    sys.exit(1)
print('App 自己写的结局文案 0 处（结论全部来自服务端）')
PYEOF
if [ $? -eq 0 ]; then ok "结局文案单一来源（App 不自己写充电结局）"; else fail "结局文案单一来源"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 10：里程表那一行的**限定语**不许再和数字拼成一段 ────────────
#
# ⚠️ **v0.94.0（真机实测的折行缺陷）**
#
# 「里程表 101,537 公里（GPS 估算）」原来是一个字符串，渲染在电量环右侧**窄栏**里 ——
# 而整串需要约 168dp、窄栏只有约 171dp，**差 2dp** ⇒ 贪心折行把限定语劈成
# 「（GPS 估」+「算）」（真机截图；v0.92.0 记下、v0.93.0 顺延到本轮修掉）。
#
# CJK 允许在**任意两个汉字之间**断行 ⇒ 只要限定语和数字在**同一个段落**里就可能被劈开；
# 唯一的结构性解法是**限定语独立成段**（`odometerParts()` 的两半 + 两个 Text 节点）。
#
# ⇒ 这道闸门守住的就是这件事：界面里不许再直接用整串 `odometerLine(`。
# 豁免：注释行不算；`Fmt.kt` 里的**定义**不算（那是它本来的职责）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys
root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')

bad = []
for p in sorted(root.rglob('*.kt')):
    if p.name == 'Fmt.kt':
        continue          # 它是定义处，不是界面
    for idx, ln in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
        st = ln.strip()
        if st.startswith('//') or st.startswith('*') or st.startswith('/*'):
            continue      # 注释是在解释，不是在界面上显示
        if 'odometerLine(' in ln:
            bad.append('%s:%d  界面里用了整串 odometerLine( —— 限定语会被折行劈开' % (p.name, idx))

if bad:
    print('里程表那一行又拼回一整串了 %d 处 —— 限定语必须**独立成段**：' % len(bad))
    for b in bad[:14]:
        print('   ', b)
    print('   ⇒ 用 odometerParts() 的两半 + 两个 Text 节点渲染')
    sys.exit(1)
print('里程表那一行的限定语独立成段（界面里没有整串 odometerLine）')
PYEOF
if [ $? -eq 0 ]; then ok "里程表限定语独立成段（不许拼回一整串）"; else fail "里程表限定语独立成段"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 11：续航结论区必须**整宽**（不许回到电量环右侧窄栏）────────────
#
# ⚠️ **v0.95.0（真机实测的折行缺陷）**
#
# 「约 191 ~ 249 公里 · 88 条实测」原来渲染在电量环右侧**窄栏**（实测约 171dp）里 ——
# 它需要约 152dp，加 ⓘ 徽标那一行的 20dp ⇒ **放不下** ⇒ 贪心折行把「88 条实测」
# 劈成「88 条实」+「测」（真机截图；v0.92.0 记下，v0.93.0/v0.94.0 顺延到 v0.95.0 修掉）。
#
# CJK 允许在**任意两个汉字之间**断行 ⇒ 窄栏里**没有任何写法**能保证不被劈开；
# 而且窄栏宽度**随机型与字号变化**（360dp 屏更窄、放大字号更窄）
# ⇒ 任何「刚好放得下」的写法都是**运气**，不是结构。
#
# ⇒ 这道闸门守住两件事：
#   ① 窄栏（`RangeHero`）里**不许**再出现结论行（ⓘ 口径行 / 保底行 / 折叠入口）；
#   ② 结论区（`RangeConclusions`）**必须**撑满整宽。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys

P = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/StatusScreen.kt')
src = P.read_text(encoding='utf-8')


def mask(text):
    """把注释换成空格（字符串原样保留）—— 括号配对时不该被注释里的花括号带偏。"""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(c if c == '\n' else ' ' for c in text[i:j]))
            i = j
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


masked = mask(src)


def body_of(sig):
    """取某个函数的**函数体**（注释已屏蔽 ⇒ 里面的花括号不会干扰配对）。"""
    i = masked.find(sig)
    if i < 0:
        return None
    j = masked.find('{', i)
    if j < 0:
        return None
    depth = 0
    k = j
    while k < len(masked):
        if masked[k] == '{':
            depth += 1
        elif masked[k] == '}':
            depth -= 1
            if depth == 0:
                return masked[j:k + 1]
        k += 1
    return None


bad = []
hero = body_of('private fun RangeHero(')
if hero is None:
    bad.append('找不到 `private fun RangeHero(` —— 窄栏主角函数改名了？这道闸门要跟着改')
else:
    for token in ('TextWithNote(', 'CollapsibleSection(', '留 20% 保底'):
        if token in hero:
            bad.append('窄栏 `RangeHero` 里又出现了结论行（%s）—— 窄栏只许有主角' % token)

conc = body_of('private fun RangeConclusions(')
if conc is None:
    bad.append('找不到 `private fun RangeConclusions(`')
elif 'fillMaxWidth()' not in conc:
    bad.append('`RangeConclusions` 没有撑满整宽（缺 fillMaxWidth()）')

if bad:
    print('续航结论区又回到窄栏了 %d 处 —— 它必须**整宽**：' % len(bad))
    for b in bad:
        print('   ', b)
    print('   ⇒ 窄栏只放主角（RangeHero），结论区整宽（RangeConclusions）')
    sys.exit(1)
print('续航结论区整宽（窄栏里没有结论行，结论区撑满整宽）')
PYEOF
if [ $? -eq 0 ]; then ok "续航结论区整宽（不许回到窄栏）"; else fail "续航结论区整宽"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 12：用量页费用块**表层只许有结论**，证据行折进「更多明细」──────
#
# ⚠️ **v0.96.0**
#
# 汇总卡原来把 **6 行等权**费用平铺在表层（充电费用 / 每公里成本 /
# 直流桩边际成本 / 实际每度电 / 电费每度 / 服务费每度）——
# 一个主角下面挂 6 行同权重小字，就是 v0.86.0 抱怨过的「灰字墙」。
#
# ⇒ v0.96.0：表层只留**两个结论**（总额 / 单价），
# 「实际每度电 / 电费每度 / 服务费每度」是**证据** ⇒ 折进「更多明细」。
#
# 这道闸门守三件事：
#   ① 表层不许再出现那三行证据；
#   ② 那三行**必须还在文件里**（不许删行 —— 数据一点没丢，只是折进去）；
#   ③ 「直流桩边际成本」必须**带条件**：它与「每公里成本」是同一个数时不该出现。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys

P = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/StatsScreen.kt')
src = P.read_text(encoding='utf-8')
lines = src.splitlines()

# 表层 = 汇总卡「更多明细」折叠入口**之前**的那段
fold_at = None
for i, ln in enumerate(lines):
    if 'CollapsibleSection("更多明细")' in ln:
        fold_at = i
        break
if fold_at is None:
    print('找不到 CollapsibleSection("更多明细") —— 汇总卡的折叠入口改名了？这道闸门要跟着改')
    sys.exit(1)

surface = '\n'.join(lines[:fold_at])
bad = []

EVIDENCE = ('KV("实际每度电"', 'KV("电费每度"', 'KV("服务费每度"')
for tok in EVIDENCE:
    if tok in surface:
        bad.append('表层又出现了证据行 %s —— 表层只许留结论' % tok)
    if tok not in src:
        bad.append('%s 不见了 —— 数据一点没丢，只是折进「更多明细」' % tok)

# 「直流桩边际成本」必须带条件（找**字符串字面量**那一行，注释里的不算）
dc_at = None
for i, ln in enumerate(lines):
    if '直流桩边际成本' in ln and ln.strip().startswith('"'):
        dc_at = i
        break
if dc_at is None:
    bad.append('找不到「直流桩边际成本」那一行')
else:
    window = '\n'.join(lines[max(0, dc_at - 14):dc_at])
    if 'dcMarginalIsRedundant(' not in window:
        bad.append('「直流桩边际成本」没有条件 —— 它与「每公里成本」是同一个数时不该出现')

if bad:
    print('用量页费用块的减法被回退了 %d 处：' % len(bad))
    for b in bad:
        print('   ', b)
    print('   ⇒ 表层只留「充电费用 / 每公里成本」两个结论，证据行折进「更多明细」')
    sys.exit(1)
print('费用块表层只有结论（证据行已折进「更多明细」，冗余行已条件化）')
PYEOF
if [ $? -eq 0 ]; then ok "费用块表层只有结论（证据行折进「更多明细」）"; else fail "费用块表层只有结论"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 13：ⓘ 口径说明的视觉宽度上限（棘轮）────────────────────────
#
# ⚠️ **v0.97.0**：ⓘ 里的口径说明**从来没有长度上限**。
#
# 闸门 5 的「啰嗦棘轮」只管**常驻可见**的长说明块 ——
# 文字一旦折进 ⓘ，闸门就看不见它了。
# 实测（v0.97.0 勘察）：用量页汇总卡那两条 ⓘ 拼出来是
# **380 / 296 视觉宽度**（≈5 行）—— 点开 ⓘ 得到的是一堵字墙，
# 不是「一句话结论 + 关键限定」。
#
# 现在给 ⓘ 立上限：**160 视觉宽度**（≈80 个全角字 ≈ 展开后 3 行）。
# 上限只有一个真相来源：`ui/NoteBudget.kt` 的 `NOTE_WIDTH_LIMIT`
# （与闸门 4 从 `Motion.kt` 抠动效 token 同一套路）。
#
# 棘轮：既有超长的登记在 `scripts/note-baseline.txt`，
# **只许变短、不许变长**；新写的 ⓘ 必须 ≤ 上限，否则这道闸门红。
#
# ⚠️ **只扫「调用点字面量」**（`note = "…"` / `note = buildString {…}`）——
# 折进提供器函数（如 `ui/CostNote.kt` 的 `noteChargeCost()`）的 ⓘ
# 由 **JVM 单测**钉住运行时宽度（更准：它检查的是真实分支的真实文本）。
# 静态扫提供器会把**互斥分支也相加** ⇒ 必然假阳性。
#
# ⚠️ **v0.99.0：这道闸门的静态值「两个误差方向相反」⇒ 它不是宽度的判据。**
# - **高估**：扫提供器时**互斥分支也相加**
#   （v0.97.0 实测：静态 208 / 运行时 153）；
# - **低估**：扫调用点时**插值按 1 字符算**（`plain_of` 把 `${…}` 换成 1 个 `…`），
#   而运行时那串字里，日期（`2026-10-31` = 10 字符）、公里数、百分比
#   （`84.4%`）是**实打实的字符**
#   （v0.99.0 实测：③「满电续航」字面量部分 145 / 实际渲染 155；① 152 / 154）。
#
# ⇒ **运行时单测才是判据**；凡是给「动态拼装的内容」立上限，
#   **必须**同时有运行时断言，不能只靠这道静态闸门。
# ⇒ 也因此：**ⓘ 里插值越多，越该放进可测的纯函数** ——
#   v0.99.0 把用量页电池卡的三条搬进 `ui/BatteryNote.kt` 就是为此
#   （插值的日期/公里/百分比，静态层根本看不见）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys

BUDGET = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/NoteBudget.kt')
src = BUDGET.read_text(encoding='utf-8', errors='replace') if BUDGET.exists() else ''
m = re.search(r'const val NOTE_WIDTH_LIMIT\s*=\s*(\d+)', src)
if not m:
    print('找不到 NOTE_WIDTH_LIMIT（ui/NoteBudget.kt）—— 上限常量被删了？')
    sys.exit(1)
LIMIT = int(m.group(1))

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
TPL = re.compile(r'\$\{[^}]*\}')


def visual(s):
    """视觉宽度：汉字/全角 = 2，其余 = 1（与闸门 5 同口径）。"""
    w = 0
    for ch in s:
        o = ord(ch)
        if (0x1100 <= o <= 0x115F or 0x2E80 <= o <= 0xA4CF or 0xAC00 <= o <= 0xD7A3
                or 0xF900 <= o <= 0xFAFF or 0xFE30 <= o <= 0xFE6F or 0xFF00 <= o <= 0xFF60
                or 0xFFE0 <= o <= 0xFFE6 or 0x4E00 <= o <= 0x9FFF):
            w += 2
        else:
            w += 1
    return w


def plain_of(text):
    """只留字面量，模板占位 ${…} 按 1 个字符算。"""
    return ''.join(TPL.sub('…', lit) for lit in LIT.findall(text))


root = pathlib.Path('app-android/app/src/main/java/com/teslamate/app')
cur = {}
for p in sorted(root.rglob('*.kt')):
    lines = p.read_text(encoding='utf-8', errors='replace').splitlines()
    i = 0
    while i < len(lines):
        st = lines[i].strip()
        if re.match(r'note\s*=\s*buildString\s*\{', st):
            body, depth, j = [], 0, i
            while j < len(lines):
                body.append(lines[j])
                depth += lines[j].count('{') - lines[j].count('}')
                if depth <= 0 and j > i:
                    break
                j += 1
            txt = plain_of('\n'.join(body))
            cur['%s::%s' % (p.name, txt[:16])] = (p.name, i + 1, visual(txt), txt[:46])
            i = j + 1
            continue
        if re.match(r'note\s*=\s*"', st):
            txt = plain_of(st)
            cur['%s::%s' % (p.name, txt[:16])] = (p.name, i + 1, visual(txt), txt[:46])
        i += 1

BASE = pathlib.Path('scripts/note-baseline.txt')
base = {}
if BASE.exists():
    for ln in BASE.read_text(encoding='utf-8', errors='replace').splitlines():
        s = ln.strip()
        if not s or s.startswith('#'):
            continue
        parts = s.split('\t')
        if len(parts) != 2:
            print('基线格式坏了（要 <宽度>\\t<文件>::<开头16字>）：%r' % s)
            sys.exit(1)
        base[parts[1].strip()] = int(parts[0].strip())

problems = []
for key, (f, ln, w, prev) in cur.items():
    if w <= LIMIT:
        continue
    if key not in base:
        problems.append('%s:%d  新增超长 ⓘ（%d > %d）：%s' % (f, ln, w, LIMIT, prev))
    elif w > base[key]:
        problems.append('%s:%d  ⓘ 变长了（%d > 基线 %d）：%s' % (f, ln, w, base[key], prev))

if problems:
    print('ⓘ 口径说明超长/变长 %d 处 ——' % len(problems))
    for pr in problems[:14]:
        print('   ', pr)
    print('   ⇒ ⓘ 只许留「一句话结论 + 关键限定」（≤ %d 视觉宽度 ≈ 3 行）' % LIMIT)
    print('   ⇒ 确实需要这么长，就把这一行**原样**加进 %s' % BASE)
    print('     （加基线 = 一次有记录的决策，CHANGELOG 里要写明理由）')
    sys.exit(1)

stale = [k for k in base if k not in cur]
print('ⓘ 口径说明 %d 条，全部 ≤ %d（基线 %d 条）—— 没有新增' % (len(cur), LIMIT, len(base)))
if stale:
    print('   ℹ 基线里有 %d 条代码里已经没有了 —— 可以从 %s 删掉（收缩是好事）' % (len(stale), BASE))
PYEOF
if [ $? -eq 0 ]; then ok "ⓘ 口径文字长度上限（棘轮）"; else fail "ⓘ 口径文字长度上限"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 14：驾驶评分权重 —— 客户端与 Hermes 必须等于服务端公式 ─────────
#
# ⚠️ **v0.102.0**：驾驶平顺分的「权重」在**三个地方各写了一份**：
#   ① App 副标题后面那个 ⓘ；
#   ② 同一张卡的「怎么算的」ⓘ（公式）；
#   ③ Hermes 的 `teslamate_driving_profile` 工具说明与「口径」字段。
#
# 而服务端 `api/handlers.go` 的 `drivingScore()` 是**唯一事实源**。
# 实测发现 ① 写「60 分 / 40 分」，而 ② 的公式加起来是 **70 / 30**
# ⇒ **同一张卡上的两条 ⓘ 互相矛盾**，而且 Hermes 那份也跟着错。
#
# ⇒ 这道闸门**从服务端源码里把五项封顶抠出来**，算出「平滑相关」与
#   「能耗+夜驾」两个数，然后要求 **App 与 Hermes 写的都等于它们**。
# ⇒ 以后谁改了服务端的封顶却忘了改两边 ⇒ 立刻报红。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys

GO = pathlib.Path('api/handlers.go')
KT = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/DriveScoreCard.kt')
MCP = pathlib.Path('mcp/teslamate_mcp.py')


def read(p):
    return p.read_text(encoding='utf-8', errors='replace') if p.exists() else ''


# ① 服务端：抠出五项封顶（急刹 / 急加 / 速度 / 能耗 / 夜驾）
go = read(GO)
m = re.search(r'func drivingScore\(.*?\n\}', go, re.S)
if not m:
    print('找不到 drivingScore()（api/handlers.go）—— 事实源被改名或挪走了？')
    sys.exit(1)
# 只认 `s -= math.Min(N, …)` 这五行扣分 ——
# ⚠️ 末尾还有 `return math.Max(0, math.Min(100, s))` 那个**夹取**，
# 直接扫 `math.Min(` 会把它当成第 6 个封顶。
caps = [int(x) for x in re.findall(r's\s*-=\s*math\.Min\((\d+),', m.group(0))]
if len(caps) != 5:
    print('drivingScore() 的封顶项数不对（拿到 %d 个，期望 5）：%s' % (len(caps), caps))
    sys.exit(1)
if sum(caps) != 100:
    print('服务端封顶加起来不是 100：%s' % caps)
    sys.exit(1)
smooth = caps[0] + caps[1] + caps[2]      # 急刹 + 急加 + 速度
energy_night = caps[3] + caps[4]          # 能耗 + 夜驾


def unquote(text):
    """剥掉 `「…」` 跨度 —— **引文是历史记录，不是当前断言**。

    ⚠️ **v0.103.0**：闸门 14 扩到扫全文后，文件里那些
    「原来写的是『…只有 60 分…』」的**历史引文**会被误判成断言 ⇒ 必须先剥引文。
    ⇒ 剥完之后仍留在正文里的，才是**当前的说法**，必须等于服务端公式。
    """
    return re.sub(r'「[^」]*」', '', text)


problems = []


def check_all(text, pat, want, label, where, required=True):
    """**所有**匹配都必须等于期望值 —— 不只是第一个。

    ⚠️ `required=False`：只查「写了的对不对」，**不查「有没有写」**
    （用来查注释里的话 —— 注释被删掉不算错，写错才算）。
    """
    got = [int(x) for x in re.findall(pat, text)]
    if not got:
        if required:
            problems.append('%s 找不到「%s」的权重（应写 %d 分）' % (where, label, want))
        return
    for g in got:
        if g != want:
            problems.append('%s 的%s权重是 %d 分，服务端公式是 %d 分' % (where, label, g, want))


# ② App：**扫全文**（不只 ⓘ 函数体）——
#
# ⚠️ **v0.103.0**：v0.102.0 只扫 `noteDriveScore()` 的函数体 ⇒
# 文件**注释**里 v0.44.0 抄错的「只覆盖了 60 分 / 40 分来自能耗与夜驾」**漏网**
# （用户可见的 ⓘ 改了，旁边的注释没改）⇒ 这类「同一事实的第二份副本」最容易被漏掉。
# ⇒ 现在扫整个 `DriveScoreCard.kt`：**注释里的错权重也会报红**。
kt = read(KT)
if 'fun noteDriveScore(' not in kt:
    print('找不到 noteDriveScore()（DriveScoreCard.kt）—— 客户端口径函数不见了？')
    sys.exit(1)
check_all(unquote(kt), r'(\d+) 分是急刹/急加/速度', smooth, '平滑相关', 'App')
check_all(unquote(kt), r'(\d+) 分来自能耗与夜驾', energy_night, '能耗与夜驾', 'App')
# ⚠️ v0.44.0 那句「「平顺分」这个名字**只覆盖了 N 分**」——
# 同样必须等于服务端口径（**存在时**才查：它是注释，删掉不算错，写错才算）。
check_all(unquote(kt), r'只覆盖了 (\d+) 分', smooth, '平滑相关', 'App 注释', required=False)

# ③ Hermes（MCP）
mcp = read(MCP)
check_all(unquote(mcp), r'(\d+) 分是平顺相关', smooth, '平滑相关', 'Hermes 口径')
check_all(unquote(mcp), r'另 (\d+) 分来自能耗与夜驾', energy_night, '能耗与夜驾', 'Hermes 口径')
check_all(unquote(mcp), r'急刹 %d \+ 急加 %d \+ 速度 (\d+)' % (caps[0], caps[1]), caps[2],
          '速度', 'Hermes 拆解')

if problems:
    print('驾驶评分权重与服务端公式对不上 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 权威源是 api/handlers.go 的 drivingScore()：封顶 %s' % caps)
    print('   ⇒ 平滑相关 = %d 分、能耗与夜驾 = %d 分；App 与 Hermes 都要这么写' % (smooth, energy_night))
    sys.exit(1)

print('驾驶评分权重与服务端一致（封顶 %s ⇒ 平滑 %d / 能耗夜驾 %d），App 与 Hermes 说的是同一句话'
      % (caps, smooth, energy_night))
PYEOF
if [ $? -eq 0 ]; then ok "驾驶评分权重（App + Hermes = 服务端公式）"; else fail "驾驶评分权重"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 15：能耗口径 —— 一个事实只能有一个说法 ───────────────────────
#
# ⚠️ **v0.103.0**：App 的用量页 ⓘ 与 Hermes 的工具说明，
#   **各自维护了一份**「两个能耗口径的关系」的说明 ⇒ **两份都错了**：
#
#   ① App（`ui/EnergyNote.kt`）说「两个都比实测电池耗电低」——
#      那是从**净值**（≈155 Wh/km，比实测 ≈198 低 24%）推出来的，
#      而车况页显示的是**毛耗电**（≈215~227，**高于**实测）⇒ 对显示值是**反的**；
#      而且「实测耗电」在 App 里**从不显示**（只在费用计算内部用）⇒ 悬空引用。
#
#   ② Hermes（`mcp/teslamate_mcp.py`）说「毛耗电**含**回充抵扣」——
#      而服务端 `api/handlers.go` 的 `EnergyProfile.Note` 写的是
#      「`wh_per_km` 是净值（回充**会**抵扣），`wh_per_km_gross` 是毛耗电（**不**含回充抵扣）」
#      ⇒ **正好相反**，而且与这个工具自己下发的两个字段自相矛盾。
#
# ⇒ 这道闸门盯的是**「只有一份」这个结构**，不是去复述口径内容：
#   ① 服务端 `EnergyProfile.Note` 必须存在、非空、且**同时**说清「净值」与「毛耗电」；
#   ② Hermes 必须**引用服务端下发的 `note`** ⇒ 不许再自己写第二份；
#   ③ Hermes 的**字符串常量**里不许出现已知错误说法与悬空引用
#      （用 `ast` 取字符串常量 ⇒ **注释不算**，因为注释要写清楚来龙去脉）。
#
# ⚠️ App 侧（`noteAvgEnergy()`）是 **provider 形态** ⇒ 静态闸门扫不到
#   ⇒ 由 **JVM 单测** `EnergyNoteTest` 钉住（宽度 + 禁「实测耗电」+ 禁「低/高」）。
#   这与 v0.97.0 定的分工一致：调用点归静态闸门、provider 归单测。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import ast, pathlib, re, sys

GO = pathlib.Path('api/handlers.go')
MCP = pathlib.Path('mcp/teslamate_mcp.py')

problems = []


def read(p):
    return p.read_text(encoding='utf-8', errors='replace') if p.exists() else ''


# ① 服务端：EnergyProfile.Note 必须存在、非空、且两个口径都说到
#
# ⚠️ `api/handlers.go` 里**不止一个** `Note:`（驾驶评分那条也有）⇒ 必须挑对那一个：
# 认准**含 `wh_per_km` 的那条**（EnergyProfile 的口径说明）。
go = read(GO)
notes = []
for m in re.finditer(r'\bNote:\s*((?:"[^"]*"\s*\+\s*)*"[^"]*")', go, re.S):
    lit = ''.join(re.findall(r'"([^"]*)"', m.group(1)))
    notes.append(lit)
note = next((n for n in notes if 'wh_per_km' in n), None)
if note is None:
    problems.append('api/handlers.go 里找不到 EnergyProfile 的 Note 文案'
                    '（含 wh_per_km 的那一条）—— 服务端不再下发口径了？')
else:
    if len(note) < 20:
        problems.append('服务端的 EnergyProfile.Note 太短（%d 字）：%s' % (len(note), note))
    if '净值' not in note or '毛耗电' not in note:
        problems.append('服务端的 EnergyProfile.Note 没有同时说清「净值」与「毛耗电」：%s' % note)
    # ⚠️⚠️ **关联**检查（不是「短语在不在」）——
    # 只查「回充会抵扣 / 不含回充抵扣」这两个短语在不在 ⇒
    # 把两个口径**说反**也能蒙混过关（负向探针 P6 实测过）。
    # ⇒ 必须检查它们**各自挨着哪个字段名**：
    #     wh_per_km（净值）↔ 回充会抵扣
    #     wh_per_km_gross（毛耗电）↔ 不含回充抵扣
    if not re.search(r'wh_per_km(?!_gross)[^，。]*?回充会抵扣', note):
        problems.append('服务端的 Note 没把「wh_per_km（净值）」与「回充会抵扣」放在一起'
                        '（两个口径可能被说反了）：%s' % note)
    if not re.search(r'wh_per_km_gross[^，。]*?不含回充抵扣', note):
        problems.append('服务端的 Note 没把「wh_per_km_gross（毛耗电）」与「不含回充抵扣」放在一起'
                        '（两个口径可能被说反了）：%s' % note)

# ② Hermes：口径必须**引用服务端那份**，不许自维护第二份
mcp_src = read(MCP)
fm = re.search(r'def teslamate_energy_profile\(.*?\n(?=\S)', mcp_src, re.S)
if not fm:
    problems.append('找不到 teslamate_energy_profile()（mcp/teslamate_mcp.py）')
else:
    body = fm.group(0)
    if 'd.get("note")' not in body:
        problems.append('teslamate_energy_profile 的口径没有引用服务端下发的 note —— '
                        '自己维护一份必然漂移（v0.103.0 就漂移了：把两个口径说反了）')

# ②' Hermes 的 docstring 里也**复述**了口径（LLM 先看它）⇒ 它也必须钉住
#     —— 用 ast 精确取出那个函数的 docstring，不受文件里别处干扰。
try:
    tree = ast.parse(mcp_src)
except SyntaxError as e:
    problems.append('mcp/teslamate_mcp.py 语法错：%s' % e)
    tree = None
doc = ''
if tree is not None:
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == 'teslamate_energy_profile':
            doc = ast.get_docstring(node) or ''
if doc:
    flat = re.sub(r'\s+', ' ', doc)
    if not re.search(r'wh_per_km_gross.{0,80}?不抵扣', flat):
        problems.append('Hermes 的 docstring 没把「wh_per_km_gross（毛耗电）」与「不抵扣回充」'
                        '放在一起：%s' % flat[:160])
    if not re.search(r'wh_per_km(?!_gross).{0,80}?回充会抵扣', flat):
        problems.append('Hermes 的 docstring 没把「wh_per_km（净值）」与「回充会抵扣」'
                        '放在一起：%s' % flat[:160])
elif tree is not None:
    problems.append('teslamate_energy_profile 没有 docstring —— LLM 看不到口径说明')

# ③ Hermes 的字符串常量里不许出现已知错误说法与悬空引用
BANNED = [
    '实测电池耗电',   # App/Hermes 都从不显示这个数 ⇒ 悬空引用
    '实测耗电',
    '更接近真实电表',  # 无据的说法
    '毛耗电含回充抵扣',  # 说反了（毛耗电**不**抵扣回充）
]
if tree is not None:
    for node in ast.walk(tree):
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            for b in BANNED:
                if b in node.value:
                    problems.append('MCP 文案里出现禁用说法「%s」：%s' % (b, node.value[:60]))

if problems:
    print('能耗口径不是一个说法 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 权威源是 api/handlers.go 的 EnergyProfile.Note；Hermes 必须引用它')
    print('   ⇒ App 侧由 JVM 单测 EnergyNoteTest 钉（provider 形态，静态闸门扫不到）')
    sys.exit(1)

print('能耗口径只有一份说法（服务端 Note 为准，Hermes 引用它，App 由单测钉）')
PYEOF
if [ $? -eq 0 ]; then ok "能耗口径单一来源（App + Hermes = 服务端 Note）"; else fail "能耗口径单一来源"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 16：「最近 30 天」卡的两条指标不许并排 + 口径限定语必须独立成段 ────
#
# ⚠️ **v0.104.0（真机实测的折行缺陷）**
#
# 那两条指标原来**并排**在一个 `Row` 里：
# 左「平均能耗 17.4 kWh/100km」、右「直流快充 93%（按充入电量）」，中间一个 weight(1f) 的 Spacer。
#
# 两条加起来需要约 **353dp**（`bodySmall` = 13sp 的实测口径），
# 而卡片内容区在**最窄**的常见屏（360dp）上只有 **296dp**
# ⇒ **放不下** ⇒ 右边那串被贪心折行劈成「…（按充入电」+「量）」，
# Spacer 同时被挤成 0 ⇒ 两串贴死，读起来像「平均能耗 17.4 kWh/100km直流快充 93%（…）」。
#
# ⚠️ CJK 允许在**任意两个汉字之间**断行 ⇒ 只要限定语与数字在**同一个段落**里，
# 它就可能被劈开（v0.94.0 的里程表教训：「估算」被劈成「估」+「算」）。
#
# ⇒ 这道闸门守的是**结构**，不是文案：
#   ① 两条指标**不许**回到同一个容器（并排放不下 ⇒ 必须上下堆叠）；
#   ② 口径限定语必须**独立成一个 Text 节点**（不许与数字拼回同一段）；
#   ③ 那个容器**必须是 `FlowRow`** —— 放得下就同排，放不下就**整体换行**；
#      ⚠️ 不能用 `Row` + `weight(1f, fill = false)`：200% 字号下限定语要 182dp，
#      数字那半只剩 114dp 而它要 158dp ⇒ 「直流快充」这个**整词**被劈成
#      「直流」+「快充 93%」（adr.md 第 28 条：短短语不许砍成半截话）；
#   ④ **值与单位之间必须用不换行空格（U+00A0）绑住** ——
#      贪心折行取**最后一个放得下的断点** ⇒ 普通空格会让 200% 下
#      「平均能耗 17.4」/「kWh/100km」**数与单位分离**。
#
# ⚠️ **宽度预算**（每条自己放不放得下）由 **JVM 单测** `SummaryMetricsWidthTest` 钉住 ——
#   静态闸门看不见实际渲染宽度。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import re, pathlib, sys

CARD = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/RecentSummaryCard.kt')
METRICS = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/SummaryMetrics.kt')

problems = []


def read(p):
    return p.read_text(encoding='utf-8', errors='replace') if p.exists() else ''


def mask(text):
    """把注释换成空格（字符串原样保留）—— 括号配对时不该被注释里的花括号带偏。"""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(c if c == '\n' else ' ' for c in text[i:j]))
            i = j
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def skip_string(text, i):
    """i 指向开引号；返回闭引号之后的位置。"""
    n = len(text)
    i += 1
    while i < n:
        if text[i] == '\\':
            i += 2
            continue
        if text[i] == '"':
            return i + 1
        i += 1
    return i


def balanced(text, start):
    """text[start] 必须是 '('；返回 (内部文本, 结束位置)。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i = skip_string(text, i)
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i
        i += 1
    return None, start


def blocks(text, pattern):
    """找出所有 `Name(...)` 调用：(参数文本, { } 体)。"""
    out = []
    for m in re.finditer(pattern, text):
        args, close = balanced(text, m.end() - 1)
        if args is None:
            continue
        body = ''
        j = close + 1
        while j < len(text) and text[j].isspace():
            j += 1
        if j < len(text) and text[j] == '{':
            depth = 0
            k = j
            while k < len(text):
                c = text[k]
                if c == '"':
                    k = skip_string(text, k)
                    continue
                if c == '{':
                    depth += 1
                elif c == '}':
                    depth -= 1
                    if depth == 0:
                        break
                k += 1
            body = text[j:k + 1]
        out.append((args, body))
    return out


def has(block, marker):
    """块里有没有这个标记 —— 参数里或 { } 体里都算。

    ⚠️ `Text(...)` 是**表达式**（没有 { } 体）⇒ 标记在参数里；
      `Row(...) { ... }` 才有体 ⇒ 标记在体里。两种都得认。
    """
    return marker in block[0] or marker in block[1]


if not CARD.exists():
    print('找不到 %s —— 这道闸门要跟着改' % CARD)
    sys.exit(1)
if not METRICS.exists():
    print('找不到 %s —— 文案与宽度预算的唯一事实源不见了' % METRICS)
    sys.exit(1)

src = mask(read(CARD))
met = mask(read(METRICS))

rows = blocks(src, r'\bRow\s*\(')
# ⚠️ **FlowRow**（`docs/adr.md` 第 28 条点名的机制：放不下就**整体换行**，绝不截断）。
# ⚠️ `\bRow\s*\(` **不会**匹配到 `FlowRow(` —— 「w」与「R」之间没有词边界 ✔
flows = blocks(src, r'\bFlowRow\s*\(')
texts = blocks(src, r'\bText\s*\(')

# ① 两条指标不许回到同一个容器；而且那个容器**必须是 FlowRow**
#
# ⚠️ 为什么不能是 `Row` + `weight(1f, fill = false)`：
#    200% 字号下限定语要 182dp ⇒ 数字那半只剩 114dp，
#    而「直流快充 93%」要 158dp ⇒ 数字那半自己换行
#    ⇒ 「直流快充」这个**整词**被劈成「直流」+「快充 93%」
#    （adr.md 第 28 条：短短语不许砍成半截话）。
#    FlowRow 则把两半当成**两个整块**：放得下同排，放不下整体换行。
dc_rows = [b for b in rows if has(b, 'dcShareLine(')]
dc_flows = [b for b in flows if has(b, 'dcShareLine(')]
containers = dc_rows + dc_flows
if len(containers) != 1:
    problems.append('包含 dcShareLine( 的容器有 %d 个（应恰好 1 个）—— 界面结构被改动了？' % len(containers))
else:
    if dc_rows:
        problems.append('直流快充那一行用 Row 而不是 FlowRow ⇒ 200% 字号下'
                        '「直流快充 93%」会被劈成「直流」/「快充 93%」'
                        '（adr.md 第 28 条：短短语不许砍成半截话）')
    if not has(containers[0], 'DC_SHARE_CALIBER'):
        problems.append('直流快充那一行里没有 DC_SHARE_CALIBER ⇒ 口径限定语不见了'
                        '（v0.47.0 特意补的「按充入电量」不许消失）')
    if has(containers[0], 'avgEnergyLine('):
        problems.append('平均能耗与直流快充又并排在同一个容器里 ⇒ 窄屏上放不下'
                        '（v0.104.0 的折行缺陷）')

# ② 限定语与数字必须**分属两个 Text 节点**
dc_texts = [b for b in texts if has(b, 'dcShareLine(')]
cal_texts = [b for b in texts if has(b, 'DC_SHARE_CALIBER')]
if len(dc_texts) != 1:
    problems.append('包含 dcShareLine( 的 Text 有 %d 个（应恰好 1 个）' % len(dc_texts))
else:
    if has(dc_texts[0], 'DC_SHARE_CALIBER'):
        problems.append('直流快充的数字与口径限定语又拼回同一段了 ⇒ 限定语会被折行劈开')
    # ③ FlowRow 的子项**不许**带 weight —— 那样又变回「被挤」的语义了
    if re.search(r'weight\s*\(', dc_texts[0][0]):
        problems.append('直流快充的数字那半还带着 weight(…) ⇒ 它会按比例分宽度，'
                        '而不是「放不下就整体换行」')
if len(cal_texts) != 1:
    problems.append('包含 DC_SHARE_CALIBER 的 Text 有 %d 个（应恰好 1 个）' % len(cal_texts))
else:
    if has(cal_texts[0], 'dcShareLine('):
        problems.append('口径限定语又和数字回到同一个 Text 里了 ⇒ 限定语会被折行劈开')

# 平均能耗那一行：**先认变量名**（不许写死「avgLine」—— 改名就会误报）
am = re.search(r'val\s+(\w+)\s*=\s*avgEnergyLine\(', src)
if not am:
    problems.append('找不到 avgEnergyLine() 的赋值 —— 事实源被改名了？')
else:
    avg_var = am.group(1)
    avg_texts = [b for b in texts if has(b, avg_var)]
    if len(avg_texts) != 1:
        problems.append('渲染平均能耗那一行的 Text 有 %d 个（应恰好 1 个）' % len(avg_texts))
    if len(containers) == 1 and has(containers[0], avg_var):
        problems.append('平均能耗又回到快充那个容器里了 ⇒ 两条并排放不下（v0.104.0 的折行缺陷）')
    # 那个容器里只许有**两个** Text：数字 + 限定语
    if len(containers) == 1:
        inner = blocks(containers[0][1], r'\bText\s*\(')
        if len(inner) != 2:
            problems.append('直流快充那个容器里有 %d 个 Text（应恰好 2 个：数字 + 限定语）'
                            '⇒ 别的指标又被塞进这一行了' % len(inner))

# ⑤ **值与单位之间必须绑住**（不换行空格 U+00A0）
#
# ⚠️ 贪心折行取的是**最后一个放得下的断点** ⇒ 「平均能耗 17.4 kWh/100km」里
#    那个**普通空格**是致命的：200% 下整行 349dp > 296dp 必然折行，
#    断点会落在「17.4」后面 ⇒ **数与单位分离**（半个句子）。
#    ⇒ 那个位置必须是不换行空格 ⇒ 整行只剩「标签 | 值+单位」一个断行机会。
nb = re.search(r'val\s+NBSP\s*:[^\n]*', met)
if not nb:
    problems.append('SummaryMetrics.kt 里找不到 NBSP 常量')
else:
    if '0xA0' not in nb.group(0):
        problems.append('NBSP 常量不是 U+00A0（0xA0）⇒ 绑不住值与单位')
am2 = re.search(r'fun\s+avgEnergyLine\s*\(', met)
if not am2:
    problems.append('SummaryMetrics.kt 里找不到 avgEnergyLine() —— 事实源被改名了？')
else:
    _, close = balanced(met, am2.end() - 1)
    body = met[close + 1:close + 400].split('\n\n')[0]
    if 'NBSP' not in body:
        problems.append('avgEnergyLine() 里没有用不换行空格绑住值与单位 ⇒ '
                        '200% 字号下会折成「平均能耗 17.4」/「kWh/100km」')

# ④ 事实源：限定语不许被塞回数字那一段
m = re.search(r'const val DC_SHARE_CALIBER\s*=\s*"([^"]*)"', met)
if not m:
    problems.append('SummaryMetrics.kt 里找不到 DC_SHARE_CALIBER 的字面量')
elif m.group(1) != '（按充入电量）':
    problems.append('口径限定语变成了「%s」' % m.group(1))
fm = re.search(r'fun\s+dcShareLine\s*\(', met)
if not fm:
    problems.append('SummaryMetrics.kt 里找不到 dcShareLine() —— 事实源被改名了？')
else:
    _, close = balanced(met, fm.end() - 1)
    tail = met[close + 1:close + 400].split('\n\n')[0]
    lits = re.findall(r'"((?:[^"\\]|\\.)*)"', tail)
    for l in lits:
        if '按充入电量' in l:
            problems.append('dcShareLine() 里又出现了口径限定语 ⇒ 限定语回到数字那一段 ⇒ 会被折行劈开')

if problems:
    print('「最近 30 天」卡的结构被改回去了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 两条指标上下堆叠；口径限定语独立成 Text 节点；两段用 FlowRow 排（放不下就整体换行）')
    print('   ⇒ 值与单位之间用不换行空格（U+00A0）绑住')
    print('   ⇒ 宽度预算由 JVM 单测 SummaryMetricsWidthTest 钉住')
    sys.exit(1)

print('「最近 30 天」卡结构正确（两条指标上下堆叠、限定语独立成段、FlowRow 整体换行、值与单位绑住）')
PYEOF
if [ $? -eq 0 ]; then ok "汇总卡结构（两条指标不并排 + 限定语独立成段 + FlowRow + 值与单位绑住）"; else fail "汇总卡结构"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 17：「记录」页折叠态那张卡不许把**证据**摊回表层 ──────────────────
#
# ⚠️ **v0.106.0（用户反馈 #26）**
#
# 用户原话：「记录最顶上的这一小块，**很难理解他想表达什么意思**。」
# 真机截图上表层并排着 **22% / 30% / 20% / 13% / 15%** 五个百分数 ——
# 「上次剩 22%」（哪一站？）、「低于 20% 保底」、「最险 … 估 13%」、
# 「全程最低 15%」全挤在一起 ⇒ 读者分不清**哪个是结论、哪个是证据**。
#
# ⇒ 表层收敛成 4 行（标题 / 主角 / 结论句 / 告警），证据折进**展开卡**。
# 这道闸门守的就是那个「折进去」：
#   ① 折叠态**不许**再调 `verdictDetail(`（「全程最低 X%（第 N 公里…）」）；
#   ② 折叠态的告警行**必须** `withDetail = false`（不许带「最险：…」细节）；
#   ③ 展开卡**必须**保留完整告警行 + 插值误差提示
#      （否则表层折掉的细节就**真丢了**；v0.54.0：数字旁边必须带不确定度）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

P = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/RoutePlan.kt')
if not P.exists():
    print('找不到 %s —— 这道闸门要跟着改' % P)
    sys.exit(1)
src = P.read_text(encoding='utf-8', errors='replace')


def mask(text):
    """把注释换成空格（字符串原样保留）—— 括号配对时不该被注释里的花括号带偏。"""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if ch == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(c if c == '\n' else ' ' for c in text[i:j]))
            i = j
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


masked = mask(src)


def skip_string(text, i):
    """i 指向开引号；返回闭引号之后的位置。"""
    n = len(text)
    i += 1
    while i < n:
        if text[i] == '\\':
            i += 2
            continue
        if text[i] == '"':
            return i + 1
        i += 1
    return i


def body_of(sig):
    """取某个函数的**函数体**（注释已屏蔽 ⇒ 里面的花括号不会干扰配对）。

    ⚠️ **必须先跳过参数表** —— 默认参数里可能有 `= {}`
    （如 `onSelectKey: (String) -> Unit = {}`），
    直接找第一个 `{` 会把**那个空 lambda**当成函数体 ⇒ 检查全部落空。
    """
    i = masked.find(sig)
    if i < 0:
        return None
    # ① 参数表：从 '(' 起做括号配平，找到它的 ')'
    j = masked.find('(', i)
    if j < 0:
        return None
    depth = 0
    k = j
    while k < len(masked):
        c = masked[k]
        if c == '"':
            k = skip_string(masked, k)
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        k += 1
    else:
        return None
    # ② 参数表之后第一个 '{' 才是函数体
    b = masked.find('{', k)
    if b < 0:
        return None
    depth = 0
    m = b
    while m < len(masked):
        c = masked[m]
        if c == '"':
            m = skip_string(masked, m)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return masked[b:m + 1]
        m += 1
    return None


problems = []

summary = body_of('fun RoutePlanSummaryRow(')
if summary is None:
    problems.append('找不到 `fun RoutePlanSummaryRow(` —— 折叠态那张卡改名了？这道闸门要跟着改')
else:
    if 'verdictDetail(' in summary:
        problems.append('折叠态又把「全程最低 X%（第 N 公里 · …之后）」摊回表层了 ⇒ '
                        '五个百分数并排，读者分不清哪个是结论（用户反馈 #26）')
    calls = re.findall(r'belowReserveNoticeFrom\([^)]*\)', summary)
    if not calls:
        problems.append('折叠态没有告警行 ⇒ 到站低于保底的那些跳在表层消失了')
    for c in calls:
        if 'withDetail = false' not in c:
            problems.append('折叠态的告警行没带 `withDetail = false` ⇒ 表层又出现'
                            '「最险：哪个站 · 多少公里 · 估多少」这半句证据')

card = body_of('fun RoutePlanCard(')
if card is None:
    problems.append('找不到 `fun RoutePlanCard(` —— 展开卡改名了？这道闸门要跟着改')
else:
    if 'belowReserveNoticeFrom(' not in card:
        problems.append('展开卡里没有完整告警行 ⇒ 表层折掉的「最险」细节**真丢了**')
    # ⚠️ **关联检查**，不是「短语在不在」——
    # 「全程最低」那个数**旁边**必须带插值误差提示（v0.54.0：数字旁边必须带不确定度）。
    # ⇒ 只查「`socProfileInterpolated` 在这个文件里出现过」是**假绿**：
    #   展开卡里别处（图表说明）也提到它 ⇒ 提示被删了照样绿。
    # ⇒ 必须查它**紧跟在 verdictDetail 那一行后面**。
    vd = card.find('verdictDetail(')
    if vd < 0:
        problems.append('展开卡里找不到 verdictDetail( ⇒ 「全程最低」那行不见了')
    elif 'socProfileInterpolated' not in card[vd:vd + 600]:
        problems.append('展开卡里「全程最低」那个数旁边没有插值误差提示 ⇒ '
                        '它带着 13 公里的不确定度出现，却一个字都不说'
                        '（v0.54.0：数字旁边必须带不确定度）')

if problems:
    print('「记录」页折叠态那张卡又摊回证据了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 表层只留：标题 / 主角 / 结论句 / 短告警；证据（全程最低、最险细节）折进展开卡')
    sys.exit(1)

print('折叠态那张卡表层只留结论与短告警（证据已折进展开卡，且展开卡仍保有完整细节）')
PYEOF
if [ $? -eq 0 ]; then ok "折叠态不摊回证据（用户反馈 #26）"; else fail "折叠态不摊回证据"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 18：车控按钮不许把「后果说明」印在标签里 ──────────────────
#
# ⚠️ **v0.107.0（用户反馈 #25）**
#
# 用户原话：「车辆控制这一块的各个按钮**可以不需要括号内的那些东西**。
# 把文字的布局尽量统一。」
#
# 真机截图上「车窗通风（开一条缝）」与「关闭全部车窗」并排 ——
# 一个**两行**、一个**一行** ⇒ 同排按钮的文字布局参差不齐。
#
# ⇒ 规矩（**结构**，不是文案）：
#   ① 按钮**只印短标签**（`commandButtonLabel(cmd)`）—— 不许直接印 `cmd.description`；
#   ② 弹窗标题 / 确认按钮也用短标签（不许印带括号的整句）；
#   ③ **后果说明必须在弹窗里**（`commandNoteLine(`）——
#      从按钮上挪走了就得有地方说，否则「会耗电、可能计费」被**悄悄删了**；
#   ④ 「有后果说明 ⇒ 必须弹窗」（`commandNeedsConfirm` 里要有 `cmd.note.isNotBlank()`）。
#
# ⚠️ **关联检查，不是「短语在不在」** —— 查的是
# 「按钮那一行**到底印的是谁**」，而不是「文件里出现过 commandButtonLabel」。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys

CARD = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/VehicleControlCard.kt')
LABELS = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/CommandLabels.kt')
for p in (CARD, LABELS):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def mask(text):
    """把注释换成空格（字符串原样保留）—— 括号配对时不该被注释里的花括号带偏。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(c if c == '\n' else ' ' for c in text[i:j]))
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
            continue
        out.append(text[i])
        i += 1
    return ''.join(out)


def body_of(text, sig):
    """取某个函数的**函数体**（注释已屏蔽）。

    ⚠️ **必须先跳过参数表** —— 默认参数里可能有 `= {}`，
    直接找第一个 `{` 会把**那个空 lambda**当成函数体 ⇒ 检查全部落空。
    """
    i = text.find(sig)
    if i < 0:
        return None
    j = text.find('(', i)
    if j < 0:
        return None
    depth = 0
    k = j
    while k < len(text):
        if text[k] == '(':
            depth += 1
        elif text[k] == ')':
            depth -= 1
            if depth == 0:
                break
        k += 1
    else:
        return None
    b = text.find('{', k)
    if b < 0:
        return None
    depth = 0
    m = b
    while m < len(text):
        c = text[m]
        if c == '"':
            m += 1
            while m < len(text):
                if text[m] == '\\':
                    m += 2
                    continue
                if text[m] == '"':
                    break
                m += 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[b:m + 1]
        m += 1
    return None


def stmt_of(text, sig):
    """取「**表达式体**」函数的整条语句（从签名到下一个空行为止）。

    ⚠️ Kotlin 的表达式体函数（`fun f(): T = expr`）**没有花括号** ——
    按花括号去找函数体，找到的会是**表达式里的 lambda**
    （`cmd.label.ifBlank { … }` 那个 `{`）⇒ 检查全部落空（**假红**）。

    ⚠️ 这是「取函数体必须先跳过参数表」那条教训的**姊妹坑**：
    一个是「第一个 `{` 是空 lambda」，一个是「**根本没有**函数体的花括号」。
    """
    i = text.find(sig)
    if i < 0:
        return None
    j = text.find('\n\n', i)
    if j < 0:
        j = len(text)
    return text[i:j]


card = mask(CARD.read_text(encoding='utf-8', errors='replace'))
labels = mask(LABELS.read_text(encoding='utf-8', errors='replace'))

problems = []

# ① 按钮那一行：必须用短标签，且不许再印 `cmd.description`。
grid = body_of(card, 'fun ActionGrid(')
if grid is None:
    problems.append('找不到 `fun ActionGrid(` —— 动作网格改名了？这道闸门要跟着改')
else:
    if 'commandButtonLabel(' not in grid:
        problems.append('动作网格没有用 commandButtonLabel(cmd) ⇒ 按钮不是短标签')
    if 'cmd.description' in grid:
        problems.append('动作网格里又直接印了 cmd.description ⇒ 括号里的后果回到按钮上了')

# ② + ③ 弹窗：标题/确认按钮用短标签，后果说明单独一行。
dlg = body_of(card, 'fun CommandConfirmDialog(')
if dlg is None:
    problems.append('找不到 `fun CommandConfirmDialog(` —— 弹窗改名了？这道闸门要跟着改')
else:
    if 'commandButtonLabel(' not in dlg:
        problems.append('弹窗没有用 commandButtonLabel(cmd) ⇒ 标题还是带括号的整句')
    if 'cmd.description' in dlg:
        problems.append('弹窗里又直接用了 cmd.description ⇒ 「确认打开前备箱（远程关不了）」这种又回来了')
    if 'commandNoteLine(' not in dlg:
        problems.append('弹窗里没有 commandNoteLine( ⇒ 后果说明从按钮挪走后没人说（信息丢了）')

# ④ 点按钮的入口：判定必须走纯函数。
req = body_of(card, 'fun request(')
if req is None:
    problems.append('找不到 `fun request(` —— 入口改名了？这道闸门要跟着改')
else:
    if 'commandNeedsConfirm(' not in req:
        problems.append('request() 没有走 commandNeedsConfirm() ⇒ 弹窗判定各写一套')

# ⑤ 纯函数本身：短标签优先、后果必须能触发弹窗。
btn = stmt_of(labels, 'fun commandButtonLabel(')
if btn is None:
    problems.append('找不到 `fun commandButtonLabel(` —— 纯函数改名了？这道闸门要跟着改')
else:
    if 'cmd.label.ifBlank' not in btn:
        problems.append('commandButtonLabel() 没有优先用 cmd.label ⇒ 又回去印 description 了')

need = stmt_of(labels, 'fun commandNeedsConfirm(')
if need is None:
    problems.append('找不到 `fun commandNeedsConfirm(` —— 纯函数改名了？这道闸门要跟着改')
else:
    if 'cmd.note.isNotBlank()' not in need:
        problems.append('commandNeedsConfirm() 没有把「有后果说明」算进去 ⇒ '
                        '「会耗电、可能计费」挪进弹窗后没人看得见（等于删了信息）')

note = stmt_of(labels, 'fun commandNoteLine(')
if note is None:
    problems.append('找不到 `fun commandNoteLine(` —— 纯函数改名了？这道闸门要跟着改')
else:
    if 'cmd.note.ifBlank' not in note:
        problems.append('commandNoteLine() 没有优先用 cmd.note ⇒ 后果说明用的是别的口径')

if problems:
    print('车控按钮又把后果说明印回标签了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 按钮只印短标签；后果说明在确认弹窗里；「有后果说明 ⇒ 必须弹窗」')
    sys.exit(1)

print('车控按钮只印短标签（后果说明在确认弹窗里，且有后果说明就会弹窗）')
PYEOF
if [ $? -eq 0 ]; then ok "车控按钮只印短标签（后果说明在弹窗里）（用户反馈 #25）"; else fail "车控按钮只印短标签"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 19：车控目录的**字段名**必须两端一致（防静默退化）──────────────
#
# ⚠️ **v0.107.1**
#
# App 的 `label` / `note` 有**默认值**（为了兼容老服务端）⇒
# **服务端一旦改了 JSON 字段名，App 不会报错** ——
# 它只会**静默退回 `description`** ⇒ 用户的界面悄悄变回
# 「标签里带括号」的老样子：**没有报错、没有崩溃、没有日志、没人会发现**。
#
# ⇒ 这道闸门把**线上契约**钉死（关联检查，不是「短语在不在」）：
#   ① 服务端 `teslaCommandSpec` 的 `json:"label"` / `json:"note"`；
#   ② App 端 `VehicleCommand` 的 `val label` / `val note`，且**必须有默认值**
#      （没默认值 ⇒ 老服务端返回的 JSON 缺这两个键 ⇒ kotlinx.serialization
#        **直接抛异常** ⇒ 整张车控卡变成「读不到车控能力清单」——
#        v0.74.0 踩过的坑）；
#   ③ 两边的**字段名必须相等**；
#   ④ App 的 JSON 解析必须 `ignoreUnknownKeys = true`
#      （否则**老 App** 收到新服务端多出来的字段 ⇒ 整个清单解析失败）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

GO = pathlib.Path('api/tesla_command.go')
KT = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/Api.kt')
for p in (GO, KT):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


go = read(GO)
kt = read(KT)

problems = []

# ① 服务端：teslaCommandSpec 里的两个 json tag
m = re.search(r'type\s+teslaCommandSpec\s+struct\s*\{(.*?)\n\}', go, re.S)
if not m:
    print('找不到 teslaCommandSpec（api/tesla_command.go）—— 这道闸门要跟着改')
    sys.exit(1)
spec_body = m.group(1)
wire = {}
for field, key in re.findall(r'\b(Label|Note)\s+string\s+`json:"([^"]+)"`', spec_body):
    wire[field] = key
for field in ('Label', 'Note'):
    if field not in wire:
        problems.append('服务端的 teslaCommandSpec 里没有 %s 字段的 json tag ⇒ '
                        'App 端对应的 DTO 字段会一直是空串（静默退回 description）' % field)

# ② App：VehicleCommand DTO 的 label / note（且必须有默认值）
m2 = re.search(r'data\s+class\s+VehicleCommand\s*\((.*?)\n\)', kt, re.S)
if not m2:
    print('找不到 data class VehicleCommand（Api.kt）—— 这道闸门要跟着改')
    sys.exit(1)
dto = m2.group(1)
dto_props = {}
for name, default in re.findall(r'val\s+(\w+)\s*:\s*String\s*(=\s*""\s*)?', dto):
    dto_props[name] = bool(default)
for field in ('label', 'note'):
    if field not in dto_props:
        problems.append('App 的 VehicleCommand 里没有 `val %s` ⇒ 服务端下发的 %s 读不到' % (field, field))
    elif not dto_props[field]:
        problems.append('App 的 `val %s` **没有默认值** ⇒ 老服务端（不下发这个字段）'
                        '会让 kotlinx.serialization 抛异常 ⇒ 整张车控卡变成'
                        '「读不到车控能力清单」（v0.74.0 踩过的坑）' % field)

# ③ 两边字段名必须相等
for field, key in wire.items():
    lower = field[0].lower() + field[1:]
    if lower in dto_props and key != lower:
        problems.append('服务端的 `json:"%s"` 与 App 的 `val %s` **对不上** ⇒ '
                        'App 读不到 ⇒ 界面静默退回带括号的老样子' % (key, lower))

# ④ App 的 JSON 解析必须忽略未知字段（老 App 遇到新服务端字段不能整个挂掉）
if 'ignoreUnknownKeys = true' not in kt:
    problems.append('App 的 Json 没有 `ignoreUnknownKeys = true` ⇒ '
                    '老 App 收到新服务端多出来的字段会**整个解析失败**')

if problems:
    print('车控目录的字段名两端对不上 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 服务端 json tag ↔ App DTO 字段名必须一致，且 App 端要有默认值')
    sys.exit(1)

print('车控目录字段名两端一致（服务端 %s ↔ App %s），且都带默认值' % (
    ', '.join('%s=%s' % (k, v) for k, v in sorted(wire.items())),
    ', '.join(sorted(k for k in dto_props if k in ('label', 'note')))))
PYEOF
if [ $? -eq 0 ]; then ok "车控目录字段名两端一致（防静默退化）"; else fail "车控目录字段名"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 20：充电统计的**区间口径**必须两端一致（且客户端不许自己算）──────
#
# ⚠️ **v0.108.0（用户反馈 #27）**
#
# 用户要「充电记录顶上这个统计能切区间（近 7 天 / 近 30 天 / 全部）」。
# 这件事最容易出的岔子是**同一个数两处算法**：
#
#   · 服务端按「今天 00:00 往前 N−1 天」切窗口；
#   · 客户端按自己的 `start_date` 再过滤一遍 ⇒ 两处边界差一天/一个时区，
#     同一个 App 里两个页面的「7 天」对不上；
#   · 客户端那份充电列表**有上限** ⇒ 拿它算「全部」＝**静默截断**的历史
#     （用户以为看到的是全部，其实少了一截，而且界面不会说）。
#
# ⇒ 口径与聚合**只在服务端**（`/charge_stats`），App 只渲染。
#
# 这道闸门钉死四件事（都是**关联**检查，不是「短语在不在」）：
#   ① 两端都真的有 charge_stats（服务端路由 ↔ App 请求路径的**同一段**路径）；
#   ② 两端的**区间键与顺序**相同（服务端 chargeRangeSpecs ↔ App chargeRangeKeys）；
#   ③ 两端的**字段名**相同（服务端 json tag ↔ App DTO 属性，且 App 端有默认值）；
#   ④ **客户端不许自己算聚合**（ChargeListPage.kt 里不许再有 sumOf / is_dc 过滤）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

GO_MAIN = pathlib.Path('api/main.go')
GO_MODELS = pathlib.Path('api/models.go')
GO_STATS = pathlib.Path('api/charge_stats.go')
KT_API = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/Api.kt')
KT_PAGE = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/ChargeListPage.kt')
for p in (GO_MAIN, GO_MODELS, GO_STATS, KT_API, KT_PAGE):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


def strip_kt_comments(src):
    """
    剥掉 Kotlin 注释。

    ⚠️ **必须剥**：本文件里的说明文字会**提到**被禁的写法
    （例：「原来这里是客户端算的（`charges.sumOf { ... }`）—— 现在删掉」）
    ⇒ 不剥的话，闸门会把自己写的说明当成违规 ⇒ **假红**。
    """
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


main = read(GO_MAIN)
models = read(GO_MODELS)
stats = read(GO_STATS)
kt = read(KT_API)
page = read(KT_PAGE)

problems = []

# ① 路径片段：服务端路由 ↔ App 请求（同一段路径才算配对）
m = re.search(r'mux\.HandleFunc\("GET\s+/api/v1/cars/\{[^}]+\}/(charge_stats)"', main)
server_path = m.group(1) if m else None
if server_path is None:
    problems.append('服务端的路由表里没有 charge_stats（api/main.go）')
m2 = re.search(r'get\("/api/v1/cars/\$carId/(charge_stats)"\)', kt)
app_path = m2.group(1) if m2 else None
if app_path is None:
    problems.append('App 没有请求 charge_stats（Api.kt）')
if server_path and app_path and server_path != app_path:
    problems.append('服务端路由是 /%s，App 请求的是 /%s ⇒ 对不上' % (server_path, app_path))

# ② 区间键与顺序（服务端 specs ↔ App keys）
go_keys = re.findall(r'\{key:\s*"([^"]+)"', stats)
m3 = re.search(r'val\s+chargeRangeKeys\s*=\s*listOf\(([^)]*)\)', page)
if not m3:
    problems.append('App 里找不到 chargeRangeKeys（ChargeListPage.kt）')
    kt_keys = []
else:
    kt_keys = re.findall(r'"([^"]+)"', m3.group(1))
if go_keys and kt_keys:
    # ⚠️ App 的区间是服务端的**子集**（服务端多一档「本月」给 Hermes 用，
    #    App 的分段控件只有 3 格 ⇒ 塞不下第 4 档）。
    #    但**子集的顺序**必须与服务端一致（那是分段控件的顺序）。
    missing = [k for k in kt_keys if k not in go_keys]
    if missing:
        problems.append('App 的区间键 %s 服务端没有 ⇒ 切过去会拿到空' % missing)
    else:
        idx = [go_keys.index(k) for k in kt_keys]
        if idx != sorted(idx):
            problems.append('区间键顺序对不上：服务端 %s，App %s（App 取的是子集，顺序也要一致）'
                            % (go_keys, kt_keys))

# ③ 字段名（服务端 json tag ↔ App DTO 属性，且 App 端要有默认值）
def go_struct_keys(src, name):
    m = re.search(r'type\s+' + name + r'\s+struct\s*\{(.*?)\n\}', src, re.S)
    if not m:
        return None
    return re.findall(r'`json:"([^"]+)"`', m.group(1))


def kt_dto_props(src, name):
    m = re.search(r'data\s+class\s+' + name + r'\s*\((.*?)\n\)', src, re.S)
    if not m:
        return None
    props = {}
    for prop, default in re.findall(r'val\s+(\w+)\s*:\s*[^,\n=]+(?:=\s*([^,\n]+))?', m.group(1)):
        props[prop] = bool(default)
    return props


for name in ('ChargeStatsRange', 'ChargeStatsLastFull'):
    wire = go_struct_keys(models, name)
    props = kt_dto_props(kt, name)
    if wire is None:
        problems.append('服务端找不到 type %s（api/models.go）' % name)
        continue
    if props is None:
        problems.append('App 找不到 data class %s（Api.kt）' % name)
        continue
    missing = sorted(set(wire) - set(props))
    if missing:
        problems.append('服务端 %s 下发了 %s，App 的 DTO 里没有 ⇒ 读不到（静默拿默认值）'
                        % (name, ', '.join(missing)))
    nodefault = sorted(k for k, v in props.items() if k in wire and not v)
    if nodefault:
        problems.append('App 的 %s.%s **没有默认值** ⇒ 老服务端缺这个字段时 '
                        'kotlinx.serialization 会抛异常（整张卡读不出来）'
                        % (name, ', '.join(nodefault)))

# ④ 客户端不许自己算聚合（同一个数只能有一处算法）
#    ⚠️ 扫的是**剥掉注释后**的代码 —— 说明文字里提到旧写法不算违规。
page_code = strip_kt_comments(page)
if 'sumOf' in page_code:
    problems.append('ChargeListPage.kt 里还有 `sumOf` ⇒ 客户端在自己算聚合 ⇒ 与服务端成了两套算法')
if re.search(r'filter\s*\{\s*it\.is_dc', page_code):
    problems.append('ChargeListPage.kt 里还在按 is_dc 过滤 ⇒ 客户端自己算直交流拆分 ⇒ 与服务端成了两套算法')
if '.total_kwh' not in page_code:
    problems.append('ChargeListPage.kt 里没有用服务端的 total_kwh ⇒ 卡片可能还在用别处的数')

if problems:
    print('充电统计的区间口径两端对不上 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 区间口径与聚合只在服务端算；两端的路径 / 区间键 / 字段名必须一致')
    sys.exit(1)

print('充电统计区间口径两端一致（路径 %s、区间键 %s），客户端没有自己的算法'
      % (server_path, ' '.join(kt_keys)))
PYEOF
if [ $? -eq 0 ]; then ok "充电统计区间口径两端一致（且客户端不自己算）"; else fail "充电统计区间口径"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 21：车控按钮网格的**列数**不许写死（大字号要改单列）────────────
#
# ⚠️ **v0.109.0**
#
# 用户反馈 #25 的诉求「把文字的布局尽量统一」在**正常字号**下已解决（v0.107.0 的短标签），
# 但 **200% 字号**下没解决：2 列时按钮文字区只有 140dp（360dp 窄屏：卡内边距 32 +
# 列间距 8 ÷ 2 + 按钮内边距 20），最长短标签「方向盘加热开关」7 个全角字在 200% 下
# 需要 210dp ⇒ **折成两行** ⇒「同一排一个一行、一个两行」的参差又回来了。
#
# ⇒ 正解是**换布局**而不是压文案：大字号下改成**单列**（满宽 ⇒ 文字区 308dp ⇒
#    一行放得下、动词也保得住）—— ADR #39，推导写在 `ui/CommandGrid.kt` 的注释里。
#
# ⇒ 这道闸门守的就是「列数由字号决定」：
#   ① `ActionGrid` 必须走 `actionGridColumns(`；
#   ② **不许**出现 `chunked(2)`（写死了大字号就又折行）；
#   ③ 规则本身在**纯函数**里（可单测），且真的按 `fontScale` 与阈值比较。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

CARD = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/VehicleControlCard.kt')
GRID = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui/CommandGrid.kt')
for p in (CARD, GRID):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def strip_kt_comments(text):
    """把注释换成空格（字符串原样保留）—— 说明文字里提到旧写法不算违规。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
            continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join(c if c == '\n' else ' ' for c in text[i:j]))
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:j + 1])
            i = j + 1
            continue
        out.append(text[i])
        i += 1
    return ''.join(out)


card = strip_kt_comments(CARD.read_text(encoding='utf-8', errors='replace'))
grid = strip_kt_comments(GRID.read_text(encoding='utf-8', errors='replace'))

problems = []

if 'actionGridColumns(' not in card:
    problems.append('车控网格没有用 actionGridColumns( ⇒ 列数写死了 ⇒ 大字号下标签又折行')

if re.search(r'chunked\s*\(\s*2\s*\)', card):
    problems.append('车控网格里还有 `chunked(2)` ⇒ 两列写死了 ⇒ 200% 下最长标签放不下 ⇒ 又折成两行')

if 'fun actionGridColumns(' not in grid:
    problems.append('找不到 `fun actionGridColumns(` —— 规则搬家了？这道闸门要跟着改')
else:
    if 'fontScale' not in grid:
        problems.append('actionGridColumns() 没有读 fontScale ⇒ 列数与字号无关')
    if not re.search(r'fontScale\s*>=\s*[A-Za-z_][A-Za-z0-9_]*', grid):
        problems.append('actionGridColumns() 没有按阈值比较 fontScale ⇒ 永远是同一个列数')

if problems:
    print('车控按钮网格的列数被写死了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 大字号（200%）下改单列（满宽）：文字区 308dp ⇒ 7 个字一行放得下')
    sys.exit(1)

print('车控按钮网格的列数随字号变（大字号改单列，ADR #39）')
PYEOF
if [ $? -eq 0 ]; then ok "车控按钮网格大字号改单列（ADR #39）"; else fail "车控按钮网格列数"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 22：MCP 读的**服务端键名**必须在服务端结构体里存在（防静默 null）────
#
# ⚠️ **v0.110.0**
#
# 一次真实事故的同款（v0.72.0）：MCP 工具「能调通、返回 JSON」，
# 但取的是**不存在的字段** ⇒ 9 个工具里 3 个**返回的全是 null** ——
# 不报错、不崩、没人看得出来。
#
# 充电统计现在有**两个消费者**（App / MCP），读的是**同一个服务端端点**
# ⇒ 键名对不上时必须有人喊出来（否则飞书里那句「这个月充了多少度」会答成 null）。
#
# ⚠️ 这道闸门查的是「MCP 读的**服务端键**」↔「服务端结构体的 json tag」——
# **不是** MCP 的**输出中文键**（那是 MCP 自己的词表，与 App 的 DTO 字段名
# 不是一套；拿它们互相对照会**假红**）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

MCP = pathlib.Path('mcp/teslamate_mcp.py')
MODELS = pathlib.Path('api/models.go')
STATS = pathlib.Path('api/charge_stats.go')
for p in (MCP, MODELS, STATS):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

mcp = MCP.read_text(encoding='utf-8', errors='replace')
models = MODELS.read_text(encoding='utf-8', errors='replace')
stats = STATS.read_text(encoding='utf-8', errors='replace')

problems = []


def go_struct_tags(src, name):
    m = re.search(r'type\s+' + name + r'\s+struct\s*\{(.*?)\n\}', src, re.S)
    if not m:
        return None
    return set(re.findall(r'`json:"([^"]+)"`', m.group(1)))


server_tags = set()
for name in ('ChargeStats', 'ChargeStatsRange', 'ChargeStatsLastFull'):
    t = go_struct_tags(models, name)
    if t is None:
        problems.append('服务端找不到 type %s（api/models.go）' % name)
    else:
        server_tags |= t

go_keys = re.findall(r'\{key:\s*"([^"]+)"', stats)
if not go_keys:
    problems.append('服务端的区间表（api/charge_stats.go）读不到')

# ① 取 MCP 工具的函数体：**先跳参数表**（默认参数里有 `= {}` 的坑），
#    再取到下一个 `@mcp.tool()`。
m = re.search(r'def\s+teslamate_charge_stats\s*\(', mcp)
body = ''
if not m:
    problems.append('MCP 里找不到 teslamate_charge_stats（mcp/teslamate_mcp.py）')
else:
    i = m.end() - 1
    depth = 0
    while i < len(mcp):
        c = mcp[i]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        i += 1
    colon = mcp.find(':', i)
    rest = mcp[colon + 1:] if colon >= 0 else ''
    nxt = rest.find('\n@mcp.tool()')
    body = rest[:nxt] if nxt >= 0 else rest

# ② 它读的**服务端键**必须都在服务端结构体里（键名写错 ⇒ 值恒 null）
read_keys = set(re.findall(r'\.get\("([^"]+)"\)', body))
for k in sorted(read_keys):
    if k not in server_tags:
        problems.append('MCP 读了服务端的 %s，但服务端结构体里没有这个键 ⇒ '
                        '工具会静默返回 null（v0.72.0 同款事故）' % k)

# ③ MCP 的两张区间表必须指向服务端真有的档位
tm = re.search(r'_CHARGE_RANGE_CN\s*=\s*\{(.*?)\n\}', mcp, re.S)
if not tm:
    problems.append('MCP 里找不到 _CHARGE_RANGE_CN')
else:
    for k in sorted(set(re.findall(r'"([^"]+)":\s*"', tm.group(1)))):
        if k not in go_keys:
            problems.append('MCP 的区间标签表里有 %s，服务端没有这一档' % k)
tm = re.search(r'_CHARGE_RANGE_ALIAS\s*=\s*\{(.*?)\n\}', mcp, re.S)
if not tm:
    problems.append('MCP 里找不到 _CHARGE_RANGE_ALIAS')
else:
    for v in sorted(set(re.findall(r':\s*"([^"]+)"', tm.group(1)))):
        if v not in go_keys:
            problems.append('MCP 的别名表指向区间 %s，服务端没有这一档' % v)

# ④ **窗口必须出现在返回的字典里**（不是 docstring 里）——
#    只说「近 30 天」不说边界，用户没法核对「是哪 30 天」。
om = re.search(r'\n    out\s*=\s*\{(.*?)\n    \}', body, re.S)
if body:
    if not om:
        problems.append('取不到 teslamate_charge_stats 的 out 字典 ⇒ 这道闸门要跟着改')
    else:
        out_block = om.group(1)
        # ⚠️ **精确键名比对**（不能用子串！）：
        #    把「窗口起点」改成「窗口起点被删了」时，子串判断照样命中 ⇒ **假绿**。
        out_keys = set(re.findall(r'"([^"]+)"\s*:', out_block))
        for k in ('窗口起点', '窗口终点'):
            if k not in out_keys:
                problems.append('MCP 的返回里没有「%s」⇒ 只说「近 30 天」不说边界' % k)

if problems:
    print('MCP 与服务端的键名对不上 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ MCP 读的服务端键必须在服务端结构体里存在；返回里必须带窗口起点/终点')
    sys.exit(1)

print('MCP 读的服务端键都在服务端结构体里（%d 个），区间表与返回的窗口都对得上'
      % len(read_keys))
PYEOF
if [ $? -eq 0 ]; then ok "MCP 与服务端的键名一致（且返回带窗口边界）"; else fail "MCP 与服务端的键名"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 23：车况页「今天 / 近 7 天」不许再并排 + 拆行必须宽度驱动 ──────
#
# ⚠️ **v0.112.0**
#
# 这一行原来把两段**并排**放着（Row + 12dp 间距）：
#   「今天 4.4 公里 · 15 分钟」 ＋ 「近 7 天 243.3 公里 · 4 天有行程」
# 两段加起来要 ≈404dp，而车况页内容区只有 328~361dp
# ⇒ **连 100% 字号都放不下** ⇒ 后一段被折行劈开
#    （「4 天有行程」变成「4 天」+「有行程」＝半个句子）。
#
# ⇒ 这道闸门钉**结构**（不是文案）：
#   ① TodayWeekRow.kt 里**不许再出现 Row(** ⇒ 两段不许并排；
#   ② 必须调用 usageLineNeedsSplit( ⇒ 拆行是**宽度驱动**的，不许写死；
#   ③ 必须调用 usageTodayLine( / usageWeekLine( ⇒ 文案只有一个事实源；
#   ④ 「数 + 单位」之间必须是不换行空格（NBSP）
#      —— 普通空格 ⇒ 200% 字号下「4.4」和「公里」会被折行拆开；
#   ⑤ 宽度预算 USAGE_ROW_CONTENT_DP 必须 = 328f
#      ⇒ 不许改小预算来把「放不下」藏起来。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

UI = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui')
ROW = UI / 'TodayWeekRow.kt'
MET = UI / 'UsageMetrics.kt'
for p in (ROW, MET):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


def strip_kt_comments(src):
    """剥掉 Kotlin 注释 —— 说明文字里提到被禁的写法不算违规（否则假红）。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


row = strip_kt_comments(read(ROW))
met = strip_kt_comments(read(MET))

problems = []

# ① 不许再并排
if re.search(r'\bRow\s*\(', row):
    problems.append('TodayWeekRow.kt 里还有 Row( ⇒ 两段又并排了 ⇒ '
                    '它们加起来放不下（≈404dp > 328~361dp）⇒ 又会被折行劈开')

# ② 拆行必须宽度驱动
if 'usageLineNeedsSplit(' not in row:
    problems.append('TodayWeekRow.kt 没有调用 usageLineNeedsSplit( ⇒ '
                    '拆行被写死了 ⇒ 改字号 / 改文案就会漏判')

# ③ 文案只有一个事实源
for fn in ('usageTodayLine(', 'usageWeekLine('):
    if fn not in row:
        problems.append('TodayWeekRow.kt 没有调用 %s ⇒ 文案被抄到界面里了（会有两份说法）' % fn)

# ④ 数与单位之间必须是不换行空格 —— **每一处**都要。
#    ⚠️ **只查「文件里有没有出现过 NBSP」是不够的**：
#    负向探针把**其中一处**换成普通空格，闸门照样绿（另一处还在）⇒ **假绿**。
#    ⇒ 改成**逐处**检查：每个单位的**前一个字符**都必须是不换行空格。
for unit in ('公里', '分钟', '天有行程'):
    for mm in re.finditer(re.escape(unit), met):
        prefix = met[max(0, mm.start() - 8):mm.start()]
        if not prefix.endswith('${NBSP}'):
            problems.append('UsageMetrics.kt 里「%s」前面是普通空格（%r）⇒ '
                            '200%% 字号下数与单位会被折行拆开'
                            % (unit, prefix))

# ⑤ 宽度预算不许被改小
m = re.search(r'USAGE_ROW_CONTENT_DP\s*=\s*([0-9.]+)f', met)
if not m:
    problems.append('UsageMetrics.kt 里找不到 USAGE_ROW_CONTENT_DP ⇒ 这道闸门要跟着改')
elif abs(float(m.group(1)) - 328.0) > 0.01:
    problems.append('USAGE_ROW_CONTENT_DP = %s，应为 328（360dp 窄屏 − 页面 32dp 内边距）'
                    '⇒ 改小预算就把「放不下」藏起来了' % m.group(1))

if problems:
    print('「今天 / 近 7 天」这一行的结构被改坏了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 两段各占一行；放不下就按宽度拆成完整短语；数与单位用不换行空格绑住')
    sys.exit(1)

print('「今天 / 近 7 天」两段各占一行、宽度驱动拆行、数与单位绑在不换行空格上（预算 328dp）')
PYEOF
if [ $? -eq 0 ]; then ok "「今天 / 近 7 天」不许并排 + 宽度驱动拆行"; else fail "「今天 / 近 7 天」的结构"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 24：「最近 30 天」卡那四格指标的**列数**不许写死（大字号要改单列）──
#
# ⚠️ **v0.113.0**
#
# 那四格指标（行驶里程 / 充入 / 充电次数 / 行驶天数）原来**列数写死成两列**
# （两个 Row + 四个 `weight(1f)`）⇒ 200% 字号下「3834.6 公里」要 238dp，
# 而两列时每格只有 **148dp**（296dp 内容区 ÷ 2）⇒ 被折成「3834.6」+「公里」
# （**数与单位分离**，ADR #42 的老毛病）。
#
# ⇒ 正解是**换布局**而不是压文案（ADR #39）：列数由**宽度预算**决定
#    （`summaryMetricColumns`）⇒ 放得下两列（视觉与原来一模一样）、
#    放不下单列（每格独占整宽 296dp）。
#
# ⇒ 这道闸门守**结构**：
#   ① 界面必须走 `summaryMetricColumns(`（列数宽度驱动）；
#   ② 每行的格数必须是 `chunked(columns)` —— 不许 `chunked(2)`（写死两列）；
#   ③ 四格的**文案**只有一个事实源（`summaryMetrics(`）——
#      不许在界面里再抄一遍「行驶里程」「充电次数」这些标签；
#   ④ **每一处**「数 + 单位」之间都必须是不换行空格（NBSP）；
#   ⑤ 宽度预算不许被改小（`SUMMARY_CARD_CONTENT_DP = 296f`、
#      两列每格 = 148f）⇒ 改小预算就把「放不下」藏起来了；
#   ⑥ 判定必须读 `fontScale` 且与**每格预算**比较（不许写死一个列数）。
#
# ⚠️ 阈值本身（两列最大倍率 **1.24**、200% 单列放得下）由 **JVM 单测**
#    `SummaryMetricsWidthTest` 钉住 —— 静态闸门看不见实际渲染宽度。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

UI = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui')
CARD = UI / 'RecentSummaryCard.kt'
MET = UI / 'SummaryMetrics.kt'
for p in (CARD, MET):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


def strip_kt_comments(src):
    """剥掉 Kotlin 注释 —— 说明文字里提到被禁的写法不算违规（否则假红）。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def body_of(src, pattern):
    """取 `fun xxx(...)` 之后的函数体。

    ⚠️ **表达式体函数**（`fun f(): T = expr`）**没有花括号** ⇒
    从签名往后取到**下一个空行为止**（别去找 `{` —— 会找到表达式里的 lambda）。
    """
    m = re.search(pattern, src)
    if not m:
        return None
    tail = src[m.end():]
    cut = tail.find('\n\n')
    return tail if cut < 0 else tail[:cut]


card = strip_kt_comments(read(CARD))
met = strip_kt_comments(read(MET))

problems = []

# ① 列数必须宽度驱动
if 'summaryMetricColumns(' not in card:
    problems.append('RecentSummaryCard.kt 没有调用 summaryMetricColumns( ⇒ '
                    '列数写死了 ⇒ 200% 下「3834.6 公里」放不下 148dp ⇒ 又被折成两截')

# ② 每行的格数必须是宽度驱动出来的那个
if re.search(r'chunked\s*\(\s*2\s*\)', card):
    problems.append('RecentSummaryCard.kt 里还有 chunked(2) ⇒ 两列写死了 ⇒ '
                    '大字号下每格只有 148dp ⇒ 指标值被折开')
if not re.search(r'chunked\s*\(\s*columns\s*\)', card):
    problems.append('RecentSummaryCard.kt 里没有 chunked(columns) ⇒ '
                    '每行的格数不是由宽度预算决定的 ⇒ 列数被写死了')

# ③ 文案只有一个事实源
if 'summaryMetrics(' not in card:
    problems.append('RecentSummaryCard.kt 没有调用 summaryMetrics( ⇒ '
                    '四格文案被抄到界面里了（会有两份说法）')
for label in ('行驶里程', '充入', '充电次数', '行驶天数', '行程次数'):
    if '"%s"' % label in card:
        problems.append('RecentSummaryCard.kt 里出现了标签字面量「%s」⇒ '
                        '文案被抄了一份 ⇒ 改文案时容易只改一处' % label)

# ④ 数与单位之间必须是不换行空格 —— **逐处**（每个构造函数各查一次）
#
# ⚠️ **只查「文件里有没有出现过 NBSP」是不够的**：负向探针把其中一处换成
#    普通空格，闸门照样绿（另一处还在）⇒ **假绿** ⇒ 必须逐处查。
for fn, unit in (('summaryKmValue', '公里'), ('summaryKwhValue', '度'),
                 ('summaryCountValue', '$unit')):
    body = body_of(met, r'fun\s+%s\s*\(' % fn)
    if body is None:
        problems.append('SummaryMetrics.kt 里找不到 %s() —— 事实源被改名了？' % fn)
        continue
    if 'NBSP' not in body:
        problems.append('%s() 里没有用不换行空格绑住值与单位 ⇒ '
                        '放不下时数与单位会被折开' % fn)
        continue
    if unit == '$unit':
        if '${NBSP}' not in body:
            problems.append('%s() 里数与单位之间不是 ${NBSP} ⇒ '
                            '「31 次」会被折成「31」+「次」' % fn)
    else:
        idx = body.find(unit)
        prefix = body[max(0, idx - 8):idx] if idx >= 0 else ''
        if idx < 0 or not prefix.endswith('${NBSP}'):
            problems.append('%s() 里「%s」前面不是 ${NBSP}（%r）⇒ '
                            '数与单位会被折开' % (fn, unit, prefix))

# ⑤ 宽度预算不许被改小
m = re.search(r'SUMMARY_CARD_CONTENT_DP\s*=\s*([0-9.]+)f', met)
if not m:
    problems.append('SummaryMetrics.kt 里找不到 SUMMARY_CARD_CONTENT_DP ⇒ 这道闸门要跟着改')
elif abs(float(m.group(1)) - 296.0) > 0.01:
    problems.append('SUMMARY_CARD_CONTENT_DP = %s，应为 296（360dp 窄屏 − 页面 32dp − 卡片 32dp）'
                    '⇒ 改小预算就把「放不下」藏起来了' % m.group(1))
m2 = re.search(r'SUMMARY_METRIC_CELL_DP_TWO_COLUMNS\s*=\s*([^\n]+)', met)
if not m2:
    problems.append('SummaryMetrics.kt 里找不到 SUMMARY_METRIC_CELL_DP_TWO_COLUMNS ⇒ '
                    '这道闸门要跟着改')
else:
    expr = m2.group(1).strip()
    derived = re.fullmatch(r'SUMMARY_CARD_CONTENT_DP\s*/\s*2(\.0)?f', expr) is not None
    if not (derived or expr == '148f'):
        problems.append('SUMMARY_METRIC_CELL_DP_TWO_COLUMNS = %r，应为 148f '
                        '（= SUMMARY_CARD_CONTENT_DP ÷ 2）⇒ 改小它就把「放不下」藏起来了'
                        % expr)

# ⑥ 判定必须读 fontScale，且与「每格预算」比较（不许写死一个列数）
body = body_of(met, r'fun\s+summaryMetricColumns\s*\(')
if body is None:
    problems.append('SummaryMetrics.kt 里找不到 summaryMetricColumns() —— 事实源被改名了？')
else:
    if 'fontScale' not in body:
        problems.append('summaryMetricColumns() 没有读 fontScale ⇒ 列数与字号无关')
    if 'SUMMARY_METRIC_CELL_DP_TWO_COLUMNS' not in body:
        problems.append('summaryMetricColumns() 没有拿宽度预算 '
                        '（SUMMARY_METRIC_CELL_DP_TWO_COLUMNS）比较 ⇒ 列数变成写死的了')

if problems:
    print('「最近 30 天」卡那四格指标的结构被改坏了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 列数由宽度预算决定（summaryMetricColumns）：放得下两列，放不下单列')
    print('   ⇒ 文案只有一个事实源（summaryMetrics），数与单位用不换行空格绑住')
    print('   ⇒ 阈值（两列最大倍率 1.24 / 200% 单列）由 JVM 单测 SummaryMetricsWidthTest 钉住')
    sys.exit(1)

print('「最近 30 天」四格指标：列数宽度驱动、文案单一事实源、数与单位绑住（每格预算 148dp）')
PYEOF
if [ $? -eq 0 ]; then ok "「最近 30 天」四格指标网格（列数宽度驱动 + 文案单一事实源）"; else fail "「最近 30 天」四格指标网格"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 25：车况页那些 **InfoTile 行**的列数不许写死（大字号要改单列）────
#
# ⚠️ **v0.115.0**
#
# 那些格子原来**并排写死**（`Row` + 固定 `weight(1f)`），每格的值放不下时靠
# `maxLines = 2` + `Ellipsis` 硬扛 ⇒ **读数被裁掉**。
#
# 宽度预算（**最窄**的常见屏 360dp）：
#   · 页面级两格（空调 / 车内温度、车外温度 / 功率）：内容区 328dp ⇒ 每格文字区 **130dp**
#     而「31.5°C」在 200% 下要 **138dp** ⇒ 放不下；
#   · 卡片里三格（充到 / 当时上限 / 充入、功率 / 已充入 / 当前电量）：
#     内容区 296dp ⇒ 每格文字区 **64dp**，而「12.3 度」在 100% 下就要 **63dp**
#     ⇒ **只剩 1dp 余量** ⇒ 110% 就开始坏。
#
# ⇒ 正解是**换布局**而不是压文案（ADR #39 / #43）：列数由**宽度预算**决定
#    （`infoTileColumns`）⇒ 放得下并排、放不下单列（每格独占整宽）。
#
# ⇒ 这道闸门守**结构**：
#   ① `StatusScreen.kt` 必须走 `InfoTileRow(`（统一那条路）；
#   ② `StatusScreen.kt` 里**不许**再直接摆 `InfoTile(`（不许有第二条路）
#      —— v0.115.0 登记的「行驶中」那三个例外已在 v0.116.0 **清零**（棘轮：只许变少）；
#   ③ `InfoTileRow.kt` 里必须有 `infoTileColumns(`，且它读了 `fontScale`
#      并按**每格预算**比较（不许写死列数）；
#   ④ **每一处**「数 + 单位」之间都必须是不换行空格（NBSP）
#      —— 普通空格是断行机会 ⇒ 折行会把「12.3」和「度」拆开；
#   ⑤ 宽度预算常量不许被改（改大 = 把「放不下」藏起来）。
#
# ⚠️ 阈值本身（110% / 170% / 200% 各档）由 **JVM 单测** `InfoTileRowWidthTest`
#    钉住 —— 静态闸门看不见实际渲染宽度。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

UI = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ui')
SCREEN = UI / 'StatusScreen.kt'
ROW = UI / 'InfoTileRow.kt'
for p in (SCREEN, ROW):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def read(p):
    return p.read_text(encoding='utf-8', errors='replace')


def strip_kt_comments(src):
    """剥掉 Kotlin 注释 —— 说明文字里提到被禁的写法不算违规（否则假红）。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


screen = strip_kt_comments(read(SCREEN))
row = strip_kt_comments(read(ROW))

problems = []

# ① 统一那条路
if 'InfoTileRow(' not in screen:
    problems.append('StatusScreen.kt 没有调用 InfoTileRow( ⇒ 那些格子还是各自摆的 ⇒ '
                    '列数写死 ⇒ 大字号下读数被裁')

# ② 直接摆 InfoTile( 的地方：**不许有**（例外已于 v0.116.0 清零 ⇒ 棘轮：例外只许变少）
#
# ⚠️ **例外历史**：
#   · v0.115.0：登记了「行驶中」那一行（车速 / 功率 / 电量）三个直接 InfoTile( 例外；
#   · v0.116.0：那一行改走 InfoTileRow( ⇒ **例外清零** ⇒ 以后**不许**再新增直接 InfoTile(。
#
# ⚠️ **v0.116.0 的一处更正**：v0.115.0 段写「42 km/h 真实宽度 ≈ 63.6dp，每格 64dp ⇒ 只剩 0.4dp」
#    —— 那是**手算**（把拉丁字母按 0.55em 算）；App 自己的估算器给的是 **4.07em × 17sp = 69.19dp**
#    ⇒ **100% 就已经放不下 64dp** ⇒ 那一格的值会被折成「42」/「km/h」（**数与单位分离**，ADR #42）
#    ⇒ 那不是「运气」，是**活的 bug**（v0.116.0 已修：两格并排 + 电量独占一行）。
direct_labels = re.findall(r'InfoTile\s*\(\s*"([^"]+)"', screen)
if direct_labels:
    problems.append('StatusScreen.kt 里还有直接摆 InfoTile( 的地方 %r ⇒ '
                    '那些格子又变回「并排写死」⇒ 大字号下读数被裁（ADR #44）'
                    % (direct_labels,))

# ②′ 车速 / 功率 / 电量 三格都必须走 InfoTileRow( ⇒ 都在宽度预算的判定里
for label in ('车速', '功率', '电量', '已充入', '当前电量'):
    if ('"%s" to ' % label) not in screen:
        problems.append('「%s」那一格没有走 InfoTileRow( ⇒ 它不在宽度预算的判定里' % label)

# ②″ 「充电中」那一行必须是**两格并排 + 当前电量独占一行**（v0.117.0）
#
# ⚠️ 功率是 **Double** ⇒ 恒带小数（「120.0 kW」= 79.05dp）
# ⇒ 三格并排时每格只有 64dp ⇒ **放不下** ⇒ `infoTileColumns` 永远判单列
# ⇒ 那张卡永远高 3 倍 ⇒ 必须两格并排 + 电量另起一行（ADR #45 的模式）。
if 'InfoTileRow(listOf("当前电量"' not in screen:
    problems.append('「当前电量」没有独占一行（InfoTileRow(listOf("当前电量"…)）'
                    ' ⇒ 「充电中」那三格会永远塌成三行单列（功率恒带小数 ⇒ 79.05dp > 64dp）')
if not re.search(r'listOf\(\s*"功率" to .*?"已充入" to', screen, re.S):
    problems.append('「功率」与「已充入」不在同一个 listOf 里 ⇒ 「充电中」那行不是两格并排')

# ②‴ 「充电结论」那一行必须是**「充到 / 当时上限」并排 + 「充入」独占一行**（v0.118.0）
#
# ⚠️ 「充入」的值是**度数**（「12.9 度」= 63.07dp /「100.0 度」= **73.61dp**）
# ⇒ 三等分（每格只有 64dp）时，**充得多就放不下**
# ⇒ 整行塌成三行单列 ⇒ 那张卡比预期高 3 倍（v0.115.0 的副作用，与「充电中」同一类）。
if not re.search(r'InfoTileRow\(\s*listOf\("充入"', screen):
    problems.append('「充入」没有独占一行（InfoTileRow(listOf("充入"…)）'
                    ' ⇒ 三格并排时每格只有 64dp，「100.0 度」= 73.61dp ⇒ 放不下 ⇒ 整行塌成三行')
if re.search(r'listOf\([^)]*充入[^)]*当时上限', screen):
    problems.append('「充入」与「当时上限」在同一个 listOf 里 ⇒ 「充入」没有独占一行')
for label in ('充到', '当时上限', '充入'):
    if ('"%s" to ' % label) not in screen:
        problems.append('「%s」那一格不见了（v0.118.0：充到 / 当时上限并排一行，充入独占一行）' % label)

# ③ 判定必须是宽度驱动的
if 'fun infoTileColumns(' not in row:
    problems.append('InfoTileRow.kt 里找不到 fun infoTileColumns( —— 规则搬家了？')
else:
    m = re.search(r'fun\s+infoTileColumns\s*\(', row)
    tail = row[m.end():m.end() + 900]
    if 'fontScale' not in tail:
        problems.append('infoTileColumns() 没有读 fontScale ⇒ 列数与字号无关')
    if 'INFO_TILE_PADDING_H_DP' not in tail:
        problems.append('infoTileColumns() 的每格预算没有扣掉 InfoTile 内边距'
                        '（INFO_TILE_PADDING_H_DP）⇒ 预算算大了 ⇒ 「放不下」被藏起来')
    # ⚠️ 必须真的拿**最宽的值**和**每格预算**比较 —— 只查「用了某个常量」不够：
    #    把判定改成写死的列数（`return n`）也必须报红。
    if not re.search(r'widest\s*<=\s*textDp', tail):
        problems.append('infoTileColumns() 没有拿「最宽的值」和「每格预算」比较 ⇒ 列数被写死了')

# ④ 数与单位之间必须是不换行空格 —— **逐处**（两个构造函数各查一次）
#
# ⚠️ **只查「文件里有没有出现过 NBSP」是不够的**：把其中一处换成普通空格，
#    闸门照样绿（另一处还在）⇒ **假绿** ⇒ 必须逐处查。
for fn, unit in (('infoTileKwhValue', '度'), ('infoTileKwValue', 'kW'), ('infoTileSpeedValue', 'km/h')):
    m = re.search(r'fun\s+%s\s*\(' % fn, row)
    if not m:
        problems.append('InfoTileRow.kt 里找不到 %s() —— 事实源被改名了？' % fn)
        continue
    body = row[m.end():m.end() + 400].split('\n\n')[0]
    if 'NBSP' not in body:
        problems.append('%s() 里没有用不换行空格绑住值与单位 ⇒ '
                        '放不下时数与单位会被折开' % fn)
        continue
    idx = body.find(unit)
    prefix = body[max(0, idx - 8):idx] if idx >= 0 else ''
    if idx < 0 or not prefix.endswith('${NBSP}'):
        problems.append('%s() 里「%s」前面不是 ${NBSP}（%r）⇒ 数与单位会被折开'
                        % (fn, unit, prefix))

# 界面里不许再出现「数 + 普通空格 + 单位」的字面量
for lit in ('" 度"', '" kW"', '" km/h"'):
    if lit in screen:
        problems.append('StatusScreen.kt 里还有字面量 %s ⇒ '
                        '数与单位之间是**普通空格**（断行机会）⇒ 会被折开' % lit)

# ⑤ 宽度预算不许被改（改大 = 把「放不下」藏起来）
budgets = (
    ('INFO_TILE_PAGE_CONTENT_DP', 328.0),
    ('INFO_TILE_CARD_CONTENT_DP', 296.0),
    ('INFO_TILE_PADDING_H_DP', 28.0),
    ('INFO_TILE_VALUE_SP', 17.0),
    ('INFO_TILE_LABEL_SP', 13.0),
)
for name, want in budgets:
    m = re.search(name + r'\s*=\s*([0-9.]+)f', row)
    if not m:
        problems.append('InfoTileRow.kt 里找不到 %s ⇒ 这道闸门要跟着改' % name)
    elif abs(float(m.group(1)) - want) > 0.01:
        problems.append('%s = %s，应为 %s ⇒ 改它就把「放不下」藏起来了'
                        % (name, m.group(1), want))

if problems:
    print('车况页那些 InfoTile 行的结构被改坏了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 列数由宽度预算决定（infoTileColumns）：放得下并排，放不下单列')
    print('   ⇒ 数与单位用不换行空格绑住；界面只走 InfoTileRow( 这一条路')
    print('   ⇒ 阈值（110% / 170% / 200% 各档）由 JVM 单测 InfoTileRowWidthTest 钉住')
    sys.exit(1)

print('车况页那些 InfoTile 行：列数宽度驱动、数与单位绑住（每格预算 130dp / 64dp）')
PYEOF
if [ $? -eq 0 ]; then ok "车况页 InfoTile 行（大字号改单列 + 单位绑不换行空格）"; else fail "车况页 InfoTile 行"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 26：文档里引用的宽度数字必须与**单测钉死的**一致 ──────────────
#
# ⚠️ **v0.117.0**：这个闸门是因为「手算的宽度写进文档」出的事 ——
#   v0.115.0 段里「「42 km/h」≈ 63.6dp」是手算的（拉丁字母按 0.55em 算），
#   而 App 自己的估算器给的是 **69.19dp** ⇒ 差 5.19dp
#   ⇒ 差点把一个「100% 就存在的 bug」当成「运气问题」放过去。
# ⇒ 所以：**宽度一律用估算器算（或让单测算），别手算**；
#   写进文档的每个宽度数字，必须在**单测**里有同名断言（两边出现同一个数）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
DOCS = [ROOT / 'docs/CHANGELOG.md', ROOT / 'docs/adr.md']
PIN = ROOT / 'app-android/app/src/test/java/com/teslamate/app/ui/WidthBudgetPinTest.kt'

if not PIN.exists():
    print('找不到 %s —— 这道闸门要跟着改' % PIN)
    sys.exit(1)

pin = PIN.read_text(encoding='utf-8')
docs = ''.join(p.read_text(encoding='utf-8') for p in DOCS if p.exists())

# (数字, 它是什么, 关键词) —— 每个都必须同时出现在：
#   ① **单测的断言行**里（钉死）；② **文档里提到该关键词的那一行**（引用）。
# ⚠️ **关键词是必需的**（v0.118.0 加固）：只查「这个数在文件里出现过」⇒
#   那个数**在别处还活着**时闸门照样绿 —— 实测：只改它被引用的那一行 ⇒ 闸门没红。
PAIRS = [
    ('69.19', '「42 km/h」/「31.5°C」', '42 km/h'),
    ('68.00', '「-3.5°C」（v0.117.0 更正：负号 0.55em，比正温还窄）', '-3.5°C'),
    ('63.07', '「12.3 度」', '12.3 度'),
    ('48.62', '「11 kW」', '11 kW'),
    ('79.05', '「120.0 kW」（充电功率恒带小数 ⇒ 超过三等分的 64dp）', '120.0 kW'),
    ('30.43', '「85%」', '85%'),
    ('52.00', '标签「当时上限」@13sp', '当时上限'),
    ('119.00', '「3834.6 公里」@20sp', '3834.6 公里'),
    ('132.75', '「近 7 天 243.3 公里」@15sp', '近 7 天 243.3 公里'),
    ('164.25', '同一句的保守口径（NBSP 按 1.0em）', '近 7 天 243.3 公里'),
    ('172.95', '「今天 4.4 公里 · 15 分钟」@15sp', '今天 4.4 公里 · 15 分钟'),
    ('189.93', '续航结论句 @13sp', '88 条实测'),
    ('105.00', '「方向盘加热开关」@15sp', '方向盘加热开关'),
    ('73.61', '「100.0 度」@17sp（充电结论那行 ⇒ 也会塌成三行）', '100.0 度'),
    ('230.55', '「近 7 天 243.3 公里 · 4 天有行程」@15sp（乐观口径）', '近 7 天 243.3 公里'),
    ('262.05', '同一句的**保守口径**（NBSP 按 1.0em）', '近 7 天那行'),
    ('193.95', '「今天 4.4 公里 · 15 分钟」的**保守口径**', '今天那行'),
    ('81.84', '主角「204」@44sp（乐观口径）', '「204」'),
    ('104.00', '主角标签「现在能开（公里）」@13sp（乐观口径）', '「现在能开（公里）」'),
    ('367.20', '保养卡底部行旧文案 @24sp（200% 字号 ⇒ 367.20dp > 344dp 预算）', '基准与间隔（爱车）'),
    ('206.40', '保养卡底部行新文案 @24sp（200% 字号）', '基准与间隔 · 爱车'),
    ('346.06', '旧文案「本次实测：从 68% 充到 80% 用了 36 分钟（读自充电曲线）」@13sp（> 328dp 窄屏内容区 ⇒ 正常字号就放不下）', '本次实测：从 68% 充到 80%'),
    ('692.12', '同一句 @26sp（200% 字号）', '本次实测：从 68% 充到 80%'),
    ('145.34', '新标签「本次实测：68% 充到 80%」@13sp', '本次实测：68% 充到 80%'),
    ('290.68', '新标签 @26sp（200% 字号）', '本次实测：68% 充到 80%'),
    ('308.23', '旧文案「最省电速度档的能耗（kWh/100km，不含回充抵扣）」@13sp（视觉宽度 45 ⇒ 登记在棘轮基线里）', '最省电速度档的能耗（kWh/100km，不含回充抵扣）'),
    ('230.23', '新标签「最省电速度档的毛耗电（kWh/100km）」@13sp（视觉宽度 33 ⇒ 退出棘轮）', '最省电速度档的毛耗电（kWh/100km）'),
]

problems = []
pin_lines = pin.splitlines()
doc_lines = ('\n'.join(p.read_text(encoding='utf-8') for p in DOCS if p.exists())).splitlines()
for num, what, kw in PAIRS:
    # ⚠️ **必须出现在断言行里** —— 只查「文件里出现过这个数」是**假绿**：
    #    注释里提一句就能满足 ⇒ 探针把断言改坏了闸门照样绿（v0.117.0 实测）。
    if not any(('assertEquals(' in ln and num in ln) for ln in pin_lines):
        problems.append('单测里没有把 %s 钉成断言（%s）⇒ 估算器变了没人知道' % (num, what))
    # ⚠️ **必须与关键词同行** —— 只查「这个数在文件里出现过」⇒ 那个数在别处还活着时
    #    闸门照样绿（v0.118.0 实测：只改了它被引用的那一行 ⇒ 闸门没红）。
    if not any((num in ln and kw in ln) for ln in doc_lines):
        problems.append('文档里没有在提到「%s」的地方引用 %s（%s）⇒ 文档与代码对不上' % (kw, num, what))

if problems:
    print('文档引用的宽度数字与单测钉死的不一致 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 宽度一律用 estimateTextWidthDpWithNbsp 算（或让单测算），别手算')
    print('   ⇒ 改了估算器 ⇒ 同步改单测与文档（两边都出现同一个数）')
    sys.exit(1)

print('文档引用的 %d 个宽度数字与单测钉死的一致' % len(PAIRS))
PYEOF
if [ $? -eq 0 ]; then ok "文档引用的宽度数字 = 单测钉死的（别手算）"; else fail "文档引用的宽度数字与单测不一致"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 27：首屏排布必须**宽度驱动**（不许拍死的字号阈值）──────────────
#
# ⚠️ **v0.120.0**：原来首屏「环 + 主角」是用 `fontScale >= 1.35f` 这个**拍死的阈值**
#   决定并排 / 上下叠 ⇒ ① 它的推导里那个宽度是**手算**的（73.9sp，
#   而估算器给的是 81.84dp）而且**没有任何单测钉住**；② 它**完全不看屏宽**
#   ⇒ 宽屏与窄屏在同一字号上一起竖排 ⇒ 宽屏白丢「环 + 主角并排」的构图。
# ⇒ 现在由 `heroArrangement` 按**运行时量出来的宽度**决定（同 ADR #43 / #44）。
#
# ⚠️ **判据必须先剥注释** —— 否则注释里提一句「1.35」就能满足 ⇒ 假绿。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
SCREEN = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui/StatusScreen.kt'
ARRANGE = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui/RangeArrangement.kt'
WIDGETS = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui/Widgets.kt'

for p in (SCREEN, ARRANGE, WIDGETS):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

def strip_comments(src):
    # 块注释（含 KDoc）先剥，再按行剥 // 注释。
    # ⚠️ 这三个文件里没有带 `://` 的字符串 ⇒ 按行剥是安全的。
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)

screen = strip_comments(SCREEN.read_text(encoding='utf-8'))
arrange = strip_comments(ARRANGE.read_text(encoding='utf-8'))
widgets = strip_comments(WIDGETS.read_text(encoding='utf-8'))

problems = []

# ① 不许再出现拍死的字号阈值
if 'fontScale >= 1.35f' in screen:
    problems.append('StatusScreen.kt 里还有 `fontScale >= 1.35f` ⇒ 排布又变回拍死的阈值了')

# ② 必须走宽度驱动的判定
if 'heroArrangement(' not in screen:
    problems.append('StatusScreen.kt 里没有调用 heroArrangement( ⇒ 首屏排布不是宽度驱动的')

# ③ 规则文件必须带着那几个预算常量
for name in ('HERO_VALUE_SP', 'HERO_LABEL_SP', 'HERO_RING_GAP_DP',
             'HERO_FIT_SLACK_DP', 'SOC_RING_MIN_DP'):
    if not re.search(r'const val ' + name + r'\s*=\s*', arrange):
        problems.append('RangeArrangement.kt 里没有 %s ⇒ 宽度预算缺一项' % name)

# ④ 预算里的环宽必须**等于画出来的**环宽（Widgets.kt 的 SocRing 默认 baseSizeDp）
m = re.search(r'baseSizeDp:\s*Int\s*=\s*(\d+)', widgets)
n = re.search(r'const val SOC_RING_MIN_DP\s*=\s*(\d+)', arrange)
if not m or not n:
    problems.append('找不到 SocRing 的 baseSizeDp 默认值或 SOC_RING_MIN_DP ⇒ 核对一下')
elif m.group(1) != n.group(1):
    problems.append('SocRing 画的环用 baseSizeDp=%s，而预算用的是 SOC_RING_MIN_DP=%s ⇒ 两者不是同一个环'
                    % (m.group(1), n.group(1)))

if problems:
    print('首屏排布不是宽度驱动的 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 排布由 heroArrangement（ui/RangeArrangement.kt）按运行时宽度决定')
    print('   ⇒ 阈值由 JVM 单测 RangeArrangementTest 钉住')
    sys.exit(1)

print('首屏排布：宽度驱动（heroArrangement）· 预算常量齐全 · 环宽与画出来的一致')
PYEOF
if [ $? -eq 0 ]; then ok "首屏排布宽度驱动（不许拍死的字号阈值）"; else fail "首屏排布宽度驱动"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 28：保养卡底部那一行不许再有**教学语**（也不许把箭头挤出屏幕）─────────
#
# ⚠️ **v0.121.0**：这一行原来写的是「基准与间隔（爱车）· 点这里修改」——
#   ① 「点这里修改」是**教学语**：界面里教用户怎么点；而**可点性已经由右边的
#      箭头图标承担了** ⇒ 同一件事说两遍（降噪的第一原则：一句事实只说一次）。
#      ⚠️ 全 App 扫过：这一行是**最后**一处「点这里 / 点击这里」类教学语。
#   ② 「（车名）」的括号把车名降级成「补充说明」
#      （用户反馈 #25 的原话：「各个按钮**可以不需要括号内的那些东西**」）。
#   ③ 旧文案在 200% 字号下要 367.20dp > 那一行的 344dp 预算 ⇒ 会被折行 /
#      把右边的箭头挤出屏幕。
#
# ⚠️ **判据必须先剥注释** —— 否则注释里提一句「点这里」就能满足 ⇒ 假绿。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
KT = ROOT / 'app-android/app/src/main/java/com/teslamate/app'
MAINT = KT / 'ui/Maintenance.kt'
SCREEN = KT / 'ui/StatusScreen.kt'

for p in (MAINT, SCREEN):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def strip_comments(src):
    # 块注释（含 KDoc）先剥，再按行剥 // 注释。
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


maint = strip_comments(MAINT.read_text(encoding='utf-8', errors='replace'))
screen = strip_comments(SCREEN.read_text(encoding='utf-8', errors='replace'))

problems = []

# ① 纯函数层：那一行的文案不许出现教学语 / 全角括号
m = re.search(r'fun\s+maintenanceBaselineLabel\s*\([^)]*\)\s*:\s*String\s*=(.*?)\n\n', maint, re.S)
if not m:
    problems.append('ui/Maintenance.kt 里找不到 maintenanceBaselineLabel(...) ⇒ 那一行的文案没有唯一事实源')
else:
    body = m.group(1)
    for bad in ('点这里', '点击', '修改'):
        if bad in body:
            problems.append('maintenanceBaselineLabel 里出现了「%s」⇒ 界面里不该教用户怎么点' % bad)
    for bad in ('（', '）'):
        if bad in body:
            problems.append('maintenanceBaselineLabel 里出现了全角括号 ⇒ 车名被降级成补充说明')

# ② 界面层：那一行必须走那个纯函数，且不许把箭头挤出屏幕
mm = re.search(r'private fun MaintenanceCard\s*\(.*?\n\}', screen, re.S)
if not mm:
    problems.append('StatusScreen.kt 里找不到 MaintenanceCard ⇒ 这道闸门要跟着改')
else:
    card = mm.group(0)
    if 'maintenanceBaselineLabel(' not in card:
        problems.append('MaintenanceCard 里没有调用 maintenanceBaselineLabel( ⇒ 文案没有唯一事实源')
    if 'buildString' in card:
        problems.append('MaintenanceCard 里出现了 buildString ⇒ 文案又在界面里现拼了')
    idx = card.find('maintenanceBaselineLabel(')
    if idx >= 0:
        # ⚠️ 判据必须**精确到那一段** —— 整张卡里出现一次 maxLines 不算数
        region = card[idx:idx + 600]
        if 'maxLines = 1' not in region:
            problems.append('那一行的 Text 没有 maxLines = 1 ⇒ 车名过长会把箭头挤出屏幕')
        if 'TextOverflow.Ellipsis' not in region:
            problems.append('那一行的 Text 没有 TextOverflow.Ellipsis ⇒ 放不下时会被裁成半个词')

# ③ 全 App（剥注释后）不许再有「点这里 / 点击这里」这类教学语
for f in sorted(KT.rglob('*.kt')):
    src = strip_comments(f.read_text(encoding='utf-8', errors='replace'))
    for bad in ('点这里', '点击这里'):
        if bad in src:
            problems.append('界面里还有教学语「%s」（%s）⇒ 可点性由图标承担，文字不该再教一次' % (bad, f.name))

if problems:
    print('保养卡底部那一行还有问题 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 文案由 ui/Maintenance.kt 的 maintenanceBaselineLabel 唯一给出')
    print('   ⇒ 宽度预算：BASELINE_ROW_TEXT_DP（单测 MaintenanceBaselineLabelTest 钉住）')
    sys.exit(1)

print('保养卡底部那一行：只剩事实 · 无教学语 · 无括号 · 单行 + 省略号')
PYEOF
if [ $? -eq 0 ]; then ok "保养卡底部行无教学语（可点性由图标承担）"; else fail "保养卡底部行还有教学语 / 几何隐患"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 29：充电详情「实测充到 80% 用了多久」必须是一条 KV 行，且两条分支共用一句话 ──
#
# ⚠️ **v0.122.0**：这一行原来是一句**平铺的自由句**：
#   「本次实测：从 68% 充到 80% 用了 36 分钟（读自充电曲线）」
# 两个毛病：
#   ① 自由句：那一屏**唯一**的非 KV 行（邻居全是「该站同起点实测中位」这类 KV）；
#   ② **同一个事实有两套说法** —— 服务端路径写「本次实测：…（读自充电曲线）」，
#      老服务端兜底路径写「实测从 …（从曲线直接读）」，两条是**互斥**分支
#      ⇒ 用户看到的是「今天这句、明天那句」。
# ⇒ 现在：文案只由 `ui/ChargeMeasured.kt` 的三个纯函数给出，两条分支**都**走它；
#   来源披露折进 ⓘ（`note = noteMeasuredTo80()`）。
#
# ⚠️ 判据必须先剥注释 —— 否则注释里提一句旧句式就能满足 ⇒ 假绿。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
KT = ROOT / 'app-android/app/src/main/java/com/teslamate/app'
MEAS = KT / 'ui/ChargeMeasured.kt'
SCREEN = KT / 'ui/ChargesScreen.kt'
BASE = ROOT / 'scripts/verbose-baseline.txt'

for p in (MEAS, SCREEN, BASE):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)


def strip_comments(src):
    # 块注释（含 KDoc）先剥，再按行剥 // 注释。
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


meas = strip_comments(MEAS.read_text(encoding='utf-8', errors='replace'))
screen = strip_comments(SCREEN.read_text(encoding='utf-8', errors='replace'))

problems = []

# ① 纯函数层：三个函数各定义**恰好一次**（文案只有一个事实源）
for fn in ('measuredTo80Label', 'measuredTo80Value', 'noteMeasuredTo80'):
    n = len(re.findall(r'fun\s+%s\s*\(' % fn, meas))
    if n != 1:
        problems.append('ui/ChargeMeasured.kt 里 %s 定义了 %d 次（应为 1）⇒ 文案没有唯一事实源' % (fn, n))

# ② 旧句式不许复活（剥注释后查）
for bad in ('本次实测：从 $', '充到 80% 用了', '（读自充电曲线）', '（从曲线直接读）'):
    if bad in screen:
        problems.append('ChargesScreen.kt 里还有旧句式「%s」⇒ 自由句又回来了' % bad)

# ③ 两条分支都走**同一个**纯函数：各调用恰好两次（服务端路径 + 老服务端兜底）
for fn in ('measuredTo80Label(', 'measuredTo80Value('):
    n = screen.count(fn)
    if n != 2:
        problems.append('ChargesScreen.kt 里 %s 调用了 %d 次（应为 2：服务端路径 + 兜底路径）⇒ 两条分支写法又分叉了' % (fn, n))

# ④ 啰嗦棘轮基线里那两句必须已经删掉
base = BASE.read_text(encoding='utf-8', errors='replace')
if '充到 80% 用了' in base:
    problems.append('scripts/verbose-baseline.txt 里还有那两句 ⇒ 棘轮没有收缩')

# ⑤ 全 App（剥注释后）「本次实测：」只许出现一次（只在那一个纯函数里）
hits = 0
for f in sorted(KT.rglob('*.kt')):
    hits += strip_comments(f.read_text(encoding='utf-8', errors='replace')).count('本次实测：')
if hits != 1:
    problems.append('全 App 里「本次实测：」出现 %d 次（应为 1，只在 ui/ChargeMeasured.kt）⇒ 同一句话被抄了第二份' % hits)

if problems:
    print('「实测充到 80% 用了多久」那一行还有问题 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 文案只由 ui/ChargeMeasured.kt 给出（measuredTo80Label / measuredTo80Value / noteMeasuredTo80）')
    print('   ⇒ 两条分支共用它；来源披露折进 ⓘ（note = noteMeasuredTo80()）')
    sys.exit(1)

print('「实测充到 80% 用了多久」：一条 KV 行 · 两条分支同一句话 · 无旧句式 · 棘轮已收缩')
PYEOF
if [ $? -eq 0 ]; then ok "实测到 80% 那一行是 KV 行且两条分支共用一句话"; else fail "实测到 80% 那一行还有问题"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 30：「线路余量表」卡的「全程最低」只许渲染**一处** ─────────────────
#
# ⚠️ **v0.124.0**：这个事实原来在卡里印了**两遍** ——
#   ① 顶部细节行（`verdictDetail()`，随起点站重算）；
#   ② 逐站列表末尾那一行（**全线**最低点，没随起点站重算）。
# ⇒ 选了起点站时，同屏出现**两个不同的「全程最低」**（自相矛盾）；
# ⇒ 而且两遍是**两种说法**（「（第 N 公里 · …之后）」vs「（在第 N 公里…）」）。
# ⇒ 现在只留 `verdictDetail()` 一处；末尾那行删掉，「低于 20% 保底」并进细节行。
#
# ⚠️ 判据**只扫 RoutePlan.kt** —— `DrivesScreen.kt` 里也有「全程最低」，
#   但那是**行程详情**的另一个事实（不是这张卡的）⇒ 全 App 扫会**假红**。
# ⚠️ 判据必须先剥注释 —— 否则注释里提一句就能满足 ⇒ 假绿。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
RP = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui/RoutePlan.kt'

if not RP.exists():
    print('找不到 %s —— 这道闸门要跟着改' % RP)
    sys.exit(1)


def strip_comments(src):
    # 块注释（含 KDoc）先剥，再按行剥 // 注释。
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


src = strip_comments(RP.read_text(encoding='utf-8', errors='replace'))
lines = src.splitlines()

problems = []

# ① 「全程最低」只许出现一次
hits = [i for i, ln in enumerate(lines) if '全程最低' in ln]
if len(hits) != 1:
    problems.append('RoutePlan.kt 里「全程最低」出现 %d 次（应为 1）⇒ 同一个事实又印了两遍' % len(hits))


def enclosing_fun(lines, idx):
    for i in range(idx, -1, -1):
        m = re.match(r'\s*(?:private |public |internal |protected |suspend |inline |operator )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', lines[i])
        if m:
            return m.group(1)
    return None


if len(hits) == 1:
    fn = enclosing_fun(lines, hits[0])
    if fn != 'verdictDetail':
        problems.append('「全程最低」那一句不在 verdictDetail 里（实际在 %s）⇒ 文案没有唯一事实源' % (fn or '(无函数)'))

# ② verdictDetail 的函数体里必须调 hopBefore（落点与公里同源）
m = re.search(r'fun\s+verdictDetail\s*\([^)]*\)\s*:\s*String\s*\{(.*?)\n    \}', src, re.S)
if not m:
    problems.append('RoutePlan.kt 里找不到 verdictDetail(...) ⇒ 这道闸门要跟着改')
else:
    if 'hopBefore(' not in m.group(1):
        problems.append('verdictDetail 里没有调 hopBefore( ⇒ 落点没跟公里同源（会出现「第 3100 公里 · 起点之后」）')

if problems:
    print('「全程最低」这个事实还有问题 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 只由 RoutePlan.verdictDetail 一处给出（标签/值/保底限定都在那里）')
    sys.exit(1)

print('「全程最低」只在一处（verdictDetail），落点与公里同源')
PYEOF
if [ $? -eq 0 ]; then ok "「全程最低」只渲染一处（且落点与公里同源）"; else fail "「全程最低」又印了两遍 / 落点与公里不同源"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 31：能耗画像卡那两行对照数据必须**同形** ───────────────────────
#
# ⚠️ **v0.126.0**：行 A「100–110 km/h · 跑了 1234 公里」与行 B
#   「跑得最多的档：130+ km/h · 1156 公里 · 18.7 kWh/100km」是**同一类数据**，
#   却用了两种句式（一个带只有它有的动词、一个用冒号分隔标签与数据）
#   ⇒ 读者要在这两行之间换一次阅读方式（用户反馈 #25：「把文字的布局尽量统一」）。
# ⇒ 现在两行同形（`档位 · 公里`），文案只由 ui/EnergyProfile.kt 的两个纯函数给出。
#
# ⚠️ 判据必须先剥注释 —— 否则注释里提一句旧句式就误报。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
KT = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui'
EP = KT / 'EnergyProfile.kt'
CARD = KT / 'EnergyProfileCard.kt'

for p in (EP, CARD):
    if not p.exists():
        print('\u627e\u4e0d\u5230 %s \u2014\u2014 \u8fd9\u9053\u95f8\u95e8\u8981\u8ddf\u7740\u6539' % p)
        sys.exit(1)


def strip_comments(src):
    # \u5757\u6ce8\u91ca\uff08\u542b KDoc\uff09\u5148\u5265\uff0c\u518d\u6309\u884c\u5265 // \u6ce8\u91ca\u3002
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


ep = strip_comments(EP.read_text(encoding='utf-8', errors='replace'))
card = strip_comments(CARD.read_text(encoding='utf-8', errors='replace'))

problems = []

# \u2460 \u754c\u9762\u5fc5\u987b\u8c03\u90a3\u4e24\u4e2a\u7eaf\u51fd\u6570\uff08\u6587\u6848\u4e0d\u5728\u754c\u9762\u91cc\u73b0\u62fc\uff09
for fn in ('bestBandLine(', 'mostDrivenBandLine('):
    if fn not in card:
        problems.append('EnergyProfileCard.kt \u91cc\u6ca1\u6709\u8c03 %s \u21d2 \u6587\u6848\u6ca1\u8d70\u552f\u4e00\u4e8b\u5b9e\u6e90' % fn)

# \u2461 \u7eaf\u51fd\u6570\u91cc\u4e0d\u8bb8\u518d\u51fa\u73b0\u5192\u53f7 / \u53ea\u6709\u4e00\u884c\u624d\u6709\u7684\u52a8\u8bcd
for bad in ('\uff1a', '\u8dd1\u4e86'):
    if bad in ep:
        problems.append('ui/EnergyProfile.kt \u91cc\u8fd8\u6709\u300c%s\u300d\u21d2 \u4e24\u884c\u53c8\u4e0d\u540c\u5f62\u4e86' % bad)

# \u2462 \u754c\u9762\u91cc\u4e0d\u8bb8\u628a\u8fd9\u4e24\u884c\u7684\u65e7\u53e5\u5f0f\u5199\u56de\u53bb
for bad in ('\u8dd1\u5f97\u6700\u591a\u7684\u6863\uff1a', '\u8dd1\u4e86'):
    if bad in card:
        problems.append('EnergyProfileCard.kt \u91cc\u8fd8\u6709\u65e7\u53e5\u5f0f\u300c%s\u300d\u21d2 \u53c8\u5199\u56de\u81ea\u7531\u53e5\u4e86' % bad)

if problems:
    print('\u80fd\u8017\u753b\u50cf\u5361\u90a3\u4e24\u884c\u8fd8\u6709\u95ee\u9898 %d \u5904 \u2014\u2014' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   \u21d2 \u6587\u6848\u53ea\u7531 ui/EnergyProfile.kt \u7684 bestBandLine / mostDrivenBandLine \u7ed9\u51fa')
    sys.exit(1)

print('\u80fd\u8017\u753b\u50cf\u5361\u90a3\u4e24\u884c\u540c\u5f62\uff08bestBandLine / mostDrivenBandLine\uff09')
PYEOF
if [ $? -eq 0 ]; then ok "能耗画像卡两行对照数据同形"; else fail "能耗画像卡两行又不同形了"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 32：能耗画像卡的主角标签不许再重复 ⓘ 里已经说过的口径 ──────────────
#
# ⚠️ **v0.128.0**：原来写的是「最省电速度档的能耗（kWh/100km，不含回充抵扣）」——
#   ① 「不含回充抵扣」在卡底 ⓘ 口径说明里**还会出现一次** ⇒ 同一句口径说了两遍；
#   ② 视觉宽度 45（逼近啰嗦棘轮的 44 门槛）⇒ 它是一块「常驻长说明块」；
#   ③ 而明细行并排列着「净 X」⇒ 读者需要知道主角那个数是**毛**的。
# ⇒ 现在标签用「毛耗电」与明细行的「净」对仗 ⇒ 重复那半句删掉 ⇒ 视觉宽度 33 ⇒ **退出**棘轮。
#
# ⚠️ 判据必须先剥注释 —— 否则注释里提一句旧句式就误报。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
KT = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui'
EP = KT / 'EnergyProfile.kt'
CARD = KT / 'EnergyProfileCard.kt'
BASE = ROOT / 'scripts/verbose-baseline.txt'

for p in (EP, CARD, BASE):
    if not p.exists():
        print('\u627e\u4e0d\u5230 %s \u2014\u2014 \u8fd9\u9053\u95f8\u95e8\u8981\u8ddf\u7740\u6539' % p)
        sys.exit(1)


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


card = strip_comments(CARD.read_text(encoding='utf-8', errors='replace'))
ep = strip_comments(EP.read_text(encoding='utf-8', errors='replace'))

problems = []

# \u2460 \u754c\u9762\u5fc5\u987b\u8c03\u90a3\u4e2a\u7eaf\u51fd\u6570
if 'energyBestBandCaption(' not in card:
    problems.append('EnergyProfileCard.kt \u91cc\u6ca1\u6709\u8c03 energyBestBandCaption( \u21d2 \u6587\u6848\u6ca1\u8d70\u552f\u4e00\u4e8b\u5b9e\u6e90')

# \u2461 \u7eaf\u51fd\u6570\u91cc\u4e0d\u8bb8\u518d\u51fa\u73b0\u300c\u4e0d\u542b\u56de\u5145\u62b5\u6263\u300d
if '\u4e0d\u542b\u56de\u5145\u62b5\u6263' in ep:
    problems.append('ui/EnergyProfile.kt \u91cc\u8fd8\u6709\u300c\u4e0d\u542b\u56de\u5145\u62b5\u6263\u300d\u21d2 \u4e0e\u5361\u5e95 \u24d8 \u91cc\u7684\u53e3\u5f84\u8bf4\u660e\u91cd\u590d\u4e86')

# \u2462 \u5168 App\uff08\u5265\u6ce8\u91ca\uff09\u91cc\u300c\u4e0d\u542b\u56de\u5145\u62b5\u6263\u300d 0 \u6b21
hits = 0
for f in sorted(KT.rglob('*.kt')):
    hits += strip_comments(f.read_text(encoding='utf-8', errors='replace')).count('\u4e0d\u542b\u56de\u5145\u62b5\u6263')
if hits != 0:
    problems.append('\u5168 App \u91cc\u300c\u4e0d\u542b\u56de\u5145\u62b5\u6263\u300d\u51fa\u73b0 %d \u6b21 \u21d2 \u540c\u4e00\u53e5\u53e3\u5f84\u8bf4\u4e86\u4e24\u904d' % hits)

# \u2463 \u7eaf\u51fd\u6570\u5b9a\u4e49\u6070\u597d\u4e00\u6b21
n = len(re.findall(r'fun\s+energyBestBandCaption\s*\(', ep))
if n != 1:
    problems.append('ui/EnergyProfile.kt \u91cc energyBestBandCaption \u5b9a\u4e49\u4e86 %d \u6b21\uff08\u5e94\u4e3a 1\uff09' % n)

# \u2464 \u54c8\u55dc\u57fa\u7ebf\u91cc\u4e0d\u8bb8\u518d\u6709 EnergyProfileCard \u7684\u6761\u76ee\uff08\u90a3\u4e24\u884c\u5df2\u9000\u51fa\u68d7\u8f6e\uff09
base = BASE.read_text(encoding='utf-8', errors='replace')
if 'EnergyProfileCard.kt::' in base:
    problems.append('scripts/verbose-baseline.txt \u91cc\u8fd8\u6709 EnergyProfileCard \u7684\u6761\u76ee \u21d2 \u90a3\u4e24\u884c\u5df2\u9000\u51fa\u68d7\u8f6e\uff0c\u4e0d\u8bb8\u7559\u7740')

if problems:
    print('\u80fd\u8017\u753b\u50cf\u5361\u4e3b\u89d2\u6807\u7b7e\u8fd8\u6709\u95ee\u9898 %d \u5904 \u2014\u2014' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   \u21d2 \u6807\u7b7e\u7528\u300c\u6bdb\u8017\u7535\u300d\u4e0e\u660e\u7ec6\u884c\u7684\u300c\u51c0\u300d\u5bf9\u4e14\uff1b\u91cd\u590d\u7684\u53e3\u5f84\u53ea\u5728\u5361\u5e95 \u24d8 \u91cc\u8bf4\u4e00\u6b21')
    sys.exit(1)

print('\u80fd\u8017\u753b\u50cf\u5361\u4e3b\u89d2\u6807\u7b7e\u7528\u300c\u6bdb\u8017\u7535\u300d\uff08\u4e0e\u660e\u7ec6\u884c\u7684\u300c\u51c0\u300d\u5bf9\u4e14\uff09\uff0c\u4e14\u5df2\u9000\u51fa\u68d7\u8f6e')
PYEOF
if [ $? -eq 0 ]; then ok "能耗画像卡主角标签不重复口径（毛/净对仗）且已退出棘轮"; else fail "能耗画像卡主角标签还有问题"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 33：充电列表页「只列最近 N 条」那一行 ⇒ 并列信息用「·」，不许括号降级 ──
#
# ⚠️ **v0.130.0**：那一行原来写「只列最近 100 条（共 128 次）」，
#   而**同一张卡**里的邻居都是「· 平行并列」（「直流 1,234 度 · 56 次」…）
#   ⇒ 并列的第二个事实被括号**降级**成补充说明（ADR #50）。
#
# ⚠️ 判据按「**同一句话只许出现在一个文件**」写 —— 这样**所有写法形态**都盖住：
#   有人在界面里把那句现拼回来 ⇒ ③ 会红；有人在纯函数里写回括号 ⇒ ④ 会红。
# ⚠️ 判据必须先剥注释 —— 否则注释里提一句旧句式就误报。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

ROOT = pathlib.Path('.')
KT = ROOT / 'app-android/app/src/main/java/com/teslamate/app/ui'
CAP = KT / 'ChargeListCap.kt'
PAGE = KT / 'ChargeListPage.kt'

for p in (CAP, PAGE):
    if not p.exists():
        print('\u627e\u4e0d\u5230 %s \u2014\u2014 \u8fd9\u9053\u95f8\u95e8\u8981\u8ddf\u7740\u6539' % p)
        sys.exit(1)


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'(?m)//.*$', '', src)


cap = strip_comments(CAP.read_text(encoding='utf-8', errors='replace'))
page = strip_comments(PAGE.read_text(encoding='utf-8', errors='replace'))

problems = []

# \u2460 \u7eaf\u51fd\u6570\u5b9a\u4e49\u6070\u597d\u4e00\u6b21
n = len(re.findall(r'fun\s+chargeListCapLine\s*\(', cap))
if n != 1:
    problems.append('ui/ChargeListCap.kt \u91cc chargeListCapLine \u5b9a\u4e49\u4e86 %d \u6b21\uff08\u5e94\u4e3a 1\uff09' % n)

# \u2461 \u754c\u9762\u5fc5\u987b\u8c03\u5b83
if 'chargeListCapLine(' not in page:
    problems.append('ChargeListPage.kt \u91cc\u6ca1\u6709\u8c03 chargeListCapLine( \u21d2 \u6587\u6848\u6ca1\u8d70\u552f\u4e00\u4e8b\u5b9e\u6e90')

# \u2462 \u300c\u53ea\u5217\u6700\u8fd1\u300d\u8fd9\u53e5\u8bdd\u5168 App \u53ea\u8bb8\u51fa\u73b0\u5728\u90a3\u4e00\u4e2a\u6587\u4ef6\u91cc
hits = []
for f in sorted(KT.rglob('*.kt')):
    src = strip_comments(f.read_text(encoding='utf-8', errors='replace'))
    if '\u53ea\u5217\u6700\u8fd1' in src:
        hits.append(f.name)
if sorted(hits) != ['ChargeListCap.kt']:
    problems.append('\u300c\u53ea\u5217\u6700\u8fd1\u300d\u51fa\u73b0\u5728 %s \u2014\u2014 \u5e94\u53ea\u5728 ui/ChargeListCap.kt\uff08\u540c\u4e00\u53e5\u8bdd\u53ea\u8bb8\u6709\u4e00\u5904\uff09' % (hits or ['(none)']))

# \u2463 \u90a3\u4e00\u884c\u4e0d\u8bb8\u518d\u7528\u62ec\u53f7\u964d\u7ea7
for bad in ('\uff08', '\uff09'):
    if bad in cap:
        problems.append('ui/ChargeListCap.kt \u91cc\u8fd8\u6709\u5168\u89d2\u62ec\u53f7 \u21d2 \u5e76\u5217\u4e8b\u5b9e\u88ab\u964d\u7ea7\u4e86')
        break

if problems:
    print('\u5145\u7535\u5217\u8868\u9875\u90a3\u4e00\u884c\u8fd8\u6709\u95ee\u9898 %d \u5904 \u2014\u2014' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   \u21d2 \u6587\u6848\u53ea\u7531 ui/ChargeListCap.kt \u7684 chargeListCapLine \u7ed9\u51fa\uff1b\u5e76\u5217\u4e8b\u5b9e\u7528\u300c\u00b7\u300d\u4e0d\u7528\u62ec\u53f7')
    sys.exit(1)

print('\u5145\u7535\u5217\u8868\u9875\u90a3\u4e00\u884c\uff1a\u5e76\u5217\u4e8b\u5b9e\u7528\u300c\u00b7\u300d\uff08\u4e0d\u7528\u62ec\u53f7\u964d\u7ea7\uff09')
PYEOF
if [ $? -eq 0 ]; then ok "充电列表页「只列最近 N 条」用「·」不用括号"; else fail "充电列表页那一行又括号降级 / 现拼了"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 34：跨会话交接位不许用相对称呼 ──────────────────────────
#
# 事故背景：AGENTS.md 有通则「交接位一律用会话全名」（2026-11-04 补）——
#   交接位里写「你/我」「本会话」「并行会话」时，两个会话会各指各的
#   （读到的人不知道说的是哪一方）⇒ 那天真的因此错过一次版本交接。
#   可那句话只是一句「记得写」⇒ 记不住 ⇒ 所以本轮把它变成源码级判据。
#
# 判据（不依赖写法形态：判的是「有没有出现这批相对称呼的字面量」，
#       不是「某一行是不是某个人写的」⇒ 换写法、换位置、换人都照样咬得住）：
#   ① docs/CHANGELOG.md 的**最新段**（第一个 `## ` 标题到第二个之间）
#      —— 那正是「以后的人第一眼看到的位置」；
#   ② AGENTS.md 全文 —— 那正是通则里点名的第二类交接位（实况记录 + 规矩行）；
#      **规则定义处除外**（那几行本来就得写出被禁的词才讲得清）。
#
# ⚠️ **代价（兜底非全覆盖，详见 CHANGELOG v0.139.0 段）**：
#   ① CHANGELOG 只管**最新段** ⇒ 历史版本段里的旧写法保留（按规矩不改）；
#   ② 单人代词「你/我」**不在判据内**（散文里太常见 ⇒ 误报率高）⇒ 仍靠 AGENTS.md 通则管；
#   ③ 被禁字面量的**清单写在这份脚本里** ⇒ **不许抄进 CHANGELOG 最新段**
#      （抄进去就会被自己这条闸门报红 —— 规则不许出现在被搜文本里）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys

ROOT = pathlib.Path('.')
CH = ROOT / 'docs/CHANGELOG.md'
AG = ROOT / 'AGENTS.md'

# 相对称呼（指代不明的那种叫法）：交接位里一律不许出现
BANNED = [
    '本会话',
    '并行会话',
    '该会话',
    '另一会话',
    '另一个会话',
    '那个会话',
    '这个会话',
    'UI 会话',
    'UI会话',
    '对面会话',
]

# 规则定义处的标记：那几行本来就得写出被禁的词（否则讲不清「不许写什么」）
DEF_MARKERS = [
    '不许写',
    '相对称呼',
    '会话全名',
]

problems = []


def read_lines(p):
    if not p.exists():
        return None
    raw = p.read_bytes()
    text = raw.decode('utf-8-sig', errors='replace')
    return text.split('\n')


def hits(line):
    """这一行里出现的被禁字面量（去重，且不因子串重叠而重复报）"""
    out = []
    rest = line
    for b in BANNED:
        if b in rest:
            out.append(b)
            rest = rest.replace(b, '')
    return out


# ① CHANGELOG 的最新段
ch = read_lines(CH)
if ch is None:
    problems.append('docs/CHANGELOG.md 不存在')
else:
    idx = [i for i, l in enumerate(ch) if l.startswith('## ')]
    if len(idx) < 2:
        problems.append('docs/CHANGELOG.md 里找不到两个二级标题（新段头没被插好）')
    else:
        for off, line in enumerate(ch[idx[0]:idx[1]]):
            for b in hits(line):
                problems.append('docs/CHANGELOG.md 第 %d 行（最新段）：%s' % (idx[0] + off + 1, b))

# ② AGENTS.md 全文（规则定义处除外）
ag = read_lines(AG)
if ag is None:
    print('说明：AGENTS.md 不在这个副本里（隔离副本没带）⇒ 这一半张漏过')
else:
    for i, line in enumerate(ag):
        if any(m in line for m in DEF_MARKERS):
            continue
        for b in hits(line):
            problems.append('AGENTS.md 第 %d 行：%s' % (i + 1, b))

if problems:
    print('交接位里还有相对称呼 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 交接位里只能出现会话全名（「综合持续迭代」/「UI交互迭代」）')
    sys.exit(1)

print('交接位（CHANGELOG 最新段 + AGENTS.md）里没有相对称呼')
PYEOF
if [ $? -eq 0 ]; then ok "跨会话交接位只用会话全名（不许相对称呼）"; else fail "交接位里出现了相对称呼（读到的人会不知道说的是哪一方）"; cat "$TMP"; fi
rm -f "$TMP"

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ 客户端闸门通过" || echo "❌ 客户端闸门失败"
exit "$FAIL"
