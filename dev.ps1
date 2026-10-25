param([Parameter(Mandatory=$true)][ValidateSet('frontend','backend')][string]$Part)
. (Join-Path $PSScriptRoot 'scripts/common.ps1')
if ($Part -eq 'backend') {
    Invoke-ProjectMaven -MavenArguments @('-B', '-ntp', 'spring-boot:run')
} else {
    $nodeExecutable = Get-NodeExecutable
    $npmCli = Get-NpmCli
    Push-Location (Join-Path $projectRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & $nodeExecutable $npmCli ci --no-audit --no-fund --cache (Join-Path $runtimeDirectory 'npm-cache')
            if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
        }
        & $nodeExecutable $npmCli run dev
    } finally { Pop-Location }
}
