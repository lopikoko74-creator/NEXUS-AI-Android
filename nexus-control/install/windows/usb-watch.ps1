param([Parameter(Mandatory=$true)][string]$AdbPath)
$ErrorActionPreference = "SilentlyContinue"
$wasConnected = $false
while ($true) {
  $connected = $false
  if (Test-Path $AdbPath) {
    & $AdbPath start-server | Out-Null
    $devices = & $AdbPath devices
    $connected = (($devices -join "`n") -match "(?m)^\S+\s+device$")
    if ($connected) {
      & $AdbPath reverse tcp:8765 tcp:8765 | Out-Null
      if (-not $wasConnected) {
        $tokenPath = Join-Path $env:USERPROFILE ".nexus-control-token"
        if (Test-Path $tokenPath) {
          $token = (Get-Content $tokenPath -Raw).Trim()
          $url = "http://127.0.0.1:8765/#token=$token"
          & $AdbPath shell am start -a android.intent.action.VIEW -d $url | Out-Null
        }
      }
    }
  }
  $wasConnected = $connected
  Start-Sleep -Seconds 5
}
