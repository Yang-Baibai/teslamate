# 弹窗根因报告：Windows 定时任务拉起控制台窗口

- 报告时间：2026-11-05 00:30
- 报告人：定位定时弹出 cmd 窗口的数据过期原因（会话 `mvs_cff54286279b4b7899878e550ae1c950`）
- 交接对象：熟悉项目并担任项目负责人（会话 `mvs_fbc78974f80b43ef85b8801295e1dcf7`）
- 结论一句话：**弹窗是 TeslaMate 项目的 Windows 计划任务 `TeslaMate-CarWatch` 每 5 分钟跑 `scripts/watch-car.ps1` 时，把控制台窗口显示出来了；窗口里那行「数据过期」是这个脚本每轮必打印的运行摘要，不是报错。**

## 一、用户原始现象

> 电脑上有个 cmd 窗口会定时弹出来一下然后就关闭，其中有「数据过期」几个字。

## 二、根因（已实机取证）

### 2.1 弹窗来源：计划任务 `TeslaMate-CarWatch`

任务完整 XML（`Export-ScheduledTask` 实测）关键字段：

| 项 | 值 |
|---|---|
| 任务名 | `\TeslaMate-CarWatch` |
| 动作 | `pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "C:\tm-ws\scripts\watch-car.ps1"` |
| 触发器 | `MSFT_TaskTimeTrigger`，`StartBoundary 2026-10-24T10:10:00`，`Interval PT5M`（**每 5 分钟**） |
| 登录类型 | `InteractiveToken`（在交互式桌面会话里跑） |
| Settings | **没有 `<Hidden>` 元素** |
| LastResult | 0（一直成功，不是崩溃） |

**机制**：任务以 `InteractiveToken` 身份在当前桌面会话直接拉起 `pwsh.exe` 控制台程序，
且任务 XML 里 **没有 `<Hidden>true</Hidden>`** ⇒ Windows 分配一个可见的控制台窗口；
脚本跑完（几秒）窗口随进程退出而关闭 ⇒ 表现为「定时弹出来一下然后就关闭」。

**与 5 分钟节奏完全吻合**——`scripts/.watch-car.log` 里「本轮结束」的时间戳逐轮对得上：

```
[2026-11-04 23:30:01] …  [23:35:02] …  [23:40:02] …  [23:45:02] …
[2026-11-05 00:00:02] …  [00:05:02] …  [00:10:02] …  [00:15:02] …  [00:20:02] …  [00:25:02] …
```

### 2.2 窗口里那行「数据过期」的来源

`scripts/watch-car.ps1:743`：

```powershell
Write-Log "本轮结束：充电=$charging 电量=$battery 胎压告警=$tpmsAlert 数据过期=$dataStale fleet=$fleetState 未锁=… 车窗=… 故障码=… 遥测接入=$telEver 已告知=[$mark]"
```

- `Write-Log`（`watch-car.ps1:131-134`）内部用 `Write-Output` ⇒ **直接写 stdout ⇒ 打进控制台窗口**。
- 实机跑一轮捕获 stdout（DryRun + 临时状态文件，未污染真实状态）：

```
[DryRun] ⓘ 爱车 数据已 27 小时未更新
[DryRun] 📊 爱车 · 近 7 天用车简报
[2026-11-05 00:27:32] 本轮结束：充电=False 电量=50 胎压告警=False 数据过期=True fleet=asleep 未锁=False(持续0分钟) 车窗=False(持续0分钟) 故障码=0 遥测接入=True 已告知=[data_stale]
```

⇒ **与用户看到的「数据过期」逐字吻合**，确认是同一处。

### 2.3 「数据过期=True」本身是真状态，不是脚本 bug

- `scripts/.watch-car-state.json`：`"alerted": { "data_stale": true }`，`"last_stale": false`（注意 `last_stale` 才是旧键，当前脚本用的是 `alerted.data_stale`）
- 数据库实测：`positions` 表最后一条 = `2026-11-03 13:09:07`，当前 `2026-11-05 00:30` ⇒ **约 35 小时未更新**
- 判定口径在服务端 `api/alerts.go`（`now - updated_at ≥ 24 小时`），文档见 `docs/data-dictionary.md:78`
- 车停在原地不跑 ⇒ 不上报 ⇒ 24 小时后必然触发 ⇒ **这是预期行为**，不是故障

⇒ 该告警**早已通过飞书推过一次**（`alerted.data_stale=true` ⇒ 上升沿已触发，不会重复推），
弹窗只是让人误以为「报错了」。

