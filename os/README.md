# NEXUS OS — bring-up foundation

NEXUS OS is the long-term goal: a landscape-first control-terminal system for the TECNO Spark 20C (BG7n), paired with a Windows 10 / Ubuntu PC. This directory begins the **device OS engineering track**, separate from the existing NEXUS Android AI application and PC dashboard.

## Current truth

This repository does **not** yet contain a bootable NEXUS OS image. The BG7n source audit found useful Android 13 filesystem and recovery-tree references, but no verified BG7n-matched kernel source, complete product/device tree, or reproducible vendor extraction set. A generic MT6765 image is not a safe substitute.

The first executable milestone is a read-only hardware/firmware inventory. It records what the actual phone reports over ADB and, when the phone is already in the bootloader, a small allowlist of read-only fastboot variables. It never unlocks, flashes, erases, formats, reboots, or changes device settings.

## Target baseline to verify

- TECNO Spark 20C, model BG7n
- MediaTek MT6765, arm64
- Owner-reported Android 13 build: BG7n-XE674SGHO-T-RU-260121V1768
- Nominal 4 GB physical RAM

These are repository research notes, not verified live-device facts. The inventory output is the source of truth for the connected handset.

## First milestone: collect inventory

Requirements: Python 3.10+, Android platform-tools (adb, fastboot).

1. Enable Developer options → USB debugging on the phone and approve this PC.
2. Connect by USB and run:

   ```powershell
   py os/tools/collect_device_inventory.py
   ```

   Or on Ubuntu:

   ```bash
   python3 os/tools/collect_device_inventory.py
   ```

3. The script writes a timestamped JSON report in the current directory. Review it before sharing; it intentionally omits USB serial numbers and account/user data.
4. If the phone is already in fastboot mode, run the same script again. It will query only a fixed list of read-only variables. It will not reboot the phone into fastboot.

## Work sequence

1. **Inventory** — confirm exact build, ABI, kernel release, AVB state, slot layout, partition names and available stock restore package.
2. **Source matching** — obtain exact BG7n Android 13 kernel source and validate the device tree against the live device and stock firmware.
3. **Vendor extraction** — extract proprietary blobs from a verified matching stock package; generate a manifest and VINTF checks.
4. **Build system** — pin an AOSP/LineageOS base, manifests, toolchain and reproducible build scripts.
5. **Boot validation** — build and test a recovery/engineering image before any system image. Keep a verified stock restore path.
6. **NEXUS product layer** — boot animation, system UI/launcher, landscape control-terminal UX, PC connection service and signed OTA strategy.
7. **Hardware validation** — display, touch, audio, cameras, modem, Wi-Fi, Bluetooth, sensors, thermal, charging, suspend and USB behavior.

## Hard safety gates

- Never flash a generic MT6765/MT6765G image onto BG7n.
- Never modify boot, vendor, vbmeta, super, recovery or userdata while the exact partition map and matching restore procedure are unknown.
- Never assume an Android 12 recovery tree is compatible with the Android 13 vendor build.
- Do not publish an image as installable until it has booted and passed hardware tests on the exact target device.
- Full AOSP builds require substantial disk, RAM and CPU resources; the user's previously described Athlon II X4 / 7.2 GB RAM PC is not a realistic full-build host. Use a suitably provisioned Linux build machine or CI runner.

## Directory map

- os/tools/collect_device_inventory.py — safe read-only ADB/fastboot inventory.
- os/device/BG7n/PORTING_STATUS.md — current porting blockers and acceptance checklist.
- os/docs/ — build and validation notes will live here as the port matures.

No firmware image is included at this stage.