@echo off
setlocal

for /f "tokens=1,2 delims==" %%i in ('findstr /B /V "^#" version.properties') do (
  if "%%i"=="release.versionName" set REL_VER_NAME=%%j
  if "%%i"=="release.versionCode" set REL_VER_CODE=%%j
  if "%%i"=="debug.versionCode" set DBG_VER_CODE=%%j
  if "%%i"=="debug.buildNumber" set DBG_BN=%%j
)

set /a NEW_BN=%DBG_BN%+1

REM rewrite version.properties
echo # Public, production release> version.properties
echo release.versionName=%REL_VER_NAME%>> version.properties
echo release.versionCode=%REL_VER_CODE%>> version.properties
echo.>> version.properties
echo # Internal development builds>> version.properties
echo debug.versionName=%REL_VER_NAME%-debug>> version.properties
echo debug.versionCode=%DBG_VER_CODE%>> version.properties
echo debug.buildNumber=%NEW_BN%>> version.properties

REM set gradle to release version (default) but debug build will show via app id? easier: keep gradle as release, and About can show full; or set
powershell -ExecutionPolicy Bypass -Command "=Get-Content 'app/build.gradle.kts' -Raw -Encoding UTF8; = -replace 'versionCode = \d+', 'versionCode = %REL_VER_CODE%'; = -replace 'versionName = \x22[^"]+\x22', 'versionName = \x22%REL_VER_NAME%-debug.%NEW_BN%\x22'; Set-Content 'app/build.gradle.kts' -Value  -Encoding UTF8"

call gradlew.bat :app:assembleDebug
endlocal
