$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($conn) {
    $pid = $conn.OwningProcess
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$pid"
    Write-Host "Port 8080 is LISTENING"
    Write-Host "PID: $pid"
    $cmd = $proc.CommandLine
    if ($cmd.Length -gt 300) { $cmd = $cmd.Substring(0, 300) + "..." }
    Write-Host "CMD: $cmd"
} else {
    Write-Host "Nothing listening on 8080"
}
