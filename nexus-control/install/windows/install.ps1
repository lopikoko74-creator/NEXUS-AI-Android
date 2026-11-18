$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Dest = Join-Path $env:LOCALAPPDATA "NexusControl"
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Copy-Item (Join-Path $Root "agent") (Join-Path $Dest "agent") -Recurse -Force
$Python = (Get-Command python -ErrorAction Stop).Source
& $Python -m venv (Join-Path $Dest "venv")
$Pip = Join-Path $Dest "venv\Scripts\pip.exe"
& $Pip install -r (Join-Path $Dest "agent\requirements.txt")
$Py = Join-Path $Dest "venv\Scripts\python.exe"
$Action = New-ScheduledTaskAction -Execute $Py -Argument ('"' + (Join-Path $Dest "agent\nexus_agent.py") + '"')
$Trigger = New-ScheduledTaskTrigger -AtLogOn
$Principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
$Settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName "NEXUS CONTROL Agent" -Action $Action -Trigger $Trigger -Principal $Principal -Settings $Settings -Force | Out-Null
Start-ScheduledTask -TaskName "NEXUS CONTROL Agent"
Write-Host "Installed. Agent listens only on 127.0.0.1:8765."
Write-Host "Token: $env:USERPROFILE\.nexus-control-token"
Write-Host "USB: authorize ADB, then run: adb reverse tcp:8765 tcp:8765"
