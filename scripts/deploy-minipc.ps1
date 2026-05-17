param(
    [string]$MiniPcHost = "192.168.55.103",
    [string]$MiniPcUser = "dynas",
    [string]$ProjectRoot = "C:\bitman_justbuy_project",
    [string]$RemoteRoot = "C:\bitman_justbuy",
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"

$FrontendDir = Join-Path $ProjectRoot "frontend"
$BackendDir = Join-Path $ProjectRoot "backend"
$StaticDir = Join-Path $BackendDir "src\main\resources\static"
$DistDir = Join-Path $FrontendDir "dist"
$JarPath = Join-Path $BackendDir "build\libs\justbuy-api-1.0.0.jar"
$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$Remote = "$MiniPcUser@$MiniPcHost"
$RemoteUpload = "C:/Windows/Temp/justbuy-api-$Stamp.jar"

function Run-Step {
    param([string]$Name, [scriptblock]$Body)
    Write-Host "=== $Name ===" -ForegroundColor Cyan
    & $Body
    Write-Host ""
}

function Invoke-RemotePowerShell {
    param([string]$Script)
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Script))
    ssh $Remote "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand $encoded"
}

Run-Step "Frontend build" {
    Push-Location $FrontendDir
    try { npm run build } finally { Pop-Location }
}

Run-Step "Copy frontend dist into Spring static resources" {
    if (-not (Test-Path $DistDir)) {
        throw "Frontend dist directory not found: $DistDir"
    }
    if (-not (Test-Path $StaticDir)) {
        New-Item -ItemType Directory -Path $StaticDir -Force | Out-Null
    }
    Get-ChildItem -LiteralPath $StaticDir -Force | Remove-Item -Recurse -Force
    Copy-Item -Path (Join-Path $DistDir "*") -Destination $StaticDir -Recurse -Force
}

if (-not $SkipTests) {
    Run-Step "Backend tests" {
        Push-Location $BackendDir
        try { .\gradlew.bat test } finally { Pop-Location }
    }
}

Run-Step "Backend bootJar" {
    Push-Location $BackendDir
    try { .\gradlew.bat bootJar -x test } finally { Pop-Location }
    if (-not (Test-Path $JarPath)) {
        throw "JAR build output not found: $JarPath"
    }
}

Run-Step "MiniPC preflight" {
    $preflight = @"
`$ErrorActionPreference = 'Stop'
`$root = '$RemoteRoot'
`$required = @(
  "`$root\backend\.env",
  "`$root\backend\data\justbuy-db.mv.db",
  "`$root\backend\data\.jwt-secret",
  "`$root\scripts\start-springboot.bat"
)
foreach (`$path in `$required) {
  if (-not (Test-Path `$path)) { throw "Missing required MiniPC file: `$path" }
}
if (-not (Test-Path "`$root\backups")) { New-Item -ItemType Directory -Path "`$root\backups" -Force | Out-Null }
Write-Host "MiniPC preflight OK"
"@
    Invoke-RemotePowerShell $preflight
}

Run-Step "Upload JAR to MiniPC temp" {
    scp $JarPath "${Remote}:$RemoteUpload"
}

Run-Step "Swap JAR and restart MiniPC service" {
    $remoteScript = @"
`$ErrorActionPreference = 'Stop'
`$root = '$RemoteRoot'
`$stamp = '$Stamp'
`$jar = "`$root\backend\justbuy-api-1.0.0.jar"
`$backupJar = "`$root\backend\justbuy-api-1.0.0.jar.bak-`$stamp"
`$upload = '$RemoteUpload'
`$health = 'http://localhost:8080/api/health'
function Get-JustBuyProcesses {
  Get-CimInstance Win32_Process |
    Where-Object { `$_.CommandLine -like '*justbuy-api-1.0.0.jar*' -or (`$_.Name -eq 'wscript.exe' -and `$_.CommandLine -like '*bitman_justbuy*autostart.vbs*') }
}
function Stop-JustBuy {
  schtasks /End /TN BitMan-JustBuy-Api 2>`$null | Out-Null
  for (`$i = 0; `$i -lt 10; `$i++) {
    `$procs = @(Get-JustBuyProcesses)
    if (`$procs.Count -eq 0) { return }
    foreach (`$proc in `$procs) {
      Stop-Process -Id `$proc.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
  }
  `$remaining = @(Get-JustBuyProcesses)
  if (`$remaining.Count -gt 0) {
    throw "Unable to stop MiniPC JustBuy process before deploy."
  }
}
function Move-WithRetry {
  param(
    [string]`$Source,
    [string]`$Destination
  )
  `$lastError = `$null
  for (`$i = 0; `$i -lt 8; `$i++) {
    try {
      Move-Item -LiteralPath `$Source -Destination `$Destination -Force
      return
    } catch {
      `$lastError = `$_
      Stop-JustBuy
      Start-Sleep -Seconds 2
    }
  }
  throw `$lastError
}
function Start-JustBuy {
  `$taskName = 'BitMan-JustBuy-Api'
  `$taskCommand = 'C:\Windows\System32\cmd.exe /c C:\bitman_justbuy\scripts\start-springboot.bat'
  schtasks /Create /TN `$taskName /TR `$taskCommand /SC ONSTART /RU SYSTEM /RL HIGHEST /F | Out-Null
  schtasks /Run /TN `$taskName | Out-Null
}

Stop-JustBuy

`$db = "`$root\backend\data\justbuy-db.mv.db"
`$dbBackup = "`$root\backups\predeploy-justbuy-db-`$stamp.mv.db"
Copy-Item `$db `$dbBackup -Force

if (Test-Path `$jar) {
  Move-WithRetry `$jar `$backupJar
}
Move-WithRetry `$upload `$jar

Start-JustBuy

`$ok = `$false
for (`$i = 0; `$i -lt 24; `$i++) {
  Start-Sleep -Seconds 5
  try {
    `$res = Invoke-WebRequest -UseBasicParsing `$health -TimeoutSec 3
    if (`$res.StatusCode -eq 200) { `$ok = `$true; break }
  } catch {}
}

if (-not `$ok) {
  Stop-JustBuy
  `$failedJar = "`$root\backend\justbuy-api-1.0.0.jar.failed-`$stamp"
  if (Test-Path `$jar) { Move-WithRetry `$jar `$failedJar }
  if (Test-Path `$backupJar) {
    Move-WithRetry `$backupJar `$jar
    Start-JustBuy
  }
  throw "MiniPC deployment failed health check. Previous JAR restored."
}

Write-Host "MiniPC deployment OK"
"@
    Invoke-RemotePowerShell $remoteScript
}

Write-Host "MiniPC deploy complete. Operational DB and secrets were preserved." -ForegroundColor Green
