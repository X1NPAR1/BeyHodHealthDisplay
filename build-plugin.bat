@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo ==========================================
echo BeyHodHealthDisplay Plugin - Windows Build Helper
echo ==========================================
echo.

echo PowerShell build script calistiriliyor...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-plugin.ps1"
if errorlevel 1 (
    echo.
    echo ERROR: Build helper basarisiz oldu. Yukaridaki hatayi kopyalayip gonder.
    pause
    exit /b 1
)

echo.
pause
