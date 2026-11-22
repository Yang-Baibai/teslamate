#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TeslaMate MCP 服务 —— 让**任何** MCP 客户端都能查这台车的数据、也能控车。

⚠️ **v0.166.0（这一轮：把 MCP 服务做成「可插拔的通用件」）**。

## 这一轮改了什么（以及为什么）

之前的 MCP 服务**能用**，但离「无缝接入任意 agent」还差四样：

1. **覆盖面**：服务端 39 个端点里 MCP 只直连了 24 个 ⇒
   「车端实时快照」「车辆元信息」「遥测连没连过」这些**取不到**。
   ⇒ 这一轮补了 6 个工具（车端原始数据 / 车端快照 / 车辆身份 /
   遥测连接状态 / 遥测设施状态 / 服务端自检）。
2. **规范注解**（协议 2025-06-18 server/tools）：tool 有 annotations 字段
   （readOnlyHint / destructiveHint / idempotentHint / openWorldHint），
   客户端**靠它决定要不要弹确认框** ⇒ 不标就等于让客户端瞎猜。
   ⇒ 这一轮给**全部 31 个**工具都标了（29 只读 / 2 写 / 1 不可逆）。
3. **server instructions + resources**（协议 lifecycle / server/resources）：
   把本项目的三条红线**写进协议** ⇒ 换任何一个 agent 接入都带着同一份约束。
4. **两种传输**（协议 basic/transports）：以前**只有 stdio** ⇒
   客户端必须能在本机起一个 Python 子进程。
   ⇒ 这一轮加了 **Streamable HTTP**（规范里的另一种标准传输）：
   服务端独立跑一个 HTTP 端点，任意客户端拿 URL 就能连。

⚠️ **判定只在服务端做**（项目红线第 1 条）：本文件**只透传**服务端的结论，
   不自己算任何阈值。工具里的中文是「给人看的标签」，不是第二套判定。

## 怎么接进去

### 方式 A：stdio（本地客户端 —— Hermes 现在用的就是这个）

客户端把本文件**当子进程拉起来**，通过 stdin/stdout 说 JSON-RPC：

    mcp_servers:
      teslamate:
        command: python
        args: ["<仓库路径>/mcp/teslamate_mcp.py"]

⚠️ **令牌不用写进客户端配置** —— 本服务会自己去**同目录的 .env** 找
    API_TOKEN（也在 TESLAMATE_API_TOKEN 环境变量里认）⇒ 少一处密钥副本。

### 方式 B：Streamable HTTP（远程 / 别的机器 / 不想起子进程）

    python mcp/teslamate_mcp.py --transport http --port 8765

客户端配置：

    {
      "mcpServers": {
        "teslamate": {
          "type": "streamable-http",
          "url": "http://127.0.0.1:8765/mcp",
          "headers": { "Authorization": "Bearer <MCP_HTTP_TOKEN>" }
        }
      }
    }

⚠️ **HTTP 模式默认只绑 127.0.0.1**（规范：本地服务默认只绑回环）。
   要让局域网/Tailscale 上的别的机器连 ⇒ 显式加 --host 0.0.0.0，
   **并且必须同时设 MCP_HTTP_TOKEN**（否则拒绝启动 ⇒ fail closed）。

## 车控的安全边界

- 动作只有服务端 api/tesla_command.go 目录里那 **21 个**，这里不新增；
- 参数由**服务端**再校验一遍（这里只是转发）；
- **高危动作**（解锁 / 开合尾门 / 远程启动）标了 destructiveHint，
  客户端应当先向用户确认再调用；
- 车在休眠时命令发不出去（Tesla 回 408）—— 本服务会把它翻译成
  「车在休眠或离线，先唤醒再试」。

## 为什么在 teslamate 里

车主诉求：「在 PC 上提供标准的 MCP 或 CLI 服务，让我电脑上的 Hermes
也可以完整的查看车辆数据和操控车辆。」
并且明确：「**Housekeeper 中的车辆控制解耦掉，正确的应该是车辆控制在此项目中。
Housekeeper 调用这个项目。**」

⇒ 车控能力归 **teslamate**，本 MCP 服务只是**它的一层壳** ——
查数据与控车都打同一个 API。

## 运行

    python mcp/teslamate_mcp.py                       # stdio（由 MCP 客户端拉起）
    python mcp/teslamate_mcp.py --transport http      # Streamable HTTP

依赖：只有 mcp 这个 Python 包（pip install mcp），不碰数据库。
"""

from __future__ import annotations

import datetime
import hmac
import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

# ⚠️ MCP SDK 2.0 把 `FastMCP` 改名成了 `MCPServer`（1.x 叫 FastMCP）——
# 两版都兼容，免得换台机器就起不来。
try:  # SDK ≥ 2.0
    from mcp.server import MCPServer as _MCP
except ImportError:  # pragma: no cover - SDK 1.x
    from mcp.server.fastmcp import FastMCP as _MCP

try:  # SDK ≥ 2.0 才有 transport_security
    from mcp.server.transport_security import TransportSecuritySettings as _TransportSecuritySettings
except ImportError:  # pragma: no cover - SDK 1.x
    _TransportSecuritySettings = None  # type: ignore[assignment]

try:  # tool 注解（两版 SDK 都有，类名/字段名要归一）
    from mcp.types import ToolAnnotations as _ToolAnnotations
except ImportError:  # pragma: no cover
    _ToolAnnotations = None  # type: ignore[assignment]

# --------------------------------------------------------------------------
# 配置
# --------------------------------------------------------------------------

_HERE = Path(__file__).resolve().parent
_REPO = _HERE.parent

API_URL = os.environ.get("TESLAMATE_API_URL", "http://localhost:8090").rstrip("/")
CAR_ID = os.environ.get("TESLAMATE_CAR_ID", "1")
TIMEOUT = float(os.environ.get("TESLAMATE_TIMEOUT", "60"))

# ⚠️ **v0.166.0**：HTTP 传输的监听参数。
#
# 默认 **127.0.0.1**（规范：本地服务不许默认绑 0.0.0.0 ——
# 否则同网段任何机器都能开车）。默认端口 8765（避开 API 的 8090）。
HTTP_HOST = os.environ.get("MCP_HTTP_HOST", "127.0.0.1")
HTTP_PORT = int(os.environ.get("MCP_HTTP_PORT", "8765"))
HTTP_PATH = os.environ.get("MCP_HTTP_PATH", "/mcp")


def _read_token() -> str:
    """取 API 令牌：环境变量优先，其次同目录/仓库根的 .env。"""
    tok = os.environ.get("TESLAMATE_API_TOKEN", "").strip()
    if tok:
        return tok
    for cand in (_HERE / ".env", _REPO / ".env"):
        try:
            for line in cand.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("API_TOKEN="):
                    return line.split("=", 1)[1].strip()
        except OSError:
            continue
    return ""


TOKEN = _read_token()



# --------------------------------------------------------------------------
# v0.166.0：MCP 协议三件套（注解 / instructions / resources）
# --------------------------------------------------------------------------
#
# ⚠️ **为什么加这些**（依据：MCP 协议 2025-06-18 官方规范）：
#
# ① **tool annotations**（规范 server/tools「Tool Definition」）——
#    协议里 tool 有 annotations 字段（readOnlyHint / destructiveHint /
#    idempotentHint / openWorldHint），客户端**靠它决定要不要弹确认框**。
#    ⇒ 不标 = 让客户端自己猜 ⇒ 危险动作可能被静默执行。
#
# ② **server instructions**（规范 basic/lifecycle「initialize」响应含
#    instructions 字段）—— 客户端把它当「这个服务该怎么用」的第一提示。
#    ⇒ 正好把本项目的三条红线（判定只在服务端 / 宁可说不知道 / 车控不可逆）
#    **写进协议** ⇒ 换任何一个 agent 接入都带着同一份约束。
#
# ③ **resources**（规范 server/resources）—— URI 寻址的只读上下文。
#    ⇒ 客户端可以「读一份车况快照进上下文」，而不是先调工具再解析。
#    ⚠️ 工具是**模型自己决定**调的（model-controlled），resource 是**宿主应用决定**
#    要不要放进上下文的（application-driven）⇒ 两者互补（规范原文如此）。


def _ann(read_only: bool, destructive: bool = False, idempotent: bool = True,
         open_world: bool = False):
    """造一份 tool 注解（老 SDK 没有就返回 None ⇒ 退回老行为）。

    ⚠️ **注解是给客户端看的**（规范要求客户端把注解当**提示**：
    不可信服务端的不采信；本项目是自用单用户，客户端即车主 ⇒ 可信）：
      · readOnlyHint = true ⇒ 客户端可**免确认**直接调（只读）；
      · destructiveHint = true ⇒ 客户端**应当**弹确认框（不可逆）；
      · idempotentHint = true ⇒ 重复调没额外副作用（锁车是，开锁不是）。
    """
    if _ToolAnnotations is None:  # pragma: no cover - 极老 SDK
        return None
    return _ToolAnnotations(
        read_only_hint=read_only,
        destructive_hint=destructive,
        idempotent_hint=idempotent,
        open_world_hint=open_world,
    )


# ⚠️ 三组常用注解（写出来是为了让每个工具的取值一眼可审）
RO = _ann(read_only=True)                                       # 只读
WR = _ann(read_only=False, destructive=False, idempotent=True)   # 写但不破坏
DE = _ann(read_only=False, destructive=True, idempotent=False)   # 不可逆


# ⚠️ **server instructions**（规范 basic/lifecycle：initialize 响应的 instructions 字段）
SERVER_INSTRUCTIONS = """这是车主**自己那台车**的 MCP 服务（teslamate，单车单用户）。

三条规矩，务必遵守：

1. **判定只在服务端**：凡是带「结论 / 阈值 / 是否正常」的字样，都是服务端算好的，
   **照原文转述，不要自己重算**。你手上没有与服务端同源的数，
   自己减一遍出来的差值一定对不上（同一个事实只能有一个说法）。
2. **宁可说「不知道」，不可编**：读不到就是读不到（值是 null、或文案写着「不知道」）。
   **不要**把「没读到」说成「正常」「关着」「没故障」—— 那是假安全。
