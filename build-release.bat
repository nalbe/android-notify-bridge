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

rem The project-level release\ is a junction onto
rem app\build\outputs\apk\release, so AGP's single write is already
rem visible from the root - NEVER copy the APK again (a copy into the
rem junction would write the file onto itself).
if not exist "release\notifybridge-release.apk" (
    echo APK not found under release (junction to app\build\outputs\apk\release)
    exit /b 1
)

echo.
echo OK: release\notifybridge-release.apk