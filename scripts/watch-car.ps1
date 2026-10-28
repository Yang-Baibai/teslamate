#!/usr/bin/env pwsh
# 车况监听 → Hermes 推送（**主动通知引擎**）
#
# 职责：轮询本机 API 的 /cars/{id}/status，判断「此刻有哪些问题成立」，
#       把**新成立**的问题推给用户，恢复时再推一条「已恢复」把通道闭上。
#
# 为什么放在服务端（而不是 App 里）：
#   这台电脑 7×24 开着，是 TeslaMate 的宿主，手机可能连不上它（外出/Tailscale 卡住）。
#   判定口径也统一在 API 层（api/alerts.go），App 和本脚本读同一份结果，不会自相矛盾。
#
# ⚠️ **v0.77.0：这一版重写了通知引擎本身。**
#
# 旧版有四个洞，每一个都是「用户会把通知关掉」级别的：
#
#   ① **告警风暴** —— 取数失败时用 `failCount -ge 3` 判定，
#      于是**越过阈值后每一轮都推**。实测 2026-10-27 01:10→11:05 推了 **97 次**
#      「⚠️ 车况服务取不到数据」。99 条里 97 条是噪音 ⇒ 用户直接静音 ⇒ 通道作废。
#      现在改成「**首次越过阈值才推**」，之后保持安静。
#
#   ② **状态丢失** —— 失败路径写状态文件时**只写** `{fail_count, last_check_at}`，
#      把 `last_charging` / `last_tpms_alert` / `last_stale` / `last_fleet_*` 全冲掉。
#      于是服务一恢复，第一轮会把「开始充电」「胎压异常」「数据过期」**全部重推一遍**。
#      现在改成**合并写**：只更新本轮真的变的字段，其余原样保留。
#
#   ③ **没有闭环** —— 恢复时一条消息都不推。
#      用户收到「服务挂了」之后，永远不知道它好了没有 —— 通道张着口不闭合。
#      现在恢复时推一条「已恢复」。
#
#   ④ **该提醒的不提醒** —— 「车一直没锁」「车窗一直开着」这类**最要紧**的事没有提醒。
#      现在补上（带超时，避免「刚开门拿东西」就吵）。
#
# ## 引擎的三个不变量（改这个脚本时不要破坏它们）
#
#   · **上升沿**：一条问题「本轮成立且上轮不成立」才推。恢复时推一条「已恢复」。
#   · **合并写**：任何一轮都只更新本轮真的变的字段，绝不覆盖别人的字段。
#   · **不确定 ≠ 否定**：读不到锁车状态（null）**不等于**「车没锁」——
#     计时器在「读不到」时保持不动，而不是重置。
#
# 用法：
#   pwsh -File scripts/watch-car.ps1            # 正常跑一轮
#   pwsh -File scripts/watch-car.ps1 -DryRun    # 只记日志不发送（首次部署用）
#   pwsh -File scripts/watch-car-selftest.ps1   # 引擎自测（不需要车）

