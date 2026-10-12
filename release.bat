@echo off
setlocal

for /f "tokens=1,2 delims==" %%i in ('findstr /B /V "^#" version.properties') do (
  if "%%i"=="release.versionName" set REL_VER_NAME=%%j
  if "%%i"=="release.versionCode" set REL_VER_CODE=%%j
  if "%%i"=="debug.versionCode" set DBG_VER_CODE=%%j
  if "%%i"=="debug.buildNumber" set DBG_BN=%%j
)

echo Current release: %REL_VER_NAME% (code %REL_VER_CODE%)
set /p ANS=Create new release? (y/N): 
if /i not "%ANS%"=="y" (
  echo Cancelled.
  exit /b 0
)

echo 1. Patch (1.0.7 -> 1.0.8)
echo 2. Minor (1.0.7 -> 1.1.0)
echo 3. Major (1.0.7 -> 2.0.0)
set /p CHOICE=Choose: 
if "%CHOICE%"=="1" goto patch
if "%CHOICE%"=="2" goto minor
if "%CHOICE%"=="3" goto major
echo Invalid. Exit.
exit /b 1

:patch
for /f "tokens=1,2,3 delims=." %%a in ("%REL_VER_NAME%") do set M=%%a& set N=%%b& set P=%%c
set /a NP=%P%+1
set NEW_VER=%M%.%N%.%NP%
set /a NEW_CODE=%REL_VER_CODE%+1
goto confirm

:minor
for /f "tokens=1,2,3 delims=." %%a in ("%REL_VER_NAME%") do set M=%%a& set N=%%b& set P=%%c
set /a NN=%N%+1
set NEW_VER=%M%.%NN%.0
set /a NEW_CODE=%REL_VER_CODE%+1
goto confirm

:major
for /f "tokens=1,2,3 delims=." %%a in ("%REL_VER_NAME%") do set M=%%a& set N=%%b& set P=%%c
set /a NM=%M%+1
set NEW_VER=%NM%.0.0
set /a NEW_CODE=%REL_VER_CODE%+1
goto confirm

:confirm
echo New: %NEW_VER% (code %NEW_CODE%)
set /p CONF=Confirm? (y/N): 
if /i not "%CONF%"=="y" (
  echo Cancelled.
  exit /b 0
)

echo # Public, production release> version.properties
echo release.versionName=%NEW_VER%>> version.properties
echo release.versionCode=%NEW_CODE%>> version.properties
echo.>> version.properties
echo # Internal development builds>> version.properties
echo debug.versionName=%NEW_VER%-debug>> version.properties
echo debug.versionCode=%DBG_VER_CODE%>> version.properties
echo debug.buildNumber=%DBG_BN%>> version.properties

powershell -ExecutionPolicy Bypass -Command "=Get-Content 'app/build.gradle.kts' -Raw -Encoding UTF8; = -replace 'versionCode = \d+', 'versionCode = %NEW_CODE%'; = -replace 'versionName = \x22[^"]+\x22', 'versionName = \x22%NEW_VER%\x22'; Set-Content 'app/build.gradle.kts' -Value  -Encoding UTF8"

call gradlew.bat :app:testDebugUnitTest
if %ERRORLEVEL% NEQ 0 (
  echo Unit tests failed.
  exit /b 1
)
call gradlew.bat :app:assembleRelease
if %ERRORLEVEL% NEQ 0 (
  echo Release build failed.
  exit /b 1
)

echo DONE: %NEW_VER%
endlocal
