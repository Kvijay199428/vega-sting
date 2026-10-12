param([string]$action="sync")
$props = @{}
Get-Content "version.properties" | ForEach-Object {
  if ($_ -match '^(release\.versionName|release\.versionCode|debug\.versionName|debug\.versionCode|debug\.buildNumber)=(.+)$') {
    $props[$matches[1]] = $matches[2].Trim()
  }
}
$rn = $props['release.versionName']
$rc = $props['release.versionCode']
if ($action -eq "sync") {
  $s = Get-Content "app/build.gradle.kts" -Raw -Encoding UTF8
  $new = ($s -replace 'versionCode = \d+', "versionCode = $rc")
  $new2 = ($new -replace 'versionName = ""[^""]+""', "versionName = ""$rn""")
  if ($new2 -ne $s) {
    Set-Content "app/build.gradle.kts" -Value $new2 -Encoding UTF8
    Write-Output "SYNCED"
  } else {
    Write-Output "NOCHANGE"
  }
}
