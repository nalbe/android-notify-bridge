@echo off
setlocal
cd /d "%~dp0"

echo === notify-bridge: assembleRelease (debug-signed) ===
call .\gradlew.bat assembleRelease --console=plain
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

if not exist "release" mkdir "release"

if exist "app\build\outputs\apk\release\app-release.apk" (
    copy /y "app\build\outputs\apk\release\app-release.apk" "release\notifybridge-release.apk" >nul
)
if exist "app\build\outputs\apk\release\notifybridge-release.apk" (
    copy /y "app\build\outputs\apk\release\notifybridge-release.apk" "release\notifybridge-release.apk" >nul
)

if not exist "release\notifybridge-release.apk" (
    echo APK not found in app\build\outputs\apk\release
    exit /b 1
)

echo.
echo OK: release\notifybridge-release.apk