3. **车控不可逆**：下发命令前先看 teslamate_command_catalog（参数怎么填、风险等级），
   高危动作（解锁 / 开合尾门 / 远程启动）**先向用户确认**；
   下发后回读「车端结果」—— rejected 绝对不能报成成功。"""


mcp = _MCP("teslamate", instructions=SERVER_INSTRUCTIONS)


# --------------------------------------------------------------------------
# HTTP 小工具
# --------------------------------------------------------------------------


def _request(method: str, path: str, body: dict | None = None) -> Any:
    url = f"{API_URL}{path}"
    data = None
    headers = {"Accept": "application/json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {"error": f"HTTP {e.code}", "detail": raw[:400]}
    except urllib.error.URLError as e:
        return {"error": "连不上 teslamate API", "detail": str(e.reason),
                "hint": f"检查 {API_URL} 是否在跑（docker compose up -d api）"}


def _get(path: str) -> Any:
    return _request("GET", path)


def _tpms_pressure(v: Any) -> Any:
    """实时胎压：四个轮子的形状**都是对象**，统一取 pressure。"""
    if isinstance(v, dict):
        return v.get("pressure")
    return v


# ⚠️ **v0.80.0 / v0.81.0**：实时数据的**来源** → 人话。
#
#   telemetry —— **车主动推**（Fleet Telemetry）。车睡着时也有「断线前的最后已知值」。
#   poll      —— **我们轮询**（Fleet API vehicle_data）。车睡着时拉不到。
#   unknown   —— 两边都没有 ⇒ 真的不知道。
#
# ⚠️ 为什么要说清楚：同一个「已上锁」/「胎压 2.9」，来源不同意味着**新鲜度完全不同**。
# 遥测给的可能是 9 小时前的状态 —— 不标出来就会被当成「现在锁着」。
#
# ⚠️ **v0.81.0**：锁车与胎压现在**共用**这一张表 ——
#    两条路的来源语义完全一样（遥测优先 + 时间诚实），
#    分成两张表只会让「哪个来源」在两处有**两种说法**。
_SOURCE_CN = {
    "telemetry": "车主动推（遥测）",
    "poll": "服务端轮询",
    "unknown": "未知",
    "": "未知",
}


def _source_cn(v: Any) -> str:
    """数据来源 → 人话。认不出来的原样摊开（不编）。"""
    if v is None:
        return "未知"
    return _SOURCE_CN.get(v, str(v))


def _cabin_cn(cabin: Any) -> dict:
    """把服务端的座舱状态翻成中文（**不编**）。

    ⚠️ **v0.88.0**：v0.87.0 让人能**设**空调 / 座椅加热 / 方向盘加热，
    这一轮补上「**读回**」⇒ Hermes 才答得出「座椅加热现在开着吗」。

    ⚠️ **三态字符串是刻意的**：
    「是否开着」给 **开着 / 关着 / 不知道** 三种字符串，
    **绝不给 null** ——
    模型拿到 null 很可能就当成「关着」报出去，那是**假安全**（ADR #25）。
    """
    if not isinstance(cabin, dict):
        return {}

    out = {
        "数据来源": _source_cn(cabin.get("source")),
        "采集于": cabin.get("at"),
        "数据是旧的": bool(cabin.get("stale")),
    }

    # ── 空调 ──────────────────────────────────────────────
    on = cabin.get("climate_on")
    out["空调"] = {
        "是否开着": "不知道" if on is None else ("开着" if on else "关着"),
        "状态": cabin.get("climate_text") or "状态未知",
        # ⚠️ **v0.89.0**：出处与时刻（ADR #24 双源合并）。
        #
        #    空调开关是「遥测 HvacPower ↔ 轮询 is_climate_on」**合并后的一个值**
        #    ⇒ 它与 App 顶层的 `is_climate_on` **永远是同一个**，
        #    两个渠道不会再给出相反的答案。
        #
        #    ⚠️ 报给用户时**要带上 `采集于`**：来源是「服务端轮询」时，
        #    那个值可能是车睡着前的最后已知值（`数据是旧的` = true）。
        "出处": _source_cn(cabin.get("climate_source")),
        "采集于": cabin.get("climate_at"),
        "数据是旧的": bool(cabin.get("climate_stale")),
        # ⚠️ 压缩机（True/False/None）与「整个空调系统」不是一回事 ——
        #    官方把 HvacACEnabled（压缩机）与 HvacPower（系统电源）分成两个字段。
        "压缩机": cabin.get("ac_enabled"),
        "模式": cabin.get("auto_mode") or "",
        "风速": cabin.get("fan_speed"),
        # ⚠️ 官方没给 HvacFanStatus 的范围 ⇒ 只报原始整数，**不编**档位名。
        "送风档位": cabin.get("fan_status"),
        "主驾设定温度℃": cabin.get("driver_temp_c"),
        "副驾设定温度℃": cabin.get("passenger_temp_c"),
    }

    # ── 座椅加热 ──────────────────────────────────────────
    # ⚠️ 只列**读到**的座位（服务端就是这么给的）⇒
    #    字典里没有「主驾」= **没读到主驾**，不是「主驾没开」。
    seats = {}
    for s in cabin.get("seat_heater") or []:
        if isinstance(s, dict) and s.get("seat"):
            seats[s["seat"]] = s.get("text")
    out["座椅加热"] = seats

    # ── 方向盘加热 ────────────────────────────────────────
    wh = cabin.get("steering_wheel_heater") or {}
    out["方向盘加热"] = {
        "档位": wh.get("level"),
        # ⚠️ 官方（中英两站 + proto + vehicle-command）**都没给**这个档位的范围
        #    ⇒ 只报原值，**不编**「关 / 低档 / 中档 / 高档」。
        "说明": wh.get("text") or "",
        "自动": cabin.get("steering_wheel_heater_auto"),
    }
    return out



# --------------------------------------------------------------------------
# resources（规范 server/resources）—— URI 寻址的只读上下文
# --------------------------------------------------------------------------
#
# ⚠️ **为什么只放三份**：resource 是**宿主应用决定**要不要放进上下文的
#    （application-driven，规范原文）⇒ 放进上下文的东西越少越好 ⇒
#    只放「稳定 + 小 + 纯只读」的三份，其余仍走工具（模型自己决定调）。
#
# ⚠️ **口径仍只在服务端**：这三个 resource 读的就是下面那些工具，
#    一样是服务端结论的透传（ADR #38）。


@mcp.resource(
    "teslamate://vehicle/info",
    name="vehicle-info",
    title="这台车是谁（车名 / 型号 / VIN）",
    description="车的身份信息：不随时间变，一次问完就够。",
    mime_type="application/json",
)
def vehicle_info_resource() -> str:
    """车辆身份信息（供客户端直接放进上下文）。"""
    return json.dumps(teslamate_car_info(), ensure_ascii=False, default=str)


@mcp.resource(
    "teslamate://vehicle/status",
    name="vehicle-status",
    title="当前车况快照（电量 / 续航 / 锁车 / 位置）",
    description="车的当前状态快照（服务端口径，含新鲜度标记）。",
    mime_type="application/json",
)
def vehicle_status_resource() -> str:
    """当前车况快照（供客户端直接放进上下文）。"""
    return json.dumps(teslamate_status(), ensure_ascii=False, default=str)


@mcp.resource(
    "teslamate://vehicle/maintenance",
    name="vehicle-maintenance",
    title="保养 / 轮胎提醒（还剩多少公里）",
    description="保养与轮胎基准的服务端结论；没设基准时如实说没设。",
    mime_type="application/json",
)
def vehicle_maintenance_resource() -> str:
    """保养 / 轮胎结论（供客户端直接放进上下文）。"""
    return json.dumps(teslamate_maintenance(), ensure_ascii=False, default=str)

# --------------------------------------------------------------------------
# v0.166.0：补齐「服务端有、MCP 没有」的 6 个端点
# --------------------------------------------------------------------------
#
# ⚠️ **为什么补这些**：这一轮做「能力对账」时发现，服务端 39 个端点里
#    MCP 只直连了 24 个 ⇒ 「车端实时快照」「车辆元信息」「遥测连没连过」
#    这些**取不到** ⇒ 换任何一个 agent 接进来都一样缺。
#
# ⚠️ **口径仍只在服务端**（ADR #38）：下面每个工具**只透传**服务端给的
#    结论与新鲜度标记，不自己算「旧不旧」「正常不正常」。


@mcp.tool(annotations=RO)
def teslamate_vehicle_data() -> dict:
    """查**车端实时原始数据**（Tesla 官方 vehicle_data 那份，Tesla 原样转发）。

    **什么时候用**：用户问「车现在到底什么状态」「Tesla 那边看到什么」
    「把车端的原始数据给我」「我要看 charge_state / climate_state 原值」—— 都归它。

    ⚠️ **这是「原始形态」**：字段名是 Tesla 官方那套（英文），
    **不是**本项目的中文结论 ⇒ 读的时候要自己翻译。
    想要**已经翻译好的结论**，用 teslamate_status / teslamate_telemetry。

    ⚠️ **车睡着时这份数据会变旧**：Tesla 直接回 408，服务端只能给
    **最近一次成功拉到**的那份 ⇒ **必须看 `数据是旧的` 与 `采集于`**：
    · `数据是旧的` = true ⇒ 那是**车睡着前**的值，不是现在的；
    · 报给用户时要说清「这是 X 分钟前的车端数据」。

    ⚠️ `车在休眠，从未拿到过实时数据` ⇒ 那不是接口坏了，
    是**车一直没醒**（开一次车或用官方 App 唤一下就会有）。
    """
    d = _get("/api/v1/cars/%s/vehicle_data" % CAR_ID)
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    resp = d.get("response") or {}
    if not isinstance(resp, dict):
        resp = {}
    return {
        "采集于": d.get("teslamate_fetched_at"),
        "数据是旧的": bool(d.get("stale")),
        "距今秒": d.get("age_sec"),
        "充电状态": resp.get("charge_state"),
        "空调状态": resp.get("climate_state"),
        "行驶状态": resp.get("drive_state"),
        "车辆状态": resp.get("vehicle_state"),
        "说明": "字段名是 Tesla 官方原样（英文），不是本项目的中文结论",
    }


@mcp.tool(annotations=RO)
def teslamate_vehicle_snapshot() -> dict:
    """查**车端快照**（服务端归一化过的那份：充电 / 空调 / 行驶 / 车况 四块）。

    **什么时候用**：用户问「车现在什么状态」「充电到什么程度了」「空调开着吗」
    「车锁了吗（Tesla 那边看到的）」「给我一份车端快照」—— 都归它。

    ⚠️ **与 teslamate_status 的分工**：
    · teslamate_status = 本项目**聚合后的中文结论**（电量 / 续航 / 位置 / 锁车）；
    · teslamate_vehicle_snapshot = **车端原始四块状态**（Tesla 官方字段，英文键）。
    ⇒ 想知道「车现在锁没锁」这类**官方口径**，用这个；
    ⇒ 想知道「现在多少电、还能开多远」，用 teslamate_status。

    ⚠️ 同样**车睡着就旧** ⇒ 看 `采集于`，别当成此刻。
    """
    d = _get("/api/v1/cars/%s/vehicle_snapshot" % CAR_ID)
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    meta = d.get("meta") or {}
    return {
        "采集于": d.get("teslamate_fetched_at"),
        "车名": meta.get("display_name"),
        "车端状态": meta.get("state"),
        "VIN": meta.get("vin"),
        "车辆状态": d.get("vehicle_state"),
        "充电状态": d.get("charge_state"),
        "空调状态": d.get("climate_state"),
        "行驶状态": d.get("drive_state"),
    }


@mcp.tool(annotations=RO)
def teslamate_car_info() -> dict:
    """查这台车**本身是谁**：车名、型号、配置、VIN、轮毂颜色。

    **什么时候用**：用户问「我这车是什么型号」「VIN 是多少」「这车叫什么」
    「我买的是 P74D 还是别的」—— 都归它。

    ⚠️ **这是整车身份信息，不随时间变** ⇒ 一次问完就够，
    不必每次查车况都带一遍。
    """
    cars = _get("/api/v1/cars")
    if isinstance(cars, dict):
        cars = [cars]
    if not isinstance(cars, list) or not cars:
        return {"error": "取不到车辆信息", "原始": str(cars)[:200]}
    mine = None
    for c in cars:
        if isinstance(c, dict) and str(c.get("id")) == str(CAR_ID):
            mine = c
            break
    c = mine or (cars[0] if isinstance(cars[0], dict) else {})
    meta = _get("/api/v1/cars/%s/vehicles" % CAR_ID)
    m0 = None
    if isinstance(meta, dict):
        arr = meta.get("response") or []
        if isinstance(arr, list) and arr and isinstance(arr[0], dict):
            m0 = arr[0]
    m0 = m0 or {}
    return {
        "车号": c.get("id"),
        "车名": m0.get("display_name") or c.get("name"),
        "车机状态": m0.get("state"),
        "型号": c.get("model"),
        "配置": c.get("trim_badging"),
        "市场名": c.get("marketing_name"),
        "VIN": m0.get("vin") or c.get("vin"),
        "车色": c.get("exterior_color"),
        "轮毂": c.get("wheel_type"),
        "电耗系数": c.get("efficiency"),
    }


@mcp.tool(annotations=RO)
def teslamate_telemetry_state() -> dict:
    """查**遥测接得上吗**（车主动推的实时数据那条链路通没通）。

    **什么时候用**：用户问「遥测接上了吗」「为什么车况数据那么旧」
    「故障码能收到吗」「车一直在推数据吗」—— 都归它。

    ⚠️ **分清三件事**（这是本项目反复踩的坑）：
    · `遥测已接入` = **从来没接过** ⇒ 车/证书/服务端任一没配好；
    · `车端此刻在线` = 现在连着 ⇒ 数据是新的；
    · `数据是旧的` = 断线前的最后已知值 ⇒ **不是现在的样子**。

    ⚠️ **「遥测没接上」不等于「车没故障」** —— 没接上就**拿不到故障码**，
    而「拿不到」≠「没有」⇒ 报告时要说「遥测没接上，**故障码拿不到**」，
    **不能说**「车没故障」（红线第 2 条：宁可说不知道，不可编）。
    """
    d = _get("/api/v1/cars/%s/telemetry_state" % CAR_ID)
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    fields = d.get("fields") or []
    return {
        "车端此刻在线": bool(d.get("connected")),
        "遥测已接入": bool(d.get("ever_connected")),
        "最后连上": d.get("last_connected_at"),
        "最后断开": d.get("last_disconnected_at"),
        "遥测字段数": len(fields),
        "遥测字段": [
            {
                "字段": f.get("label") or f.get("field"),
                "值": f.get("text"),
                "采集于": f.get("at"),
                "是旧的": bool(f.get("stale")),
            }
            for f in fields
            if isinstance(f, dict)
        ],
    }


@mcp.tool(annotations=RO)
def teslamate_fleet_telemetry() -> dict:
    """查**Fleet Telemetry（车主动推）这套设施**本身的状态：装没装、配没配对。

    **什么时候用**：用户问「遥测部署了吗」「证书配好了吗」
    「车端固件版本是多少」「为什么收不到故障码」—— 都归它。

    ⚠️ 这是**设施层**的信息（不是车况）⇒ 平时用不上，
    只在排查「遥测为什么不通」时用。

    ⚠️ `可部署` = false 时，先看 `原因`，别自己猜。
    """
    d = _get("/api/v1/cars/%s/fleet_telemetry" % CAR_ID)
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    return {
        "已配置": bool(d.get("configured")),
        "可部署": bool(d.get("can_deploy")),
        "已部署": bool(d.get("deployable")),
        "密钥已配对": bool(d.get("key_paired")),
        "车端固件版本": d.get("firmware_version"),
        "遥测版本": d.get("fleet_telemetry_version"),
        "VIN": d.get("vin"),
        "已同步": bool(d.get("synced")),
        "达上限": bool(d.get("limit_reached")),
        "密钥总数": d.get("total_number_of_keys"),
        "配置摘要": d.get("config_summary"),
        "原因": d.get("reason") or d.get("error"),
    }


@mcp.tool(annotations=RO)
def teslamate_server_health() -> dict:
    """查**服务端自己**的状态（数据库通不通、车端 API 通不通、服务器时间）。

    **什么时候用**：用户问「服务还活着吗」「数据是不是最新的」
    「为什么查不到数据」「接口是不是挂了」—— 都归它。

    ⚠️ **这是自检工具**：查不到车况时**先用它**排除「服务端坏了」，
    再去怀疑车或网络。

    ⚠️ `数据库正常` = false ⇒ 整个系统读不到数据（**所有**工具都会失败）。

    ⚠️ **修掉一个「恒为 false」的假红（2026-11-19）**：原来这里读的是
    `teslamate.ok` / `fleet.ok` —— **服务端 `/api/v1/health` 从来没有这两个字段**
    ⇒ `.get("ok")` 永远拿到 None ⇒ 那两项**恒为 false**（与服务端死活无关：
    服务端好好的也报「采集 / 车端 API 都不正常」）。
    ⇒ 现在只透传服务端**真有的**字段：
    · `车端API状态` = 服务端自己的五态结论（ok / asleep / unauthorized /
      unreachable / disabled）—— **别自己另写一套映射**（红线 #1）。
    · `采集滞后小时` / `最后采集于` = 服务端给的**原值**；新不新鲜由服务端自己
      的口径说（/status 的「车况数据是旧的」）—— 这里不自己算阈值。
    """
    d = _get("/api/v1/health")
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    tm = d.get("teslamate") or {}
    fl = d.get("fleet") or {}
    return {
        "数据库正常": bool(d.get("db_ok")),
        "数据库延迟ms": d.get("db_latency_ms"),
        "车端API状态": fl.get("state"),
        "车端API最近成功": fl.get("last_ok"),
        "车端API错误": fl.get("error") or None,
        "采集滞后小时": tm.get("age_hours"),
        "最后采集于": tm.get("last_position_at"),
        "服务器时间": d.get("server_time"),
        "服务器时区": d.get("server_timezone"),
        "说明": "服务端自检：读不到车况时先排除这里",
    }
# --------------------------------------------------------------------------
# 查数据
# --------------------------------------------------------------------------


@mcp.tool(annotations=RO)
def teslamate_status() -> dict:
    """查这台车**现在**的状态：电量、续航、锁没锁、位置、内外温、实时胎压、车醒没醒、车窗车门哨兵。

    **什么时候用**：用户问「车锁了吗」「还有多少电」「车在哪」「外面多少度」
    「胎压正常吗」「车窗关了吗」「后备箱开着吗」「哨兵开着吗」「车醒着吗」—— 都归它。

    ⚠️ 车在休眠时锁车与胎压会显示「未知」—— 那是**没读到**，不是车没锁。

    ⚠️ **v0.80.0：锁车状态有两个来源，必须一起报**：

    - `锁车数据来源` = **车主动推**（遥测）⇒ 车睡着时也有「断线前的最后已知值」
    - `锁车数据来源` = **轮询**（Fleet API）⇒ 车睡着时拉不到
    - `锁车数据来源` = 未知 ⇒ 两边都没有

    ⚠️ 当来源是遥测、且 `锁车状态是旧的` = true 时，
    那个锁车状态是**车断线那一刻**的（可能已经过了好几个小时）——
    **报给用户时必须带上这个时间**，别当成「现在锁着」。

    ⚠️ **v0.88.0：座舱状态**（`座舱`）—— 空调 / 座椅加热 / 方向盘加热。

    - `座舱.空调.是否开着` 是**三态字符串**：`开着` / `关着` / `不知道`。
      ⚠️ **「不知道」≠「关着」** —— 车没推这批字段、或值认不出来，
      就是「不知道」（ADR #25）。**别把「不知道」说成「关着」。**
    - `座舱.座椅加热` 是字典，**只列读到过的座位**；
      字典里没有「主驾」= 没读到主驾，不是主驾没开。
    - `座舱.方向盘加热.档位` 是**原始整数**：官方（中英两站 + proto +
      vehicle-command）**都没定义**这个档位的范围 ⇒ **不要**自己翻译成
      「关 / 低档 / 中档 / 高档」。
    - `座舱.空调.出处` = 「车主动推（遥测）」/「服务端轮询」/「未知」——
      这是**双源合并后**那个值的出处（ADR #24）。
      ⚠️ 来源是「服务端轮询」时那个值可能是**车睡着前**的，
      ⇒ 报给用户时**要带上 `座舱.空调.采集于`**。
    - ⚠️ `座舱.空调.是否开着` 与 **App 界面上那个空调格子是同一个值**
      （服务端合并后的权威值）⇒ 两边不会给出相反的答案。
    - `座舱.数据是旧的` = true ⇒ 那是车断线前的最后已知值，
      **报给用户时必须带上 `座舱.采集于` 这个时间**。
    - ⚠️ **v0.137.0**：`车内温℃` 为 **None** ⇒ **现在读不到车内温度**
      （车睡着时传感器不报，服务端把「停车那一刻」的旧值收起来了）。
      拿旧值当现在的温度报出去是**假事实** —— 车在外面暴晒一整天时，
      旧值会让人以为「不闷热」（实测：车停了 22 小时，界面仍写 25.8℃）。
      ⇒ 报给用户时要说「现在读不到车内温度（最后一次是 X℃，采集于 Y）」，
      **不要**说成 0℃ /「没有温度数据」。
    - ⚠️ **v0.142.0**：`车外温℃` 为 **None** ⇒ **现在读不到车外温度**
      （车睡着时 positions 里的读数停在**停车那一刻**，服务端把它收起来了）。
      ⚠️ 它与 `车内温℃` 是**同一批**读数 ⇒ 要么两个都有、要么两个都没有。
      ⇒ 报给用户时要说「现在读不到车外温度（最后一次是 X℃，采集于 Y）」，
      **不要**说成 0℃ /「没有温度数据」。
    - ⚠️ **v0.164.0**：天气的结论（`天气风险` / `天气提示` / `天气建议`）是**服务端判的**
      （ADR #79）—— **只有「值得打扰」的天气才会给结论**，常态天气一律不打扰；
      · `天气风险` = none ⇒ 现在没有需要特别留意的天气；
      · 非 none ⇒ 照 `天气提示` 与 `天气建议` 说（那是**服务端渲染**的原文）；
      · ⚠️ 这一组**全是 null** ⇒ 服务端**没取到**（不是「天气没问题」），
        要说「暂时读不到天气」，**不要**替它下结论。
    - ⚠️ **v0.163.0**：「低电量」是**服务端判的**（ADR #78）—— App 的角标、
      飞书那条「停着不动还低电量」提醒、这里**三处同一个口径**。
      · `低电量` / `电量严重偏低` / `停着不动还低电量` = **结论**（照着报）；
      · `低电量阈值%` / `严重偏低阈值%` = **阈值参考**（判定以那三个布尔为准）；
      · ⚠️ **不要自己按电量重算**（那会和服务端漂 —— 这正是本项目反复吃亏的地方）。
      · ⚠️ 这三个结论对应的是**最后一次上报**的时刻 ⇒ `数据滞后小时` 很大时，
        要说清「这是 X 小时前的结论」，别当成此刻。
    """
    s = _get(f"/api/v1/cars/{CAR_ID}/status")
    if not isinstance(s, dict) or "error" in s:
        return s
    return {
        "状态": s.get("state"),
        "车机状态": s.get("fleet_state"),
        "电量%": s.get("battery_level"),
        "可用电量%": s.get("usable_battery_level"),
        "标定续航km": s.get("rated_range_km"),
        "估算续航km": s.get("est_range_km"),
        "里程表km": s.get("odometer_vehicle") or s.get("odometer"),
        # ⚠️ **v0.127.0**：车端里程表的**口径与采集时刻**（服务端给的原文，ADR #41）。
        #   ⚠️ 服务端那个字段原本是**英里**却被当公里 ⇒ 飞书里也会说错；
        #   现在服务端已换算成公里，MCP **只透传**。
        "里程表口径": s.get("odometer_note"),
        "里程表采集于": s.get("odometer_vehicle_at"),
        # ⚠️ **v0.129.0**：座舱闷热（暴晒后车内能到 60℃）——
        # 判定与文案都在服务端，这里**只透传**（ADR #38 / #41）。
        "座舱闷热": (s.get("cabin_heat") or {}).get("text") or None,
        "已锁": s.get("locked"),
        "锁车状态采集于": s.get("locked_at"),
        # ⚠️ 这两个是 **v0.80.0** 新增的 —— 少了它们，
        # 9 小时前的锁车状态会被当成「现在锁着」报出去。
        "锁车数据来源": _source_cn(s.get("locked_source")),
        "锁车状态是旧的": bool(s.get("locked_stale")),
        "位置": s.get("location_name"),
        # ⚠️ **v0.142.0**：车外温度**只在读数是当前时**才有值。
        #   positions.outside_temp 与 inside_temp 是**同一批稀疏行**
        #   （实测非空数完全相同：61709 行，非空率同为 3.32%）⇒ 车睡着后
        #   它停在**停车那一刻**的气温 ⇒ 服务端把它收起来了（置 null）。
        #   ⇒ 这里**只透传**（ADR #38 / #41），不自己算「旧不旧」。
        "车外温℃": s.get("outside_temp"),
        # ⚠️ **v0.142.0**：信息不许丢 —— 上次读到多少、什么时候读到的。
        "车外温度上次读到℃": s.get("outside_temp_last"),
        "车外温度采集于": s.get("outside_temp_at"),
        # ⚠️ **v0.137.0**：车内温度**只在读数是当前时**才有值。
        #   positions.inside_temp 是**车醒着时才更新**的稀疏列 ⇒ 车睡着后
        #   它停在**停车那一刻**的值 ⇒ 服务端把它收起来了（置 null）。
        #   ⇒ 这里**只透传**（ADR #38 / #41），不自己算「旧不旧」。
        "车内温℃": s.get("inside_temp"),
        # ⚠️ **v0.137.0**：信息不许丢 —— 上次读到多少、什么时候读到的。
        "车内温度上次读到℃": s.get("inside_temp_last"),
        "车内温度采集于": s.get("inside_temp_at"),
        "空调开着": s.get("is_climate_on"),
        "胎压bar": {
            # ⚠️ **v0.72.0 修过形状**：四个轮子**全是对象**（含 pressure / alert 等），
            # 原来只对左前做了 `.get("pressure")`，另外三个直接把整个 dict 塞进去了。
            "左前": _tpms_pressure(s.get("tpms_live_fl")),
            "右前": _tpms_pressure(s.get("tpms_live_fr")),
            "左后": _tpms_pressure(s.get("tpms_live_rl")),
            "右后": _tpms_pressure(s.get("tpms_live_rr")),
        },
        "胎压告警": s.get("tpms_live_alert_detail") or s.get("tpms_alert_detail"),
        # ⚠️ **v0.81.0**：实时胎压也有两个来源了（遥测 / 轮询）——
        #    少了这两个，9 小时前的胎压会被当成「刚刚测的」报出去。
        "胎压数据来源": _source_cn(s.get("tpms_source")),
        "胎压采集于": s.get("tpms_live_at"),
        # ⚠️ **v0.82.0**：车窗 / 车门 / 哨兵 —— 「当下安全状态」。
        #
        # 这三项与 `teslamate_telemetry` **口径不同**：
        #   teslamate_telemetry = **纯遥测**（车主动推，含「数据是旧的」标记）
        #   这里的              = **遥测与轮询取更新的那个**（更可能是当下的样子）
        #
        # ⚠️ **三态**：开着哪几扇窗 / 开着哪几个门
        #   null  = **没读到**（不是「都关着」）
        #   []    = 知道，全关
        #   [..]  = 开着这些
        # ⇒ 报给用户时**不能**把 null 说成「都关着」。
        "开着哪几扇窗": s.get("open_windows"),
        "车窗数据来源": _source_cn(s.get("open_windows_source")),
        "开着哪几个门": s.get("open_doors"),
        "车门数据来源": _source_cn(s.get("doors_source")),
        # ⚠️ 哨兵是**归一化状态词 + 中文**：
        #   未开启 / 待命 / 已布防 / 感知中 / 警报触发 / 静默 / 已开启 / 状态未知
        # 「警报触发」是唯一需要**立刻行动**的（红色）。
        "哨兵模式": s.get("sentry_text"),
        "哨兵状态": s.get("sentry_state"),
        "哨兵数据来源": _source_cn(s.get("sentry_source")),
        "哨兵采集于": s.get("sentry_at"),
        # ⚠️ **v0.168.0**：哨兵**警报**是否触发（服务端判定：官方枚举里的 panic）。
        #   true = 有人在动车 / 车被撬 ⇒ 哨兵唯一需要**立刻行动**的状态。
        "哨兵警报": bool(s.get("sentry_alarm")),
        # ⚠️ v0.84.0：固件版本 + 有没有可装的固件。
        # 「待安装版本」空串 = **不知道**（不是「没有更新」）——
        # 所以另给一个布尔「有可装固件」，别让调用方自己比字符串。
        "当前固件": s.get("car_version"),
        "待安装固件": s.get("software_update_version"),
        "有可装固件": bool(s.get("update_pending")),
        # ⚠️ **v0.88.0**：座舱状态（空调 / 座椅加热 / 方向盘加热）。
        #    v0.87.0 让人能「设」，这一轮补上「读回」。
        "座舱": _cabin_cn(s.get("cabin")),
        # ⚠️ **v0.163.0**：「低电量」的**服务端结论**（ADR #78）。
        #   App 的角标、飞书那条「停着不动还低电量」提醒、这里 —— **三处同一个口径**。
        #   ⇒ 这里**只透传**，**不许**自己按电量重算（自己算 = 第二份事实源）。
        "低电量": s.get("low_battery"),
        "电量严重偏低": s.get("low_battery_severe"),
        # ⚠️ 阈值只是**参考**：判定以上面那两个布尔为准（服务端怎么判就怎么报）。
        "低电量阈值%": s.get("low_battery_soc"),
        "严重偏低阈值%": s.get("low_battery_severe_soc"),
        # 「停着不动还低电量」= 低电量 ＋ 车停着（数据 ≥2 小时没更新）＋ 没在跑。
        "停着不动还低电量": s.get("low_battery_parked"),
        # ⚠️ **v0.164.0**：天气的**服务端结论**（ADR #38 / #41 / #79）。
        #   判定在服务端（未来两天有没有**值得打扰**的天气）⇒ 这里**只透传**。
        #   ⚠️ 全是 null ⇒ 服务端**没取到**（不知道）⇒ **不许**说成「天气没问题」。
        "天气风险": (s.get("weather_risk") or {}).get("level"),
        "天气提示": (s.get("weather_risk") or {}).get("what"),
        "天气建议": (s.get("weather_risk") or {}).get("advice"),
        "天气城市": (s.get("weather_risk") or {}).get("city"),
        "数据滞后小时": s.get("data_age_hours"),
        # ⚠️ **v0.136.0**：陈旧阈值**只在服务端**（ADR #38）⇒ 这里给**结论**，
        #    不给模型一个「小时数」让它自己跟某个阈值比。
        #    true ⇒ 这一整份读数是**过去**的，报给用户时要说清有多旧。
        "车况数据是旧的": bool(s.get("data_stale")),
        "最后采集于": s.get("updated_at"),
    }


@mcp.tool(annotations=RO)
def teslamate_battery() -> dict:
    """查**电池健康度**：现在还能装多少度、相对出厂的健康度百分比、出厂折算的满电续航、衰减趋势。

    **什么时候用**：用户问「电池还行吗」「衰减了多少」「还能装多少度」
    「换块新电池能装多少」—— 都归它。

    ⚠️ 「健康度」是**相对出厂可用容量**的百分比（不是「还剩多少」）；
    「近期趋势」是**相对首测**，不是相对出厂 —— 两件事，别混。
    """
    b = _get(f"/api/v1/cars/{CAR_ID}/battery")
    if not isinstance(b, dict) or "error" in b:
        return b
    # ⚠️ **时间诚实性**：「健康度」这个数是**哪一次充电**测的
    # ⇒ 时刻与口径文案都由服务端给，这里**只透传**（ADR #41：文案只有一份）。
    meas = b.get("measurement") or {}
    out = {
        "现在还能装(度)": b.get("current_capacity_kwh"),
        "健康度%(相对出厂可用容量)": b.get("health_percent"),
        "健康度测量于": meas.get("at"),
        "健康度测量口径": meas.get("note"),
        "健康度数据距今(天)": meas.get("age_days"),
        "新电池标称(度)": b.get("nominal_capacity_kwh"),
        # ⚠️ **v0.90.0**：补上「历史最好时」——
        # App 的用量页把这一行折进了「更多明细」（默认看不见），
        # 而使命书要求「UI 折叠掉的数据必须在 Hermes Tools 里 100% 保留」。
        # 取**服务端**的严格档（只取充到 100% 的测量点），
        # **不要**在这里自己从 series 里求最大值 —— 那是另一套口径，
        # 客户端复算必然与服务端漂移（本仓库的老账）。
        "历史最好时(度)": b.get("max_capacity_kwh_strict"),
        "近期趋势%(相对首测)": b.get("health_percent_of_baseline"),
        "首测容量(度)": b.get("baseline_capacity_kwh"),
        "满电标定续航·现在(km)": None,
        "满电标定续航·出厂折算(km)": b.get("factory_rated_range_km"),
    }
    series = b.get("series") or []
    full = [p for p in series if (p.get("end_soc") or 0) >= 100 and p.get("rated_range_km")]
    if full:
        last = max(full, key=lambda p: p.get("date") or "")
        out["满电标定续航·现在(km)"] = last.get("rated_range_km")
        out["最近一次满充"] = last.get("date")
    decay = b.get("decay") or {}
    if decay:
        out["衰减结论"] = decay.get("conclusion")
        # ⚠️ **v0.172.0**：那段说明也透传（服务端唯一一份）——
        # App 的电池健康卡上写着它，飞书这边也该能问到**同一句**
        # （ADR #41：文案只有一份）。
        if decay.get("note"):
            out["说明"] = decay.get("note")
        out["覆盖天数"] = b.get("series_span_days")
        out["覆盖里程km"] = b.get("odometer_span_km")
    return out


@mcp.tool(annotations=RO)
def teslamate_range_estimate(to_km: float = 0) -> dict:
    """查「**真实可达续航**」：按**你自己跑出来的实测能耗**折算的里程、区间与依据。

    **什么时候用**：用户问「我现在真实能跑多远」「还能开多少」「能跑到下一个服务区吗」
    「官方标称 226 公里是真的吗」「开空调会掉多少续航」—— 都归它。

    ⚠️ **别拿官方标定续航当答案**：标定续航（EPA 认证值）是一块**能量表**，不是里程表
    ⇒ 夏季高速用它做规划会乐观 25%~40%。

    ⚠️ 这个数是「**真实可达**」＝ 标定续航 × 146 Wh/km ÷ 实测 Wh/km × 温度修正 × 保底余量
    ⇒ **样本不足时服务端不给数**（宁可不说，也不编一个）。

    ⚠️ **别自己复算**：服务端用的是**同一批行程**（与 App 完全相同的那 500 趟），
    你在这里另算一遍，样本 / 温度 / 窗口都会偏 ⇒ 出来的数一定对不上。

    ⚠️ 分工：电量 / 车机读数 → `teslamate_status`；电池衰减 → `teslamate_battery`。
    ⚠️ **用户说了「还有多远」就带上 `to_km`**（比如「到下一个服务区还有 150 公里」
    ⇒ 传 `to_km=150`）⇒ 服务端会多判一次「够不够到」「差多少公里」
    「到达时约剩多少电」。
    ⇒ **不许自己拿「真实可达」减一下** —— 判定只在服务端（同一条规矩）。

    """
    url = f"/api/v1/cars/{CAR_ID}/range_estimate"
    if to_km and float(to_km) > 0:
        url += "?to_km=%s" % to_km
    d = _get(url)
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}

    band = d.get("true_range_band_km") or {}
    fband = d.get("floor_range_band_km") or {}

    out = {
        "真实可达(km)": d.get("true_range_km"),
        "区间下界(km)": band.get("lo_km"),
        "区间上界(km)": band.get("hi_km"),
        "留20%保底能开(km)": d.get("floor_range_km"),
        "保底区间下界(km)": fband.get("lo_km"),
        "保底区间上界(km)": fband.get("hi_km"),
        "实测能耗(Wh/km)": d.get("wh_per_km"),
        "采用的车速档": d.get("bucket_label"),
        "该档样本数": d.get("bucket_samples"),
        "参考车速(km/h)": d.get("reference_speed_kph"),
        "温度修正": d.get("temp_factor"),
        # ⚠️ **v0.142.0**：车外温度**只在读数是当前时**才有值。
        #   读数陈旧 ⇒ 服务端**不拿它做温度修正**（宁可不做，不可编），
        #   ⇒ 「温度修正」= 1.0 时要看一眼「车外温度读数是旧的」是不是 true。
        "车外温℃": d.get("outside_temp_c"),
        "车外温上次读到℃": d.get("outside_temp_last"),
        "车外温采集于": d.get("outside_temp_at"),
        "车外温度读数是旧的": bool(d.get("outside_temp_stale")),
        "标定续航(km)": d.get("rated_range_km"),
        "当前电量(%)": d.get("battery_level"),
        "模型样本条数": d.get("sample_count"),
        "残差P90(Wh/km)": d.get("residual_p90_wh_per_km"),
        "残差样本条数": d.get("residual_samples"),
        "一句话": d.get("summary"),
        "怎么算的": d.get("basis"),
    }
    # ⚠️ **v0.121.0 补记**：「还有 X 公里，够吗」的判定（服务端给，**不许自己减**）。
    if d.get("to_km") is not None:
        out["目的地(km)"] = d.get("to_km")
        out["够不够到"] = d.get("reach_ok")
        if d.get("reach_shortfall_km") is not None:
            out["还差(km)"] = d.get("reach_shortfall_km")
        out["留20%保底够不够"] = d.get("reach_floor_ok")
        if d.get("reach_floor_shortfall_km") is not None:
            out["留20%保底还差(km)"] = d.get("reach_floor_shortfall_km")
        if d.get("arrive_soc_pct") is not None:
            out["到达时预计剩余电量(%)"] = d.get("arrive_soc_pct")
    if d.get("note"):
        out["注意"] = d["note"]
    if d.get("temp_caveat"):
        out["温度口径"] = d["temp_caveat"]
    return out


@mcp.tool(annotations=RO)
def teslamate_charges(limit: int = 10) -> dict:
    """查**最近的充电记录**：时间、地点、充入多少度、起止电量、费用、峰值/平均功率。

    **什么时候用**：用户问「上次在哪充的」「充了多少度」「充电花了多少钱」
    「这个月充了几次」—— 都归它。

    ⚠️ 要看某一场的功率曲线，用返回的 `id` 接着调 `teslamate_charge_curve`。

    ⚠️ 问「**充满了吗**」「这次算不算完成」—— 用 `teslamate_charge_outcome`，
    那个工具专门给结论（这里只给记录，不给判定）。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/charges")
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        return data if isinstance(data, dict) else {"error": "返回格式异常"}
    rows = []
    for it in items[:max(1, int(limit))]:
        rows.append({
            # ⚠️ id 要留着 —— 它是 teslamate_charge_curve 的入参，
            #    两个工具必须能**接得上**（这才是「无缝」）。
            "id": it.get("id"),
            "开始": it.get("start_date"),
            "结束": it.get("end_date"),
            "地点": it.get("place") or it.get("poi_name") or it.get("place_label"),
            "桩型": it.get("cable_type"),
            "是直流": it.get("is_dc"),
            "充入kWh": it.get("energy_added_kwh"),
            "开始%": it.get("start_soc"),
            "结束%": it.get("end_soc"),
            "时长分钟": it.get("duration_min"),
            "峰值kW": it.get("peak_power_kw"),
            "平均kW": it.get("avg_power_kw_true") or it.get("avg_power_kw"),
            "费用": it.get("cost"),
            "起点续航km": it.get("start_range_km"),
            "结束续航km": it.get("end_range_km"),
        })
    return {"共": len(items), "最近": rows}


