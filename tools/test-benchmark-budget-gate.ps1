<#
.SYNOPSIS
Proves the benchmark budget gate can fail.

.DESCRIPTION
A check nobody has watched fail is a check nobody knows works. This runs
check-benchmark-budgets.ps1 against the real results four times: once as it stands, which has to
pass, and three times against a doctored budget or result file, each of which has to fail.

Run it after changing either the checker or the budgets:
    pwsh -File tools/test-benchmark-budget-gate.ps1
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot),
    [string] $ResultsPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$checker = Join-Path $PSScriptRoot 'check-benchmark-budgets.ps1'
$budgets = Join-Path $PSScriptRoot 'benchmark-budgets.json'

if (-not $ResultsPath) {
    $searchRoot = Join-Path $Root 'benchmark/build/outputs'
    $found = if (Test-Path -LiteralPath $searchRoot) {
        Get-ChildItem -LiteralPath $searchRoot -Recurse -Filter '*benchmarkData.json' |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    } else { $null }
    if (-not $found) {
        throw 'no benchmark results to test the gate against; run the benchmark first'
    }
    $ResultsPath = $found.FullName
}

$scratch = Join-Path ([IO.Path]::GetTempPath()) ("wt-budget-gate-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null

function Invoke-Checker {
    param([string] $Budget, [string] $Results)
    & pwsh -NoProfile -File $checker -Root $Root -BudgetFile $Budget -ResultsPath $Results 2>&1 |
        Out-String | Out-Null
    return $LASTEXITCODE
}

$failures = New-Object System.Collections.Generic.List[string]

try {
    # 1. As it stands. Anything else means the budgets do not describe this device.
    if ((Invoke-Checker -Budget $budgets -Results $ResultsPath) -ne 0) {
        $failures.Add('the real budgets do not pass against the real results')
    }

    $real = Get-Content -LiteralPath $budgets -Raw | ConvertFrom-Json

    # 2. Every ceiling at zero. Nothing can be under it, so the gate must fail.
    $impossible = Get-Content -LiteralPath $budgets -Raw | ConvertFrom-Json
    foreach ($entry in $impossible.benchmarks.PSObject.Properties) {
        foreach ($limit in $entry.Value.PSObject.Properties) { $limit.Value = 0 }
    }
    $path = Join-Path $scratch 'impossible.json'
    $impossible | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path
    if ((Invoke-Checker -Budget $path -Results $ResultsPath) -eq 0) {
        $failures.Add('a budget of zero was accepted')
    }

    # 3. A benchmark the run does not contain. A fixture that stopped measuring something must
    #    not read as a fixture that is passing.
    $missing = Get-Content -LiteralPath $budgets -Raw | ConvertFrom-Json
    $missing.benchmarks | Add-Member -NotePropertyName 'benchmarkThatDoesNotExist' `
        -NotePropertyValue ([pscustomobject]@{ timeToInitialDisplayMs = 1 })
    $path = Join-Path $scratch 'missing-benchmark.json'
    $missing | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path
    if ((Invoke-Checker -Budget $path -Results $ResultsPath) -eq 0) {
        $failures.Add('a budget naming a benchmark the run does not have was accepted')
    }

    # 4. A metric the run does not record, for the same reason.
    $metric = Get-Content -LiteralPath $budgets -Raw | ConvertFrom-Json
    $first = ($metric.benchmarks.PSObject.Properties | Select-Object -First 1)
    $first.Value | Add-Member -NotePropertyName 'metricThatDoesNotExist' -NotePropertyValue 1
    $path = Join-Path $scratch 'missing-metric.json'
    $metric | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path
    if ((Invoke-Checker -Budget $path -Results $ResultsPath) -eq 0) {
        $failures.Add('a budget naming a metric the run does not record was accepted')
    }
} finally {
    Remove-Item -LiteralPath $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL: $_" }
    throw "$($failures.Count) of the gate's own checks did not behave"
}

Write-Host 'The benchmark budget gate passes what it should and refuses what it should.'
