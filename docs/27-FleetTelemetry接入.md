# Fleet Telemetry 接入（v0.79.0）

> 一句话：**让车主动把数据推给我们**，而不是我们去轮询把它唤醒。
> 这是拿到 `BMS_*` / `DI_*` / `VCFRONT_*` / `CP_*` 故障码的唯一途径
> —— `vehicle_data` 端点里根本没有这些字段。

## 为什么值得做

| | 轮询 vehicle_data（现状） | Fleet Telemetry |
|---|---|---|
| 车况新鲜度 | 2 分钟级，且要唤醒 | 车在线时近实时，**不唤醒** |
| 唤醒次数 | 每轮一次 | 睡着时 0 次 |
| 故障码 | **拿不到** | 有（alerts 记录） |
| 尾流费 | 按唤醒计 | 按信号量计，远低 |

官方口径：`https://developer.tesla.cn/docs/fleet-api/fleet-telemetry`

## 架构

```
   Tesla 车
      │  WSS + mTLS（车端证书由 Tesla CA 签发）
      ▼
   Tailscale Funnel  tcp://<主机名>.<tailnet域>.ts.net:8443   ← raw TCP 透传
      │  （TLS 端到端，**不**在 Tailscale 侧终止 ⇒ 客户端证书原样到达）
      ▼
   fleet-telemetry 容器  :4443   （终止 mTLS，校验 Tesla CA）
      │  logger dispatcher → 容器日志（JSON）
      ▼
   mosquitto（本机已有）→ Go API → Postgres → App / Hermes
```

### 为什么 443 和 8443 要分开

- **443**：`https://<主机名>.<tailnet域>.ts.net/.well-known/appspecific/com.tesla.3p.public-key.pem`
  —— 这是**应用公钥托管**，Tesla 云来拉，**不带客户端证书**。
  ⇒ 必须走普通 HTTPS（Funnel 终止 TLS）。
- **8443**：遥测数据入口，车端带 Tesla CA 签发的客户端证书。
  ⇒ 必须端到端 TLS ⇒ **不能用普通反代**（反代会剥掉客户端证书）。

Funnel 允许的公网端口只有 `443` / `8443` / `10000`，所以 8443 是唯一可选。

### 证书

- 接收端用 **Tailscale 签的 Let's Encrypt 证书**（`${CERTS_DIR}\server.crt`）。
  ⚠️ 必须公开可信 —— raw TCP 模式下 Tesla 直接看到这张证书，自签会被拒。
- ⚠️ **90 天过期** ⇒ `scripts/renew-telemetry-cert.ps1` 每周续期 + 重启容器
  （bind mount 挂的是文件，`tailscale cert` 写新文件后容器还握着旧 inode）。
- 证书目录**故意放在 OneDrive 外面** —— 私钥不该被同步。

## 已就绪的资产（2026-10-28 实测）

| 资产 | 状态 |
|---|---|
| 应用公钥托管 | ✅ `https://<主机名>.<tailnet域>.ts.net/.well-known/appspecific/com.tesla.3p.public-key.pem` → HTTP 200 / 177 B PEM / Range 206 |
| 私钥 ↔ 托管公钥配对 | ✅ kid 哈希一致（`d8KVwfrQ4t0wwRAKdeDGzuJW…`） |
| command-proxy | ✅ `tesla/vehicle-command:latest` 在跑（4443），车控已验证走通 |
| mosquitto | ✅ 本机已有（1883），遥测数据有现成落点 |
| fleet-telemetry 镜像 | ✅ `tesla/fleet-telemetry:latest`（**Go 写的**，与本项目同栈） |
| Funnel 443（HTTPS） | ✅ `/.well-known` 指向 `C:\Users\<用户名>\tesla-key` |
| Funnel 8443（raw TCP） | ✅ `tcp://…ts.net:8443 → tcp://127.0.0.1:4443` |
| 服务端证书 | ✅ 公网可信（`Verify return code: 0 (ok)`，Let's Encrypt YE2） |

