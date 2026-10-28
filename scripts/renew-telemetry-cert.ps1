#!/usr/bin/env pwsh
# 续期 Fleet Telemetry 接收端的服务器证书。
#
# 为什么需要它：
#   接收端用的是 **Tailscale 签的 Let's Encrypt 证书**（Tesla 会验，必须公开可信）。
#   LE 证书有效期 90 天 ⇒ 过期后 Tesla 会拒绝连接，遥测数据直接断流。
#   Tailscale 自己会续签证书，但**只在你手动跑 `tailscale cert` 时才写文件** ——
#   tailscaled 后台续的是它的内部副本，不是我们容器读的那个文件。
#
# 为什么必须重启容器：
#   Docker bind mount 挂的是**文件**。`tailscale cert` 是「写新文件 + rename」，
#   容器里挂的还是**旧 inode** ⇒ 不重启就读到旧证书。
#
# 用法（建议每周跑一次，或挂 Windows 计划任务）：
#   pwsh -NoProfile -File scripts/renew-telemetry-cert.ps1

$ErrorActionPreference = 'Stop'

$Domain = '<主机名>.<tailnet域>.ts.net'
$CertDir = '${CERTS_DIR}'
$Container = 'teslamate-fleet-telemetry'

# ── 重下发遥测配置用（v0.79.0）──────────────────────────────────
# ApiBase：本地 API（读 .env 拿 API_TOKEN 鉴权）
# CarId  ：要下发给哪台车（默认 1）
$ApiBase = 'http://127.0.0.1:8090'
$CarId = 1
$RepoRoot = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path $CertDir)) {
    New-Item -ItemType Directory -Force -Path $CertDir | Out-Null
}

# 只在**快到期**时才重启容器（避免每周无谓断开公网入口）。
$needRestart = $true
$leaf = Join-Path $CertDir 'server.crt'
if (Test-Path $leaf) {
    $oss = 'C:\Program Files\Git\usr\bin\openssl.exe'
    if (Test-Path $oss) {
        $endTxt = & $oss x509 -in $leaf -noout -enddate 2>$null
        if ($endTxt -match 'notAfter=(.+)$') {
            # ⚠️ **v0.79.0 修的真 bug**：原来这里用的是 `[DateTime]::Parse($s)`。
            #
            #   openssl 的格式是 `Jan 23 15:24:06 2027 GMT` ——
            #   **这台机器的系统区域是中文**，`.Parse` 认不出结尾的 "GMT"，
            #   实测直接抛 `String 'Jan 23 15:24:06 2027 GMT' was not recognized`。
            #
            #   ⇒ 脚本**每次跑到这里就崩**，续期从来没成功执行过 ——
            #   遥测会在证书到期那天静默断流。
            #
            #   ⚠️ 连 `Parse` 配 InvariantCulture 也照样失败（实测），
            #   `ParseExact` 虽能过却**差 8 小时**（把 GMT 当成了本地时区）。
            #   ⇒ 这里**完全不依赖区域设置**：正则拆字段 + 月份表 + 按 UTC 构造。
            $raw = $Matches[1].Trim()
            $end = $null
            $MONTHS = @{
                jan = 1; feb = 2; mar = 3; apr = 4; may = 5; jun = 6
                jul = 7; aug = 8; sep = 9; oct = 10; nov = 11; dec = 12
            }
            if ($raw -match '^(?<mon>[A-Za-z]{3})\s+(?<day>\d{1,2})\s+(?<hh>\d{1,2}):(?<mm>\d{2}):(?<ss>\d{2})\s+(?<yyyy>\d{4})') {
                $m = $MONTHS[$Matches['mon'].ToLowerInvariant()]
                if ($m) {
                    try {
                        $end = [datetime]::new(
                            [int]$Matches['yyyy'], $m, [int]$Matches['day'],
                            [int]$Matches['hh'], [int]$Matches['mm'], [int]$Matches['ss'],
                            [System.DateTimeKind]::Utc)
                    } catch { $end = $null }
                }
            }
            if ($null -ne $end) {
                $days = ($end - (Get-Date).ToUniversalTime()).TotalDays
                Write-Host ("当前证书到期: {0} UTC（还有 {1:N1} 天）" -f $end, $days)
                if ($days -gt 30) {
                    Write-Host '还有 30 天以上，跳过续期。'
                    $needRestart = $false
                }
            } else {
                # ⚠️ 解析不了 ⇒ **不知道还有多久** ⇒ 保守处理：去续一次。
                #    （`tailscale cert` 幂等，多跑一次不会出事；
                #      不续期的代价是证书到期后遥测直接断。）
                Write-Host ("到期时间读不出来：{0} ⇒ 按「需要续期」处理。" -f $raw)
            }
        }
    }
}

