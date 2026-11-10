# -*- coding: utf-8 -*-
"""
MCP 工具的**字段映射自检**。

## 为什么要有这个检查（一次真实事故）

「无缝接入 Hermes」的表面标准是「工具能调通、返回 JSON」。
但 v0.72.0 核验发现：9 个工具里有 **3 个返回的全是 null** ——

| 工具 | 取的字段 | API 真实字段 | 后果 |
|---|---|---|---|
| `teslamate_charges` | `location_name` / `charge_kwh` | `place` / `energy_added_kwh` | 地点、充入电量**全 null** |
| `teslamate_drives` | `start_location_name` / `kwh_per_100km` | `start_place` / `wh_per_km` | 起终点、能耗**全 null** |
| `teslamate_stay_points` | `items` | `points` | 整个工具**只回「返回格式异常」** |

⇒ **工具「能用」，但 Hermes 拿到的是残缺数据** —— 而且不报错、不崩，
人根本看不出来。这就是「能跑 ≠ 正确」。

⇒ 这份自检把「关键字段非 null」变成**可断言的契约**：
字段名一改，这里立刻红。

用法：`python mcp/check_tool_fields.py`（退出码 0 = 全过）
"""
from __future__ import annotations

import datetime
import sys

# ⚠️ **必须**：这台机器的默认编码是 GBK，Python 打印 '✓' / '✗' 会抛
#    UnicodeEncodeError ⇒ **扫描器自己崩了**，退出码非 0，
#    而调用方只看到「闸门失败」，根本不知道是「检查没过」还是「检查器崩了」。
#    这和 v0.77.0 在服务端闸门踩过的坑是同一个。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - 老 Python 没有这个 API
    pass

import teslamate_mcp as T

# (工具名, 参数, 结果里的路径, 说明)
# ⚠️ 只断言「**必然存在**」的字段：
#    费用（没配电价时合法为 null）、锁车状态（车在休眠时合法为 null）**不列**。
SPEC = [
    # ⚠️ **v0.122.0 补记**：「App 能看到的，Hermes 也要能取」（能力对账）。
    #    这两个是 App 有、而 Hermes 原来没有的端点 ⇒ 本轮补上。
    ("teslamate_drive_energy_profile", {}, "采样点数", "这趟行程的逐公里电量剖面"),
    ("teslamate_climb_model", {}, "上坡每米耗电(Wh/m)", "爬升能耗标定的上坡系数"),
    # ⚠️ **v0.123.0**：保养 / 轮胎工具。
    #    这两个**恒在**（哪怕车主还没填过基准）⇒ 可以断言。
    #    ⚠️ 「保养.还剩(km)」**不能**进 SPEC —— 车主没填基准 / 里程表读不到时
    #    那一项整个不存在（那是「不知道」，不是「还剩 0」）。
    ("teslamate_maintenance", {}, "已设置基准", "保养基准是否已设置"),
    ("teslamate_maintenance", {}, "里程表来源", "里程表来自车端还是未知"),
    ("teslamate_status", {}, "电量%", "电量"),
    ("teslamate_status", {}, "里程表km", "里程表"),
    # ⚠️ **v0.100.0**：用户反馈工具。
    #    这两个**恒在**（哪怕一条反馈都没有）⇒ 可以断言。
    #    ⚠️ 「反馈[0].内容」这种**列表元素**不能进 SPEC ——
    #    没有反馈时数组长度为 0 ⇒ 访问下标越界 ⇒ 自检假红。
    #    那部分交给下面的 check_feedback_items()（有数据才查）。
    ("teslamate_feedback", {}, "条数", "反馈条数"),
    ("teslamate_feedback", {}, "待评估", "还没评估的反馈条数"),
    ("teslamate_status", {}, "车机状态", "车机状态"),
    # ⚠️ **v0.80.0**：锁车状态的**来源**与**是否陈旧**。
    #
    # 这两个是**恒在**的（不是 null）⇒ 可以断言。
    # 少了它们，Hermes 会把「车断线前 9 小时的锁车状态」当「现在锁着」报出去。
    ("teslamate_status", {}, "锁车数据来源", "锁车状态来自遥测还是轮询"),
    ("teslamate_status", {}, "锁车状态是旧的", "这个锁车状态是不是车断线前的最后已知值"),
    ("teslamate_battery", {}, "现在还能装(度)", "实测可用容量"),
    ("teslamate_battery", {}, "健康度%(相对出厂可用容量)", "电池健康度"),
    ("teslamate_battery", {}, "满电标定续航·出厂折算(km)", "出厂折算续航"),
    ("teslamate_charges", {"limit": 3}, "最近[0].地点", "充电地点"),
    ("teslamate_charges", {"limit": 3}, "最近[0].充入kWh", "充入电量"),
    ("teslamate_charges", {"limit": 3}, "最近[0].开始%", "起始电量"),
    ("teslamate_charges", {"limit": 3}, "最近[0].id", "充电 id（要能接 charge_curve）"),
    # ⚠️ **v0.78.0**：充电结局工具。
    #    只断言「**必然存在**」的字段 ——
    #    「结束电量%」在**正在充电**时合法为 null（那时还没有结束电量），
    #    所以不列它；「场次id」同理（充电中场次还没定稿）。
    ("teslamate_charge_outcome", {}, "结局", "充电结局枚举"),
    ("teslamate_charge_outcome", {}, "结论", "结局结论（服务端给的中文原文）"),
    ("teslamate_charge_outcome", {}, "完整结论", "结局完整一句话"),
    # ⚠️ **v0.79.0**：故障码工具。
    #
    # 只断言「**必然存在**」的字段 ——
    # 本车当前 0 条故障码，所以「故障码[0].码」**不能**断言
    # （数组会是空的，断言就变成「数据不够」而不是「字段名对不上」）。
    # 断言的是「接入状态」这类恒在的字段 ——
    # 它们才是判断「没有故障码」是真是假的关键。
    ("teslamate_alerts", {}, "遥测已接入", "遥测是否接得上（没它就没法判断「无故障码」是真是假）"),
    ("teslamate_alerts", {}, "接入状态", "接入状态文案"),
    ("teslamate_alerts", {}, "活跃故障码", "活跃故障码数量"),
    ("teslamate_alerts", {}, "故障码总数", "故障码总数"),
    ("teslamate_alerts", {}, "车端此刻在线", "车端此刻是否连着"),
    ("teslamate_alerts", {}, "遥测流.收到条数", "遥测流计数"),
    # ⚠️ **v0.81.0**：实时胎压的**来源**。
    #    遥测给的值可能是「车断线前 9 小时」的 —— 不标来源就会被当成刚测的。
    ("teslamate_status", {}, "胎压数据来源", "实时胎压来自遥测还是轮询"),
    ("teslamate_status", {}, "胎压采集于", "这批胎压的采集时刻"),
    # ⚠️ **v0.81.0**：车况实况工具。
    #    只断言「**必然存在**」的字段 ——
    #    「胎压bar.左前」在车还没推过胎压时合法为 null（那时还没有这个字段）。
    ("teslamate_telemetry", {}, "车端此刻在线", "车端此刻是否连着"),
    ("teslamate_telemetry", {}, "遥测已接入", "遥测是否接得上"),
    ("teslamate_telemetry", {}, "数据是旧的", "这批数据是不是车断线前的最后已知值"),
    ("teslamate_telemetry", {}, "开着哪几扇窗", "车窗结论（三态，恒在）"),
    ("teslamate_telemetry", {}, "开着哪几个门", "门/舱盖结论（三态，恒在）"),
    ("teslamate_telemetry", {}, "哨兵", "哨兵模式状态"),
    # ⚠️ **v0.82.0**：车门 / 哨兵 / 车窗的**来源**。
    #
    # 只断言「**恒在**」的字段 ——
    # 「开着哪几个门」在没读到时合法为 null（那是「不知道」，不是「都关着」），
    # 断言它就变成「数据不够」而不是「字段名对不上」。
    # 断言的是**来源**（永远是个字符串）与**哨兵文案**（认不出来也有「状态未知」）。
    ("teslamate_status", {}, "车窗数据来源", "车窗结论来自遥测还是轮询"),
    ("teslamate_status", {}, "车门数据来源", "车门结论来自遥测还是轮询"),
    ("teslamate_status", {}, "哨兵数据来源", "哨兵结论来自遥测还是轮询"),
    ("teslamate_status", {}, "哨兵模式", "哨兵模式状态文案"),
    ("teslamate_drives", {"limit": 3}, "最近[0].起点", "起点"),
    ("teslamate_drives", {"limit": 3}, "最近[0].终点", "终点"),
    ("teslamate_drives", {"limit": 3}, "最近[0].能耗kWh/100km", "能耗"),
    ("teslamate_drives", {"limit": 3}, "最近[0].到达电量%", "到达电量"),
    ("teslamate_stay_points", {"limit": 3}, "地点[0].地点", "常驻点名称"),
    ("teslamate_stay_points", {"limit": 3}, "地点[0].停过次数", "停过次数"),
    ("teslamate_cost", {"period": "week"}, "直流(度)", "直流用电量"),
    ("teslamate_cost", {"period": "week"}, "行驶里程km", "行驶里程"),
    # ⚠️ **v0.93.0**：用量汇总工具（App 的「7 天汇总」卡就是它这一份数据）。
    #
    # 之前**一个都没断言** ⇒ `teslamate_stats` 里「行驶耗电(度)」写成
    # `summ.get("drive_kwh")`（服务端没这个键）也一直没人发现，
    # 飞书侧拿到的恒是 `None`，而 App 显示 15.2 度 ⇒ 两边对不上。
    #
    # 现在把 5 个字段全断言上：**键名写错 ⇒ 值为 null ⇒ 闸门红**。
    ("teslamate_stats", {"period": "week"}, "里程km", "周里程"),
    ("teslamate_stats", {"period": "week"}, "行驶耗电(度)", "行驶耗电"),
    ("teslamate_stats", {"period": "week"}, "充入电量(度)", "充入电量"),
    ("teslamate_stats", {"period": "week"}, "行程数", "行程数"),
    ("teslamate_stats", {"period": "week"}, "充电次数", "充电次数"),
    ("teslamate_energy_profile", {}, "速度档[0].毛耗电Wh/km", "能耗分档"),
    ("teslamate_driving_profile", {}, "平顺分(中位·合成指标)", "平顺分"),
    ("teslamate_command_catalog", {}, "动作[0].动作名", "动作目录"),
    # ⚠️ **v0.110.0**：充电统计工具（把 App 的区间能力接到 Hermes / 飞书）。
    #
    # 断言「窗口起点 / 窗口终点」—— 只说「近 30 天」不说边界，
    # 用户没法核对「是哪 30 天」（并行会话给的规格）。
    ("teslamate_charge_stats", {"period": "7d"}, "区间", "区间标签"),
    ("teslamate_charge_stats", {"period": "7d"}, "窗口起点", "区间起点"),
    ("teslamate_charge_stats", {"period": "7d"}, "窗口终点", "区间终点"),
    ("teslamate_charge_stats", {"period": "7d"}, "服务器时区", "分日用的时区"),
    ("teslamate_charge_stats", {"period": "7d"}, "场次", "区间内充电场次"),
    ("teslamate_charge_stats", {"period": "7d"}, "充入(度)", "区间内充入电量"),
    ("teslamate_charge_stats", {"period": "7d"}, "直流(度)", "直流充入电量"),
    ("teslamate_charge_stats", {"period": "7d"}, "直流场次", "直流场次"),
    ("teslamate_charge_stats", {"period": "7d"}, "交流(度)", "交流充入电量"),
    ("teslamate_charge_stats", {"period": "7d"}, "交流场次", "交流场次"),
    ("teslamate_charge_stats", {"period": "7d"}, "上次充满", "上次充满（没有也要说清楚）"),
    # ⚠️ 自然月这一档必须能用 —— 「这个月充了多少度」是飞书里最常问的一句，
    # 而「本月」**不是** 30 天（两者实测差 25.9%）。
    ("teslamate_charge_stats", {"period": "month"}, "窗口起点", "自然月的起点"),
    ("teslamate_charge_stats", {"period": "all"}, "场次", "全部历史的场次"),
    # ⚠️ **v0.111.0**：充电简报工具（给飞书的周期性报告）。
    #
    # 「标题」「正文」是**服务端渲染的原文** —— 本工具只转述（ADR #41）。
    # 少了任何一个，推送出去的就是一段空话。
    ("teslamate_charge_brief", {"period": "7d"}, "标题", "简报标题（服务端原文）"),
    ("teslamate_charge_brief", {"period": "7d"}, "正文", "简报正文（服务端原文）"),
    ("teslamate_charge_brief", {"period": "7d"}, "窗口起点", "简报窗口起点"),
    ("teslamate_charge_brief", {"period": "7d"}, "窗口终点", "简报窗口终点"),
    ("teslamate_charge_brief", {"period": "7d"}, "场次", "简报里的场次"),
    ("teslamate_charge_brief", {"period": "7d"}, "充入(度)", "简报里的充入电量"),
    ("teslamate_charge_brief", {"period": "7d"}, "上次充满", "上次充满（没有也要说清楚）"),
    # ⚠️ **v0.115.1**：能耗段（与充电**同一个窗口**）。
    ("teslamate_charge_brief", {"period": "7d"}, "行驶里程km", "简报里的行驶里程"),
    ("teslamate_charge_brief", {"period": "7d"}, "用电(度)", "简报里的用电量"),
    ("teslamate_charge_brief", {"period": "7d"}, "平均能耗(kWh/100km)", "简报里的平均能耗"),
    # ⚠️ **v0.116.0 补记**：电池健康段。
    #
    # ⚠️ 「当前 SOH%」在**没有出厂基准**（NOMINAL_BATTERY_KWH）时合法为 null
    # ⇒ **不列进 SPEC**（SPEC 只断言必然存在的字段）；
    #   它的**相等性**由 check_brief_battery_contract 单独断言。
    ("teslamate_charge_brief", {"period": "7d"}, "电池趋势·窗口内满充实测次数", "简报里的窗口内满充实测次数"),
    # ⚠️ **v0.118.0 补记**：真实可达续航（服务端算，App 与 Hermes 共用一份）。
    #    只断言**必然存在**的字段 —— 「真实可达(km)」在样本不足时合法为 null
    #    ⇒ 不进 SPEC（它的**相等性**由 check_range_estimate_contract 断言）。
    ("teslamate_range_estimate", {}, "一句话", "真实可达续航的一句话结论"),
    ("teslamate_range_estimate", {}, "怎么算的", "真实可达续航的算法口径"),
    ("teslamate_range_estimate", {}, "模型样本条数", "模型用了多少条行程"),
]


