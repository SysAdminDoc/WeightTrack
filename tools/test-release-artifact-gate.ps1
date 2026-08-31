<#
.SYNOPSIS
Proves the release gate rejects package, signer, version, version-code, checksum and
undocumented-fingerprint faults.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot),
    [string] $ArtifactsPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$rootPath = [IO.Path]::GetFullPath($Root)
$properties = Get-Content -LiteralPath (Join-Path $rootPath 'gradle.properties')
$versionLine = $properties | Where-Object { $_ -match '^weighttrackVersionName=' } | Select-Object -First 1
if (-not $versionLine) { throw 'weighttrackVersionName is missing from gradle.properties.' }
$version = ($versionLine -split '=', 2)[1].Trim()
if ([string]::IsNullOrWhiteSpace($ArtifactsPath)) {
    $ArtifactsPath = Join-Path $rootPath "dist/release-v$version"
}
$artifactRoot = [IO.Path]::GetFullPath($ArtifactsPath)
$checksumPath = Join-Path $artifactRoot 'SHA256SUMS.txt'
$checker = Join-Path $rootPath 'tools/check-release-artifacts.ps1'
$pwsh = (Get-Process -Id $PID).Path
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = [IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot ("weighttrack-release-gate-" + [Guid]::NewGuid().ToString('N')))
)
if (-not $testRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a test fixture outside the system temporary directory: $testRoot"
}
New-Item -ItemType Directory -Path $testRoot | Out-Null

function Invoke-Gate {
    param(
        [string[]] $ExtraArguments = @(),
        [string] $Checksums = $checksumPath,
        [string] $Artifacts = $artifactRoot,
        [string] $Root = $rootPath
    )

    $arguments = @(
        '-NoProfile',
        '-File', $checker,
        '-Root', $Root,
        '-ArtifactsPath', $Artifacts,
        '-ChecksumFile', $Checksums
    ) + $ExtraArguments
    $output = @(& $pwsh @arguments 2>&1)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($output -join [Environment]::NewLine)
    }
}

function Assert-Rejected {
    param(
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][pscustomobject] $Result,
        [Parameter(Mandatory)][string] $Pattern
    )

    if ($Result.ExitCode -eq 0) {
        throw "The release gate accepted the $Name mismatch."
    }
    if ($Result.Output -notmatch $Pattern) {
        Write-Host $Result.Output
        throw "The $Name probe failed for an unrelated reason."
    }
}

try {
    $valid = Invoke-Gate
    if ($valid.ExitCode -ne 0) {
        Write-Host $valid.Output
        throw 'The valid release fixture did not pass the gate.'
    }

    Assert-Rejected `
        -Name 'package' `
        -Result (Invoke-Gate -ExtraArguments @('-ExpectedPackageName', 'invalid.weighttrack')) `
        -Pattern 'wrong package'
    Assert-Rejected `
        -Name 'signer' `
        -Result (Invoke-Gate -ExtraArguments @('-ExpectedCertificateSha256', ('0' * 64))) `
        -Pattern 'wrong signing certificate'
    # The expected version also names the files, so asking for 0.0.0 against the real directory
    # only proves the gate noticed three missing names. Rename a copy so every other check passes
    # and the manifest version is the one thing left to disagree.
    $renamedRoot = Join-Path $testRoot 'renamed'
    New-Item -ItemType Directory -Path $renamedRoot | Out-Null
    $renamedLines = foreach ($apk in Get-ChildItem -LiteralPath $artifactRoot -Filter '*.apk' -File) {
        $renamed = $apk.Name.Replace("-v$version-", '-v0.0.0-')
        if ($renamed -eq $apk.Name) { throw "Could not rename $($apk.Name) for the version probe." }
        Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $renamedRoot $renamed)
        "$((Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $renamed"
    }
    $renamedChecksum = Join-Path $renamedRoot 'SHA256SUMS.txt'
    [IO.File]::WriteAllLines($renamedChecksum, @($renamedLines), [Text.UTF8Encoding]::new($false))
    Assert-Rejected `
        -Name 'version' `
        -Result (Invoke-Gate `
            -Artifacts $renamedRoot `
            -Checksums $renamedChecksum `
            -ExtraArguments @('-ExpectedVersionName', '0.0.0')) `
        -Pattern 'wrong version name'

    Assert-Rejected `
        -Name 'version code' `
        -Result (Invoke-Gate -ExtraArguments @('-ExpectedPhoneVersionCode', '999999')) `
        -Pattern 'wrong version code'

    $tamperedChecksum = Join-Path $testRoot 'SHA256SUMS.txt'
    $lines = @(Get-Content -LiteralPath $checksumPath)
    if ($lines.Count -eq 0) { throw 'The valid checksum fixture is empty.' }
    $first = $lines[0]
    $replacement = if ($first[0] -eq '0') { '1' } else { '0' }
    $lines[0] = $replacement + $first.Substring(1)
    [IO.File]::WriteAllLines($tamperedChecksum, $lines, [Text.UTF8Encoding]::new($false))
    Assert-Rejected `
        -Name 'checksum' `
        -Result (Invoke-Gate -Checksums $tamperedChecksum) `
        -Pattern 'checksum mismatch'

    # A fingerprint that only exists in the trust file is one nobody installing can check
    # against, so the gate has to notice when the published guide stops naming it.
    $undocumentedRoot = Join-Path $testRoot 'undocumented'
    New-Item -ItemType Directory -Path (Join-Path $undocumentedRoot 'tools') | Out-Null
    Copy-Item `
        -LiteralPath (Join-Path $rootPath 'gradle.properties') `
        -Destination (Join-Path $undocumentedRoot 'gradle.properties')
    Copy-Item `
        -LiteralPath (Join-Path $rootPath 'tools/release-trust.json') `
        -Destination (Join-Path $undocumentedRoot 'tools/release-trust.json')
    $stripped = (Get-Content -LiteralPath (Join-Path $rootPath 'SECURITY.md') -Raw) `
        -replace '[0-9a-fA-F]{64}', 'not-a-fingerprint'
    [IO.File]::WriteAllText((Join-Path $undocumentedRoot 'SECURITY.md'), $stripped)
    Assert-Rejected `
        -Name 'undocumented fingerprint' `
        -Result (Invoke-Gate -Root $undocumentedRoot) `
        -Pattern 'SECURITY\.md does not publish'

    Write-Host 'Release gate rejected package, signer, version, version-code, checksum and undocumented-fingerprint faults.'
} finally {
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    $safeLeaf = (Split-Path -Leaf $resolvedTestRoot) -like 'weighttrack-release-gate-*'
    if (
        $safeLeaf -and
        $resolvedTestRoot.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedTestRoot)
    ) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
