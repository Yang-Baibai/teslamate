# MCP 接入指南（把这台车接进任何 agent）

> **给「想用自己的 agent 问这台车 / 开这台车」的人**。
> 一句话：**这个项目把车况与车控做成了一个标准 MCP 服务**，
> 任何支持 MCP 的客户端（Hermes / minimaxcode / Claude Desktop / Cursor …）
> 接上就能查数据、也能控车。

---

## 0. 一分钟接入

### 方式 A：stdio（本地客户端 —— **推荐**，Hermes 现在用的就是这个）

客户端把本文件**当子进程拉起来**，通过 stdin/stdout 说 JSON-RPC。

```json
{
  "mcpServers": {
    "teslamate": {
      "command": "python",
      "args": ["<仓库路径>/mcp/teslamate_mcp.py"]
    }
  }
}
```

Hermes 的 `config.yaml` 里对应的是：

```yaml
mcp_servers:
  teslamate:
    command: python
    args: ["<仓库路径>/mcp/teslamate_mcp.py"]
    timeout: 120
```

⚠️ **令牌不用写进客户端配置** —— 本服务会自己去**同目录的 `.env`** 找
`API_TOKEN`（也在 `TESLAMATE_API_TOKEN` 环境变量里认）⇒ 少一处密钥副本。

### 方式 B：Streamable HTTP（远程 / 别的机器 / 不想起子进程）

```powershell
python mcp/teslamate_mcp.py --transport http --port 8765
```

客户端配置：

```json
{
  "mcpServers": {
    "teslamate": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <MCP_HTTP_TOKEN>" }
    }
  }
}
```

⚠️ **HTTP 模式默认只绑 `127.0.0.1`**（规范：本地服务默认只绑回环）。
要让局域网/Tailscale 上的别的机器连 ⇒ 显式加 `--host 0.0.0.0`，
**并且必须同时设 `MCP_HTTP_TOKEN`**（否则**拒绝启动**，fail closed）。

---

## 1. 有哪些能力

### 1.1 工具（31 个，模型自己决定调哪个）

| 分组 | 工具 | 干什么 |
|---|---|---|
| **汇总车况** | `teslamate_status` | 电量 / 续航 / 锁车 / 位置 / 内外温 / 胎压 / 车窗车门哨兵 / 低电量 / 天气 |
| | `teslamate_battery` | 电池健康度（SOH）、实测可用容量、衰减趋势 |
| | `teslamate_range_estimate` | 「到 X 公里外够吗」（**服务端判**） |
| | `teslamate_server_health` | **服务端自检**（数据库 / 车端 API / 服务器时间） |
| **充电** | `teslamate_charges` | 最近几场充电明细 |
| | `teslamate_charge_stats` | 充电量统计（近 7 天 / 30 天 / 本月 / 全部） |
| | `teslamate_charge_brief` | **服务端渲染好的**充电简报（标题 + 正文） |
| | `teslamate_charge_outcome` | 这一场充完了吗（结局判定） |
| | `teslamate_charge_curve` | 某一场的功率曲线 |
| **行程与能耗** | `teslamate_drives` | 最近几趟行程 |
| | `teslamate_stats` | 用量统计（里程 / 电量 / 速度档） |
| | `teslamate_driving_profile` | 驾驶习惯画像 |
| | `teslamate_stay_points` | 常去的点（停留点） |
| | `teslamate_energy_profile` | 能耗剖面（速度档 / 爬升） |
| | `teslamate_route_profile` | 某趟行程的路线与能耗 |
| | `teslamate_drive_energy_profile` | 某一趟的**逐公里**电量剖面 |
| | `teslamate_climb_model` | 爬升能耗标定 |
| | `teslamate_cost` | 电费估算 |
| **车端实况** | `teslamate_telemetry` | 胎压 / 车窗 / 门 / 哨兵 / 温度 / 充电上限（车主动推） |
| | `teslamate_telemetry_state` | **遥测接得上吗**（连过没 / 现在在线吗） |
| | `teslamate_vehicle_data` | 车端**原始** vehicle_data（Tesla 官方字段） |
| | `teslamate_vehicle_snapshot` | 车端快照（充电 / 空调 / 行驶 / 车况 四块） |
| | `teslamate_fleet_telemetry` | 遥测**设施**状态（装没装 / 配没配对 / 固件版本） |
| | `teslamate_alerts` | 故障码（BMS_ / DI_ / VCFRONT_ / CP_ …） |
| | `teslamate_car_info` | 这台车是谁（车名 / 型号 / VIN） |
| **保养** | `teslamate_maintenance` | 保养 / 轮胎提醒（还剩多少公里） |
| | `teslamate_set_maintenance_baseline` | **写**保养 / 轮胎基准 |
| **车控** | `teslamate_command_catalog` | 这台车**支持哪些动作** + 参数怎么填 + 风险等级 |
| | `teslamate_vehicle_command` | **下发**一条命令（21 个动作） |
| **反馈** | `teslamate_feedback` | 读用户反馈 |
| | `teslamate_feedback_decision` | **写**反馈评估结论 |

### 1.2 资源（3 个，宿主应用决定要不要放进上下文）

