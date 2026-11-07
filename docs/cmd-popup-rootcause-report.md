# 弹窗根因报告：Windows 定时任务拉起控制台窗口

- 报告时间：2026-11-05 00:30
- **状态：已交接并落地（v0.134.0 首轮方案无效，v0.135.0 已修对）** ——
  v0.134.0（commit `af4ca29`）加的 `-WindowStyle Hidden` **在这台机器上不起作用**（用户 2026-11-07 22:19 回报仍在弹）；
  v0.135.0 改用 **`conhost.exe --headless` 显式承载 pwsh**（绕过 Windows Terminal 的 ConPTY 接管）⇒ 实测零窗口。
  报告随 commit `e15222e` 入库；更正随 commit `3744bbd` 入库
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
⇒ Windows 分配一个可见的控制台窗口；脚本跑完（几秒）窗口随进程退出而关闭
⇒ 表现为「定时弹出来一下然后就关闭」。

> ⚠️ **本报告初版把「加 `<Hidden>true</Hidden>`」当作消弹窗手段，这是错的**（已修正）。
> 任务 XML 的 `<Settings><Hidden>true</Hidden>` 只让任务**在任务计划程序 UI 里不显示**，
> **对控制台窗口毫无影响**；真正消弹窗的是动作参数 **`-WindowStyle Hidden`**
> （给 pwsh 传 `STARTUPINFO.wShowWindow = SW_HIDE` ⇒ 窗口创建时就是隐藏的）。
> ⇒ 两者**都做**：`-WindowStyle Hidden` 管消弹窗，`<Hidden>` 管不碍眼。
> （实证更正来自「熟悉项目并担任项目负责人」在 v0.134.0 的落地实测。）

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

## 三、修复建议（**已由「熟悉项目并担任项目负责人」在 v0.134.0 落地**）

### 3.1 首选：动作参数加 `-WindowStyle Hidden`（消弹窗靠这个）

把任务动作改成：

```
pwsh.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\tm-ws\scripts\watch-car.ps1"
```

⇒ pwsh 收到的 `STARTUPINFO.wShowWindow = SW_HIDE` ⇒ 控制台窗口**创建时就是隐藏的** ⇒ 不弹。

同时可加任务 XML 的 `<Settings><Hidden>true</Hidden>`——但那**只是不显示在任务计划程序 UI 里**，
**不能消弹窗**，两者别混为一谈。

**必须改任务的注册源头**，否则重装/重建会退回有窗口的版本。

⚠️ **注意**：仓库里目前**没有**创建 `TeslaMate-CarWatch` 的脚本
（`grep Register-ScheduledTask|schtasks /Create` 只命中 `scripts/hermes-gateway-autostart.ps1` 的注释，
以及 `docs/operations.md` 里的运维命令）。
⇒ 建议负责人**新增 `scripts/install-watch-task.ps1`**（幂等：先 `Unregister` 再 `Register`），
把「注册 + Hidden + 每 5 分钟」固化进仓库，避免这台机器上的任务成为**不可复现的手工产物**。

### 3.2 未采纳：改 `Write-Log` 的 stdout 语义（保持原样）

`watch-car.ps1` 的 `Write-Log` 用 `Write-Output` 写 stdout，是窗口里那行字的直接来源。
但 `Write-Log` 同时也写日志文件（`Write-LogQuiet`），**且被大量逻辑依赖**（返回值语义有陷阱，见脚本注释），
**不建议**为了消窗而改 `Write-Log` 语义。

⇒ 更稳的做法是**只做 3.1**（隐藏窗口），stdout 内容随窗口一起不再可见，日志文件与推送行为完全不受影响。

> ✅ **落地确认**：「熟悉项目并担任项目负责人」采纳了这条判断——v0.134.0 **未改** `watch-car.ps1` 的
> `Write-Log` 语义，只做隐藏窗口；stdout / 日志 / 推送行为均未受影响。

### 3.3 参考：本机已有的「隐藏启动」范式