# ⚠️ **v0.110.0**：充电统计的**区间**口径也归 Hermes。
#
# 为什么 MCP 要有这个工具：App 里那张卡能切「近 7 天 / 近 30 天 / 全部」，
# 飞书里却问不出来 ⇒ 用户在两个渠道拿到的能力不一样。
# ⇒ 这里读**同一个服务端端点**（`/charge_stats`），**不另算一套**（ADR #38）。
#
# ⚠️ **「本月」是自然月，不是 30 天** —— 两者实测差 25.9%（v0.29.0 记录），
# 用户说「这个月充了多少度」指的是自然月 ⇒ 服务端单列一档 `month`。

# 区间 key → 中文标签（标签只说名字，**真正的边界以服务端给的窗口为准**）。
_CHARGE_RANGE_CN = {
    "7d": "近 7 天",
    "30d": "近 30 天",
    "month": "本月（自然月）",
    "all": "全部历史",
}

# LLM 可能传的中文说法 → 服务端的区间 key。
#
# ⚠️ 这里是**别名**（不是第二份口径）：真正的窗口由服务端给，
# 别名只解决「LLM 传了个近义词」这件事。
_CHARGE_RANGE_ALIAS = {
    "7d": "7d", "7": "7d", "week": "7d", "近7天": "7d", "近 7 天": "7d", "近一周": "7d", "一周": "7d",
    "30d": "30d", "30": "30d", "近30天": "30d", "近 30 天": "30d", "一个月": "30d",
    "month": "month", "本月": "month", "这个月": "month", "自然月": "month", "calendar_month": "month",
    "all": "all", "全部": "all", "全部历史": "all", "历史": "all", "total": "all",
}


