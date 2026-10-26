# Tesla 车控接入路径（本项目的实现）

> 适用范围：**本项目**（teslamate）自己怎么把「给这台车发命令」这条链路搭起来、怎么验证、坏了怎么查。
>
> 平台层面的通用调研（域名方案对比、中国区特有的坑、partner account 官方流程）见
> Housekeeper 的 `docs/26-Tesla车控接入路径.md`（调研部分仍然有效）。

---

## 0. 边界：车辆的一切归本项目

| 能力 | 归谁 | 在本项目里长什么样 |
|---|---|---|
| OAuth 授权 / refresh_token 轮换 | **teslamate** | `scripts/tesla-authorize.ps1` + `api/tesla.go` |
| 车辆签名私钥 + TLS 证书 | **teslamate** | docker 卷 `teslamate_command_keys`（**不入库**） |
| partner account（公钥域名注册） | **teslamate** | `scripts/tesla-partner.ps1` |
| 签名命令（signed command） | **teslamate** | `command-proxy` sidecar（官方 `tesla/vehicle-command`） |
| 实时 `vehicle_data` | **teslamate** | `api/tesla.go` 缓存原始响应，`api/tesla_downstream.go` **原样转发** |
| 动作目录 / 参数范围 / 风险等级 | **teslamate** | `api/tesla_command.go`（**唯一事实源**） |
| 字段归一化语义 | 下游自己 | Housekeeper 的 `normalize_vehicle_data()`（一行未改） |

> **下游不直连 Tesla**。要车况就问本项目，要控车也问本项目。
> 详见 Housekeeper 的 `docs/01-架构决策.md` **ADR-029**。

---

## 1. 全景：一条命令是怎么发出去的

```
App / Hermes(MCP) / Housekeeper
        │  POST /api/v1/cars/{id}/commands/{action}
        ▼
   teslamate api（Go）
        │  ① 查动作目录（16 个动作 + 参数校验 + 风险分级）
        │  ② 调 command-proxy
        ▼
   command-proxy（官方 tesla/vehicle-command sidecar）
        │  ③ 用车辆私钥做 ECDSA 签名 → routable_message
        │  ④ 发到 Tesla 的 signed_command 端点
        ▼
      Tesla  →  车
```

**关键点**：③④ 业务代码做不了（Tesla 把这套协议实现成了 sidecar），
所以 `command-proxy` 是**必需组件**，不是可选装饰。

---

## 2. 搭起来要做的四件事

### 2.1 开发者后台建应用（人工，一次性）

在 <https://developer.tesla.cn> 建应用，记下 `client_id` / `client_secret`。

⚠️ 后台的「**允许的源 URL**」要填**完整主机名**（如
`https://<主机名>.<tailnet域>.ts.net`），**不是根域** ——
后台表单会当场校验「该域名自己得有有效 TLS 证书」，根域没有证书会直接报红。

### 2.2 OAuth 授权（`scripts/tesla-authorize.ps1`）

```powershell
pwsh -File scripts/tesla-authorize.ps1
```

⚠️ **中国区必须走 `authorization_code`**：`client_credentials` 换到的令牌**没有 scope**，
调任何端点都是 `403 {"error":"Unauthorized missing scopes"}`。

⚠️ `refresh_token` 是**一次性**的（有效期 3 个月，刷新即轮换）。
服务端每次刷新都会把新 token 写回 `.env`（`api/tesla.go:persistRefreshToken`）。

### 2.3 车辆私钥 + TLS 证书（docker 卷）

卷 `teslamate_command_keys` 里放三件套：

| 文件 | 用途 |
|---|---|
| `private-key.pem` | 车辆签名私钥（EC / P-256） |
| `tls-cert.pem` | sidecar 的自签证书（公开部分） |
| `tls-key.pem` | sidecar 的 TLS 私钥 |

⚠️ **这个卷只给 `command-proxy` 用**。api 容器**只挂公开证书**卷 `teslamate_command_ca`，
不挂私钥卷 —— api 不需要车辆私钥。

### 2.4 注册 partner account（`scripts/tesla-partner.ps1`）

2021 年后的 Model 3/Y 发「签名命令」必须用**注册在 Tesla partner account 上的那把私钥**，
所以这一步不能省。

```powershell
# 查当前注册状态（顺带比对「Tesla 侧注册的公钥」与「本地私钥」是不是同一把）
pwsh -File scripts/tesla-partner.ps1 -Domain <主机名>.<tailnet域>.ts.net

# 注册 / 换域名
pwsh -File scripts/tesla-partner.ps1 -Action register -Domain <主机名>.<tailnet域>.ts.net
```

脚本做三件事：
1. 取 **partner 令牌**（`client_credentials` + `audience` + scope）；
2. 查/注册 `POST /api/1/partner_accounts`（提交的是**公钥**，私钥永远不发出去）；
3. **比对**：把本地私钥推导出的 EC 点与 Tesla 返回的公钥点逐字节比 ——
   不一致 ⇒ **签名验不过**，车控必然失败（这是最容易踩又最难查的坑）。

