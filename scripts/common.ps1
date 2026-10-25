$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $projectRoot '.runtime'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$localConfig = Join-Path $runtimeDirectory 'local-tools.json'
$localTools = if (Test-Path -LiteralPath $localConfig) { Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json } else { $null }

function Get-JavaExecutable {
    $configuredHome = if ($localTools -and $localTools.javaHome -and (Test-Path -LiteralPath $localTools.javaHome)) { $localTools.javaHome } else { $env:JAVA_HOME }
    if ($configuredHome -and (Test-Path -LiteralPath (Join-Path $configuredHome 'bin/java.exe'))) {
        $env:JAVA_HOME = $configuredHome
        $executable = Join-Path $configuredHome 'bin/java.exe'
    } else {
        $command = Get-Command java -ErrorAction SilentlyContinue
        if (-not $command) { throw 'Install JDK 21 and set JAVA_HOME. See README.md.' }
        $executable = $command.Source
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $executable)
    }
    # Java prints its version to stderr; capture both streams without treating it as failure.
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $versionText = (& $executable -version 2>&1 | Out-String)
    $ErrorActionPreference = $oldPreference
    if ($versionText -notmatch 'version "([0-9]+)' -or [int]$Matches[1] -lt 21) {
        throw 'JDK 21 or newer is required. Current Java is too old. Set JAVA_HOME to your JDK 21 directory.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { throw 'Full JDK 21+ with javac is required for compilation and tests. Set JAVA_HOME to the JDK directory.' }
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    return $executable
}

function Get-NodeExecutable {
    if ($localTools -and $localTools.node -and (Test-Path -LiteralPath $localTools.node)) { $executable = $localTools.node }
    else {
        $command = Get-Command node -ErrorAction SilentlyContinue
        if (-not $command) { throw 'Install Node.js 22.12+ (or 24 LTS). See README.md.' }
        $executable = $command.Source
    }
    $version = (& $executable --version).TrimStart('v')
    if ([version]$version -lt [version]'22.12.0') { throw 'Node.js 22.12+ is required.' }
    $env:PATH = (Split-Path -Parent $executable) + ';' + $env:PATH
    return $executable
}

function Get-NpmCli {
    if ($localTools -and $localTools.npmCli -and (Test-Path -LiteralPath $localTools.npmCli)) { return $localTools.npmCli }
    $npmCommand = Get-Command npm.cmd -ErrorAction Stop
    $npmCli = Join-Path (Split-Path -Parent $npmCommand.Source) 'node_modules/npm/bin/npm-cli.js'
    if (-not (Test-Path -LiteralPath $npmCli)) { throw 'Cannot locate npm-cli.js. Reinstall Node.js.' }
    return $npmCli
}

function Invoke-ProjectMaven {
    param([string[]]$MavenArguments)
    $null = Get-JavaExecutable
    $maven = if ($localTools -and $localTools.maven -and (Test-Path -LiteralPath $localTools.maven)) { $localTools.maven } else { Join-Path $projectRoot 'backend/mvnw.cmd' }
    $repository = if ($localTools -and $localTools.mavenRepository) { $localTools.mavenRepository } else { Join-Path $runtimeDirectory 'maven-repository' }
    $env:MAVEN_USER_HOME = Join-Path $runtimeDirectory 'maven-home'
    Push-Location (Join-Path $projectRoot 'backend')
    try {
        & $maven "-Dmaven.repo.local=$repository" @MavenArguments
        if ($LASTEXITCODE -ne 0) { throw 'Maven failed. Check the messages above.' }
    } finally { Pop-Location }
}
