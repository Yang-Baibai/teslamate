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

还没用上、但已在官方名单里备着的（等扩大遥测配置时直接用）：
`TpmsPressureFl/Fr/Rl/Rr`（胎压）、`FdWindow/FpWindow/RdWindow/RpWindow`（车窗）、
`DoorState`（车门）、`ChargeLimitSoc`（充电上限）、`InsideTemp` / `OutsideTemp`、
`SentryMode`（哨兵）、`TpmsHardWarnings` / `TpmsSoftWarnings`。

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

## 还需要用户确认的

> ⚠️ **后台域名是 `developer.tesla.cn`（中国区），不是 `developer.tesla.com`（海外区）。**
> 两者文档同路径同内容（`.cn/docs/fleet-api/fleet-telemetry` 实测 200），
> 但**账号、开关、客户端是分开的两套** —— 中国区的车只认 `.cn` 那侧。

1. **OAuth 客户端：原地改，不要新建。**
   `.env` 里的 `TESLA_CLIENT_ID` / `TESLA_CLIENT_SECRET` 与后台那套**完全一致**
   （`<client_id>……` / `ta-secret.…<已脱敏>`），且中国区的 `CN_…` refresh_token
   配它在 `auth.tesla.cn` 能换出 token ⇒ **它就是中国区在用的那套凭据**。
   ⚠️ 在 `.cn` 后台**另建**一套 client，等于多出第二份凭据，
   以后换车 / 加车就会配错 client —— 那种错**不报错**，只是 401。

2. **`developer.tesla.cn` 后台的「Fleet Telemetry」开关**是否已打开。
   ⚠️ 数据流已实证生效（车端用我们的配置连上并推送了 6 条消息）
   ⇒ **至少有一侧是开的**；要确认的是「你登录的这一个域名」下也是开的。

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
