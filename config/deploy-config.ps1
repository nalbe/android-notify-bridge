# Deploys notifybridge config to the phone and asks the running bridge
# to reload it.
#
# Full config (replaces /data/local/tmp/notifybridge.json):
#   .\deploy-config.ps1                     # push example + reload
#   .\deploy-config.ps1 -ConfigPath my.json # push your own file
#
# Drop-in fragment (goes to /data/local/tmp/notifybridge.d/<name>.json,
# merged with whatever else is there - never touches existing config):
#   .\deploy-config.ps1 -DropInName app2 -ConfigPath my-fragment.json
#
# Both variants accept -RestartService as a fallback when the reload
# broadcast is not delivered to a chilled process (seen on some ROMs).
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot "notifybridge.example.json"),
    [string]$DropInName = "",
    [switch]$RestartService
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw "Config not found: $ConfigPath"
}

if ($DropInName -ne "") {
    adb shell mkdir -p /data/local/tmp/notifybridge.d
    if ($LASTEXITCODE -ne 0) {
        throw "mkdir failed"
    }
    adb push $ConfigPath "/data/local/tmp/notifybridge.d/$DropInName.json"
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed"
    }
    Write-Host "Pushed fragment notifybridge.d/$DropInName.json"
} else {
    adb push $ConfigPath /data/local/tmp/notifybridge.json
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed"
    }
    Write-Host "Pushed main config notifybridge.json"
}

if ($RestartService) {
    adb shell am force-stop com.bastet.notifybridge
    if ($LASTEXITCODE -ne 0) {
        throw "am force-stop failed"
    }
    Write-Host "Restarted (forced rebind)."
} else {
    adb shell am broadcast -a com.bastet.notifybridge.RELOAD_CONFIG | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "reload broadcast failed"
    }
    Write-Host "Reload requested (RELOAD_CONFIG)."
    Write-Host "Note: a chilled/frozen process may not receive the broadcast on some"
    Write-Host "ROMs; rerun with -RestartService in that case."
}

Write-Host "Done. Check: adb logcat -d | findstr notifybridge"