---

## 3. 启动与验证

```powershell
# 起车控代理（密钥卷没准备好时不让它拖垮整个栈 ⇒ 放在 control profile 里）
docker compose --profile control up -d command-proxy

# 看它有没有在听
docker logs teslamate-command-proxy --tail 20
```

⚠️ `-host` 必须显式给 `0.0.0.0`（compose.yaml 里已经给了）。
sidecar **默认只听 localhost** ⇒ 日志会诚实地写 `Listening on localhost:4443`，
然后 api 容器连不上（connection refused / 超时），**且不报错**。

端到端验证（会真的动车，**先确认车在哪、能不能动**）：

```powershell
$token = (Select-String -Path .env -Pattern '^API_TOKEN=(.*)$').Matches[0].Groups[1].Value.Trim()
Invoke-RestMethod -Uri 'http://localhost:8090/api/v1/cars/1/commands' -Headers @{Authorization="Bearer $token"}
```

⚠️ **车在休眠时 Tesla 会回 408 `vehicle is offline or asleep`** ——
那是**车没醒**，**不是权限问题**。车醒了再验。

---

## 4. 换域名怎么办

**partner account 是「原地更新」，不是「注册即锁死」**（2026-10-26 实测）：
换域名后重新注册同一个应用即可，`created_at` 不变、`updated_at` 刷新，
**不需要**新建 3P 应用。旧域名会随之失效。

步骤：

1. 后台「允许的源 URL」补上新主机名（**完整主机名**，且它自己得有有效 TLS 证书）；
2. `pwsh -File scripts/tesla-partner.ps1 -Action register -Domain <新主机名>`；
3. `pwsh -File scripts/tesla-partner.ps1 -Domain <新主机名>` 复核，确认「同一把 ✅」。

---

## 5. 常见坑

| 症状 | 原因 | 怎么修 |
|---|---|---|
| `403 Unauthorized missing scopes` | 用了 `client_credentials` 令牌 | 走 `authorization_code`（§2.2） |
| sidecar 日志 `Listening on localhost:4443`，api 连不上 | `-host` 没给 `0.0.0.0` | compose.yaml 里显式给 |
| `expected 17-character VIN in path` | 传了 Fleet API 的**数字 id** | 传 **VIN**（17 位） |
| `vehicle is offline or asleep` | 车在休眠 | 不是权限问题，等车醒 |
| 命令发到了北美端点 | 换令牌**漏了 `audience`** | `audience` = 本区 `fleet` URL |
| 注册报 403 | 后台 `allowed_origins` 里没有那个**完整主机名** | 后台补完整主机名（不是根域） |
| 注册报「域名必须有有效证书」 | 填了**根域** | 填完整主机名 |
| 车控命令全部失败但不报错 | Tesla 侧注册的公钥与本地私钥**不是同一把** | `scripts/tesla-partner.ps1` 的 status 会直接指出来 |
| `public_key` 读出来是 `$null` | 中国区响应包在 `{"response":{...}}` 里 | 脚本已处理（别直接读顶层字段） |

---

## 6. 下游怎么用

| 消费方 | 入口 |
|---|---|
| **App**（手机） | `POST /api/v1/cars/{id}/commands/{action}`（`app-android/.../VehicleControlCard.kt`） |
| **Hermes**（电脑上的 MCP） | `mcp/teslamate_mcp.py` 的 `vehicle_command` / `command_catalog` 工具 |
| **Housekeeper** | `backend/app/integrations/teslamate_vehicle/` —— **不再直连 Tesla** |

⚠️ Housekeeper 侧的车控**预校验**走 teslamate 的 `?dry_run=1`（只校验不下发），
参数范围以 teslamate 的动作目录为**唯一事实源**。

---

## 7. 相关文件

| 路径 | 作用 |
|---|---|
| `api/tesla.go` | Fleet API 客户端：令牌轮换、实时状态、原始 `vehicle_data` 缓存 |
| `api/tesla_command.go` | **16 个动作**的目录、参数校验、风险分级、审计日志 |
| `api/tesla_downstream.go` | 下游端点（`vehicle_data` 原样转发 / `vehicles` / `vehicle_snapshot`） |
| `api/tesla_command_test.go` | 动作目录与风险分级的单测 |
| `scripts/tesla-authorize.ps1` | OAuth 一次性授权（换 refresh_token） |
| `scripts/tesla-partner.ps1` | partner account 注册 / 查状态 / 公钥比对 |
| `compose.yaml` | `command-proxy` 服务 + `command_keys` / `command_ca` 卷 |
| `mcp/teslamate_mcp.py` | Hermes 的 stdio MCP 服务（9 个工具） |