## 接收端配置

`fleet-telemetry/config.json`：

- `records`: `alerts` / `errors` / `V` / `connectivity` → **`mqtt`**
  ⇒ 数据落到本机 mosquitto（`tcp://mosquitto:1883`），主题前缀 `tesla_telemetry`。
- `mqtt.retained: true` ⇒ 订阅方重启后**立刻**拿到当前状态（不用等下一条）。
- `transmit_decoded_records: true` ⇒ 输出 JSON 而不是 protobuf。
- `tls`: 指向 `/certs/server.crt` / `/certs/server.key`。
  ⚠️ **这个服务是 mTLS-only 的**，没有 `tls` 段会直接 panic 退出。

### MQTT 主题结构（官方 `datastore/mqtt/README.md`）

```
<base>/<VIN>/v/<field>                 ← 车辆数据字段（Soc / Locked / …）
<base>/<VIN>/alerts/<name>/current     ← 正在持续的告警
<base>/<VIN>/alerts/<name>/history     ← 已结束的告警
<base>/<VIN>/errors/<name>             ← 遥测错误
<base>/<VIN>/connectivity              ← 连接事件
```

⚠️ **告警码在主题里，不在载荷里** —— 载荷只有 `StartedAt` / `EndedAt` / `Audiences`。
解析错就会把所有码读成空字符串，界面一片空白。

## V 字段：车主动推的车辆实时数据（v0.80.0）

### 主题

官方 `datastore/mqtt/README.md`：

```
Metrics: <topic_base>/<VIN>/v/<field_name>
```

载荷是**裸 JSON 值**（不是对象）：

```
v/Soc                73.92228293867637            ← 裸数字
v/Locked             true                          ← 裸布尔
v/DetailedChargeState "DetailedChargeStateDisconnected"  ← 带引号的字符串
```

⚠️ **类型会漂移**（官方原文）：同一个字段在某个固件版本是数字，
在另一个版本可能是字符串 ⇒ 解析必须三种形态都认。

### 本机实际收到的字段（2026-10-29 实测）

| 字段 | 值 | 间隔 |
|---|---|---|
| `Soc` | 73.92… | 60 秒 |
| `Locked` | true | 60 秒 |
| `EstBatteryRange` | — | 60 秒 |
| `ChargeAmps` | 0 | 5 秒 |
| `DetailedChargeState` | `DetailedChargeStateDisconnected` | 5 秒 |
| `Odometer` | 63073.42… | 300 秒 |
| `TpmsPressureFl/Fr/Rl/Rr` | 3.0000000447034836 / 2.925… / 2.875… / 2.875… | 300 秒 |
| `TpmsHardWarnings` / `TpmsSoftWarnings` | `null` | 300 秒 |
| `FdWindow` / `FpWindow` / `RdWindow` / `RpWindow` | `WindowStateClosed` | 300 秒 |
| `DoorState` | `{"DriverFront":false,…}` | 300 秒 |
| `SentryMode` | `SentryModeStateOff` | 300 秒 |
| `InsideTemp` / `OutsideTemp` | 29.30000103265047 / 28.5 | 300 秒 |
| `ChargeLimitSoc` | 80 | 300 秒 |
| `IsolationResistance` | 9830 | 300 秒 |

### ⚠️ retained 重放 = 「旧值说成新的」

broker 上保留着每个字段的**最后一份**（`retained: true`）。
⇒ 我们一订阅就会收到它们 —— 但那是**车断线前**的值，
而「收到时刻」是**现在**。

⚠️ 直接拿 received_at 当新鲜度 ⇒ **API 一重启，9 小时前的旧值显示成「刚刚」**。

⇒ 判据（`api/telemetry_state.go` 的 `telemetryFieldFreshness`）：

