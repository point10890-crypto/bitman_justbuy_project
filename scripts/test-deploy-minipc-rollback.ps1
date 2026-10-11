$ErrorActionPreference = 'Stop'

# Execute the real remote swap body with local fixtures and service/network doubles.
# No production host, process, database or secret is accessed.
$deployFile = Join-Path $PSScriptRoot 'deploy-minipc.ps1'
$tokens = $null; $parseErrors = $null
$deployAst = [System.Management.Automation.Language.Parser]::ParseFile($deployFile, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Deploy script has syntax errors' }
$assignments = $deployAst.FindAll({ param($node) $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$remoteScript' }, $true)
$expression = @($assignments | ForEach-Object { $_.Right.FindAll({ param($node) $node -is [System.Management.Automation.Language.ExpandableStringExpressionAst] }, $true) } | Where-Object { $_.Value -match 'Stop-JustBuy' })[0]
if (-not $expression) { throw 'Remote deployment body not found' }

$fixtureRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('bitman-deploy-tests-' + [Guid]::NewGuid().ToString('N'))))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$failures = 0
try {
  foreach ($case in @('backup-failure', 'replace-failure', 'restart-failure', 'health-failure', 'success')) {
    $script:caseRoot = Join-Path $fixtureRoot $case
    New-Item -ItemType Directory -Path (Join-Path $script:caseRoot 'backend/data') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $script:caseRoot 'backups') -Force | Out-Null
    $RemoteRoot = $script:caseRoot
    $Stamp = $case
    $RemoteUpload = Join-Path $script:caseRoot 'upload.jar'
    $script:jarFile = Join-Path $script:caseRoot 'backend/justbuy-api-1.0.0.jar'
    [IO.File]::WriteAllText($script:jarFile, 'previous-build')
    [IO.File]::WriteAllText($RemoteUpload, 'new-build')
    if ($case -ne 'backup-failure') { [IO.File]::WriteAllText((Join-Path $script:caseRoot 'backend/data/justbuy-db.mv.db'), 'member-data') }
    $script:failureCase = $case
    $script:serviceStopped = $false
    $script:startCount = 0

    function Stop-JustBuy { $script:serviceStopped = $true }
    function Start-JustBuy {
      $script:startCount++
      if ($script:failureCase -eq 'restart-failure' -and $script:startCount -eq 1) { throw 'Simulated task restart failure' }
      $script:serviceStopped = $false
    }
    function Move-WithRetry {
      param([string]$Source, [string]$Destination)
      foreach ($target in @($Source, $Destination)) {
        if (-not [IO.Path]::GetFullPath($target).StartsWith($script:caseRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture move path' }
      }
      if ($script:failureCase -eq 'replace-failure' -and $Source -eq $RemoteUpload) { throw 'Simulated JAR replacement failure' }
      Move-Item -LiteralPath $Source -Destination $Destination -Force
    }
    function Start-Sleep { param([int]$Seconds) }
    function Invoke-WebRequest {
      param([switch]$UseBasicParsing, [string]$Uri, [int]$TimeoutSec)
      if ($script:failureCase -eq 'health-failure' -and [IO.File]::ReadAllText($script:jarFile) -eq 'new-build') { throw 'Simulated unhealthy new build' }
      return @{ StatusCode = 200 }
    }

    $expanded = & ([ScriptBlock]::Create($expression.Extent.Text))
    $remoteAst = [System.Management.Automation.Language.Parser]::ParseInput($expanded, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw 'Expanded remote body has syntax errors' }
    $body = [string]::Join("`n", @($remoteAst.EndBlock.Statements | Where-Object { $_ -isnot [System.Management.Automation.Language.FunctionDefinitionAst] } | ForEach-Object { $_.Extent.Text }))
    $raised = $false
    try { & ([ScriptBlock]::Create($body)) } catch { $raised = $true }

    $expectedJar = if ($case -eq 'success') { 'new-build' } else { 'previous-build' }
    $actualJar = if (Test-Path -LiteralPath $script:jarFile) { [IO.File]::ReadAllText($script:jarFile) } else { 'missing' }
    $passed = ($raised -eq ($case -ne 'success')) -and (-not $script:serviceStopped) -and ($actualJar -eq $expectedJar)
    if ($passed -and $case -ne 'backup-failure') {
      $passed = [IO.File]::ReadAllText((Join-Path $script:caseRoot 'backend/data/justbuy-db.mv.db')) -eq 'member-data'
    }
    if ($passed) { Write-Output "PASS $case" } else {
      $failures++
      Write-Output "FAIL $case : error=$raised stopped=$script:serviceStopped jar=$actualJar"
    }
  }
} finally {
  $resolvedFixture = (Resolve-Path -LiteralPath $fixtureRoot).Path
  if ($resolvedFixture -ne $fixtureRoot -or -not (Split-Path -Leaf $resolvedFixture).StartsWith('bitman-deploy-tests-')) { throw 'Unsafe fixture cleanup path' }
  Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
if ($failures) { throw "$failures rollback regression cases failed" }
Write-Output 'All 5 MiniPC deployment rollback cases passed'
