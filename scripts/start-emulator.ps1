#!/usr/bin/env pwsh
# 启动本机的 Android 模拟器（版面复核用）—— **可复现的一键脚本**
#
# ── 为什么需要这个脚本（2026-11-11 实测） ──────────────────────────────────
# 本机的模拟器此前**连续八轮起不来**。实测排下来，真因**不是**虚拟化、不是图形 DLL、
# 也不是换 AVD / 换镜像，而是：
#
#   **emulator 前端的 SDK 根路径里含非 ASCII 字符（用户名「<用户名>」）时起不来。**
#
# 三组对照（同一份系统镜像、同一套参数、同一台机器）：
#
#   | emulator 所在路径       | 换包              | 快照 | 结果                                   |
#   |-------------------------|-------------------|------|----------------------------------------|
#   | C:/Users/<用户名>/...     | 否                | 开   | 崩：0xC0000005 访问冲突                 |
#   | C:/Users/<用户名>/...     | 否                | 关   | 永久 offline（qemu CPU 0.6 秒 = 阻塞）  |
#   | C:/Users/<用户名>/...     | **是(37.2.13)**   | 关   | 永久 offline（**换包不是解药**）        |
#   | C:/Users/<用户名>/...     | 是                | 关   | **加了 ANDROID_SDK_ROOT 等环境变量照样失败** |
#   | **C:/tm-sdk（全 ASCII）** | 否              | 关   | ✅ **46.8 秒启动成功**（WHPX 加速）     |
#
# ⇒ **唯一决定性变量 = emulator 所在 SDK 根路径是不是纯 ASCII。**
# ⇒ 所以本脚本强制把 emulator 放在 **C:/tm-sdk**（纯 ASCII）下启动，
#    其余目录（system-images / platform-tools / cmdline-tools / licenses）用**目录联接**
#    指回真实 SDK ⇒ 既避开非 ASCII 路径，又不用复制 1 GB。
#
# ── 不需要管理员、不需要重启、不改宿主虚拟化配置 ──────────────────────────
# ⚠️ 实测：**WHPX 加速在「未重启」的状态下就已经在工作**
#    （qemu 命令行里带 -enable-whpx，启动耗时 46.8 秒而非分钟级）
#    ⇒ **模拟器不等重启就能用**；宿主侧那个「HypervisorPlatform 待激活」与本问题无关。
#
# ── 用法 ──────────────────────────────────────────────────────────────────
#   pwsh -File scripts/start-emulator.ps1                 # 起 tm_pixel 并等它 boot 完
#   pwsh -File scripts/start-emulator.ps1 -Avd tm_emu     # 换 AVD
#   pwsh -File scripts/start-emulator.ps1 -Stop            # 只停
#   pwsh -File scripts/start-emulator.ps1 -NoWindow        # 无头（只要截图时更省资源）
#   bash scripts/start-emulator.sh                        # Git Bash 包装（参数照传）
#
# 退出码：0 = 已 boot 成功（并已打印产物证据）；1 = 失败（并已打印关键日志行）。

param(
  [string]$Avd = 'tm_pixel',
  [int]$TimeoutSec = 300,
  [switch]$Snapshot,      # 允许加载本地快照（默认关：现存的快照是脏的，见下）
  [switch]$NoWindow,      # 无头模式
  [switch]$WipeData,      # 洗 userdata（只影响这个 AVD）
  [switch]$Stop,          # 只停不停
  [switch]$ShowKernel,    # 打印 guest 内核输出（排查用）
  [bool]$AppTunnel = $true,   # 给 App 铺 `adb reverse`（设备 localhost:8090 -> 主机 8090）
  [string]$AsciiSdkRoot = 'C:\tm-sdk',
  [string]$RealSdkRoot = ''
)

$ErrorActionPreference = 'Continue'

function Say([string]$m) { Write-Host $m }