def _fmt_tz(ts: Any, tz: str) -> str:
    """把服务端给的 ISO 时刻按**服务器时区**渲染（回答时直接说这个时间）。"""
    if not ts:
        return ""
    try:
        import datetime
        from zoneinfo import ZoneInfo
        dt = datetime.datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
        return dt.astimezone(ZoneInfo(tz)).strftime("%Y-%m-%d %H:%M")
    except Exception:
        # tzdata 缺失 / 时刻格式不认识 ⇒ 退回原始串（**不编时间**）。
        return str(ts)




# ⚠️ **v0.174.0**：实测满电续航 —— Hermes 也能问了。
#
# 为什么加：App 用量页那张「实测满电续航」以前是**客户端自己折算**的
# ⇒ 飞书里问「满电到底能跑多少」问不出来（mission §2.1，
# docs/app-conclusions.json 的第 1 条 gap）。
# ⇒ 现在判定与文案都在**服务端**（/full_charge_range），这里**只透传**
# ⇒ App 与飞书是**同一份**数（ADR #38 / ADR #41）。
#
# ⚠️ **别自己复算**：折算要看「每次充电的起止电量 + 里程表」，
# 还要剔掉物理上不可能的段 —— 这里读服务端那份，别在这里另算一遍。
@mcp.tool(annotations=RO)
def teslamate_full_charge_range() -> dict:
    """查「**实测满电续航**」：这台车满电到底能跑多少公里（实测折算的中位数）。

    **什么时候用**：用户问「满电能跑多少」「满电能跑多远」「这车的真实续航是多少」
    「官方标称 448 公里是真的吗」「实测比官方低多少」—— 都归它。

    ⚠️ **这是折算值，不是某一次满电实跑的公里数**：
    把「从 X% 开到 Y% 跑了多少公里」按掉电比例折算到满电，
    取所有有效段的**中位数** ⇒ 市区与高速工况不同，中位数不代表任何一次具体出行。

    ⚠️ **别拿它当规划依据**：规划请用 `teslamate_range_estimate`（那是「现在能开多少」）。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/full_charge_range")
    if not isinstance(data, dict) or "error" in data:
        return data
    median = data.get("median_km")
    official = data.get("official_km")
    pct = data.get("vs_official_percent")
    out = {
        # ⚠️ **算不出来就是「不知道」** —— 不给 0，也不给半截数。
        "实测满电续航(km)": median,
        "官方标称满电续航(km)": official,
        "实测段数": data.get("segment_count"),
        "依据": data.get("note"),
    }
    if pct is not None:
        # ⚠️ **方向**：正数 = 实测**高**出百分之几；负数 = 实测**低**百分之几。
        #   （旧算法把方向搞反过，报成「实测高 77%」，而真相是「低 23%」——
        #    那是 v0.40.0 的教训，服务端口径已钉住。）
        out["实测比官方标称(%)"] = pct
        out["实测与官方标称的关系"] = ("实测高 %d%%" % pct) if pct >= 0 else ("实测低 %d%%" % (-pct))
    caveat = data.get("caveat")
    if caveat:
        out["为什么没有数"] = caveat
    # ⚠️ **v0.175.0**：三段 ⓘ 文案也透传（服务端唯一一份 —— ADR #41）。
    #
    # ⇒ 飞书里也能问清「这个数怎么算的」「最好/最差怎么读」「口径是什么」，
    # 且与 App 卡片上印的**是同一段字**。
    if data.get("method_note"):
        out["这个数怎么算的"] = data.get("method_note")
    if data.get("read_note"):
        out["最好/最差怎么读"] = data.get("read_note")
    if data.get("scope_note"):
        out["口径"] = data.get("scope_note")
    segs = data.get("segments") or []
    if segs:
        # ⚠️ **段明细**也透传（App 的折叠区与这里是同一份）。
        out["段明细"] = [
            {
                "开始": s.get("start_date"),
                "起始%": s.get("from_soc"),
                "到达%": s.get("to_soc"),
                "实跑km": s.get("km"),
                "折算满电km": s.get("full_charge_km"),
            }
            for s in segs
        ]
    return out
# ⚠️ **v0.181.0**：长途线路（journey）—— Hermes 也能问了。
#
# 为什么加：「哪些算一次长途出门」「沿途有哪些充电站」原来在**客户端**算
# ⇒ 飞书里问「我那条长途线路上次在哪儿充的电」问不出来
#   （mission §2.1；docs/app-conclusions.json 的 route_plan_margin 那条 gap 的第一段）。
# ⇒ 现在判定与阈值都在**服务端**（/route_plans），这里**只透传**
# ⇒ App 与飞书是**同一份**（ADR #38 / #41）。
#
# ⚠️ **别自己复算**：切分要「里程连续 + 回家了」两个判据，归属要同时看里程与时间
# —— 这里读服务端那份，别在这里另算一遍。
@mcp.tool(annotations=RO)
def teslamate_route_plans() -> dict:
    """查**长途线路**：历史上跑过哪些长途（≥200 公里的一次出门），以及沿途在哪儿充的电。

    **什么时候用**：用户问「我上次那条长途是哪个」「那条线路沿途在哪儿充的电」
    「我一共跑过几次长途」「那条 600 公里的线中途停了几站」—— 都归它。

    ⚠️ **一条线路 = 一次连续出门**（不是一趟行程）：充电必然打断行程，
    所以一次长途会被切成好几段 ⇒ 这里按「里程连续 + 回家了」合并回一次出门。

    ⚠️ **到站余量 / 够不够到 20% 保底**不在这里（那仍是 App 的余量表判定）——
    这里给的是**线路清单 + 沿途站归属**。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/route_plans")
    if not isinstance(data, list):
        if isinstance(data, dict):
            return data
        return {"错误": "返回格式异常"}
    out = {
        "线路数": len(data),
        "线路": [
            {
                "key": t.get("key"),
                "从": t.get("from_label"),
                "到": t.get("to_label"),
                "里程(km)": t.get("distance_km"),
                "段数": t.get("leg_count"),
                "总段数": t.get("total_leg_count"),
                "沿途充电次数": t.get("stop_count"),
                "最近一次": t.get("last_driven_at"),
                "出发%": t.get("start_soc"),
                "到达%": t.get("end_soc"),
                "均速(km/h)": t.get("avg_speed_kph"),
                "是往返": t.get("is_round_trip"),
                "沿途站": [
                    {
                        # ⚠️ 站名由**服务端**给（ADR #41：文案只有一份）—— 这里不再拼。
                        "地点": (s.get("charge") or {}).get("name"),
                        "第几公里": s.get("km_along"),
                        "到站%": s.get("arrival_soc"),
                        "插枪%": s.get("soc_before"),
                        "充完%": s.get("soc_after"),
                        "是终点站": s.get("is_endpoint"),
                    }
                    for s in (t.get("stops") or [])
                ],
            }
            for t in data
        ],
    }
    return out