同机 Hermes 已用 VBS 隐藏启动（启动文件夹 `Hermes_Gateway.vbs` → `sh.Run …, 0, False`），
且 `Hermes_Gateway_Auto.ps1:62-68` 内部 `Start-Process -WindowStyle Hidden`。
⇒ `TeslaMate-CarWatch` 走 `-WindowStyle Hidden` 即可消窗（无需新增包装文件），
这是比 VBS 包装器更轻的做法。

⚠️ **弹窗本身无法程序化验证**：用 `EnumWindows` 枚举可见顶层窗口时，
对照组与实验组都探到 0 个可见 pwsh 窗口（探针自身在后台会话里跑，无法真实复现桌面弹窗）。
⇒ 修复方案**必须实机肉眼验证**：改完后坐等一个 5 分钟窗口，确认不再弹。
（已写入 `docs/operations.md` §12.2 与 CHANGELOG v0.134.0，作为留给用户的最后一步。）

### 3.4 顺带发现的另一个同源问题（优先级低于本条）

`Hermes-Gateway` 任务同样是 `InteractiveToken` + 无 `<Hidden>`，动作是
`pwsh.exe -NoProfile … -File "…\Hermes_Gateway_Auto.ps1"`（**每 10 分钟**），
且该脚本内部 `Start-Process -WindowStyle Hidden` 只作用于**子进程**，
**它自己那层 pwsh 仍会弹窗** ⇒ 用户看到的可能是**两个**来源交替弹（5 分钟 + 10 分钟）。
⇒ 建议同批修掉（同样是给**任务动作**加 `-WindowStyle Hidden`，不是加 `<Hidden>`）。

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

## 五、本轮边界与落地状态

**排查轮（定位定时弹出 cmd 窗口的数据过期原因）的边界**：


- 本轮**只做只读排查**：未修改任何任务、脚本、配置、数据库；未触发任何用户可见推送
  （验证用 `-DryRun` + 临时状态/日志文件）
- 临时探针任务（`MCodeProbeA/B`）已删除，实测残留数 = 0
- 未动 `scripts/.watch-car-state.json` 与 `.watch-car.log`
- 按项目规则，**代码改动与版本号归属由「熟悉项目并担任项目负责人」决定**
  （当前线上版本号以 `/api/v1/app/latest` 与 CHANGELOG 顶部交接位为准）

---

## 六、落地状态（v0.134.0 · commit `af4ca29`，已逐字节核对）

| 项 | 状态 |
|---|---|
| **`TeslaMate-CarWatch` 动作（v0.135.0）** | ✅ `C:\WINDOWS\System32\conhost.exe --headless "C:\Program Files\PowerShell\7\pwsh.exe" -NoProfile -ExecutionPolicy Bypass -File "C:\tm-ws\scripts\watch-car.ps1"` |
| **`Hermes-Gateway` 动作（v0.135.0）** | ✅ 同样改为 `conhost.exe --headless` 承载 |
| 两个任务 XML | ✅ 均保留 `<Hidden>true</Hidden>`（只管 UI 不碍眼）、`LogonType=InteractiveToken`、`PT5M` / `PT10M` |
| `scripts/install-watch-task.ps1` | ✅ 已改为 conhost 承载 + 补 `$conhost` 存在性校验 + 文件头写真因与实测对照表 |
| `scripts/install-hermes-gateway-task.ps1` | ✅ 同上 |
| 安装脚本可重现性 | ✅ 跑完两个脚本 ⇒ 注册结果与现网一致（重建不会退回有窗口的版本） |
| 运行验证 | ✅ CarWatch `LastResult=0`、`scripts/.watch-car.log` 照常追加「本轮结束：…」；Hermes `LastResult=0`、网关进程数仍为 1、自启日志「已在运行 ⇒ 跳过」（幂等没坏） |
| 飞书通道 | ✅ 未受影响（网关进程数始终为 1） |
| `watch-car.ps1` 的 `Write-Log` 语义 | ✅ 未改（stdout / 日志 / 推送行为不受影响） |
| **弹窗实测** | ✅ **22:44:16 真实触发点，观察者捕获到 0 个可见窗口**（同一观察者在 22:34:16 / 22:39:18 两个触发点都抓到了 `PseudoConsoleWindow` ⇒ 探针有效） |
| **最终肉眼确认** | ⏳ 仍请用户看一眼接下来的触发点（22:51 / 22:56 / 23:01 …） |

