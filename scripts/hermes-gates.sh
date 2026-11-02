#!/usr/bin/env bash
# Hermes 链路闸门 —— **服务端 ↔ MCP ↔ 推送脚本** 之间的契约。
#
# ## 为什么单独一个脚本（而不是塞进 client-gates.sh）
#
# · client-gates.sh 管的是**界面文案与几何**（App 侧）；
# · 这条链路的三个端分别是**服务端 / MCP / 推送脚本**（**没有 App**）——
#   塞进 App 的闸门里既不搭，也会和并行的「UI交互迭代」会话抢同一个文件。
#
# ## 三道闸门
#
#   1. **简报的文案只有服务端一份** ＋ 正文**必带窗口边界**
#      （同一句话写两遍 ⇒ 迟早漂：v0.76.0「完成后是这样」、v0.78.0「充电完成」）
#   2. **MCP 读的服务端键必须在服务端结构体里存在**
#      （防 v0.72.0 同款「工具能调通、返回 JSON，但全是 null」）
#   3. **推送脚本的简报必须节流**
#      （同一槽位不重复推 —— 告警风暴同款，v0.77.0 实测一轮推了 97 次）
#
# 用法：bash scripts/hermes-gates.sh      （在仓库根）
set -uo pipefail
cd "$(dirname "$0")/.."

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✓ $1"; }
fail() { FAIL=$((FAIL+1)); echo "  ✗ $1"; }

PY=$(command -v python || command -v python3 || true)
export PYTHONIOENCODING=utf-8
if [ -z "$PY" ]; then
  echo "  ✗ 找不到 python，跳过 Hermes 闸门"
  exit 1
fi

echo "══ Hermes 链路闸门 ══"

# ── 闸门 1：简报文案只留一处 + 正文必带窗口边界 ──────────────────
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

GO  = pathlib.Path('api/charge_brief.go')
PS  = pathlib.Path('scripts/watch-car.ps1')
MCP = pathlib.Path('mcp/teslamate_mcp.py')
for p in (GO, PS, MCP):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

go  = GO.read_text(encoding='utf-8', errors='replace')
ps  = PS.read_text(encoding='utf-8', errors='replace')
mcp = MCP.read_text(encoding='utf-8', errors='replace')

problems = []


def strip_py_code(src):
    """去掉 docstring 与 # 注释行 —— 剩下的才是**会执行的**代码。

    ⚠️ 为什么必须剥掉：工具的 description 里本来就要写「充电简报」这几个字
    （那是给人看的说明，不是文案来源）⇒ 不剥必然假红。
    """
    src = re.sub(r'"""[\s\S]*?"""', '', src)
    src = re.sub(r"'''[\s\S]*?'''", '', src)
    return "\n".join(ln for ln in src.split("\n") if not ln.lstrip().startswith('#'))


def strip_ps_code(src):
    """去掉 # 注释行（PowerShell 的注释）。"""
    return "\n".join(ln for ln in src.split("\n") if not ln.lstrip().startswith('#'))


# ① 服务端必须真的渲染简报，且正文里带窗口两端 + 上次充满。
m = re.search(r'func\s+renderChargeBrief\s*\(', go)
if not m:
    problems.append('api/charge_brief.go 里找不到 renderChargeBrief（简报渲染器改名了？）')
else:
    i = go.find('\n}', m.end())
    body = go[m.end():i] if i > 0 else go[m.end():]
    # ⚠️ **关联检查**：窗口起点与终点必须**都**出现 ——
    #    只查「有没有出现过 WindowStart」会放过「只写了起点」的半截实现。
    for name, why in (('WindowStart', '窗口起点'), ('WindowEnd', '窗口终点'), ('LastFull', '上次充满')):
        if not re.search(name, body):
            problems.append('简报正文里没有%s ⇒ 用户没法核对「说的是哪段时间」' % why)

# ② 推送脚本：标题与正文**必须来自服务端响应**，且不许自己写简报文案。
ps_code = strip_ps_code(ps)
if 'charge_brief' not in ps_code:
    problems.append('watch-car.ps1 没有调用 charge_brief 端点 ⇒ 简报不是从服务端来的')
if not re.search(r"Get-Prop\s+\$brief\s+'title'", ps_code):
    problems.append('watch-car.ps1 没有从服务端响应里取 title ⇒ 标题可能自己写的')
if not re.search(r"Get-Prop\s+\$brief\s+'body'", ps_code):
    problems.append('watch-car.ps1 没有从服务端响应里取 body ⇒ 正文可能自己写的')
# ⚠️ 判据用**服务端独有的措辞标记**，不用「充电简报」这个词 ——
#    脚本的**日志**与 MCP 的**报错**里本来就会出现这几个字
#    （那是给人看的说明，不是简报文案）⇒ 用这个词会假红（第一版就这么翻车了）。
for marker, why in (('📊', '标题图标'), ('· 充入', '正文里的充入那一行'), ('（快充）', '直流那行的措辞')):
    if marker in ps_code:
        problems.append('watch-car.ps1 的可执行代码里出现了服务端简报的%s（%r）⇒ 文案必须只有服务端一份'
                        % (why, marker))

# ③ MCP：同样只转述。
mcp_code = strip_py_code(mcp)
if not re.search(r'\.get\("title"\)', mcp_code):
    problems.append('MCP 没有读服务端的 title ⇒ 简报标题可能自己写的')
if not re.search(r'\.get\("body"\)', mcp_code):
    problems.append('MCP 没有读服务端的 body ⇒ 简报正文可能自己写的')
