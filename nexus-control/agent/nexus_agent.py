#!/usr/bin/env python3
"""NEXUS CONTROL local PC agent. Loopback-only; intended for Android ADB reverse."""
from __future__ import annotations
import json, os, platform, secrets, shutil, subprocess, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HOST = "127.0.0.1"
PORT = int(os.environ.get("NEXUS_PORT", "8765"))
TOKEN_FILE = Path(os.environ.get("NEXUS_TOKEN_FILE", str(Path.home() / ".nexus-control-token")))
try:
    import psutil
except Exception:
    psutil = None
try:
    import pyautogui
except Exception:
    pyautogui = None

def token():
    value = os.environ.get("NEXUS_TOKEN")
    if value: return value.strip()
    if TOKEN_FILE.exists(): return TOKEN_FILE.read_text().strip()
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    value = secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(value)
    try: TOKEN_FILE.chmod(0o600)
    except OSError: pass
    return value

TOKEN = token()

def run(args, timeout=12):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=False)

def status():
    disks = []
    if psutil:
        try:
            for part in psutil.disk_partitions(all=False):
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    disks.append({"mount": part.mountpoint, "total": usage.total, "used": usage.used, "free": usage.free})
                except (OSError, PermissionError): continue
        except Exception: pass
    if not disks:
        path = (os.environ.get("SystemDrive", "C:") + "\\\\") if os.name == "nt" else "/"
        try:
            u = shutil.disk_usage(path)
            disks.append({"mount": path, "total": u.total, "used": u.used, "free": u.free})
        except OSError: pass
    processes = []
    if psutil:
        try:
            for p in psutil.process_iter(["pid", "name", "username", "memory_info", "cpu_percent"]):
                try:
                    info = p.info
                    processes.append({"pid": info["pid"], "name": info["name"] or "?", "user": info.get("username") or "",
                                      "memory": info["memory_info"].rss if info.get("memory_info") else 0,
                                      "cpu": info.get("cpu_percent") or 0})
                except (psutil.NoSuchProcess, psutil.AccessDenied): continue
        except Exception: pass
    data = {
        "hostname": platform.node(), "os": platform.platform(), "system": platform.system(),
        "release": platform.release(), "architecture": platform.machine(), "python": platform.python_version(),
        "uptime_seconds": int(time.time() - psutil.boot_time()) if psutil else None,
        "cpu_percent": psutil.cpu_percent(interval=0.15) if psutil else None,
        "cpu_count": psutil.cpu_count() if psutil else os.cpu_count(),
        "memory": (lambda m: {"total": m.total, "used": m.used, "available": m.available, "percent": m.percent})(psutil.virtual_memory()) if psutil else None,
        "disks": disks, "processes": sorted(processes, key=lambda x: x["memory"], reverse=True)[:100],
        "agent": "NEXUS CONTROL Agent 0.2.0",
    }
    return data

def set_volume(level):
    level = int(level)
    if not 0 <= level <= 100: raise ValueError("Volume must be 0..100")
    if platform.system() == "Windows":
        try:
            from pycaw.pycaw import AudioUtilities
            device = AudioUtilities.GetSpeakers()
            interface = device.EndpointVolume
            interface.SetMasterVolumeLevelScalar(level / 100.0, None)
        except Exception as e:
            raise RuntimeError("Windows audio adapter unavailable: " + str(e))
    elif platform.system() == "Linux":
        if shutil.which("pactl"):
            result = run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"])
        elif shutil.which("amixer"):
            result = run(["amixer", "sset", "Master", f"{level}%"])
        else: raise RuntimeError("Install pulseaudio-utils (pactl) or alsa-utils (amixer)")
        if result.returncode: raise RuntimeError((result.stderr or result.stdout or "Volume command failed").strip())
    else: raise RuntimeError("Volume adapter unsupported on this operating system")
    return {"ok": True, "volume": level}

def terminate_process(pid):
    if psutil is None: raise RuntimeError("Install psutil to manage processes")
    pid = int(pid)
    if pid <= 1 or pid == os.getpid(): raise ValueError("Protected process")
    try: proc = psutil.Process(pid)
    except psutil.NoSuchProcess: raise ValueError("Process no longer exists")
    try:
        owner = proc.username()
        current = psutil.Process().username()
        if owner != current: raise ValueError("Only processes owned by the logged-in user can be stopped")
        if proc.is_running() and proc.status() == psutil.STATUS_ZOMBIE: raise ValueError("Process already stopped")
        proc.terminate()
        try: proc.wait(timeout=3)
        except psutil.TimeoutExpired: proc.kill()
    except psutil.AccessDenied: raise ValueError("Access denied by the operating system")
    return {"ok": True, "message": f"Process {pid} stopped"}