| 情况 | 真实采集时刻 | 陈旧？ |
|---|---|---|
| 车在线 | 收到时刻 | 否 |
| 车离线 + 收到时刻晚于断线时刻（重放） | **车断线的时刻** | **是** |
| 车离线 + 收到时刻早于断线时刻 | 收到时刻 | 否 |
| 从来没有连接状态 | 收到时刻 | **是** |

「车断线的时刻」来自 `tm_telemetry_connectivity` 里最近一条 DISCONNECTED。

### 对外接口

```
GET /api/v1/cars/{id}/telemetry_state
```

返回每个字段的：原始文本 / 类型化后的值（num、bool）/ 真实采集时刻 / 是否陈旧，
外加 `connected` / `ever_connected` / `last_connected_at` / `last_disconnected_at`。

⚠️ 调用方**必须**看 `stale` 才敢说「现在锁着」——
`/status` 的 `locked_source` + `locked_stale` 就是给这个用的。

### ⚠️ 字段名必须逐字照抄官方 proto

出处：`teslamotors/fleet-telemetry → protos/vehicle_data.proto` 的 `enum Field`。

⚠️ 写错**不报错** —— 车照常推送、订阅照常成功，只是那个字段**永远不来**。
⇒ 官方枚举编号已抄进 `api/telemetry_state_test.go` 的
`telemetryOfficialFieldNumbers`，用到的字段名不在名单里 ⇒ **测试当场变红**。

⚠️ **v0.81.0 起这批字段全部已用上**（真车已下发并收到），见下一节。

## V 字段扩容（v0.81.0）

### 下发给车端的字段清单（22 个）

`api/fleet_telemetry.go` → `telemetryFields` → JWT claim 的 `fields`。

| 字段 | 间隔 | 中文 | 官方编号 |
|---|---|---|---|
| `Soc` | 60s | 电量 | 8 |
| `Locked` | 60s | 锁车状态 | 59 |
| `EstBatteryRange` | 60s | 估算续航 | 40 |
| `Odometer` | 300s | 车端里程表 | 5 |
| `ChargeAmps` | 5s | 充电电流 | 49 |
| `DetailedChargeState` | 5s | 充电状态 | 179 |
| `TpmsPressureFl` / `Fr` / `Rl` / `Rr` | 300s | 四轮胎压 | 69 / 70 / 71 / 72 |
| `TpmsHardWarnings` / `TpmsSoftWarnings` | 300s | 胎压硬/软告警位 | 224 / 225 |
| `FdWindow` / `FpWindow` / `RdWindow` / `RpWindow` | 300s | 四扇车窗 | 60 / 61 / 62 / 63 |
| `DoorState` | 300s | 车门 + 前后备箱 | 58 |
| `SentryMode` | 300s | 哨兵模式 | 65 |
| `InsideTemp` / `OutsideTemp` | 300s | 车内 / 车外温度 | 85 / 86 |
| `ChargeLimitSoc` | 300s | 充电上限 | 38 |
| `IsolationResistance` | 300s | 高压绝缘电阻 | 11 |

⚠️ `interval_seconds` 是**最小间隔**（官方口径：值变了才发，且不比间隔更频繁）。
⚠️ 固件门槛：字段 179 需 ≥2024.38、184/187 需 ≥2024.44.32、260+ 需 ≥2026.32。
本车固件 `2026.26.200` ⇒ **260 及以后的字段不可用**（所以没用 `GpsAccuracyMeters` 那些）。

### 官方「Available Data」对照表：遥测字段 ↔ 轮询字段（2026-10-29 实读）

Fleet Telemetry「Available Data」页的 `related field` 列就是官方给的对照表：

