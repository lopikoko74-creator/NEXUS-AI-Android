$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
function Find-Python {
  $launcher = Get-Command py -ErrorAction SilentlyContinue
  if ($launcher) { try { $candidate = (& $launcher.Source -3 -c "import sys;print(sys.executable)" 2>$null | Select-Object -First 1); if ($candidate -and (Test-Path $candidate)) { return $candidate } } catch {} }
  $p = Get-Command python -ErrorAction SilentlyContinue
  if ($p -and $p.Source -notmatch "WindowsApps") { return $p.Source }
  $cmd = Get-Command winget -ErrorAction SilentlyContinue
  if (-not $cmd) { throw "Python 3 is missing and winget is unavailable. Install Python 3.11+ once, then run START-NEXUS.bat again." }
  Write-Host "[1/4] Installing Python 3.12..."
  & winget install --id Python.Python.3.12 -e --scope user --silent --accept-package-agreements --accept-source-agreements
  $candidate = Join-Path $env:LOCALAPPDATA "Programs\Python\Python312\python.exe"
  if (Test-Path $candidate) { return $candidate }
  $p = Get-Command python -ErrorAction SilentlyContinue
  if ($p) { return $p.Source }
  throw "Python installation finished but python.exe was not found. Restart Windows and run this installer again."
}
function Find-Adb {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $tools = Join-Path $env:LOCALAPPDATA "NexusControl\platform-tools"
  $adb = Join-Path $tools "adb.exe"
  if (-not (Test-Path $adb)) {
    Write-Host "[2/4] Downloading official Android platform-tools..."
    $zip = Join-Path $env:TEMP "nexus-platform-tools.zip"
    Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
    New-Item -ItemType Directory -Force -Path (Split-Path $tools) | Out-Null
    Expand-Archive -Path $zip -DestinationPath (Split-Path $tools) -Force
    Remove-Item $zip -Force
  }
  if (-not (Test-Path $adb)) { throw "ADB download failed." }
  return $adb
}
$Python = Find-Python
Write-Host "[3/4] Installing NEXUS CONTROL agent..."
& $Python (Join-Path $Root "install\windows\install-core.py") $Root
if ($LASTEXITCODE -ne 0) { throw "Agent installation failed." }
$Adb = Find-Adb
$Watch = Join-Path $Root "install\\windows\\usb-watch.ps1"
$WatchAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $Watch + '" -AdbPath "' + $Adb + '"')
$WatchTrigger = New-ScheduledTaskTrigger -AtLogOn
$WatchPrincipal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName "NEXUS CONTROL USB Bridge" -Action $WatchAction -Trigger $WatchTrigger -Principal $WatchPrincipal -Force | Out-Null
Start-ScheduledTask -TaskName "NEXUS CONTROL USB Bridge"
Write-Host "[4/4] Connect TECNO by USB. Unlock phone and approve the USB debugging prompt."
& $Adb start-server | Out-Null
$authorized = $false
for ($i=0; $i -lt 90; $i++) {
  $devices = & $Adb devices
  if (($devices -join "`n") -match "(?m)^\S+\s+device$") { $authorized = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $authorized) { throw "Phone not authorized. Enable USB debugging, approve the RSA prompt, then run START-NEXUS.bat again." }
& $Adb reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw "ADB reverse tunnel failed." }
$Token = (Get-Content (Join-Path $env:USERPROFILE ".nexus-control-token") -Raw).Trim()
$Url = "http://127.0.0.1:8765/#token=$Token"
$Qr = Join-Path $env:LOCALAPPDATA "NexusControl\NEXUS-CONTROL-QR.png"
$Vpy = Join-Path $env:LOCALAPPDATA "NexusControl\venv\Scripts\python.exe"
& $Vpy -c "import qrcode,sys; qrcode.make(sys.argv[1]).save(sys.argv[2])" $Url $Qr
Start-Process $Qr
Write-Host ""
Write-Host "NEXUS CONTROL IS READY. Scan the QR code with your phone camera."
Write-Host "If the QR viewer closes, open: $Url"
Write-Host "USB tunnel remains active while this PC session is running."
