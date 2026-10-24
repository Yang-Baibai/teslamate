<#
.SYNOPSIS
    Tesla Fleet API 一次性授权（中国区），换出 refresh_token 并写入 .env。

.DESCRIPTION
    Fleet API 的 client_credentials 令牌**没有 scope**，调任何端点都是
    403 Unauthorized missing scopes。必须走一次 authorization_code 流程，
    由车主的 Tesla 账号显式授权，才能拿到带 vehicle_device_data scope 的
    refresh_token。

    本脚本做三件事：
      1. 起一个本地 HTTP 监听，等 Tesla 回调把 code 送回来
      2. 用 code 换 access_token + refresh_token
      3. 把 refresh_token 写回 .env 的 TESLA_REFRESH_TOKEN=（原子写）

    用法 A（redirect_uri 已在 Tesla 后台注册为本机 localhost，推荐）：
        pwsh -File scripts/tesla-authorize.ps1
        脚本会打印一个 URL，在浏览器里打开、同意授权，脚本自动收尾。

    用法 B（后台注册的是别的地址，把回调 URL 里的 code 贴过来）：
        pwsh -File scripts/tesla-authorize.ps1 -Code '<回调URL里的code>'

.PARAMETER RedirectUri
    必须是 Tesla 开发者后台为该 client 注册的回调地址（精确匹配）。

.PARAMETER Port
    本地监听端口（用法 A）。

.PARAMETER Region
    cn（默认，中国区）| na | eu

.NOTES
    凭据只从 .env 读，**不会**写进任何被 git 跟踪的文件。
    refresh_token 是一次性、有效期 3 个月，服务端每次刷新会轮换并写回 .env。