param(
  [switch]$DryRun,
  # ── 测试钩子（自测用，正常跑不要传）──
  [string]$StateFile = '',    # 覆盖状态文件路径
  [string]$StatusFile = '',   # 从 JSON 文件读车况（不给就走 HTTP）
  [string]$LogFile = '',      # 覆盖日志路径
  [string]$ApiBase = '',      # 覆盖 API 地址
  [string]$CarName = '',      # 覆盖车名（省一次 /cars 请求）
  [int]$UnlockTimeoutMinutes = 30,   # 未锁车多久算问题
  [int]$WindowTimeoutMinutes = 30,   # 车窗未关多久算问题
  [int]$FailThreshold = 3             # 连续失败几轮才推「服务挂了」
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root       = Split-Path -Parent $PSScriptRoot
$EnvFile    = Join-Path $Root '.env'
if (-not $StateFile) { $StateFile = Join-Path $Root 'scripts/.watch-car-state.json' }
if (-not $LogFile)   { $LogFile   = Join-Path $Root 'scripts/.watch-car.log' }

# ── 读配置：真实环境变量优先于 .env ────────────────────────────────
# 环境变量优先的好处：临时改配置不用动 .env（测试、临时换通道都用它）。
function Get-EnvValue([string]$key, [string]$default = '') {
  $fromEnv = [Environment]::GetEnvironmentVariable($key, 'Process')
  if ($fromEnv) { return $fromEnv }
  if (-not (Test-Path $EnvFile)) { return $default }
  $line = Select-String -Path $EnvFile -Pattern "^$([regex]::Escape($key))=(.*)$" -EA SilentlyContinue |
    Select-Object -First 1
  if (-not $line) { return $default }
  $v = ($line.Matches[0].Groups[1].Value).Trim()
  if ($v) { return $v } else { return $default }
}

if (-not $ApiBase) { $ApiBase = Get-EnvValue 'WATCH_API_BASE' 'http://127.0.0.1:8090' }
$ApiToken   = Get-EnvValue 'API_TOKEN'
$CarId      = Get-EnvValue 'WATCH_CAR_ID' '1'
$HermesTo   = Get-EnvValue 'WATCH_HERMES_TARGET' 'feishu'
$HermesBin  = Get-EnvValue 'HERMES_BIN' (Join-Path $env:LOCALAPPDATA 'hermes\bin\hermes.exe')

# ⚠️ DryRun / 自测模式**不要求**令牌与 Hermes 可执行文件存在 ——
# 自测就是要能在没有 Hermes 的机器上跑。
if (-not $DryRun -and -not $StatusFile -and -not $ApiToken) {
  Write-Output "❌ .env 里没有 API_TOKEN，退出"; exit 1
}
if (-not $DryRun -and -not (Test-Path $HermesBin)) {
  Write-Output "❌ 找不到 Hermes 可执行文件: $HermesBin（可用环境变量 HERMES_BIN 指定）"
  exit 1
}

# ── 日志 ──────────────────────────────────────────────────────────
#
# ⚠️ **两个日志函数，不是一个** —— 这是 PowerShell 的一个真陷阱：
# 函数里 `Write-Output` 的内容会混进**返回值**。
# 如果 Send-Hermes 顺手把日志也 Write-Output 出去，
# 那么 `if (Send-Hermes ...)` 拿到的是「日志行 + true」的**数组**，
# 而非空数组在 `if` 里恒为真 ⇒ **推送失败也会被当成成功**，
# 于是告警被标记成「已告知」，用户永远收不到。
# 所以：会返回值的函数一律用 Write-LogQuiet（只写文件）。
function Format-LogLine([string]$msg) {
  "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
}

function Write-LogQuiet([string]$msg) {
  $line = Format-LogLine $msg
  try { Add-Content -LiteralPath $LogFile -Value $line -Encoding utf8 } catch { }
}

function Write-Log([string]$msg) {
  Write-Output (Format-LogLine $msg)
  Write-LogQuiet $msg
}

# ── 发送消息 ──────────────────────────────────────────────────────
# 用 --file 传正文：中文消息里有引号/括号，走命令行参数容易被 PowerShell 拆碎。
$MsgTmp = Join-Path ([System.IO.Path]::GetTempPath()) ("teslamate-msg-" + [guid]::NewGuid().ToString('N') + ".txt")

# 返回 $true = 送达（调用方据此决定要不要重试）。
function Send-Hermes([string]$subject, [string]$body) {
  if ($DryRun) {
    Write-LogQuiet "[DryRun] 标题: $subject"
    Write-LogQuiet "[DryRun] 正文: $body"
    Write-Host "[DryRun] $subject"
    return $true
  }
  try {
    Set-Content -LiteralPath $MsgTmp -Value $body -Encoding utf8
    # & 调 .exe；--quiet 只看退出码，成功时 stdout 无噪音
    & $HermesBin send --to $HermesTo --subject $subject --file $MsgTmp --quiet 2>&1 |
      ForEach-Object { if ($_ -match '\S') { Write-LogQuiet "  hermes: $_" } }
    if ($LASTEXITCODE -eq 0) {
      Write-LogQuiet "✅ 已推送[$HermesTo] $subject"
      return $true
    } else {
      Write-LogQuiet "❌ 推送失败[$HermesTo] 退出码=$LASTEXITCODE"
      return $false
    }
  } catch {
    Write-LogQuiet "❌ 推送异常: $($_.Exception.Message)"
    return $false
  } finally {
    Remove-Item -LiteralPath $MsgTmp -Force -EA SilentlyContinue
  }
}

# ── 安全取属性 ────────────────────────────────────────────────────
# API 对空值字段用了 omitempty（如没充电时就不返回 active_charge），
# 而 Set-StrictMode 下访问不存在的属性会直接报错，所以统一走这个取。
#
# ⚠️ 必须同时支持 **hashtable** 与 **PSCustomObject**：
# hashtable 的键**不在** `PSObject.Properties` 里（那是 Hashtable 自己的成员表），
# 只按 PSCustomObject 取会永远取不到 —— 状态文件读回来正是 hashtable。
function Get-Prop($obj, [string]$name, $default = $null) {
  if ($null -eq $obj) { return $default }
  if ($obj -is [System.Collections.IDictionary]) {
    if (-not $obj.Contains($name)) { return $default }
    $v = $obj[$name]
    if ($null -eq $v) { return $default }
    return $v
  }
  $p = $obj.PSObject.Properties[$name]
  if ($null -eq $p) { return $default }
  if ($null -eq $p.Value) { return $default }
  return $p.Value
}

# ── 状态文件：读 / 合并写 ─────────────────────────────────────────
#
# ⚠️ **合并写是这一版的核心修复**（旧版的第二个洞）。
#
# 旧版失败路径直接 `Set-Content` 一个只含两三个键的新对象，
# 把 `last_charging` / `last_tpms_alert` / `last_stale` / `last_fleet_*` 全冲掉 ——
# 于是服务恢复后第一轮会把这些**全部重推一遍**（用户看到「充电开始」紧接着「充电完成」）。
#
# 现在：读出旧状态 → 只覆盖本轮真的变的键 → 写回。
# 任何一个**没被本轮提到**的键都原样保留。
function ConvertTo-Hashtable($obj) {
  $h = @{}
  if ($null -eq $obj) { return $h }
  if ($obj -is [System.Collections.IDictionary]) {
    foreach ($k in $obj.Keys) { $h[[string]$k] = $obj[$k] }
    return $h
  }
  foreach ($p in $obj.PSObject.Properties) { $h[$p.Name] = $p.Value }
  return $h
}

function Read-State {
  if (-not (Test-Path -LiteralPath $StateFile)) { return @{} }
  try {
    $raw = Get-Content -LiteralPath $StateFile -Raw -Encoding utf8
    if (-not $raw) { return @{} }
    return ConvertTo-Hashtable ($raw | ConvertFrom-Json)
  } catch {
    Write-LogQuiet "⚠️ 状态文件读不了（当空处理）: $($_.Exception.Message)"
    return @{}
  }
}

function Save-State([hashtable]$patch) {
  $cur = Read-State
  foreach ($k in $patch.Keys) { $cur[$k] = $patch[$k] }
  try {
    ($cur | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $StateFile -Encoding utf8
  } catch {
    Write-LogQuiet "❌ 状态文件写失败: $($_.Exception.Message)"
  }
}

# 状态里的 `alerted` 是「已经告诉过用户」的条件集合（code → bool）。
function Get-Alerted($state) {
  $v = Get-Prop $state 'alerted' $null
  if ($null -eq $v) { return @{} }
  return ConvertTo-Hashtable $v
}

# ── 时间工具 ──────────────────────────────────────────────────────
function Get-SinceMinutes($iso, [datetime]$nowUtc) {
  if (-not $iso) { return $null }
  try {
    $dto = [datetimeoffset]::Parse([string]$iso)
    return ($nowUtc - $dto.UtcDateTime).TotalMinutes
  } catch {
    return $null
  }
}

# ── 告警判定与推送（统一上升沿）──────────────────────────────────
#
# $Alerted 是 hashtable（引用类型），这里直接改它。
#
# 规则：
#   成立且没告诉过 → 推告警，标记为「已告知」
#   不成立且告诉过 → 推「已恢复」，取消标记
#   其它           → 什么都不做（**不重复推**）
#
# 推失败时**不**改标记 ⇒ 下一轮会重试（通知没送到用户就等于没告知）。
function Update-Alert(
  [hashtable]$Alerted, [string]$Code, [bool]$Active,
  [string]$Title, [string]$Body, [string]$RTitle, [string]$RBody
) {
  $was = $false
  if ($Alerted.ContainsKey($Code)) { $was = [bool]$Alerted[$Code] }
  if ($Active -and -not $was) {
    if (Send-Hermes $Title $Body) { $Alerted[$Code] = $true }
  } elseif ((-not $Active) -and $was) {
    if (Send-Hermes $RTitle $RBody) { $Alerted[$Code] = $false }
  }
}

# ═════════════════════════════════════════════════════════════════
# 一、拉车况
# ═════════════════════════════════════════════════════════════════
$st = $null
$fetchError = $null
if ($StatusFile) {
  try {
    $st = Get-Content -LiteralPath $StatusFile -Raw -Encoding utf8 | ConvertFrom-Json
  } catch {
    $fetchError = "读状态文件失败: $($_.Exception.Message)"
  }
} else {
  try {
    $hdr = @{ Authorization = "Bearer $ApiToken" }
    $st  = Invoke-RestMethod -Uri "$ApiBase/api/v1/cars/$CarId/status" -Headers $hdr -TimeoutSec 15
  } catch {
    $fetchError = $_.Exception.Message
  }
}

$prev      = Read-State
$alerted   = Get-Alerted $prev
$nowUtc    = (Get-Date).ToUniversalTime()
$nowIso    = $nowUtc.ToString('o')

# ═════════════════════════════════════════════════════════════════
# 二、取不到数据 —— 只推一次，**合并写**，不冲掉任何状态
# ═════════════════════════════════════════════════════════════════
if ($null -ne $fetchError) {
  Write-Log "❌ 取状态失败: $fetchError"

  $failCount = 1
  if ($prev.ContainsKey('fail_count')) { $failCount = [int]$prev['fail_count'] + 1 }

  # ⚠️ **只推一次**（旧版的第一个洞：`failCount -ge 3` 越过阈值后每轮都推 ⇒ 实测 97 次）。
  #    阈值只用来决定「什么时候第一次说」，不决定「说几次」。
  Update-Alert $alerted 'service_unreachable' ($failCount -ge $FailThreshold) `
    "⚠️ 车况服务取不到数据" `
    "已连续 $failCount 轮无法从服务器读取车况。`n请确认家里那台电脑开着、服务器在跑，以及手机上的 Tailscale 已连接。" `
    "✅ 车况服务已恢复" `
    "已重新读到车况数据，接下来继续盯着。"

  # ⚠️ **合并写**（旧版的第二个洞）：只更新失败计数与告警集合，
  #    `last_charging` / `unlocked_since` / `alerted` 里其它条件的标记**一个都不动**。
  Save-State @{
    fail_count    = $failCount
    alerted       = $alerted
    last_check_at = $nowIso
  }
  exit 2
}

# ═════════════════════════════════════════════════════════════════
# 三、读车况事实
# ═════════════════════════════════════════════════════════════════
$chargeInfo = Get-Prop $st 'active_charge'
$charging   = $null -ne $chargeInfo
$battery    = Get-Prop $st 'battery_level'
$addedKwh   = if ($charging) { Get-Prop $chargeInfo 'energy_added_kwh' } else { $null }
$tpmsAlert  = [bool](Get-Prop $st 'tpms_alert')
$dataStale  = [bool](Get-Prop $st 'data_stale')

# ⚠️ v0.22.0：Fleet 实时状态（四态）。
#
# 为什么推送脚本必须读它：
# 中国区的 refresh_token 是**一次性**的，授权过期后 Fleet **永远不会自己恢复** ——
# 锁车与实时胎压会**静默停更**，而这个脚本原本完全不读 Fleet 状态，
# 于是「锁没锁」这个车主第一诉求，可能一周都没人发现。
#
# unauthorized  → 不会自愈，要人工在服务器上重新授权
# unreachable  → 会自愈（退避重试中），但还是值得说一声
$fleetState = [string](Get-Prop $st 'fleet_state' 'ok')

if (-not $CarName) {
  $CarName = try {
    (Invoke-RestMethod -Uri "$ApiBase/api/v1/cars" -Headers @{ Authorization = "Bearer $ApiToken" } -TimeoutSec 10 |
      Where-Object { $_.id -eq [int]$CarId } | Select-Object -First 1).name
  } catch { "车辆" }
}
if (-not $CarName) { $CarName = "车辆" }

# ── 锁车状态：三态 ────────────────────────────────────────────────
# $null = **不知道**（没配凭据 / 拉取失败），**不等于**「没锁」。
$lockedRaw = Get-Prop $st 'locked' $null

# ── 车窗状态：三态 ────────────────────────────────────────────────
# ⚠️ 数组要显式 @() 包一层：PowerShell 函数返回空数组会被**解包成 $null**，
#    「全关」会被误判成「不知道」—— 那样计时器永远不结束。
$openWindows = $null
$owProp = $st.PSObject.Properties['open_windows']
if ($null -ne $owProp -and $null -ne $owProp.Value) {
  $openWindows = @($owProp.Value)
}

# ═════════════════════════════════════════════════════════════════
# 四、计时器：未锁车 / 车窗未关
# ═════════════════════════════════════════════════════════════════
#
# 为什么计时器在**脚本**这一层（而不是服务端）：
#   「开着多久了才该提醒」是**通知策略**，不是车的事实；
#   服务端重启会让内存里的计时归零 ⇒ 告警反复重来。
#   脚本的状态文件在磁盘上，重启不丢 ⇒ 计时连续。
#
# 三态处理：
#   事实成立   → 计时开始 / 继续
#   事实不成立 → 计时清零
#   读不到    → **计时保持不动**（读不到 ≠ 已经好了）

$unlockedSince = Get-Prop $prev 'unlocked_since' $null
if ($null -ne $lockedRaw) {
  if ([bool]$lockedRaw) {
    $unlockedSince = $null                      # 锁好了 → 计时清零
  } elseif (-not $unlockedSince) {
    $unlockedSince = $nowIso                    # 开始计时
  }
}
# lockedRaw 为 null（不知道）⇒ 计时保持不动
$unlockedMinutes = Get-SinceMinutes $unlockedSince $nowUtc
$unlockedTimeout = ($null -ne $unlockedMinutes) -and ($unlockedMinutes -ge $UnlockTimeoutMinutes)

$windowSince = Get-Prop $prev 'window_open_since' $null
if ($null -ne $openWindows) {
  if ($openWindows.Count -eq 0) {
    $windowSince = $null                        # 全关上了 → 计时清零
  } elseif (-not $windowSince) {
    $windowSince = $nowIso                      # 开始计时
  }
}
# openWindows 为 null（不知道）⇒ 计时保持不动
$windowMinutes = Get-SinceMinutes $windowSince $nowUtc
$windowTimeout = ($null -ne $windowMinutes) -and ($windowMinutes -ge $WindowTimeoutMinutes)

# ═════════════════════════════════════════════════════════════════
# 五、逐条判定（上升沿 + 恢复闭环）
# ═════════════════════════════════════════════════════════════════

# ── 充电 ──
# 「开始充电 / 充电结束」也是一对**上升沿**：false→true 推开始，true→false 推结束。
# 首轮不推（车在你装脚本时就在充电，不等于「刚刚开始充电」）。
$chargingKnown = $prev.ContainsKey('last_charging')
$socNow = if ($null -ne $battery) { "$battery%" } else { "未知" }
$prevBat = Get-Prop $prev 'last_battery'
$socPrev = if ($null -ne $prevBat) { "$prevBat%" } else { "未知" }
$prevAdded = Get-Prop $prev 'last_added_kwh'
$extra = if ($null -ne $prevAdded) { "`n充入 $([math]::Round([double]$prevAdded,1)) kWh" } else { "" }

# ⚠️ **v0.78.0：充电结束 ≠ 充电完成**。
#
# 旧版不管充到哪儿，结束一律说「✅ 充电完成」。
# 实测本机最近一次（2026-10-28，Tesla 超充）：54% → 80%，充电上限 100% ——
# **根本没充满**，旧版却说「完成」。
#
# 这和 v0.76.0 修掉的「车端拒绝过的事被报成已下发」是同一类错：**假成功**。
# 用户看到「完成」就不会再看第二眼，而真正需要知道「为什么没充满」的时刻被错过了。
#
# 结局由**服务端**判定（收尾曲线 + 车端状态），文案也由服务端给 ——
# 这里只把结局翻译成标题，**不自己编结论**（三处各写一份文案，迟早漂移）。
$outcome     = Get-Prop $st 'charge_outcome' $null
$outcomeKind = [string](Get-Prop $outcome 'outcome' '')
$outcomeText = [string](Get-Prop $outcome 'detail' '')

$endTitle = switch ($outcomeKind) {
  'complete'    { "✅ $CarName 充电完成" }
  'interrupted' { "⚠️ $CarName 充电中断" }
  'no_power'    { "⚠️ $CarName 充电中断（充电桩没供电）" }
  # ⚠️ 「结束」而不是「完成」—— 没到上限就是没到上限，不能粉饰。
  default       { "🔌 $CarName 充电结束" }
}
# 服务端没给结论（老服务端 / 没数据）⇒ 只报事实，**绝不说「完成」**。
$endBody = if ($outcomeText) { $outcomeText } else { "当前电量 $socPrev$extra" }

Update-Alert $alerted 'charging' ($charging -and $chargingKnown) `
  "🔌 $CarName 开始充电" `
  "当前电量 $socNow`n充电结束会通知你。" `
  $endTitle `
  $endBody

# ── 胎压 ──
$tpmsDetail = Get-Prop $st 'tpms_alert_detail' ''
$wheels = @(Get-Prop $st 'tpms_alert_wheels' @())
$wheelsText = if ($wheels.Count -gt 0) { $wheels -join '、' } else { "未知" }
Update-Alert $alerted 'tpms' $tpmsAlert `
  "⚠️ $CarName 胎压异常 · 建议检查" `
  "$tpmsDetail`n异常轮胎：$wheelsText`n可能是漏气或胎压异常，建议尽快检查。" `
  "✅ $CarName 胎压已恢复正常" `
  "胎压已回到正常范围，检查过就好。"

# ── 数据过期 ──
$ageH = Get-Prop $st 'data_age_hours' 0
Update-Alert $alerted 'data_stale' $dataStale `
  "ⓘ $CarName 数据已 $ageH 小时未更新" `
  "车辆长时间没有上报数据。`n车停着不动属正常；若你人在外地，也可能是网络不通，请留意。" `
  "✅ $CarName 车况数据已恢复更新" `
  "车辆又开始上报数据了。"

# ── Fleet 授权失效 ──
Update-Alert $alerted 'fleet_unauthorized' ($fleetState -eq 'unauthorized') `
  "🔑 $CarName Tesla 授权已失效 · 锁车状态已停更" `
  "实时状态拿不到了（一次性令牌过期，重试也不会成功）。`n请在服务器上重新授权：scripts/tesla-authorize.ps1`n在此之前，App 里的锁车状态与实时胎压都不可信。" `
  "✅ $CarName Tesla 授权已恢复" `
  "实时状态重新开始更新，锁车与胎压都可信了。"

# ── Fleet 连不上 ──
Update-Alert $alerted 'fleet_unreachable' ($fleetState -eq 'unreachable') `
  "📡 $CarName 暂时连不上 Tesla 云端" `
  "实时状态暂时取不到（会自己恢复）。`n锁车与实时胎压显示的是最后一次成功获取的数据。" `
  "✅ $CarName 已重新连上 Tesla 云端" `
  "实时状态已恢复更新。"

# ── 服务恢复（取数失败那条的闭环）──
Update-Alert $alerted 'service_unreachable' $false `
  "⚠️ 车况服务取不到数据" `
  "已连续多轮无法从服务器读取车况。" `
  "✅ 车况服务已恢复" `
  "已重新读到车况数据，接下来继续盯着。"

# ── 未锁车超时 ──
$unlockedSinceText = if ($unlockedSince) {
  ([datetimeoffset]::Parse([string]$unlockedSince)).ToLocalTime().ToString('MM-dd HH:mm')
} else { "" }
Update-Alert $alerted 'unlocked_timeout' $unlockedTimeout `
  "🔓 $CarName 已解锁超过 $UnlockTimeoutMinutes 分钟" `
  "从 $unlockedSinceText 起一直没上锁。`n如果已经停好了，回 App 点一下「锁车」就行。" `
  "✅ $CarName 车已锁好" `
  "锁车状态恢复正常。"

# ── 车窗未关超时 ──
$windowText = if ($openWindows -and $openWindows.Count -gt 0) { $openWindows -join '、' } else { "未知" }
$windowSinceText = if ($windowSince) {
  ([datetimeoffset]::Parse([string]$windowSince)).ToLocalTime().ToString('MM-dd HH:mm')
} else { "" }
Update-Alert $alerted 'window_open_timeout' $windowTimeout `
  "⚠️ $CarName 车窗未关超过 $WindowTimeoutMinutes 分钟" `
  "开着的是：$windowText（自 $windowSinceText 起）。`n停在露天的话记得关窗。" `
  "✅ $CarName 车窗已关好" `
  "车窗状态恢复正常。"

# ═════════════════════════════════════════════════════════════════
# 六、存新状态（合并写）
# ═════════════════════════════════════════════════════════════════
Save-State @{
  last_charging     = $charging
  last_battery      = $battery
  last_added_kwh    = if ($charging) { $addedKwh } else { Get-Prop $prev 'last_added_kwh' }
  unlocked_since    = $unlockedSince
  window_open_since = $windowSince
  alerted           = $alerted
  fail_count        = 0
  last_check_at     = $nowIso
}

$unlockedM = 0; if ($null -ne $unlockedMinutes) { $unlockedM = [math]::Round([double]$unlockedMinutes, 1) }
$windowM = 0;    if ($null -ne $windowMinutes)    { $windowM = [math]::Round([double]$windowMinutes, 1) }
$mark = (($alerted.Keys | Sort-Object) -join ',')
# ⚠️ 这里**必须**用 ${}：PowerShell 的变量名可以含中文，
#    写成 "$unlockedM分钟" 会被解析成变量 `$unlockedM分钟`（不存在）⇒ 直接报错。
#    同款陷阱在 Kotlin 模板里也咬过两次（"$s.stopCount"）。
Write-Log "本轮结束：充电=$charging 电量=$battery 胎压告警=$tpmsAlert 数据过期=$dataStale fleet=$fleetState 未锁=$unlockedTimeout(持续${unlockedM}分钟) 车窗=$windowTimeout(持续${windowM}分钟) 已告知=[$mark]"
