# Hermes 网关自启包装器（Windows 计划任务用）
#
# ⚠️ **为什么不用仓库里原有的 `gateway-service\Hermes_Gateway.cmd`**：
#    那个 .cmd 是 **UTF-8 无 BOM** ⇒ cmd.exe 按 OEM 码页（936 / GBK）解析
#    ⇒ 路径里的「<用户名>」变成乱码 ⇒ `cd /d` 失败、python 路径也找不到
#    ⇒ **静默失败**（日志里连 `gateway.start` 都没有）。
#    ⇒ 本包装器用 **PowerShell**（正确处理 Unicode 路径）+ UTF-8 编码。
#
# ⚠️ **幂等**：已经在跑就直接退出 —— 计划任务每 10 分钟触发一次，
#    没有这道检查就会每 10 分钟多起一个网关。
#
# 用法：
#    pwsh -NoProfile -ExecutionPolicy Bypass -File hermes-gateway-autostart.ps1
#
# 安装成计划任务（登录时 + 每 10 分钟自愈）：
#    schtasks /Create /TN "Hermes-Gateway" /SC MINUTE /MO 10 /TR "pwsh -NoProfile -File <本文件路径>" /F
param(
    [switch]$Verbose2
)

$ErrorActionPreference = 'Continue'

# ⚠️ 变量名**不能**叫 `$hroot` —— 那是 PowerShell 的**只读自动变量**
#    （用户主目录）⇒ 赋值会直接报错：「无法覆盖变量 HOME」。
$hroot = Join-Path $env:LOCALAPPDATA 'hermes'
$py   = Join-Path $hroot 'tools\python-3.14.7+20260901-win32-x64\python.exe'
$log  = Join-Path $hroot 'logs'
$trace = Join-Path $log 'gateway-autostart.log'

function Write-Trace([string]$msg) {
    try {
        Add-Content -LiteralPath $trace -Value ("{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8
    } catch { }
}

# ── ① 已经在跑就别再起一个（幂等）────────────────────────
$running = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'hermes_cli\.main gateway run' }
if ($running) {
    Write-Trace ("已在运行（PID {0}）⇒ 跳过" -f (($running.ProcessId) -join ','))
    exit 0
}

if (-not (Test-Path -LiteralPath $py)) {
    Write-Trace ("❌ 找不到 python：{0}" -f $py)
    exit 1
}

# ── ② 环境变量（与手工启动时完全一致）───────────────────
$env:HERMES_HOME             = $hroot
$env:PYTHONIOENCODING        = 'utf-8'
$env:HERMES_GATEWAY_DETACHED = '1'
$env:HERMES_SUPERVISED_CHILD = '1'
$env:VIRTUAL_ENV             = Join-Path $hroot 'tools'
$env:PYTHONPATH              = Join-Path $hroot 'hermes-agent'

$out = Join-Path $log 'gateway-auto.out'
$err = Join-Path $log 'gateway-auto.err'

# ── ③ 拉起来（脱离本进程；父进程退出不影响它）────────────
try {
    $p = Start-Process -FilePath $py `
        -ArgumentList '-m', 'hermes_cli.main', 'gateway', 'run' `
        -WorkingDirectory $hroot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $out `
        -RedirectStandardError  $err `
        -PassThru
    Write-Trace ("已拉起网关 PID {0}（stdout→{1} / stderr→{2}）" -f $p.Id, $out, $err)
} catch {
    Write-Trace ("❌ 拉起失败：{0}" -f $_.Exception.Message)
    exit 1
}

exit 0