| 遥测字段 | 官方 related field | 官方备注（逐字） |
|---|---|---|
| `DoorState` | `vehicle_state.df` `dr` `pf` `pr` `ft` `rt` | "This field has passenger front and driver rear doors **swapped** in firmware versions prior to **2024.44.32**." |
| `SentryMode` | `vehicle_state.sentry_mode` | "The current state of sentry mode." |
| `FdWindow` / `FpWindow` / `RdWindow` / `RpWindow` | `vehicle_state.fd_window` / `fp_window` / `rd_window` / `rp_window` | "The state of the front driver window." 等 |
| `Locked` | `vehicle_state.locked` | "If the vehicle is locked." |
| `Version` | `vehicle_state.car_version` | "In firmware versions earlier than 2024.44, this field returned the version of an **available software update**." |
| `TpmsPressureFl` … `Rr` | `vehicle_state.tpms_pressure_fl` … | "The last measured tire pressure of the front left tire **in bar**." |
| `InsideTemp` | `climate_state.inside_temp` | "Estimated temperature of the cabin (**in Celsius**)." |
| `IsolationResistance` | （**无**） | "Resistance between HV bus and chassis." ⇒ 官方**也没标单位** |

⚠️ 官方表里**有两处它自己的笔误**：`TpmsPressureRr` 的 related field 写成了 `tpms_pressure_fr`；
`HomelinkDeviceCount` / `HomelinkNearby` 的 related field 互换了
⇒ **以逐位实测对撞为准**，不照抄笔误。

#### ⚠️ `related field` 只说「**是哪个字段**」，**没说两边编码相同**

`FdWindow → vehicle_state.fd_window` 看上去是「同一个字段」，但**编码是两套**：

| 来源 | 编码 | 取值 |
|---|---|---|
| **轮询侧**（Fleet API `vehicle_data`） | **legacy 整数** | 0 关 / 1 开一条缝 / 2 开 |
| **遥测侧**（官方 `WindowState` 枚举） | **枚举名** | Unknown=0 / Closed=1 / PartiallyOpen=2 / Opened=3 |

官方 proto `protos/vehicle_data.proto` 的编号逐字为：

```
WindowStateUnknown = 0;  WindowStateClosed = 1;
WindowStatePartiallyOpen = 2;  WindowStateOpened = 3;
```

⚠️ **实测佐证**（2026-10-29）：轮询四扇窗全 **0**，遥测同时给 **"WindowStateClosed"**（枚举 **1**）
⇒ 两边编码确实不同（0 在 legacy 是「关」，在枚举是「Unknown」）。

⇒ **两条推论**（都已落地）：

1. **跨源只能比语义**（「开着哪几扇」），**不能比原始编码值**
   ⇒ `sameOpenSet` + 「口径冲突以遥测为准」（ADR #24）。
2. **轮询侧的取值要先过合法性闸门**：不在 {0,1,2}（或布尔）之内 ⇒ **按「没读到」处理**。
   实测某刻 `rt = 32` —— **它什么都不表示**；旧代码「非 0 即开」会把它说成「后备箱开着」
   （那次碰巧与遥测一致，**纯属运气**）。
   ⇒ v0.83.0 起该字段降级为「不知道」，由遥测接管（ADR #26）。

⚠️ **两条互相咬住的固件陷阱**（都只影响老车，但别忘了）：

1. `DoorState` 在 **2024.44.32 之前**固件里，**副驾前门 ↔ 主驾后门互换**；
2. `Version`（`car_version`）在 **2024.44 之前**返回的是「**可下载的更新版本**」而不是当前固件。

⇒ 合起来：**2024.44 ~ 2024.44.32 这一段，版本号本身不可靠** ⇒ 换门判断在这段区间不可信。
本车固件 `2026.26.200` ⇒ 两个坑都不在。

### 车端实际给的形态（2026-10-29 真车实测）

⚠️ 官方 proto 只定义**类型**，没告诉我们车端怎么**编码**。实测：