# ── 0. 定位真实 SDK ────────────────────────────────────────────────────────
if (-not $RealSdkRoot) {
  if ($env:ANDROID_SDK_ROOT) { $RealSdkRoot = $env:ANDROID_SDK_ROOT }
  else { $RealSdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
$RealEmuDir = Join-Path $RealSdkRoot 'emulator'
$RealAvdHome = Join-Path $env:USERPROFILE '.android\avd'

# ── 1. 清理残留实例（同一 AVD 不能并行开第二个；无头实例进程名带 -headless）──
function Stop-Emulators {
  $procs = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -match '^(emulator|qemu-system-.*)$' }
  foreach ($p in $procs) {
    Say ("  kill " + $p.Id + " (" + $p.ProcessName + ")")
    try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch {}
  }
  if ($procs) { Start-Sleep -Seconds 2 }
}

function Clear-StaleLocks([string]$avdHome) {
  if (-not (Test-Path $avdHome)) { return }
  Get-ChildItem $avdHome -Directory -Filter *.avd -ErrorAction SilentlyContinue | ForEach-Object {
    Get-ChildItem $_.FullName -Filter *.lock -Force -ErrorAction SilentlyContinue | ForEach-Object {
      try { Remove-Item $_.FullName -Force -ErrorAction Stop -Confirm:$false; Say ("  unlock " + $_.FullName) }
      catch { Say ("  lock busy（有实例在跑，正常）: " + $_.Name) }
    }
  }
}

Stop-Emulators

if ($Stop) {
  Say '已停止所有 emulator / qemu 进程。'
  exit 0
}

# ── 2. 准备纯 ASCII 的 SDK 根（这是本脚本的全部关键）─────────────────────
if ($AsciiSdkRoot -match '[^\x00-\x7F]') {
  Say ('✗ AsciiSdkRoot 含非 ASCII 字符：' + $AsciiSdkRoot + ' ⇒ 本脚本不认（那正是起不来的原因）')
  exit 1
}
New-Item -ItemType Directory -Force -Path $AsciiSdkRoot | Out-Null

function Ensure-Junction([string]$link, [string]$target) {
  if (Test-Path $link) { return $true }
  if (-not (Test-Path $target)) { return $false }
  try {
    New-Item -ItemType Junction -Path $link -Target $target -ErrorAction Stop | Out-Null
    Say ("  联接 " + $link + ' -> ' + $target)
    return $true
  } catch {
    Say ("  ✗ 建联接失败 " + $link + ' : ' + $_.Exception.Message)
    return $false
  }
}

Say ('=== 准备纯 ASCII SDK 根: ' + $AsciiSdkRoot + ' ===')
# emulator 本体：优先用纯 ASCII 根里已有的**真实目录**（实测可跑）；
# 没有才用联接指回真实 SDK 的 emulator 目录。
$AsciiEmuExe = Join-Path $AsciiSdkRoot 'emulator\emulator.exe'
if (-not (Test-Path $AsciiEmuExe)) {
  Ensure-Junction (Join-Path $AsciiSdkRoot 'emulator') $RealEmuDir | Out-Null
}
foreach ($d in @('system-images','platform-tools','cmdline-tools','licenses')) {
  Ensure-Junction (Join-Path $AsciiSdkRoot $d) (Join-Path $RealSdkRoot $d) | Out-Null
}
$AsciiAvdHome = 'C:\tm-avd'
Ensure-Junction $AsciiAvdHome $RealAvdHome | Out-Null

if (-not (Test-Path $AsciiEmuExe)) {
  Say ('✗ 找不到 emulator.exe（' + $AsciiEmuExe + '）')
  Say ('  本机真实 SDK: ' + $RealSdkRoot + ' ⇒ 先跑 sdkmanager --install emulator')
  exit 1
}
$ver = (Get-Content (Join-Path $AsciiSdkRoot 'emulator\source.properties') -ErrorAction SilentlyContinue | Select-String 'Pkg.Revision').ToString()
Say ('  emulator 版本: ' + $ver)
$realVer = (Get-Content (Join-Path $RealSdkRoot 'emulator\source.properties') -ErrorAction SilentlyContinue | Select-String 'Pkg.Revision').ToString()
if ($realVer -and $ver -and ($realVer -ne $ver)) {
  Say ('  ⚠️ 纯 ASCII 根里的 emulator(' + $ver + ') 与真实 SDK(' + $realVer + ') 版本不同')
  Say '     （实测**版本不是根因**，不强制同步；需要最新版就把真实 SDK 的 emulator 目录复制过来）'
}

$env:ANDROID_SDK_ROOT = $AsciiSdkRoot
$env:ANDROID_HOME     = $AsciiSdkRoot
$env:ANDROID_AVD_HOME = $AsciiAvdHome
if ($env:PATH -notlike ('*' + $AsciiSdkRoot + '\platform-tools*')) {
  $env:PATH = (Join-Path $AsciiSdkRoot 'platform-tools') + ';' + $env:PATH
}

$adb = Join-Path $AsciiSdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = Join-Path $RealSdkRoot 'platform-tools\adb.exe' }

