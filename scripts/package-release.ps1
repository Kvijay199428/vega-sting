# ============================================================================
# VEGA STING release packaging
#
# Verifies a freshly built channel APK (package id, version, embedded default
# update channel, signing certificate), computes its SHA-256 and emits the
# machine-readable update.json manifest plus a publication summary.
#
# Invoked by release.bat / debug.bat. Never publishes anything by itself.
# ============================================================================
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][ValidateSet('stable', 'dev')][string]$Channel,
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [int]$MinSupportedVersionCode = 1,
    [string]$PackageName = 'com.vega.sting',
    [string]$OutDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot

function Fail([string]$Message) {
    [Console]::Error.WriteLine("ERROR: $Message")
    exit 1
}

if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK not found: $Apk" }
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$apkName = Split-Path -Leaf $Apk

$channelLabel = if ($Channel -eq 'dev') { 'DEBUG' } else { 'STABLE' }
$channelTag = if ($Channel -eq 'dev') { 'debug' } else { 'stable' }

# --- locate the Android build-tools ----------------------------------------
$sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
    Where-Object { $_ -and (Test-Path -LiteralPath $_) }
if ($sdkRoots.Count -eq 0) { Fail 'Android SDK not found (set ANDROID_HOME).' }

$buildToolsDir = $null
foreach ($sdk in $sdkRoots) {
    $bt = Join-Path $sdk 'build-tools'
    if (Test-Path -LiteralPath $bt) {
        $candidate = Get-ChildItem -LiteralPath $bt -Directory |
            Sort-Object { [version]($_.Name -replace '-.*$', '') } -ErrorAction SilentlyContinue |
            Select-Object -Last 1
        if ($candidate) { $buildToolsDir = $candidate.FullName; break }
    }
}
if (-not $buildToolsDir) { Fail 'No Android build-tools found under the SDK.' }

function ToolPath([string]$Name) {
    foreach ($ext in @('.bat', '.exe', '')) {
        $p = Join-Path $script:buildToolsDir "$Name$ext"
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

$aapt2 = ToolPath 'aapt2'
$apksigner = ToolPath 'apksigner'
if (-not $aapt2) { Fail 'aapt2 not found in build-tools.' }
if (-not $apksigner) { Fail 'apksigner not found in build-tools.' }

# --- verify package / version ----------------------------------------------
$badging = & $aapt2 dump badging $Apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "aapt2 dump badging failed:`n$badging" }
$badgingText = $badging -join "`n"

if ($badgingText -notmatch "package: name='([^']+)'") { Fail 'Could not read package name from APK.' }
$apkPackage = $Matches[1]
if ($badgingText -notmatch "versionCode='([^']+)'") { Fail 'Could not read versionCode from APK.' }
$apkCode = [int]$Matches[1]
if ($badgingText -notmatch "versionName='([^']+)'") { Fail 'Could not read versionName from APK.' }
$apkVersionName = $Matches[1]

if ($apkPackage -ne $PackageName) { Fail "APK package '$apkPackage' != expected '$PackageName'." }
if ($apkCode -ne $VersionCode) { Fail "APK versionCode $apkCode != expected $VersionCode." }
if ($apkVersionName -ne $VersionName) { Fail "APK versionName '$apkVersionName' != expected '$VersionName'." }

# --- verify the embedded default update channel ----------------------------
$tree = & $aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "aapt2 dump xmltree failed:`n$tree" }
$treeLines = @($tree)
$channelValue = $null
for ($i = 0; $i -lt $treeLines.Count; $i++) {
    if ($treeLines[$i] -match 'android:name[^"]*"DEFAULT_UPDATE_CHANNEL"') {
        for ($j = $i; $j -lt [Math]::Min($i + 4, $treeLines.Count); $j++) {
            if ($treeLines[$j] -match 'android:value[^"]*"([^"]*)"') { $channelValue = $Matches[1]; break }
        }
        if ($channelValue) { break }
    }
}
if (-not $channelValue) { Fail 'Embedded DEFAULT_UPDATE_CHANNEL meta-data not found in the APK.' }
if ($channelValue -ne $channelLabel) {
    Fail "Embedded default channel '$channelValue' != expected '$channelLabel'."
}

# --- verify the signature ---------------------------------------------------
$certOut = & $apksigner verify --print-certs $Apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "APK signature verification failed:`n$($certOut -join "`n")" }
$certText = $certOut -join "`n"
if ($certText -notmatch 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)') {
    Fail 'Could not read the signing certificate digest from apksigner.'
}
$signerDigest = ($Matches[1] -replace ':', '').ToLowerInvariant()