| 字段 | 车端给的 | 解析要点 |
|---|---|---|
| `FdWindow` … `RpWindow` | `"WindowStateClosed"` | **枚举名**（`WindowStateUnknown/Closed/PartiallyOpen/Opened` = 0/1/2/3） |
| `DoorState` | `{"DriverFront":false,…}` | **对象**；官方 `message Doors` 的 6 个字段名逐字照抄 |
| `SentryMode` | `"SentryModeStateOff"` | **枚举名**（`Unknown/Off/Idle/Armed/Aware/Panic/Quiet` = 0–6） |
| `TpmsHardWarnings` / `TpmsSoftWarnings` | `null`（**字面量**） | = 「**没有告警位**」，不是解析失败 |
| `TpmsPressureFl` | `3.0000000447034836` | 浮点 |
| `InsideTemp` / `OutsideTemp` | `29.30000103265047` / `28.5` | 浮点 |

⚠️ **枚举名与数字都要认** —— 官方 README 明说类型会随固件漂移
（`"12.3"` 与 `12.3` 都可能），解析器三种形态全认，认不出就**摊原文**。

### 单位：靠交叉验证，不靠猜

⚠️ 遥测 proto **没标任何单位**。用同一时刻的两个来源对撞（2026-10-29 12:18）：

| 量 | 遥测 | 轮询 `vehicle_data` | 结论 |
|---|---|---|---|
| 左前胎压 | `3.0000000447034836` | `3` | **bar** |
| 右前 / 左后 / 右后 | `2.925` / `2.875` / `2.875` | `2.925` / `2.875` / `2.875` | **bar** |
| 车外温 | `28.5` | `28.5` | **℃** |
| 车内温 | `29.30000103265047` | `30` | **℃**（`climate_state` 取整） |

⚠️ `IsolationResistance` 官方**没标单位** ⇒ 只给原始值，不编单位。

### 对外接口

```
GET /api/v1/cars/{id}/telemetry_state    ← 原始字段流（逐字段 label/at/stale）
GET /api/v1/cars/{id}/telemetry_vehicle  ← 解读后的车况实况（v0.81.0）
```

`telemetry_vehicle` 返回：胎压（四轮 + 单位 + 告警三态）、车窗（四扇 + 中文状态）、
门/舱盖（6 个）、哨兵、内外温、充电上限、绝缘电阻，
外加整组的 `at` / `stale` / `connected` / `ever_connected`。

⚠️ `open_windows` / `open_doors` 可能是 **null** —— 那是「**不知道**」，不是「全关」。

### `/status` 的车况实况字段（v0.82.0）

| 字段 | 含义 |
|---|---|
| `open_windows` / `windows_source` | 开着哪几扇窗 + 来源（`poll` / `telemetry`） |
| `open_doors` / `doors_source` | 开着哪几个门 + 来源 |
| `sentry_state` / `sentry_text` | 哨兵状态（`off` / `on` / `panic` / `unsupported` …）+ 中文文案 |
| `sentry_source` / `sentry_at` | 哨兵来源 + 采集时刻 |
| `vehicle_safety_at` | 整组车况实况的采集时刻（三项里**最旧**的） |

⚠️ 客户端规则（ADR #25）：**没读到 ≠ 全关**；无来源的项**整项不显示**。

### 两个来源怎么合

`pickNewerTPMS` / `pickNewerWindows` / `pickNewerDoors` / `pickNewerSentry`：
**口径一致时，按采集时刻取更新的那个**
（遥测可能是「车断线前的最后已知值」，轮询是上一次成功拉取）。

⚠️ 一边「不知道」（nil）时直接用另一边 —— 不能把「读不到」当成「全关」。

#### 口径**冲突**时以遥测为准（v0.82.0，被真实实测逼出来的）

某一刻轮询侧只有 `rt` 非零，值是 **32**（既非 0 也非 1）；同一时刻遥测给 `TrunkRear = true`
⇒ **两个独立来源指向同一个门位**。

