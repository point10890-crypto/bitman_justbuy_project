param(
    [string]$HostName = "dynas@192.168.55.103"
)

$ErrorActionPreference = "Continue"

function Invoke-RemoteBoundaryCheck {
    $remote = @'
Write-Host "== Folders =="
Get-ChildItem C:\ -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'bitman|market|justbuy|flow' } |
    Select-Object FullName,LastWriteTime |
    Format-Table -AutoSize

Write-Host "`n== Cloudflared config =="
$configs = @(
    'C:\Users\dynas\.cloudflared\config.yml',
    'C:\Windows\System32\config\systemprofile\.cloudflared\config.yml',
    'C:\ProgramData\Cloudflare\config.yml'
)
foreach ($config in $configs) {
    if (Test-Path $config) {
        Write-Host "--- $config ---"
        Get-Content $config
    }
}

Write-Host "`n== Ports =="
netstat -ano | Select-String ':8080\s|:5001\s'

Write-Host "`n== Scheduled task roots =="
schtasks /Query /FO LIST /V |
    Select-String -Pattern 'TaskName:|Task To Run:|Start In:|bitman_justbuy|bitman_marketfloww|MarketFlow|JustBuy' -Context 0,1

Write-Host "`n== Local health =="
try {
    $justbuy = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8080/api/health -TimeoutSec 8
    Write-Host "JustBuy 8080: $($justbuy.StatusCode) $($justbuy.Content)"
} catch {
    Write-Host "JustBuy 8080: FAILED $($_.Exception.Message)"
}

try {
    $marketflow = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:5001/api/health -TimeoutSec 8
    Write-Host "MarketFlow 5001: $($marketflow.StatusCode) $($marketflow.Content)"
} catch {
    Write-Host "MarketFlow 5001: FAILED $($_.Exception.Message)"
}
'@

    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($remote))
    ssh $HostName "powershell -NoProfile -EncodedCommand $encoded"
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
            if ($contains) {
                Write-Error "Boundary violation: $Url contains '$ExpectedText'."
            }
        } elseif (-not $contains) {
            Write-Error "Unexpected service response: $Url did not contain '$ExpectedText'."
        }
    } catch {
        Write-Host "Request failed: $($_.Exception.Message)"
    }
}

Invoke-RemoteBoundaryCheck

Test-PublicEndpoint -Url "https://api.bit-man.net/api/health" -ExpectedText "justbuy-api"
Test-PublicEndpoint -Url "https://marketflow-api.bit-man.net/api/health" -ExpectedText "MarketFlow API"
Test-PublicEndpoint -Url "https://bitman.net" -ExpectedText "justbuy-api" -MustNotContainExpectedText
Test-PublicEndpoint -Url "https://www.bitman.net" -ExpectedText "justbuy-api" -MustNotContainExpectedText