> 📌 **本文件已按落地实测更正**：初版把「加 `<Hidden>true</Hidden>`」当作消弹窗手段是**错的**——
> 它只让任务在任务计划程序 UI 里不显示。

---

## 七、v0.135.0 续：v0.134.0 的方案也是错的（用户回报仍在弹）

### 7.1 现象

v0.134.0 落地后，用户 2026-11-07 22:19 回报：**仍然弹，而且一次弹两个，一瞬间就关**。
⇒ 两个任务当次 LastRun 恰为 **22:19:16 / 22:19:18**（相差 2 秒）⇒ 与描述完全吻合。

### 7.2 真因：`-WindowStyle Hidden` 在这台机器上无效

本机 pwsh 由 **Windows Terminal** 承载（`HKCU\Console\%%Startup\DelegateTerminal` 为空 ⇒ 交给 Windows 决定 ⇒ 选 Terminal）。
⇒ Terminal 用 **ConPTY / `PseudoConsoleWindow`** 渲染会话，**它不看 `STARTUPINFO.wShowWindow`**
⇒ pwsh 自己设的 `SW_HIDE` **被忽略** ⇒ 窗口照弹。

⇒ **`<Hidden>` 与 `-WindowStyle Hidden` 在这台机器上都不能消弹窗。**

### 7.3 探针方法论修正（这一轮最值钱的东西）

前两轮用 `EnumWindows` 枚举可见顶层窗口，**对照组与实验组都探到 0** ⇒ 当时判「弹窗无法程序化验证」。

⇒ **那个结论本身是错的**：探针跑在**后台会话**里，桌面窗口属于**交互式会话**（WinSta0\Default），
**后台会话枚举不到桌面窗口** ⇒ 差异被一起抹平 ⇒ 实验无效。

⇒ **正解：观察者自己也要跑在交互式会话里**（用同样 `InteractiveToken` 的计划任务拉起它）。
⇒ 改后立刻看见桌面上 17~19 个真实窗口（explorer / Edge / Lightroom / MiniMax Code…），实验才成立。

### 7.4 实测对照表（观察者 40ms 轮询可见顶层窗口）

| 时刻 | 方案 | 捕获结果 |
|---|---|---|
| 22:34:16 | `-WindowStyle Hidden`（v0.134.0 方案） | ⚠️ `proc=pwsh class='PseudoConsoleWindow'` ⇒ **弹了** |
| 22:39:18 | 同上（Hermes） | ⚠️ `proc=pwsh class='PseudoConsoleWindow'` ⇒ **弹了** |
| 22:44:16 | `conhost.exe --headless` 承载 pwsh | ✅ **无任何窗口** |

### 7.5 修法

动作改用 **`conhost.exe --headless` 显式承载 pwsh** ⇒ 绕开 Terminal 的 ConPTY 接管 ⇒ 桌面根本不创建窗口。
两个任务都改了，两个 `install-*.ps1` 都同步固化（含 `$conhost` 存在性校验）。

### 7.6 试过但**没采用**

1. **VBS 包装器**（`WScript.Shell.Run …, 0, False`）—— ⚠️ **WScript 按 ANSI 解码命令行参数**
   ⇒ 中文路径变乱码（实测：`arg0` 里「<用户名>」被解成 **GBK 乱码**而不是中文）⇒ `FileExists` 失败 ⇒ **静默退出**。
   要它工作，VBS 与被调脚本**路径必须全 ASCII**；加上「静默失败 = 任务静默不工作」的风险，
   不如 `conhost --headless`（Windows 自带、参数即失败码）。
2. **改系统 `DelegateTerminal=0`** —— 会改变用户全局终端行为，副作用超出本项目范围 ⇒ 不做。
