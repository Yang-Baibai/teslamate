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

官方口径：`https://developer.tesla.com/docs/fleet-api/fleet-telemetry`

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

- `records`: `alerts` / `errors` / `V` / `connectivity` → `logger`
  ⇒ 先用 logger（stdout JSON），确认数据格式后再接 mqtt dispatcher。
- `transmit_decoded_records: true` ⇒ logger 输出 JSON 而不是 protobuf（省掉 protobuf 解析）。
- `tls`: 指向 `/certs/server.crt` / `/certs/server.key`。
  ⚠️ **这个服务是 mTLS-only 的**，没有 `tls` 段会直接 panic 退出。

## 还需要做的

### 一、用户侧（我做不到的）

1. **确认车辆固件 ≥ 2024.26**
   （中国区文档口径；传统证书签名流程的应用要求 ≥ 2023.20.6。
   2021 年前的 Model S/X 不支持。）
   查法：车机屏幕 → 软件更新；或 Tesla App → 车辆 → 设置 → 软件版本。
   ⚠️ 唤醒车只为了查这个版本不划算（耗电 + 可能计费），所以**交给用户查**。
2. **确认虚拟密钥已配对**
   `https://tesla.com/_ak/<developer-domain>` → 在 Tesla App 里把钥匙加到车上。
   车控能跑通 ⇒ 大概率已配对，但要用 `fleet_status` 端点最终确认。
3. **developer.tesla.com 后台**：确认 app 上的「Fleet Telemetry」开关已打开，
   且「允许的源 URL」里是**完整主机名** `https://<主机名>.<tailnet域>.ts.net`
   （不是根域 —— 根域没有证书，后台会当场报红）。

### 二、我侧（下一轮）

1. 在 teslamate API 里加「下发 `fleet_telemetry_config`」的端点
   —— **复用已有的 `ensureToken()` + command-proxy client**（v0.76.0 车控那条路），
   不要另写一套令牌逻辑。
2. 数据落地：mosquitto → Go API → Postgres。
3. 接进 App 车况页 + 推送通道（故障码告警）。

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