def resolve(obj, path):
    """按 `a.b[0].c` 取值。"""
    cur = obj
    for part in path.replace("[", ".[").split("."):
        if not part:
            continue
        if part.startswith("["):
            if not isinstance(cur, list):
                return None, "不是数组"
            idx = int(part.strip("[]"))
            if idx >= len(cur):
                return None, "数组长度 %d，访问下标 %d 越界" % (len(cur), idx)
            cur = cur[idx]
        else:
            if not isinstance(cur, dict):
                return None, "不是对象（实际 %r）" % type(cur).__name__
            if part not in cur:
                return None, "**没有这个键**"
            cur = cur[part]
    return cur, None


def check_command_catalog_params() -> list:
    """车控清单必须**每个动作都带**「参数怎么填」，且条数与「参数」一致。

    ⚠️ **v0.87.0**：这是「LLM 零失误调用」的**契约**。

    以前清单只给参数名 ⇒ 模型只能猜范围（比如把座椅加热的档位当成 1~3 而不是 0~3，
    于是「关」永远发不出去）。现在服务端给了完整规格，
    MCP 必须**原样透出** —— 少透一条，这个检查立刻红。
    """
    bad = []
    cat = T.teslamate_command_catalog()
    actions = cat.get("动作") or []
    if not actions:
        return ["车控清单里一个动作都没有"]
    with_params = 0
    for a in actions:
        name = a.get("动作名")
        if "参数怎么填" not in a:
            bad.append("%s 少了「参数怎么填」" % name)
            continue
        howto = a.get("参数怎么填")
        names = a.get("参数") or []
        if not isinstance(howto, list):
            bad.append("%s 的「参数怎么填」不是数组" % name)
            continue
        if len(howto) != len(names):
            bad.append("%s 的「参数怎么填」有 %d 条，参数名有 %d 个" % (name, len(howto), len(names)))
        for h in howto:
            if not h.get("说明"):
                bad.append("%s 的参数 %s 缺说明" % (name, h.get("参数名")))
        if names:
            with_params += 1
    if with_params == 0:
        bad.append("一个带参数的动作都没有 —— 「参数怎么填」这条链路是空的")
    return bad