# ⚠️ 同上：判据是**服务端独有的措辞标记**，不是「充电简报」这个词
#    （MCP 的报错文案「取不到充电简报」里就有它 ⇒ 用这个词必然假红）。
for marker, why in (('📊', '标题图标'), ('· 充入', '正文里的充入那一行'), ('（快充）', '直流那行的措辞')):
    if marker in mcp_code:
        problems.append('MCP 的可执行代码里出现了服务端简报的%s（%r）⇒ 文案必须只有服务端一份'
                        % (why, marker))

if problems:
    print('简报的文案/边界契约破了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 文案与判定只在服务端一处；客户端只转述 title / body；正文必须带窗口边界')
    sys.exit(1)

print('简报：服务端渲染 + 客户端只转述 + 正文带窗口边界 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "简报文案只留一处（且带窗口边界）"; else fail "简报的文案/边界契约"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 2：MCP 简报工具读的**服务端键**必须在服务端结构体里存在 ──────
#
# ⚠️ **精确键名比对**（不能用子串！）：
#    把「title」改成「title_removed」时子串照样命中 ⇒ 假绿。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

MCP    = pathlib.Path('mcp/teslamate_mcp.py')
MODELS = pathlib.Path('api/models.go')
for p in (MCP, MODELS):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

mcp = MCP.read_text(encoding='utf-8', errors='replace')
models = MODELS.read_text(encoding='utf-8', errors='replace')


def go_struct_tags(src, name):
    m = re.search(r'type\s+' + name + r'\s+struct\s*\{(.*?)\n\}', src, re.S)
    if not m:
        return None
    return set(re.findall(r'json:"([^"]+)"', m.group(1)))


server_tags = set()
for name in ('ChargeBrief', 'ChargeStatsRange', 'ChargeStatsLastFull'):
    t = go_struct_tags(models, name)
    if t is None:
        print('服务端找不到 type %s（api/models.go）' % name)
        sys.exit(1)
    server_tags |= t

# 取工具函数体：**先跳参数表**（默认参数里有 = {} 的坑），再取到下一个 @mcp.tool()。
m = re.search(r'def\s+teslamate_charge_brief\s*\(', mcp)
if not m:
    print('MCP 里找不到 teslamate_charge_brief（mcp/teslamate_mcp.py）')
    sys.exit(1)
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

read_keys = set(re.findall(r'\.get\("([^"]+)"\)', body))
missing = sorted(k for k in read_keys if k not in server_tags)
if missing:
    print('MCP 简报工具读了服务端的 %s，但服务端结构体里没有这些键 ⇒ 工具会静默返回 null'
          % '、'.join(missing))
    sys.exit(1)

# 返回里必须带：标题 / 正文 / 窗口两端（精确键名比对）。
om = re.search(r'\n    out\s*=\s*\{(.*?)\n    \}', body, re.S)
if not om:
    print('取不到 teslamate_charge_brief 的 out 字典 ⇒ 这道闸门要跟着改')
    sys.exit(1)
out_keys = set(re.findall(r'"([^"]+)"\s*:', om.group(1)))
for k in ('标题', '正文', '窗口起点', '窗口终点'):
    if k not in out_keys:
        print('MCP 简报的返回里没有「%s」' % k)
        sys.exit(1)

print('MCP 简报工具读的 %d 个服务端键都在结构体里，返回带标题/正文/窗口两端 ✔' % len(read_keys))
PYEOF
if [ $? -eq 0 ]; then ok "MCP 简报工具与服务端的键名对得上"; else fail "MCP 简报工具的键名契约"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 3：推送脚本的简报必须**节流** ──────────────────────────
#
# ⚠️ 这道闸门守的是**告警风暴**（v0.77.0 的洞 ①：旧版越过阈值后每一轮都推，
#    实测一轮推了 97 次 ⇒ 用户直接把通知静音了）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

PS = pathlib.Path('scripts/watch-car.ps1')
if not PS.exists():
    print('找不到 scripts/watch-car.ps1')
    sys.exit(1)
ps = PS.read_text(encoding='utf-8', errors='replace')
problems = []

# 节流三件套：读（状态文件里上次的槽位）/ 比（与本槽位比）/ 写（推成功后记下）。
if 'last_brief_slot' not in ps:
    problems.append('watch-car.ps1 没有 last_brief_slot ⇒ 每 5 分钟都会重推一遍')
if not re.search(r'ne\s+\$slotInfo\.Slot', ps):
    problems.append('watch-car.ps1 没有把「本槽位」与「上次的槽位」比较 ⇒ 没有节流')

saves = [m.start() for m in re.finditer(r'Save-State\s+@\s*\{\s*last_brief_slot', ps)]
i_push = ps.find('if ($pushed)')
if i_push < 0:
    problems.append('watch-car.ps1 里找不到推送成功判断（if ($pushed)）⇒ 失败与成功分不开')
elif not saves:
    problems.append('watch-car.ps1 推完没有记下槽位 ⇒ 下一轮还会再推一遍')
elif not any(s > i_push for s in saves):
    problems.append('watch-car.ps1 在推送成功之前就记了槽位 ⇒ 推送失败会被当成「已告知」（用户永远收不到）')

# ⚠️ **没数据 ⇒ 不推但记槽位**：那一行也必须在（否则每 5 分钟重试一次）。
if '窗口内没有充电记录' not in ps:
    problems.append('watch-car.ps1 没有「窗口内没有充电记录 ⇒ 不推送」的分支')

if problems:
    print('简报节流契约破了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    sys.exit(1)

print('推送脚本的简报带槽位节流 ✔（读 / 比 / 写三件套齐，且失败不记槽位）')
PYEOF
if [ $? -eq 0 ]; then ok "推送脚本的简报带槽位节流"; else fail "简报的节流契约"; cat "$TMP"; fi
rm -f "$TMP"

echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ Hermes 链路闸门通过" || echo "❌ Hermes 链路闸门失败"
exit "$FAIL"