## 三、修复建议（**由项目负责人排期，本轮未改动任何生产文件**）

### 3.1 首选：给任务加 `<Hidden>true</Hidden>`

在 `Export-ScheduledTask` 的 `<Settings>` 里加 `<Hidden>true</Hidden>`，
并重新注册该任务。**必须改任务的注册源头**，否则重装/重建会退回有窗口的版本。

⚠️ **注意**：仓库里目前**没有**创建 `TeslaMate-CarWatch` 的脚本
（`grep Register-ScheduledTask|schtasks /Create` 只命中 `scripts/hermes-gateway-autostart.ps1` 的注释，
以及 `docs/operations.md` 里的运维命令）。
⇒ 建议负责人**新增 `scripts/install-watch-task.ps1`**（幂等：先 `Unregister` 再 `Register`），
把「注册 + Hidden + 每 5 分钟」固化进仓库，避免这台机器上的任务成为**不可复现的手工产物**。

### 3.2 备选 / 配合：把 stdout 从窗口挪走

`watch-car.ps1` 的 `Write-Log` 用 `Write-Output` 写 stdout，是窗口里那行字的直接来源。
但 `Write-Log` 同时也写日志文件（`Write-LogQuiet`），**且被大量逻辑依赖**（返回值语义有陷阱，见脚本注释），
**不建议**为了消窗而改 `Write-Log` 语义。

⇒ 更稳的做法是**只做 3.1**（隐藏窗口），stdout 内容随窗口一起不再可见，日志文件与推送行为完全不受影响。

### 3.3 参考：本机已有的「隐藏启动」范式

同机 Hermes 已用 VBS 隐藏启动（启动文件夹 `Hermes_Gateway.vbs` → `sh.Run …, 0, False`），
且 `Hermes_Gateway_Auto.ps1:62-68` 内部 `Start-Process -WindowStyle Hidden`。
⇒ `TeslaMate-CarWatch` 若走同一范式（VBS 包装器或 `-WindowStyle Hidden`）也能消窗，
但**任务级 `<Hidden>` 更直接**，且不新增包装文件。

⚠️ **本轮未能实测 `<Hidden>` 方案的可见窗口差异**：用 `EnumWindows` 枚举可见顶层窗口时，
对照组与实验组都探到 0 个可见 pwsh 窗口（探针自身在后台会话里跑，无法真实复现桌面弹窗）。
⇒ 修复方案**必须由负责人实机验证**：改完后坐等一个 5 分钟窗口，确认不再弹。

### 3.4 顺带发现的另一个同源问题（优先级低于本条）

`Hermes-Gateway` 任务同样是 `InteractiveToken` + 无 `<Hidden>`，动作是
`pwsh.exe -NoProfile … -File "…\Hermes_Gateway_Auto.ps1"`（**每 10 分钟**），
且该脚本内部 `Start-Process -WindowStyle Hidden` 只作用于**子进程**，
**它自己那层 pwsh 仍会弹窗** ⇒ 用户看到的可能是**两个**来源交替弹（5 分钟 + 10 分钟）。
⇒ 建议同批修掉。

## 四、复现与核验命令（交负责人）

```powershell
# 看任务全貌
schtasks /Query /TN "TeslaMate-CarWatch" /FO LIST /V
Export-ScheduledTask -TaskName "TeslaMate-CarWatch"   # 看有无 <Hidden>

# 立即触发一次（修完后用它验证）
schtasks /Run /TN "TeslaMate-CarWatch"

# 看脚本每轮打印了什么（= 窗口里原本会显示的内容）
Get-Content C:\tm-ws\scripts\.watch-car.log -Tail 5 -Encoding utf8

# 直接看脚本 stdout（不碰真实状态）
pwsh -NoProfile -File C:\tm-ws\scripts\watch-car.ps1 -DryRun -StateFile <临时> -LogFile <临时>
```

## 五、本轮边界（重要）

- 本轮**只做只读排查**：未修改任何任务、脚本、配置、数据库；未触发任何用户可见推送
  （验证用 `-DryRun` + 临时状态/日志文件）
- 临时探针任务（`MCodeProbeA/B`）已删除，实测残留数 = 0
- 未动 `scripts/.watch-car-state.json` 与 `.watch-car.log`
- 按项目规则，**代码改动与版本号归属由「熟悉项目并担任项目负责人」决定**
  （当前线上版本号以 `/api/v1/app/latest` 与 CHANGELOG 顶部交接位为准）
