[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path,
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$taskRepository = [System.IO.Path]::GetFullPath($RepositoryRoot)
if (-not (Test-Path -LiteralPath (Join-Path $taskRepository 'backend/gradlew.bat') -PathType Leaf)) {
    throw 'Pass the BitMan repository root with -RepositoryRoot.'
}
$taskJavaCompiler = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { (Get-Command javac -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source }
$taskJavaRuntime = Join-Path (Split-Path -Parent $taskJavaCompiler) 'java.exe'
$taskNodeRuntime = (Get-Command node -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$taskProbeDir = Join-Path ([System.IO.Path]::GetTempPath()) ('bitman-offline-audit-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskProbeDir | Out-Null
$taskClasspathFile = Join-Path $taskProbeDir 'classpath.txt'
$taskInitFile = Join-Path $taskProbeDir 'classpath.init.gradle'
@'
allprojects {
    plugins.withId('java') {
        tasks.register('pipelineAuditClasspath') {
            doLast {
                new File(System.getProperty('pipeline.audit.classpath')).text =
                    (project.sourceSets.main.output + project.configurations.testRuntimeClasspath).asPath
            }
        }
    }
}
'@ | Set-Content -LiteralPath $taskInitFile -Encoding utf8

# Build current sources and export their exact dependency graph without network.
# No Spring application context, configuration files, operational APIs, or real DBs are opened.
Push-Location (Join-Path $taskRepository 'backend')
try {
    & './gradlew.bat' --offline --quiet '-I' $taskInitFile ('-Dpipeline.audit.classpath=' + $taskClasspathFile) classes pipelineAuditClasspath
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE). Dependencies must already be installed." }
} finally { Pop-Location }
$taskClasspath = (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
$taskJavaSources = @('PipelineAuditProbe.java', 'RuntimeDataProbe.java', 'AuditProbe.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
& $taskJavaCompiler -encoding UTF-8 -cp $taskClasspath -d $taskProbeDir $taskJavaSources
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE). Use JDK 21 or newer." }

$taskRuns = @(
    @{ Main = 'PipelineAuditProbe'; Output = 'pipeline-observations.txt'; Pattern = '^OBSERVED ' },
    @{ Main = 'com.bitman.justbuy.service.RuntimeDataProbe'; Output = 'data-observations.txt'; Pattern = '^(P[12]_|OBSERVED=)' },
    @{ Main = 'com.bitman.justbuy.service.AuditProbe'; Output = 'provider-observations.txt'; Pattern = '^(grok_|gemini_|deepseek_|chatgpt_|verifier_|structured_|network=)' }
)
foreach ($taskRun in $taskRuns) {
    $taskOutput = @(& $taskJavaRuntime -cp ($taskProbeDir + ';' + $taskClasspath) $taskRun.Main 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { $taskOutput | Write-Output; throw "$($taskRun.Main) failed ($LASTEXITCODE)." }
    $taskObservation = @($taskOutput | Where-Object { $_ -match $taskRun.Pattern })
    $taskObservation | Set-Content -LiteralPath (Join-Path $taskProbeDir $taskRun.Output) -Encoding utf8
    $taskObservation | Write-Output
}
$taskUiOutput = @(& $taskNodeRuntime (Join-Path $PSScriptRoot 'ui-audit-probe.mjs') '--repo-root' $taskRepository 2>&1 | ForEach-Object { $_.ToString() })
if ($LASTEXITCODE -ne 0) { $taskUiOutput | Write-Output; throw "UI probe failed ($LASTEXITCODE)." }
$taskUiOutput | Set-Content -LiteralPath (Join-Path $taskProbeDir 'ui-observations.txt') -Encoding utf8
$taskUiOutput | Write-Output
Write-Output "Evidence outputs: $taskProbeDir"
Write-Output 'Audit reproduction completed. Passing observations reproduce defects; they do not prove application correctness.'
