param(
  [string]$Cmd = "show"
)
$props = @{}
Get-Content "version.properties" | ForEach-Object {
  if ($_ -match '^([^#=][^=]*)=(.*)$') {
    $props[$matches[1]] = $matches[2].Trim()
  }
}
if ($Cmd -eq "show") {
  Write-Host "release.versionName=" $props["release.versionName"]
  Write-Host "release.versionCode=" $props["release.versionCode"]
  Write-Host "debug.buildNumber=" $props["debug.buildNumber"]
  Write-Host "debug.versionName=" $props["debug.versionName"]
}
