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
    ("teslamate_status", {}, "车机状态", "车机状态"),
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
    ("teslamate_drives", {"limit": 3}, "最近[0].起点", "起点"),
    ("teslamate_drives", {"limit": 3}, "最近[0].终点", "终点"),
    ("teslamate_drives", {"limit": 3}, "最近[0].能耗kWh/100km", "能耗"),
    ("teslamate_drives", {"limit": 3}, "最近[0].到达电量%", "到达电量"),
    ("teslamate_stay_points", {"limit": 3}, "地点[0].地点", "常驻点名称"),
    ("teslamate_stay_points", {"limit": 3}, "地点[0].停过次数", "停过次数"),
    ("teslamate_cost", {"period": "week"}, "直流(度)", "直流用电量"),
    ("teslamate_cost", {"period": "week"}, "行驶里程km", "行驶里程"),
    ("teslamate_energy_profile", {}, "速度档[0].毛耗电Wh/km", "能耗分档"),
    ("teslamate_driving_profile", {}, "平顺分(中位·合成指标)", "平顺分"),
    ("teslamate_command_catalog", {}, "动作[0].动作名", "动作目录"),
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
    desc_bad = check_descriptions()
    for b in desc_bad:
        print("  ✗ %s" % b)
    if not desc_bad:
        names = sorted(n for n in dir(T) if n.startswith("teslamate_"))
        print("  ✓ %-26s %-24s %d 个工具都写了「什么时候用」" % ("（description）", "工具用途说明", len(names)))
    bad.extend(desc_bad)

    print("\n  ⇒ %s（%d 项不合规）" % ("通过" if not bad else "有问题", len(bad)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
