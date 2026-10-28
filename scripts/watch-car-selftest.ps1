#!/usr/bin/env pwsh
# 主动通知引擎自测（v0.77.0）
#
# 为什么需要它：
#   这个脚本的价值全在「**什么时候推、什么时候不推**」上，
#   而这件事**在正常运行时几乎测不出来** ——
#   你得先让服务挂掉、再让它恢复、再让车解锁超过半小时…
#   这些都是「等一天也不一定遇到一次」的场景。
#
#   于是把它做成**桩驱动**：用 JSON 文件喂车况、用独立进程跑脚本、
#   从日志里数「推了几条」。每个洞一组断言，跑完几秒钟。
#
# 守的四个洞（对应 watch-car.ps1 顶部的说明）：
#   ① 告警风暴 —— 同一问题连着 5 轮，只该推 **1** 次
#   ② 状态丢失 —— 服务挂掉期间，别的状态不许被冲掉
#   ③ 没有闭环 —— 恢复时必须推一条「已恢复」
#   ④ 该提醒的不提醒 —— 未锁车 / 车窗未关 超时要提醒，且要能恢复
#   ⑤ 读不到 ≠ 关掉 —— 状态未知时计时器不许重置
#
# 用法：pwsh -NoProfile -File scripts/watch-car-selftest.ps1
# 退出码：0 = 全过，1 = 有失败

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptDir = $PSScriptRoot
$WatchCar  = Join-Path $ScriptDir 'watch-car.ps1'
if (-not (Test-Path -LiteralPath $WatchCar)) {
  Write-Output "❌ 找不到 watch-car.ps1（期望在 $ScriptDir）"
  exit 1
}

# 用当前 pwsh 的可执行文件跑子进程 ——
# 必须**独立进程**：watch-car.ps1 失败时会 `exit 2`，
# 同进程调用会把整个自测一起带走。
$Pwsh = (Get-Process -Id $PID).Path

$Tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("teslamate-watch-selftest-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Tmp -Force | Out-Null

$StateFile  = Join-Path $Tmp 'state.json'
$StatusFile = Join-Path $Tmp 'status.json'
$LogFile    = Join-Path $Tmp 'watch.log'

# 一个**必然连不上**的本地端口：模拟「家里那台电脑/服务挂了」。
$DeadApi = 'http://127.0.0.1:45999'

$script:Pass = 0
$script:Fail = 0

function Ok([string]$msg)   { $script:Pass++; Write-Host "  ✓ $msg" }
function No([string]$msg)   { $script:Fail++; Write-Host "  ✗ $msg" }

function Assert-True([bool]$cond, [string]$msg) {
  if ($cond) { Ok $msg } else { No $msg }
}

function Assert-Eq($expected, $actual, [string]$msg) {
  if ("$expected" -eq "$actual") { Ok "$msg（= $expected）" }
  else { No "$msg —— 期望 [$expected]，实际 [$actual]" }
}

# ── 喂一份车况 ────────────────────────────────────────────────────
function Set-Status([hashtable]$fields) {
  $base = @{
    car_id           = 1
    battery_level    = 55
    active_charge    = $null
    tpms_alert       = $false
    tpms_alert_wheels = @()
    tpms_alert_detail = ''
    data_stale       = $false
    data_age_hours   = 0
    fleet_state      = 'ok'
    locked           = $true
    open_windows     = @()
  }
  foreach ($k in $fields.Keys) { $base[$k] = $fields[$k] }
  ($base | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $StatusFile -Encoding utf8
}

# ── 跑一轮 watch-car.ps1（独立进程）──────────────────────────────
#
# ⚠️ 这里**绝不能**删状态文件 ——
#    引擎的价值全在「跨轮记住什么」，删了状态就等于每次都是首轮，
#    测出来的东西毫无意义。要重置状态由各场景自己显式删。
function Invoke-Watch([switch]$UseHttp) {
  $argv = @('-NoProfile', '-File', $WatchCar,
            '-StateFile', $StateFile, '-LogFile', $LogFile,
            '-DryRun', '-CarName', '测试车')
  if ($UseHttp) {
    # 走 HTTP，但指向一个连不上的端口 ⇒ 触发「取数失败」分支。
    $argv += @('-ApiBase', $DeadApi)
  } else {
    $argv += @('-StatusFile', $StatusFile)
  }
  # ⚠️ 子进程的 stderr 会被 2>&1 变成错误记录；ErrorActionPreference=Stop 下
  #    那可能被当成终止错误 —— 自测要的是「跑完」，不是「别出错」。
  $prevPref = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $Pwsh @argv 2>&1
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prevPref
  }
  return [pscustomobject]@{ code = $code; out = ($out | Out-String) }
}

# 安全取 JSON 字段：StrictMode 下访问不存在的属性会**抛错**，
# 而「字段不存在」在断言里是很正常的一种结果。
function Get-JsonProp($obj, [string]$name) {
  if ($null -eq $obj) { return $null }
  $p = $obj.PSObject.Properties[$name]
  if ($null -eq $p) { return $null }
  return $p.Value
}

# ── 数「推了几条」─────────────────────────────────────────────────
# ⚠️ 返回数组的函数在 PowerShell 里会被**解包** ——
#    空数组会变成 $null。调用处一律用 @() 包一层。
function Get-Pushes {
  $out = @()
  if (-not (Test-Path -LiteralPath $LogFile)) { return $out }
  foreach ($line in (Get-Content -LiteralPath $LogFile -Encoding utf8)) {
    if ($line -match '\[DryRun\] 标题: (.*)$') { $out += $Matches[1] }
  }
  return $out
}

function Count-Push([string[]]$pushes, [string]$pattern) {
  $n = 0
  foreach ($p in $pushes) { if ($p -match $pattern) { $n++ } }
  return $n
}

# 取推送**正文**。
#
# ⚠️ v0.78.0：结局判定在**服务端**，文案也由服务端给 ——
#    脚本只负责把结局翻译成标题。所以「说的是不是实话」要看正文，
#    光看标题不够。
function Get-Bodies {
  $out = @()
  if (-not (Test-Path -LiteralPath $LogFile)) { return $out }
  foreach ($line in (Get-Content -LiteralPath $LogFile -Encoding utf8)) {
    if ($line -match '\[DryRun\] 正文: (.*)$') { $out += $Matches[1] }
  }
  return $out
}

function Body-Contains([string[]]$bodies, [string]$pattern) {
  $n = 0
  foreach ($b in $bodies) { if ($b -match $pattern) { $n++ } }
  return $n
}

# 最后一条正文（= 结束那一轮说的）。
#
# ⚠️ 日志是**按行**写的，多行正文只有第一行带 "[DryRun] 正文:" 前缀 ——
#    所以断正文必须看**最后一条**，不能对全体做包含判断
#    （「开始充电」的正文里也有「当前电量」，会数成 2 条）。
function Get-LastBody {
  $all = @(Get-Bodies)
  if ($all.Count -eq 0) { return '' }
  return $all[-1]
}

# 喂一个充电结局（服务端算好的）。
function Set-ChargeOutcome([string]$kind, [string]$detail) {
  $oc = @{ outcome = $kind; detail = $detail; end_soc = 80; reached_limit = $false }
  Set-Status @{ charge_outcome = $oc }
}

# 走完一轮「正在充电 → 充电结束」的上升沿，返回这期间推过的所有标题。
#
# ⚠️ 需要**三轮**才走完一条完整的上升沿：
#   第 1 轮：正在充电（首轮不推，只记住 last_charging=true）
#   第 2 轮：还在充电（这时才知道「确实在充」⇒ 推「开始充电」）
#   第 3 轮：不充电了（true→false ⇒ 推「充电结束」）
#
# $kind 为空 ⇒ 模拟「老服务端 / 没数据」（status 里根本没有 charge_outcome）。
function Invoke-ChargeEnd([string]$kind, [string]$detail) {
  if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
  Reset-Log
  # 第 1 轮：正在充电（首轮不推）
  Set-Status @{ active_charge = @{ charge_id = 79; started_at = '2026-10-28T04:32:37Z'; energy_added_kwh = 12.5 } }
  Invoke-Watch | Out-Null
  # 第 2 轮：还在充电 ⇒ 推「开始充电」
  Invoke-Watch | Out-Null
  # 第 3 轮：充电结束 + 服务端算好的结局
  if ($kind) { Set-ChargeOutcome $kind $detail } else { Set-Status @{ active_charge = $null } }
  Invoke-Watch | Out-Null
  return @(Get-Pushes)
}

function Reset-Log {
  if (Test-Path -LiteralPath $LogFile) { Remove-Item -LiteralPath $LogFile -Force }
}

function Read-StateJson {
  if (-not (Test-Path -LiteralPath $StateFile)) { return $null }
  return (Get-Content -LiteralPath $StateFile -Raw -Encoding utf8 | ConvertFrom-Json)
}

# 把状态文件里某个计时器往前拨 minutes 分钟（模拟「已经开着这么久了」）。
function Age-Timer([string]$key, [double]$minutes) {
  $s = Read-StateJson
  if ($null -eq $s) { return }
  $since = (Get-Date).ToUniversalTime().AddMinutes(-$minutes).ToString('o')
  $s | Add-Member -NotePropertyName $key -NotePropertyValue $since -Force
  ($s | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $StateFile -Encoding utf8
}

Write-Host "══ 推送引擎自测 ══"

# ═════════════════════════════════════════════════════════════════
# 场景 1：同一个问题连着 5 轮 —— 只该推 1 次（洞 ① 告警风暴）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 1：同一告警只推一次"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
Set-Status @{ tpms_alert = $true; tpms_alert_wheels = @('左后'); tpms_alert_detail = '左后 2.30 bar' }
1..5 | ForEach-Object { Invoke-Watch | Out-Null }
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '胎压异常') "连着 5 轮，胎压告警只推了 1 次"
Assert-Eq 1 $pushes.Count "本轮总共只推了 1 条（没有别的噪音）"

# ═════════════════════════════════════════════════════════════════
# 场景 2：问题恢复 —— 必须推一条「已恢复」（洞 ③ 没有闭环）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 2：恢复要闭环"
Set-Status @{ tpms_alert = $false }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '胎压已恢复正常') "恢复后推了 1 条「已恢复」"
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '胎压已恢复正常') "恢复消息不会重复推"

