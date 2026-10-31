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

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ 客户端闸门通过" || echo "❌ 客户端闸门失败"
exit "$FAIL"
