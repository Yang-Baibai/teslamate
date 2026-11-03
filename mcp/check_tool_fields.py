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


    range_bad = check_range_estimate_contract()
    for b in range_bad:
        print("  ✗ %s" % b)
    if not range_bad:
        print("  ✓ %-26s %-24s 与客户端算法逐位一致（同一个数）"
              % ("teslamate_range_estimate", "续航契约"))
    bad.extend(range_bad)

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