# ⚠️ **v0.182.0**：按站画像 —— Hermes 也能问了。
#
# 为什么加：「这个服务区充到 80% 要多久」「这个桩快不快」原来在**客户端**
# 按地点聚合充电历史算 ⇒ 飞书里问不出来（mission §2.1；
# docs/app-conclusions.json 的 charge_measured_80 / charge_fold_80 两条 gap）。
# ⇒ 现在聚合在**服务端**（/station_stats），这里**只透传**
# ⇒ App 与飞书是**同一份**（ADR #38 / #41）。
#
# ⚠️ **别自己复算**：典型功率、折算耗时、实测样本都要按站聚合
# —— 这里读服务端那份，别在这里另算一遍。
@mcp.tool(annotations=RO)
def teslamate_station_stats() -> dict:
    """查**按站画像**：某个充电桩/服务区到底快不快、我要等多久。

    **什么时候用**：用户问「这个服务区充到 80% 要多久」「这个桩快不快」
    「我在 X 站充过电，那次用了多久」「这个站有没有一次没充到 80%」—— 都归它。

    ⚠️ **按站点聚合**（服务端按真实距离聚类出来的「同一个地方」），
    不是某一场充电 ⇒ 回答「这个站」时别拿单场数据冒充。

    ⚠️ **实测耗时**与**折算耗时**是两个口径：
    - 实测 = 服务端逐点直读那场充电的曲线（准，但只在**直流**场次里）；
    - 折算 = 用该站典型功率反推（所有场次都有，含交流）。

    ⚠️ 站点数多时只列**场次最多**的前若干个 —— 想看别的站再说。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/station_stats")
    if not isinstance(data, dict):
        return {"错误": "返回格式异常"}
    stations = data.get("stations") or []
    out = {
        "站点数": data.get("station_count"),
        "参与统计的充电场次": data.get("sample_count"),
        # ⚠️ 反推的**整包**容量（kWh）—— 折算耗时的分母。
        "实测可用容量(度)": data.get("capacity_kwh"),
    }
    ranked = sorted(
        stations,
        key=lambda s: (-(s.get("sessions") or 0), s.get("place_key") or ""),
    )
    top = ranked[:12]
    if top:
        out["站点"] = [
            {
                "站点": s.get("name"),
                "place_key": s.get("place_key"),
                "场次": s.get("sessions"),
                "典型功率(kW)": s.get("typical_power_kw"),
                "直流典型功率(kW)": s.get("typical_power_dc_kw"),
                "折算充到80%(分钟)": s.get("typical_minutes_to_80"),
                "P90(分钟)": s.get("p90_minutes_to_80"),
                "实测样本数": len(s.get("measured_samples") or []),
                "没充到80%的次数": s.get("dc_never_reached_80"),
                "其中很快离开的次数": s.get("dc_never_reached_80_short"),
            }
            for s in top
        ]
    return out
@mcp.tool(annotations=RO)
def teslamate_charge_stats(period: str = "7d") -> dict:
    """查**充电统计**：按区间给出充了多少度、几次、直交流拆分、平均时长、上次充满。

    **什么时候用**：用户问「这个月充了多少度」「最近 30 天快充几次」「充电花了几次」
    「我一共充过多少度」「这个月充满过几次」「最近多久没充满电了」—— 都归它。

    ⚠️ **区间只有这四个**：`7d`（近 7 天）/ `30d`（近 30 天）/ `month`（**本月 = 自然月**，
    **不是** 30 天）/ `all`（全部历史）。

    ⚠️ **以返回的「窗口起点 / 窗口终点」为准** —— 那是服务端算好的区间边界
    （服务器时区）。回答时把它说出来，别自己另算「是哪 30 天」。

    ⚠️ 分工：最近几场充电的明细 → `teslamate_charges`；
    某一场的功率曲线 → 用它的 `id` 调 `teslamate_charge_curve`。
    """
    key = _CHARGE_RANGE_ALIAS.get(str(period).strip().lower())
    if key is None:
        return {
            "错误": "区间只能是 7d / 30d / month / all",
            "收到": period,
        }
    data = _get(f"/api/v1/cars/{CAR_ID}/charge_stats")
    if not isinstance(data, dict):
        return {"错误": "返回格式异常"}
    tz = data.get("server_timezone") or "Asia/Shanghai"
    rng = next((r for r in (data.get("ranges") or []) if isinstance(r, dict) and r.get("key") == key), None)
    if rng is None:
        return {"错误": "服务端没有这个区间", "区间": key}
    out = {
        "区间": _CHARGE_RANGE_CN.get(key, key),
        # ⚠️ **窗口必须露出来**（v0.110.0）：只说「近 30 天」不说边界，
        # 用户没法核对「是哪 30 天」。
        "窗口起点": _fmt_tz(rng.get("window_start"), tz),
        "窗口终点": _fmt_tz(rng.get("window_end"), tz),
        "服务器时区": tz,
        "场次": rng.get("count"),
        "充入(度)": rng.get("total_kwh"),
        "直流(度)": rng.get("dc_kwh"),
        "直流场次": rng.get("dc_count"),
        "交流(度)": rng.get("ac_kwh"),
        "交流场次": rng.get("ac_count"),
    }
    # 平均时长：**没有样本就是没有**（服务端给 null）⇒ 不拿 0 充数。
    if rng.get("dc_avg_min") is not None:
        out["直流平均(分钟)"] = rng.get("dc_avg_min")
    if rng.get("ac_avg_min") is not None:
        out["交流平均(分钟)"] = rng.get("ac_avg_min")
    lf = rng.get("last_full")
    if isinstance(lf, dict):
        out["上次充满"] = "%s（%s%% → %s%%）" % (
            _fmt_tz(lf.get("date"), tz), lf.get("from_soc"), lf.get("to_soc"))
    else:
        out["上次充满"] = "这个区间里没有充满过"
    return out


@mcp.tool(annotations=RO)
def teslamate_charge_brief(period: str = "7d") -> dict:
    """查**充电简报**：一段可以直接发给用户的短报告（标题 + 正文）+ 底下的结构化数字。

    **什么时候用**：用户要「一份充电简报」「把充电情况总结一下」
    「这周充了多少电，给我个总结」「发个充电周报」—— 都归它。

    ⚠️ **标题与正文都是服务端的原文**（`/charge_brief` 渲染好的）——
    本工具**不自己拼**（同一句话只能有一份，见 ADR #41）。
    ⇒ 要发出去就直接用「标题」+「正文」，**别自己改写数字**。

    ⚠️ **区间只有这四个**：`7d`（近 7 天）/ `30d`（近 30 天）/ `month`
    （**本月 = 自然月**，**不是** 30 天）/ `all`（全部历史）。

    ⚠️ **以「窗口起点 / 窗口终点」为准** —— 只说「近 30 天」不说边界，
    用户没法核对「是哪 30 天」。

    ⚠️ 分工：某一场的功率曲线 → `teslamate_charge_curve`；
    历次明细 → `teslamate_charges`。
    """
    key = _CHARGE_RANGE_ALIAS.get(str(period).strip().lower())
    if key is None:
        return {
            "错误": "区间只能是 7d / 30d / month / all",
            "收到": period,
        }
    data = _get(f"/api/v1/cars/{CAR_ID}/charge_brief?period={key}")
    if not isinstance(data, dict) or "error" in data:
        return {"错误": "取不到充电简报", "详情": data}
    tz = data.get("server_timezone") or "Asia/Shanghai"
    out = {
        # ⚠️ **原样转述**（ADR #41）：标题与正文都是服务端写的。
        "标题": data.get("title") or "",
        "正文": data.get("body") or "",
        "区间": data.get("period_label") or _CHARGE_RANGE_CN.get(key, key),
        "窗口起点": _fmt_tz(data.get("window_start"), tz),
        "窗口终点": _fmt_tz(data.get("window_end"), tz),
        "服务器时区": tz,
        "场次": data.get("count"),
        "充入(度)": data.get("total_kwh"),
        "直流(度)": data.get("dc_kwh"),
        "直流场次": data.get("dc_count"),
        "交流(度)": data.get("ac_kwh"),
        "交流场次": data.get("ac_count"),
    }
    # 平均时长：**没有样本就是没有**（服务端给 null）⇒ 不拿 0 充数。
    if data.get("dc_avg_min") is not None:
        out["直流平均(分钟)"] = data.get("dc_avg_min")
    if data.get("ac_avg_min") is not None:
        out["交流平均(分钟)"] = data.get("ac_avg_min")
    lf = data.get("last_full")
    if isinstance(lf, dict):
        out["上次充满"] = "%s（%s%% → %s%%）" % (
            _fmt_tz(lf.get("date"), tz), lf.get("from_soc"), lf.get("to_soc"))
    else:
        out["上次充满"] = "这个区间里没有充满过"
    # ⚠️ **v0.115.1**：能耗段（里程 / 用电 / 平均能耗）。
    # 它与充电**共用同一个窗口**（服务端只算一次）⇒ 回答时别自己另算窗口。
    energy = data.get("energy")
    if isinstance(energy, dict):
        out["行驶里程km"] = energy.get("km")
        out["行驶时长分钟"] = energy.get("minutes")
        out["用电(度)"] = energy.get("kwh_used")
        out["平均能耗(Wh/km)"] = energy.get("wh_per_km")
        out["平均能耗(kWh/100km)"] = energy.get("kwh_per_100km")
        out["行程数"] = energy.get("drive_count")
    else:
        # 没有能耗段时，服务端给**一句话**说明（如「这一档不并能耗段」）。
        note = data.get("energy_note")
        if note:
            out["能耗段说明"] = note
    # ⚠️ **v0.116.0 补记**：电池健康段。
    #
    # ⚠️ **两个口径，别混**：
    #   · 「电池健康·当前SOH%」= 最近一次满充实测 ÷ 出厂标称容量 ⇒ **与窗口无关**；
    #   · 「电池趋势·…」 = **窗口内**严格档（真的充到 100%）的实测点。
    # ⇒ 回答时**不要**把当前 SOH 说成「近 7 天的 SOH」——
    #   那是一个我们**没算过**的数。
    battery = data.get("battery")
    if isinstance(battery, dict):
        out["电池健康·当前SOH%"] = battery.get("soh_percent")
        out["电池趋势·有窗口"] = battery.get("windowed")
        out["电池趋势·窗口内满充实测次数"] = battery.get("full_count")
        # ⚠️ **只给范围（最小 / 最大），不给「首 → 末」** ——
        # 首末的方向会被读成「电池变好了」，而那不是结论
        #（严格档离散度 ≈ 0.59% ⇒ 首末差可能只是测量噪声）。
        out["电池趋势·窗口内容量最小(度)"] = battery.get("min_kwh")
        out["电池趋势·窗口内容量最大(度)"] = battery.get("max_kwh")
        out["电池趋势·窗口内首次实测"] = battery.get("first_at")
        out["电池趋势·窗口内末次实测"] = battery.get("last_at")
    else:
        note = data.get("battery_note")
        if note:
            out["电池段说明"] = note
    # ⚠️ **v0.119.0 补记**：续航段（**当前值**：与窗口无关）。
    # 数值与服务端 /range_estimate、App 首屏**同一个数** ⇒ 别自己另算。
    rg = data.get("range")
    if isinstance(rg, dict):
        out["续航·当前真实可达(公里)"] = rg.get("true_range_km")
        lo = rg.get("true_range_lo_km")
        hi = rg.get("true_range_hi_km")
        if lo is not None and hi is not None:
            out["续航·当前真实可达区间(公里)"] = "%s ~ %s" % (lo, hi)
        out["续航·当前保底可达(公里)"] = rg.get("floor_range_km")
        out["续航·档位"] = rg.get("bucket_label")
        out["续航·样本条数"] = rg.get("sample_count")
    else:
        note = data.get("range_note")
        if note:
            out["续航段说明"] = note

    return out


# ⚠️ **v0.78.0**：结局的**文案**只有服务端那一份（`verdict` / `detail`）。
#
# 原来这里还写了一份枚举 → 中文说明的映射表（`_OUTCOME_CN`）——
# 那就是**第二份副本**，三处各写一份文案、迟早漂移（v0.76.0 吃过一次亏）。
# 现在改成**直接透传服务端的结论**，本文件只负责转述。
#
# 枚举本身（`charging` / `complete` / …）是**机器可读**的标识，
# 配上一句服务端给的中文结论，模型要答什么都有足够的依据。


@mcp.tool(annotations=RO)
def teslamate_charge_outcome() -> dict:
    """查**最近一次充电的结局**：充满了吗、充到多少、充了多少度、用了多久、为什么没充满。

    **什么时候用**：用户问「刚才那次充满了吗」「充满电了吗」「充到多少了」
    「这次充电算成功吗」「为什么没充满」「我刚拔枪算完成吗」「充电结束了吗」
    —— 都归它。

    ⚠️ **「充电结束」≠「充电完成」**：旧版充电一结束就推「✅ 充电完成」，
    不管它充到哪儿了 —— 那是**假成功**。现在由服务端按
    **「结束电量 vs 那场会话当时的上限」**判定，**别自己猜**。

    ⚠️ 结论**可能是「结束方式判断不了」**：那种情况下**事实照报**
    （充到多少 / 充了多少度 / 用了多久），只是「怎么结束的」还说不准 ——
    **别把「结束」说成「完成」**，也别编「没到上限」。

    ⚠️ 分工：历次充了多少度、在哪充的 → `teslamate_charges`；
    某一场的功率曲线 → `teslamate_charges` 的 `id` 调 `teslamate_charge_curve`。
    """
    s = _get(f"/api/v1/cars/{CAR_ID}/status")
    if not isinstance(s, dict) or "error" in s:
        return s
    oc = s.get("charge_outcome")
    if not isinstance(oc, dict):
        return {
            "结局": "unknown",
            "结论": "这台车还没有过充电记录",
            "提示": "先用 teslamate_charges 看有没有充电记录",
        }
    out = {
        # ⚠️ 枚举 + 服务端给的中文结论，**不改写**。
        "结局": oc.get("outcome") or "unknown",
        "结论": oc.get("verdict") or oc.get("detail"),
        "完整结论": oc.get("detail"),
        "结束电量%": oc.get("end_soc"),
        "充电上限%": oc.get("limit_soc"),
        "充到上限了吗": oc.get("reached_limit"),
        "充入kWh": oc.get("energy_added_kwh"),
        "时长分钟": oc.get("duration_min"),
        "结束于": oc.get("ended_at"),
        "场次id": oc.get("charge_id"),
    }
    # 判据本身也交出来 —— 结论要**可核对**，不能只给一句话。
    if oc.get("last_power_kw") is not None:
        out["收尾功率kW"] = oc.get("last_power_kw")
        out["本场峰值kW"] = oc.get("peak_power_kw")
    return out


@mcp.tool(annotations=RO)
def teslamate_drives(limit: int = 10) -> dict:
    """查**最近的行程记录**：时间、起终点、里程、时长、能耗、到达电量。

    **什么时候用**：用户问「上次开车去哪了」「这趟开了多远」「油耗多少」
    「最近跑了哪些路」—— 都归它。

    ⚠️ 要看某一趟的电量剖面，用返回的 `id` 接着调 `teslamate_route_profile`。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/drives")
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        return data if isinstance(data, dict) else {"error": "返回格式异常"}
    rows = []
    for it in items[:max(1, int(limit))]:
        wh = it.get("wh_per_km")
        rows.append({
            "id": it.get("id"),
            "开始": it.get("start_date"),
            "结束": it.get("end_date"),
            "起点": it.get("start_place"),
            "终点": it.get("end_place"),
            "里程km": it.get("distance_km"),
            "时长分钟": it.get("duration_min"),
            # ⚠️ API 给的是 **Wh/km**，不是 kWh/100km —— 换算要 ÷10。
            "能耗kWh/100km": (round(wh / 10.0, 2) if isinstance(wh, (int, float)) else None),
            "用电kWh": it.get("energy_kwh"),
            "开始%": it.get("start_soc"),
            "到达电量%": it.get("end_soc"),
            "均速kph": it.get("avg_speed_kph"),
            "最高kph": it.get("speed_max"),
            "爬升m": it.get("ascent_m"),
            "车外温℃": it.get("outside_temp_avg"),
        })
    return {"共": len(items), "最近": rows}


@mcp.tool(annotations=RO)
def teslamate_stats(period: str = "week") -> dict:
    """查**一段时间的用量汇总**：里程、行驶耗电、充入电量、行程数、充电次数。

    **什么时候用**：用户问「这周开了多少」「这个月用了多少电」「今天跑没跑」
    —— 都归它。period 取 today / week / month / year。

    ⚠️ 要**钱**（电费）用 `teslamate_cost`；要**电池健康**用 `teslamate_battery`。
    """
    p = (period or "week").strip().lower()
    if p not in ("today", "week", "month", "year"):
        return {"error": f"period 只能是 today/week/month/year，收到 {period!r}"}
    data = _get(f"/api/v1/cars/{CAR_ID}/stats?period={p}")
    if not isinstance(data, dict) or "error" in data:
        return data
    summ = data.get("summary") or {}
    return {
        "周期": p,
        "服务端时区": data.get("server_timezone"),
        "里程km": summ.get("km"),
        # ⚠️ **v0.93.0（顺手修掉的真问题）**：键名是 **`kwh_used`**，不是 `drive_kwh`。
        #
        # 服务端 `/cars/{id}/stats` 的 `summary` 里只有 `kwh_used`（全库 grep：
        # `drive_kwh` **只存在于本文件**，服务端从来没下过这个键）⇒
        # 这一行**一直**是 `None` —— 飞书里问「这周用了多少电」得到的是空值，
        # 而 App 同一屏显示「行驶耗电 15.2 度」⇒ **两边对不上**。
        #
        # 这正是本轮把「行驶耗电」折进「更多明细」时撞上的：
        # App 少显示可以，但 Hermes 侧必须**一条不少**。
        "行驶耗电(度)": summ.get("kwh_used"),
        "充入电量(度)": summ.get("charge_kwh"),
        "行程数": summ.get("drive_count"),
        "充电次数": summ.get("charge_count"),
    }


@mcp.tool(annotations=RO)
def teslamate_driving_profile() -> dict:
    """查**驾驶画像**：平顺分，以及急刹 / 急加 / 能耗 / 夜驾 / 速度档各扣了多少分。

    **什么时候用**：用户问「我开车平顺吗」「急刹多不多」「这个分怎么算的」
    —— 都归它。

    ⚠️ 名字叫「平顺分」，但**只有 70 分是平顺相关**（急刹 30 + 急加 20 + 速度 20），
    另 30 分来自能耗与夜驾 —— 别当成纯粹的平顺度量。

    ⚠️ **v0.102.0**：原来这里的权重写错了，而且与下面「口径」里的公式**自相矛盾** ——
    权威源是服务端 `api/handlers.go` 的 `drivingScore()`：
    急刹 30 + 急加 20 + 速度 20 + 能耗 15 + 夜驾 15 = 100
    ⇒ 平滑相关 70 分、能耗与夜驾 30 分。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/driving_profile")
    if not isinstance(d, dict) or "error" in d:
        return d
    s = d.get("summary") or {}
    return {
        "平顺分(中位·合成指标)": s.get("median_score"),
        "平均分": s.get("avg_score"),
        "趟数": s.get("drive_count"),
        "总里程km": s.get("total_km"),
        "急刹车次数": s.get("hard_brake_total"),
        "急加速次数": s.get("hard_accel_total"),
        "超速档占比%": s.get("over_speed_pct_avg"),
        "能耗Wh/km": s.get("wh_per_km_median_gross"),
        "夜驾占比%": s.get("night_pct_avg"),
        "口径": "分 = 100 − 急刹(≤30) − 急加(≤20) − 速度(≤20) − 能耗(≤15) − 夜驾(≤15)；名字叫「平顺」但只有 70 分是平顺相关",
    }


@mcp.tool(annotations=RO)
def teslamate_stay_points(limit: int = 10) -> dict:
    """查**常驻点**：停得最多、最久的地方（家 / 公司 / 常去的商场）。

    **什么时候用**：用户问「车平时停哪」「最常去哪个地方」「在家待多久」
    —— 都归它。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/stay_points")
    items = (d.get("points") if isinstance(d, dict) else d) or []
    if not isinstance(items, list):
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    rows = [{
        "地点": it.get("name"),
        "停过次数": it.get("visits"),
        "累计小时": it.get("total_hours"),
        "平均小时": it.get("avg_hours"),
        "最长一次小时": it.get("max_hours"),
        "首次": it.get("first_seen"),
        "最近": it.get("last_seen"),
    } for it in items[:max(1, int(limit))]]
    out = {"共": len(items), "地点": rows}
    if isinstance(d, dict) and d.get("note"):
        out["口径"] = d.get("note")
    return out


