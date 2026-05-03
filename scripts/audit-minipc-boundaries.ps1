param(
    [string]$HostName = "dynas@192.168.55.103"
)

$ErrorActionPreference = "Continue"
$script:Violations = 0

function Add-Violation {
    param([string]$Message)
    $script:Violations++
    Write-Error "BOUNDARY VIOLATION: $Message"
}

function Invoke-RemoteBoundaryCheck {
    $remote = @'
$violations = 0
function V($m) { $script:violations++; Write-Error "BOUNDARY VIOLATION: $m" }

Write-Host "== Folders =="
Get-Item C:\bitman_justbuy,C:\bitman_marketfloww -ErrorAction SilentlyContinue |
    Select-Object FullName,LastWriteTime |
    Format-Table -AutoSize

if (!(Test-Path 'C:\bitman_justbuy')) { V 'Missing JustBuy root C:\bitman_justbuy' }
if (!(Test-Path 'C:\bitman_marketfloww')) { V 'Missing MarketFlow root C:\bitman_marketfloww' }
if (!(Test-Path 'C:\bitman_justbuy\SERVICE-OWNER.txt')) { V 'Missing JustBuy marker C:\bitman_justbuy\SERVICE-OWNER.txt' }
if (!(Test-Path 'C:\bitman_marketfloww\SERVICE-OWNER.txt')) { V 'Missing MarketFlow marker C:\bitman_marketfloww\SERVICE-OWNER.txt' }

Write-Host "`n== Cloudflared config =="
$configs = @('C:\Users\dynas\.cloudflared\config.yml','C:\ProgramData\Cloudflare\config.yml')
foreach ($config in $configs) {
    if (Test-Path $config) {
        Write-Host "--- $config ---"
        $content = Get-Content $config
        $content
        $joined = $content -join "`n"

        if ($joined -match 'hostname:\s*api\.bitman\.net[\s\S]*?service:\s*http://localhost:8080') {
            V "$config routes api.bitman.net to JustBuy 8080; use api.bit-man.net only"
        }
        if ($joined -match 'hostname:\s*(www\.)?bitman\.net[\s\S]*?service:\s*http://localhost:8080') {
            V "$config routes bitman.net/www.bitman.net to JustBuy 8080"
        }
        if ($joined -notmatch 'hostname:\s*api\.bit-man\.net[\s\S]*?service:\s*http://localhost:8080') {
            V "$config missing api.bit-man.net -> localhost:8080"
        }
        if ($joined -notmatch 'hostname:\s*marketflow-api\.bit-man\.net[\s\S]*?service:\s*http://localhost:5001') {
            V "$config missing marketflow-api.bit-man.net -> localhost:5001"
        }
    }
}

Write-Host "`n== Ports =="
netstat -ano | Select-String ':8080\s|:5001\s'

Write-Host "`n== Scheduled task roots =="
$tasks = @(
  'BitMan-JustBuy-Autostart','BitMan-JustBuy-Autostart-Boot','BitMan-JustBuy-AuxBackup',
  'BitMan-JustBuy-RunSpringBoot','BitMan-JustBuy-SpringBoot',
  'MarketFlow-Cloudflared','MarketFlow-Flask','MarketFlow-Flask-Watchdog',
  'MarketFlow-Scheduler','MarketFlow-Scheduler-Watchdog','MarketFlow-Watchdog','MarketFlowFlaskOneShot'
)
foreach ($task in $tasks) {
    $text = schtasks /Query /TN $task /FO LIST /V 2>$null
    if (!$text) { continue }
    Write-Host "--- $task ---"
    $text | Where-Object { $_ -match 'bitman_justbuy|bitman_marketfloww|MarketFlow|JustBuy' }
    $joined = $text -join "`n"
    if ($task -like 'BitMan-JustBuy-*' -and $joined -match 'bitman_marketfloww') { V "JustBuy task $task references MarketFlow root" }
    if ($task -like 'MarketFlow*' -and $joined -match 'bitman_justbuy') { V "MarketFlow task $task references JustBuy root" }
}

Write-Host "`n== Local health =="
try {
    $justbuy = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/api/health -TimeoutSec 8
    Write-Host "JustBuy 8080: $($justbuy.StatusCode) $($justbuy.Content)"
    if ([string]$justbuy.Content -notlike '*justbuy-api*') { V 'localhost:8080 did not identify as justbuy-api' }
} catch {
    Write-Host "JustBuy 8080: FAILED $($_.Exception.Message)"
    V 'localhost:8080 health failed'
}

try {
    $marketflow = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:5001/api/health -TimeoutSec 8
    Write-Host "MarketFlow 5001: $($marketflow.StatusCode) $($marketflow.Content)"
    if ([string]$marketflow.Content -notlike '*MarketFlow API*') { V 'localhost:5001 did not identify as MarketFlow API' }
} catch {
    Write-Host "MarketFlow 5001: FAILED $($_.Exception.Message)"
    V 'localhost:5001 health failed'
}

if ($violations -gt 0) { exit 2 }
'@

    $remoteScript = "C:/Windows/Temp/audit-minipc-boundaries.ps1"
    $localTemp = Join-Path $env:TEMP ("audit-minipc-boundaries-{0}.ps1" -f ([Guid]::NewGuid().ToString("N")))
    Set-Content -LiteralPath $localTemp -Value $remote -Encoding UTF8
    try {
        scp $localTemp "${HostName}:$remoteScript" | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Add-Violation "Failed to upload remote MiniPC audit script."
            return
        }

        ssh $HostName "powershell -NoProfile -ExecutionPolicy Bypass -File $remoteScript"
        if ($LASTEXITCODE -ne 0) {
            Add-Violation "Remote MiniPC boundary check failed with exit code $LASTEXITCODE"
        }
    } finally {
        Remove-Item -LiteralPath $localTemp -Force -ErrorAction SilentlyContinue
    }
}

function Test-PublicEndpoint {
    param(
        [string]$Url,
        [string]$ExpectedText,
        [switch]$MustNotContainExpectedText
    )

    Write-Host "`n== $Url =="
    try {
        $response = Invoke-WebRequest -UseBasicParsing $Url -TimeoutSec 12
        $content = [string]$response.Content
        Write-Host "HTTP $($response.StatusCode)"
        Write-Host $content.Substring(0, [Math]::Min(240, $content.Length))

        $contains = $content -like "*$ExpectedText*"
        if ($MustNotContainExpectedText) {
            if ($contains) { Add-Violation "$Url contains '$ExpectedText'." }
        } elseif (-not $contains) {
            Add-Violation "$Url did not contain '$ExpectedText'."
        }
    } catch {
        Write-Host "Request failed: $($_.Exception.Message)"
    }
}

Invoke-RemoteBoundaryCheck

Test-PublicEndpoint -Url "https://api.bit-man.net/api/health" -ExpectedText "justbuy-api"
Test-PublicEndpoint -Url "https://api.bitman.net/api/health" -ExpectedText "justbuy-api" -MustNotContainExpectedText
Test-PublicEndpoint -Url "https://marketflow-api.bit-man.net/api/health" -ExpectedText "MarketFlow API"
Test-PublicEndpoint -Url "https://bitman.net" -ExpectedText "justbuy-api" -MustNotContainExpectedText
Test-PublicEndpoint -Url "https://www.bitman.net" -ExpectedText "justbuy-api" -MustNotContainExpectedText

if ($script:Violations -gt 0) {
    Write-Error "Boundary audit failed with $script:Violations violation(s)."
    exit 2
}

Write-Host "`nBoundary audit passed."
