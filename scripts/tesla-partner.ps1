<#
.SYNOPSIS
    Tesla 车控链路的 **公钥域名管理**（partner account）。

.DESCRIPTION
    2021 年后的 Model 3/Y 发「签名命令」必须用**注册在 Tesla partner account 上的
    那把私钥**做 ECDSA 签名。这套链路的**一次性搭建**在这里：

      1. 生成/复用 EC(P-256) 私钥（车辆签名私钥）
      2. 把公钥注册到 Tesla（`POST /api/1/partner_accounts`）
      3. 以后随时查状态（`GET /api/1/partner_accounts/public_key`）

    ⚠️ **本项目里为什么需要这个脚本**：
    v0.66.0 把车控整体下沉到本项目后，密钥卷 `teslamate_command_keys`、
    command-proxy sidecar、partner account 都归本项目管。
    但**换域名**（比如 Tailscale 的机器名变了）或**轮换密钥**时，
    必须重新注册公钥 —— 没有这一步，sidecar 签出来的命令 Tesla 验不过。

    ⚠️ **partner account 是「原地更新」，不是「注册即锁死」**（2026-10-26 实测）：
    换域名后重新注册同一个应用即可，`created_at` 不变、`updated_at` 刷新，
    **不需要**新建 3P 应用。旧域名会随之失效。

    ⚠️ **公钥注册后不是「随便哪个域名都行」**：
    Tesla 后台「允许的源 URL」里必须**有那个完整主机名**，
    且它自己得有有效 TLS 证书（后台表单会当场校验）。

.PARAMETER Domain
    要注册 / 要查询的**公网域名**（如 `<主机名>.<tailnet域>.ts.net`）。

.PARAMETER Action
    `status`（默认，查注册状态）或 `register`（注册 / 换域名）。

.PARAMETER Volume
    车辆私钥所在的 docker 卷名，默认 `teslamate_command_keys`。

.PARAMETER Force
    允许对 **trycloudflare 临时域名** 执行注册（默认拒绝 ——
    临时域名隧道一停就失效，把车控永久绑死在会死的地址上没意义）。

.EXAMPLE
    pwsh -File scripts/tesla-partner.ps1 -Domain <主机名>.<tailnet域>.ts.net

.EXAMPLE
    pwsh -File scripts/tesla-partner.ps1 -Action register -Domain new-host.<tailnet域>.ts.net
#>
[CmdletBinding()]
param(
    [ValidateSet('status', 'register')]
    [string]$Action = 'status',
    [Parameter(Mandatory = $true)]
    [string]$Domain,
    [string]$Volume = 'teslamate_command_keys',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ── 区域端点 ───────────────────────────────────────────────────────
# ⚠️ **必须用中国区的 api_base** —— 官方排障原话：
#    「确保你已为用户所在区域调用了注册端点」。
$Regions = @{
    cn = @{
        auth  = 'https://auth.tesla.cn'
        fleet = 'https://fleet-api.prd.cn.vn.cloud.tesla.cn'
    }
    na = @{
        auth  = 'https://auth.tesla.com'
        fleet = 'https://fleet-api.prd.na.vn.cloud.tesla.com'
    }
    eu = @{
        auth  = 'https://auth.tesla.eu'
        fleet = 'https://fleet-api.prd.eu.vn.cloud.tesla.com'
    }
}

# ⚠️ **partner 令牌的 scope 不含 offline_access** ——
#    client_credentials 拿不到 refresh_token，加了也没用。
#    这份 scope 与 Housekeeper 实测通过的那份**逐字一致**。
$PartnerScope = 'openid email vehicle_device_data vehicle_cmds vehicle_charging_cmds vehicle_location'

function Write-Step($msg) { Write-Host "▶ $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "✅ $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "⚠️  $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "❌ $msg" -ForegroundColor Red }

# ── 读 .env ────────────────────────────────────────────────────────
function Read-DotEnv {
    $root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
    $envFile = Join-Path $root '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw "找不到 $envFile —— 在项目根目录运行本脚本"
    }
    $map = [ordered]@{}
    foreach ($line in [System.IO.File]::ReadAllLines($envFile, [System.Text.UTF8Encoding]::new($false))) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $kv = $line -split '=', 2
        $key = $kv[0].Trim()
        $val = $kv[1].Trim().Trim('"').Trim("'")
        # ⚠️ **同名键取第一个** —— 本项目 .env 里 TESLA_REGION 出现过两次，
        #    取后出现的那个会把区域带偏。
        if (-not $map.Contains($key)) { $map[$key] = $val }
    }
    return $map
}