| URI | 内容 |
|---|---|
| `teslamate://vehicle/info` | 这台车是谁（车名 / 型号 / VIN） |
| `teslamate://vehicle/status` | 当前车况快照 |
| `teslamate://vehicle/maintenance` | 保养 / 轮胎结论 |

⚠️ **工具与资源不是二选一**（MCP 规范原文）：
工具是**模型自己决定**调的（model-controlled），
资源是**宿主应用决定**要不要放进上下文的（application-driven）。

---

## 2. 接入后会自带的三条规矩（server instructions）

MCP 协议里 `initialize` 响应带 `instructions` 字段，客户端会把它当
「这个服务该怎么用」的第一提示。本服务把项目的三条红线**写进了协议**
⇒ **换任何一个 agent 接入，都带着同一份约束**：

1. **判定只在服务端** —— 带「结论 / 阈值 / 是否正常」的都是服务端算好的，
   **照原文转述，不要自己重算**（同一个事实只能有一个说法）。
2. **宁可说「不知道」，不可编** —— 读不到就是读不到，
   **不要**把「没读到」说成「正常 / 关着 / 没故障」。
3. **车控不可逆** —— 下发前先查 `teslamate_command_catalog`；
   高危动作（解锁 / 开合尾门 / 远程启动）**先向用户确认**；
   下发后回读「车端结果」—— `rejected` 绝对不能报成成功。

---

## 3. 工具注解（客户端靠它决定要不要弹确认框）

MCP 规范（2025-06-18）的 tool 有 `annotations` 字段。本项目给**全部 31 个**工具都标了：

| 注解 | 含义 | 本项目里是谁 |
|---|---|---|
| `readOnlyHint=true` | 只读 ⇒ 客户端可**免确认**直接调 | 29 个查询工具 |
| `readOnlyHint=false` | 会写东西 | `teslamate_set_maintenance_baseline`、`teslamate_feedback_decision` |
| `destructiveHint=true` | **不可逆** ⇒ 客户端**应当**弹确认框 | `teslamate_vehicle_command`（解锁 / 尾门 / 远程启动） |
| `idempotentHint` | 重复调没有额外副作用 | 车控标 `false`（解锁重复调没意义） |

⇒ 不标 = 让客户端自己猜 ⇒ 危险动作可能被静默执行。

---

## 4. 安全边界

| | |
|---|---|
| **谁在跑** | 本服务**只调本项目的 API**（`http://localhost:8090`），**不碰数据库** |
| **令牌** | API 令牌从 `.env` 读，**不写进客户端配置** |
| **HTTP 监听** | 默认 `127.0.0.1`；绑 `0.0.0.0` **必须**带 `MCP_HTTP_TOKEN`（否则拒绝启动） |
| **HTTP 鉴权** | Bearer 令牌（MCP 官方 `TokenVerifier` 协议）⇒ 令牌不对 = **401** |
| **Origin / Host** | SDK 的 DNS rebinding 防护（恶意 Origin = 403，恶意 Host = 421） |
| **车控动作** | 只有服务端 `api/tesla_command.go` 目录里那 **21 个**；参数服务端再校验一遍 |
| **吊销** | 改 `.env` 的 `MCP_HTTP_TOKEN` ⇒ **一键关掉**远程车控 |

---

## 5. 出问题先查这里

| 症状 | 先看 |
|---|---|
| 工具**调不到**（不是报错，是根本列不出来） | 入口块必须在**文件最末**（v0.166.0 修过一个真 bug）；跑 `python mcp/selftest_mcp.py` |
| 工具能调，但**字段全是 null** | 跑 `python mcp/check_tool_fields.py`（字段映射自检） |
| 飞书里**没有**本服务的工具 | `python mcp/check_hermes_config.py`（Hermes 配置体检） |
| 数据是**旧的** | 看返回里的 `数据是旧的` / `采集于`（车睡着时车端就断了） |
| **遥测没接上** | `teslamate_telemetry_state` ⇒ 拿不到故障码 ≠ 没有故障码 |
| HTTP 起不来 | `python mcp/teslamate_mcp.py --transport http --port 8765` 看 stderr |

⚠️ **改了 `mcp/teslamate_mcp.py` ⇒ 必须重启 Hermes 网关**
（MCP 服务是网关的子进程，不重启跑的仍是旧代码）：
杀网关进程树 ⇒ `Start-ScheduledTask -TaskName 'Hermes-Gateway'`。

---

## 6. 怎么验「真的接上了」

```bash
python mcp/selftest_mcp.py            # ① 工具直调 ② stdio 握手 ③ Hermes 配置 ④ 字段自检
python mcp/check_tool_fields.py       # 字段映射自检（0 项不合规 = 通过）
python mcp/check_hermes_config.py     # Hermes 配置体检
bash scripts/hermes-gates.sh          # Hermes 链路闸门（含 3 道 MCP 专项闸门）
```

⚠️ **「能 import」不等于「活进程里有」** —— v0.166.0 实测踩过：
入口块在文件中部 ⇒ `mcp.run()` 先阻塞 ⇒ 后面的工具**从没被注册**，
而 `import` 路径的自检**全绿** ⇒ **假绿**。
⇒ 闸门 30 会**起一个真的 stdio 服务**并 `tools/list` 数一遍。