# ═════════════════════════════════════════════════════════════════
# 场景 3：服务挂掉 —— 只推 1 次（洞 ①）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 3：服务挂掉只推一次"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
1..5 | ForEach-Object { Invoke-Watch -UseHttp | Out-Null }
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '车况服务取不到数据') "连挂 5 轮，只推了 1 次（旧版会推 5 次）"

# ═════════════════════════════════════════════════════════════════
# 场景 4：服务挂掉期间**不许冲掉别的状态**（洞 ② 状态丢失）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 4：失败不冲状态"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
# 先成功一轮：制造「胎压异常」这个已告知状态 + 未锁车计时
Set-Status @{ tpms_alert = $true; tpms_alert_wheels = @('左后'); tpms_alert_detail = '左后 2.30 bar'; locked = $false }
Invoke-Watch | Out-Null
$s = Read-StateJson
Assert-Eq $true (Get-JsonProp (Get-JsonProp $s 'alerted') 'tpms') "成功轮之后记住了「已告知胎压异常」"
Assert-True ($null -ne (Get-JsonProp $s 'unlocked_since')) "成功轮之后记下了未锁车计时"

# 接着挂 3 轮
1..3 | ForEach-Object { Invoke-Watch -UseHttp | Out-Null }
$s = Read-StateJson
Assert-Eq $true (Get-JsonProp (Get-JsonProp $s 'alerted') 'tpms') "挂掉期间「已告知胎压异常」没被冲掉"
Assert-True ($null -ne (Get-JsonProp $s 'unlocked_since')) "挂掉期间未锁车计时没被冲掉"
Assert-Eq 3 (Get-JsonProp $s 'fail_count') "失败计数被正确累加"

