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

function Assert-LastExitCode {
    param([string]$CommandName)
    if ($LASTEXITCODE -ne 0) {
        throw "$CommandName failed with exit code $LASTEXITCODE"
    }
}

function Invoke-RemotePowerShell {
    param([string]$Script)
    $remoteScript = "C:/Windows/Temp/bitman-remote-$Stamp-$([Guid]::NewGuid().ToString('N')).ps1"
    $localScript = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetFileName($remoteScript))
    Set-Content -LiteralPath $localScript -Value $Script -Encoding UTF8
    try {
        scp $localScript "${Remote}:$remoteScript"
        Assert-LastExitCode "scp remote script"
        ssh $Remote "powershell -NoProfile -ExecutionPolicy Bypass -File $remoteScript"
        Assert-LastExitCode "remote powershell"
    } finally {
        Remove-Item -LiteralPath $localScript -Force -ErrorAction SilentlyContinue
        $oldPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "SilentlyContinue"
            ssh $Remote "powershell -NoProfile -Command `"Remove-Item -LiteralPath '$remoteScript' -Force -ErrorAction SilentlyContinue`"" 2>$null | Out-Null
            $global:LASTEXITCODE = 0
        } finally {
            $ErrorActionPreference = $oldPreference
        }
    }
}

Run-Step "Frontend build" {
    Push-Location $FrontendDir
    try {
        npm run build
        Assert-LastExitCode "npm run build"
    } finally { Pop-Location }
}

Run-Step "Copy frontend dist into Spring static resources" {
    $resolvedStaticDir = [IO.Path]::GetFullPath($StaticDir)
    $expectedStaticDir = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'backend\src\main\resources\static'))
    if ($resolvedStaticDir -ne $expectedStaticDir -or $resolvedStaticDir -eq [IO.Path]::GetPathRoot($resolvedStaticDir)) {
        throw "Unsafe static resource destination: $resolvedStaticDir"
    }
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
        try {
            .\gradlew.bat test
            Assert-LastExitCode "gradlew test"
        } finally { Pop-Location }
    }
}

Run-Step "Backend bootJar" {
    Push-Location $BackendDir
    try {
        .\gradlew.bat bootJar -x test
        Assert-LastExitCode "gradlew bootJar"
    } finally { Pop-Location }
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
    Assert-LastExitCode "scp JAR"
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
function Set-LongRunningTaskSettings {
  param([string]`$TaskName)
  try {
    `$taskSettings = @{
      AllowStartIfOnBatteries = `$true
      DontStopIfGoingOnBatteries = `$true
      StartWhenAvailable = `$true
      MultipleInstances = 'IgnoreNew'
      ExecutionTimeLimit = New-TimeSpan -Seconds 0
      RestartCount = 999
      RestartInterval = New-TimeSpan -Minutes 1
    }
    `$settings = New-ScheduledTaskSettingsSet @taskSettings
    Set-ScheduledTask -TaskName `$TaskName -Settings `$settings | Out-Null
  } catch {
    Write-Warning ("Unable to update scheduled task settings for " + `$TaskName + ": " + `$_.Exception.Message)
  }
}
function Move-WithRetry {
  param(
    [string]`$Source,
    [string]`$Destination
  )
  `$rootPrefix = [IO.Path]::GetFullPath(`$root).TrimEnd('\') + '\'
  `$sourcePath = [IO.Path]::GetFullPath(`$Source)
  `$destinationPath = [IO.Path]::GetFullPath(`$Destination)
  if ((-not `$sourcePath.StartsWith(`$rootPrefix, [StringComparison]::OrdinalIgnoreCase) -and `$sourcePath -ne [IO.Path]::GetFullPath(`$upload)) -or
      -not `$destinationPath.StartsWith(`$rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe MiniPC JAR move path.'
  }
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
  if (`$LASTEXITCODE -ne 0) { throw 'Unable to create MiniPC API scheduled task.' }
  Set-LongRunningTaskSettings `$taskName
  Set-LongRunningTaskSettings 'JustBuy-Watchdog'
  schtasks /Run /TN `$taskName | Out-Null
  if (`$LASTEXITCODE -ne 0) { throw 'Unable to start MiniPC API scheduled task.' }
}

`$db = "`$root\backend\data\justbuy-db.mv.db"
`$dbBackup = "`$root\backups\predeploy-justbuy-db-`$stamp.mv.db"
try {
  Stop-JustBuy
  Copy-Item -LiteralPath `$db -Destination `$dbBackup -Force
  if (Test-Path -LiteralPath `$jar) { Move-WithRetry `$jar `$backupJar }
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
  if (-not `$ok) { throw 'New MiniPC build failed health check.' }
} catch {
  `$deployFailure = `$_
  try {
    Stop-JustBuy
    `$failedJar = "`$root\backend\justbuy-api-1.0.0.jar.failed-`$stamp"
    # A backup-copy failure leaves the original JAR in place. A failed upload
    # can leave only the backup. Restore it when present, then restart either way.
    if (Test-Path -LiteralPath `$backupJar) {
      if (Test-Path -LiteralPath `$jar) { Move-WithRetry `$jar `$failedJar }
      Move-WithRetry `$backupJar `$jar
    }
    if (-not (Test-Path -LiteralPath `$jar)) { throw 'No MiniPC JAR available for recovery.' }
    Start-JustBuy
    `$restored = `$false
    for (`$i = 0; `$i -lt 24; `$i++) {
      Start-Sleep -Seconds 5
      try {
        `$res = Invoke-WebRequest -UseBasicParsing `$health -TimeoutSec 3
        if (`$res.StatusCode -eq 200) { `$restored = `$true; break }
      } catch {}
    }
    if (-not `$restored) { throw 'Previous MiniPC build failed recovery health check.' }
  } catch {
    throw ("MiniPC deployment failed: " + `$deployFailure.Exception.Message + "; recovery failed: " + `$_.Exception.Message)
  }
  throw ("MiniPC deployment failed; previous service recovered: " + `$deployFailure.Exception.Message)
}

Write-Host "MiniPC deployment OK"
"@
    Invoke-RemotePowerShell $remoteScript
}

Write-Host "MiniPC deploy complete. Operational DB and secrets were preserved." -ForegroundColor Green
