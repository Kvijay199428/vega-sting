# ============================================================================
# VEGA STING version engine
#
# Drives version.properties for the two update channels. Invoked by
# release.bat / debug.bat -- no other tool should write this file.
#
#   next   -Channel stable [-Respin | -Minor | -Major]
#   next   -Channel dev    [-Base x.y.z]
#       -> prints the version to build (does NOT touch version.properties)
#
#   commit -Channel stable|dev -VersionName <name> -VersionCode <n>
#       -> advances version.properties after a verified build
#
# Separating "next" from "commit" means a failed or abandoned build never
# consumes a version code.
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('next', 'commit', 'show')][string]$Mode,
    [ValidateSet('stable', 'dev')][string]$Channel = 'stable',
    [switch]$Respin,
    [switch]$Minor,
    [switch]$Major,
    [string]$Base,
    [string]$VersionName,
    [int]$VersionCode
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$PropsPath = Join-Path $Root 'version.properties'

function Fail([string]$Message) {
    [Console]::Error.WriteLine("ERROR: $Message")
    exit 1
}

function BumpVersion([string]$Version, [ValidateSet('patch', 'minor', 'major')][string]$Part) {
    if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
        Fail "Malformed base version '$Version' (expected x.y.z)."
    }
    $maj = [int]$Matches[1]; $min = [int]$Matches[2]; $pat = [int]$Matches[3]
    switch ($Part) {
        'major' { return "$($maj + 1).0.0" }
        'minor' { return "$maj.$($min + 1).0" }
        default { return "$maj.$min.$($pat + 1)" }
    }
}

function CompareVersion([string]$A, [string]$B) {
    $pa = $A.Split('.') | ForEach-Object { [int]$_ }
    $pb = $B.Split('.') | ForEach-Object { [int]$_ }
    for ($i = 0; $i -lt 3; $i++) {
        if ($pa[$i] -ne $pb[$i]) { return $pa[$i] - $pb[$i] }
    }
    return 0
}

function ReadProps {
    if (-not (Test-Path $PropsPath)) { Fail "version.properties not found at $PropsPath" }
    $map = @{}
    foreach ($line in Get-Content -LiteralPath $PropsPath) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $trimmed.Substring(0, $idx).Trim()
        $val = $trimmed.Substring($idx + 1).Trim()
        $map[$key] = $val
    }
    return $map
}

function WriteProps([hashtable]$Map) {
    # Only the machine-managed keys are rewritten; the explanatory header is
    # regenerated so the file stays self-documenting. Written as UTF-8 WITHOUT a
    # byte-order mark: java.util.Properties.load() does not strip a BOM, which
    # would turn the first comment line into a junk key.
    $content = @(
        '# ============================================================================'
        '# VEGA STING version metadata -- single source of truth for the build.'
        '#'
        '# Edited automatically by release.bat / debug.bat. Do not hand-edit.'
        '# ============================================================================'
        ''
        '# versionCode for the NEXT STABLE release. It only moves when a stable'
        '# build is committed.'
        "next.versionCode=$($Map['next.versionCode'])"
        ''
        '# A DEBUG build reuses the latest stable release versionCode'
        '# (next.versionCode - 1) so a device can switch STABLE <-> DEBUG without'
        '# Android rejecting it as a downgrade. Debug builds are told apart by'
        '# their versionName (x.y.z-dev.N).'
        ''
        '# Oldest installed versionCode still eligible for OTA updates.'
        "minSupported.versionCode=$($Map['minSupported.versionCode'])"
        ''
        '# ---- STABLE channel (production) ----'
        '# baseVersion is the versionName of the last stable release (plain x.y.z);'
        '# the next --stable build patch-bumps it. No suffix is used, so the tag'
        '# stays parseable by updaters already installed on user devices.'
        "release.baseVersion=$($Map['release.baseVersion'])"
        "release.defaultChannel=$($Map['release.defaultChannel'])"
        ''
        '# ---- DEBUG channel (development / prereleases) ----'
        '# The next --dev build becomes "<baseVersion>-dev.<buildNumber + 1>".'
        "debug.baseVersion=$($Map['debug.baseVersion'])"
        "debug.buildNumber=$($Map['debug.buildNumber'])"
        "debug.versionName=$($Map['debug.versionName'])"
        "debug.defaultChannel=$($Map['debug.defaultChannel'])"
        ''
    )
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($PropsPath, [string[]]$content, $utf8NoBom)
}

