<#
.SYNOPSIS
Regenerates Gradle dependency locks and SHA-256 verification metadata.

.DESCRIPTION
Uses a new Gradle user home on every run so the review includes artifacts needed by a cold build.
Existing trust files are restored if Gradle fails, and dependency verification stays enabled while
the new metadata is written.
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
$trustHome = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot ("weighttrack-gradle-trust-" + [Guid]::NewGuid().ToString('N')))
)
if (-not $trustHome.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a Gradle cache outside the system temporary directory: $trustHome"
}

$trustFiles = @(
    'gradle/verification-metadata.xml',
    'settings-gradle.lockfile',
    'app/buildscript-gradle.lockfile',
    'app/gradle.lockfile',
    'core/buildscript-gradle.lockfile',
    'core/gradle.lockfile',
    'wear/buildscript-gradle.lockfile',
    'wear/gradle.lockfile',
    'benchmark/buildscript-gradle.lockfile',
    'benchmark/gradle.lockfile',
    'buildSrc/gradle.lockfile'
)
$backupRoot = Join-Path $trustHome 'state-backup'
$logPath = Join-Path $trustHome 'generation.log'
$previousGradleUserHome = $env:GRADLE_USER_HOME
$completed = $false
$wrapper = Join-Path $rootPath 'gradlew.bat'

New-Item -ItemType Directory -Path $backupRoot | Out-Null
$backups = foreach ($relative in $trustFiles) {
    $source = Join-Path $rootPath $relative
    if (Test-Path -LiteralPath $source) {
        $backup = Join-Path $backupRoot ([Guid]::NewGuid().ToString('N'))
        Copy-Item -LiteralPath $source -Destination $backup
        [pscustomobject]@{ Source = $source; Backup = $backup }
    }
}

try {
    $env:GRADLE_USER_HOME = $trustHome
    Push-Location $rootPath
    try {
        & $wrapper `
            ':app:dependencies' `
            ':core:dependencies' `
            ':wear:dependencies' `
            ':benchmark:dependencies' `
            ':core:testDebugUnitTest' `
            ':app:testPlayDebugUnitTest' `
            ':app:testFossDebugUnitTest' `
            ':wear:testDebugUnitTest' `
            ':core:assembleAndroidTest' `
            ':app:assembleAndroidTest' `
            ':wear:assembleAndroidTest' `
            ':core:assembleRelease' `
            ':app:assemblePlayRelease' `
            ':app:assembleFossRelease' `
            ':wear:assembleRelease' `
            ':benchmark:assembleBenchmark' `
            'checkFormFactorVersions' `
            '--write-locks' `
            '--write-verification-metadata' 'sha256' `
            '--no-configuration-cache' `
            '--no-daemon' *> $logPath
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($exitCode -ne 0) {
        Get-Content -LiteralPath $logPath | Select-Object -Last 80 | Write-Host
        throw "Gradle dependency trust generation failed with exit code $exitCode."
    }

    Get-Content -LiteralPath $logPath | Select-Object -Last 20 | Write-Host
    Write-Host 'Dependency locks and SHA-256 verification metadata were regenerated.'
    Write-Host 'Review every trust-file diff before committing.'
    $completed = $true
} finally {
    if (-not $completed) {
        foreach ($relative in $trustFiles) {
            $current = Join-Path $rootPath $relative
            if (Test-Path -LiteralPath $current) {
                Remove-Item -LiteralPath $current -Force
            }
        }
        foreach ($entry in $backups) {
            Copy-Item -LiteralPath $entry.Backup -Destination $entry.Source
        }
    }

    if ($null -eq $previousGradleUserHome) {
        Remove-Item Env:GRADLE_USER_HOME -ErrorAction SilentlyContinue
    } else {
        $env:GRADLE_USER_HOME = $previousGradleUserHome
    }

    Remove-DisposableDirectory `
        -Path $trustHome `
        -ExpectedLeafPrefix 'weighttrack-gradle-trust-' `
        -AllowedRoot $temporaryRoot
}
