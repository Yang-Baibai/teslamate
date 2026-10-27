# -*- coding: utf-8 -*-
"""MCP 服务自检：① 工具直接调 ② stdio 握手 + tools/list + tools/call。"""
import io, json, os, subprocess, sys, time

# ⚠️ **v0.76.0**：Windows 的默认控制台编码是 GBK ——
# 打印「✓」这种符号会直接 **UnicodeEncodeError 崩掉自检**，
# 于是「自检跑不起来」被误当成「服务坏了」。
# ⇒ 强制 stdout 走 UTF-8，并对编不出的字符降级而不是抛异常。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRV = os.path.join(REPO, 'mcp', 'teslamate_mcp.py')

# ── ① 直接调工具（验证 API 通路）──
sys.path.insert(0, os.path.join(REPO, 'mcp'))
import teslamate_mcp as T  # noqa: E402

print('=== ① 工具直调 ===')
for fn, args in [
    ('teslamate_status', {}),
    ('teslamate_battery', {}),
    ('teslamate_command_catalog', {}),
    ('teslamate_stats', {'period': 'week'}),
]:
    try:
        r = getattr(T, fn)(**args)
        s = json.dumps(r, ensure_ascii=False, default=str)
        print('  %-30s -> %s' % (fn, s[:220]))
    except Exception as e:
        print('  %-30s -> EXC %s' % (fn, e))

# ── ② stdio 握手 ──
print('\n=== ② stdio MCP 握手 ===')
p = subprocess.Popen([sys.executable, SRV], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, text=True, encoding='utf-8', bufsize=1)


def send(obj):
    p.stdin.write(json.dumps(obj, ensure_ascii=False) + '\n')
    p.stdin.flush()


def recv(timeout=25):
    t0 = time.time()
    while time.time() - t0 < timeout:
        line = p.stdout.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except json.JSONDecodeError:
            continue
    return None


send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
      "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                 "clientInfo": {"name": "selftest", "version": "1"}}})
init = recv()
if not init:
    print('  ✗ initialize 无响应')
    print(p.stderr.read() if p.poll() is not None else '(stderr 还没输出)')
    p.kill()
    sys.exit(1)
print('  ✓ initialize:', json.dumps(init.get('result', {}), ensure_ascii=False)[:200])
send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})
send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
tl = recv()
tools = (tl or {}).get('result', {}).get('tools', [])
print('  ✓ tools/list: %d 个' % len(tools))
for t in tools:
    print('      - %-28s %s' % (t['name'], (t.get('description') or '').splitlines()[0][:60]))

send({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
      "params": {"name": "teslamate_command_catalog", "arguments": {}}})
tc = recv()
txt = ''
if tc:
    content = tc.get('result', {}).get('content', [])
    txt = content[0].get('text', '') if content else json.dumps(tc, ensure_ascii=False)
print('  ✓ tools/call teslamate_command_catalog -> %s' % txt[:240])

send({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
      "params": {"name": "teslamate_status", "arguments": {}}})
ts = recv()
txt2 = ''
if ts:
    content = ts.get('result', {}).get('content', [])
    txt2 = content[0].get('text', '') if content else json.dumps(ts, ensure_ascii=False)
print('  ✓ tools/call teslamate_status -> %s' % txt2[:240])

# ── ③ Hermes 配置体检 ──
#
# ⚠️ **v0.72.0**：这个检查是被一次真实事故逼出来的 ——
# v0.66.0 加 teslamate MCP 时在 config.yaml 末尾**又写了一个 `mcp_servers:` 顶层键**，
# YAML 里重复的顶层键**后者覆盖前者** ⇒ housekeeper 段的 6 个工具**全部消失**，
# 而 `yaml.safe_load` **不报错**（静默失效）。
# ⇒ 「无缝接入 Hermes」的前提是**配置本身没坏** —— 这一步把它变成可检查的。
print('\n=== ③ Hermes 配置体检 ===')
try:
    import check_hermes_config as H  # noqa: E402

    ok, lines = H.check()
    for ln in lines:
        print(ln)
    print('  %s' % ('✓ 配置体检通过' if ok else '✗ 配置体检有问题（Hermes 里会少工具）'))
except Exception as e:
    print('  ⚠️ 体检跳过：%s' % e)

# ── ④ 工具字段映射自检 ──
#
# ⚠️ **v0.72.0**：工具「能调通」不等于「数据对」。
# 原来 3 个工具的关键字段全是 null（地点/电量/起终点/能耗），
# 而它们**不报错、不崩** —— 人根本看不出来。
# ⇒ 把「关键字段非 null」变成可断言的契约。
print('\n=== ④ 工具字段映射自检 ===')
try:
    import check_tool_fields as F  # noqa: E402

    rc = F.main()
    print('  %s' % ('✓ 字段映射全过' if rc == 0 else '✗ 有字段映射对不上（Hermes 会拿到空数据）'))
except Exception as e:
    print('  ⚠️ 自检跳过：%s' % e)

p.kill()
print('\nDONE')
