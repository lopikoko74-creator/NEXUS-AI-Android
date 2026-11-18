$ErrorActionPreference = "Stop"
$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) { throw "Android platform-tools (adb) not found. Install Google's platform-tools first." }
& adb devices
& adb reverse tcp:8765 tcp:8765
Write-Host "USB tunnel ready. Open http://127.0.0.1:8765 on the Android phone."
