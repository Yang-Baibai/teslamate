#!/usr/bin/env bash
# Hermes 链路闸门 —— **服务端 ↔ MCP ↔ 推送脚本** 之间的契约。
#
# ## 为什么单独一个脚本（而不是塞进 client-gates.sh）
#
# · client-gates.sh 管的是**界面文案与几何**（App 侧）；
# · 这条链路的三个端分别是**服务端 / MCP / 推送脚本**（**没有 App**）——
#   塞进 App 的闸门里既不搭，也会和并行的「UI交互迭代」会话抢同一个文件。
#
# ## 十一道闸门
#
#   1. **简报的文案只有服务端一份** ＋ 正文**必带窗口边界**
#      （同一句话写两遍 ⇒ 迟早漂：v0.76.0「完成后是这样」、v0.78.0「充电完成」）
#   2. **MCP 读的服务端键必须在服务端结构体里存在**
#      （防 v0.72.0 同款「工具能调通、返回 JSON，但全是 null」）
#   3. **推送脚本的简报必须节流**
#      （同一槽位不重复推 —— 告警风暴同款，v0.77.0 实测一轮推了 97 次）
#   4. **简报的能耗段与充电段共用同一窗口 / 同一聚合**
#      （一个简报里两个窗口 ⇒ 用户对不上账）
#   5. **简报的电池段与 /battery 同一份 SOH、趋势借同一窗口**
#      （两处各算一次 SOH ⇒ 飞书与 App 报不同的数）
#   6. **能耗/续航口径：客户端与服务端两份实现逐条一致**
#      （分档边界 + 样本过滤各写一遍，只靠注释维持 ⇒ 迟早漂）
#   7. **真实可达续航的常量：客户端与服务端逐条一致**
#      （分档边界 + 样本过滤各写一遍，只靠注释维持 ⇒ 迟早漂）
#   8. **简报的续航段必须复用端点那份算法（同一个数）**
#      （简报自己算一份 ⇒ 飞书里说 171 公里而 App 上是 165 ⇒ 用户以为数据在骗人；
#        且这一段是**当前值** ⇒ 文案必须写「当前」，不许暗示方向）
#   9. **「够不够到」的两端参数契约**（to_km）
#      （「还有 X 公里，够吗」必须由服务端判；MCP 只透传，不许自己减）
#   10. **契约自检函数必须都被调用**（定义 ≠ 生效）
#      （自检没跑 ⇒ 「0 项不合规」是假绿）
#   11. **App 能看到的，Hermes 也要能取**（能力对账）
#      （App 独有的端点要么给 MCP 补工具，要么在例外清单里写清理由）
#  10. **契约自检必须真的在跑**（定义了函数 ≠ 生效）
#      （注册条件写错 ⇒ 那条自检一次都没跑，而自检照样报「0 项不合规」⇒ 假绿）


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
    # ⚠️ 只取**键名**：标签里可能带 ",omitempty" ⇒ 把选项吃进键会误报「键不存在」。
    return set(re.findall(r'json:"([^",]+)"', m.group(1)))


server_tags = set()
for name in ('ChargeBrief', 'ChargeBriefEnergy', 'ChargeBriefBattery', 'ChargeBriefRange', 'ChargeStatsRange', 'ChargeStatsLastFull'):
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

# ── 闸门 24：简报的**能耗段不许自己算窗口**（必须借充电段的区间）──────
#
# ⚠️ **v0.115.1**
#
# 简报现在同时讲**充电**与**能耗**。若两边各算一次窗口
# ⇒ 同一段话里两个口径（「充电是近 30 天、能耗是自然月」）
# ⇒ **用户对不上账**（ADR #38：一个事实只能有一个说法）。
#
# ⇒ 规矩：窗口**只能**在 `api/charge_stats.go` 里算一次
# （`dayWindowStart` / `monthStart`），简报**从区间借**；
# 聚合也只能用**用量页那个函数**（`statsBucketsForWindow`）。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

BRIEF = pathlib.Path('api/charge_brief.go')
STATS = pathlib.Path('api/charge_stats.go')
for p in (BRIEF, STATS):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

brief = BRIEF.read_text(encoding='utf-8', errors='replace')
stats = STATS.read_text(encoding='utf-8', errors='replace')
problems = []

# ⚠️ **先剥掉 Go 的行注释**再查关键字 ——
#    否则「注释里也写了 target.WindowStart」就会让闸门以为代码里还在用
#    ⇒ 探针把代码改坏时闸门照样绿（**假绿**）。
#    （本文件里没有带 "://" 的字符串，所以按行剥注释是安全的。）
brief_code = re.sub(r'(?m)//.*$', '', brief)
stats_code = re.sub(r'(?m)//.*$', '', stats)

