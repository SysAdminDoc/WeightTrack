<#
.SYNOPSIS
Proves that Gradle refuses dependency lock drift and altered cached bytes.

.DESCRIPTION
Builds one core test in a disposable Gradle user home, forces a different Guava version without
refreshing locks, then changes one byte in Truth's cached JAR. Each rebuild must fail for the
expected trust reason. The disposable cache is deleted after the check.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Remove-DisposableDirectory {
    param(
        [Parameter(Mandatory)]
        [string] $Path,
        [Parameter(Mandatory)]
        [string] $ExpectedLeafPrefix,
        [Parameter(Mandatory)]
        [string] $AllowedRoot
    )

    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedRoot = [IO.Path]::GetFullPath($AllowedRoot)
    $safeLeaf = (Split-Path -Leaf $resolvedPath).StartsWith(
        $ExpectedLeafPrefix,
        [StringComparison]::OrdinalIgnoreCase
    )
    if (-not $safeLeaf -or -not $resolvedPath.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove an unexpected temporary directory: $resolvedPath"
    }

    for ($attempt = 1; $attempt -le 6; $attempt++) {
        if (-not (Test-Path -LiteralPath $resolvedPath)) {
            return
        }
        try {
            Remove-Item -LiteralPath $resolvedPath -Recurse -Force
            return
        } catch {
            if ($attempt -eq 6) {
                Write-Warning "Could not remove the disposable Gradle cache after six attempts: $resolvedPath"
                return
            }
            Start-Sleep -Milliseconds (500 * $attempt)
        }
    }
}

$rootPath = [IO.Path]::GetFullPath($Root)
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testHome = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot ("weighttrack-gradle-tamper-" + [Guid]::NewGuid().ToString('N')))
)
if (-not $testHome.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a Gradle cache outside the system temporary directory: $testHome"
}

$previousGradleUserHome = $env:GRADLE_USER_HOME
New-Item -ItemType Directory -Path $testHome | Out-Null

try {
    $env:GRADLE_USER_HOME = $testHome
    $wrapper = Join-Path $rootPath 'gradlew.bat'
    $initialLog = Join-Path $testHome 'initial.log'
    $driftLog = Join-Path $testHome 'lock-drift.log'
    $driftInit = Join-Path $testHome 'force-transitive-drift.gradle'
    $tamperedLog = Join-Path $testHome 'tampered.log'

    Push-Location $rootPath
    try {
        & $wrapper `
            ':core:testDebugUnitTest' `
            '--tests' 'com.weighttrack.core.model.WeightPlausibilityTest' `
            '--no-configuration-cache' `
            '--no-daemon' *> $initialLog
        if ($LASTEXITCODE -ne 0) {
            Get-Content -LiteralPath $initialLog | Select-Object -Last 80 | Write-Host
            throw 'The clean dependency-verification fixture did not build.'
        }

        @'
allprojects {
    configurations.configureEach {
        resolutionStrategy.force("com.google.guava:guava:33.3.1-android")
    }
}
'@ | Set-Content -LiteralPath $driftInit -Encoding utf8NoBOM

        & $wrapper `
            ':core:testDebugUnitTest' `
            '--tests' 'com.weighttrack.core.model.WeightPlausibilityTest' `
            '--init-script' $driftInit `
            '--rerun-tasks' `
            '--no-configuration-cache' `
            '--no-daemon' *> $driftLog
        $driftExitCode = $LASTEXITCODE
        $driftOutput = Get-Content -LiteralPath $driftLog -Raw
        if ($driftExitCode -eq 0) {
            throw 'Gradle accepted a changed transitive version without a lock refresh.'
        }
        if (
            $driftOutput -notmatch 'forced / substituted to a different version' -and
            $driftOutput -notmatch 'not part of the dependency lock state'
        ) {
            Get-Content -LiteralPath $driftLog | Select-Object -Last 80 | Write-Host
            throw 'The transitive-version probe failed for a reason other than strict locking.'
        }

        $truthCache = Join-Path $testHome 'caches/modules-2/files-2.1/com.google.truth/truth'
        $artifact = Get-ChildItem -LiteralPath $truthCache -Filter 'truth-*.jar' -File -Recurse |
            Select-Object -First 1
        if ($null -eq $artifact) {
            throw 'The clean build did not cache the Truth JAR used by the core tests.'
        }

        $artifactPath = [IO.Path]::GetFullPath($artifact.FullName)
        if (-not $artifactPath.StartsWith($testHome, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to alter an artifact outside the disposable cache: $artifactPath"
        }
        $bytes = [IO.File]::ReadAllBytes($artifactPath)
        if ($bytes.Length -eq 0) {
            throw "The cached artifact is empty: $artifactPath"
        }
        $last = $bytes.Length - 1
        $bytes[$last] = $bytes[$last] -bxor 0xFF
        [IO.File]::WriteAllBytes($artifactPath, $bytes)

        & $wrapper `
            ':core:testDebugUnitTest' `
            '--tests' 'com.weighttrack.core.model.WeightPlausibilityTest' `
            '--rerun-tasks' `
            '--offline' `
            '--no-configuration-cache' `
            '--no-daemon' *> $tamperedLog
        $tamperedExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $tamperedOutput = Get-Content -LiteralPath $tamperedLog -Raw
    if ($tamperedExitCode -eq 0) {
        throw 'Gradle accepted a cached dependency after its bytes changed.'
    }
    if ($tamperedOutput -notmatch 'Dependency verification failed') {
        Get-Content -LiteralPath $tamperedLog | Select-Object -Last 80 | Write-Host
        throw 'The tampered build failed for a reason other than dependency verification.'
    }

    Write-Host 'Gradle refused the forced transitive-version drift.'
    Write-Host "Gradle refused the altered cached artifact: $($artifact.Name)"
} finally {
    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }

    Remove-DisposableDirectory `
        -Path $testHome `
        -ExpectedLeafPrefix 'weighttrack-gradle-tamper-' `
        -AllowedRoot $temporaryRoot
}