def check_descriptions() -> list:
    """每个工具的 description 必须写明「什么时候用」。

    ⚠️ **v0.73.0**：MCP 协议里，工具的 `description` 是模型**挑选工具的唯一依据**。
    原来 13 个工具写的是「**这个工具返回什么**」，缺「**什么时候该用它**」——
    而「电量」同时出现在 status / battery / stats / cost 四个工具里，
    模型只能靠猜。

    ⇒ 统一要求：description 里必须出现「什么时候用」这一段。
    """
    bad = []
    for name in sorted(n for n in dir(T) if n.startswith("teslamate_")):
        fn = getattr(T, name)
        doc = (fn.__doc__ or "").strip()
        if "什么时候用" not in doc:
            bad.append("%-26s description 里没有「什么时候用」" % name)
        elif len(doc) < 60:
            bad.append("%-26s description 太短（%d 字），说不清用途" % (name, len(doc)))
    return bad


def check_cabin_contract() -> list:
    """座舱三态契约（v0.88.0）。

    ⚠️ 这条守的是**模型最容易犯的那个错**：
    把「没读到」当成「关着」。

    ⇒ `座舱.空调.是否开着` 必须是 **开着 / 关着 / 不知道** 三态字符串，
      **绝不能是 null** —— 模型拿到 null 很可能直接说「空调关着」，
      而真相是「车根本没推这批字段」。那是**假安全**（ADR #25）。

    ⚠️ 同时守**反幻觉**：方向盘加热的档位官方（中英两站 + proto +
      vehicle-command）**都没给范围** ⇒ 文案里**不许**出现
      「关 / 低档 / 中档 / 高档」这种自己编的档位名。
    """
    bad = []
    try:
        res = T.teslamate_status()
    except Exception as e:
        return ["teslamate_status 调用异常 %r" % (e,)]

    cab = res.get("座舱")
    if not isinstance(cab, dict):
        return ["座舱 不是字典（字段名对不上？）"]

    for key in ("数据来源", "采集于", "数据是旧的"):
        if key not in cab:
            bad.append("座舱 缺字段「%s」" % key)

    cl = cab.get("空调")
    if not isinstance(cl, dict):
        bad.append("座舱.空调 不是字典")
    else:
        v = cl.get("是否开着")
        if v not in ("开着", "关着", "不知道"):
            bad.append("座舱.空调.是否开着 必须是三态字符串（开着/关着/不知道），实际 %r" % (v,))

        # ⚠️ **v0.89.0**：出处与时刻必须如实给出（ADR #24）。
        for key in ("出处", "采集于", "数据是旧的"):
            if key not in cl:
                bad.append("座舱.空调 缺字段「%s」" % key)
        if cl.get("出处") not in ("未知", "车主动推（遥测）", "服务端轮询"):
            bad.append("座舱.空调.出处 不是已知来源：%r" % (cl.get("出处"),))

    # ⚠️ **v0.89.0**：**一个渠道一个答案** ——
    #    Hermes 读的「座舱.空调.是否开着」必须与 **App 读的顶层 is_climate_on**
    #    是同一个值。两者分家 = App 与飞书对同一件事给出相反的答案。
    try:
        raw = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
    except Exception as e:
        raw = None
        bad.append("取原始 status 失败 %r（无法核对「一个渠道一个答案」）" % (e,))
    if isinstance(raw, dict) and "error" not in raw:
        top = raw.get("is_climate_on")
        mine = (cl or {}).get("是否开着") if isinstance(cl, dict) else None
        expect = "不知道" if top is None else ("开着" if top else "关着")
        if mine != expect:
            bad.append("座舱.空调.是否开着=%r，但顶层 is_climate_on=%r ⇒ "
                       "App 与 Hermes 对同一件事给出了不同答案（ADR #24）"
                       % (mine, top))

    seats = cab.get("座椅加热")
    if not isinstance(seats, dict):
        bad.append("座舱.座椅加热 不是字典（应是「座位 → 档位」）")

    wh = cab.get("方向盘加热")
    if not isinstance(wh, dict):
        bad.append("座舱.方向盘加热 不是字典")
    else:
        text = wh.get("说明") or ""
        for forbidden in ("低档", "中档", "高档"):
            if forbidden in text:
                bad.append("座舱.方向盘加热.说明 含 %q —— 官方没定义这个范围，编档位名就是造事实"
                           % forbidden)
    return bad


def check_charge_brief_contract() -> list:
    """简报必须带窗口边界，且边界必须与 `teslamate_charge_stats` **一致**。

    ⚠️ 为什么不只问「有没有窗口」：简报与统计是**两个端点**，
    窗口一旦各算各的 ⇒ 飞书里那句「近 7 天充了 X 度」和 App 的卡片对不上
    ⇒ 用户以为数据在骗人（一个事实只能有一个说法）。
    ⇒ 这里断言的是**起点完全相等**，终点只允许分钟级差异
    （终点 = 生成时刻，两次调用天然差几秒）。
    """
    bad = []
    try:
        brief = T.teslamate_charge_brief("7d")
        stats = T.teslamate_charge_stats("7d")
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(brief, dict) or "错误" in brief:
        return ["充电简报取不到：%r" % (brief,)]
    for key in ("标题", "正文", "窗口起点", "窗口终点", "区间", "场次"):
        if key not in brief:
            bad.append("简报缺字段「%s」" % key)

    # 窗口**起点**必须完全相等（它是确定的：当天 00:00 − N 天）。
    if brief.get("窗口起点") != stats.get("窗口起点"):
        bad.append("简报的「窗口起点」= %r，与充电统计的 %r 不一致 ⇒ 两个端点各算各的窗口了"
                   % (brief.get("窗口起点"), stats.get("窗口起点")))

    # 窗口**终点** = 生成时刻 ⇒ 两次调用差几秒 ⇒ 只允许分钟级差异。
    try:
        b = datetime.datetime.strptime(str(brief.get("窗口终点")), "%Y-%m-%d %H:%M")
        s = datetime.datetime.strptime(str(stats.get("窗口终点")), "%Y-%m-%d %H:%M")
        gap = abs((b - s).total_seconds())
        if gap > 120:
            bad.append("简报与充电统计的窗口终点差 %d 秒（>2 分钟）" % int(gap))
    except Exception:
        bad.append("窗口终点不是可解析的时间：%r / %r"
                   % (brief.get("窗口终点"), stats.get("窗口终点")))

    # 正文里必须出现窗口起点的日期（只说「近 7 天」不说边界 ⇒ 用户没法核对）。
    day = str(brief.get("窗口起点") or "")[:10]
    body = str(brief.get("正文") or "")
    if day and day not in body:
        bad.append("简报正文里没有窗口起点日期 %s（正文=%r）" % (day, body[:120]))
    return bad


