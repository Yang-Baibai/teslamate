# -*- coding: utf-8 -*-
"""MCP 服务自检：① 工具直接调 ② stdio 握手 + tools/list + tools/call。"""
import io, json, os, subprocess, sys, time

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

p.kill()
print('\nDONE')
