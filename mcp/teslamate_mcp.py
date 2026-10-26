#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""TeslaMate MCP 服务（stdio）—— 让电脑上的 Hermes 能查这台车的数据、也能控车。

⚠️ **v0.66.0（第 33 轮「车辆控制下沉」）**。

## 为什么在 teslamate 里

车主诉求：「在 PC 上提供标准的 MCP 或 CLI 服务，让我电脑上的 Hermes
也可以完整的查看车辆数据和操控车辆。」
并且明确：「**Housekeeper 中的车辆控制解耦掉，正确的应该是车辆控制在此项目中。
Housekeeper 调用这个项目。**」

⇒ 车控能力归 **teslamate**（`api/tesla_command.go` + command-proxy sidecar），
本 MCP 服务只是**它的一层壳** —— 查数据与控车都打同一个 API。

## 怎么接进 Hermes

Hermes 的 `config.yaml` 里有 `mcp_servers:` 段（stdio 形如 `command` + `args`）：

```yaml
mcp_servers:
  teslamate:
    command: python
    args: ["C:/Users/<用户名>/OneDrive/文档/teslamate/mcp/teslamate_mcp.py"]
```

⚠️ **令牌不用写进 Hermes 的配置** —— 本服务会自己去**同目录的 `.env`** 找
`API_TOKEN`（也在 `TESLAMATE_API_TOKEN` 环境变量里认）。
少一处密钥副本。

## 车控的安全边界

- 动作只有服务端 `api/tesla_command.go` 目录里那 **16 个**，这里不新增；
- 参数由**服务端**再校验一遍（这里只是转发）；
- **高危动作**（解锁 / 开合尾门 / 远程启动）在工具描述里标了风险等级，
  Hermes 应当先向用户确认再调用；
- 车在休眠时命令发不出去（Tesla 回 408）—— 本服务会把它翻译成
  「车在休眠或离线，先唤醒再试」。

## 运行

```powershell
python mcp/teslamate_mcp.py            # stdio（由 MCP 客户端拉起）
```

依赖：只有 `mcp` 这个 Python 包（`pip install mcp`），不碰数据库。
"""

from __future__ import annotations

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

# --------------------------------------------------------------------------
# 配置
# --------------------------------------------------------------------------

_HERE = Path(__file__).resolve().parent
_REPO = _HERE.parent

API_URL = os.environ.get("TESLAMATE_API_URL", "http://localhost:8090").rstrip("/")
CAR_ID = os.environ.get("TESLAMATE_CAR_ID", "1")
TIMEOUT = float(os.environ.get("TESLAMATE_TIMEOUT", "60"))


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

mcp = _MCP("teslamate")


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


# --------------------------------------------------------------------------
# 查数据
# --------------------------------------------------------------------------


@mcp.tool()
def teslamate_status() -> dict:
    """查这台车现在的状态：电量、续航、锁车、位置、内外温、实时胎压、车辆是否醒着。

    车在休眠时锁车与胎压会显示「未知」—— 那是没读到，不是车没锁。
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
        "已锁": s.get("locked"),
        "锁车状态采集于": s.get("locked_at"),
        "位置": s.get("location_name"),
        "车外温℃": s.get("outside_temp"),
        "车内温℃": s.get("inside_temp"),
        "空调开着": s.get("is_climate_on"),
        "胎压bar": {
            "左前": (s.get("tpms_live_fl") or {}).get("pressure")
            if isinstance(s.get("tpms_live_fl"), dict) else s.get("tpms_live_fl"),
            "右前": s.get("tpms_live_fr"),
            "左后": s.get("tpms_live_rl"),
            "右后": s.get("tpms_live_rr"),
        },
        "数据滞后小时": s.get("data_age_hours"),
        "最后采集于": s.get("updated_at"),
    }


