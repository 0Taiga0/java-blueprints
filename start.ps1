[CmdletBinding()]
param([int]$Port = 8080)
. (Join-Path $PSScriptRoot 'scripts/common.ps1')
$javaExecutable = Get-JavaExecutable
$jarPath = Join-Path $projectRoot 'backend/target/java-blueprints-0.13.0.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    Write-Host 'First run: building the app...'
    & (Join-Path $projectRoot 'build.ps1')
}
Write-Host "Open http://127.0.0.1:$Port in your browser. Press Ctrl+C to stop."
& $javaExecutable '-Dfile.encoding=UTF-8' -jar $jarPath "--server.port=$Port"
exit $LASTEXITCODE
