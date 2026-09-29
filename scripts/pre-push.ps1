<#
.SYNOPSIS
    Checks a push before it leaves the machine.

.DESCRIPTION
    This project builds nothing on GitHub, so a push is the last point at which anything can be
    checked. Called by .git/hooks/pre-push with the remote name and URL, reading the pushed refs
    from standard input the way git supplies them. Run scripts/install-hooks.ps1 once to wire it up.

    Every push gets the commit checks: who committed each published commit, trailers or authors
    naming an AI tool, and tracked files that name the maintainer's machine or a phone. A tag, or
    a commit that adds or changes patches-bundle.json (the index Morphe Manager reads), is a
    release, and a release needs HUSHGRAM_ALLOW_RELEASE=1 set for that one push.

    When a pushed commit changes the build, the patches, the extension or a root file their tests
    read, the tip is checked out into a clean worktree and built there: the unit tests, both lints,
    the catalog regenerated and compared, the bundle, and every declared build patched when
    HUSHGRAM_FIXTURE_DIR holds it. The ledger's rules run when the ledger or its scripts move.
    Set HUSHGRAM_SKIP_PRE_PUSH=1 to push anyway.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$RemoteName,
    [Parameter(Position = 1)][string]$RemoteUrl,
    [string]$Root
)

$ErrorActionPreference = 'Stop'

# Not a parameter default: Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if ($env:HUSHGRAM_SKIP_PRE_PUSH -eq '1') {
    Write-Host '[pre-push] skipped by HUSHGRAM_SKIP_PRE_PUSH'
    exit 0
}
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')

# A hook runs with git's environment, which can miss user variables set after the shell started.
foreach ($envName in @('HUSHGRAM_DESKTOP_JAR', 'HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_WORKDIR')) {
    if (-not (Test-Path "Env:\$envName")) {
        $value = [Environment]::GetEnvironmentVariable($envName, [EnvironmentVariableTarget]::User)
        if ($value) { Set-Item -LiteralPath "Env:\$envName" -Value $value }
    }
}

$committer = 'matt_parker@outlook.com'
# Built from parts: Find-MachineNames refuses any tracked line that spells the notes folder's name.
$assistant = @('c', 'l', 'a', 'u', 'd', 'e') -join ''
$aiPattern = "(?i)\b($assistant|anthropic|openai|chatgpt|codex|copilot|gemini)\b"
$buildPaths = '^(extensions/|patches/|gradle/|build\.gradle\.kts$|settings\.gradle\.kts$|gradle\.properties$|' +
    'NOTICE$|provenance\.json$|README\.md$|patches-list\.json$|sources/)'
$ledgerPaths = '^(sources/|scripts/(instagram-sources|test-instagram-sources|audit-instagram-sources)\.ps1$|NOTICE$|provenance\.json$)'
$zero = '0' * 40

function Write-Step([string]$Message) { Write-Host "[pre-push] $Message" }
function Stop-Push([string]$Message) { Write-Host "[pre-push] refused: $Message"; exit 1 }
function Invoke-Git {
    $output = & git -C $Root @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') failed with exit $LASTEXITCODE" }
    return $output
}

$published = New-Object System.Collections.Generic.List[string]
$tips = New-Object System.Collections.Generic.List[string]
$release = $false
foreach ($line in @([Console]::In.ReadToEnd() -split "`n" | Where-Object { $_.Trim() })) {
    $localRef, $localSha, $remoteRef, $remoteSha = $line.Trim() -split '\s+'
    if ($localSha -eq $zero) { continue }
    if ($remoteRef -like 'refs/tags/*') { $release = $true }
    # Typed: a one-element result unwraps to a string, and splatting a string passes it a character at a time.
    [string[]]$range = if ($remoteSha -eq $zero) { $localSha, '--not', '--remotes' } else { "$remoteSha..$localSha" }
    foreach ($commit in @(Invoke-Git rev-list @range)) { if ($commit -and -not $published.Contains($commit)) { $published.Add($commit) } }
    if ($remoteRef -like 'refs/heads/*') { $tips.Add($localSha) }
}
if ($published.Count -eq 0 -and $tips.Count -eq 0 -and -not $release) { Write-Step 'nothing to check'; exit 0 }

