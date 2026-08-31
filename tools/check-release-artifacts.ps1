<#
.SYNOPSIS
Checks prepared release APKs, their signing identity, and SHA-256 checksums.

.DESCRIPTION
Requires the exact Play, FOSS, and Wear release set for the project version. Each APK must carry
the expected package, version, version-code band, signing certificate, supported native ABIs, and
16 KB zip alignment, and a v2 or v3 signature. SHA256SUMS.txt must name every APK exactly once
and contain no extra entry. SECURITY.md must publish the enforced fingerprint and every retired
one, because a fingerprint nobody can read is a fingerprint nobody can check against.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot),
    [string] $ArtifactsPath,
    [string] $ChecksumFile,
    [string] $ExpectedPackageName,
    [string] $ExpectedVersionName,
    [int] $ExpectedPhoneVersionCode = -1,
    [int] $ExpectedWearVersionCode = -1,
    [string] $ExpectedCertificateSha256
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-ProjectProperties {
    param([Parameter(Mandatory)][string] $Path)

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#!][^=]*?)\s*=\s*(.*?)\s*$') {
            $values[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }
    return $values
}

function Find-AndroidTool {
    param([Parameter(Mandatory)][string] $Name)

    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $sdk)) {
        throw "Android SDK not found: $sdk"
    }

    # Sorted by parsed version, not by name. "9.0.0" sorts above "36.0.0" as text, and an
    # ancient zipalign has no -P flag, so a correct release fails the alignment check on any
    # machine that still has a single-digit build-tools installed.
    $tool = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter $Name -File -Recurse |
        ForEach-Object {
            $folder = Split-Path -Leaf (Split-Path -Parent $_.FullName)
            $parsed = $null
            if (-not [Version]::TryParse($folder, [ref] $parsed)) {
                $grandparent = Split-Path -Leaf (Split-Path -Parent (Split-Path -Parent $_.FullName))
                [void][Version]::TryParse($grandparent, [ref] $parsed)
            }
            [pscustomobject]@{ Path = $_.FullName; Version = $parsed }
        } |
        Where-Object { $_.Version } |
        Sort-Object Version -Descending |
        Select-Object -First 1 -ExpandProperty Path
    if (-not $tool) {
        throw "$Name was not found in the Android SDK."
    }
    return $tool
}

function Normalize-Fingerprint {
    param([Parameter(Mandatory)][string] $Value)
    return ($Value -replace '[^0-9a-fA-F]', '').ToLowerInvariant()
}

$rootPath = [IO.Path]::GetFullPath($Root)
$properties = Read-ProjectProperties -Path (Join-Path $rootPath 'gradle.properties')
$trust = Get-Content -LiteralPath (Join-Path $rootPath 'tools/release-trust.json') -Raw |
    ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($ExpectedPackageName)) {
    $ExpectedPackageName = [string]$trust.packageName
}
if ([string]::IsNullOrWhiteSpace($ExpectedVersionName)) {
    $ExpectedVersionName = [string]$properties.weighttrackVersionName
}
if ($ExpectedPhoneVersionCode -lt 0) {
    $ExpectedPhoneVersionCode = [int]$properties.weighttrackVersionCode
}
if ($ExpectedWearVersionCode -lt 0) {
    $ExpectedWearVersionCode =
        [int]$properties.weighttrackWearVersionBand + $ExpectedPhoneVersionCode
}
if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
    $ExpectedCertificateSha256 = [string]$trust.channels.github.certificateSha256
}
$ExpectedCertificateSha256 = Normalize-Fingerprint -Value $ExpectedCertificateSha256
if ($ExpectedCertificateSha256.Length -ne 64) {
    throw 'The expected signing-certificate SHA-256 must contain 64 hexadecimal characters.'
}

