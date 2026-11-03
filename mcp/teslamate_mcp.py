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

import datetime
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
# 查数据
# --------------------------------------------------------------------------


@mcp.tool()
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
        # ⚠️ 这两个是 **v0.80.0** 新增的 —— 少了它们，
        # 9 小时前的锁车状态会被当成「现在锁着」报出去。
        "锁车数据来源": _source_cn(s.get("locked_source")),
        "锁车状态是旧的": bool(s.get("locked_stale")),
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
        # ⚠️ v0.84.0：固件版本 + 有没有可装的固件。
        # 「待安装版本」空串 = **不知道**（不是「没有更新」）——
        # 所以另给一个布尔「有可装固件」，别让调用方自己比字符串。
        "当前固件": s.get("car_version"),
        "待安装固件": s.get("software_update_version"),
        "有可装固件": bool(s.get("update_pending")),
        # ⚠️ **v0.88.0**：座舱状态（空调 / 座椅加热 / 方向盘加热）。
        #    v0.87.0 让人能「设」，这一轮补上「读回」。
        "座舱": _cabin_cn(s.get("cabin")),
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
        out["覆盖天数"] = b.get("series_span_days")
        out["覆盖里程km"] = b.get("odometer_span_km")
    return out


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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


@mcp.tool()
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
            "告警条数": stream.get("alerts"),
            "错误条数": stream.get("errors"),
            "最后一条": stream.get("last_message_at"),
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


@mcp.tool()
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


@mcp.tool()
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


# --------------------------------------------------------------------------
# 入口
# --------------------------------------------------------------------------

if __name__ == "__main__":
    # ⚠️ stdio 传输：stdout 是协议通道，**任何 print 都会破坏它** ⇒ 日志只走 stderr。
    import sys

    print(f"[teslamate-mcp] API={API_URL} car={CAR_ID} token={'有' if TOKEN else '缺'!s}",
          file=sys.stderr)
    mcp.run()
