@echo off
rem Double-click to regenerate ResourcePack.manifest.json for the pack. Optional: drag another zip onto this file.
if "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-manifest.ps1"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-manifest.ps1" -Zip "%~1"
)
echo.
pause
