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
#       -> advances version.properties after a verified build. REFUSES to
#          commit a versionCode that has already been consumed, so two
#          published APKs can never share a code.
#
#   verify -Channel stable|dev -VersionCode <n> [-VersionName <name>]
#       -> fails if the code (or the release tag) has already been used.
#
# Separating "next" from "commit" means a failed or abandoned build never
# consumes a version code.
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('next', 'commit', 'show', 'verify')][string]$Mode,
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
        '# Globally increasing versionCode, unique to every published APK.'
        '#'
        '# One monotonically increasing sequence is shared by BOTH channels: every'
        '# release -- STABLE or DEBUG -- consumes the current value and advances it,'
        '# so versionCode always grows monotonically across channels.'
        '#'
        '# Two published APKs are never allowed to carry the same code. The commit'
        '# step refuses a code that was already consumed, which keeps an in-place'
        '# install possible in either direction:'
        '#   STABLE 17 -> DEBUG 18 (ordinary update)'
        '#   DEBUG 18  -> STABLE 19 (ordinary update)'
        '# A channel is identified by the release manifest / build flavor, never by'
        '# the versionCode. It only moves after a verified build is committed.'
        "next.versionCode=$($Map['next.versionCode'])"
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
            # OPTION A (globally increasing versionCode): a DEBUG build consumes
            # the SAME shared counter as STABLE. Both channels advance one
            # monotonically increasing sequence, so an in-place install is
            # possible whichever channel the device moves to. Channel identity
            # comes from the build flavor / release manifest, not the code.
            $code = [int]$props['next.versionCode']
            if ($code -lt 1) { $code = 1 }
        }
        Write-Output "versionName=$name"
        Write-Output "versionCode=$code"
        Write-Output "minSupportedVersionCode=$($props['minSupported.versionCode'])"
        break
    }
    'verify' {
        if ($VersionCode -le 0) { Fail 'verify requires -VersionCode.' }

        $conflicts = @()

        # 1) Locally packaged releases: any dist\*\update.json that already
        #    claims this code.
        $dist = Join-Path $Root 'dist'
        if (Test-Path -LiteralPath $dist) {
            foreach ($manifest in Get-ChildItem -Path $dist -Filter 'update.json' -Recurse -ErrorAction SilentlyContinue) {
                try {
                    $json = Get-Content -LiteralPath $manifest.FullName -Raw | ConvertFrom-Json
                }
                catch { continue }
                if ($null -ne $json.PSObject.Properties['versionCode'] -and
                    [int]$json.versionCode -eq $VersionCode) {
                    $conflicts += "local: $($manifest.FullName) (channel $($json.channel), version $($json.versionName))"
                }
            }
        }

        # 2) Published releases: refuse to reuse a tag that is already out
        #    there. Best effort -- skipped when gh is unavailable or offline.
        if ($VersionName) {
            $tag = if ($Channel -eq 'dev') { "debug-v$VersionName" } else { "v$VersionName" }
            if (Get-Command gh -ErrorAction SilentlyContinue) {
                # Native stderr is treated as a terminating error while
                # $ErrorActionPreference is 'Stop', so relax it for the probe and
                # rely on the exit code instead.
                $savedEap = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    & gh release view $tag --json tagName *> $null
                    $exists = ($LASTEXITCODE -eq 0)
                }
                catch {
                    $exists = $false
                    Write-Warning "Could not check GitHub for '$tag' (offline?); continuing with local checks only."
                }
                finally {
                    $ErrorActionPreference = $savedEap
                }
                if ($exists) {
                    $conflicts += "published: release '$tag' already exists"
                }
            }
        }

        if ($conflicts.Count -gt 0) {
            Fail ("version $VersionName (code $VersionCode) is not free:`n  " + ($conflicts -join "`n  "))
        }
        Write-Output 'unique=1'
        Write-Output "versionCode=$VersionCode"
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

            # The built APK must have consumed the code `next` handed out. A
            # LOWER code means that code was already used by an earlier
            # published release: refuse to commit rather than silently reuse it,
            # so two APKs in the wild can never share a versionCode. (A code
            # ahead of `next` is still adopted, e.g. a manually injected build.)
            $expected = [int]$props['next.versionCode']
            if ($VersionCode -lt $expected) {
                Fail "versionCode $VersionCode has already been consumed; the next free code is $expected. Rebuild the release."
            }
            $props['next.versionCode'] = [string]($VersionCode + 1)

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
            $props['debug.baseVersion'] = $Matches[1]
            $props['debug.buildNumber'] = $Matches[2]
            $props['debug.versionName'] = $VersionName

            # OPTION A: a DEBUG release also consumes the shared versionCode.
            # Refuse a code that was already consumed (a lower code than `next`
            # means an earlier release used it); adopt a code that is ahead.
            $expected = [int]$props['next.versionCode']
            if ($VersionCode -lt $expected) {
                Fail "versionCode $VersionCode has already been consumed; the next free code is $expected. Rebuild the release."
            }
            $props['next.versionCode'] = [string]($VersionCode + 1)
        }

        WriteProps $props
        Write-Output "committed=1"
        Write-Output "nextVersionCode=$($props['next.versionCode'])"
        break
    }
}
