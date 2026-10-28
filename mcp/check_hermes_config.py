# -*- coding: utf-8 -*-
"""
体检 Hermes 的 `config.yaml` —— 专治「YAML 重复顶层键」这种**静默**失效。

## 为什么要有这个检查（一次真实事故）

v0.66.0 把 teslamate 的 MCP 注册进 Hermes 时，我在配置文件**末尾又写了一个
`mcp_servers:` 顶层键**。而 YAML 里**重复的顶层键是后者覆盖前者**：

```yaml
mcp_servers:          # ← 第 1 个
  housekeeper: {...}
...
mcp_servers:          # ← 第 2 个：把上面整段顶掉
  teslamate: {...}
```

⇒ `yaml.safe_load` **不报错**（它默默用后一个值），
⇒ Hermes 里 Housekeeper 的 **6 个工具全部消失**（衣物检索 / 临期 / 车辆状态 /
相册地点 / 今日事件），而 teslamate 一切正常 —— **没人会发现**。

⇒ 重复顶层键必须被**当成错误**，而不是「后一个赢」。

## 检查项

1. 配置文件存在
2. **没有重复的顶层键**（用会报错的 loader，而不是 `safe_load`）
3. `mcp_servers` 存在且是映射
4. `teslamate` 在 `mcp_servers` 里
5. `teslamate` 指向的 MCP 脚本**真实存在**
6. 列出全部已注册的 server（顺带看有没有别的被顶掉的）

用法：`python mcp/check_hermes_config.py`（退出码 0 = 通过）
"""
from __future__ import annotations

import os
import sys

# ⚠️ **v0.79.0 3/3 修掉的真 bug**：这台机器的控制台编码是 GBK，
# 而 ✅ / ❌ / ⇒ **全都不在 GBK 里** ⇒ `print()` 直接抛 UnicodeEncodeError。
#
# ⇒ 后果不是「显示不好看」，而是**体检脚本自己崩在最后一行** ——
# 调用方只看到「检查器挂了」，根本分不清「配置有问题」和「检查器坏了」。
# 这和「闸门自己崩了 ≠ 通过」是同一个坑。
#
# ⇒ 做法和 `check_tool_fields.py` 一致：把 stdout/stderr 强制成 UTF-8，
# 用 `errors="replace"` 兜底（万一还有别的字符不在 UTF-8 里，也不至于抛异常）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python 没有这个 API
    pass

try:
    import yaml
except ImportError:  # pragma: no cover
    print("需要 PyYAML：pip install pyyaml")
    sys.exit(2)

DEFAULT_CONFIG = os.path.join(
    os.environ.get("LOCALAPPDATA", os.path.expanduser("~")),
    "hermes", "config.yaml",
)


class UniqueKeyLoader(yaml.SafeLoader):
    """重复的顶层键 ⇒ 抛错，而不是「后一个赢」。"""


def _construct_unique_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping", node.start_mark,
                "found duplicate key %r" % (key,), key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping
)


def check(config_path: str = DEFAULT_CONFIG) -> tuple[bool, list[str]]:
    lines: list[str] = []
    ok = True

    if not os.path.exists(config_path):
        return False, ["❌ 找不到 Hermes 配置文件：%s" % config_path]

    with open(config_path, encoding="utf-8") as f:
        try:
            data = yaml.load(f, Loader=UniqueKeyLoader)
        except yaml.constructor.ConstructorError as e:
            return False, ["❌ 重复的顶层键：%s" % str(e).splitlines()[0]]

    if not isinstance(data, dict):
        return False, ["❌ 顶层不是映射（配置损坏）"]

    servers = data.get("mcp_servers")
    if not isinstance(servers, dict):
        return False, ["❌ 没有 `mcp_servers` 段（或类型不对）"]

    lines.append("  已注册的 MCP server（%d 个）：" % len(servers))
    for name, cfg in servers.items():
        cfg = cfg or {}
        args = cfg.get("args") or []
        inc = (cfg.get("tools") or {}).get("include")
        lines.append("    - %-14s %s%s" % (
            name,
            os.path.basename(str(args[0])) if args else "(无脚本)",
            "  工具白名单 %d 个" % len(inc) if inc else "",
        ))

    if "teslamate" not in servers:
        ok = False
        lines.append("  ❌ `mcp_servers` 里没有 `teslamate` —— Hermes 看不到本项目")
    else:
        args = (servers["teslamate"] or {}).get("args") or []
        if not args:
            ok = False
            lines.append("  ❌ teslamate 没有配 args（脚本路径）")
        else:
            script = str(args[0])
            if not os.path.exists(script):
                ok = False
                lines.append("  ❌ teslamate 指向的脚本不存在：%s" % script)
            else:
                lines.append("  ✅ teslamate 脚本存在：%s" % script)

    return ok, lines


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CONFIG
    print("=== Hermes 配置体检：%s ===" % path)
    ok, lines = check(path)
    for ln in lines:
        print(ln)
    print("  ⇒ %s" % ("通过" if ok else "有问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
