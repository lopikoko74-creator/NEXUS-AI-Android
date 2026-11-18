param([Parameter(Mandatory=$true)][string]$AdbPath)
$ErrorActionPreference = "SilentlyContinue"
while ($true) {
  if (Test-Path $AdbPath) {
    & $AdbPath start-server | Out-Null
    $devices = & $AdbPath devices
    if (($devices -join "`n") -match "(?m)^\S+\s+device$") {
      & $AdbPath reverse tcp:8765 tcp:8765 | Out-Null
    }
  }
  Start-Sleep -Seconds 5
}