# --------------------------------------------------------------------------
# v0.72.0：补齐「车主会问、但答不了」的那几问
# --------------------------------------------------------------------------
#
# ⚠️ **v0.72.0（苹果设计总监复审）**：原来 9 个工具只能回答
# 「车怎么样 / 电池怎么样 / 记了什么」——
# 但下面这几个问题是车主（和替车主办事的 Hermes）**真的会问**的，而当时答不了：
#
#   「这个月电费花了多少？」          → teslamate_cost
#   「我开多快最省电？」              → teslamate_energy_profile
#   「这趟的电量是怎么一路掉下去的？」→ teslamate_route_profile
#   「上次充电的功率曲线长什么样？」  → teslamate_charge_curve
#
# ⇒ 「无缝接入 Hermes」不是「把接口都包一层」，
#   而是**把车主会说的话都变成能答**。


@mcp.tool(annotations=RO)
def teslamate_cost(period: str = "week") -> dict:
    """查**充电成本构成**：充了多少度、直流 / 交流 / 家充各占多少、行驶里程。

    **什么时候用**：用户问「这个月电费多少」「充电花了多少」「家充占比多少」
    —— 都归它。period 取 today / week / month / year。

    ⚠️ **金额**要 App「设置 → 电价与成本」里填了电价与服务费才有；
    没填时这里只给用电量 —— 别自己乘个电价当结论。
    """
    p = (period or "week").strip().lower()
    if p not in ("today", "week", "month", "year"):
        return {"error": f"period 只能是 today/week/month/year，收到 {period!r}"}
    d = _get(f"/api/v1/cars/{CAR_ID}/cost_summary?period={p}")
    if not isinstance(d, dict) or "error" in d:
        return d
    return {
        "周期": p,
        "直流(度)": d.get("dc_used_kwh"),
        "直流次数": d.get("dc_sessions"),
        "直流分钟": d.get("dc_minutes"),
        "交流(度)": d.get("ac_used_kwh"),
        "交流次数": d.get("ac_sessions"),
        "家充(度)": d.get("home_used_kwh"),
        "家充次数": d.get("home_sessions"),
        "行驶里程km": d.get("km_drives"),
        "里程表km": d.get("km_odometer"),
        "口径": "费用金额要看 App「设置 → 电价与成本」；没填电价时这里只有电量",
    }


@mcp.tool(annotations=RO)
def teslamate_energy_profile() -> dict:
    # ⚠️ **v0.103.0（第 11 轮 CDO Polish）：这份工具说明原来把两个口径说反了。**
    #
    # 原文写的是「毛耗电**含**回充抵扣」「车轮 Wh/km **不含**回充抵扣」——
    # 而服务端 `api/handlers.go` 的 `EnergyProfile.Note` 写的是
    # 「`wh_per_km` 是**净值**（下坡回充**会**抵扣），
    #   `wh_per_km_gross` 是**毛耗电**（**不**含回充抵扣）」——
    # ⇒ 两边**正好相反**，而且这个工具自己的「口径」字段与它自己下发的
    #   `wh_per_km` / `wh_per_km_gross` 也自相矛盾。
    #
    # ⇒ 修法**不是**把这里改对，而是**删掉自己维护的那一份**：
    #   口径一律引用服务端下发的 `note`（唯一事实源）⇒ 以后服务端改口径，这里自动跟着变。
    # ⇒ 下面 docstring 里只留**两件事**：各字段对应哪个口径、看「最省电」该看哪个。
    """查**能耗-车速曲线**：每个速度档的平均 Wh/km（车轮功率 / 毛耗电）与跑了多少公里。

    **什么时候用**：用户问「我开多快最省电」「高速费电吗」「多少码最经济」
    —— 都归它。看 `毛耗电Wh/km` 最低的那个速度档。

    ⚠️ **两个口径别搞反**：
    - `毛耗电Wh/km`（服务端的 `wh_per_km_gross`）= `max(P,0)` 积分，
      **不抵扣下坡回充** ⇒ 「最省电的速度」**只看它**；
    - `车轮Wh/km`（服务端的 `wh_per_km`）= 梯形积分的**净值**，
      **回充会抵扣** ⇒ 下坡趟次会把它拉低，是假象。

    完整口径见返回值里的「口径」字段（由服务端下发）。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/energy_profile")
    if not isinstance(d, dict) or "error" in d:
        return d
    bands = d.get("bands") or []
    rows = [{
        "速度档kph": f"{b.get('min_kph')}-{b.get('max_kph')}",
        "车轮Wh/km": b.get("wh_per_km"),
        "毛耗电Wh/km": b.get("wh_per_km_gross"),
        "里程km": b.get("km"),
        "样本数": b.get("bins"),
    } for b in bands]
    best = None
    if rows:
        cand = [r for r in rows if isinstance(r.get("毛耗电Wh/km"), (int, float))]
        if cand:
            best = min(cand, key=lambda r: r["毛耗电Wh/km"])
    out = {"速度档": rows, "最省电的速度档": best.get("速度档kph") if best else None}
    if best:
        out["最省电的毛耗电Wh/km"] = best.get("毛耗电Wh/km")
    # ⚠️ **v0.103.0**：口径**不再自己维护一份** ——
    # 服务端 `note` 字段就是权威口径（`api/handlers.go` 的 `EnergyProfile.Note`），
    # 自己写一份必然漂移（本轮就发现这里把两个口径说反了）。
    out["口径"] = d.get("note") or "（服务端未下发口径说明）"
    return out


@mcp.tool(annotations=RO)
def teslamate_route_profile(drive_ids: str, step_km: int = 0) -> dict:
    """查**一趟（或几趟连续行程）的电量剖面**：每走多少公里，电量掉到多少。

    **什么时候用**：用户问「这趟电够不够」「电量是怎么一路掉下去的」
    「中途会不会没电」—— 都归它。

    ⚠️ drive_ids 用逗号分隔的行程 id（先用 `teslamate_drives` 拿 id）。
    ⚠️ km 是**相对起点**（可能是负数 = 起点之前），% 是该点的电量。
    """
    ids = (drive_ids or "").strip()
    if not ids:
        return {"error": "缺少 drive_ids（用 teslamate_drives 取 id 后逗号分隔）"}
    parts = [p.strip() for p in ids.replace("，", ",").split(",") if p.strip()]
    if any(not p.isdigit() for p in parts):
        return {"error": f"drive_ids 只能是数字 id，收到 {drive_ids!r}"}
    q = f"?drive_ids={','.join(parts)}"
    if step_km and int(step_km) > 0:
        q += f"&step_km={int(step_km)}"
    d = _get(f"/api/v1/cars/{CAR_ID}/route_profile{q}")
    if not isinstance(d, dict) or "error" in d:
        return d
    pts = d.get("points") or []
    return {
        "总里程km": d.get("total_km"),
        "累计爬升m": d.get("ascent_m"),
        "累计下降m": d.get("descent_m"),
        "分段": d.get("legs"),
        "电量剖面(km→%)": pts[:200],
        "点数": len(pts),
        "口径": "km 是相对起点（可负 = 起点之前），% 是该点的电量",
    }


@mcp.tool(annotations=RO)
def teslamate_charge_curve(charge_id: int) -> dict:
    """查**某一场充电的功率曲线**：随时间变化的功率、SOC、电压、电流。

    **什么时候用**：用户问「这次充电功率曲线什么样」「快充慢充怎么变的」
    「充电中途功率掉过吗」—— 都归它。

    ⚠️ charge_id 用 `teslamate_charges` 返回的 `id` 字段（已带上，可直接接上）。
    """
    try:
        cid = int(charge_id)
    except (TypeError, ValueError):
        return {"error": f"charge_id 必须是整数，收到 {charge_id!r}"}
    d = _get(f"/api/v1/charges/{cid}/curve")
    # ⚠️ **v0.72.0 修过形状**：这个端点返回的是**裸数组**（不是 {"points":…}），
    # 字段名是 `battery_level` / `charger_power_kw` / `charger_voltage` / `charger_current`。
    pts = d if isinstance(d, list) else (
        (d.get("points") or d.get("items") or []) if isinstance(d, dict) else []
    )
    if not isinstance(pts, list):
        return {"error": "返回格式异常", "raw": str(d)[:300]}
    rows = [{
        "时间": p.get("date"),
        "SOC%": p.get("battery_level"),
        "功率kW": p.get("charger_power_kw"),
        "电压V": p.get("charger_voltage"),
        "电流A": p.get("charger_current"),
        "相数": p.get("charger_phases"),
    } for p in pts[:240]]
    return {"充电id": cid, "点数": len(pts), "曲线": rows}



# --------------------------------------------------------------------------
# 控车
# --------------------------------------------------------------------------


def _param_howto(specs):
    """把服务端的**参数规格**翻译成 LLM 能直接照抄的样子。

    ⚠️ **v0.87.0**：以前清单只给「参数名」（`["driver_temp"]`）⇒
    LLM 不知道范围，只能猜 —— 而车控**不可逆**，猜错有代价。

    ⇒ 现在每个参数都带上：类型、是否必填、可填范围、每个取值的含义、完整说明。
    例：座椅加热的 `seat_position` 会告诉模型
    「0=主驾、1=副驾、2~6 第二排、7~8 第三排」，
    而不是让人自己猜「1 到底是副驾还是第二排」。
    """
    out = []
    for p in specs or []:
        if not isinstance(p, dict):
            continue
        d = {
            "参数名": p.get("name"),
            "类型": p.get("type"),
            "必填": bool(p.get("required")),
            "说明": p.get("description"),
        }
        lo, hi = p.get("min"), p.get("max")
        if lo is not None and hi is not None:
            d["可填范围"] = "%s~%s" % (lo, hi)
        opts = p.get("options") or []
        if opts:
            d["可选值"] = "、".join(
                "%s=%s" % (o.get("value"), o.get("label")) for o in opts if isinstance(o, dict)
            )
        out.append(d)
    return out


@mcp.tool(annotations=RO)
def teslamate_command_catalog() -> dict:
    """列出这台车**支持哪些车控动作**，每个动作的风险等级与参数该怎么填。

    **什么时候用**：用户问「车能做什么」「有没有座椅加热」「怎么远程启动」；
    或者你要下发动作前**先查参数怎么填**（参数名拼错会失败，取值填错更糟）。

    风险等级：high（解锁车门 / 打开尾门 / 打开前备箱 / 远程启动）/ medium / low。

    每个动作还带一个 `完成后是什么样`（「锁车」→「已锁车」）——
    下发成功后**直接用这一句**告诉用户，别自己拼。

    ⚠️ **每个动作都有 `参数怎么填`**（v0.87.0 新增）——
    里面有类型、是否必填、可填范围、每个取值代表什么。
    **照着填，不要猜**。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/commands")
    if not isinstance(d, dict) or "error" in d:
        return d
    return {
        "代理就绪": d.get("proxy"),
        "动作": [{
            "动作名": a.get("action"),
            "说明": a.get("description"),
            "完成后是什么样": a.get("done"),
            "风险": a.get("risk"),
            "参数": a.get("params") or [],
            "参数怎么填": _param_howto(a.get("param_specs")),
            "会唤醒车": a.get("wakes_vehicle"),
        } for a in (d.get("actions") or [])],
    }


@mcp.tool(annotations=DE)
def teslamate_vehicle_command(action: str, params: dict | None = None) -> dict:
    """给这台车**下发一条命令**：锁车 / 解锁 / 空调 / 座椅加热 / 方向盘加热 /
    车窗通风 / 充电上限 / 闪灯 / 鸣笛 / 尾门 / 前备箱 / 远程启动 / 唤醒…

    **什么时候用**：用户**明确要求**某个动作时
    （「把车锁了」「打开空调」「把主驾座椅加热开到高档」「把车窗开条缝」「打开前备箱」）。

    ⚠️ **高危动作**（door_unlock / actuate_trunk / open_frunk / remote_start_drive）
    请**先向用户确认**再调用。
    ⚠️ 动作名与**参数怎么填**先查 `teslamate_command_catalog`，别猜
    （那儿有类型、范围、每个取值的含义 —— 座椅加热的座位号、档位都在里面）。
    ⚠️ 车在休眠时命令发不出去 —— 会返回「车在休眠或离线，先唤醒再试」。
    ⚠️ 唤醒（wake_up）本身会耗电、也可能被 Tesla 计费。

    ⚠️ **回读「车端结果」，别只看「已下发」**（v0.76.0）：
    - `车端结果` = `executed` ⇒ **车真的照做了**，可以跟用户说「已锁车」；
    - `车端结果` = `rejected` ⇒ **车明确拒绝了**（原因见 `原因`），
      绝对不要报成功 —— 常见原因有「车已经在充电了」「充电枪没插」「当前状态不允许」；
    - `车端结果` = `dispatched` ⇒ 发出去了但**还没拿到车端确认**，如实说「已下发，等确认」。
    """
    act = (action or "").strip()
    if not act:
        return {"error": "缺少动作名"}
    body = {"params": params or {}}
    res = _request("POST", f"/api/v1/cars/{CAR_ID}/commands/{act}", body)
    if not isinstance(res, dict):
        return {"error": "返回格式异常", "raw": str(res)[:400]}
    out = {
        "动作": res.get("action"),
        "说明": res.get("description"),
        "风险": res.get("risk"),
        "已下发": res.get("sent"),
        "车端已执行": res.get("executed"),
        "车端结果": res.get("state"),
        "车": res.get("vin"),
        "时间": res.get("at"),
    }
    if res.get("done"):
        out["完成后是什么样"] = res.get("done")
    if res.get("reason"):
        out["原因码"] = res.get("reason")
    if res.get("reason_text"):
        out["原因"] = res.get("reason_text")
    if res.get("error"):
        out["错误"] = res.get("error")
    if res.get("hint"):
        out["提示"] = res.get("hint")
    if res.get("response"):
        out["车端回应"] = res.get("response")
    return out


