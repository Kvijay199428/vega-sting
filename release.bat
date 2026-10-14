@echo off
setlocal
pushd "%~dp0"

REM ==========================================================================
REM  VEGA STING release builder
REM
REM    release.bat --stable            next production release (patch bump)
REM    release.bat --stable --minor    next minor production release
REM    release.bat --stable --major    next major production release
REM    release.bat --stable --respin   same as --stable (stable names carry no suffix)
REM    release.bat --dev               next development/prerelease build
REM    release.bat --dev --base 2.0.3  start a new development base
REM
REM  Version numbers are derived automatically from version.properties. Nothing
REM  is published to GitHub -- uploading the artifacts is a separate manual step.
REM ==========================================================================

set "CHANNEL="
set "FLAVOR="
set "TAG="

if /i "%~1"=="--stable" ( set "CHANNEL=stable" & set "FLAVOR=StableRelease" & set "TAG=stable" )
if /i "%~1"=="--dev"    ( set "CHANNEL=dev"    & set "FLAVOR=DevRelease"    & set "TAG=debug" )
if /i "%~1"=="--debug"  ( set "CHANNEL=dev"    & set "FLAVOR=DevRelease"    & set "TAG=debug" )
if /i "%~1"=="--help"   goto usage
if /i "%~1"=="-h"       goto usage
if "%CHANNEL%"=="" goto usage

set "EXTRA="
shift
:collect
if "%~1"=="" goto collected
if /i "%~1"=="--respin" ( set "EXTRA=%EXTRA% -Respin" & shift & goto collect )
if /i "%~1"=="--minor"  ( set "EXTRA=%EXTRA% -Minor"  & shift & goto collect )
if /i "%~1"=="--major"  ( set "EXTRA=%EXTRA% -Major"  & shift & goto collect )
if /i "%~1"=="--base"   ( set "EXTRA=%EXTRA% -Base %~2" & shift & shift & goto collect )
echo ERROR: unknown option "%~1"
goto usage
:collected

REM ---- derive the version to build -----------------------------------------
set "VER_NAME="
set "VER_CODE="
set "MIN_SUPPORTED=1"
for /f "usebackq tokens=1,* delims==" %%a in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\version.ps1" next -Channel %CHANNEL% %EXTRA%`) do (
  if "%%a"=="versionName" set "VER_NAME=%%b"
  if "%%a"=="versionCode" set "VER_CODE=%%b"
  if "%%a"=="minSupportedVersionCode" set "MIN_SUPPORTED=%%b"
)
if "%VER_NAME%"=="" (
  echo ERROR: could not compute the %CHANNEL% version.
  popd & exit /b 1
)

echo.
echo ==========================================================================
echo  VEGA STING - %CHANNEL% channel
echo    versionName : %VER_NAME%
echo    versionCode : %VER_CODE%
echo ==========================================================================
echo.

echo [1/4] Running unit tests...
call gradlew.bat :app:test%FLAVOR%UnitTest
if errorlevel 1 (
  echo ERROR: unit tests failed. Nothing was changed.
  popd & exit /b 1
)

echo [2/4] Building :app:assemble%FLAVOR% ...
call gradlew.bat :app:assemble%FLAVOR% -PvegaVersionName=%VER_NAME% -PvegaVersionCode=%VER_CODE%
if errorlevel 1 (
  echo ERROR: %CHANNEL% build failed. Nothing was changed.
  popd & exit /b 1
)

set "APK=app\build\outputs\apk\%CHANNEL%\release\vega-sting-%TAG%-%VER_NAME%.apk"
if not exist "%APK%" (
  echo ERROR: expected APK not found: %APK%
  popd & exit /b 1
)

REM STABLE tags are the plain v<versionName> form so that the updater already
REM installed on older devices can still discover the release; DEBUG keeps the
REM debug-v<versionName> form (it is a pre-release and invisible to them).
set "RELEASE_TAG=v%VER_NAME%"
if "%TAG%"=="debug" set "RELEASE_TAG=debug-v%VER_NAME%"

echo [3/4] Verifying the APK and writing the release manifest...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-release.ps1" ^
  -Apk "%APK%" -Channel %CHANNEL% -VersionName "%VER_NAME%" -VersionCode %VER_CODE% ^
  -MinSupportedVersionCode %MIN_SUPPORTED%
if errorlevel 1 (
  echo ERROR: APK verification failed. Nothing was published and version.properties was left unchanged.
  popd & exit /b 1
)

echo [4/4] Advancing version.properties...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\version.ps1" commit -Channel %CHANNEL% -VersionName "%VER_NAME%" -VersionCode %VER_CODE%
if errorlevel 1 (
  echo ERROR: could not advance version.properties.
  popd & exit /b 1
)

echo.
echo ==========================================================================
echo  DONE - %CHANNEL% %VER_NAME% (code %VER_CODE%)
echo  Artifacts: dist\%TAG%\
echo  Upload the APK, update.json and the .sha256 file to a GitHub release
echo  tagged %RELEASE_TAG% (see dist\%TAG%\PUBLISH.txt).
echo ==========================================================================
popd
endlocal
exit /b 0

:usage
echo.
echo Usage:
echo   release.bat --stable [--minor ^| --major ^| --respin]
echo   release.bat --dev    [--base x.y.z]
echo.
popd
endlocal
exit /b 0
