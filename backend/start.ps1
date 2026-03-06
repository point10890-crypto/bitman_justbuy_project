Set-Location 'C:\bitman_justbuy_project\backend'

# Load .env
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        $key = $matches[1].Trim()
        $val = $matches[2].Trim()
        [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
    }
}

# Start the jar
Start-Process -FilePath 'java' -ArgumentList '-jar', 'build/libs/justbuy-api-1.0.0.jar' -NoNewWindow
