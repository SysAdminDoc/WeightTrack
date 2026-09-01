<#
.SYNOPSIS
Fails a benchmark run that went over the checked-in budget.

.DESCRIPTION
Macrobenchmark records numbers; it does not judge them. This reads the JSON a run leaves behind
and compares every metric named in tools/benchmark-budgets.json against its ceiling, so a change
that makes the app slower on a long history fails a command rather than sitting in a report
nobody opens.

Refuses a run that is missing a benchmark or a metric the budget names. A fixture that quietly
stops measuring something reads exactly like a fixture that is passing.
#>
[CmdletBinding()]
param(
    [string] $Root = (Split-Path -Parent $PSScriptRoot),
    [string] $ResultsPath,
    [string] $BudgetFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $BudgetFile) { $BudgetFile = Join-Path $PSScriptRoot 'benchmark-budgets.json' }
if (-not (Test-Path -LiteralPath $BudgetFile)) {
    throw "no budget file at $BudgetFile"
}

if (-not $ResultsPath) {
    $searchRoot = Join-Path $Root 'benchmark/build/outputs'
    if (-not (Test-Path -LiteralPath $searchRoot)) {
        throw "no benchmark output under $searchRoot; run the benchmark first"
    }
    $found = Get-ChildItem -LiteralPath $searchRoot -Recurse -Filter '*benchmarkData.json' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $found) { throw "no benchmarkData.json under $searchRoot; run the benchmark first" }
    $ResultsPath = $found.FullName
}

$budget = Get-Content -LiteralPath $BudgetFile -Raw | ConvertFrom-Json
$results = Get-Content -LiteralPath $ResultsPath -Raw | ConvertFrom-Json

Write-Host "Reading $ResultsPath"
Write-Host "Against  $BudgetFile"
Write-Host ''

$problems = New-Object System.Collections.Generic.List[string]
$checked = 0

foreach ($entry in $budget.benchmarks.PSObject.Properties) {
    $name = $entry.Name
    $measured = $results.benchmarks | Where-Object { $_.name -eq $name }
    if (-not $measured) {
        $problems.Add("the run has no benchmark called '$name'")
        continue
    }

    foreach ($limit in $entry.Value.PSObject.Properties) {
        $metric = $limit.Name
        $ceiling = [double] $limit.Value
        $recorded = $measured.metrics.PSObject.Properties | Where-Object { $_.Name -eq $metric }
        if (-not $recorded) {
            $problems.Add("'$name' recorded no '$metric', so nothing was checked")
            continue
        }

        $value = [double] $recorded.Value.median
        $checked++
        $verdict = if ($value -gt $ceiling) { 'OVER' } else { 'ok' }
        Write-Host ("{0,-28} {1,-26} {2,10:N1} / {3,10:N1}  {4}" -f $name, $metric, $value, $ceiling, $verdict)
        if ($value -gt $ceiling) {
            $problems.Add(
                ("{0} {1} is {2:N1}, over the budget of {3:N1}" -f $name, $metric, $value, $ceiling))
        }
    }
}

Write-Host ''
if ($checked -eq 0) {
    throw 'nothing was checked, which is not the same as nothing being wrong'
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "FAIL: $_" }
    throw "$($problems.Count) benchmark budget(s) exceeded"
}

Write-Host "All $checked benchmark budgets met."