if ([string]::IsNullOrWhiteSpace($ArtifactsPath)) {
    $ArtifactsPath = Join-Path $rootPath "dist/release-v$ExpectedVersionName"
}
$artifactsRoot = [IO.Path]::GetFullPath($ArtifactsPath)
if (-not (Test-Path -LiteralPath $artifactsRoot -PathType Container)) {
    throw "Prepared release directory not found: $artifactsRoot"
}
if ([string]::IsNullOrWhiteSpace($ChecksumFile)) {
    $ChecksumFile = Join-Path $artifactsRoot 'SHA256SUMS.txt'
}
$checksumPath = [IO.Path]::GetFullPath($ChecksumFile)

$specs = @(
    [pscustomobject]@{
        Name = "WeightTrack-v$ExpectedVersionName-play-release.apk"
        VersionCode = $ExpectedPhoneVersionCode
    },
    [pscustomobject]@{
        Name = "WeightTrack-v$ExpectedVersionName-foss-release.apk"
        VersionCode = $ExpectedPhoneVersionCode
    },
    [pscustomobject]@{
        Name = "WeightTrack-v$ExpectedVersionName-wear-release.apk"
        VersionCode = $ExpectedWearVersionCode
    }
)
$expectedNames = @($specs.Name)
$problems = [Collections.Generic.List[string]]::new()

$apks = @(Get-ChildItem -LiteralPath $artifactsRoot -Filter '*.apk' -File)
foreach ($extra in $apks | Where-Object { $_.Name -notin $expectedNames }) {
    $problems.Add("unexpected release APK: $($extra.Name)")
}
foreach ($spec in $specs) {
    if (-not (Test-Path -LiteralPath (Join-Path $artifactsRoot $spec.Name) -PathType Leaf)) {
        $problems.Add("missing release APK: $($spec.Name)")
    }
}

$checksumEntries = @{}
if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    $problems.Add("missing checksum file: $checksumPath")
} else {
    foreach ($line in Get-Content -LiteralPath $checksumPath) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') {
            $problems.Add("invalid SHA256SUMS.txt line: $line")
            continue
        }
        $name = $Matches[2]
        if ([IO.Path]::GetFileName($name) -ne $name) {
            $problems.Add("checksum entry must contain a file name only: $name")
            continue
        }
        if ($checksumEntries.ContainsKey($name)) {
            $problems.Add("duplicate checksum entry: $name")
            continue
        }
        $checksumEntries[$name] = $Matches[1].ToLowerInvariant()
    }
    foreach ($extra in $checksumEntries.Keys | Where-Object { $_ -notin $expectedNames }) {
        $problems.Add("unexpected checksum entry: $extra")
    }
    foreach ($name in $expectedNames) {
        if (-not $checksumEntries.ContainsKey($name)) {
            $problems.Add("missing checksum entry: $name")
        }
    }
}