# ⚠️ adb **不能**把 stdout 接进 PowerShell 管道（`| Out-String`）：
#    adb 守护进程会继承那个管道的**写端** ⇒ 管道永远等不到 EOF ⇒ 调用方**永久挂起**。
#    ⇒ 一律让 adb 写**文件**，再读文件。
$adbOutFile = Join-Path $env:TEMP 'teslamate-emulator\adb-out.txt'
function Invoke-Adb([string[]]$a) {
  New-Item -ItemType Directory -Force -Path (Split-Path $adbOutFile) | Out-Null
  & $adb @a > $adbOutFile 2>&1
  $t = Get-Content $adbOutFile -Raw -ErrorAction SilentlyContinue
  if ($null -eq $t) { return '' }
  return $t
}

# ── 3. AVD 存在吗 ─────────────────────────────────────────────────────────
$avdDirs = @()
if (Test-Path $AsciiAvdHome) {
  $avdDirs = Get-ChildItem $AsciiAvdHome -Directory -Filter *.avd -ErrorAction SilentlyContinue | ForEach-Object { $_.Name -replace '\.avd$','' }
}
if ($avdDirs -notcontains $Avd) {
  Say ('✗ 没有 AVD ' + $Avd + '。现有：' + ($avdDirs -join ', '))
  exit 1
}

Clear-StaleLocks $AsciiAvdHome

# ── 4. 启动 ───────────────────────────────────────────────────────────────
$logDir = Join-Path $env:TEMP 'teslamate-emulator'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# ⚠️ **绕一圈用 cmd 启动，是有原因的（踩过，别改回去）**：
#    PowerShell 7 的 `Start-Process -RedirectStandardOutput` 给子进程的是**管道**（不是文件），
#    而 pwsh 退出时会等这个管道的 EOF ⇒ **emulator 这种常驻进程会把调用方吊住**
#    （表现：脚本明明跑完了、证据也打印了，调用方却一直不返回）。
#    ⇒ 改成：cmd 自己把 stdout/stderr 重定向到**文件**，再用 ShellExecute 起 cmd
#       （ShellExecute ⇒ 子进程**不继承**调用方的任何句柄 ⇒ 调用方不会被吊住）。
#    ⇒ 顺带：adb 的输出也**一律写文件再读**（见上面的 Invoke-Adb），同一个坑。
$workDir = 'C:\tm-emu'
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
$workOut = Join-Path $workDir 'emulator.out.log'
$workErr = Join-Path $workDir 'emulator.err.log'
$outLog = $workOut
$errLog = $workErr

$eargs = @('-avd', $Avd, '-no-audio', '-no-boot-anim')
if (-not $Snapshot) { $eargs += '-no-snapshot' }   # 现存快照是脏的（ram.img.dirty）⇒ 默认不加载
if ($NoWindow)     { $eargs += '-no-window' }
if ($WipeData)     { $eargs += '-wipe-data' }
if ($ShowKernel)   { $eargs += '-show-kernel' }