# ── partner 令牌 ───────────────────────────────────────────────────
function Get-PartnerToken {
    param([hashtable]$Endpoints, [hashtable]$Env)
    if (-not $Env['TESLA_CLIENT_ID'] -or -not $Env['TESLA_CLIENT_SECRET']) {
        throw '未配置 TESLA_CLIENT_ID / TESLA_CLIENT_SECRET'
    }
    $body = @{
        grant_type    = 'client_credentials'
        client_id     = $Env['TESLA_CLIENT_ID']
        client_secret = $Env['TESLA_CLIENT_SECRET']
        # ⚠️ **audience 必填**：上游 sidecar 从令牌的 aud 推导区域，
        #    缺了就落到北美端点（症状：命令发到了北美）。
        audience      = $Endpoints.fleet
        scope         = $PartnerScope
    }
    $resp = Invoke-RestMethod -Method Post -Uri "$($Endpoints.auth)/oauth2/v3/token" `
        -Body $body -ContentType 'application/x-www-form-urlencoded' -TimeoutSec 30
    if (-not $resp.access_token) {
        throw 'partner 令牌响应里没有 access_token'
    }
    return $resp.access_token
}

# ── 从卷里取车辆私钥 ─────────────────────────────────────────────
#
# ⚠️ **私钥会短暂进入本进程内存**。
#    这是**运维脚本跑在车主自己机器上**的场景 —— 私钥本来就在这台机器的
#    docker 卷里，脚本只是读出来算一个**公钥**。
#    真正的红线是另外两条，都守住了：
#      · 私钥**不发给**任何服务（注册时只提交公钥）；
#      · api 容器**不挂** command_keys 卷（只挂公开证书）。
function Get-LocalPrivateKeyPem {
    param([string]$VolumeName)
    # ⚠️ 用 alpine（本地已缓存）只做「读文件」——
    #    alpine/openssl 这类镜像本机拉不到（镜像源不通），而且不需要：
    #    公钥推导用 .NET 自带的 ECDsa 就行。
    $pem = docker run --rm -v "${VolumeName}:/v:ro" alpine sh -c `
        'cat /v/private-key.pem 2>/dev/null || cat /v/tesla-command-key.pem 2>/dev/null' 2>$null
    if (-not $pem) {
        throw "卷 $VolumeName 里读不出 EC 私钥（/v/private-key.pem 或 /v/tesla-command-key.pem）"
    }
    return ($pem -join "`n").Trim()
}

# ── 私钥 PEM → 公钥 PEM（SubjectPublicKeyInfo，注册时要提交它）─────
function Get-PublicKeyPem {
    param([string]$PrivateKeyPem)
    $ec = [System.Security.Cryptography.ECDsa]::Create()
    try {
        $ec.ImportFromPem($PrivateKeyPem)
        $b64 = [Convert]::ToBase64String($ec.ExportSubjectPublicKeyInfo())
        $lines = [regex]::Matches($b64, '.{1,64}') | ForEach-Object { $_.Value }
        return "-----BEGIN PUBLIC KEY-----`n" + ($lines -join "`n") + "`n-----END PUBLIC KEY-----"
    } finally {
        $ec.Dispose()
    }
}

# ── 私钥 PEM → 未压缩 EC 点（04‖X‖Y 的 hex，Tesla 返回的就是它）──
function Get-PublicKeyPoint {
    param([string]$PrivateKeyPem)
    $ec = [System.Security.Cryptography.ECDsa]::Create()
    try {
        $ec.ImportFromPem($PrivateKeyPem)
        $p = $ec.ExportParameters($false)
        return '04' + [Convert]::ToHexString($p.Q.X).ToLowerInvariant() +
                        [Convert]::ToHexString($p.Q.Y).ToLowerInvariant()
    } finally {
        $ec.Dispose()
    }
}

# ── 解析 public_key 响应 ──────────────────────────────────────────
# ⚠️ **中国区的响应包在 `{"response":{...}}` 里**（实测 2026-10-26），
#    不是顶层字段 —— 直接读 `$j.public_key` 会拿到 $null。
function Get-PublicKeyHex {
    param([string]$Content)
    $obj = $Content | ConvertFrom-Json
    if ($null -ne $obj -and $obj.PSObject.Properties.Name -contains 'response') {
        $obj = $obj.response
    }
    if ($null -ne $obj -and $obj.PSObject.Properties.Name -contains 'public_key') {
        return $obj.public_key
    }
    return $null
}

# ── 取 HTTP 状态码（StrictMode 下别把原始错误盖掉）───────────────
function Get-HttpStatus {
    param($ErrorRecord)
    $code = 0
    $prop = $ErrorRecord.Exception.PSObject.Properties['Response']
    if ($null -ne $prop -and $null -ne $prop.Value) {
        try { $code = [int]$prop.Value.StatusCode } catch { $code = 0 }
    }
    return $code
}

