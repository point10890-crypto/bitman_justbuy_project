$ErrorActionPreference = "SilentlyContinue"

$Project = "C:\bitman_justbuy_project"
$LogDir = Join-Path $Project "logs"
$LogFile = Join-Path $LogDir "justbuy-watchdog.log"
$StartSpring = Join-Path $Project "scripts\start-springboot.bat"
$HealthUrl = "http://127.0.0.1:8080/actuator/health"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-WatchdogLog {
    param([string]$Message)
    $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Add-Content -Path $LogFile -Value "$ts | $Message" -Encoding UTF8
}

function Test-JustBuyHealth {
    try {
        $content = & curl.exe -s --max-time 8 $HealthUrl
        return ($LASTEXITCODE -eq 0 -and $content -match '"status"\s*:\s*"UP"')
    } catch {
        return $false
    }
}

function Test-Port8080 {
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    return $null -ne $conn
}

function Start-JustBuy {
    if (-not (Test-Path $StartSpring)) {
        Write-WatchdogLog "START FAILED: missing $StartSpring"
        return
    }

    Write-WatchdogLog "START: launching Spring Boot"
    Start-Process -FilePath "cmd.exe" `
        -ArgumentList "/c `"$StartSpring`"" `
        -WorkingDirectory $Project `
        -WindowStyle Hidden | Out-Null
}

function Ensure-Cloudflared {
    $service = Get-Service -Name "cloudflared" -ErrorAction SilentlyContinue
    if ($service -and $service.Status -ne "Running") {
        Write-WatchdogLog "CLOUDFLARED: starting Windows service"
        Start-Service -Name "cloudflared" -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
    }

    $proc = Get-Process -Name "cloudflared" -ErrorAction SilentlyContinue
    if (-not $proc) {
        Write-WatchdogLog "CLOUDFLARED: process not found after service check"
    }
}

function Invoke-WatchdogPass {
    Ensure-Cloudflared

    if (Test-JustBuyHealth) {
        Write-WatchdogLog "OK: JustBuy health UP"
        return
    }

    if (Test-Port8080) {
        Write-WatchdogLog "WARN: port 8080 is listening but health is DOWN"
        Start-Sleep -Seconds 20
        if (Test-JustBuyHealth) {
            Write-WatchdogLog "OK: JustBuy health recovered after grace period"
            return
        }
    } else {
        Write-WatchdogLog "DOWN: port 8080 is not listening"
    }

    Start-JustBuy
    Start-Sleep -Seconds 45

    if (Test-JustBuyHealth) {
        Write-WatchdogLog "RECOVERED: JustBuy health UP"
    } else {
        Write-WatchdogLog "FAILED: JustBuy still unhealthy"
    }
}

Write-WatchdogLog "========== WATCHDOG START =========="

if ($args -contains "-Once") {
    Invoke-WatchdogPass
    exit 0
}

while ($true) {
    Invoke-WatchdogPass
    Start-Sleep -Seconds 60
}
