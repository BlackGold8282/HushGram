<#
.SYNOPSIS
    Exercise canonical production identity boundaries using independent repositories.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    try { & $Action | Out-Null } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "Unexpected refusal: $($_.Exception.Message)"
    }
    throw "Expected refusal: $Pattern"
}
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('hushgram-identity-' + [guid]::NewGuid().ToString('N'))
try {
    $policy = [IO.File]::ReadAllText((Join-Path $Root 'scripts/canonical-build-inputs.txt'))
    $minimal = [ordered]@{}
    foreach ($line in $policy -split '\r?\n' | Where-Object { $_ -notmatch '^\s*(#|$)' }) {
        $category, $kind, $path = $line -split ' ', 3
        $name = switch ($kind) { file { $path }; tree { "$path/input.txt" }; main { "$path/component/src/main/input.txt" }; module { "$path/component/build.gradle.kts" } }
        $minimal[$name] = "$category $path`n"
    }
    $minimal['scripts/canonical-build-inputs.txt'] = $policy
    $minimal['gradle.properties'] = "version=0.0.4`n"
    $minimal['README.md'] = "documentation`n"
    $minimal['patches/src/test/example.kt'] = "test only`n"
    $minimal['.gitignore'] = "build/`n.gradle/`nlocal.properties`n"
    $roots = @((Join-Path $scratch 'first'), (Join-Path $scratch 'different location'))
    foreach ($repo in $roots) {
        New-Item -ItemType Directory -Path $repo -Force | Out-Null
        foreach ($name in $minimal.Keys) {
            $file = Join-Path $repo $name
            New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
            [IO.File]::WriteAllText($file, $minimal[$name], [Text.UTF8Encoding]::new($false))
        }
        Invoke-RepoGit -Root $repo -Arguments @('init', '--quiet') | Out-Null
        Invoke-RepoGit -Root $repo -Arguments @('config', 'core.autocrlf', 'false') | Out-Null
        Invoke-RepoGit -Root $repo -Arguments @('add', '-A') | Out-Null
    }
    $before = Get-CanonicalBuildIdentity -Root $roots[0]
    Assert-True ($before.id -ceq (Get-CanonicalBuildIdentity -Root $roots[1]).id) 'Machine paths changed the production identity.'
    foreach ($name in @('README.md', 'patches/src/test/example.kt', 'scripts/dependency-graphs.init.gradle',
            'patches/src/test/fixture/build.gradle.kts', 'extensions/instagram/src/test/fixture.pro',
            'local.properties', 'build/generated/identity.json')) {
        $file = Join-Path $roots[0] $name
        New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force | Out-Null
        [IO.File]::WriteAllText($file, 'different output or unrelated input')
        if ($name -notin @('local.properties', 'build/generated/identity.json')) {
            Invoke-RepoGit -Root $roots[0] -Arguments @('add', $name) | Out-Null
        }
        Assert-True ((Get-CanonicalBuildIdentity -Root $roots[0]).id -ceq $before.id) "Unrelated $name changed the production identity."
    }
    foreach ($category in @('source', 'catalog', 'toolchain')) {
        $name = switch ($category) {
            source { 'patches/src/main/input.txt' }; catalog { 'patches-list.json' }; toolchain { 'gradle.properties' }
        }
        $file = Join-Path $roots[0] $name
        $bytes = [IO.File]::ReadAllBytes($file)
        try {
            [IO.File]::AppendAllText($file, 'same-version change')
            $after = Get-CanonicalBuildIdentity -Root $roots[0]
            Assert-True ($after.id -cne $before.id -and $after.($category + 'Sha256') -cne $before.($category + 'Sha256')) "$category bytes did not change the identity."
            Remove-Item -LiteralPath $file -Force
            Assert-Throws { Get-CanonicalBuildIdentity -Root $roots[0] } '*missing or linked*'
        } finally { [IO.File]::WriteAllBytes($file, $bytes) }
    }
    $production = Join-Path $roots[0] 'extensions/instagram/src/main/second.java'
    [IO.File]::WriteAllText($production, 'new production input')
    Assert-Throws { Get-CanonicalBuildIdentity -Root $roots[0] } '*untracked canonical*'
    Invoke-RepoGit -Root $roots[0] -Arguments @('add', $production) | Out-Null
    Assert-True ((Get-CanonicalBuildIdentity -Root $roots[0]).id -cne $before.id) 'A newly tracked production input disappeared.'
    Invoke-RepoGit -Root $roots[0] -Arguments @('rm', '--cached', $production) | Out-Null
    Remove-Item -LiteralPath $production -Force
    $fixturePolicy = Join-Path $roots[0] 'scripts/canonical-build-inputs.txt'
    foreach ($bad in @('source tree ../outside', 'toolchain tree build', 'unknown file NOTICE')) {
        [IO.File]::WriteAllText($fixturePolicy, $policy + "`n$bad`n")
        Assert-Throws { Get-CanonicalBuildIdentity -Root $roots[0] } '*canonical input boundary*'
    }
    [IO.File]::WriteAllText($fixturePolicy, ($policy -replace '(?m)^catalog file patches-list.json\r?\n', ''))
    Assert-Throws { Get-CanonicalBuildIdentity -Root $roots[0] } '*omits catalog*'
    [IO.File]::WriteAllText($fixturePolicy, $policy)
    foreach ($field in @('sourceSha256', 'catalogSha256', 'toolchainSha256', 'id', 'schemaVersion')) {
        $copy = $before | ConvertTo-Json | ConvertFrom-Json
        $copy.PSObject.Properties.Remove($field)
        Assert-Throws { Assert-CanonicalBuildIdentity $copy } '*canonical build identity*'
    }
    $changed = $before | ConvertTo-Json | ConvertFrom-Json
    $changed.sourceSha256 = '0' * 64
    Assert-Throws { Assert-CanonicalBuildIdentity $changed } '*does not match*'
    $changed = $before | ConvertTo-Json | ConvertFrom-Json
    Add-Member -InputObject $changed -NotePropertyName account_id -NotePropertyValue 'not a build input'
    Assert-Throws { Assert-CanonicalBuildIdentity $changed } '*unsupported fields*'

    # Exercise the real Gradle producer, not a stand-in that can agree with a broken reader.
    $producerRoot = $roots[1]
    $fixtureBuild = @'
apply from: 'scripts/build-inputs.gradle'
tasks.register('mutateCanonicalInput') {
    doLast {
        def target = project.findProperty('mutateInput')
        if (!target) throw new GradleException('No fixture mutation selected.')
        file(target).append('changed during the build\n', 'UTF-8')
    }
}
tasks.named('writeDependencyAuditInputs') { mustRunAfter('mutateCanonicalInput') }
tasks.named('writeCanonicalBuildIdentity') { mustRunAfter('mutateCanonicalInput') }
tasks.register('assembleFixture') {
    dependsOn('writeCanonicalBuildIdentity')
    doLast {
        file(project.findProperty('mutateAfterIdentity')).append('changed after snapshot\n', 'UTF-8')
        rootProject.ext.verifyCanonicalBuildIdentity.call()
    }
}
'@
    $fixturePatches = @'
tasks.register('generatePatchesList') {
    doLast {
        if (project.hasProperty('regenerateCatalog')) rootProject.file('patches-list.json').append('new catalog\n', 'UTF-8')
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $producerRoot 'build.gradle'), $fixtureBuild, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $producerRoot 'settings.gradle'), "include ':patches'`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $producerRoot 'patches/build.gradle'), $fixturePatches, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $producerRoot 'scripts/canonical-build-inputs.txt'), $policy + "`ntoolchain file build.gradle`n", [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $Root 'scripts/build-inputs.gradle') -Destination (Join-Path $producerRoot 'scripts/build-inputs.gradle')
    Invoke-RepoGit -Root $producerRoot -Arguments @('add', '-A') | Out-Null
    $launcher = Join-Path $Root $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
    function Invoke-IdentityProducer([string[]]$Arguments, [string]$Refusal) {
        $said = @(& $launcher '-p' $producerRoot '--console=plain' '--no-configuration-cache' @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
        if ($Refusal) {
            Assert-True ($exitCode -ne 0 -and ($said -join "`n") -like $Refusal) "The real producer accepted a mid-build mutation or refused it for the wrong reason: $($said -join "`n")"
        } else {
            Assert-True ($exitCode -eq 0) "The real identity producer failed: $($said -join "`n")"
            $produced = Get-Content -LiteralPath (Join-Path $producerRoot 'build/reports/build-identity/canonical-inputs.json') -Raw | ConvertFrom-Json
            Assert-CanonicalBuildIdentity $produced
            Assert-True ($produced.id -ceq (Get-CanonicalBuildIdentity -Root $producerRoot).id) 'The independent reader disagreed with the real Gradle producer.'
            return $produced
        }
    }
    $produced = Invoke-IdentityProducer -Arguments @('writeCanonicalBuildIdentity')
    foreach ($name in @('patches/src/main/input.txt', 'gradle.properties')) {
        $file = Join-Path $producerRoot $name
        $bytes = [IO.File]::ReadAllBytes($file)
        try {
            Invoke-IdentityProducer -Arguments @('mutateCanonicalInput', 'writeCanonicalBuildIdentity', "-PmutateInput=$name") -Refusal '*Canonical production source or toolchain inputs changed during the build*'
        } finally { [IO.File]::WriteAllBytes($file, $bytes) }
    }
    foreach ($name in @('patches/src/main/input.txt', 'gradle.properties', 'patches-list.json')) {
        $file = Join-Path $producerRoot $name
        $bytes = [IO.File]::ReadAllBytes($file)
        try {
            Invoke-IdentityProducer -Arguments @('assembleFixture', "-PmutateAfterIdentity=$name") -Refusal '*Canonical production inputs changed after the identity snapshot*'
        } finally { [IO.File]::WriteAllBytes($file, $bytes) }
    }
    $regenerated = Invoke-IdentityProducer -Arguments @(':patches:generatePatchesList', 'writeCanonicalBuildIdentity', '-PregenerateCatalog=true')
    Assert-True ($regenerated.id -cne $produced.id -and $regenerated.catalogSha256 -cne $produced.catalogSha256 -and
        $regenerated.sourceSha256 -ceq $produced.sourceSha256 -and $regenerated.toolchainSha256 -ceq $produced.toolchainSha256) 'Catalog regeneration was omitted or changed unrelated canonical categories.'
    Write-Host '[build-identity] canonical input, exclusion, substitution and missing-input contracts passed'
} finally {
    Remove-GeneratedPath -Path $scratch -Root ([IO.Path]::GetTempPath())
}
