<#
.SYNOPSIS
Builds, names, hashes, and verifies the three WeightTrack release APKs.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot),
    [string] $OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-ProjectVersion {
    param([Parameter(Mandatory)][string] $Path)
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*weighttrackVersionName\s*=\s*(\S+)\s*$') {
            return $Matches[1]
        }
    }
    throw 'weighttrackVersionName is missing from gradle.properties.'
}

$rootPath = [IO.Path]::GetFullPath($Root)
$version = Read-ProjectVersion -Path (Join-Path $rootPath 'gradle.properties')
$distRoot = [IO.Path]::GetFullPath((Join-Path $rootPath 'dist'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $distRoot "release-v$version"
}
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
$safeLeaf = (Split-Path -Leaf $outputPath) -eq "release-v$version"
$safeParent = (Split-Path -Parent $outputPath) -eq $distRoot
if (-not $safeLeaf -or -not $safeParent) {
    throw "Release output must be the versioned directory under dist: $distRoot"
}

if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $outputPath | Out-Null

$wrapper = Join-Path $rootPath 'gradlew.bat'
Push-Location $rootPath
try {
    & $wrapper `
        ':core:clean' `
        ':app:clean' `
        ':wear:clean' `
        ':core:assembleRelease' `
        ':app:assemblePlayRelease' `
        ':app:assembleFossRelease' `
        ':wear:assembleRelease' `
        'checkFormFactorVersions' `
        '--no-configuration-cache' `
        '--no-daemon'
    if ($LASTEXITCODE -ne 0) {
        throw "Release build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$artifacts = [ordered]@{
    "WeightTrack-v$version-play-release.apk" =
        (Join-Path $rootPath 'app/build/outputs/apk/play/release/app-play-release.apk')
    "WeightTrack-v$version-foss-release.apk" =
        (Join-Path $rootPath 'app/build/outputs/apk/foss/release/app-foss-release.apk')
    "WeightTrack-v$version-wear-release.apk" =
        (Join-Path $rootPath 'wear/build/outputs/apk/release/wear-release.apk')
}
foreach ($entry in $artifacts.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value -PathType Leaf)) {
        throw "Built APK not found: $($entry.Value)"
    }
    Copy-Item -LiteralPath $entry.Value -Destination (Join-Path $outputPath $entry.Key)
}

$checksumLines = foreach ($name in $artifacts.Keys | Sort-Object) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $outputPath $name) -Algorithm SHA256).Hash
    "$($hash.ToLowerInvariant())  $name"
}
$checksumPath = Join-Path $outputPath 'SHA256SUMS.txt'
[IO.File]::WriteAllLines($checksumPath, $checksumLines, [Text.UTF8Encoding]::new($false))

& (Join-Path $rootPath 'tools/check-release-artifacts.ps1') `
    -Root $rootPath `
    -ArtifactsPath $outputPath `
    -ChecksumFile $checksumPath

Write-Host ''
Write-Host "Prepared release assets: $outputPath"