@mcp.tool(annotations=RO)
def teslamate_telemetry() -> dict:
    """查这台车的**车况实况**（车主动推的实时数据）：胎压、车窗、门、哨兵、温度、充电上限。

    **什么时候用**：用户问「四个轮胎压多少」「胎压正常吗」「车窗关了吗」
    「后备箱开着吗」「哨兵开着吗」「车里热不热」「充电上限设的多少」—— 都归它。

    ⚠️ **与 `teslamate_status` 的分工**：
    - `teslamate_status` = **汇总状态**（电量 / 续航 / 位置 / 锁车）
    - `teslamate_telemetry` = **车主动推的实时细节**（胎压 / 车窗 / 门 / 哨兵 / 温度 / 充电上限）

    ⚠️ **「数据是旧的」必须一起报**：
    车睡着时车端就断了，broker 上留的是**断线前的最后已知值**。
    `数据是旧的` = true ⇒ 那批值是**车断线那一刻**的（可能已经过了好几个小时）——
    **报给用户时必须带上 `采集于` 的时间**，别当成「现在的样子」。

    ⚠️ **没读到就是没读到**：
    `开着哪几扇窗` / `开着哪几个门` 若是「不知道（没读到）」，
    **不要说成「都关着」** —— 那会让「车窗未关」的提醒凭空消失。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/telemetry_vehicle")
    if not isinstance(d, dict) or "error" in d:
        return d if isinstance(d, dict) else {"error": "返回格式异常"}

    tpms = d.get("tpms") or {}
    windows = d.get("windows") or []
    doors = d.get("doors") or []
    sentry = d.get("sentry") or {}

    open_win = d.get("open_windows")
    open_door = d.get("open_doors")

    def _open_text(v):
        """三态：None=不知道 / []=全关 / 列表=开着这些。"""
        if v is None:
            return "不知道（没读到）"
        if not v:
            return "全部关闭"
        return "、".join(v)

    return {
        "车端此刻在线": bool(d.get("connected")),
        # ⚠️ 「从未接入」与「车睡着了」是两回事 —— 少了它就分不清。
        "遥测已接入": bool(d.get("ever_connected")),
        "采集于": d.get("at"),
        # ⚠️ 少了这个，9 小时前的胎压会被当成「刚刚测的」。
        "数据是旧的": bool(d.get("stale")),
        "胎压bar": {
            "左前": tpms.get("fl"),
            "右前": tpms.get("fr"),
            "左后": tpms.get("rl"),
            "右后": tpms.get("rr"),
        },
        "胎压是否异常": tpms.get("alert"),
        "胎压异常轮次": tpms.get("alert_wheels") or [],
        "胎压接近阈值轮次": tpms.get("warn_wheels") or [],
        "胎压异常详情": tpms.get("detail") or "",
        "开着哪几扇窗": _open_text(open_win),
        "车窗状态": {w.get("label"): w.get("text") for w in windows},
        "开着哪几个门": _open_text(open_door),
        "门状态": {x.get("label"): ("开着" if x.get("open") else "关着") for x in doors},
        # ⚠️ 哨兵状态认不出来时，服务端给的是**车端原文** —— 原样报，不编。
        "哨兵": sentry.get("text") or "不知道（没读到）",
        "车内温℃": d.get("inside_temp"),
        "车外温℃": d.get("outside_temp"),
        "充电上限%": d.get("charge_limit_soc"),
        "高压绝缘电阻": d.get("insulation_resistance"),
        # 原始字段：任何我们拿到的字段都能在这里查到（label → 车端原值）。
        "原始字段": {f.get("label") or f.get("field"): f.get("text") for f in (d.get("fields") or [])},
    }


@mcp.tool(annotations=RO)
def teslamate_alerts() -> dict:
    """查这台车的**故障码**（BMS_ / DI_ / VCFRONT_ / CP_ …）与遥测接入状态。

    **什么时候用**：用户问「车有故障吗」「车报了什么码」「电池有没有报警」
    「车出毛病了吗」「故障码是什么」「车机亮了什么灯」—— 都归它。

    ⚠️ **反幻觉**：故障码的含义**只认已核验的来源**。
    没收录的码，服务端会明说「含义未收录」并让你去车机 Service Mode 看 ——
    **不要按字母硬凑一个中文名**（那是编的）。

    ⚠️ **「没有故障码」可能是假的**：先看 `遥测已接入`。
    它是 false ⇒ 我们**根本没在收数据**（不是「车没故障」）；
    这时要老实说「拿不到故障码」，**别报「一切正常」**。

    ⚠️ **v0.136.0：「最后一条」不是「我们收到它的时刻」** ——
    broker 会把留着的旧消息在订阅者上线时**重发**（retained），
    而 MQTT 载荷**不带车端采集时刻** ⇒ 「收到时刻」不能当「采集时刻」。
    现在服务端给的是**车端最后一条真实数据**的时刻：

    - `最后一条` = 车端最后一次真实上报的时刻；
      车已离线时 = **车断线的那个时刻**（值是断线前的最后已知值）；
    - `最后一条是旧的` = true ⇒ **报给用户时必须带上「最后一条」这个时间**，
      别当成「车端刚刚还在报」；
    - `车端新消息条数` / `broker重放条数` 把两者分开 ——
      「收到条数」是**总数（含重放）**，别拿它当「车端报了多少」。

    ⚠️ 分工：电量 / 续航 → `teslamate_status`；电池衰减与健康度 → `teslamate_battery`；
    充电结局 → `teslamate_charge_outcome`；胎压 → `teslamate_status`。
    """
    data = _get(f"/api/v1/cars/{CAR_ID}/alerts")
    if not isinstance(data, dict) or "error" in data:
        return data if isinstance(data, dict) else {"error": "返回格式异常"}

    ever = bool(data.get("ever_connected"))
    connected = (data.get("connectivity") or "") == "CONNECTED"
    if not ever:
        state = "从未接入"
    elif connected:
        state = "已接入"
    else:
        state = "已接入（车端此刻离线）"

    stream = data.get("stream") or {}
    alerts = data.get("alerts") or []
    errors = data.get("errors") or []

    out = {
        # ⚠️ **这三个是「必然存在」的** —— 哪怕一台故障码都没有也照样给。
        #    没它们，调用方就没法区分「车没故障」与「我们没在收数据」。
        "遥测已接入": ever,
        "接入状态": state,
        "活跃故障码": sum(1 for a in alerts if a.get("active")),
        "故障码总数": len(alerts),
        "车端此刻在线": connected,
        "遥测流": {
            "broker已连接": bool(stream.get("broker_connected")),
            "收到条数": stream.get("messages"),
            # ⚠️ **v0.136.0**：「收到条数」里**混着 broker 重发的 retained**
            #    （broker 会把留着的旧消息在订阅者上线时重发 ⇒ API 一重启就收到一批）。
            #    ⇒ 拆成「车端新发的」与「重放的」两个数，别把它们混着说。
            "车端新消息条数": stream.get("car_messages"),
            "broker重放条数": stream.get("replayed_messages"),
            "告警条数": stream.get("alerts"),
            "错误条数": stream.get("errors"),
            # ⚠️ **v0.136.0**：「最后一条」现在是**车端最后一条真实数据**的时刻，
            #    **不是**「我们收到它的时刻」—— broker 重发 retained 会把后者刷新成假的
            #    （2026-11-09 实测：车端 19:40 断连，流级却显示 23:01，
            #      那是 API 容器重启补落的时刻）。
            "最后一条": stream.get("last_message_at"),
            # ⚠️ true ⇒ 那是**车断线前的最后已知值**，不是「车端刚刚还在报」
            #    ⇒ 报给用户时**必须**带上「最后一条」这个时间。
            "最后一条是旧的": bool(stream.get("last_message_stale")),
            "车端此刻在线": bool(stream.get("car_online")),
        },
        "故障码": [
            {
                "码": a.get("code"),
                "子系统": a.get("subsystem"),
                "含义": a.get("meaning") or "（未收录，含义以车机 Service Mode 为准）",
                "摘要": a.get("summary"),
                "仍在持续": bool(a.get("active")),
                "开始于": a.get("started_at"),
                "结束于": a.get("ended_at"),
            }
            for a in alerts
        ],
        "遥测错误": [
            {
                "名称": e.get("name"),
                "内容": e.get("body"),
                "时间": e.get("created_at"),
            }
            for e in errors
        ],
    }
    if data.get("updated_at"):
        out["最后一条遥测数据"] = data.get("updated_at")
    if data.get("hint"):
        out["提示"] = data.get("hint")
    if not ever:
        # ⚠️ 遥测从没接上 ⇒ **不能**让调用方以为「车没故障」。
        out["警告"] = "遥测从未接入，**拿不到**故障码 —— 这不等于「车没有故障」。"
    return out


@mcp.tool(annotations=RO)
def teslamate_feedback(limit: int = 20, status: str = "") -> dict:
    """读**用户反馈**（用户自己在 App「设置 → 用户反馈」里记下来的问题与建议）。

    **什么时候用**：迭代开始前先跑这个 —— 用户在 App 里记的问题全在这儿，
    不用再问「你是在哪个页面发现的」「当时是什么现象」。

    ⚠️ `status` 传 `new` 只看**还没评估**的；空串 = 全部。

    ⚠️ **反馈是用户的原话** —— 别替它改写，也别自己判断「这条其实不算问题」；
    判断的结果请用 `teslamate_feedback_decision` **写回**（要处理 / 不处理 + 理由）。

    ⚠️ 分工：车况 / 电量 → `teslamate_status`；故障码 → `teslamate_alerts`。
    """
    try:
        n = max(1, min(int(limit), 200))
    except Exception:
        n = 20
    # ⚠️ status 只认四个值（服务端会拒）⇒ 这里先挡一层，别把脏参数打给 API。
    st = status if status in ("new", "doing", "skip", "done") else ""
    data = _get("/api/v1/feedback?limit=%d&status=%s" % (n, st))
    if not isinstance(data, dict) or "error" in data:
        return data if isinstance(data, dict) else {"error": "返回格式异常"}

    items = data.get("items") or []
    return {
        # ⚠️ 这两个**恒在**（哪怕一条反馈都没有）⇒ 可以被字段自检断言。
        "条数": len(items),
        "待评估": sum(1 for i in items if i.get("status") == "new"),
        "反馈": [
            {
                "id": i.get("id"),
                "时间": i.get("created_at"),
                "内容": i.get("message"),
                # ⚠️ 上下文**可能是空的**（老数据 / 客户端没带）⇒ 明说「未记录」，
                #    不要编一个页面名出来。
                "页面": i.get("screen") or "（未记录）",
                "App版本": i.get("app_version") or "（未记录）",
                "车辆": i.get("car_name") or "",
                "状态": _feedback_status_cn(i.get("status")),
                "评估结论": i.get("decision_note") or "",
                # ⚠️ 截图给的是**宿主上的绝对路径** —— 这样才能直接打开看图。
                "截图": _feedback_image_path(i.get("image_path")),
            }
            for i in items
        ],
    }


def _feedback_status_cn(status: Any) -> str:
    """反馈状态 → 中文（与服务端的四态一致）。"""
    return {
        "new": "待评估",
        "doing": "要处理",
        "skip": "不处理",
        "done": "已修好",
    }.get(status or "", "未知")


def _feedback_image_path(image_path: Any) -> str:
    """截图相对路径 → **宿主上的绝对路径**（agent 能直接打开看图）。"""
    p = (image_path or "").strip()
    if not p:
        return ""
    # ⚠️ 只取文件名 ⇒ 就算库里被塞进 "../../xxx" 也出不了 media 目录。
    return str(_REPO / "media" / "feedback" / Path(p).name)


@mcp.tool(annotations=WR)
def teslamate_feedback_decision(feedback_id: int, decision: str, note: str = "") -> dict:
    """给一条用户反馈**写评估结论**（发版前的必做动作）。

    **什么时候用**：每次迭代开始、读完 `teslamate_feedback` 之后，
    对每条「待评估」的反馈给出判断 —— 要处理（doing）还是不处理（skip + 理由）。

    ⚠️ **skip 必须写理由**：不处理而不说理由 ⇒ 下一轮还会有人重新纠结同一件事。
    ⚠️ `done` = 已经修好了（改完记得回填它）。
    """
    d = (decision or "").strip()
    if d not in ("doing", "skip", "done", "new"):
        return {"error": "decision 只能是 doing / skip / done / new"}
    if d == "skip" and not (note or "").strip():
        return {"error": "判断为「不处理」时必须写理由"}
    body = {"status": d}
    if (note or "").strip():
        body["note"] = note.strip()
    res = _request("POST", "/api/v1/feedback/%d/status" % int(feedback_id), body)
    if not isinstance(res, dict) or "error" in res:
        return res if isinstance(res, dict) else {"error": "返回格式异常"}
    return {
        "id": res.get("id"),
        "状态": _feedback_status_cn(res.get("status")),
        "评估结论": res.get("decision_note") or "",
        "内容": res.get("message"),
    }


# ⚠️ **v0.166.0 修掉的严重 bug**：这个「入口」块原来在**文件中部**。
#
# ⇒ 后果：`mcp.run()` 在 stdio 模式下**阻塞** ⇒ 后面定义的 4 个工具
#   （teslamate_drive_energy_profile / teslamate_climb_model /
#   teslamate_maintenance / teslamate_set_maintenance_baseline）
#   **从来没被注册进服务** ⇒ 飞书里根本调不到它们。
# ⇒ 而 `mcp/check_tool_fields.py` 是 `import teslamate_mcp` 直接调函数
#   （**走模块导入路径，不是 stdio 启动路径**）⇒ 那里全绿 ⇒ **假绿**。
#
# ⚠️ 教训（与 v0.163.0「定义了自检函数 ≠ 它真跑了」同型）：
#   **「源码里有」不等于「活进程里有」** ⇒ 必须打活进程验（tools/list）。
# ⇒ 现在入口块移到**文件最末**，并新增闸门 30 钉住这件事。


@mcp.tool(annotations=RO)
def teslamate_drive_energy_profile(drive_id: int = 0, step_km: int = 5) -> dict:
    """查**某一趟行程的逐公里电量剖面**（这趟的电是怎么掉的）。

    **什么时候用**：用户问「上周那趟电怎么掉得这么快」「这趟跑到一半就没电了」
    「哪一段最费电」「这趟的爬升多耗了多少电」—— 都归它。

    ⚠️ **逐公里真实剖面**（不是直线插值）：服务端按里程 5 公里分箱取 battery_level
    的中位数 ⇒ 山区长途的误差从实测 13 公里降到 1 公里内
    （插值会把「第 244 公里跌破 20% 保底」算成 231 公里 ⇒ **危险决策**）。

    ⚠️ drive_id=0 ⇒ 取**最近一趟**（模型手里没有 id 时就用这个；
    也可先查 teslamate_drives 拿到 id 再指定）。
    """
    did = int(drive_id or 0)
    if did <= 0:
        rows = _get(f"/api/v1/cars/{CAR_ID}/drives?limit=1")
        if not isinstance(rows, list) or not rows:
            return {"错误": "库里还没有行程"}
        did = int(rows[0].get("id") or 0)
        if did <= 0:
            return {"错误": "最近一趟拿不到 id"}
    pts = _get(f"/api/v1/drives/{did}/soc_profile?step_km={int(step_km)}")
    if not isinstance(pts, list):
        return {"错误": "取不到电量剖面", "详情": str(pts)[:200]}
    if not pts:
        return {"错误": "这趟没有逐公里电量采样"}
    out = {
        "行程id": did,
        "每公里步长": int(step_km),
        "采样点数": len(pts),
        "起点电量(%)": pts[0].get("soc"),
        "终点电量(%)": pts[-1].get("soc"),
        "剖面": [{"公里": p.get("km"), "电量(%)": p.get("soc")} for p in pts],
    }
    return out


@mcp.tool(annotations=RO)
def teslamate_climb_model(min_km: float = 20.0) -> dict:
    """查**本车的爬升能耗标定**（上坡每米多耗多少电 / 下坡每米能回多少）。

    **什么时候用**：用户问「上坡特别费电吗」「下坡能回充多少」
    「这个坡大概要多少电」「爬升修正系数是多少」—— 都归它。

    ⚠️ 这是**本车实测**的系数（不是通用值）：上坡约 6.4 Wh/m、下坡约 3.4 Wh/m。
    ⚠️ **回充系数可能不可信**（纯下坡样本只有 5 趟）⇒ 看「回充可信」；
    不可信时用「保守下坡回充」（偏安全那一侧）。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/climb_model?min_km={min_km}")
    if not isinstance(d, dict) or "error" in d:
        return {"错误": "取不到爬升标定", "详情": str(d)[:200]}
    return {
        "上坡每米耗电(Wh/m)": d.get("c_up_wh_per_m"),
        "下坡每米回充(Wh/m)": d.get("c_regen_wh_per_m"),
        "保守下坡回充(Wh/m)": d.get("conservative_c_regen"),
        "上坡传动效率": d.get("implied_eta_up"),
        "下坡回充效率": d.get("implied_eta_regen"),
        "上坡系数可信": d.get("c_up_trustworthy"),
        "回充可信": d.get("c_regen_trustworthy"),
        "参与拟合行程数": d.get("n_drive"),
        "纯上坡行程数": d.get("n_pure_up"),
        "纯下坡行程数": d.get("n_pure_down"),
        "毛耗电回归R2": d.get("r2_gross"),
        "最短统计里程(km)": d.get("min_km"),
        "方法": d.get("method"),
        "注意": d.get("caveat"),
    }

