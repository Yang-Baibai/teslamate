# 注册/更新 Windows 计划任务 `Hermes-Gateway`（Hermes 网关 = 飞书/微信通道的宿主）。
#
# ## 为什么要有这个脚本
#
# ⚠️ **这台机器上的任务原本是「不可复现的手工产物」** ⇒ 换机器 / 重装就不知道它该长什么样。
# ⇒ 这个脚本把「注册 + 隐藏窗口 + 登录自启 + 每 10 分钟自愈」**固化进仓库**（幂等）。
#
# ## 三件事
#
# ① **登录时**拉起（机器重启 / 登出后自动回来）
# ② **每 10 分钟**自愈心跳：包装器 `scripts/hermes-gateway-autostart.ps1` 自己判断
#    「网关在不在」—— 在就跳过、不在就拉起 ⇒ **不会多起一个**
# ③ **隐藏窗口**：动作用 `conhost.exe --headless` 显式承载 pwsh。
#    ⚠️ 本机 pwsh 由 **Windows Terminal** 承载 ⇒ `-WindowStyle Hidden` 对它**无效**
#       （Terminal 走 ConPTY，不看 STARTUPINFO.wShowWindow）⇒ 2026-11-07 实测仍会弹。
#       用 conhost --headless 承载 ⇒ 桌面上根本不创建窗口（实测 22:44:16 触发点零窗口）。
#    ⚠️ 任务 XML 的 `<Hidden>true</Hidden>` 是**另一回事**（只让任务在 UI 里不显示）。
#
# ⚠️ 弹窗属**桌面会话**，后台脚本枚举不到可见窗口 ⇒ **修完仍要肉眼确认**。
#    （观察者必须**自己也跑在交互式会话里**，否则枚举永远是 0 = 探针无效。）
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/install-hermes-gateway-task.ps1
param(
    [string]$TaskName     = 'Hermes-Gateway',
    [string]$WrapperPath  = "$env:LOCALAPPDATA\hermes\gateway-service\Hermes_Gateway_Auto.ps1",
    [string]$HermesHome   = "$env:LOCALAPPDATA\hermes",
    [int]   $SelfHealMin  = 10
)

$ErrorActionPreference = 'Stop'

$pwsh = (Get-Command pwsh.exe -ErrorAction SilentlyContinue).Source
if (-not $pwsh) { $pwsh = 'C:\Program Files\PowerShell\7\pwsh.exe' }

# ── 承载器：conhost --headless（消弹窗的关键）──
# ⚠️ 本机 pwsh 由 **Windows Terminal** 承载 ⇒ pwsh 的 `-WindowStyle Hidden` 对它**无效**
#    （Terminal 走 ConPTY，不看 STARTUPINFO.wShowWindow）⇒ 必须显式用 conhost 承载。
#    详见 scripts/install-watch-task.ps1 文件头的实测对照表。
$conhost = "$env:SystemRoot\System32\conhost.exe"
if (-not (Test-Path -LiteralPath $conhost)) {
    Write-Output "❌ 找不到 conhost.exe：$conhost"
    exit 1
}

# ── 包装器必须是**机器本地**的一份（带 UTF-8 BOM）──
#     理由：① 仓库在 OneDrive 里，可能被同步/移动；② 计划任务指向的路径不能随仓库变。
$src = Join-Path (Split-Path -Parent $PSCommandPath) 'hermes-gateway-autostart.ps1'
if (Test-Path -LiteralPath $src) {
    $dstDir = Split-Path -Parent $WrapperPath
    if (-not (Test-Path -LiteralPath $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
    $text = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText($WrapperPath, $text, (New-Object System.Text.UTF8Encoding($true)))
    Write-Output ("① 包装器已装到 {0}（带 BOM）" -f $WrapperPath)
} elseif (Test-Path -LiteralPath $WrapperPath) {
    Write-Output ("① 包装器已存在（{0}）" -f $WrapperPath)
} else {
    Write-Output "❌ 找不到包装器：$src"
    exit 1
}

$xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Author>$env:USERDOMAIN\$env:USERNAME</Author>
    <Description>Hermes 网关（飞书通道宿主）：登录时拉起 + 每 $SelfHealMin 分钟自愈。窗口已隐藏，不打扰桌面。</Description>
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
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>3</Count>
    </RestartOnFailure>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
  </Settings>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>$env:USERDOMAIN\$env:USERNAME</UserId>
    </LogonTrigger>
    <TimeTrigger>
      <StartBoundary>$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ss')</StartBoundary>
      <Repetition>
        <Interval>PT${SelfHealMin}M</Interval>
      </Repetition>
    </TimeTrigger>
  </Triggers>
  <Actions Context="Author">
    <Exec>
      <Command>$conhost</Command>
      <Arguments>--headless "$pwsh" -NoProfile -ExecutionPolicy Bypass -File "$WrapperPath"</Arguments>
    </Exec>
  </Actions>
</Task>
"@

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $TaskName -Xml $xml -Force | Out-Null

$t = Get-ScheduledTask -TaskName $TaskName
Write-Output ("✅ 已注册 {0}" -f $TaskName)
Write-Output ("   动作：{0} {1}" -f $t.Actions[0].Execute, $t.Actions[0].Arguments)
Write-Output ("   触发器：{0}" -f (($t.Triggers | ForEach-Object { $_.CimClass.CimClassName }) -join ' + '))
Write-Output ("   <Hidden>：{0}" -f ((Export-ScheduledTask -TaskName $TaskName) -match '<Hidden>true</Hidden>'))
Write-Output ("   下次运行：{0}" -f (Get-ScheduledTaskInfo -TaskName $TaskName).NextRunTime)
