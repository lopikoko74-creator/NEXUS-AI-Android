#!/usr/bin/env python3
"""Collect a privacy-conscious, read-only Android/fastboot device inventory."""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ADB_PROPERTIES = {
    "manufacturer": "ro.product.manufacturer",
    "brand": "ro.product.brand",
    "model": "ro.product.model",
    "device": "ro.product.device",
    "product": "ro.product.name",
    "build_fingerprint": "ro.build.fingerprint",
    "build_id": "ro.build.id",
    "build_display_id": "ro.build.display.id",
    "android_release": "ro.build.version.release",
    "sdk": "ro.build.version.sdk",
    "security_patch": "ro.build.version.security_patch",
    "abi": "ro.product.cpu.abi",
    "abi_list": "ro.product.cpu.abilist",
    "kernel_release": "kernel_release",
    "bootloader": "ro.bootloader",
    "verified_boot_state": "ro.boot.verifiedbootstate",
    "flash_locked": "ro.boot.flash.locked",
    "slot_suffix": "ro.boot.slot_suffix",
    "vndk_version": "ro.vndk.version",
}
FASTBOOT_VARIABLES = (
    "product", "unlocked", "secure", "current-slot", "has-slot:boot",
    "partition-size:boot", "partition-size:vendor_boot",
    "partition-size:recovery", "partition-size:dtbo",
    "partition-size:vbmeta", "partition-size:super",
)


def run(command: list[str], timeout: int = 12) -> tuple[int, str]:
    try:
        completed = subprocess.run(
            command, capture_output=True, text=True, timeout=timeout, check=False
        )
        output = "\n".join(
            part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
        )
        return completed.returncode, output
    except subprocess.TimeoutExpired:
        return 124, "command timed out"
    except OSError as exc:
        return 127, str(exc)


def parse_devices(output: str) -> list[dict[str, str]]:
    devices: list[dict[str, str]] = []
    for line in output.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        # Never persist the USB serial; keep only state and safe hardware hints.
        entry = {"state": parts[1]}
        for part in parts[2:]:
            if ":" in part:
                key, value = part.split(":", 1)
                if key in {"product", "model", "device"}:
                    entry[key] = value
        devices.append(entry)
    return devices


def getprop(adb: str, prop: str) -> str:
    if prop == "kernel_release":
        code, output = run([adb, "shell", "uname", "-r"])
    else:
        code, output = run([adb, "shell", "getprop", prop])
    return output if code == 0 else ""


def fastboot_value(fastboot: str, variable: str) -> dict[str, Any]:
    code, output = run([fastboot, "getvar", variable])
    return {"value": output, "exit_code": code}


def collect() -> dict[str, Any]:
    adb = shutil.which("adb")
    fastboot = shutil.which("fastboot")
    report: dict[str, Any] = {
        "schema_version": 1,
        "collected_at_utc": datetime.now(timezone.utc).isoformat(),
        "privacy": "USB serials and personal/account data are not collected.",
        "tools": {"adb_available": bool(adb), "fastboot_available": bool(fastboot)},
        "adb": {"state": "unavailable", "properties": {}, "partition_links": ""},
        "fastboot": {"state": "unavailable", "variables": {}},
        "notes": [],
    }

    if adb:
        code, output = run([adb, "devices", "-l"])
        devices = parse_devices(output)
        report["adb"]["devices"] = devices
        online = any(device["state"] == "device" for device in devices)
        if code == 0 and online:
            report["adb"]["state"] = "connected"
            report["adb"]["properties"] = {
                name: getprop(adb, prop) for name, prop in ADB_PROPERTIES.items()
            }
            _, links = run([adb, "shell", "ls", "-l", "/dev/block/by-name"])
            report["adb"]["partition_links"] = links
            _, meminfo = run([adb, "shell", "cat", "/proc/meminfo"])
            report["adb"]["memory_info"] = meminfo
        elif devices:
            report["adb"]["state"] = "device_not_authorized_or_offline"
            report["notes"].append("Unlock the phone and approve the USB debugging prompt.")
        else:
            report["adb"]["state"] = "no_device"
    else:
        report["notes"].append("Install Android platform-tools and ensure adb is on PATH.")

    if fastboot:
        code, output = run([fastboot, "devices"])
        # fastboot devices prints serials; only retain whether a device is present.
        present = any(line.strip() for line in output.splitlines())
        if code == 0 and present:
            report["fastboot"]["state"] = "connected"
            report["fastboot"]["variables"] = {
                variable: fastboot_value(fastboot, variable)
                for variable in FASTBOOT_VARIABLES
            }
        else:
            report["fastboot"]["state"] = "no_device"
    else:
        report["notes"].append("fastboot is unavailable; bootloader variables were skipped.")

    if report["adb"]["state"] != "connected" and report["fastboot"]["state"] != "connected":
        report["notes"].append(
            "No readable device detected. Connect Android with USB debugging enabled, "
            "or run while the phone is already in fastboot mode. The tool will not reboot it."
        )
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-o", "--output", type=Path,
        help="Output JSON path (default: timestamped nexus-device-inventory file)",
    )
    args = parser.parse_args()
    report = collect()
    output_path = args.output or Path(
        "nexus-device-inventory-" + datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S") + ".json"
    )
    output_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"Inventory written: {output_path.resolve()}")
    print(f"ADB: {report['adb']['state']} | fastboot: {report['fastboot']['state']}")
    print("Read-only mode: no reboot, unlock, flash, erase, or partition writes were performed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