# ── 主流程 ────────────────────────────────────────────────────────
$envMap = Read-DotEnv
$region = if ($envMap['TESLA_REGION']) { $envMap['TESLA_REGION'] } else { 'cn' }
if (-not $Regions.ContainsKey($region)) {
    throw "未知区域 '$region'（可选：cn / na / eu）"
}
$ep = $Regions[$region]

Write-Host "══ Tesla partner account · $Action ══" -ForegroundColor White
Write-Host "区域 : $region  ($($ep.fleet))"
Write-Host "域名 : $Domain"
Write-Host ""

$token = Get-PartnerToken -Endpoints $ep -Env $envMap
Write-Ok 'partner 令牌已取得'

$headers = @{ Authorization = "Bearer $token" }

if ($Action -eq 'status') {
    $url = "$($ep.fleet)/api/1/partner_accounts/public_key?domain=$([uri]::EscapeDataString($Domain))"
    try {
        $r = Invoke-WebRequest -Method Get -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 30
        $remotePoint = Get-PublicKeyHex -Content $r.Content
        Write-Ok "已注册（HTTP $($r.StatusCode)）"
        Write-Host "   Tesla 返回的公钥点: $remotePoint"
        try {
            $localPem = Get-LocalPrivateKeyPem -VolumeName $Volume
            $localPoint = Get-PublicKeyPoint -PrivateKeyPem $localPem
            Write-Host "   本地签名私钥的点 : $localPoint"
            if ($localPoint -eq $remotePoint) {
                Write-Ok '与本地签名私钥**同一把** ⇒ 签名能验过'
            } else {
                Write-Err '与本地签名私钥**不是同一把** ⇒ 签名会验不过（车控必然失败）'
            }
        } catch {
            Write-Warn "读不到本地私钥，跳过比对：$($_.Exception.Message)"
        }
    } catch {
        $code = Get-HttpStatus -ErrorRecord $_
        switch ($code) {
            403 {
                Write-Err "HTTP 403 —— 应用的 allowed_origins 里没有这个根域"
                Write-Host "   ⇒ 在 developer.tesla.cn 后台「允许的源 URL」补上**完整主机名**"
                Write-Host "     （该域名自己得有有效 TLS 证书，后台表单会当场校验）"
            }
            404 { Write-Warn 'HTTP 404 —— 该域名**未注册**' }
            default {
                Write-Err "查询失败（HTTP $code）—— $($_.Exception.Message)"
            }
        }
    }
    exit 0
}

# ── register ───────────────────────────────────────────────────────
if ($Domain -like '*trycloudflare.com' -and -not $Force) {
    Write-Err '这是 trycloudflare **临时**域名 —— 隧道一停就失效。'
    Write-Host '   注册之后改不了，等于把车控永久绑死在一个会死的地址上。'
    Write-Host '   确实要用，加 -Force。'
    exit 1
}

Write-Step "从卷 $Volume 推导公钥"
$privateKeyPem = Get-LocalPrivateKeyPem -VolumeName $Volume
$publicKeyPem = Get-PublicKeyPem -PrivateKeyPem $privateKeyPem
Write-Host $publicKeyPem

$payload = @{
    domain     = $Domain
    public_key = $publicKeyPem
} | ConvertTo-Json -Depth 3

Write-Step "注册到 $($ep.fleet)/api/1/partner_accounts"
try {
    $r = Invoke-RestMethod -Method Post -Uri "$($ep.fleet)/api/1/partner_accounts" `
        -Headers $headers -Body $payload -ContentType 'application/json' -TimeoutSec 30
    Write-Ok 'partner account 已注册 / 已更新'
    Write-Host "   响应: $($r | ConvertTo-Json -Compress)"
} catch {
    $code = [int]$_.Exception.Response.StatusCode
    Write-Err "注册失败（HTTP $code）"
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}

Write-Host ''
Write-Step '复核'
try {
    $url = "$($ep.fleet)/api/1/partner_accounts/public_key?domain=$([uri]::EscapeDataString($Domain))"
    $r = Invoke-WebRequest -Method Get -Uri $url -Headers $headers -UseBasicParsing -TimeoutSec 30
    $remotePoint = Get-PublicKeyHex -Content $r.Content
    $localPoint = Get-LocalPublicKeyPoint -PublicKeyPem $publicKeyPem
    Write-Host "   Tesla 返回的点: $remotePoint"
    Write-Host "   本地私钥的点  : $localPoint"
    if ($localPoint -eq $remotePoint) {
        Write-Ok '与本地签名私钥同一把 ⇒ 签名能验过'
    } else {
        Write-Err '与本地签名私钥不是同一把 ⇒ 签名会验不过'
    }
} catch {
    Write-Warn "复核失败：$($_.Exception.Message)"
}