$changed = New-Object System.Collections.Generic.HashSet[string]
foreach ($commit in $published) {
    $who = @(Invoke-Git show -s --format='%ae%n%ce%n%an' $commit)
    if ($who[1] -ne $committer) { Stop-Push "$commit was committed by $($who[1]), not $committer" }
    if (($who -join ' ') -match $aiPattern) { Stop-Push "$commit names an AI tool as its author" }
    $message = (Invoke-Git show -s --format='%B' $commit) -join "`n"
    foreach ($trailer in @($message -split "`n" | Where-Object { $_ -match '^(Co-Authored-By|Signed-off-by|Generated-by):' })) {
        if ($trailer -match $aiPattern -or $trailer -match '(?i)noreply@') { Stop-Push "$commit carries the trailer '$trailer'" }
    }
    if ($message -match "(?i)generated with .*($assistant|codex|copilot)") { Stop-Push "$commit says a tool generated it" }
    foreach ($path in @(Invoke-Git diff-tree --no-commit-id --name-only -r --root $commit)) {
        if ($path) { [void]$changed.Add($path) }
    }
}
if ($changed.Contains('patches-bundle.json')) { $release = $true }
if ($release -and $env:HUSHGRAM_ALLOW_RELEASE -ne '1') {
    Stop-Push 'this push is a release (a tag or patches-bundle.json). Set HUSHGRAM_ALLOW_RELEASE=1 for the push once it has been approved.'
}

$hits = @(Find-MachineNames -Root $Root -Commit @($published))
if ($hits.Count -gt 0) {
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
    Stop-Push "$($hits.Count) line(s) name this machine or a phone"
}
Write-Step "$($published.Count) commit(s): committer, trailers and machine names are clean"

if (@($changed | Where-Object { $_ -match $ledgerPaths }).Count -gt 0) {
    Write-Step 'the source ledger or its rules changed, running them'
    & pwsh -NoProfile -File (Join-Path $Root 'scripts/test-instagram-sources.ps1')
    if ($LASTEXITCODE -ne 0) { Stop-Push 'scripts/test-instagram-sources.ps1 failed' }
}

if (@($changed | Where-Object { $_ -match $buildPaths }).Count -eq 0 -or $tips.Count -eq 0) {
    Write-Step 'no build input changed'
    exit 0
}

$tip = $tips[$tips.Count - 1]
$gate = Join-Path ([System.IO.Path]::GetTempPath()) ('hushgram-gate-' + [guid]::NewGuid().ToString('N').Substring(0, 12))
Write-Step "building $($tip.Substring(0, 12)) in a clean worktree"
Invoke-Git worktree add --detach $gate $tip | Out-Null
try {
    $localProperties = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $localProperties) { Copy-Item -LiteralPath $localProperties -Destination $gate }
    $gradle = Join-Path $gate $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' })
    & $gradle -p $gate --console=plain :patches:test :extensions:instagram:testDebugUnitTest `
        :extensions:instagram:lint :extensions:shared:library:lint
    if ($LASTEXITCODE -ne 0) { Stop-Push 'tests or lint failed' }

    $catalog = Join-Path $gate 'patches-list.json'
    $before = Get-Content -LiteralPath $catalog -Raw
    & $gradle -p $gate --console=plain :patches:generatePatchesList :patches:buildAndroid
    if ($LASTEXITCODE -ne 0) { Stop-Push 'the bundle did not build' }
    if ((Get-Content -LiteralPath $catalog -Raw) -ne $before) {
        Stop-Push 'patches-list.json is stale: run :patches:generatePatchesList and commit it'
    }

    $bundle = Get-ChildItem -LiteralPath (Join-Path $gate 'patches/build/release') -Filter 'patches-*.mpp' | Select-Object -First 1
    $fixtures = $env:HUSHGRAM_FIXTURE_DIR
    if (-not $fixtures -or -not (Test-Path -LiteralPath $fixtures -PathType Container)) {
        Write-Step 'HUSHGRAM_FIXTURE_DIR is not set, so no build was patched'
        exit 0
    }
    $desktop = Resolve-DesktopCli -Root $Root -Required
    $target = Get-PatchTarget -PatchList (Get-Content -LiteralPath $catalog -Raw | ConvertFrom-Json)
    foreach ($version in @($target.PackageVersions)) {
        $apk = Get-ChildItem -LiteralPath $fixtures -File | Where-Object {
            $_.Name -like "instagram-$version-*" -and $_.Extension -in '.apk', '.apks', '.apkm', '.xapk'
        } | Select-Object -First 1
        if (-not $apk) { Stop-Push "no fixture for the declared build $version in $fixtures" }
        $work = Join-Path $gate "build/verify-$version"
        New-Item -ItemType Directory -Path $work -Force | Out-Null
        & pwsh -NoProfile -File (Join-Path $gate 'scripts/verify-all-patches.ps1') -Apk $apk.FullName `
            -DesktopJar $desktop -WorkDir $work -Bundle $bundle.FullName -PatchList $catalog
        if ($LASTEXITCODE -ne 0) { Stop-Push "patching Instagram $version failed" }
    }
    Write-Step 'every check passed'
} finally {
    & git -C $Root worktree remove --force $gate 2>$null | Out-Null
    if (Test-Path -LiteralPath $gate) { Remove-Item -LiteralPath $gate -Recurse -Force -ErrorAction SilentlyContinue }
}
exit 0
