# 注册/更新 Windows 计划任务 `TeslaMate-CarWatch`（车况监听 → Hermes 飞书推送）。
#
# ## 为什么要有这个脚本
#
# ⚠️ **这台机器上的任务原本是「不可复现的手工产物」** —— 仓库里没有创建它的脚本
# ⇒ 换机器、重装、或被人手工改过之后，就没人知道它原本该长什么样。
# ⇒ 这个脚本把「注册 + 隐藏窗口 + 每 5 分钟」**固化进仓库**（幂等：先删后建）。
#
# ## ⚠️ 隐藏窗口：为什么是 `-WindowStyle Hidden`（而不是任务 XML 的 `<Hidden>`）
#
# 现象：任务用 `InteractiveToken` 在**桌面会话**里拉起 `pwsh.exe`，
#      而 `pwsh.exe` 是**控制台程序** ⇒ 每次都弹出一个可见的 cmd 窗口，
#      窗口里那行「数据过期」是这个脚本**每轮必打印的运行摘要**（不是报错）。
#
# 两个容易混淆的东西：
#
# | 做法 | 真实作用 | 能消掉弹窗吗 |
# |---|---|---|
# | 任务 XML 的 `<Settings><Hidden>true</Hidden>` | **在任务计划程序 UI 里隐藏这个任务** | ❌ **不能** |
# | 动作参数 `-WindowStyle Hidden` | 给 pwsh 传 `STARTUPINFO.wShowWindow = SW_HIDE` ⇒ 控制台窗口**创建时就是隐藏的** | ✅ 能 |
#
# ⇒ 本脚本**两个都做**：`-WindowStyle Hidden` 负责消弹窗，
#   `<Hidden>true</Hidden>` 让任务不在 UI 里碍眼。
#
# ⚠️ 弹窗属**桌面会话**，后台脚本枚举不到可见窗口 ⇒ **修完要肉眼确认一个 5 分钟窗口**。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/install-watch-task.ps1
param(
    [string]$TaskName    = 'TeslaMate-CarWatch',
    [string]$ScriptPath  = 'C:\tm-ws\scripts\watch-car.ps1',
    [int]   $IntervalMin = 5
)

$ErrorActionPreference = 'Stop'

# ── 路径兜底：优先用 ASCII 联接（中文路径在计划任务里也可能出岔子）──
if (-not (Test-Path -LiteralPath $ScriptPath)) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
    $fallback = Join-Path $repoRoot 'scripts\watch-car.ps1'
    if (Test-Path -LiteralPath $fallback) {
        Write-Output "⚠️ {0} 不存在 ⇒ 改用真实路径 {1}" -f $ScriptPath, $fallback
        $ScriptPath = $fallback
    } else {
        Write-Output "❌ 找不到脚本：$ScriptPath"
        exit 1
    }
}

$pwsh = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwsh) { $pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe' }

# ── 任务 XML（保持与原任务一致：每 5 分钟 / InteractiveToken / IgnoreNew）──
# ⚠️ `-WindowStyle Hidden` 紧跟在 -ExecutionPolicy 后面（pwsh 的参数解析更稳）。
$xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Author>$env:USERDOMAIN\$env:USERNAME</Author>
    <Description>teslamate 车况监听：每 $IntervalMin 分钟检查一次，把告警/简报推给 Hermes（飞书）。窗口已隐藏，不打扰桌面。</Description>
    <URI>\$TaskName</URI>
  </RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <UserId>$env:USERDOMAIN\$env:USERNAME</UserId>
      <LogonType>InteractiveToken</LogonType>
    </Principal>
  </Principals>
  <Settings>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <Hidden>true</Hidden>
  </Settings>
  <Triggers>
    <TimeTrigger>
      <StartBoundary>$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')</StartBoundary>
      <Repetition>
        <Interval>PT${IntervalMin}M</Interval>
      </Repetition>
    </TimeTrigger>
  </Triggers>
  <Actions Context="Author">
    <Exec>
      <Command>$pwsh</Command>
      <Arguments>-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$ScriptPath"</Arguments>
    </Exec>
  </Actions>
</Task>
"@

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Xml $xml -Force | Out-Null

$t = Get-ScheduledTask -TaskName $TaskName
Write-Output ("✅ 已注册 {0}" -f $TaskName)
Write-Output ("   动作：{0} {1}" -f $t.Actions[0].Execute, $t.Actions[0].Arguments)
Write-Output ("   设置里有 <Hidden>：{0}" -f ((Export-ScheduledTask -TaskName $TaskName) -match '<Hidden>true</Hidden>'))
Write-Output ("   下次运行：{0}" -f (Get-ScheduledTaskInfo -TaskName $TaskName).NextRunTime)
