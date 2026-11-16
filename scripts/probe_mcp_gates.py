# -*- coding: utf-8 -*-
"""负向探针：把 MCP 服务改坏 ⇒ 闸门 30/31/32 必须精确报红。

⚠️ 在**隔离副本**里跑（scripts/probe-run.sh）⇒ 共享工作树不受影响。
"""
import pathlib, re

ROOT = pathlib.Path('.')
MCP = ROOT / 'mcp' / 'teslamate_mcp.py'
src = MCP.read_text(encoding='utf-8', errors='replace')

MODE = __import__('os').environ.get('PROBE_MODE', 'entry')

if MODE == 'entry':
    # 探针 A：把入口块挪回**文件中部**（复现 v0.166.0 修掉的那个 bug）
    # ⇒ 后面的工具不再注册 ⇒ 闸门 30 必须报红
    # ⚠️ 判据不能写死行尾（\r\n vs \n）⇒ 直接按**行**找，不写正则。
    _lines = src.split('\n')
    _idx = None
    for _i, _ln in enumerate(_lines):
        if _ln.startswith('if __name__'):
            _idx = _i
            break
    if _idx is None:
        print('probe: 找不到入口块，探针 A 失效')
        raise SystemExit(1)
    _end = _idx + 1
    while _end < len(_lines) and (_lines[_end].startswith('    ') or not _lines[_end].strip()):
        _end += 1
    block = '\n'.join(_lines[_idx:_end]) + '\n'
    _lines = _lines[:_idx] + _lines[_end:]
    src = '\n'.join(_lines)
    # 插到**两个顶层定义之间**（文件中部）⇒ 后面的工具就不会被注册
    # ⚠️ 必须插在**顶层**（缩进 0）⇒ 插到函数体里会造成语法错误，
    #    那样闸门报的是「import 失败」而不是「工具对不上」⇒ 判据不精确。
    _lines = src.split('\n')
    _at = None
    for _i, _ln in enumerate(_lines):
        if _ln.startswith('@mcp.tool(annotations=RO)') and _i + 1 < len(_lines) \
                and _lines[_i + 1].startswith('def teslamate_status'):
            _at = _i
            break
    if _at is None:
        print('probe: 找不到锚点，探针 A 失效')
        raise SystemExit(1)
    _lines = _lines[:_at] + block.rstrip('\n').split('\n') + _lines[_at:]
    src = '\n'.join(_lines)
    MCP.write_text(src, encoding='utf-8', newline='')
    print('probe A: 入口块已挪到文件中部')

elif MODE == 'annot':
    # 探针 B：把车控工具的注解去掉 ⇒ 闸门 31 必须报红
    src = src.replace('@mcp.tool(annotations=DE)', '@mcp.tool()')
    MCP.write_text(src, encoding='utf-8', newline='')
    print('probe B: 车控工具的 destructive 注解已去掉')

elif MODE == 'http':
    # 探针 C：把 HTTP 默认监听地址改成 0.0.0.0 ⇒ 闸门 32 必须报红
    src = src.replace('MCP_HTTP_HOST", "127.0.0.1"', 'MCP_HTTP_HOST", "0.0.0.0"')
    MCP.write_text(src, encoding='utf-8', newline='')
    print('probe C: HTTP 默认监听地址已改成 0.0.0.0')

elif MODE == 'auth':
    # 探针 D：把构造期的鉴权参数去掉 ⇒ 闸门 32 必须报红
    src = src.replace('                token_verifier=verifier,\n', '')
    MCP.write_text(src, encoding='utf-8', newline='')
    print('probe D: 构造期 token_verifier 已被去掉')

else:
    print('未知 PROBE_MODE: %s' % MODE)
    raise SystemExit(2)