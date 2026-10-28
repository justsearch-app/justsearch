<#
.SYNOPSIS
    Self-test for Assert-AppSurface's ledger predicate (round 15 fix, tempdoc 817).

.DESCRIPTION
    Assert-AppSurface used to check ONLY `kind == "navigation"` / "targetSurface",
    which is the shape a BACKEND-driven navigation gets (BackendIntentRouterImpl,
    e.g. an agent/MCP-initiated nav). A real GUI-driven click never goes through
    that path -- it's FE-local and bridges into the ledger as
    `kind == "effect"`, `effectKind == "navigate"`, `subject == "<route>"`
    (ActionLedgerClient.ts startEffectIngest -> POST /api/action-ledger/events).
    Round 15 observed exactly that shape and got "no navigation entries in
    action-ledger" on a click that had genuinely succeeded.

    This script exercises the fixed predicate against a captured fixture payload
    (fixtures/action-ledger-sample.json) with NO network calls, via
    Assert-AppSurface's -Entries self-test seam. No Pester dependency (none is
    staged in this repo) -- plain PowerShell 5.1, exits 0 on all-pass, 1 otherwise.

.EXAMPLE
    powershell.exe -NoProfile -File test-assert-app-surface.ps1
#>

$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "JustSearchGui.psm1") -Force

$fixturePath = Join-Path $PSScriptRoot "fixtures\action-ledger-sample.json"
if (-not (Test-Path -LiteralPath $fixturePath)) {
    Write-Host "FAIL: fixture not found at $fixturePath"
    exit 1
}
$fixture = Get-Content -LiteralPath $fixturePath -Raw | ConvertFrom-Json
$allEntries = @($fixture.entries)
if ($allEntries.Count -eq 0) {
    Write-Host "FAIL: fixture loaded but 'entries' is empty -- fixture is broken"
    exit 1
}

$failures = New-Object System.Collections.ArrayList

function Test-ExpectPass {
    param([string]$Name, [scriptblock]$Body)
    try {
        $result = & $Body
        Write-Host "PASS: $Name"
        return $result
    }
    catch {
        Write-Host "FAIL: $Name -- expected NO throw, but got: $($_.Exception.Message)"
        [void]$failures.Add($Name)
        return $null
    }
}

function Test-ExpectThrow {
    param([string]$Name, [scriptblock]$Body, [string]$MessageContains)
    $threw = $false
    $msg = $null
    try {
        & $Body | Out-Null
    }
    catch {
        $threw = $true
        $msg = $_.Exception.Message
    }
    if (-not $threw) {
        Write-Host "FAIL: $Name -- expected a throw, but the call succeeded"
        [void]$failures.Add($Name)
        return
    }
    if ($MessageContains -and ($msg -notlike "*$MessageContains*")) {
        Write-Host "FAIL: $Name -- threw, but message did not contain '$MessageContains': $msg"
        [void]$failures.Add($Name)
        return
    }
    Write-Host "PASS: $Name (threw as expected: $msg)"
}

# --- Case A: real round-15 shape -- most recent nav is an effect/navigate row
# targeting core.search-v3-surface. Must resolve via "subject", not
# "targetSurface" (which this row does not have).
[void](Test-ExpectPass "effect/navigate row resolves the correct surface (bare id)" {
    Assert-AppSurface -ExpectedSurface "core.search-v3-surface" -Entries $allEntries
})

# --- Case B: -ExpectedSurface accepts the full justsearch://surface/<id> form too.
[void](Test-ExpectPass "effect/navigate row resolves the correct surface (full URI form)" {
    Assert-AppSurface -ExpectedSurface "justsearch://surface/core.search-v3-surface" -Entries $allEntries
})

# --- Case C: the MOST RECENT navigation wins, not just "any navigation entry
# somewhere in the ledger" -- core.library-surface is an earlier (legacy-shape)
# nav in this fixture, not the most recent one, so asserting it must fail.
Test-ExpectThrow "wrong expected surface (not the most recent nav) throws" {
    Assert-AppSurface -ExpectedSurface "core.library-surface" -Entries $allEntries
} -MessageContains "does not match expected"

# --- Case D: no navigation-shaped entries at all (only the operation + the
# non-navigate effect) must throw the "no navigation entries" message, not
# silently pass or throw something unrelated.
$noNavEntries = @($allEntries | Where-Object {
        $_.id -eq "op-1" -or $_.id -eq "fe-effect:3"
    })
Test-ExpectThrow "zero navigation entries throws 'no navigation entries'" {
    Assert-AppSurface -ExpectedSurface "core.search-v3-surface" -Entries $noNavEntries
} -MessageContains "no navigation entries"

# --- Case E: backward compatibility -- the OLD kind=="navigation"/targetSurface
# shape (backend-driven navigation, e.g. agent/MCP) must still resolve
# correctly. This is what the predicate checked exclusively before the fix;
# the fix must be additive, not a replacement that breaks this shape.
$legacyOnly = @($allEntries | Where-Object { $_.id -eq "nav-legacy-1" })
[void](Test-ExpectPass "legacy kind=='navigation'/targetSurface row still resolves" {
    Assert-AppSurface -ExpectedSurface "core.library-surface" -Entries $legacyOnly
})

# Intentional legacy-id input: ledger parsing preserves an old address; this does not prove navigation.
[void](Test-ExpectPass "legacy surface token is parsed without rewriting" {
    $old = [pscustomobject]@{kind="effect"; effectKind="navigate"; subject="justsearch://surface/core.unified-chat-surface"}
    if ((Get-AppSurfaceFromLedgerEntry -Entry $old) -ne "core.unified-chat-surface") { throw "legacy parsing changed" }
})

# --- Case F: Get-AppSurfaceFromLedgerEntry unit-level checks (the extraction
# helper itself), including the "not a navigation at all" -> $null case.
$effectEntry = $allEntries | Where-Object { $_.id -eq "fe-effect:2" } | Select-Object -First 1
$legacyEntry = $allEntries | Where-Object { $_.id -eq "nav-legacy-1" } | Select-Object -First 1
$opEntry = $allEntries | Where-Object { $_.id -eq "op-1" } | Select-Object -First 1

if ((Get-AppSurfaceFromLedgerEntry -Entry $effectEntry) -ne "core.search-v3-surface") {
    Write-Host "FAIL: Get-AppSurfaceFromLedgerEntry did not extract the surface from an effect/navigate row"
    [void]$failures.Add("Get-AppSurfaceFromLedgerEntry effect row")
}
else {
    Write-Host "PASS: Get-AppSurfaceFromLedgerEntry effect row"
}

if ((Get-AppSurfaceFromLedgerEntry -Entry $legacyEntry) -ne "core.library-surface") {
    Write-Host "FAIL: Get-AppSurfaceFromLedgerEntry did not extract the surface from a legacy navigation row"
    [void]$failures.Add("Get-AppSurfaceFromLedgerEntry legacy row")
}
else {
    Write-Host "PASS: Get-AppSurfaceFromLedgerEntry legacy row"
}

if ($null -ne (Get-AppSurfaceFromLedgerEntry -Entry $opEntry)) {
    Write-Host "FAIL: Get-AppSurfaceFromLedgerEntry returned non-null for a non-navigation (operation) row"
    [void]$failures.Add("Get-AppSurfaceFromLedgerEntry operation row")
}
else {
    Write-Host "PASS: Get-AppSurfaceFromLedgerEntry operation row returns `$null"
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "RESULT: $($failures.Count) failure(s): $($failures -join ', ')"
    exit 1
}
Write-Host "RESULT: all Assert-AppSurface self-tests passed."
exit 0