$aapt2 = Find-AndroidTool -Name 'aapt2.exe'
$apksigner = Find-AndroidTool -Name 'apksigner.bat'
$zipalign = Find-AndroidTool -Name 'zipalign.exe'
Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($spec in $specs) {
    $apkPath = Join-Path $artifactsRoot $spec.Name
    if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) { continue }
    Write-Host "== $($spec.Name)"

    if ($checksumEntries.ContainsKey($spec.Name)) {
        $actualHash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $checksumEntries[$spec.Name]) {
            $problems.Add("checksum mismatch for $($spec.Name)")
        } else {
            Write-Host '   SHA-256 matches SHA256SUMS.txt'
        }
    }

    $badging = @(& $aapt2 dump badging $apkPath 2>&1)
    $badgingExit = $LASTEXITCODE
    if ($badgingExit -ne 0) {
        $problems.Add("could not read the manifest from $($spec.Name)")
    } else {
        $packageLine = $badging | Where-Object { "$_" -like 'package:*' } | Select-Object -First 1
        $identityPattern = "name='([^']+)' versionCode='([^']+)' versionName='([^']+)'"
        if ("$packageLine" -notmatch $identityPattern) {
            $problems.Add("could not parse the package identity from $($spec.Name)")
        } else {
            $packageName = $Matches[1]
            $versionCode = [int]$Matches[2]
            $versionName = $Matches[3]
            if ($packageName -ne $ExpectedPackageName) {
                $problems.Add("wrong package in $($spec.Name): $packageName")
            }
            if ($versionCode -ne $spec.VersionCode) {
                $problems.Add("wrong version code in $($spec.Name): $versionCode")
            }
            if ($versionName -ne $ExpectedVersionName) {
                $problems.Add("wrong version name in $($spec.Name): $versionName")
            }
            Write-Host "   package=$packageName version=$versionName code=$versionCode"
        }
    }

    $signatureOutput = @(& $apksigner verify --verbose --print-certs $apkPath 2>&1)
    $signatureExit = $LASTEXITCODE
    $signatureText = $signatureOutput -join [Environment]::NewLine
    if ($signatureExit -ne 0) {
        $problems.Add("APK signature verification failed for $($spec.Name)")
    } else {
        # The published guide tells people to check that a modern signature scheme verified.
        # apksigner's exit code alone passes a v1-only APK carrying the right certificate.
        $modern = [regex]::Matches(
            $signatureText,
            '(?im)^Verified using v([2-9])\s+scheme[^:]*:\s*true\s*$'
        )
        if ($modern.Count -eq 0) {
            $problems.Add("$($spec.Name) is not signed with the v2 or v3 scheme")
        }
        $fingerprints = @(
            [regex]::Matches(
                $signatureText,
                '(?im)certificate SHA-256 digest:\s*([0-9a-f]{64})'
            ) | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() }
        )
        if ($fingerprints.Count -ne 1) {
            $problems.Add("expected one signing certificate in $($spec.Name), found $($fingerprints.Count)")
        } elseif ($fingerprints[0] -ne $ExpectedCertificateSha256) {
            $problems.Add("wrong signing certificate in $($spec.Name): $($fingerprints[0])")
        } else {
            Write-Host "   signer=$($fingerprints[0])"
        }
    }

    & $zipalign -c -P 16 -v 4 $apkPath *> $null
    if ($LASTEXITCODE -ne 0) {
        $problems.Add("$($spec.Name) is not aligned for 16 KB pages")
    } else {
        Write-Host '   aligned for 16 KB pages'
    }

    $archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        $abis = @(
            $archive.Entries |
                Where-Object { $_.FullName -like 'lib/*/*' } |
                ForEach-Object { ($_.FullName -split '/')[1] } |
                Sort-Object -Unique
        )
    } finally {
        $archive.Dispose()
    }
    if ($abis.Count -eq 0) {
        Write-Host '   no shared libraries'
    } elseif (@($abis | Where-Object { $_ -in @('arm64-v8a', 'x86_64') }).Count -eq 0) {
        $problems.Add("$($spec.Name) ships no 64-bit native code")
    } else {
        Write-Host "   native ABIs=$($abis -join ', ')"
    }
}

# The permanent guide is what a person actually reads before installing, so a fingerprint that
# only exists in the trust file is a fingerprint nobody can check against.
$guide = Join-Path $rootPath 'SECURITY.md'
if (-not (Test-Path -LiteralPath $guide -PathType Leaf)) {
    $problems.Add('SECURITY.md is missing, so no fingerprint is published anywhere a person reads.')
} else {
    $published = (Get-Content -LiteralPath $guide -Raw)
    $documented = @([regex]::Matches($published, '[0-9a-fA-F]{64}') |
        ForEach-Object { $_.Value.ToLowerInvariant() })
    if ($ExpectedCertificateSha256 -notin $documented) {
        $problems.Add('SECURITY.md does not publish the signing fingerprint the gate enforces.')
    }
    foreach ($retired in @($trust.retiredCertificates)) {
        $value = Normalize-Fingerprint -Value ([string]$retired.certificateSha256)
        if ($value -and $value -notin $documented) {
            $problems.Add("SECURITY.md does not record the retired fingerprint $value.")
        }
    }
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "PROBLEM: $_" }
    throw "$($problems.Count) release artifact check(s) failed."
}

Write-Host ''
Write-Host "All $($specs.Count) release APKs and SHA256SUMS.txt pass."