if ($needRestart) {
    Write-Host '正在向 Tailscale 申请新证书...'
    Push-Location $CertDir
    try {
        & tailscale cert $Domain
        if ($LASTEXITCODE -ne 0) { throw "tailscale cert 失败（exit=$LASTEXITCODE）" }
    } finally {
        Pop-Location
    }

    # tailscale cert 写的是 <domain>.crt / <domain>.key，规整成 server.crt / server.key
    Copy-Item (Join-Path $CertDir "$Domain.crt") (Join-Path $CertDir 'server.crt') -Force
    Copy-Item (Join-Path $CertDir "$Domain.key") (Join-Path $CertDir 'server.key') -Force

    Write-Host '重启接收端让它读到新证书...'
    & docker restart $Container | Out-Null

    Start-Sleep -Seconds 3
    Write-Host '当前状态:'
    & docker ps --filter "name=$Container" --format '{{.Names}} {{.Status}}'
    & tailscale funnel status
}

# ── 重下发遥测配置 ───────────────────────────────────────────────
#
# ⚠️ **为什么续期证书后必须重下发**：
#
#   车端手里存着一份「CA 证书串 + 有效期」。
#   证书换了 ⇒ 链变了 ⇒ 车端用旧 CA 验不过新的服务器证书
#   ⇒ **遥测直接断流**，而且**不报错**（车连不上就是连不上）。
#
# ⚠️ **另一条独立的到期线**：官方对配置里的 `exp` 有硬上限
#   （「服务端当下 + 365 天」，实测原文），所以这份配置**到点就失效**。
#   我们下发时取的是 +360 天 ⇒ 差不多一年后必须重签。
#   ⇒ 这一步**不只**跟着证书跑，也跟着「剩余天数」跑。
#
# ⚠️ 下发走**服务端**（command-proxy 签名），脚本**不自己造 JWS**。

function Get-ApiToken {
    $envFile = Join-Path $RepoRoot '.env'
    if (-not (Test-Path $envFile)) { return '' }
    foreach ($line in (Get-Content $envFile -Encoding utf8)) {
        if ($line -match '^\s*API_TOKEN=(.*)$') {
            return $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
    return ''
}

function Redeploy-TelemetryConfig([string]$reason, [bool]$force = $false) {
    $token = Get-ApiToken
    if (-not $token) {
        Write-Host '⚠️ 读不到 API_TOKEN —— 跳过重下发（证书已换但车端配置还是旧的，遥测会断）。'
        return $false
    }
    $hdr = @{ Authorization = "Bearer $token" }
    try {
        $st = Invoke-RestMethod -Uri "$ApiBase/api/v1/cars/$CarId/fleet_telemetry" -Headers $hdr -TimeoutSec 30
    } catch {
        Write-Host "⚠️ 查遥测配置现状失败：$($_.Exception.Message)"
        return $false
    }

    $days = -1
    if ($st.config_summary -and $null -ne $st.config_summary.days_left) {
        $days = [int]$st.config_summary.days_left
    }
    if (-not $force -and $days -ge 30) {
        Write-Host ("遥测配置还有 {0} 天有效期 —— 不用重下发。" -f $days)
        return $true
    }

    try {
        $res = Invoke-RestMethod -Method POST -Uri "$ApiBase/api/v1/cars/$CarId/fleet_telemetry" `
                                 -Headers $hdr -TimeoutSec 90
    } catch {
        Write-Host "⚠️ 重下发失败：$($_.Exception.Message)"
        return $false
    }

    # ⚠️ 三态分开报（v0.76.0 的教训）：「发出去了」≠「车接下了」≠「车已经在用」。
    Write-Host ("✅ 已重下发（{0}）：发出={1} 车端接受={2} 车端已采用={3} 有效期剩 {4} 天" -f `
                $reason, $res.sent, $res.accepted, $res.synced, $days)
    if ($res.message) { Write-Host ("   说明：{0}" -f $res.message) }
    if ($res.skipped -and $res.skipped.Count -gt 0) {
        foreach ($s in $res.skipped) {
            Write-Host ("   ⚠️ 这台车被跳过：{0}（{1}）" -f $s.vin, $s.reason)
        }
    }
    return $true
}

# 证书换了 ⇒ **必须**重下发（链变了，车端手里的旧 CA 验不过）。
if ($needRestart) {
    Redeploy-TelemetryConfig -reason '证书已续期' -force $true | Out-Null
} else {
    # 没换证书 ⇒ 只在快到期时重签（配置有自己的到期线）。
    Redeploy-TelemetryConfig -reason '配置快到期' | Out-Null
}
