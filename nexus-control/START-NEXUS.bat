@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install\windows\one-click.ps1"
if errorlevel 1 (
  echo.
  echo NEXUS setup did not finish. See the error above.
  pause
)
