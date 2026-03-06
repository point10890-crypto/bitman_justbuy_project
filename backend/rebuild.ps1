# Kill justbuy backend process
$procs = Get-CimInstance Win32_Process -Filter "Name='java.exe'"
foreach ($p in $procs) {
    if ($p.CommandLine -like '*justbuy*') {
        Write-Host "Killing justbuy PID: $($p.ProcessId)"
        Stop-Process -Id $p.ProcessId -Force
    }
}
Start-Sleep -Seconds 2

# Build
Set-Location 'C:\bitman_justbuy_project\backend'
.\gradlew.bat clean build -x test