#>
[CmdletBinding()]
param(
    [string]$RedirectUri = 'http://localhost:8000/callback',
    [int]$Port = 8000,
    [string]$Region = 'cn',
    [string]$Code = '',
    [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'

$authBase = switch ($Region) {
    'cn' { 'https://auth.tesla.cn' }
    'na' { 'https://auth.tesla.com' }
    'eu' { 'https://auth.tesla.eu' }
    default { throw "未知 region: $Region" }
}
$fleetBase = switch ($Region) {
    'cn' { 'https://fleet-api.prd.cn.vn.cloud.tesla.cn' }
    'na' { 'https://fleet-api.prd.na.vn.cloud.tesla.com' }
    'eu' { 'https://fleet-api.prd.eu.vn.cloud.tesla.com' }
    default { throw "未知 region: $Region" }
}

# ── 读凭据 ──────────────────────────────────────────────
if (-not (Test-Path $envFile)) { throw "找不到 .env：$envFile" }
$envLines = [System.IO.File]::ReadAllLines($envFile, (New-Object System.Text.UTF8Encoding($false)))
function Get-EnvValue($name) {
    foreach ($l in $envLines) { if ($l -match "^$name=(.*)$") { return $Matches[1].Trim() } }
    return $null
}
$clientId = Get-EnvValue 'TESLA_CLIENT_ID'
$clientSecret = Get-EnvValue 'TESLA_CLIENT_SECRET'
if (-not $clientId -or -not $clientSecret) {
    throw "请先在 .env 里设置 TESLA_CLIENT_ID 与 TESLA_CLIENT_SECRET"
}

# ── PKCE ────────────────────────────────────────────────
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$b = New-Object byte[] 64; $rng.GetBytes($b)
$verifier = [Convert]::ToBase64String($b).TrimEnd('=').Replace('+','-').Replace('/','_')
$hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::ASCII.GetBytes($verifier))
$challenge = [Convert]::ToBase64String($hash).TrimEnd('=').Replace('+','-').Replace('/','_')
$b2 = New-Object byte[] 24; $rng.GetBytes($b2)
$state = [Convert]::ToBase64String($b2).TrimEnd('=').Replace('+','-').Replace('/','_')

# scope 只要读车辆实时数据（含 lock_state）；不申请 vehicle_cmds（那是解锁/远程启动等写操作）
$scope = 'openid offline_access vehicle_device_data'

if (-not $Code) {
    # ── 监听回调 ──────────────────────────────────────────
    $listener = New-Object System.Net.HttpListener
    $listener.Prefixes.Add("http://localhost:$Port/")
    $listener.Start()
    Write-Host ''
    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    Write-Host ' 请在浏览器打开下面的 URL，登录并同意授权：' -ForegroundColor Cyan
    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    $url = "$authBase/oauth2/v3/authorize?client_id=$clientId&code_challenge=$challenge&code_challenge_method=S256&redirect_uri=$([uri]::EscapeDataString($RedirectUri))&scope=$([uri]::EscapeDataString($scope))&state=$state&prompt=login&prompt_missing_scopes=true&response_type=code"
    Write-Host $url -ForegroundColor Yellow
    Write-Host ''
    Write-Host " 正在等待回调（http://localhost:$Port/callback）… 授权后自动继续。" -ForegroundColor DarkGray
    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    Write-Host ''

    $ctx = $listener.GetContext()
    # 手动解析 query（不依赖 System.Web，PowerShell 7 上不可靠）
    $q = @{}
    $qs = $ctx.Request.Url.Query
    if ($qs) {
        foreach ($pair in ($qs.TrimStart('?') -split '&')) {
            if ($pair -match '^([^=]+)=(.*)$') { $q[$Matches[1]] = [System.Uri]::UnescapeDataString($Matches[2]) }
        }
    }
    $listener.Stop()
    $Code = $q['code']
    $err = $q['error']
    $gotState = $q['state']
    if ($err) { throw "授权失败：$err $($q['error_description'])" }
    if (-not $Code) { throw "回调里没有 code。实际收到：$($ctx.Request.Url)" }
    if ($gotState -ne $state) { throw "state 不匹配（可能是 CSRF），请重试" }
    Write-Host "✓ 收到授权码（$($Code.Length) 字符）" -ForegroundColor Green
}

# ── 换 token ─────────────────────────────────────────────
$formFile = Join-Path $env:TEMP "tesla-auth-form.txt"
$form = [ordered]@{
    grant_type    = 'authorization_code'
    client_id     = $clientId
    client_secret = $clientSecret
    code          = $Code
    audience      = $fleetBase
    redirect_uri  = $RedirectUri
}
$pairs = $form.GetEnumerator() | ForEach-Object { "$([uri]::EscapeDataString($_.Key))=$([uri]::EscapeDataString([string]$_.Value))" }
[System.IO.File]::WriteAllText($formFile, ($pairs -join '&'), (New-Object System.Text.UTF8Encoding($false)))

$resp = & curl.exe -s -X POST --data "@$formFile" "$authBase/oauth2/v3/token" 2>&1
$body = ($resp -join "`n")
try { $tok = $body | ConvertFrom-Json } catch { throw "换 token 失败：$body" }
if (-not $tok.access_token) { throw "换 token 失败：$body" }
Remove-Item -LiteralPath $formFile -Force -ErrorAction SilentlyContinue

Write-Host "✓ 拿到 access_token（scope: $($tok.scope)）" -ForegroundColor Green
if (-not $tok.refresh_token) { throw "响应里没有 refresh_token —— 确认授权时带了 offline_access scope" }

# ── 写回 .env（原子） ────────────────────────────────────
$newLines = New-Object System.Collections.Generic.List[string]
$replaced = $false
foreach ($l in $envLines) {
    if ($l -match '^TESLA_REFRESH_TOKEN=') {
        $newLines.Add("TESLA_REFRESH_TOKEN=$($tok.refresh_token)"); $replaced = $true
    } else { $newLines.Add($l) }
}
if (-not $replaced) {
    $newLines.Add("TESLA_REFRESH_TOKEN=$($tok.refresh_token)")
    $newLines.Add("TESLA_REGION=$Region")
}
$tmp = "$envFile.tmp"
[System.IO.File]::WriteAllLines($tmp, $newLines, (New-Object System.Text.UTF8Encoding($false)))
Move-Item -LiteralPath $tmp -Destination $envFile -Force
Write-Host "✓ refresh_token 已写入 .env（原子替换）" -ForegroundColor Green

# ── 立刻验证：列车辆 + 取 lock_state ──────────────────────
Write-Host ''
Write-Host '── 验证 Fleet API ──' -ForegroundColor Cyan
$vehResp = & curl.exe -s -H "Authorization: Bearer $($tok.access_token)" "$fleetBase/api/1/vehicles" 2>&1
try { $veh = ($vehResp -join "`n") | ConvertFrom-Json } catch { $veh = $null }
if ($veh -and $veh.Count -gt 0) {
    foreach ($v in $veh) { Write-Host "  车辆: id=$($v.id) name='$($v.display_name)'" -ForegroundColor DarkGray }
    $vid = $veh[0].id
    $vdResp = & curl.exe -s -H "Authorization: Bearer $($tok.access_token)" "$fleetBase/api/1/vehicles/$vid/vehicle_data?endpoints=lock_state" 2>&1
    try {
        $vd = ($vdResp -join "`n") | ConvertFrom-Json
        Write-Host "  lock_state = $($vd.data.lock_state)" -ForegroundColor Green
    } catch { Write-Host "  vehicle_data 响应: $(($vdResp -join "`n").Substring(0,[Math]::Min(200,($vdResp -join "`n").Length)))" }
} else {
    Write-Host "  ⚠ 车辆列表为空或解析失败。可能是该 client 还没被授权车辆。" -ForegroundColor Yellow
    Write-Host "  $(($vehResp -join "`n").Substring(0,[Math]::Min(300,($vehResp -join "`n").Length)))" -ForegroundColor DarkGray
}
Write-Host ''
Write-Host '接下来：docker compose up -d api --build 让服务端加载新凭据。' -ForegroundColor Cyan