# Cross-check the signer against the configured release keystore when the
# credentials are available, so a debug-signed APK can never be published.
$gradleProps = Join-Path $env:USERPROFILE '.gradle\gradle.properties'
$keystoreChecked = $false
if (Test-Path -LiteralPath $gradleProps) {
    $ks = @{}
    foreach ($line in Get-Content -LiteralPath $gradleProps) {
        $t = $line.Trim()
        if ($t -match '^(VEGA_STING_[A-Z_]+)\s*=\s*(.+)$') { $ks[$Matches[1]] = $Matches[2].Trim() }
    }
    if ($ks['VEGA_STING_STORE_FILE'] -and $ks['VEGA_STING_KEY_ALIAS'] -and $ks['VEGA_STING_STORE_PASSWORD']) {
        $keytool = (Get-Command keytool -ErrorAction SilentlyContinue)
        if ($keytool) {
            $kt = & $keytool.Source -list -v -keystore $ks['VEGA_STING_STORE_FILE'] `
                -alias $ks['VEGA_STING_KEY_ALIAS'] -storepass $ks['VEGA_STING_STORE_PASSWORD'] 2>&1
            $ktText = $kt -join "`n"
            if ($ktText -match 'SHA256:\s*([0-9A-Fa-f:]+)') {
                $keystoreDigest = ($Matches[1] -replace ':', '').ToLowerInvariant()
                if ($keystoreDigest -ne $signerDigest) {
                    Fail "APK signer ($signerDigest) does not match the release keystore ($keystoreDigest). Refusing to package a debug-signed APK."
                }
                $keystoreChecked = $true
            }
        }
    }
}
if (-not $keystoreChecked) {
    Write-Warning 'Signer was not cross-checked against the release keystore (credentials unavailable).'
}

# --- hash + manifest --------------------------------------------------------
$hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()

$distRoot = Join-Path $Root 'dist'
$targetDir = if ($OutDir) { $OutDir } else { Join-Path $distRoot $channelTag }
New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$manifest = [ordered]@{
    schemaVersion           = 1
    channel                 = $channelLabel
    versionName             = $VersionName
    versionCode             = $VersionCode
    packageName             = $PackageName
    apkAsset                = $apkName
    sha256                  = $hash
    minSupportedVersionCode = $MinSupportedVersionCode
}
$manifestPath = Join-Path $targetDir 'update.json'
$manifest | ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding UTF8

$checksumPath = Join-Path $targetDir "$apkName.sha256"
"$hash  $apkName" | Set-Content -LiteralPath $checksumPath -Encoding ASCII

$publishedApk = Join-Path $targetDir $apkName
Copy-Item -LiteralPath $Apk -Destination $publishedApk -Force

$tag = if ($Channel -eq 'dev') { "debug-v$VersionName" } else { "v$VersionName" }
$summary = @(
    "VEGA STING - $channelLabel channel artifacts"
    "------------------------------------------------------------"
    "Tag:            $tag"
    "Package:        $apkPackage"
    "Version name:   $apkVersionName"
    "Version code:   $apkCode"
    "APK asset:      $apkName"
    "SHA-256:        $hash"
    "Signer digest:  $signerDigest"
    ""
    "Artifacts written to: $targetDir"
    ""
    "Publish on GitHub (manual step - nothing was uploaded):"
    "  1. Create a release tagged '$tag'."
    if ($Channel -eq 'dev') {
        "  2. Mark it as a PRE-RELEASE (never a draft). DEBUG releases are"
        "     intentionally invisible to older installs."
    } else {
        "  2. Publish it as a NORMAL release (not a pre-release, not a draft)."
        "     This is what lets already-installed apps discover it through"
        "     /releases/latest, so they update in-app instead of manually."
    }
    "  3. Attach the APK, update.json and the .sha256 file."
    "  4. In the release notes, state the channel ($channelLabel), the version and the audience."
    if ($Channel -ne 'dev') {
        "  5. Never reuse this version/tag and never mark a STABLE release as a"
        "     pre-release: older clients depend on both invariants."
    }
) -join "`n"
$summaryPath = Join-Path $targetDir 'PUBLISH.txt'
$summary | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Output "channel=$channelLabel"
Write-Output "tag=$tag"
Write-Output "apk=$publishedApk"
Write-Output "manifest=$manifestPath"
Write-Output "checksum=$checksumPath"
Write-Output "sha256=$hash"
Write-Output "signer=$signerDigest"
Write-Output "keystoreChecked=$keystoreChecked"