# ① 简报里不许出现窗口计算函数（那两处只该在 charge_stats.go 里）。
for fn in ('dayWindowStart(', 'monthStart('):
    if fn in brief_code:
        problems.append('api/charge_brief.go 里出现了 %s ⇒ 简报自己算了一次窗口'
                        '（应当从充电区间借，否则同一段话里两个口径）' % fn)

# ② 简报必须**从区间借**窗口（查的是**代码**，不是注释）。
if 'target.WindowStart' not in brief_code:
    problems.append('api/charge_brief.go 没有用 target.WindowStart ⇒ 能耗段没有借用充电段的窗口')

# ③ 聚合必须走**用量页那个函数**（不许写第二份）。
if 'statsBucketsForWindow(' not in brief_code:
    problems.append('api/charge_brief.go 没有调 statsBucketsForWindow ⇒ 能耗段另算了一份聚合')

# ④ 窗口定义**确实**在 charge_stats.go 里（否则上面三条就都成了空检查）。
if 'dayWindowStart(' not in stats_code or 'monthStart(' not in stats_code:
    problems.append('api/charge_stats.go 里找不到 dayWindowStart / monthStart ⇒ 窗口定义搬家了？')

if problems:
    print('简报的能耗/窗口契约破了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 窗口只在 charge_stats.go 算一次；简报从区间借；聚合走 statsBucketsForWindow')
    sys.exit(1)