# ⚠️ **v0.165.0**：写「保养 / 轮胎基准」时**只改用户说的那几项**。
#
# 为什么：服务端的写接口是**整份 upsert**（四个数一起写）⇒
#   如果直接拿用户给的两项去写，**没说的那两项会被 0 覆盖**
#   （比如「上次保养 9 万公里」⇒ 轮胎基准被清零 ⇒ 轮胎提醒凭空消失）。
#
# ⇒ 先读当前基准，再合并 ⇒ 只说哪项就只改哪项。
def merge_maintenance_baseline(current: dict, given: dict) -> dict:
    """合并基准：用户给了的用用户的，没给的**保持原样**。

    ⚠️ 纯函数（不碰网络、不写库）⇒ 契约自检可以**只测它**
    （写工具本身绝不能进自检的 SPEC —— 自检每轮都跑 ⇒ 会一直写用户的基准）。
    """
    cur = current if isinstance(current, dict) else {}
    out = {}
    for key in ("service_odo_km", "service_interval_km", "tire_odo_km", "tire_interval_km"):
        v = given.get(key)
        if v is None:
            v = cur.get(key)
        out[key] = 0 if v is None else v
    return out


@mcp.tool(annotations=WR)
def teslamate_set_maintenance_baseline(
    service_odo_km: float | None = None,
    service_interval_km: int | None = None,
    tire_odo_km: float | None = None,
    tire_interval_km: int | None = None,
) -> dict:
    """把**保养 / 轮胎基准**写进服务端（按车隔离）—— 之后「我轮胎还能跑多少」就能答了。

    **什么时候用**：用户**明确给出**这些数字时 ——
    「上次保养是 9 万公里，每 2 万公里一次」「换胎是 6 万公里，每 4 万公里一次」
    —— 或者他直接说「把保养基准记成 …」。

    ⚠️ **先向用户确认再写**：基准是**车主的线下行为**（他知道自己换过机油、开了保养单）
    ⇒ **别替他猜数字**，也别把「上次保养大概 9 万」当成精确值写进去。
    ⚠️ **只写用户说的那几项**：没说的（保养还是轮胎）**保持原样**
    （服务端的写接口是整份覆盖 ⇒ 直接写会把没说的一项清零）。
    ⚠️ **间隔（每多少公里一次）要一起给**：如果之前也没设过间隔，
    只给里程会被服务端拒（间隔必须 > 0）⇒ 那就**问用户一句**再写。

    ✅ 写完服务端会**回读并给出结论**（「还剩多少公里」）⇒ 把那个结论原样报给用户。
    """
    given = {
        "service_odo_km": service_odo_km,
        "service_interval_km": service_interval_km,
        "tire_odo_km": tire_odo_km,
        "tire_interval_km": tire_interval_km,
    }
    # ⚠️ 负数一律拒（服务端也会拒，但**别**把错误丢成一句 HTTP 400）。
    for k, v in given.items():
        if v is not None and v < 0:
            return {"错误": "%s 不能是负数（里程表读数不可能是负的）" % k}
    # ⚠️ **先读当前基准** ⇒ 合并 ⇒ 只改用户说的那几项。
    cur = _get(f"/api/v1/cars/{CAR_ID}/maintenance")
    if not isinstance(cur, dict) or "error" in cur:
        return {"错误": "取不到当前基准（先修好服务器连接）", "详情": str(cur)[:200]}
    body = merge_maintenance_baseline(cur.get("baseline") or {}, given)
    res = _request("POST", f"/api/v1/cars/{CAR_ID}/maintenance/baseline", body)
    if not isinstance(res, dict) or "error" in res:
        return {"错误": "写入失败", "详情": str(res)[:300]}
    base = res.get("baseline") or {}
    out = {
        "已写入": True,
        "上次保养时的里程(km)": base.get("service_odo_km"),
        "保养间隔(km)": base.get("service_interval_km"),
        "上次换胎时的里程(km)": base.get("tire_odo_km"),
        "轮胎更换间隔(km)": base.get("tire_interval_km"),
        # ⚠️ 结论是**服务端渲染的原文**（ADR #41）⇒ 原样带出去。
        "一句话": res.get("summary"),
        "已设置基准": res.get("configured"),
        "说明": res.get("note"),
    }
    for key, label in (("service", "保养"), ("tire", "轮胎")):
        it = res.get(key)
        if isinstance(it, dict):
            out[label] = {
                "还剩(km)": it.get("remaining_km"),
                "该在里程表(km)时做": it.get("due_odo_km"),
                "已超期": it.get("overdue"),
                "快到了": it.get("due_soon"),
                "结论": it.get("text"),
            }
    return out

@mcp.tool(annotations=RO)
def teslamate_maintenance() -> dict:
    """查**保养 / 轮胎提醒**：「我轮胎还能跑多少」「保养超期了吗」「该什么时候做保养」。

    **什么时候用**：用户问「我轮胎还能跑多少公里」「保养超期了吗」
    「上次保养是什么时候」「什么时候该换胎」—— 都归它。

    ⚠️ **基准存在服务端**（按车隔离）⇒ 这里读到的与 App 车况页**同一个数、同一句话**。

    ⚠️ **别自己减**：剩余公里数是服务端按**车端里程表**算的
    （车端 = 仪表盘上那个数；不是 GPS 累计值，那个能偏几万公里）⇒
    你在这里拿别的数减一遍，结果一定对不上。

    ⚠️ **里程表读不到就不给判定**（车可能睡着了）⇒ 这时看「说明」，
    **别自己估一个**（估出来的是假告警，误报的方向最危险）。

    ⚠️ **「还没设置基准」是常态**：保养是车主的线下行为（换了机油、开了保养单），
    只有车主自己知道 ⇒ 他没填过就**没有**这个提醒，不是故障。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/maintenance")
    if not isinstance(d, dict) or "error" in d:
        return {"错误": "取不到保养提醒", "详情": str(d)[:200]}

    def item(it):
        if not isinstance(it, dict):
            return None
        return {
            "还剩(km)": it.get("remaining_km"),
            "该在里程表(km)时做": it.get("due_odo_km"),
            "已超期": it.get("overdue"),
            "快到了": it.get("due_soon"),
            "结论": it.get("text"),
        }

    svc = item(d.get("service"))
    tire = item(d.get("tire"))
    base = d.get("baseline") or {}

    out = {
        "一句话": d.get("summary"),
        "车端里程表(km)": d.get("odometer_km"),
        "里程表来源": d.get("odometer_source"),
        "已设置基准": d.get("configured"),
        "上次保养时的里程(km)": base.get("service_odo_km"),
        "保养间隔(km)": base.get("service_interval_km"),
        "上次换胎时的里程(km)": base.get("tire_odo_km"),
        "轮胎更换间隔(km)": base.get("tire_interval_km"),
        "说明": d.get("note"),
    }
    if svc is not None:
        out["保养"] = svc
    if tire is not None:
        out["轮胎"] = tire
    return out



# --------------------------------------------------------------------------
# 入口（**必须在文件最末** —— 见上面那条 v0.166.0 的教训）
# --------------------------------------------------------------------------

def _read_http_token() -> str:
    """HTTP 模式的口令：环境变量优先，其次同目录/仓库根的 .env。

    ⚠️ **为什么与 API_TOKEN 分开**：API_TOKEN 是**本机**调 API 用的；
    HTTP 暴露的是**能把车开走**的入口 ⇒ 必须有独立的、
    **可以随时吊销**的口令 ⇒ 吊销它 = 一键关掉远程车控。
    """
    tok = os.environ.get("MCP_HTTP_TOKEN", "").strip()
    if tok:
        return tok
    for cand in (_HERE / ".env", _REPO / ".env"):
        try:
            for line in cand.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("MCP_HTTP_TOKEN="):
                    return line.split("=", 1)[1].strip()
        except OSError:
            continue
    return ""


class _StaticTokenVerifier:
    """校验 Bearer 令牌（实现 MCP 官方的 TokenVerifier 协议）。

    ⚠️ **为什么自己写这个类**：MCP 的授权规范面向 OAuth 2.1（动态注册、
    授权码流程…）—— 那是给**公网多租户**服务用的。本项目是
    **单用户自用** ⇒ 一个静态口令就够，且更好管：
    改 .env 里的 MCP_HTTP_TOKEN ⇒ **一键吊销**所有远程车控。

    ⚠️ 用 hmac.compare_digest 比较 ⇒ 不给时序侧信道留机会。
    """

    def __init__(self, token: str):
        self._token = token or ""

    async def verify_token(self, token: str):
        if not self._token:
            # 没配口令 ⇒ 放行（但只绑回环；非回环时上面已 fail closed 退出）
            return _access_token(token)
        if not token or not hmac.compare_digest(str(token), self._token):
            return None
        return _access_token(token)


def _access_token(token: str):
    """造一个 AccessToken 对象。

    ⚠️ **SDK 2.0 的 AccessToken 必填 client_id**（实测：只给 token + scopes
    会抛 pydantic 校验错）⇒ 这里给一个固定值（单用户自用，没有真实 OAuth 客户端）。
    ⚠️ 老 SDK 的字段名不同 ⇒ 逐个构造签名试，谁成用谁。
    """
    try:
        from mcp.server.auth.provider import AccessToken
    except Exception:  # pragma: no cover - 老 SDK
        return None
    for kwargs in (
        {"token": token, "client_id": "teslamate-mcp", "scopes": ["teslamate"]},
        {"token": token, "client_id": "teslamate-mcp"},
        {"token": token, "scopes": ["teslamate"]},
        {"token": token},
    ):
        try:
            return AccessToken(**kwargs)
        except Exception:
            continue
    return None


def _copy_registry(src_server, dst_server) -> None:
    """把源 server 上已注册的工具与资源**搬**到目标 server。

    ⚠️ **为什么需要**：鉴权必须在**构造时**传进 MCPServer（SDK 2.0 的约束）
    ⇒ HTTP 模式要另建一个带鉴权的实例 ⇒ 而工具是装饰器注册的（在源实例上）
    ⇒ 这里把注册表搬过去（**不复制工具实现**，只是让两个实例指向同一批函数）。

    ⚠️ **add_tool 收的是「函数」不是 Tool 对象**（实测：传 Tool 会静默失败，
    症状是 tools/list 返回 0 个工具 ⇒ 看起来像「服务没工具」，极难查）
    ⇒ 这里统一从 Tool 对象上取 .fn 再注册，并**带上注解**
    （否则客户端看不见 readOnly / destructive 标记）。
    """
    s_tools = getattr(getattr(src_server, "_tool_manager", None), "_tools", None)
    if isinstance(s_tools, dict):
        for item in list(s_tools.values()):
            fn = getattr(item, "fn", None)
            if fn is None:
                continue
            try:
                dst_server.add_tool(
                    fn,
                    name=item.name,
                    title=item.title,
                    description=item.description,
                    annotations=item.annotations,
                )
            except Exception:
                pass
    # ⚠️ **add_resource 收的是「Resource 对象」**（与 add_tool 不同：
    #    add_tool 收函数，add_resource 收对象）⇒ 这里直接搬对象。
    s_res = getattr(getattr(src_server, "_resource_manager", None), "_resources", None)
    if isinstance(s_res, dict):
        for uri, item in list(s_res.items()):
            try:
                dst_server.add_resource(item)
            except Exception:
                pass


def _run_http(host: str, port: int, path: str) -> None:
    """以 Streamable HTTP 传输跑（规范 basic/transports 的第二种标准传输）。

    ⚠️ **安全三件套**（缺一不可）：
    ① **默认只绑回环**（127.0.0.1）—— 规范明写：本地服务 SHOULD 只绑回环；
    ② **绑非回环必须带口令** —— 否则同网段任何机器都能开车 ⇒ fail closed；
    ③ **Origin / Host 校验**（规范：MUST validate the Origin header，防 DNS rebinding）
       ⇒ 由 SDK 的 TransportSecuritySettings 承担（allowed_hosts/allowed_origins）。

    ⚠️ **Bearer 令牌用 MCP 官方的 TokenVerifier 协议**（规范 2025-06-18 授权）
    ⇒ 客户端配置里带 headers.Authorization 即可；
    ⇒ 令牌不对 ⇒ **401**（不是 200 + 空结果）。
    """
    import sys

    tok = _read_http_token()
    is_loopback = host in ("127.0.0.1", "localhost", "::1")
    if not is_loopback and not tok:
        print("[teslamate-mcp] 拒绝启动：绑 %s 需要 MCP_HTTP_TOKEN" % host,
              file=sys.stderr)
        raise SystemExit(2)
    if not tok:
        print("[teslamate-mcp] 警告：没有 MCP_HTTP_TOKEN ⇒ 本地 HTTP 口令为空"
              "（只绑回环，仅本机可用）",
              file=sys.stderr)

    security = None
    if _TransportSecuritySettings is not None:
        security = _TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=["%s:*" % host, "127.0.0.1:*", "localhost:*", "[::1]:*"],
            allowed_origins=["http://%s:*" % host, "http://127.0.0.1:*", "http://localhost:*"],
        )

    print("[teslamate-mcp] Streamable HTTP：http://%s:%d%s（口令：%s）"
          % (host, port, path, "有" if tok else "无"),
          file=sys.stderr)

    # ⚠️ **鉴权用 SDK 官方的 TokenVerifier 协议**（不是自己写中间件 ——
    #    那样容易漏掉某些路径；SDK 的实现会统一套在整条 /mcp 路由上）。
    verifier = _StaticTokenVerifier(tok)
    auth_settings = None
    try:
        from mcp.server.auth.settings import AuthSettings as _AuthSettings
        auth_settings = _AuthSettings(
            issuer_url="http://%s:%d" % (host, port),
            resource_server_url="http://%s:%d" % (host, port),
            required_scopes=[],
        )
    except Exception:  # pragma: no cover - 老 SDK 没有授权模块
        auth_settings = None

    # ⚠️ **鉴权必须在「构造 MCPServer 时」传进去**（SDK 2.0 的真实约束）：
    #    · `MCPServer(..., auth=..., token_verifier=...)` ⇒ **构造参数**；
    #    · `streamable_http_app()` 与 `run()` **都不接受** auth 参数
    #      ⇒ 走那两条路时鉴权会被**悄悄丢掉** ⇒ 错令牌也返回 200（**假鉴权**）。
    # ⇒ 所以 HTTP 模式**另建一个带鉴权的 server 实例**，
    #    再把已注册的工具与资源**搬过去**（不复制工具实现）。
    http_mcp = mcp
    if auth_settings is not None:
        try:
            http_mcp = _MCP(
                "teslamate",
                instructions=SERVER_INSTRUCTIONS,
                auth=auth_settings,
                token_verifier=verifier,
            )
            _copy_registry(mcp, http_mcp)
        except Exception as e:  # pragma: no cover
            print("[teslamate-mcp] 鉴权实例创建失败，退回无鉴权：%r" % e,
                  file=sys.stderr)

    app = http_mcp.streamable_http_app(
        streamable_http_path=path,
        transport_security=security,
        host=host,
    )
    import uvicorn
    uvicorn.run(app, host=host, port=port, log_level="warning")


def _main() -> None:
    """命令行入口。

    ⚠️ stdio 传输：stdout 是协议通道，**任何 print 都会破坏它** ⇒ 日志只走 stderr。
    """
    import argparse
    import sys

    ap = argparse.ArgumentParser(
        prog="teslamate-mcp",
        description="TeslaMate MCP 服务（查车况 + 控车）",
    )
    ap.add_argument(
        "--transport",
        choices=["stdio", "http"],
        default=os.environ.get("MCP_TRANSPORT", "stdio"),
        help="stdio=由 MCP 客户端拉起（默认）；http=独立跑一个 HTTP 端点",
    )
    ap.add_argument("--host", default=HTTP_HOST, help="HTTP 监听地址（默认 127.0.0.1）")
    ap.add_argument("--port", type=int, default=HTTP_PORT, help="HTTP 监听端口（默认 8765）")
    ap.add_argument("--path", default=HTTP_PATH, help="HTTP 端点路径（默认 /mcp）")
    args = ap.parse_args()

    print("[teslamate-mcp] API=%s car=%s token=%s transport=%s"
          % (API_URL, CAR_ID, "有" if TOKEN else "缺", args.transport),
          file=sys.stderr)
    if args.transport == "http":
        _run_http(args.host, args.port, args.path)
    else:
        mcp.run()


if __name__ == "__main__":
    _main()