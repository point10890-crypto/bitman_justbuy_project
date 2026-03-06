# Test register
Write-Host "=== Register admin user ==="
$body = '{"name":"admin","email":"admin@bitman.com","password":"admin1234"}'

try {
    $r = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/register' -Method POST -Body $body -ContentType 'application/json; charset=utf-8'
    Write-Host ("Token: " + $r.token.Substring(0, 20) + "...")
    Write-Host ("User: " + $r.user.name + " / " + $r.user.role + " / " + $r.user.subscription)
    $adminToken = $r.token
} catch {
    Write-Host ("Register failed: " + $_.Exception.Message)
    exit 1
}

# Test register demo user
Write-Host ""
Write-Host "=== Register demo user ==="
$body2 = '{"name":"demo","email":"demo@bitman.com","password":"demo1234"}'

try {
    $r2 = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/register' -Method POST -Body $body2 -ContentType 'application/json; charset=utf-8'
    Write-Host ("User: " + $r2.user.name + " / " + $r2.user.role + " / " + $r2.user.subscription)
    $demoToken = $r2.token
} catch {
    Write-Host ("Register failed: " + $_.Exception.Message)
}

# Subscribe demo user
Write-Host ""
Write-Host "=== Subscribe demo user ==="
$subBody = '{"depositorName":"TestUser"}'
try {
    $demoHeaders = @{ Authorization = ("Bearer " + $demoToken) }
    $r3 = Invoke-RestMethod -Uri 'http://localhost:8080/api/subscription/apply' -Method POST -Body $subBody -ContentType 'application/json; charset=utf-8' -Headers $demoHeaders
    Write-Host ("Subscription: " + $r3.subscription + " / depositor: " + $r3.depositorName)
} catch {
    Write-Host ("Subscribe failed: " + $_.Exception.Message)
}

# Test admin endpoints
Write-Host ""
Write-Host "=== Admin: Pending subscriptions ==="
$adminHeaders = @{ Authorization = ("Bearer " + $adminToken) }
try {
    $pending = Invoke-RestMethod -Uri 'http://localhost:8080/api/admin/subscriptions/pending' -Method GET -Headers $adminHeaders
    Write-Host ("Pending count: " + $pending.Count)
    foreach ($u in $pending) {
        Write-Host ("  - " + $u.name + " (" + $u.email + ") depositor=" + $u.depositorName)
    }
} catch {
    Write-Host ("Pending failed: " + $_.Exception.Message)
}

# Test all users endpoint (NEW)
Write-Host ""
Write-Host "=== Admin: All users ==="
try {
    $users = Invoke-RestMethod -Uri 'http://localhost:8080/api/admin/users' -Method GET -Headers $adminHeaders
    Write-Host ("Total users: " + $users.Count)
    foreach ($u in $users) {
        Write-Host ("  - " + $u.name + " (" + $u.email + ") role=" + $u.role + " sub=" + $u.subscription)
    }
} catch {
    Write-Host ("All users failed: " + $_.Exception.Message)
}
