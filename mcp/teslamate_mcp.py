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


def _tpms_pressure(v: Any) -> Any:
    """实时胎压：四个轮子的形状**都是对象**，统一取 pressure。"""
    if isinstance(v, dict):
        return v.get("pressure")
    return v


# --------------------------------------------------------------------------
# 查数据
# --------------------------------------------------------------------------


@mcp.tool()
def teslamate_status() -> dict:
    """查这台车**现在**的状态：电量、续航、锁没锁、位置、内外温、实时胎压、车醒没醒。

    **什么时候用**：用户问「车锁了吗」「还有多少电」「车在哪」「外面多少度」
    「胎压正常吗」「车醒着吗」—— 都归它。

    ⚠️ 车在休眠时锁车与胎压会显示「未知」—— 那是**没读到**，不是车没锁。
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
            # ⚠️ **v0.72.0 修过形状**：四个轮子**全是对象**（含 pressure / alert 等），
            # 原来只对左前做了 `.get("pressure")`，另外三个直接把整个 dict 塞进去了。
            "左前": _tpms_pressure(s.get("tpms_live_fl")),
            "右前": _tpms_pressure(s.get("tpms_live_fr")),
            "左后": _tpms_pressure(s.get("tpms_live_rl")),
            "右后": _tpms_pressure(s.get("tpms_live_rr")),
        },
        "胎压告警": s.get("tpms_live_alert_detail") or s.get("tpms_alert_detail"),
        "数据滞后小时": s.get("data_age_hours"),
        "最后采集于": s.get("updated_at"),
    }


@mcp.tool()
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
    """查**最近的充电记录**：时间、地点、充入多少度、起止电量、费用、峰值/平均功率。

    **什么时候用**：用户问「上次在哪充的」「充了多少度」「充电花了多少钱」
    「这个月充了几次」—— 都归它。

    ⚠️ 要看某一场的功率曲线，用返回的 `id` 接着调 `teslamate_charge_curve`。
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


@mcp.tool()
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


@mcp.tool()
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
        "行驶耗电(度)": summ.get("drive_kwh"),
        "充入电量(度)": summ.get("charge_kwh"),
        "行程数": summ.get("drive_count"),
        "充电次数": summ.get("charge_count"),
    }


@mcp.tool()
def teslamate_driving_profile() -> dict:
    """查**驾驶画像**：平顺分，以及急刹 / 急加 / 能耗 / 夜驾 / 速度档各扣了多少分。

    **什么时候用**：用户问「我开车平顺吗」「急刹多不多」「这个分怎么算的」
    —— 都归它。

    ⚠️ 名字叫「平顺分」，但**只有 60 分是平顺相关**（急刹 30 + 急加 20 + 速度 10），
    另 40 分来自能耗与夜驾 —— 别当成纯粹的平顺度量。
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
        "口径": "分 = 100 − 急刹(≤30) − 急加(≤20) − 速度(≤20) − 能耗(≤15) − 夜驾(≤15)；名字叫「平顺」但只有 60 分是平顺相关",
    }


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
def teslamate_energy_profile() -> dict:
    """查**能耗-车速曲线**：每个速度档的平均 Wh/km（车轮功率 / 毛耗电）与跑了多少公里。

    **什么时候用**：用户问「我开多快最省电」「高速费电吗」「多少码最经济」
    —— 都归它。看 `毛耗电Wh/km` 最低的那个速度档。

    ⚠️ 车轮 Wh/km 不含传动损耗与回充抵扣；**毛耗电**含回充抵扣，更接近真实电表。
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
    out["口径"] = "车轮 Wh/km 不含传动损耗与回充抵扣；毛耗电含回充抵扣，更接近真实电表"
    return out


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
def teslamate_command_catalog() -> dict:
    """列出这台车**支持哪些车控动作**，每个动作的风险等级与需要的参数。

    **什么时候用**：用户问「车能做什么」「有没有鸣笛」「怎么远程启动」；
    或者你要下发动作前**先查参数名**（参数拼错会失败）。

    风险等级：high（解锁车门 / 开合尾门 / 远程启动）/ medium / low。
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
    """给这台车**下发一条命令**：锁车 / 解锁 / 空调 / 充电上限 / 闪灯 / 鸣笛 / 尾门 / 远程启动 / 唤醒…

    **什么时候用**：用户**明确要求**某个动作时（「把车锁了」「打开空调」「鸣一下笛」）。

    ⚠️ **高危动作**（door_unlock / actuate_trunk / remote_start_drive）
    请**先向用户确认**再调用。
    ⚠️ 动作名与参数名先查 `teslamate_command_catalog`，别猜。
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