@mcp.tool()
def teslamate_battery() -> dict:
    """查电池健康度：当前实测可用容量、相对出厂的健康度、出厂折算的满电续航、衰减趋势。"""
    b = _get(f"/api/v1/cars/{CAR_ID}/battery")
    if not isinstance(b, dict) or "error" in b:
        return b
    out = {
        "现在还能装(度)": b.get("current_capacity_kwh"),
        "健康度%(相对出厂可用容量)": b.get("health_percent"),
        "新电池标称(度)": b.get("nominal_capacity_kwh"),
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
        out["覆盖天数"] = b.get("series_span_days")
        out["覆盖里程km"] = b.get("odometer_span_km")
    return out


@mcp.tool()
def teslamate_charges(limit: int = 10) -> dict:
    """查最近的充电记录（时间、地点、充入电量、起始/结束电量、费用）。"""
    data = _get(f"/api/v1/cars/{CAR_ID}/charges")
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        return data if isinstance(data, dict) else {"error": "返回格式异常"}
    rows = []
    for it in items[:max(1, int(limit))]:
        rows.append({
            "开始": it.get("start_date"),
            "地点": it.get("location_name"),
            "充入kWh": it.get("charge_kwh"),
            "开始%": it.get("start_battery_level"),
            "结束%": it.get("end_battery_level"),
            "时长分钟": it.get("duration_min"),
            "费用": it.get("cost"),
        })
    return {"共": len(items), "最近": rows}


@mcp.tool()
def teslamate_drives(limit: int = 10) -> dict:
    """查最近的行程记录（时间、起止、里程、时长、能耗、到达电量）。"""
    data = _get(f"/api/v1/cars/{CAR_ID}/drives")
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        return data if isinstance(data, dict) else {"error": "返回格式异常"}
    rows = []
    for it in items[:max(1, int(limit))]:
        rows.append({
            "开始": it.get("start_date"),
            "起点": it.get("start_location_name"),
            "终点": it.get("end_location_name"),
            "里程km": it.get("distance_km"),
            "时长分钟": it.get("duration_min"),
            "能耗kWh/100km": it.get("kwh_per_100km"),
            "到达电量%": it.get("end_battery_level"),
        })
    return {"共": len(items), "最近": rows}


@mcp.tool()
def teslamate_stats(period: str = "week") -> dict:
    """查用量统计。period 取 today / week / month / year。"""
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
        "行驶耗电(度)": summ.get("drive_kwh"),
        "充入电量(度)": summ.get("charge_kwh"),
        "行程数": summ.get("drive_count"),
        "充电次数": summ.get("charge_count"),
    }


@mcp.tool()
def teslamate_driving_profile() -> dict:
    """查驾驶画像：平顺分、各分项（急刹/急加/能耗/夜驾/速度档）、评分口径。"""
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
        "能耗Wh/km": s.get("wh_per_km_median_gross"),
        "夜驾占比%": s.get("night_pct_avg"),
        "口径": "分 = 100 − 急刹(≤30) − 急加(≤20) − 速度(≤20) − 能耗(≤15) − 夜驾(≤15)；名字叫「平顺」但只有 60 分是平顺相关",
    }


@mcp.tool()
def teslamate_stay_points(limit: int = 10) -> dict:
    """查常驻点（停得最多/最久的地方）。"""
    d = _get(f"/api/v1/cars/{CAR_ID}/stay_points")
    items = d.get("items") if isinstance(d, dict) else d
    if not isinstance(items, list):
        return d if isinstance(d, dict) else {"error": "返回格式异常"}
    rows = [{
        "地点": it.get("name") or it.get("location_name"),
        "停过次数": it.get("count") or it.get("stop_count"),
        "累计小时": it.get("total_hours"),
        "最长一次小时": it.get("max_hours"),
    } for it in items[:max(1, int(limit))]]
    return {"共": len(items), "地点": rows}


# --------------------------------------------------------------------------
# 控车
# --------------------------------------------------------------------------


@mcp.tool()
def teslamate_command_catalog() -> dict:
    """列出这台车支持的车控动作、每个动作的风险等级与需要的参数。

    风险等级：high（解锁/开合尾门/远程启动）/ medium / low。
    """
    d = _get(f"/api/v1/cars/{CAR_ID}/commands")
    if not isinstance(d, dict) or "error" in d:
        return d
    return {
        "代理就绪": d.get("proxy"),
        "动作": [{
            "动作名": a.get("action"),
            "说明": a.get("description"),
            "风险": a.get("risk"),
            "参数": a.get("params") or [],
            "会唤醒车": a.get("wakes_vehicle"),
        } for a in (d.get("actions") or [])],
    }


@mcp.tool()
def teslamate_vehicle_command(action: str, params: dict | None = None) -> dict:
    """给这台车下发一条命令（锁车/解锁/空调/充电上限/闪灯/鸣笛/尾门/远程启动…）。

    ⚠️ **高危动作**（door_unlock / actuate_trunk / remote_start_drive）
    请**先向用户确认**再调用。
    ⚠️ 车在休眠时命令发不出去 —— 会返回「车在休眠或离线，先唤醒再试」。
    ⚠️ 唤醒（wake_up）本身会耗电、也可能被 Tesla 计费。
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
        "车": res.get("vin"),
        "时间": res.get("at"),
    }
    if res.get("error"):
        out["错误"] = res.get("error")
    if res.get("hint"):
        out["提示"] = res.get("hint")
    if res.get("response"):
        out["车端回应"] = res.get("response")
    return out


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------

if __name__ == "__main__":
    # ⚠️ stdio 传输：stdout 是协议通道，**任何 print 都会破坏它** ⇒ 日志只走 stderr。
    import sys

    print(f"[teslamate-mcp] API={API_URL} car={CAR_ID} token={'有' if TOKEN else '缺'!s}",
          file=sys.stderr)
    mcp.run()