def action(name, value=None):
    system = platform.system()
    if name == "set_volume": return set_volume(value)
    if name == "lock":
        if system == "Windows": cmd = ["rundll32.exe", "user32.dll,LockWorkStation"]
        elif system == "Linux": cmd = ["loginctl", "lock-session"]
        else: raise ValueError("Unsupported operating system")
    elif name in ("shutdown", "restart"):
        if system == "Windows": cmd = ["shutdown", "/s" if name == "shutdown" else "/r", "/t", "15"]
        elif system == "Linux": cmd = ["systemctl", "poweroff" if name == "shutdown" else "reboot"]
        else: raise ValueError("Unsupported operating system")
    elif name == "cancel_power":
        if system != "Windows": raise ValueError("Cancel is currently supported on Windows only")
        cmd = ["shutdown", "/a"]
    else: raise ValueError("Action not allowed")
    result = run(cmd)
    if result.returncode: raise RuntimeError((result.stderr or result.stdout or "Command failed").strip())
    return {"ok": True, "message": name + " accepted"}

def input_action(body):
    if pyautogui is None: raise ValueError("Input control unavailable: install pyautogui and ensure an active desktop session")
    kind = str(body.get("type", ""))
    if kind == "move":
        dx = max(-500, min(500, int(body.get("dx", 0)))); dy = max(-500, min(500, int(body.get("dy", 0))))
        x, y = pyautogui.position(); pyautogui.moveTo(max(0, x + dx), max(0, y + dy), duration=0)
    elif kind == "click":
        button = str(body.get("button", "left"))
        if button not in ("left", "right", "middle"): raise ValueError("Invalid mouse button")
        pyautogui.click(button=button)
    elif kind == "scroll": pyautogui.scroll(max(-10, min(10, int(body.get("amount", 0)))))
    elif kind == "type":
        value = str(body.get("text", ""))
        if len(value) > 500: raise ValueError("Text is too long")
        pyautogui.write(value, interval=0.01)
    elif kind == "hotkey":
        allowed = {"alt_tab":("alt","tab"),"win_d":("win","d"),"ctrl_c":("ctrl","c"),"ctrl_v":("ctrl","v"),
                   "ctrl_z":("ctrl","z"),"ctrl_s":("ctrl","s"),"ctrl_shift_esc":("ctrl","shift","esc"),
                   "alt_f4":("alt","f4"),"enter":("enter",),"escape":("esc",)}
        combo = str(body.get("combo", ""))
        if combo not in allowed: raise ValueError("Shortcut not allowed")
        pyautogui.hotkey(*allowed[combo])
    else: raise ValueError("Input action not allowed")
    return {"ok": True, "message": "Input sent"}

class Handler(BaseHTTPRequestHandler):
    server_version = "NEXUS-Control/0.2"
    def log_message(self, fmt, *args): print("[nexus]", fmt % args)
    def send(self, code, obj, content_type="application/json; charset=utf-8"):
        raw = obj if isinstance(obj, bytes) else json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(raw))); self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff"); self.end_headers(); self.wfile.write(raw)
    def authorized(self):
        return secrets.compare_digest(self.headers.get("Authorization", ""), "Bearer " + TOKEN)
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            html = (Path(__file__).parent / "web" / "index.html").read_bytes()
            return self.send(200, html, "text/html; charset=utf-8")
        if not self.authorized(): return self.send(401, {"error":"Unauthorized"})
        if path == "/api/status":
            try: return self.send(200, status())
            except Exception as e: return self.send(500, {"error":str(e)})
        if path == "/api/health": return self.send(200, {"ok":True,"agent":"NEXUS CONTROL"})
        if path == "/api/screenshot":
            if pyautogui is None: return self.send(501, {"error":"Install pyautogui and use an active desktop session"})
            try:
                import io
                buffer = io.BytesIO(); pyautogui.screenshot().save(buffer, format="PNG")
                return self.send(200, buffer.getvalue(), "image/png")
            except Exception as e: return self.send(503, {"error":"Screenshot failed: "+str(e)})
        return self.send(404, {"error":"Not found"})
    def do_POST(self):
        if not self.authorized(): return self.send(401, {"error":"Unauthorized"})
        path = urlparse(self.path).path
        if path not in ("/api/action", "/api/input", "/api/process/terminate"): return self.send(404, {"error":"Not found"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            if n < 0 or n > 4096: return self.send(413, {"error":"Request too large"})
            body = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(body, dict): raise ValueError("JSON object required")
            if path == "/api/input": result = input_action(body)
            elif path == "/api/process/terminate": result = terminate_process(body.get("pid"))
            else: result = action(str(body.get("action", "")), body.get("value"))
            return self.send(200, result)
        except Exception as e: return self.send(400, {"error":str(e)})

if __name__ == "__main__":
    print(f"NEXUS CONTROL listening on http://{HOST}:{PORT}")
    print(f"Access token stored at: {TOKEN_FILE}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
