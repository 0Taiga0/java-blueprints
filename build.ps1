param([switch]$SkipTests)
. (Join-Path $PSScriptRoot 'scripts/common.ps1')
$nodeExecutable = Get-NodeExecutable
$npmCli = Get-NpmCli
$null = Get-JavaExecutable
Push-Location (Join-Path $projectRoot 'frontend')
try {
    Write-Host 'Installing frontend dependencies...'
    & $nodeExecutable $npmCli ci --no-audit --no-fund --cache (Join-Path $runtimeDirectory 'npm-cache')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
    & $nodeExecutable $npmCli run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
$mavenArguments = @('-B', '-ntp', 'clean', 'package')
if ($SkipTests) { $mavenArguments += '-DskipTests' }
Invoke-ProjectMaven -MavenArguments $mavenArguments
Write-Host 'Ready. Start the app with: .\start.ps1'