⇒ **轮询侧的门位编码是不透明的** ⇒「非 0 即开」这种假设**不成立**，
必须有官方消息做裁判；而遥测 `DoorState` 是官方 `message Doors` 的**逐字编码**
⇒ **冲突时以遥测为准**（ADR #24）。

⚠️ **哨兵不做跨词表臆造映射**：轮询侧只能分辨开/关（布尔），
`true` 只说「**已开启**」，**不猜** armed/aware/panic；
遥测的 `unknown`（官方枚举 0）**不算「知道了」** ⇒ 不参与与轮询的竞争。

## 已完成（2026-10-28 v0.79.0 3/3 实测）

| 项 | 状态 |
|---|---|
| 下发 `fleet_telemetry_config` | ✅ `sent=true, accepted=true`；车端回 `{"response":{"updated_vehicles":1}}` |
| 车端接入 | ✅ 唤醒后 **13 秒**连上；`tm_telemetry_connectivity` 落库 `CONNECTED` |
| 车端遥测客户端版本 | ✅ `1.3.0`（固件 `2026.26.200`） |
| 数据落地 | ✅ `Soc` / `Locked` / `Odometer` / `ChargeAmps` / `DetailedChargeState` / `EstBatteryRange` |
| 故障码查询 | ✅ `GET /api/v1/cars/{id}/alerts` |
| 车况页 | ✅ `/status` 的 `telemetry` 块 → App 故障码卡 |
| 推送 | ✅ `watch-car.ps1` 按码集合判定 |
| Hermes | ✅ MCP 第 15 个工具 `teslamate_alerts` |

### 踩过的坑（照抄报错比猜参数快）

1. **`exp` 有硬边界**。Tesla 的原文：
   `expiration should be greater than 1793204345 and less than 1824653945`
   ⇒ `exp ∈ (服务端当下, 服务端当下 + 365 天)`。
   ⚠️ 用本地时钟算「now + 365 天」会**晚几秒越界** ⇒ 本机取 **360 天**。
2. **`synced=true` 不是「已生效」**。车端**没有配置**时也回 `{"synced":true,"config":null}`
   —— 那是**默认值**。判据只能是 `config != null`。
3. **中国区的 `fleet_status` 字段是嵌套的**（在 `vehicle_info[VIN]` 里），
   与官方文档的平铺结构不同 ⇒ 必须保留 `Raw` 原文对照。
4. **`fleet_status` 的 POST body 必须带 `vins`**，否则 400
   `param is missing or the value is empty or invalid: vins`。
5. **CA 串里不能有叶子证书**。车端信任库只该有中间 + 根；
   本机 `server.crt` 4 段（叶子 + 2 中间 + 1 根）⇒ 下发时剥掉叶子，剩 **3 段**。
   `config_summary` 里 `ca_certificates` 与 `ca_source_certificates` 两个数一对才看得出对不对。

### 界面的三种状态（绝不能混成一种）

| 情况 | 界面 | 为什么 |
|---|---|---|
| 有正在持续的故障码 | 故障码卡（红色顶条 + 码 + 已核验含义） | 有话可说 |
| 遥测正常、确实没有故障 | **什么都不显示** | 装饰宁删勿留 |
| 遥测**从未**接上 / 读不到 | 显式说「遥测还没接上，暂时拿不到故障码。」 | 否则就是**假安全** |

### 运维：证书与配置的到期线

- **证书 90 天** ⇒ `scripts/renew-telemetry-cert.ps1` 每周续期 + 重启容器。
  ⚠️ 该脚本原先解析到期时间会抛异常（中文区域认不出 `GMT`）⇒ **续期从来没成功执行过**，
  已改成不依赖区域设置的解析（正则 + 月份表 + 按 UTC 构造）。
- **配置 360 天** ⇒ 续期脚本现在会**顺带重下发**遥测配置
  （证书换了 ⇒ 车端手里的旧 CA 验不过新证书 ⇒ 不重下发遥测会**静默断流**）。

## 还需要用户确认的（2026-10-29 逐条复核后）

