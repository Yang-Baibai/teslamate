#!/usr/bin/env pwsh
# 车况监听 → Hermes 推送
#
# 职责：轮询本机 API 的 /cars/{id}/status，检测状态跳变，调用 Hermes 推送到微信/飞书。
#
# 为什么放在服务端（而不是 App 里）：
#   这台电脑 7×24 开着，是 TeslaMate 的宿主，手机可能连不上它（外出/Tailscale 卡住）。
#   判定口径也统一在 API 层（api/alerts.go），App 和本脚本读同一份结果，不会自相矛盾。
#
# 触发场景（只在状态「跳变」时推，否则每 5 分钟一条噪音，几天后就会被关掉）：
#   • 开始充电 → 通知
#   • 停止充电 → 通知（带最终电量与充入量）
#   • 胎压异常 → 通知（同一异常只推一次，恢复正常后才重置）
#   • 数据超过 N 小时未更新 → 通知
#
# 用法：
#   pwsh -File scripts/watch-car.ps1            # 正常跑一轮
#   pwsh -File scripts/watch-car.ps1 -DryRun    # 只打印不发送（首次部署用）

param(
  [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root       = Split-Path -Parent $PSScriptRoot
$EnvFile    = Join-Path $Root '.env'
$StateFile  = Join-Path $Root 'scripts/.watch-car-state.json'
$LogFile    = Join-Path $Root 'scripts/.watch-car.log'

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

$ApiBase    = Get-EnvValue 'WATCH_API_BASE' 'http://127.0.0.1:8090'
$ApiToken   = Get-EnvValue 'API_TOKEN'
$CarId      = Get-EnvValue 'WATCH_CAR_ID' '1'
$HermesTo   = Get-EnvValue 'WATCH_HERMES_TARGET' 'feishu'
$HermesBin  = Get-EnvValue 'HERMES_BIN' (Join-Path $env:LOCALAPPDATA 'hermes\bin\hermes.exe')

if (-not $ApiToken) { Write-Output "❌ .env 里没有 API_TOKEN，退出"; exit 1 }
if (-not (Test-Path $HermesBin)) {
  Write-Output "❌ 找不到 Hermes 可执行文件: $HermesBin（可用环境变量 HERMES_BIN 指定）"
  exit 1
}

function Write-Log([string]$msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  Write-Output $line
  try { Add-Content -LiteralPath $LogFile -Value $line -Encoding utf8 } catch { }
}

# ── 发送消息 ──────────────────────────────────────────────────────
# 用 --file 传正文：中文消息里有引号/括号，走命令行参数容易被 PowerShell 拆碎。
$MsgTmp = Join-Path ([System.IO.Path]::GetTempPath()) ("teslamate-msg-" + [guid]::NewGuid().ToString('N') + ".txt")

function Send-Hermes([string]$subject, [string]$body) {
  if ($DryRun) {
    Write-Log "[DryRun] 标题: $subject"
    Write-Log "[DryRun] 正文: $body"
    return
  }
  try {
    Set-Content -LiteralPath $MsgTmp -Value $body -Encoding utf8
    # & 调 .exe；--quiet 只看退出码，成功时 stdout 无噪音
    & $HermesBin send --to $HermesTo --subject $subject --file $MsgTmp --quiet 2>&1 |
      ForEach-Object { if ($_ -match '\S') { Write-Log "  hermes: $_" } }
    if ($LASTEXITCODE -eq 0) {
      Write-Log "✅ 已推送[$HermesTo] $subject"
    } else {
      Write-Log "❌ 推送失败[$HermesTo] 退出码=$LASTEXITCODE"
    }
  } catch {
    Write-Log "❌ 推送异常: $($_.Exception.Message)"
  } finally {
    Remove-Item -LiteralPath $MsgTmp -Force -EA SilentlyContinue
  }
}

# ── 安全取属性 ────────────────────────────────────────────────────
# API 对空值字段用了 omitempty（如没充电时就不返回 active_charge），
# 而 Set-StrictMode 下访问不存在的属性会直接报错，所以统一走这个取。
function Get-Prop($obj, [string]$name, $default = $null) {
  if ($null -eq $obj) { return $default }
  $p = $obj.PSObject.Properties[$name]
  if ($null -eq $p) { return $default }
  if ($null -eq $p.Value) { return $default }
  return $p.Value
}

# ── 拉状态 ────────────────────────────────────────────────────────
try {
  $hdr  = @{ Authorization = "Bearer $ApiToken" }
  $st   = Invoke-RestMethod -Uri "$ApiBase/api/v1/cars/$CarId/status" -Headers $hdr -TimeoutSec 15
} catch {
  Write-Log "❌ 取状态失败: $($_.Exception.Message)"
  # ⚠️ **v0.22.0（自托管运维评审 P1-5）：失败也要说一声** ——
  # 服务器或 API 挂掉时，用户唯一的告警通道（Hermes 推送）**恰好随服务一起沉默** ——
  # 于是「车况服务挂了」这件事完全没人知道，只能等用户自己发现 App 不更新。
  #
  # 连着失败 3 轮才推（避免网络抖一下就吵），
  # 但每次失败都把计数写进状态文件，恢复后归零。
  $failCount = 1
  if (Test-Path $StateFile) {
    try {
      $pf = Get-Content -LiteralPath $StateFile -Raw -Encoding utf8 | ConvertFrom-Json
      $failCount = [int](Get-Prop $pf 'fail_count' 0) + 1
    } catch { $failCount = 1 }
  }
  if ($failCount -ge 3) {
    Send-Hermes "⚠️ 车况服务取不到数据" "已连续 $failCount 轮无法从服务器读取车况。`n请确认家里那台电脑开着、服务器在跑，以及手机上的 Tailscale 已连接。"
  }
  $failState = [ordered]@{
    fail_count    = $failCount
    last_check_at = (Get-Date).ToUniversalTime().ToString('o')
  }
  $failState | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding utf8
  exit 2
}

# ── 读上次状态 ────────────────────────────────────────────────────
$prev = $null
if (Test-Path $StateFile) {
  try { $prev = Get-Content -LiteralPath $StateFile -Raw -Encoding utf8 | ConvertFrom-Json } catch { $prev = $null }
}
if (-not $prev) {
  Write-Log "首次运行：无历史状态可比对（充电类事件不推），但现存问题（胎压/数据过期）会照常推一次"
}

$chargeInfo = Get-Prop $st 'active_charge'
$charging  = $null -ne $chargeInfo
$battery   = Get-Prop $st 'battery_level'
$addedKwh  = if ($charging) { Get-Prop $chargeInfo 'energy_added_kwh' } else { $null }
$tpmsAlert = [bool](Get-Prop $st 'tpms_alert')
$dataStale = [bool](Get-Prop $st 'data_stale')
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
$carName   = try {
  (Invoke-RestMethod -Uri "$ApiBase/api/v1/cars" -Headers $hdr -TimeoutSec 10 |
    Where-Object { $_.id -eq [int]$CarId } | Select-Object -First 1).name
} catch { "车辆" }
if (-not $carName) { $carName = "车辆" }

$events = @()

# 充电是「状态跳变」，必须要有上一次的状态才能判断，所以首轮不推
# （车在你装脚本时就在充电，不等于「刚刚开始充电」）
$wasCharging = $null -ne $prev -and [bool](Get-Prop $prev 'last_charging')

if ($charging -and -not $wasCharging -and $prev) {
  $soc = if ($null -ne $battery) { "$battery%" } else { "未知" }
  $events += @{
    subject = "🔌 $carName 开始充电"
    body    = "当前电量 $soc`n充电完成会通知你。"
  }
}

if (-not $charging -and $wasCharging) {
  $prevBat = Get-Prop $prev 'last_battery'
  $soc = if ($null -ne $battery) { "$battery%" } elseif ($null -ne $prevBat) { "$prevBat%" } else { "未知" }
  $prevAdded = Get-Prop $prev 'last_added_kwh'
  $extra = if ($null -ne $prevAdded) { "`n充入 $([math]::Round([double]$prevAdded,1)) kWh" } else { "" }
  $events += @{
    subject = "✅ $carName 充电完成"
    body    = "当前电量 $soc$extra"
  }
}

# 胎压 / 数据过期是「现存问题」，不是跳变。
# 所以首次运行就该报一次——你刚装好脚本时车已经有胎压异常，理应立刻知道。
# 之后同一异常只推一次（服务端已判好 tpms_alert），恢复正常后才重置。
if ($tpmsAlert -and -not [bool](Get-Prop $prev 'last_tpms_alert')) {
  $detail = Get-Prop $st 'tpms_alert_detail' ''
  $wheels = @(Get-Prop $st 'tpms_alert_wheels' @())
  $wheelsText = if ($wheels.Count -gt 0) { $wheels -join '、' } else { "未知" }
  $events += @{
    subject = "⚠️ $carName 胎压异常 · 建议检查"
    body    = "$detail`n异常轮胎：$wheelsText`n可能是漏气或胎压异常，建议尽快检查。"
  }
}

if ($dataStale -and -not [bool](Get-Prop $prev 'last_stale')) {
  $ageH = Get-Prop $st 'data_age_hours' 0
  $events += @{
    subject = "ⓘ $carName 数据已 $ageH 小时未更新"
    body    = "车辆长时间没有上报数据。`n车停着不动属正常；若你人在外地，也可能是网络不通，请留意。"
  }
}

# ⚠️ v0.22.0：Fleet 状态告警（只推一次，恢复后重置）。
if ($fleetState -eq 'unauthorized' -and -not [bool](Get-Prop $prev 'last_fleet_unauthorized')) {
  $events += @{
    subject = "🔑 $carName Tesla 授权已失效 · 锁车状态已停更"
    body    = "实时状态拿不到了（一次性令牌过期，重试也不会成功）。`n请在服务器上重新授权：scripts/tesla-authorize.ps1`n在此之前，App 里的锁车状态与实时胎压都不可信。"
  }
}
if ($fleetState -eq 'unreachable' -and -not [bool](Get-Prop $prev 'last_fleet_unreachable')) {
  $events += @{
    subject = "📡 $carName 暂时连不上 Tesla 云端"
    body    = "实时状态暂时取不到（会自己恢复）。`n锁车与实时胎压显示的是最后一次成功获取的数据。"
  }
}

foreach ($e in $events) { Send-Hermes $e.subject $e.body }

# ── 存新状态 ──────────────────────────────────────────────────────
$new = [ordered]@{
  last_charging    = $charging
  last_battery     = $battery
  last_added_kwh   = if ($charging) { $addedKwh } else { Get-Prop $prev 'last_added_kwh' }
  last_tpms_alert  = $tpmsAlert
  last_stale       = $dataStale
  # ⚠️ v0.22.0：Fleet 状态的两个告警位（恢复后自动重置，不再重复推）。
  last_fleet_unauthorized = ($fleetState -eq 'unauthorized')
  last_fleet_unreachable  = ($fleetState -eq 'unreachable')
  # ⚠️ v0.22.0：本轮成功 → 失败计数**归零**（下次再失败时重新从 1 数）。
  fail_count    = 0
  last_check_at = (Get-Date).ToUniversalTime().ToString('o')
}
$new | ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding utf8
Write-Log "本轮结束：充电=$charging 电量=$battery 胎压告警=$tpmsAlert 数据过期=$dataStale fleet=$fleetState 事件数=$($events.Count)"
