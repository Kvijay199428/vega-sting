@echo off
REM ==========================================================================
REM  Compatibility wrapper. The DEBUG channel is built by:
REM      release.bat --dev
REM  Kept so `debug.bat` keeps working for anyone used to the old script.
REM ==========================================================================
call "%~dp0release.bat" --dev %*
exit /b %ERRORLEVEL%
