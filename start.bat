@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\services.ps1" -Action start
if errorlevel 1 (
  echo Startup failed. See .runtime\logs\*.err.log
  exit /b 1
)
for /f "delims=" %%u in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\services.ps1" -Action url') do start "" "%%u"
endlocal