$props = ReadProps
foreach ($required in @('next.versionCode', 'minSupported.versionCode', 'release.baseVersion',
                        'debug.baseVersion', 'debug.buildNumber')) {
    if (-not $props.ContainsKey($required)) { Fail "version.properties is missing '$required'." }
}

switch ($Mode) {
    'show' {
        # Read-only diagnostics: mirrors the previous `version.ps1 show` helper.
        Write-Output "next.versionCode=$($props['next.versionCode'])"
        Write-Output "minSupported.versionCode=$($props['minSupported.versionCode'])"
        Write-Output "release.baseVersion=$($props['release.baseVersion'])"
        Write-Output "debug.baseVersion=$($props['debug.baseVersion'])"
        Write-Output "debug.buildNumber=$($props['debug.buildNumber'])"
        Write-Output "debug.versionName=$($props['debug.versionName'])"
        break
    }
    'next' {
        if ($Channel -eq 'stable') {
            # STABLE names are plain x.y.z (no -stable.N suffix). A respin is
            # therefore just another patch bump: once a stable version has been
            # installed it can never be re-offered, so a distinct versionName
            # (and versionCode) is always required.
            $base = $props['release.baseVersion']
            if ($Major) { $base = BumpVersion $base 'major' }
            elseif ($Minor) { $base = BumpVersion $base 'minor' }
            else { $base = BumpVersion $base 'patch' }
            $name = $base
            $code = [int]$props['next.versionCode']
        }
        else {
            if ($Base) {
                if ($Base -notmatch '^\d+\.\d+\.\d+$') { Fail "Bad -Base '$Base' (expected x.y.z)." }
                $base = $Base
                $counter = 1
            }
            else {
                $base = $props['debug.baseVersion']
                $counter = [int]$props['debug.buildNumber'] + 1
            }
            $name = "$base-dev.$counter"
            # A DEBUG build reuses the versionCode of the latest STABLE release
            # (next.versionCode - 1) and does NOT advance it. Android permits an
            # equal-code reinstall but never a lower one, so keeping DEBUG level
            # with STABLE is what makes STABLE <-> DEBUG switching installable in
            # both directions. next.versionCode therefore only moves on a stable.
            $code = [int]$props['next.versionCode'] - 1
            if ($code -lt 1) { $code = 1 }
        }
        Write-Output "versionName=$name"
        Write-Output "versionCode=$code"
        Write-Output "minSupportedVersionCode=$($props['minSupported.versionCode'])"
        break
    }
    'commit' {
        if (-not $VersionName) { Fail 'commit requires -VersionName.' }
        if ($VersionCode -le 0) { Fail 'commit requires -VersionCode.' }

        if ($Channel -eq 'stable') {
            if ($VersionName -notmatch '^(\d+\.\d+\.\d+)$') {
                Fail "Stable version name '$VersionName' does not match x.y.z."
            }
            $stableBase = $Matches[1]
            $props['release.baseVersion'] = $stableBase

            # Only a STABLE build consumes/moves next.versionCode. The build may
            # have run with an injected code; adopt it if it is ahead, then move
            # past it so the next stable never reuses a code.
            $code = [int]$props['next.versionCode']
            if ($VersionCode -gt $code) { $code = $VersionCode }
            $props['next.versionCode'] = [string]($code + 1)

            # Advance the development line past the newly shipped stable base,
            # but never move it backwards if dev is already ahead.
            if ((CompareVersion $stableBase $props['debug.baseVersion']) -ge 0) {
                $nextDevBase = BumpVersion $stableBase 'patch'
                $props['debug.baseVersion'] = $nextDevBase
                $props['debug.buildNumber'] = '0'
                $props['debug.versionName'] = "$nextDevBase-dev.1"
            }
        }
        else {
            if ($VersionName -notmatch '^(\d+\.\d+\.\d+)-dev\.(\d+)$') {
                Fail "Dev version name '$VersionName' does not match x.y.z-dev.N."
            }
            # A DEBUG build does NOT consume a version code: it reuses the last
            # stable's code so channel switches stay installable. Advancing the
            # dev counter is enough to distinguish consecutive debug builds.
            $props['debug.baseVersion'] = $Matches[1]
            $props['debug.buildNumber'] = $Matches[2]
            $props['debug.versionName'] = $VersionName
        }

        WriteProps $props
        Write-Output "committed=1"
        Write-Output "nextVersionCode=$($props['next.versionCode'])"
        break
    }
}
