#!/usr/bin/env python3
"""USB-only client for the NEXUS Android Local Bridge. Standard library only."""
from __future__ import annotations
import argparse
import json
import os
import socket
import subprocess
import sys
import urllib.error
import urllib.request

HOST = "127.0.0.1"
PORT = 8766
BASE = f"http://{HOST}:{PORT}"
MAX_PROMPT = 8000
DEFAULT_TOKENS = 128

class BridgeError(RuntimeError):
    pass

def adb_devices(adb: str) -> list[str]:
    try:
        result = subprocess.run([adb, "devices"], check=True, capture_output=True,
                                text=True, timeout=10)
    except (OSError, subprocess.SubprocessError) as exc:
        raise BridgeError(f"ADB could not run: {exc}") from exc
    devices = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) >= 2 and fields[1] == "device":
            devices.append(fields[0])
    return devices

def ensure_forward(adb: str) -> str:
    devices = adb_devices(adb)
    if not devices:
        raise BridgeError("No authorized Android device. Connect the phone and approve its USB debugging prompt.")
    if len(devices) != 1:
        raise BridgeError(f"Expected exactly one authorized device; found {len(devices)}. Disconnect extras.")
    serial = devices[0]
    try:
        subprocess.run([adb, "-s", serial, "forward", "--no-rebind",
                        f"tcp:{PORT}", f"tcp:{PORT}"], check=True,
                       capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError) as exc:
        raise BridgeError(f"Could not create USB ADB forward: {exc}") from exc
    return serial

def request(path: str, method: str = "GET", payload: dict | None = None) -> dict:
    token = os.environ.get("NEXUS_BRIDGE_TOKEN", "").strip()
    if not token:
        raise BridgeError("NEXUS_BRIDGE_TOKEN is not set in the agent's secret environment.")
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=body, method=method,
        headers={"Authorization": "Bearer " + token, "Accept": "application/json",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=180) as response:
            raw = response.read(128 * 1024 + 1)
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            raise BridgeError("NEXUS rejected the token. Revoke it and create a new one in the app.") from None
        if exc.code == 404:
            raise BridgeError("NEXUS Local Bridge endpoint is not available in this app version.") from None
        raise BridgeError(f"NEXUS bridge returned HTTP {exc.code}.") from None
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
        raise BridgeError(f"Cannot reach NEXUS over USB. Is Local Bridge running? ({exc})") from exc
    if len(raw) > 128 * 1024:
        raise BridgeError("NEXUS response exceeded the 128 KiB client limit.")
    try:
        result = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise BridgeError("NEXUS returned an invalid JSON response.") from None
    if not isinstance(result, dict):
        raise BridgeError("NEXUS returned an unexpected response.")
    return result

def main() -> int:
    parser = argparse.ArgumentParser(description="Use the NEXUS local Android model over USB.")
    parser.add_argument("--adb", default="adb", help="ADB executable")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status", help="Check bridge and model status")
    gen = sub.add_parser("generate", help="Generate with the already-loaded local model")
    gen.add_argument("--prompt", required=True)
    gen.add_argument("--max-tokens", type=int, default=DEFAULT_TOKENS)
    args = parser.parse_args()
    serial = None
    try:
        serial = ensure_forward(args.adb)
        if args.command == "status":
            result = request("/v1/status")
        elif args.command == "generate":
            if not args.prompt.strip() or len(args.prompt) > MAX_PROMPT:
                raise BridgeError(f"Prompt must contain 1..{MAX_PROMPT} characters.")
            if not 16 <= args.max_tokens <= 160:
                raise BridgeError("max-tokens must be between 16 and 160.")
            result = request("/v1/generate", "POST",
                             {"prompt": args.prompt, "max_tokens": args.max_tokens})
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if result.get("ok") is False or result.get("error"):
            return 2
        return 0
    except BridgeError as exc:
        print(f"NEXUS: {exc}", file=sys.stderr)
        return 2
    finally:
        if serial:
            subprocess.run([args.adb, "-s", serial, "forward", "--remove", f"tcp:{PORT}"],
                           capture_output=True, text=True, timeout=10)

if __name__ == "__main__":
    raise SystemExit(main())