print('简报的能耗段：窗口借自充电区间、聚合走用量页同一个函数 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "简报能耗段与充电段共用同一窗口/同一聚合"; else fail "简报的能耗/窗口契约"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 25：简报的**电池段**不许自己算 SOH、不许自己算窗口 ───────
#
# ⚠️ **v0.116.0 补记**
#
# 简报现在还讲**电池健康**，而电池健康有两个坑：
#
#  ① **SOH% 必须走同一份计算**（computeBatteryHealth）
#     —— 不许在简报里另算一套（否则飞书里说 83%，App 上却是 78%）。
#  ② **「当前 SOH%」是窗口无关的**，「窗口内趋势」才是窗口相关的
#     ⇒ 趋势必须**借**充电段的窗口，不许自己再算一个。
#  ③ 趋势只取**严格档**（真的充到 100%）的点 —— 90~98% 的点外推会偏高 0.56%。
#  ④ 文案必须写「当前」 ⇒ 否则窗口无关的数被说成了窗口内的数。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

BRIEF = pathlib.Path('api/charge_brief.go')
if not BRIEF.exists():
    print('找不到 %s —— 这道闸门要跟着改' % BRIEF)
    sys.exit(1)

brief = BRIEF.read_text(encoding='utf-8', errors='replace')

# ⚠️ **先剥掉 Go 的行注释**再查关键字 ——
#    否则「注释里也写了 computeBatteryHealth」就会让闸门以为代码里还在用
#    ⇒ 探针把代码改坏时闸门照样绿（**假绿**）。
brief_code = re.sub(r'(?m)//.*$', '', brief)
problems = []

# ① SOH 必须来自 computeBatteryHealth（不许在简报里另算一套）。
if 'computeBatteryHealth(' not in brief_code:
    problems.append('api/charge_brief.go 没有调 computeBatteryHealth ⇒ 简报的 SOH 是另算的一套')

# ② 电池趋势必须**借**充电段的窗口（起点 + 终点都要）。
#    ⚠️ 匹配的是**调用处**：定义处的参数是 h *BatteryHealth，不会命中。
#    ⚠️ 「全部历史」那一档**故意**传 nil（那一档没有窗口）⇒
#       判据是「**至少有一个**调用借了窗口」，不是「每个调用都借」。
calls = re.findall(r'chargeBriefBatteryFromHealth\(health([^)]*)\)', brief_code)
if not calls:
    problems.append('api/charge_brief.go 里找不到 chargeBriefBatteryFromHealth 的调用 ⇒ 电池段是怎么算的？')
elif not any('target.WindowStart' in a and 'target.WindowEnd' in a for a in calls):
    problems.append('电池趋势段没有借充电段的窗口（要 target.WindowStart + target.WindowEnd）⇒ 同一段话里两个窗口')

# ③ 严格档判据 + 不许在这里另算 SOH。
m = re.search(r'func\s+chargeBriefBatteryFromHealth\s*\([^)]*\)\s*\*ChargeBriefBattery\s*\{(.*?)\n\}', brief_code, re.S)
if not m:
    problems.append('取不到 chargeBriefBatteryFromHealth 的函数体 ⇒ 这道闸门要跟着改')
else:
    fn = m.group(1)
    if 'EndSoc' not in fn or '100' not in fn:
        problems.append('电池趋势段没有「严格档」判据（EndSoc / 100）⇒ 混进了没充满的点')
    if 'NominalCapacityKwh' in fn:
        problems.append('chargeBriefBatteryFromHealth 里出现了 NominalCapacityKwh ⇒ 它在另算 SOH（口径要只有一处）')

# ④ 文案必须写「当前」（窗口无关的数不许被说成窗口内的）。
if '当前' not in brief_code:
    problems.append('简报里没有「当前」二字 ⇒ 窗口无关的 SOH 会被说成窗口内的数')

# ⑤ 电池段**不许用箭头**：箭头 = 趋势符号，而我们**没下趋势结论**
#    （严格档离散度 MAD/中位数 ≈ 0.59% ⇒ 窗口内首末差可能只是测量噪声）
#    ⇒ 正文只报**范围**（min ~ max）。
m3 = re.search(r'func\s+renderBatteryBriefLine\s*\([^)]*\)\s*string\s*\{(.*?)\n\}', brief_code, re.S)
if not m3:
    problems.append('取不到 renderBatteryBriefLine 的函数体 ⇒ 这道闸门要跟着改')
else:
    if '→' in m3.group(1):
        problems.append('电池段里用了箭头（→）⇒ 那会被读成「电池变好了/变差了」，而我们没下这个结论')

if problems:
    print('简报的电池段契约破了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ SOH 走 computeBatteryHealth；趋势借充电段的窗口；只用严格档；文案写明「当前」')
    sys.exit(1)

print('简报的电池段：SOH 同一份计算 + 趋势借同一窗口 + 严格档 + 写明「当前」 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "简报电池段与 /battery 同一份 SOH、趋势借同一窗口"; else fail "简报的电池段契约"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 6：能耗/续航口径 —— Kotlin 与 Go 两份实现必须逐条一致 ─────────
#
# ⚠️ **为什么这道闸门存在**（2026-11-03）：
#
# 「真实可达续航」这条判定的**分档边界 + 样本过滤**，在**两个地方各写了一遍**：
#   · 客户端 `ConsumptionModel.kt`（`EDGES` + `usable` 过滤）
#   · 服务端 `api/climb_calib.go`（`climbSpeedEdges`）+ `api/queries.go`（`sqlClimbCalib` 过滤）
#
# 两边**只靠注释**（「必须与客户端逐条一致」）维持一致 ⇒ **没有任何机器校验**
# ⇒ 谁改了一边，另一边静默跟着漂 ⇒ App 的「真实可达」与服务端 / Hermes 的数各说各话
# （本项目吃过同款亏：「60 分」那句话抄了第二份，错了 57 个版本）。
#
# ⇒ 这道闸门把那句注释变成**机器校验**：逐条比对两份实现的分档边界与样本过滤阈值，
#   并报出**是哪一行**不一样。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

KT    = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ConsumptionModel.kt')
GOED  = pathlib.Path('api/climb_calib.go')
GOSQL = pathlib.Path('api/queries.go')


def load(p):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)
    return p.read_text(encoding='utf-8')


def strip_comments(t):
    # ⚠️ **必须先剥注释再匹配**：改过的旧值都留在注释里（"原来是 20.0" 之类）
    # ⇒ 不剥就可能在**注释里**找到那个数 ⇒ 假绿。
    t = re.sub(r'(?s)/\*.*?\*/', '', t)
    t = re.sub(r'(?m)//.*$', '', t)
    return t


def lineno(t, idx):
    return t.count('\n', 0, idx) + 1


kt    = strip_comments(load(KT))
goed  = strip_comments(load(GOED))
gosql = strip_comments(load(GOSQL))

problems = []
ok_lines = []

# ── 客户端的 `usable` 过滤体 / 服务端的 `sqlClimbCalib` 体 ─────
m_kf = re.search(r'val\s+usable\s*=\s*drives\.filter\s*\{(.*?)\n\s*\}', kt, re.S)
m_gs = re.search(r'const\s+sqlClimbCalib\s*=\s*`(.*?)`', gosql, re.S)

if not m_kf:
    problems.append('客户端取不到 usable 过滤体（ConsumptionModel.kt）⇒ 规则搬走了？')
if not m_gs:
    problems.append('服务端取不到 sqlClimbCalib（queries.go）⇒ 规则搬走了？')

# ── ① 车速分档边界（EDGES ↔ climbSpeedEdges）────────────────────
m_kt_edges = re.search(r'EDGES\s*=\s*listOf\(([^)]*)\)', kt)
m_go_edges = re.search(r'climbSpeedEdges\s*=\s*\[\]int\{([^}]*)\}', goed)


def edges_kt(raw):
    out = []
    for p in [x.strip() for x in raw.split(',') if x.strip()]:
        if 'MAX_VALUE' in p:
            out.append('MAX')
        elif re.match(r'^-?\d+$', p):
            out.append(p)
        else:
            return None
    return out


def edges_go(raw):
    parts = [x.strip() for x in raw.split(',') if x.strip()]
    out = []
    for i, p in enumerate(parts):
        if not re.match(r'^-?\d+$', p):
            return None
        # ⚠️ Go 侧末档用 **0** 表示「开区间」（代码里 `if hi == 0`）⇒ 归一化成 MAX
        out.append('MAX' if (i == len(parts) - 1 and p == '0') else p)
    return out


if not m_kt_edges:
    problems.append('客户端取不到 EDGES（listOf 形式）⇒ 规则搬走了？')
if not m_go_edges:
    problems.append('服务端取不到 climbSpeedEdges（[]int 形式）⇒ 规则搬走了？')
if m_kt_edges and m_go_edges:
    a = edges_kt(m_kt_edges.group(1))
    b = edges_go(m_go_edges.group(1))
    if a is None or b is None:
        problems.append('分档边界解析不出来 ⇒ 这道闸门要跟着改')
    elif a != b:
        problems.append(
            '车速分档边界不一致：客户端 %s（ConsumptionModel.kt:%d） ↔ 服务端 %s（climb_calib.go:%d）'
            % ('/'.join(a), lineno(kt, m_kt_edges.start()),
               '/'.join(b), lineno(goed, m_go_edges.start())))
    else:
        ok_lines.append('分档边界 %s（ConsumptionModel.kt:%d ↔ climb_calib.go:%d）'
                        % ('/'.join(a), lineno(kt, m_kt_edges.start()),
                           lineno(goed, m_go_edges.start())))


# ── ② 样本过滤阈值：最小里程 km / wh 区间 ──────────────────────
def pick(pat, body):
    if body is None:
        return None
    m = re.search(pat, body)
    return m.group(1) if m else None


def rule(name, kt_pat, go_pat, norm=None):
    kv = pick(kt_pat, m_kf.group(1) if m_kf else None)
    gv = pick(go_pat, m_gs.group(1) if m_gs else None)
    if norm:
        kv = norm(kv)
        gv = norm(gv)
    if kv is None:
        problems.append('%s：客户端取不到（ConsumptionModel.kt 的规则搬走了？）' % name)
        return
    if gv is None:
        problems.append('%s：服务端取不到（queries.go 的规则搬走了？）' % name)
        return
    if kv != gv:
        problems.append('%s 不一致：客户端 %s ↔ 服务端 %s' % (name, kv, gv))
    else:
        ok_lines.append('%s = %s（两侧一致）' % (name, kv))


rule('样本过滤·最小里程 km',
     r'km\s*>=\s*([0-9]+(?:\.[0-9]+)?)',
     r'd\.distance\s*>=\s*([0-9]+(?:\.[0-9]+)?)')

rule('样本过滤·wh 区间',
     r'wh\s+in\s+([0-9]+(?:\.[0-9]+)?)\.\.([0-9]+(?:\.[0-9]+)?)',
     r'BETWEEN\s+([0-9]+(?:\.[0-9]+)?)\s+AND\s+([0-9]+(?:\.[0-9]+)?)',
     norm=lambda v: (v.replace('..', ' AND ') if v else v))

if problems:
    print('能耗/续航口径的两份实现已经漂了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 客户端 ConsumptionModel.kt 与服务端 climb_calib.go / queries.go 必须逐条一致')
    sys.exit(1)

for ln in ok_lines:
    print('   ✓', ln)
print('能耗/续航口径：Kotlin ↔ Go 两份实现逐条一致 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "能耗/续航口径：客户端与服务端两份实现逐条一致"; else fail "能耗/续航口径两份实现漂了"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 7：客户端 ↔ 服务端「真实可达续航」实现的常量必须逐条一致 ──
#
# ⚠️ **为什么还要一道**（2026-11-03）：闸门 6 盯的是「客户端 ↔ climb_calib.go」，
#    而**真实可达续航**这条判定搬到了服务端（api/range_estimate.go）
#    ⇒ 那边的温度修正 / EPA Wh/km / 样本门槛 / 残差口径**也必须**与客户端一致
#    ⇒ 否则飞书说 171 公里、App 说 180 公里（两个渠道各说各话）。
#
# ⇒ 这道闸门把「服务端那份是照客户端搬的」从注释变成**机器校验**。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

KT = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/ConsumptionModel.kt')
GO = pathlib.Path('api/range_estimate.go')


def load(p):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)
    return p.read_text(encoding='utf-8')


def strip_comments(t):
    t = re.sub(r'(?s)/\*.*?\*/', '', t)
    t = re.sub(r'(?m)//.*$', '', t)
    return t


kt = strip_comments(load(KT))
go = strip_comments(load(GO))

problems = []
ok_lines = []

# (说明, 客户端正则, 服务端正则)
PAIRS = [
    ('EPA Wh/km（标称能耗）', r'EPA_WH_PER_KM\s*=\s*([0-9.]+)', r'rangeEPAWhPerKm\s*=\s*([0-9.]+)'),
    ('温度修正上限', r'minOf\(([0-9.]+),\s*[0-9.]+\s*\*', r'rangeTempMax\s*=\s*([0-9.]+)'),
    ('温度修正系数', r'minOf\([0-9.]+,\s*([0-9.]+)\s*\*', r'rangeTempCoef\s*=\s*([0-9.]+)'),
    ('温度修正阈值℃', r'maxOf\(0\.0,\s*it\s*-\s*([0-9.]+)\)', r'rangeTempHotC\s*=\s*([0-9.]+)'),
    ('低温不可信线℃', r'outsideTempC\s*<=\s*([0-9.]+)', r'rangeTempLowC\s*=\s*([0-9.]+)'),
    ('低温样本门槛', r'LOW_TEMP_MIN_SAMPLES\s*=\s*([0-9]+)', r'rangeLowTempMinSamples\s*=\s*([0-9]+)'),
    ('每档样本门槛', r'bucket\.samples\s*<\s*([0-9]+)', r'rangeBucketMinSamples\s*=\s*([0-9]+)'),
    ('可用样本门槛', r'usable\.size\s*<\s*([0-9]+)', r'rangeMinUsableSamples\s*=\s*([0-9]+)'),
    ('残差样本的最短里程', r'residuals\s*=\s*usable\s*\.filter\s*\{\s*\(it\.distance_km\s*\?:?\s*0\.0\)\s*>=\s*([0-9.]+)', r'rangeResidualMinKm\s*=\s*([0-9.]+)'),
    ('残差样本条数门槛', r'residuals\.size\s*>=\s*([0-9]+)', r'rangeResidualMinSamples\s*=\s*([0-9]+)'),
    ('日常保底余量', r'reserveFactor:\s*Double\s*=\s*([0-9.]+)', r'rangeDefaultReserve\s*=\s*([0-9.]+)'),
]


def one(pat, text):
    m = re.search(pat, text)
    return m.group(1) if m else None


def all_vals(pat, text):
    return sorted(set(re.findall(pat, text)))


for label, kpat, gpat in PAIRS:
    gv = one(gpat, go)
    if gv is None:
        problems.append('%s：服务端取不到（range_estimate.go 的写法变了？）' % label)
        continue
    # ⚠️ **客户端这边可能有多个同类写法** ⇒ 只取第一个会**假红/假绿**。
    #    实测：客户端有「distance_km >= 30」（巡航筛选）与「>= 100」（残差筛选）两处
    #    ⇒ 判据必须精确到上下文；这里再补一道**自检**：取值不唯一 ⇒ 直接报「判据不精确」。
    kvals = all_vals(kpat, kt)
    if len(kvals) == 0:
        problems.append('%s：客户端取不到（ConsumptionModel.kt 的写法变了？）' % label)
        continue
    if len(kvals) > 1:
        problems.append('%s：客户端有多个取值 %s ⇒ 这条判据不精确（改精确到上下文）'
                        % (label, ' / '.join(kvals)))
        continue
    kv = kvals[0]
    if kv != gv:
        problems.append('%s 不一致：客户端 %s ↔ 服务端 %s' % (label, kv, gv))
    else:
        ok_lines.append('%s = %s' % (label, kv))

# ── 车速分档边界（客户端 EDGES ↔ 服务端 rangeSpeedEdges）──────────
def edges_kt(t):
    m = re.search(r'EDGES\s*=\s*listOf\(([^)]*)\)', t)
    if not m:
        return None
    out = []
    for p in [x.strip() for x in m.group(1).split(',') if x.strip()]:
        if 'MAX_VALUE' in p:
            out.append('MAX')
        elif re.match(r'^-?\d+$', p):
            out.append(p)
        else:
            return None
    return out


def edges_go(t):
    m = re.search(r'rangeSpeedEdges\s*=\s*\[\]int\{([^}]*)\}', t)
    if not m:
        return None
    parts = [x.strip() for x in m.group(1).split(',') if x.strip()]
    out = []
    for i, p in enumerate(parts):
        if not re.match(r'^-?\d+$', p):
            return None
        out.append('MAX' if (i == len(parts) - 1 and p == '0') else p)
    return out


ke = edges_kt(kt)
ge = edges_go(go)
if ke is None:
    problems.append('车速分档边界：客户端取不到 EDGES')
elif ge is None:
    problems.append('车速分档边界：服务端取不到 rangeSpeedEdges')
elif ke != ge:
    problems.append('车速分档边界不一致：客户端 %s ↔ 服务端 %s' % ('/'.join(ke), '/'.join(ge)))
else:
    ok_lines.append('车速分档边界 = %s' % '/'.join(ke))

if problems:
    print('客户端与服务端的「真实可达续航」常量漂了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 服务端那份是照客户端搬的：改了一边就必须改另一边')
    sys.exit(1)

for ln in ok_lines:
    print('   ✓', ln)
print('真实可达续航的常量：客户端 ↔ 服务端逐条一致 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "真实可达续航的常量：客户端与服务端逐条一致"; else fail "真实可达续航的常量漂了"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 8：简报的续航段必须复用端点那份算法（同一个数）────────────
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

GO = pathlib.Path('api/charge_brief.go')
if not GO.exists():
    print('找不到 %s —— 这道闸门要跟着改' % GO)
    sys.exit(1)

go = GO.read_text(encoding='utf-8', errors='replace')
problems = []

# ① 简报**必须**复用 computeRangeEstimate（与 /range_estimate、App 首屏同一个数）
n = go.count('computeRangeEstimate(')
if n != 1:
    problems.append('api/charge_brief.go 里 computeRangeEstimate( 出现 %d 次（应为 1）—— 简报必须复用端点那份算法' % n)

# ② 简报**不许**自己建模型（建模型是端点那份的活）
if 'buildRangeModel(' in go:
    problems.append('api/charge_brief.go 里出现了 buildRangeModel( —— 简报不许自己建模型')

# ③ 续航段的文案：必须有「当前」，不许有箭头
if 'func renderRangeBriefLine' not in go:
    problems.append('api/charge_brief.go 里没有 renderRangeBriefLine —— 续航段的文案要有唯一一份')
else:
    body = go.split('func renderRangeBriefLine', 1)[1].split(chr(10) + 'func ', 1)[0]
    if '当前' not in body:
        problems.append('续航段的文案里没有「当前」二字 ⇒ 窗口无关的当前值会被读成窗口内的量')
    if '→' in body:
        problems.append('续航段的文案里出现了箭头「→」⇒ 那是趋势符号，而我们没下这个结论')

# ④ **接线**：这一段必须真的出现在**正文**与**响应**里
#
# ⚠️ v0.121.0 的教训：上一版只把段喂给渲染器，
#   · 响应字面量 writeJSON(w, &ChargeBrief{...}) 里**没有 Range 字段** ⇒ JSON 里是 null
#     （MCP 读 data["range"] 只能拿到空 ⇒ **静默退化**：用户看不到，也没人报错）；
#   · 正文里**只在「没有充电记录」那个分支**渲染 ⇒ 正常窗口的正文里根本没有这一段。
# ⇒ 下面两条判据就是为了抓住这两半接线。
if 'appendRangeBriefLines(' not in go:
    problems.append('api/charge_brief.go 里没有 appendRangeBriefLines( ⇒ 续航段的接线要跟着改')
else:
    n = go.count('appendRangeBriefLines(lines, seg)')
    if n != 2:
        problems.append('appendRangeBriefLines 只被调用 %d 次（应为 2：两个分支都要渲染）—— 只接一个分支 ⇒ 正常窗口的正文里没有续航段' % n)

wm = go.find('writeJSON(w, &ChargeBrief{')
if wm < 0:
    problems.append('api/charge_brief.go 里找不到 writeJSON(w, &ChargeBrief{...}) ⇒ 这道闸门要跟着改')
else:
    lit = go[wm:wm + 800]
    for field in ('Range:', 'RangeNote:'):
        # ⚠️ 必须**词边界**：'ChargeStatsRange:' 里也含 'Range:' ⇒ 不设边界会假绿
        if not re.search(r'(?<![A-Za-z])' + field, lit):
            problems.append('ChargeBrief 的响应字面量里没有 %s ⇒ JSON 里是 null，MCP 读到空（静默退化）' % field)

if problems:
    print('简报的续航段没复用端点那份算法 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    sys.exit(1)
print('简报的续航段：复用 /range_estimate 的数 + 标明「当前」✔')
PYEOF
if [ $? -eq 0 ]; then ok "简报的续航段复用端点那份算法（同一个数）"; else fail "简报的续航段没复用端点那份算法"; cat "$TMP"; fi
rm -f "$TMP"
# ── 闸门 9：「够不够到」的两端参数契约 ───────────────────────────
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, sys

GO = pathlib.Path('api/range_estimate_http.go')
MCP = pathlib.Path('mcp/teslamate_mcp.py')
for p in (GO, MCP):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)
go = GO.read_text(encoding='utf-8', errors='replace')
mcp = MCP.read_text(encoding='utf-8', errors='replace')
problems = []

# ① 服务端必须接受 to_km（否则飞书问「还有多远」没人判）
if 'to_km' not in go:
    problems.append('服务端的续航端点不接受 to_km ⇒ 「还有 X 公里够吗」没人判')
# ② MCP 必须有 to_km 参数
if 'to_km' not in mcp:
    problems.append('MCP 的续航工具没有 to_km ⇒ 判定到不了模型手上')
# ③ MCP 必须**读**服务端的判定，而不是自己减
if 'reach_ok' not in mcp:
    problems.append('MCP 没有读 reach_ok ⇒ 说明它在别处自己判「够不够到」')
# ④ 契约自检必须**被调用**（定义了函数 ≠ 生效 —— 「绿灯 ≠ 检查到东西」）
CHK = pathlib.Path('mcp/check_tool_fields.py')
chk = CHK.read_text(encoding='utf-8', errors='replace') if CHK.exists() else ''
if 'reach_bad = check_range_reach_contract()' not in chk:
    problems.append('契约自检 check_range_reach_contract 没被调用 ⇒ 那条契约等于没写')

if problems:
    print('「够不够到」的参数契约断了 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    sys.exit(1)
print('「够不够到」：服务端与 MCP 两端都认 to_km，判定只在服务端 ✔')
PYEOF
if [ $? -eq 0 ]; then ok "「够不够到」：to_km 两端都认（判定只在服务端）"; else fail "「够不够到」的参数契约断了"; cat "$TMP"; fi
rm -f "$TMP"



# ── 闸门 10：契约自检**必须真的在跑**（定义了函数 ≠ 生效）────────────────────
#
# ⚠️ **v0.121.0（并行会话踩到的坑）**：它加了一条契约自检函数，
#   但 runner 里的**注册条件写错**（被另一条检查的名字误命中）
#   ⇒ 那条自检**一次都没跑**，而自检依然报「0 项不合规」
#   ⇒ 「MCP 字段自检 0 项不合规」这句话**本身**可能是假绿。
#
# ⇒ 这道闸门是**通用**的（不针对某一条）：
#   「mcp/check_tool_fields.py 里每个 check_* 函数都必须
#     ① 在 main() 里被调用；② 其结果被并进 bad（否则跑了也不改退出码）」。
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

F = pathlib.Path('mcp/check_tool_fields.py')
if not F.exists():
    print('找不到 %s —— 这道闸门要跟着改' % F)
    sys.exit(1)

src = F.read_text(encoding='utf-8', errors='replace')
if 'def main(' not in src:
    print('mcp/check_tool_fields.py 里找不到 main() ⇒ 这道闸门要跟着改')
    sys.exit(1)
main = src.split('def main(', 1)[1]

defs = re.findall(r'^def\s+(check_[A-Za-z0-9_]+)\s*\(', src, re.M)
if not defs:
    print('mcp/check_tool_fields.py 里一个 check_* 函数都没有 ⇒ 这道闸门要跟着改')
    sys.exit(1)

problems = []
for name in defs:
    m = re.search(r'(\w+)\s*=\s*' + name + r'\s*\(', main)
    if not m:
        problems.append('契约自检 %s() 从没在 main() 里被调用 ⇒ 它一次都没跑过' % name)
        continue
    var = m.group(1)
    if not re.search(r'bad\.extend\(\s*' + var + r'\s*\)', main):
        problems.append('契约自检 %s() 的结果（%s）没有并进 bad ⇒ 就算它报了错，退出码仍是 0（假绿）' % (name, var))

if problems:
    print('契约自检形同虚设 %d 处 ——' % len(problems))
    for pr in problems:
        print('   ', pr)
    print('   ⇒ 每个 check_* 都要在 main() 里被调用，且结果并进 bad')
    sys.exit(1)

print('%d 个契约自检全部在 runner 里被调用，且结果并进 bad' % len(defs))
PYEOF
if [ $? -eq 0 ]; then ok "契约自检真的在跑（不是形同虚设）"; else fail "契约自检形同虚设（自检可能一次都没跑）"; cat "$TMP"; fi
rm -f "$TMP"

# ── 闸门 11：App 能看到的，Hermes 也要能取（能力对账）────────────────
TMP=$(mktemp)
"$PY" - > "$TMP" <<'PYEOF'
import pathlib, re, sys

KT = pathlib.Path('app-android/app/src/main/java/com/teslamate/app/Api.kt')
MCP = pathlib.Path('mcp/teslamate_mcp.py')
for p in (KT, MCP):
    if not p.exists():
        print('找不到 %s —— 这道闸门要跟着改' % p)
        sys.exit(1)

kt = KT.read_text(encoding='utf-8', errors='replace')
mcp = MCP.read_text(encoding='utf-8', errors='replace')


def norm(p):
    """端点归一化：抹掉查询串与变量段 ⇒ 只留**路径形状**。"""
    p = p.split('?')[0].rstrip('/')
    p = re.sub(r'\$\{?[A-Za-z_][A-Za-z0-9_]*\}?', '*', p)
    p = re.sub(r'\{[^}]*\}', '*', p)
    p = re.sub(r'%[a-z]', '*', p)
    # 尾部可能多出一个变量段（例：route_profile{q}）⇒ 两侧同规则去掉。
    p = p.rstrip('*')
    return p


def endpoints(text):
    """扫出文件里所有 **/api/v1/** 端点字面量（剥掉整行注释）。

    ⚠️ 为什么扫**全文件**而不是只扫 _get(...)：
    URL 常常先存进变量再拼查询串（例：url = f"/api/v1/cars/{CAR_ID}/range_estimate"）
    ⇒ 只扫调用点会**漏判成「Hermes 没有」** ⇒ 闸门假红（判据不精确）。
    """
    out = set()
    for line in text.split(chr(10)):
        s = line.strip()
        if s.startswith('#') or s.startswith('//'):
            continue
        for m in re.finditer(r'["\']([^"\']*/api/v1/[^"\']*)["\']', line):
            out.add(norm(m.group(1)))
    return out


app_eps = endpoints(kt)
mcp_eps = endpoints(mcp)

# 例外：App 专用 / 运维自检 / 图片 / 原始采样点 —— 每条**必须写理由**
EXCEPTIONS = {
    '/api/v1/cars': '单用户单车：Hermes 侧固定车号，车列表对模型没有意义',
    '/api/v1/app/latest': 'App 的更新自检，模型不需要',
    '/api/v1/app/staticmap': '地图图片（不是数据）；Hermes 侧给坐标与地点',
    '/api/v1/health': '运维自检，模型不需要',
    '/api/v1/feedback/*/image': 'App 上传截图；Hermes 侧不传图',
    '/api/v1/drives/*/track': '原始轨迹采样点（上百个）；Hermes 给可读结论（soc_profile / energy_profile）',
}

missing = []
for ep in sorted(app_eps):
    if ep in mcp_eps:
        continue
    if ep in EXCEPTIONS:
        continue
    missing.append(ep)

if missing:
    print('App 能取、但 Hermes 侧没有对应入口的端点 %d 个 ——' % len(missing))
    for ep in missing:
        print('   ', ep)
    print('   ⇒ 使命 §2.1：App 能看到的，Hermes 也要能取')
    print('      （要么给 MCP 补一个工具，要么在 EXCEPTIONS 里写清理由）')
    sys.exit(1)
print('App ↔ Hermes 能力对账：%d 个端点全覆盖 ✔' % len(app_eps))
PYEOF
if [ $? -eq 0 ]; then ok "App 能看到的，Hermes 也能取（能力对账）"; else fail "App 能取、但 Hermes 取不到的端点"; cat "$TMP"; fi
rm -f "$TMP"


echo "══ 结果：$PASS 过 / $FAIL 败 ══"
[ "$FAIL" -eq 0 ] && echo "✅ Hermes 链路闸门通过" || echo "❌ Hermes 链路闸门失败"
exit "$FAIL"
