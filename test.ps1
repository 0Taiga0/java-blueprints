. (Join-Path $PSScriptRoot 'scripts/common.ps1')
Invoke-ProjectMaven -MavenArguments @('-B', '-ntp', 'test')