> ⚠️ **后台域名是 `developer.tesla.cn`（中国区），不是 `developer.tesla.com`（海外区）。**
> 两者文档同路径同内容（`.cn/docs/fleet-api/fleet-telemetry` 实测 200），
> 但**账号、客户端是分开的两套** —— 中国区的车只认 `.cn` 那侧。

> ✅ **复核结论：真正需要人动手的 = 零条。**
> - 第 1、3 条是「**不要动**」（原地改 / 不要改），不是「要去开」；
> - 第 2 条**已订正**（后台**没有**「Fleet Telemetry」开关）⇒ 不用做；
> - 第 4、5 条**已实证**（公钥托管 HTTP 200、固件 `2026.26.200`）。

1. **OAuth 客户端：原地改，不要新建。**
   `.env` 里的 `TESLA_CLIENT_ID` / `TESLA_CLIENT_SECRET` 与后台那套**完全一致**
   （`<client_id>……` / `ta-secret.…<已脱敏>`），且中国区的 `CN_…` refresh_token
   配它在 `auth.tesla.cn` 能换出 token ⇒ **它就是中国区在用的那套凭据**。
   ⚠️ 在 `.cn` 后台**另建**一套 client，等于多出第二份凭据，
   以后换车 / 加车就会配错 client —— 那种错**不报错**，只是 401。

2. ~~**`developer.tesla.cn` 后台的「Fleet Telemetry」开关**是否已打开。~~
   —— **这条是错的，已订正**（2026-10-29 实读官方文档）：
   开发者后台**没有**这个开关。官方先决条件只有三条：
   非 2021 年前 Model S/X · 固件 ≥ 2024.26 · **虚拟密钥已配对** —— 我们**全部满足**
   （固件 `2026.26.200`、`key_paired_vins` 含本车、`synced: true`、21 个字段在推）。

   ⚠️ 真实存在的那个开关在**车机安全屏幕**（「允许第三方应用数据流」），
   但它**只对英特尔 Atom 的 Model S/X** 有效（那些车不支持虚拟密钥）——
   本车是 Model 3，**不适用**（`fleet_status` 里也没有 `safety_screen_streaming_toggle_enabled`）。

   ⇒ 这条**不用做**。要查授权状态请去 Tesla 手机 App → 用户资料 → 安全与隐私 → 第三方应用。

3. **「允许的源」**里填的是**完整主机名**
   `https://<主机名>.<tailnet域>.ts.net`
   （不是根域 —— 根域 `<tailnet域>.ts.net` 连 DNS 名称都没有、没有证书，后台会当场报红）。

4. **公钥托管 URL**（Tesla 云来拉，不带客户端证书）：
   `https://<主机名>.<tailnet域>.ts.net/.well-known/appspecific/com.tesla.3p.public-key.pem`
   ⚠️ 这一条**不受区域影响** —— Tesla 直接去拉我们给的这个完整 URL，
   所以它已经实证生效（车端拿到了公钥才会采纳我们签的 config）。

5. **确认车辆固件 ≥ 2024.26**（走车控代理的要求）。
   本机实测 `2026.26.200` ✓，可不用再查。

## 恢复步骤（Funnel 配置丢了时）



Funnel 配置存在 tailscaled 里，重启会自动恢复。若真丢了：

```powershell
# 443：应用公钥托管（**必须免鉴权**）
tailscale funnel --bg --https=443 --set-path=/.well-known/appspecific "C:\Users\<用户名>\tesla-key"

# 8443：遥测入口（raw TCP 透传，**需要 Windows 管理员**）
tailscale funnel --bg --tcp=8443 tcp://127.0.0.1:4443
```

⚠️ 两条都指向**同一个域名**但不同端口：443 给 Tesla 云拉公钥（不带客户端证书），
8443 给车端推数据（带客户端证书）。混用会导致 Tesla 云拉公钥失败或车端握手失败。
