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
            $end = [DateTime]::Parse($Matches[1].Trim())
            $days = ($end - (Get-Date)).TotalDays
            Write-Host ("当前证书到期: {0}（还有 {1:N1} 天）" -f $end, $days)
            if ($days -gt 30) {
                Write-Host '还有 30 天以上，跳过续期。'
                $needRestart = $false
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
