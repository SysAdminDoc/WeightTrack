<#
.SYNOPSIS
    Checks the built release APKs against the two deadlines Play has set.

.DESCRIPTION
    Two things will stop a release being accepted and neither shows up in a build log.

    Wear OS apps have to ship 64-bit code from 2026-09-15. WeightTrack has no native code of its
    own, but a dependency can bring some in without anybody noticing, so this looks at what is
    actually in the archive rather than at what the build files ask for.

    From 2027-02-01 every app has to support 16 KB memory pages, which for an APK means its
    shared libraries are aligned to 16 KB and the archive is zipaligned with -P 16. An APK with
    no shared libraries in it passes on both counts, and saying so out loud is the point: it is
    the difference between having checked and having assumed.

    Run it after `gradlew :app:assemblePlayRelease :app:assembleFossRelease :wear:assembleRelease`.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

function Find-Zipalign {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path $sdk)) { return $null }
    Get-ChildItem -Path (Join-Path $sdk 'build-tools') -Filter 'zipalign.exe' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

$apks = @(
    Get-ChildItem -Path $Root -Recurse -Filter '*.apk' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\build\\outputs\\apk\\.*release.*\.apk$' }
)

if ($apks.Count -eq 0) {
    Write-Error 'No release APKs found. Build them first.'
}

$zipalign = Find-Zipalign
if (-not $zipalign) {
    Write-Warning 'zipalign was not found in the Android SDK; the 16 KB check was skipped.'
}

$problems = @()
Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($apk in $apks) {
    Write-Host "== $($apk.Name)"

    $archive = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $abis = $archive.Entries |
            Where-Object { $_.FullName -like 'lib/*/*' } |
            ForEach-Object { ($_.FullName -split '/')[1] } |
            Sort-Object -Unique
    } finally {
        $archive.Dispose()
    }

    if ($abis.Count -eq 0) {
        Write-Host '   no shared libraries, so 64-bit and 16 KB alignment are not in question'
    } else {
        Write-Host "   architectures: $($abis -join ', ')"
        $sixtyFour = $abis | Where-Object { $_ -in @('arm64-v8a', 'x86_64') }
        if ($sixtyFour.Count -eq 0) {
            $problems += "$($apk.Name) ships only 32-bit code ($($abis -join ', '))"
        }
    }

    if ($zipalign) {
        & $zipalign -c -P 16 -v 4 $apk.FullName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            $problems += "$($apk.Name) is not aligned for 16 KB pages"
        } else {
            Write-Host '   aligned for 16 KB pages'
        }
    }
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "PROBLEM: $_" }
    exit 1
}

Write-Host ''
Write-Host "All $($apks.Count) release APKs pass."
