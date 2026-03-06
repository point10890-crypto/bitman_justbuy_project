try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/auth/me' -Method GET -UseBasicParsing -ErrorAction Stop
    Write-Host "Status: $($r.StatusCode)"
} catch {
    $status = $_.Exception.Response.StatusCode.value__
    if ($status) {
        Write-Host "Status: $status (server is running)"
    } else {
        Write-Host "Error: $($_.Exception.Message)"
    }
}