# ═════════════════════════════════════════════════════════════════
# 场景 5：服务恢复 —— 推「已恢复」，且**不重推**旧的胎压告警（洞 ② + ③）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 5：服务恢复的闭环"
Reset-Log
Set-Status @{ tpms_alert = $true; locked = $false }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '车况服务已恢复') "服务恢复推了 1 条「已恢复」"
Assert-Eq 0 (Count-Push $pushes '胎压异常') "恢复后**没有**重推旧的胎压告警（状态没丢 ⇒ 不该重推）"

# ═════════════════════════════════════════════════════════════════
# 场景 6：未锁车超时（洞 ④）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 6：未锁车超时提醒"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
Set-Status @{ locked = $false }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 0 (Count-Push $pushes '已解锁超过') "刚解锁还没到 30 分钟 —— 不该吵"

Age-Timer 'unlocked_since' 31
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '已解锁超过') "解锁超过 30 分钟 —— 提醒 1 次"

Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '已解锁超过') "持续未锁 —— 不会重复提醒"

Set-Status @{ locked = $true }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '车已锁好') "锁好了 —— 推「已恢复」"

# ═════════════════════════════════════════════════════════════════
# 场景 7：车窗未关超时（洞 ④）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 7：车窗未关超时提醒"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
Set-Status @{ open_windows = @('左前窗') }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 0 (Count-Push $pushes '车窗未关超过') "刚开着还没到 30 分钟 —— 不该吵"

Age-Timer 'window_open_since' 31
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '车窗未关超过') "车窗开着超过 30 分钟 —— 提醒 1 次"

Set-Status @{ open_windows = @() }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 1 (Count-Push $pushes '车窗已关好') "车窗关好了 —— 推「已恢复」"

# ═════════════════════════════════════════════════════════════════
# 场景 8：读不到 ≠ 关掉（计时器不许被重置）
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 8：状态未知时计时器保持不动"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
Set-Status @{ locked = $false; open_windows = @('右前窗') }
Invoke-Watch | Out-Null
$before = Read-StateJson
Assert-True ($null -ne (Get-JsonProp $before 'unlocked_since')) "起点：未锁车计时已启动"

# 现在「读不到锁车状态」（null）与「读不到车窗状态」（null）
Set-Status @{ locked = $null; open_windows = $null }
Invoke-Watch | Out-Null
$after = Read-StateJson
Assert-True ($null -ne (Get-JsonProp $after 'unlocked_since')) "锁车状态未知 ⇒ 未锁车计时**没被重置**"
Assert-True ($null -ne (Get-JsonProp $after 'window_open_since')) "车窗状态未知 ⇒ 车窗计时**没被重置**"

# 「确实锁好了」才清零
Set-Status @{ locked = $true; open_windows = @() }
Invoke-Watch | Out-Null
$after2 = Read-StateJson
Assert-Eq $null (Get-JsonProp $after2 'unlocked_since') "确认锁好了 ⇒ 未锁车计时清零"
Assert-Eq $null (Get-JsonProp $after2 'window_open_since') "确认车窗全关 ⇒ 车窗计时清零"

# ═════════════════════════════════════════════════════════════════
# 场景 9：首次运行不把「本来就在充电」当成「刚刚开始充电」
# ═════════════════════════════════════════════════════════════════
Write-Host "── 场景 9：首轮不误报"
Reset-Log
if (Test-Path -LiteralPath $StateFile) { Remove-Item -LiteralPath $StateFile -Force }
Set-Status @{ active_charge = @{ charge_id = 66; started_at = '2026-10-27T01:00:00Z'; energy_added_kwh = 12.5 } }
Invoke-Watch | Out-Null
$pushes = @(Get-Pushes)
Assert-Eq 0 (Count-Push $pushes '开始充电') "首轮不把「本来就在充电」说成「开始充电」"

# ═════════════════════════════════════════════════════════════════
# 场景 10~13：充电结局的四种说法（v0.78.0）
#
# 守的是通知通道里的**假成功**：
# 旧版不管充到哪儿，结束一律说「✅ 充电完成」。
# 实测本机最近一次（2026-10-28，Tesla 超充）只充到 **80%**（上限 100%），
# 旧版却说「完成」—— 用户看到「完成」就不会再看第二眼。
#
# 结局由服务端判（收尾曲线 + 车端状态），脚本只翻译标题 + 透传正文。
# ═════════════════════════════════════════════════════════════════