def check_brief_energy_contract() -> list:
    """简报的**能耗段**必须与**用量页的汇总**是同一个数（同一窗口、同一算法）。

    ⚠️ 为什么比「有没有能耗」更重要：简报与用量页是**两个端点**
    ⇒ 窗口一旦各算各的 ⇒ 飞书里「近 7 天用了 168 度」而 App 上是 172 度
    ⇒ 用户对不上账（ADR #38：一个事实只能有一个说法）。
    ⇒ 这里断言的是**相等**（带一点浮点容差），不是「有没有」。

    ⚠️ 窗口档位的对应关系（ADR #40 代价段）：
    简报 `7d`（`dayWindowStart(7)`）↔ 用量页 `period=week`（同一个起点）。
    """
    bad = []
    try:
        brief = T.teslamate_charge_brief("7d")
        stats = T.teslamate_charge_stats("7d")
        raw = T._get("/api/v1/cars/%s/stats?period=week" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(brief, dict) or "错误" in brief:
        return ["充电简报取不到：%r" % (brief,)]
    if not isinstance(raw, dict) or "error" in raw:
        return ["用量页取不到：%r" % (raw,)]
    summary = raw.get("summary") or {}
    for mk, sk, tol, label in (
        ("行驶里程km", "km", 0.5, "里程"),
        ("用电(度)", "kwh_used", 0.01, "用电"),
    ):
        b = brief.get(mk)
        s = summary.get(sk)
        if b is None or s is None:
            bad.append("简报没有「%s」或用量页没有 %s ⇒ 拿不到就别装有数" % (mk, sk))
        elif abs(float(b) - float(s)) > tol:
            bad.append("简报的%s = %s，与用量页汇总的 %s = %s 不一致 ⇒ 两个端点各算各的窗口了"
                       % (label, b, sk, s))
    if brief.get("窗口起点") != stats.get("窗口起点"):
        bad.append("简报与充电统计的窗口起点不一致：%r / %r"
                   % (brief.get("窗口起点"), stats.get("窗口起点")))
    return bad


def check_brief_battery_contract() -> list:
    """简报的**电池段**必须与 /battery 端点**是同一个 SOH 数**。

    ⚠️ 为什么比「有没有电池段」更重要：简报与电池页是**两个端点**
    ⇒ SOH 一旦各算各的 ⇒ 飞书里说「电池 83%」而 App 上是 78%
    ⇒ 用户以为数据在骗人（ADR #38：一个事实只能有一个说法）。

    ⚠️ **口径必须分开**：「当前 SOH%」是**窗口无关**的
    ⇒ 这里只断言它**等于** /battery 的 health_percent，
    **不**断言它落在窗口内（那是一个我们没算过的数）。

    ⚠️ 另外：正文里出现电池段时**必须**带「当前」二字
    ⇒ 否则窗口无关的 SOH 被说成了窗口内的数（编口径）。
    """
    bad = []
    try:
        brief = T.teslamate_charge_brief("7d")
        raw = T._get("/api/v1/cars/%s/battery" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(brief, dict) or "错误" in brief:
        return ["充电简报取不到：%r" % (brief,)]
    if not isinstance(raw, dict) or "error" in raw:
        return ["电池健康取不到：%r" % (raw,)]
    b = brief.get("电池健康·当前SOH%")
    s = raw.get("health_percent")
    if b is None or s is None:
        bad.append("简报没有「电池健康·当前SOH%%」或 /battery 没有 health_percent（%r / %r）"
                   % (b, s))
    elif abs(float(b) - float(s)) > 0.01:
        bad.append("简报的当前 SOH = %s，与 /battery 的 health_percent = %s 不一致 ⇒ 两个端点各算各的"
                   % (b, s))
    body = str(brief.get("正文") or "")
    if "电池健康" in body and "电池健康：当前" not in body:
        bad.append("简报正文里有电池段却没有「电池健康：当前」⇒ 窗口无关的 SOH 被说成了窗口内的")
    return bad



def check_battery_measurement_contract() -> list:
    """简报里的**测量时刻口径**必须与 /battery 的 measurement 是**同一句**。

    ⚠️ 为什么比「有没有测量时刻」更重要：三个出口（App 副标题 / 飞书工具 / 周期简报）
    各写各的措辞 ⇒ 用户看到「App 说 10-31 实测、飞书说的是另一句」⇒
    **同一个事实两套话**（ADR #38 / #47）。

    ⇒ 这里断言：**简报正文里含有** /battery 的 measurement.note_short 原文
    （不是「差不多」，是同一句 —— 措辞只有服务端一份）。
    """
    bad = []
    try:
        brief = T.teslamate_charge_brief("7d")
        raw = T._get("/api/v1/cars/%s/battery" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(brief, dict) or "错误" in brief:
        return ["充电简报取不到：%r" % (brief,)]
    if not isinstance(raw, dict) or "error" in raw:
        return ["电池健康取不到：%r" % (raw,)]
    meas = raw.get("measurement") or {}
    short = meas.get("note_short")
    note = meas.get("note")
    if not short:
        bad.append("/battery 没有 measurement.note_short ⇒ 测量时刻口径没有事实源")
        return bad
    if not note:
        bad.append("/battery 没有 measurement.note ⇒ 完整口径没有事实源")
    body = str(brief.get("正文") or "")
    if short not in body:
        bad.append("简报正文里没有测量口径 %r（正文：%r）⇒ 简报与 App 会说两套话"
                   % (short, body[:200]))
    return bad


def check_odometer_unit_contract() -> list:
    """车端里程表必须是**公里**，且必须与遥测原值换算后对得上。

    ⚠️ 为什么必须有这道：官方 `vehicle_state.odometer` 与遥测 `Odometer`
    给的是**英里**，而本项目全链路用公里。
    v0.127.0 之前本项目把英里当公里 ⇒ App 显示「里程表 63,055 公里」
    （真值约 101,467 公里），**保养超期判定也跟着少算了 38,412 公里**
    ⇒ 该保养时不报（漏报方向，比误报更隐蔽）。

    ⇒ 这里断言两件事：
      ① `/status` 的 `odometer_vehicle` 与遥测原值 × 1.609344 **对得上**；
      ② 它**不等于**遥测原值（否则就是「把英里当公里」那个 bug 复现）。
    """
    bad = []
    try:
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
        tv = T._get("/api/v1/cars/%s/telemetry_vehicle" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(st, dict) or "error" in st:
        return ["车况取不到：%r" % (st,)]
    if not isinstance(tv, dict) or "error" in tv:
        return ["遥测车况取不到：%r" % (tv,)]
    miles = None
    for f in (tv.get("fields") or []):
        if f.get("field") == "Odometer":
            miles = f.get("num")
            break
    if miles is None:
        return ["遥测里没有 Odometer 字段 ⇒ 没法核对车端里程表的单位"]
    km = st.get("odometer_vehicle")
    if km is None:
        return ["/status 的 odometer_vehicle 是 null ⇒ 车端里程表没有出口"]
    km = float(km)
    want = float(miles) * 1.609344
    if abs(km - want) / max(want, 1.0) > 0.10:
        bad.append("车端里程表 %.1f 公里 与遥测原值换算出来的 %.1f 公里（%s 英里）对不上 ⇒ 单位或换算有问题"
                   % (km, want, miles))
    if abs(km - float(miles)) / max(float(miles), 1.0) < 0.30:
        bad.append("车端里程表 %.1f 与遥测原值 %.1f 几乎相同 ⇒ 那个英里值被当成公里用了"
                   "（v0.127.0 修掉的 bug）" % (km, float(miles)))
    if not st.get("odometer_note"):
        bad.append("/status 没有 odometer_note ⇒ 界面上没有来源口径（ADR #41）")
    try:
        tool = T.teslamate_status()
        if isinstance(tool, dict) and "错误" not in tool:
            got = tool.get("里程表km")
            if got is not None and abs(float(got) - km) > 0.01:
                bad.append("MCP 的「里程表km」= %s，与服务端的 %.1f 不一致 ⇒ MCP 自己换算了一遍"
                           % (got, km))
    except Exception as e:
        bad.append("MCP 车况工具调用异常 %r" % (e,))
    return bad


def check_cabin_heat_contract() -> list:
    """座舱闷热的**判定与文案**必须与服务端是**同一份**。

    ⚠️ 为什么要有这道：暴晒后这台车的座舱峰值到过 **62.4℃**（车外才 34.0℃），
    「闷不闷热」是个**判定** ⇒ 判定只能服务端做（ADR #38），
    文案也只能服务端一份（ADR #41）⇒ App 与飞书必须说同一句话。

    ⇒ 这里断言：
      ① `/status` 的 cabin_heat 结构齐全（hot / inside_c / text）；
      ② 闷热时文案非空、且**带车内温度**；不闷热时文案为空（说的是没依据的话 = 错）；
      ③ MCP 的「座舱闷热」与服务端的 text **一字不差**（只透传）。
    """
    bad = []
    try:
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
        tool = T.teslamate_status()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(st, dict) or "error" in st:
        return ["车况取不到：%r" % (st,)]
    ch = st.get("cabin_heat")
    if ch is None:
        # 「不知道」是**合法状态**（车况数据太旧 ⇒ 服务端不给结论，ADR #38）
        # ⇒ 这时只要求 MCP 也**没有**闷热文案（两边必须一致）。
        if isinstance(tool, dict) and tool.get("座舱闷热"):
            bad.append("服务端说「不知道」而 MCP 却有闷热文案 ⇒ 两边不一致")
        return bad
    if not isinstance(ch, dict):
        bad.append("/status 的 cabin_heat 不是对象：%r" % (ch,))
        return bad
    for k in ("hot", "inside_c", "text"):
        if k not in ch:
            bad.append("/status 的 cabin_heat 缺键 %s" % k)
    hot = bool(ch.get("hot"))
    text = str(ch.get("text") or "")
    inside = ch.get("inside_c")
    if hot:
        if not text:
            bad.append("cabin_heat 说闷热却没有文案 ⇒ 界面上会空着")
        elif inside is not None and ("%.1f" % float(inside)) not in text:
            bad.append("闷热文案里没有车内温度 %s ⇒ 用户不知道有多热（%r）" % (inside, text))
    elif text:
        bad.append("不闷热却有文案（%r）⇒ 说的是没依据的话" % text)
    if isinstance(tool, dict) and "错误" not in tool:
        got = tool.get("座舱闷热")
        want = text or None
        if (got or None) != (want or None):
            bad.append("MCP 的「座舱闷热」= %r，与服务端的 %r 不一致 ⇒ 两边各说各话" % (got, want))
    return bad


def check_cabin_temp_freshness_contract() -> list:
    """车内温度读数的**新鲜度**必须由服务端说了算（ADR #38 / #35）。

    ⚠️ 为什么要有这道：positions.inside_temp 是**车醒着时才更新**的稀疏列
    （实测非空率仅 3.5%）⇒ 车睡着后它停在**停车那一刻**的值。
    原来服务端把它当「现在的车内温度」直接下发 ⇒ App 显示成一个**裸数字**
    （实测 2026-11-10：车停了 22 小时，界面仍写「车内 25.8℃」）
    ⇒ **假新鲜度 / 假安全**（车实际在外面暴晒一整天时，界面反而说「不闷热」）。

    ⇒ 这里断言：
      ① `/status` 有 inside_temp / inside_temp_last / inside_temp_at 三个键；
      ② **车不在线时 `inside_temp` 必须是 null**（不许把旧值当现在的读数）；
      ③ 车不在线时 `inside_temp_last` / `inside_temp_at` **仍要给出**（信息不丢）；
      ④ MCP 的「车内温℃」与服务端的 inside_temp **一字不差**（只透传）。
    """
    bad = []
    try:
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
        tool = T.teslamate_status()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(st, dict) or "error" in st:
        return ["车况取不到：%r" % (st,)]

    for k in ("inside_temp", "inside_temp_last", "inside_temp_at"):
        if k not in st:
            bad.append("/status 缺键 %s ⇒ 车内温度的新鲜度说不清" % k)

    online = str(st.get("state") or "").strip().lower() == "online"
    if not online:
        if st.get("inside_temp") is not None:
            bad.append("车不在线（state=%r）时 inside_temp 仍是 %r ⇒ "
                       "把停车那一刻的旧读数当成现在的车内温度（假新鲜度 / 假安全）"
                       % (st.get("state"), st.get("inside_temp")))
        if st.get("inside_temp_last") is None and st.get("inside_temp_at") is None:
            bad.append("车不在线时 inside_temp_last / inside_temp_at **都没有** ⇒ "
                       "「上次读到多少、什么时候」这条信息被整段丢掉了")

    if isinstance(tool, dict) and "错误" not in tool:
        got = tool.get("车内温℃")
        want = st.get("inside_temp")
        if (got if got is not None else None) != (want if want is not None else None):
            bad.append("MCP 的「车内温℃」= %r，与服务端的 %r 不一致 ⇒ 两边各说各话"
                       % (got, want))
        for k in ("车内温度上次读到℃", "车内温度采集于"):
            if k not in tool:
                bad.append("MCP 缺字段「%s」⇒ 报给用户时说不清这个读数有多旧" % k)
    return bad


def check_telemetry_stream_freshness_contract() -> list:
    """遥测「最后一条」的**新鲜度口径**必须与服务端是**同一份**。

    ⚠️ 为什么要有这道（2026-11-09 实测打出来的）：
    broker 会把留着的旧消息在订阅者上线时**重发**（retained），
    而 MQTT 载荷**不带车端采集时刻** ⇒ 「我们收到它的时刻」**不能**当
    「车端上报的时刻」⇒ API 容器一重启，流级「最后一条」就被刷新成假的
    （实测：车端 19:40 断连，流级显示 23:01 —— 那是容器重启补落的时刻）。

    ⇒ 新鲜度的判定**只能在服务端**做（ADR #38），MCP 只透传。

    ⇒ 这里断言：
      ① `/alerts` 的 stream 必须给出 last_message_at / last_message_stale /
         car_messages / replayed_messages（没有它们，模型只能拿假时刻去回答）；
      ② last_message_stale = true ⇒ last_message_at 必须在
         （不能空着却说「这是旧的」——那等于不知道）；
      ③ MCP 的「最后一条」「最后一条是旧的」与服务端**一字不差**（只透传）；
      ④ MCP 必须把「车端新消息条数」与「broker重放条数」**分开**给出。
    """
    bad = []
    try:
        al = T._get("/api/v1/cars/%s/alerts" % T.CAR_ID)
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
        tool = T.teslamate_alerts()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(al, dict) or "error" in al:
        return ["告警取不到：%r" % (al,)]
    stream = al.get("stream") or {}
    if not isinstance(stream, dict):
        return ["`/alerts` 的 stream 不是对象：%r" % (stream,)]
    for k in ("last_message_at", "last_message_stale", "car_messages", "replayed_messages"):
        if k not in stream:
            bad.append("`/alerts` 的 stream 缺键 %s ⇒ 飞书只能拿假时刻回答「遥测多久没更新」" % k)
    if bool(stream.get("last_message_stale")) and not stream.get("last_message_at"):
        bad.append("`/alerts` 说「最后一条是旧的」却没有给时刻 ⇒ 等于「不知道」却装成知道")

    # ⚠️ **两个入口必须给同一份事实**（v0.136.0 实测踩到过）：
    #   /status 里的遥测块与 /alerts 的 stream 如果各走各的 ⇒ 一个说「19:40 · 是旧的」，
    #   另一个把那个时刻整个丢掉、还把 stale 说成 false ⇒ 同一个事实两套话。
    if isinstance(st, dict) and "error" not in st:
        st_stream = ((st.get("telemetry") or {}).get("stream") or {})
        if isinstance(st_stream, dict) and st_stream:
            for k in ("last_message_at", "last_message_stale"):
                if st_stream.get(k) != stream.get(k):
                    bad.append("/status 的遥测流 %s = %r，与 /alerts 的 %r 不一致 ⇒ 两个入口不是同一份事实"
                               % (k, st_stream.get(k), stream.get(k)))
    if isinstance(tool, dict) and "错误" not in tool:
        flow = tool.get("遥测流") or {}
        if not isinstance(flow, dict):
            bad.append("MCP 的「遥测流」不是对象：%r" % (flow,))
        else:
            if flow.get("最后一条") != stream.get("last_message_at"):
                bad.append("MCP 的「最后一条」= %r，与服务端 %r 不一致 ⇒ 两边各说各话"
                           % (flow.get("最后一条"), stream.get("last_message_at")))
            if bool(flow.get("最后一条是旧的")) != bool(stream.get("last_message_stale")):
                bad.append("MCP 的「最后一条是旧的」与服务端不一致 ⇒ 陈旧标记被客户端改写了")
            for k in ("车端新消息条数", "broker重放条数"):
                if k not in flow:
                    bad.append("MCP 的「遥测流」缺 %s ⇒ 分不清「车端报的」与「broker 重放的」" % k)
    return bad


def check_status_staleness_contract() -> list:
    """车况读数的「是不是旧的」必须由**服务端给结论**（ADR #38）。

    ⚠️ 阈值（几小时算陈旧）**只在服务端一份** ⇒ 不能让模型拿着
    「数据滞后小时」自己跟某个阈值比（两端各判一次 ⇒ 迟早漂）。

    ⇒ 这里断言：`/status` 给出 data_stale 布尔，MCP 原样透传成「车况数据是旧的」。
    """
    bad = []
    try:
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
        tool = T.teslamate_status()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(st, dict) or "error" in st:
        return ["车况取不到：%r" % (st,)]
    if "data_stale" not in st:
        bad.append("`/status` 没有 data_stale ⇒ 陈旧阈值被漏到客户端了（ADR #38）")
    if isinstance(tool, dict) and "错误" not in tool:
        if "车况数据是旧的" not in tool:
            bad.append("MCP 的车况工具没有「车况数据是旧的」⇒ 模型只能自己跟阈值比")
        elif "data_stale" in st and bool(tool.get("车况数据是旧的")) != bool(st.get("data_stale")):
            bad.append("MCP 的「车况数据是旧的」与服务端 data_stale 不一致")
    return bad

def check_feedback_items() -> list:
    """反馈**每一条**都必须带上「id / 时间 / 内容 / 状态」。

    ⚠️ 为什么不能写进 SPEC：没有反馈时数组长度为 0 ⇒ 访问 `[0]` 越界 ⇒ 自检假红。
    ⇒ 这里**有数据才查**（跟「正在充电时跳过场次id」同一个道理：
    那不是「不合法」，是「现在没有」）。

    ⚠️ 字段名写错不会报错、只会静默变 null —— 本仓库的老教训。
    """
    bad = []
    try:
        res = T.teslamate_feedback(limit=5)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    items = res.get("反馈") or []
    if not items:
        return bad
    for it in items:
        for key in ("id", "时间", "内容", "状态"):
            if it.get(key) in (None, ""):
                bad.append("反馈条目缺 %s（%r）" % (key, it))
        # ⚠️ 截图字段允许为空（没附图），但**键必须在** ——
        #    少了它，调用方就没法区分「没图」与「我们没取到」。
        if "截图" not in it:
            bad.append("反馈条目少了「截图」键（%r）" % (it,))
    return bad


def check_brief_range_contract() -> list:
    """简报的**续航段**必须与 /range_estimate **是同一个数**（而且标明「当前」）。

    ⚠️ 为什么比「有没有续航段」更重要：简报与续航端点是**两处出口**
    ⇒ 数值一旦各算各的 ⇒ 飞书里说「能开 171 公里」而 App 上是 165 公里
    ⇒ 用户以为数据在骗人（ADR #38：一个事实只能有一个说法）。

    ⚠️ 另外：正文里出现续航段时**必须**带「当前」二字
    ⇒ 否则窗口无关的当前值被说成了窗口内的量（编口径，同 ADR #46）。
    """
    bad = []
    try:
        brief = T.teslamate_charge_brief("7d")
        est = T._get("/api/v1/cars/%s/range_estimate" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(brief, dict) or "错误" in brief:
        return ["充电简报取不到：%r" % (brief,)]
    if not isinstance(est, dict) or "error" in est:
        return ["续航端点取不到：%r" % (est,)]
    b = brief.get("续航·当前真实可达(公里)")
    e = est.get("true_range_km")
    if b is None or e is None:
        bad.append("简报没有「续航·当前真实可达(公里)」或续航端点没有 true_range_km ⇒ 拿不到就别装有数")
    elif abs(float(b) - float(e)) > 1e-6:
        bad.append("简报的当前真实可达 = %s，与续航端点的 true_range_km = %s 不一致 ⇒ 两个出口各算各的了"
                   % (b, e))
    body = str(brief.get("正文") or "")
    if b is not None:
        if "当前" not in body:
            bad.append("简报正文里的续航段没有「当前」二字 ⇒ 窗口无关的当前值会被读成窗口内的量")
        shown = "%.0f" % float(b)
        if shown not in body:
            bad.append("简报正文里没有续航数值 %s（正文=%r）" % (shown, body[:160]))
    return bad



def check_maintenance_contract() -> list:
    """保养 / 轮胎提醒必须**与服务端同源**（不许模型自己减）。

    ⚠️ 查五件事：
      ① 服务端必须给出「车端里程表」与它的来源（读不到时要能说清「不知道」）；
      ② MCP 必须**读到**服务端的判定，而不是自己拿别的数减一遍；
      ③ 「还剩多少公里」必须与「该在里程表多少时做」能对得上
         （remaining = due − odometer）⇒ 否则它自己编了一个数；
      ④ 文案必须**原样透传**服务端的那份（ADR #41：文案只有服务端一份）；
      ⑤ 基准存在服务端（按车隔离）⇒ 飞书问得到「我轮胎还能跑多少」。
    """
    bad = []
    try:
        rep = T._get("/api/v1/cars/%s/maintenance" % T.CAR_ID)
        m = T.teslamate_maintenance()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(rep, dict) or "error" in rep:
        return ["保养端点取不到：%r" % (rep,)]
    if not isinstance(m, dict) or "错误" in m:
        bad.append("MCP 的保养工具取不到：%r" % (m,))
        return bad

    # ① 来源必须透传（读不到时也要能说清「不知道」）
    if "里程表来源" not in m:
        bad.append("MCP 的保养工具没有「里程表来源」⇒ 读不到时无法区分「不知道」与「真的 0」")
    if "车端里程表(km)" not in m:
        bad.append("MCP 的保养工具没有「车端里程表」⇒ 判定依据不可核对")

    # ② ⑤ 基准必须在服务端（不是客户端各存一份）
    if "已设置基准" not in m:
        bad.append("MCP 的保养工具没有「已设置基准」⇒ 读不到基准时说不清原因")
    for key in ("上次保养时的里程(km)", "保养间隔(km)", "上次换胎时的里程(km)", "轮胎更换间隔(km)"):
        if key not in m:
            bad.append("MCP 的保养工具缺「%s」⇒ 飞书答不出基准是多少" % key)

    # ③④ 判定必须与服务端一致，且文案原样透传
    for name, field in (("保养", "service"), ("轮胎", "tire")):
        srv = rep.get(field)
        got = m.get(name)
        if not isinstance(srv, dict):
            # 服务端没给这一项（没设基准 / 里程表读不到）⇒ MCP 也不该编一个
            if got is not None:
                bad.append("服务端没给「%s」判定，MCP 却给出了 %r" % (name, got))
            continue
        if not isinstance(got, dict):
            bad.append("MCP 的工具没给出「%s」这一项" % name)
            continue
        for k in ("还剩(km)", "该在里程表(km)时做", "已超期", "快到了", "结论"):
            if k not in got:
                bad.append("MCP 的「%s」缺字段「%s」" % (name, k))
        # 文案必须逐字等于服务端那份（不许自己重写）
        if got.get("结论") != srv.get("text"):
            bad.append("MCP 的「%s」结论 = %r，与服务端原文 %r 不一致 ⇒ 文案有两份"
                       % (name, got.get("结论"), srv.get("text")))
        # 剩余公里数必须与「到期 − 当前里程表」对得上（否则它自己编了一个数）
        rem = got.get("还剩(km)")
        due = got.get("该在里程表(km)时做")
        odo = rep.get("odometer_km")
        if rem is not None and due is not None and odo is not None:
            want = float(due) - float(odo)
            if abs(float(rem) - want) > 1.0:
                bad.append("MCP 的「%s」还剩 %s，但「到期 %s − 里程表 %s」= %s ⇒ 判定不是服务端那份"
                           % (name, rem, due, odo, want))
    return bad


def check_range_reach_contract() -> list:
    """「还有 X 公里，够吗」的判定必须**与服务端同源**（不许模型自己减）。

    ⚠️ 查四件事：
      ① 服务端给了 to_km 时，reach_ok 必须与「真实可达 ≥ to_km」**完全一致**；
      ② 不够时必须给出**差多少公里**（不给 ⇒ 模型会自己编一个差值）；
      ③ 到得了才给「到达时约剩多少电」（到不了还给 ⇒ 会被读成到了就没电）；
      ④ MCP 侧必须**读到**服务端的判定（reach_ok），而不是自己算。
    """
    bad = []
    try:
        est = T._get("/api/v1/cars/%s/range_estimate?to_km=100" % T.CAR_ID)
        brief = T.teslamate_range_estimate(100)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(est, dict) or "error" in est:
        return ["续航端点取不到：%r" % (est,)]
    to_km = est.get("to_km")
    true_km = est.get("true_range_km")
    if to_km is None or true_km is None:
        bad.append("带 to_km 的请求没有回 to_km / true_range_km（参数没被接住）")
    else:
        want = float(to_km) <= float(true_km)
        got = est.get("reach_ok")
        if got is None or bool(got) != want:
            bad.append("reach_ok = %r，与「真实可达 %s ≥ 到 %s」不一致"
                       % (got, true_km, to_km))
        if not want and est.get("reach_shortfall_km") is None:
            bad.append("到不了却没给「还差多少公里」⇒ 模型会自己编一个差值")
        if want and est.get("arrive_soc_pct") is None:
            bad.append("到得了却没给「到达时约剩多少电」")
    if not isinstance(brief, dict) or "错误" in brief:
        bad.append("MCP 的续航工具取不到：%r" % (brief,))
    else:
        if brief.get("目的地(km)") is None:
            bad.append("MCP 的续航工具没带 to_km ⇒ 「够不够到」的判定没透出来")
        if brief.get("够不够到") is None:
            bad.append("MCP 没读到服务端的 reach_ok ⇒ 说明它在别处自己判")
    return bad



def check_drive_energy_profile_contract() -> list:
    """本轮补的两个工具必须**真的取得到数**（不是工具壳子）。

    ⚠️ 为什么查：上一轮踩过「函数定义了但没被调用 ⇒ 等于没写」，
    以及「纯函数测了、但没接到输出上」两类坑 ⇒ 这里查**运行时**。
    """
    bad = []
    try:
        prof = T.teslamate_drive_energy_profile()
        climb = T.teslamate_climb_model()
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(prof, dict) or "错误" in prof:
        bad.append("行程电量剖面取不到：%r" % (prof,))
    else:
        if not prof.get("采样点数"):
            bad.append("行程电量剖面没有采样点（库里没有行程？）")
        if prof.get("起点电量(%)") is None or prof.get("终点电量(%)") is None:
            bad.append("行程电量剖面缺起点/终点电量")
        if not prof.get("剖面"):
            bad.append("行程电量剖面没有逐公里点")
    if not isinstance(climb, dict) or "错误" in climb:
        bad.append("爬升标定取不到：%r" % (climb,))
    else:
        if climb.get("上坡每米耗电(Wh/m)") is None:
            bad.append("爬升标定缺上坡系数")
        if climb.get("回充可信") is None:
            bad.append("爬升标定缺「回充可信」标志（不可信时要用保守值）")
    return bad
def check_range_estimate_contract() -> list:
    """「真实可达续航」必须是**客户端算法**的复算结果（两个渠道同一个数）。

    ⚠️ 为什么这么严：App 首屏那个「真实可达」原先**只在客户端**算
    ⇒ 飞书问出来的是**另一个数**（或者根本问不出来）⇒ 用户以为两个渠道在骗人。
    ⇒ 服务端现在算同一份（同一批行程 + 同一套规则）
    ⇒ 这里**独立复算**一遍（照客户端 `ConsumptionModel` 的规则写）
    ⇒ 与服务端差一点点就报红 ⇒ 抓的是「服务端自己漂了」。

    ⚠️ **车在行驶时只比与车速无关的部分**：App 的参考车速取实时车速，
    服务端取最近一条 position 的车速 ⇒ 两者取的时刻差几秒 ⇒ 行驶中可能落在不同档
    ⇒ 那时只比样本数 / 各档中位数 / 巡航均速 / 残差 P90（这些与当前车速无关）。
    """
    bad = []
    try:
        est = T._get("/api/v1/cars/%s/range_estimate" % T.CAR_ID)
        drives = T._get("/api/v1/cars/%s/drives?limit=500&offset=0" % T.CAR_ID)
        st = T._get("/api/v1/cars/%s/status" % T.CAR_ID)
    except Exception as e:
        return ["调用异常 %r" % (e,)]
    if not isinstance(est, dict) or "error" in est:
        return ["续航估算取不到：%r" % (est,)]
    if not isinstance(drives, list) or not isinstance(st, dict):
        return ["行程 / 车况取不到：%r / %r" % (type(drives), type(st))]

    # ── 独立复算（照客户端规则）────────────────────────────────────
    usable = [d for d in drives
              if d.get("wh_per_km") is not None and d.get("distance_km") is not None
              and d["distance_km"] >= 20.0 and d.get("avg_speed_kph") is not None
              and d["avg_speed_kph"] > 0 and 50.0 <= d["wh_per_km"] <= 400.0]
    if len(usable) < 4:
        if est.get("true_range_km") is not None:
            bad.append("样本不足（%d 条可用行程）时服务端却给了真实可达 ⇒ 不该给数" % len(usable))
        return bad

    def median(vals):
        if not vals:
            return None
        s = sorted(vals)
        n = len(s)
        return s[n // 2] if n % 2 == 1 else (s[n // 2 - 1] + s[n // 2]) / 2.0

    edges = [0, 60, 90, 110, 130, float("inf")]
    buckets = []
    for i in range(len(edges) - 1):
        lo, hi = edges[i], edges[i + 1]
        vals = [d["wh_per_km"] for d in usable
                if d["avg_speed_kph"] >= lo and d["avg_speed_kph"] < hi]
        if len(vals) < 2:
            continue
        buckets.append({"lo": lo, "hi": hi, "wh": median(vals), "n": len(vals)})

    def bucket_for(sp):
        for b in buckets:
            if sp >= b["lo"] and sp < b["hi"]:
                return b, True
        best, bd = None, None
        for b in buckets:
            d2 = abs((b["lo"] - sp) if sp < b["lo"] else (sp - b["hi"] + 1))
            if bd is None or d2 < bd:
                best, bd = b, d2
        return best, False

    recent = [d for d in usable if d["distance_km"] >= 30.0 and d["avg_speed_kph"] > 0]
    recent.sort(key=lambda d: d["start_date"], reverse=True)
    cruise = median([d["avg_speed_kph"] for d in recent[:5]])
    if cruise is None:
        cruise = median([d["avg_speed_kph"] for d in usable])
    if cruise is None:
        cruise = 60.0

    resid = []
    for d in usable:
        if d["distance_km"] < 100.0:
            continue
        b, _ = bucket_for(d["avg_speed_kph"])
        if b is None:
            continue
        resid.append(abs(d["wh_per_km"] - b["wh"]))
    p90 = None
    if len(resid) >= 10:
        s = sorted(resid)
        rank = max(1, min((len(s) * 9 + 9) // 10, len(s)))
        p90 = s[rank - 1]

    # ── 与服务端逐条比 ───────────────────────────────────────────
    if est.get("sample_count") != len(usable):
        bad.append("样本数不一致：服务端 %r vs 复算 %d" % (est.get("sample_count"), len(usable)))
    parked = (st.get("speed") or 0) <= 5
    srv_ref = float(est.get("reference_speed_kph") or 0)
    if parked and abs(srv_ref - cruise) > 1e-9:
        bad.append("参考车速不一致：服务端 %r vs 复算巡航均速 %r（车是停着的，两者应当相同）"
                   % (srv_ref, cruise))
    srv_wh = est.get("wh_per_km")
    b, _ = bucket_for(srv_ref)
    if b is not None and srv_wh is not None and abs(float(srv_wh) - b["wh"]) > 1e-6:
        bad.append("该档的实测 Wh/km 不一致：服务端 %r vs 复算 %r ⇒ 分档或中位数漂了"
                   % (srv_wh, b["wh"]))
    srv_p90 = est.get("residual_p90_wh_per_km")
    if (srv_p90 is None) != (p90 is None):
        bad.append("残差 P90 有无不一致：服务端 %r vs 复算 %r" % (srv_p90, p90))
    elif srv_p90 is not None and abs(float(srv_p90) - p90) > 1e-6:
        bad.append("残差 P90 不一致：服务端 %r vs 复算 %r" % (srv_p90, p90))

    # ── 结论（车停着时可比）──────────────────────────────────────
    if parked and abs(srv_ref - cruise) <= 1e-9:
        rated = st.get("rated_range_km")
        if rated is None:
            rated = st.get("ideal_range_km")
        temp = st.get("outside_temp")
        batt = st.get("battery_level")
        if rated and b is not None:
            tf = 1.0 + min(0.10, 0.006 * max(0.0, temp - 22.0)) if temp is not None else 1.0
            want = rated * 146.0 / (b["wh"] * tf) * 0.90
            got = est.get("true_range_km")
            if got is None or abs(float(got) - want) > 1e-6:
                bad.append("真实可达不一致：服务端 %r vs 复算 %r ⇒ 服务端与客户端不是同一个数"
                           % (got, want))
            if batt is not None and batt > 20:
                want_f = rated * 146.0 / (b["wh"] * tf) * ((batt - 20) / batt)
                got_f = est.get("floor_range_km")
                if got_f is None or abs(float(got_f) - want_f) > 1e-6:
                    bad.append("保底里程不一致：服务端 %r vs 复算 %r" % (got_f, want_f))
    return bad


def main() -> int:
    print("=== MCP 工具字段映射自检 ===")
    bad = []
    for fn, args, path, desc in SPEC:
        try:
            res = getattr(T, fn)(**args)
        except Exception as e:
            bad.append("%-26s %-24s 调用异常 %r" % (fn, path, e))
            print("  ✗ %-26s %-24s 调用异常 %r" % (fn, path, e))
            continue
        val, err = resolve(res, path)
        if err:
            bad.append("%-26s %-24s %s" % (fn, path, err))
            print("  ✗ %-26s %-24s %s" % (fn, path, err))
        elif val is None:
            bad.append("%-26s %-24s 值为 null（字段名对不上？）" % (fn, path))
            print("  ✗ %-26s %-24s 值为 null（字段名对不上？）" % (fn, path))
        else:
            print("  ✓ %-26s %-24s %s" % (fn, path, str(val)[:40]))

    # 端到端：charges → charge_curve 要接得上
    try:
        ch = T.teslamate_charges(limit=1)
        cid = (ch.get("最近") or [{}])[0].get("id")
        if cid:
            cc = T.teslamate_charge_curve(cid)
            if cc.get("曲线"):
                print("  ✓ %-26s %-24s 接得上（%d 个采样点）" % ("teslamate_charge_curve", "由 charges.id 串起来", cc.get("点数") or 0))
            else:
                bad.append("charge_curve 拿不到曲线")
                print("  ✗ charge_curve 拿不到曲线")
        else:
            bad.append("charges 没给出 id，charge_curve 接不上")
            print("  ✗ charges 没给出 id，charge_curve 接不上")
    except Exception as e:
        bad.append("charges→charge_curve 串联异常 %r" % e)
        print("  ✗ charges→charge_curve 串联异常 %r" % e)

    # 串联断言：结局里的「场次id」要能直接喂给 charge_curve。
    #
    # ⚠️ 正在充电时场次还没定稿（charge_id 为 null），此时跳过 ——
    #    那不是「接不上」，是「还没结束」。
    try:
        oc = T.teslamate_charge_outcome()
        ocid = oc.get("场次id")
        if ocid:
            cc = T.teslamate_charge_curve(ocid)
            if cc.get("曲线"):
                print("  ✓ %-26s %-24s 接得上（%d 个采样点）" % ("teslamate_charge_curve", "由结局.场次id 串起来", cc.get("点数") or 0))
            else:
                bad.append("charge_curve 拿不到曲线（结局给的 id）")
                print("  ✗ charge_curve 拿不到曲线（结局给的 id）")
        else:
            print("  · %-26s %-24s 跳过（正在充电，场次未定稿）" % ("teslamate_charge_curve", "由结局.场次id 串起来"))
    except Exception as e:
        bad.append("charge_outcome→charge_curve 串联异常 %r" % e)
        print("  ✗ charge_outcome→charge_curve 串联异常 %r" % e)

    # description 体检：模型靠它选工具
    cat_bad = check_command_catalog_params()
    for b in cat_bad:
        print("  ✗ %s" % b)
    if not cat_bad:
        print("  ✓ %-26s %-24s 每个动作都带「参数怎么填」" % ("teslamate_command_catalog", "参数规格透出"))
    bad.extend(cat_bad)

    cab_bad = check_cabin_contract()
    for b in cab_bad:
        print("  ✗ %s" % b)
    if not cab_bad:
        print("  ✓ %-26s %-24s 三态字符串 + 不编方向盘档位名" % ("teslamate_status", "座舱契约"))
    bad.extend(cab_bad)

    desc_bad = check_descriptions()
    for b in desc_bad:
        print("  ✗ %s" % b)
    if not desc_bad:
        names = sorted(n for n in dir(T) if n.startswith("teslamate_"))
        print("  ✓ %-26s %-24s %d 个工具都写了「什么时候用」" % ("（description）", "工具用途说明", len(names)))
    bad.extend(desc_bad)

    brief_bad = check_charge_brief_contract()
    for b in brief_bad:
        print("  ✗ %s" % b)
    if not brief_bad:
        print("  ✓ %-26s %-24s 窗口边界与充电统计一致、正文带窗口起点"
              % ("teslamate_charge_brief", "简报契约"))
    bad.extend(brief_bad)

    energy_bad = check_brief_energy_contract()
    for b in energy_bad:
        print("  ✗ %s" % b)
    if not energy_bad:
        print("  ✓ %-26s %-24s 能耗段与用量页汇总是同一个数" % ("teslamate_charge_brief", "能耗契约"))
    bad.extend(energy_bad)

    battery_bad = check_brief_battery_contract()
    for b in battery_bad:
        print("  ✗ %s" % b)
    if not battery_bad:
        print("  ✓ %-26s %-24s 当前 SOH 与 /battery 是同一个数、正文标明「当前」"
              % ("teslamate_charge_brief", "电池契约"))
    bad.extend(battery_bad)

    range_brief_bad = check_brief_range_contract()
    for b in range_brief_bad:
        print("  ✗ %s" % b)
    if not range_brief_bad:
        print("  ✓ %-26s %-24s 当前真实可达与 /range_estimate 是同一个数、正文标明「当前」"
              % ("teslamate_charge_brief", "续航契约"))
    bad.extend(range_brief_bad)


    reach_bad = check_range_reach_contract()
    for b in reach_bad:
        print("  ✗ %s" % b)
    if not reach_bad:
        print("  ✓ %-26s %-24s 「还有 X 公里够吗」与服务端同一个判定"
              % ("teslamate_range_estimate", "目的地可达性契约"))
    bad.extend(reach_bad)


    drive_energy_bad = check_drive_energy_profile_contract()
    for b in drive_energy_bad:
        print("  ✗ %s" % b)
    if not drive_energy_bad:
        print("  ✓ %-26s %-24s 这趟电怎么掉的 / 爬升标定都取得到数"
              % ("teslamate_drive_energy_profile", "能力对账契约"))
    bad.extend(drive_energy_bad)

    range_bad = check_range_estimate_contract()
    for b in range_bad:
        print("  ✗ %s" % b)
    if not range_bad:
        print("  ✓ %-26s %-24s 与客户端算法逐位一致（同一个数）"
              % ("teslamate_range_estimate", "续航契约"))
    bad.extend(range_bad)

    maint_bad = check_maintenance_contract()
    for b in maint_bad:
        print("  ✗ %s" % b)
    if not maint_bad:
        print("  ✓ %-26s %-24s 保养 / 轮胎提醒与服务端同一个判定、同一句话"
              % ("teslamate_maintenance", "保养契约"))
    bad.extend(maint_bad)

    meas_bad = check_battery_measurement_contract()
    for b in meas_bad:
        print("  ✗ %s" % b)
    if not meas_bad:
        print("  ✓ %-26s %-24s 简报与电池页说**同一句**测量时刻口径"
              % ("teslamate_charge_brief", "测量口径契约"))
    bad.extend(meas_bad)

    odo_bad = check_odometer_unit_contract()
    for b in odo_bad:
        print("  ✗ %s" % b)
    if not odo_bad:
        print("  ✓ %-26s %-24s 车端里程表是**公里**（官方原值是英里，服务端换算）"
              % ("teslamate_status", "里程表单位契约"))
    bad.extend(odo_bad)

    cab_bad = check_cabin_heat_contract()
    for b in cab_bad:
        print("  ✗ %s" % b)
    if not cab_bad:
        print("  ✓ %-26s %-24s 座舱闷热的判定与文案和服务端是**同一份**"
              % ("teslamate_status", "座舱闷热契约"))
    bad.extend(cab_bad)

    ct_bad = check_cabin_temp_freshness_contract()
    for b in ct_bad:
        print("  ✗ %s" % b)
    if not ct_bad:
        print("  ✓ %-26s %-24s 车内温度**只给当前读数**，旧读数不冒充现在"
              % ("teslamate_status", "座舱温度新鲜度契约"))
    bad.extend(ct_bad)

    tel_bad = check_telemetry_stream_freshness_contract()
    for b in tel_bad:
        print("  ✗ %s" % b)
    if not tel_bad:
        print("  ✓ %-26s %-24s 遥测「最后一条」是**车端真实时刻**，不是收到时刻"
              % ("teslamate_alerts", "遥测新鲜度契约"))
    bad.extend(tel_bad)

    stale_bad = check_status_staleness_contract()
    for b in stale_bad:
        print("  ✗ %s" % b)
    if not stale_bad:
        print("  ✓ %-26s %-24s 车况「是不是旧的」由服务端给结论（阈值不外泄）"
              % ("teslamate_status", "陈旧结论契约"))
    bad.extend(stale_bad)

    fb_bad = check_feedback_items()
    for b in fb_bad:
        print("  ✗ %s" % b)
    if not fb_bad:
        print("  ✓ %-26s %-24s 每条反馈都带 id / 时间 / 内容 / 状态" % ("teslamate_feedback", "条目字段"))
    bad.extend(fb_bad)

    print("\n  ⇒ %s（%d 项不合规）" % ("通过" if not bad else "有问题", len(bad)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