Say ('=== 启动: ' + ($eargs -join ' ') + ' ===')
$launchCmd = Join-Path $workDir 'launch.cmd'
$cmdBody = '@echo off' + [Environment]::NewLine + '"' + $AsciiEmuExe + '" ' + ($eargs -join ' ') + ' > "' + $workOut + '" 2> "' + $workErr + '"'
Set-Content -Path $launchCmd -Value $cmdBody -Encoding ASCII
$proc = Start-Process -FilePath $env:ComSpec -ArgumentList '/c', $launchCmd -WindowStyle Hidden -PassThru
Say ('  启动器 pid=' + $proc.Id + '  日志: ' + $workOut)

# ── 5. 等 boot（只认产物证据：设备变 device + sys.boot_completed=1）─────────
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$boot = ''
while ((Get-Date) -lt $deadline) {
  Start-Sleep -Seconds 5
  $line = Invoke-Adb @('devices')
  $mine = ($line -split '\r?\n' | Where-Object { $_ -match 'emulator-' } | Select-Object -First 1)
  if (-not $mine) { $mine = '<未注册>' } else { $mine = $mine.Trim() }
  $boot = (Invoke-Adb @('-s','emulator-5554','shell','getprop','sys.boot_completed')).Trim()
  Say ('  [' + (Get-Date -Format 'HH:mm:ss') + '] ' + $mine + '  boot=' + $boot)
  if ($boot -eq '1') { break }
}

if ($boot -ne '1') {
  Say ('✗ 没等到 sys.boot_completed=1（超时 ' + $TimeoutSec + ' 秒）')
  Say '── 关键日志行 ──'
  Select-String -Path $outLog -Pattern 'argv\[0\]|program directory|Renderer initialized|CPU Acceleration status|WHPX on Windows|accelerator is operational|Boot completed|enable-whpx|FATAL' -ErrorAction SilentlyContinue | Select-Object -Last 25 | ForEach-Object { Say ('  ' + $_.Line) }
  Say '── 日志尾部 ──'
  Get-Content $outLog -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Say ('  ' + $_) }
  Say ('完整日志: ' + $outLog)
  exit 1
}

# ── 6. 给 App 铺隧道（`adb reverse`）────────────────────────────────────────
#    ⚠️ **这条不跨重启存活**（adb server / 设备重启都会清掉）⇒ 每次起完模拟器都要重铺，
#    否则 App 会显示「连不上服务器」。App 的服务器地址填 `http://127.0.0.1:8090` 即可。
if ($AppTunnel) {
  $rb = Invoke-Adb @('-s','emulator-5554','reverse','tcp:8090','tcp:8090')
  if ($rb -match '8090') {
    Say '   备用隧道: adb reverse tcp:8090 tcp:8090（App 地址优先用 http://10.0.2.2:8090）'
  } else {
    Say '   ⚠️ adb reverse 没建成功 ⇒ App 会显示「连不上服务器」'
  }
}

# ── 7. 打印产物证据 ───────────────────────────────────────────────────────
$shot = Join-Path $logDir ('emulator-' + $stamp + '.png')
& $adb -s emulator-5554 exec-out screencap -p > $shot 2>$null
$size = (Invoke-Adb @('-s','emulator-5554','shell','wm','size')).Trim()
$dens = (Invoke-Adb @('-s','emulator-5554','shell','wm','density')).Trim()
$devText = Invoke-Adb @('devices')
$devLine = ($devText -split '\r?\n' | Where-Object { $_ -match 'emulator-' } | Select-Object -First 1)
$whpx = (Select-String -Path $outLog -Pattern 'enable-whpx|WHPX on Windows|accelerator is operational' -ErrorAction SilentlyContinue | Select-Object -First 1)

Say '✅ 模拟器已启动并 boot 成功'
Say ('   设备: ' + $devLine)
Say ('   sys.boot_completed=' + $boot)
Say ('   ' + $size + ' / ' + $dens + '   （1440x3200@600 = 384dp 宽，与真机同参）')
if ($whpx) { Say '   加速: WHPX（qemu 命令行含 -enable-whpx）' } else { Say '   加速: 未确认（看日志）' }
Say ('   截图: ' + $shot)
Say ('   日志: ' + $outLog)
exit 0
