import os, subprocess, sys, shutil
from pathlib import Path
root = Path(sys.argv[1]).resolve()
dest = Path(os.environ["LOCALAPPDATA"]) / "NexusControl"
dest.mkdir(parents=True, exist_ok=True)
python = sys.executable
venv = dest / "venv"
if not (venv / "Scripts" / "python.exe").exists():
    subprocess.check_call([python, "-m", "venv", str(venv)])
vp = venv / "Scripts" / "python.exe"
subprocess.check_call([str(vp), "-m", "pip", "install", "--upgrade", "pip"])
subprocess.check_call([str(vp), "-m", "pip", "install", "-r", str(root / "agent" / "requirements.txt")])
agent_dest = dest / "agent"
if agent_dest.exists(): shutil.rmtree(agent_dest)
shutil.copytree(root / "agent", agent_dest)
ps = r"""
$ErrorActionPreference='Stop'
$dest=Join-Path $env:LOCALAPPDATA 'NexusControl'
$py=Join-Path $dest 'venv\Scripts\python.exe'
$script=Join-Path $dest 'agent\nexus_agent.py'
$action=New-ScheduledTaskAction -Execute $py -Argument ('"' + $script + '"')
$trigger=New-ScheduledTaskTrigger -AtLogOn
$principal=New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName 'NEXUS CONTROL Agent' -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName 'NEXUS CONTROL Agent'
"""
subprocess.check_call(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps])