Write-Host "── 场景 10：充到上限 ⇒ 说「充电完成」"
$pushes = @(Invoke-ChargeEnd 'complete' '已充到上限：充到 100%，充入 20.6 kWh，用了 51 分钟')
Assert-Eq 1 (Count-Push $pushes '充电完成') "真的充到上限 —— 说「充电完成」"
Assert-Eq 0 (Count-Push $pushes '充电中断') "充到上限不是「中断」"
$lastBody = Get-LastBody
Assert-True ($lastBody -match '充入 20\.6 kWh') "正文透传了服务端给的结论"

Write-Host "── 场景 11：拔枪没充到上限 ⇒ 说「充电结束」，绝不说「完成」"
$pushes = @(Invoke-ChargeEnd 'ended_early' '没到充电上限（上限 100%）：充到 80%，充入 17.6 kWh，用了 19 分钟')
Assert-Eq 1 (Count-Push $pushes '充电结束') "没充到上限 —— 说「充电结束」"
Assert-Eq 0 (Count-Push $pushes '充电完成') "**没有**说「充电完成」（这正是本轮要消灭的假成功）"
Assert-Eq 0 (Count-Push $pushes '充电中断') "拔枪走人不算「中断」"
$lastBody = Get-LastBody
Assert-True ($lastBody -match '没到充电上限') "正文说明了「没到充电上限」"
Assert-True ($lastBody -match '充到 80%') "正文带着事实「充到 80%」"
Assert-True ($lastBody -notmatch '完成') "正文里**没有**「完成」二字"

Write-Host "── 场景 12：枪还插着但停了 ⇒ 说「充电中断」"
$pushes = @(Invoke-ChargeEnd 'interrupted' '没充到上限就停了，枪还插着：充到 80%，充入 12.4 kWh，用了 30 分钟')
Assert-Eq 1 (Count-Push $pushes '充电中断') "枪还插着却停了 —— 说「充电中断」"
Assert-Eq 0 (Count-Push $pushes '充电完成') "中断不是「完成」"
$lastBody = Get-LastBody
Assert-True ($lastBody -match '枪还插着') "正文说明了「枪还插着」（这是要去看一眼的信号）"

Write-Host "── 场景 13：桩没供电 ⇒ 说「充电中断（充电桩没供电）」"
$pushes = @(Invoke-ChargeEnd 'no_power' '充电桩没有供电：充到 80%，充入 9.1 kWh，用了 26 分钟')
Assert-Eq 1 (Count-Push $pushes '充电中断') "桩没供电 —— 也归到「中断」"
Assert-Eq 1 (Count-Push $pushes '没供电') "标题点明了「充电桩没供电」"
Assert-Eq 0 (Count-Push $pushes '充电完成') "桩没供电不是「完成」"

Write-Host "── 场景 14：服务端没给结论 ⇒ 只报事实，**绝不说「完成」**"
$pushes = @(Invoke-ChargeEnd '' '')
Assert-Eq 0 (Count-Push $pushes '充电完成') "判不出来时**不能**说「完成」"
Assert-Eq 1 (Count-Push $pushes '充电结束') "判不出来时只说「充电结束」"
$lastBody = Get-LastBody
Assert-True ($lastBody -match '当前电量') "判不出来时只报事实（当前电量）"
Assert-True ($lastBody -notmatch '完成|没到充电上限|已充到上限') "判不出来时**不编**任何结论"

# ═════════════════════════════════════════════════════════════════
# 清理 + 汇总
# ═════════════════════════════════════════════════════════════════
try { Remove-Item -LiteralPath $Tmp -Recurse -Force -EA SilentlyContinue } catch { }

Write-Host "══ 结果：$script:Pass 过 / $script:Fail 败 ══"
if ($script:Fail -gt 0) { Write-Host "❌ 推送引擎自测未通过"; exit 1 }
Write-Host "✅ 推送引擎自测通过"
exit 0
