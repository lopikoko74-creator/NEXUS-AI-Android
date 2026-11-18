# NEXUS CONTROL OS — Engineering Roadmap

## Goal
Build a landscape-oriented, always-on control-terminal operating environment for TECNO Spark 20C (BG7n), connected to a dual-boot PC (Windows 10 + Ubuntu) over a physical USB-A 3.0 host port.

This is a staged engineering project. The first deliverable is a validated Android/AOSP-derived control-terminal system image for BG7n; a true replacement OS must not be claimed until device-specific boot, kernel, vendor, recovery, and hardware support are proven.

## Product behavior
- Boot directly to the NEXUS Control interface in landscape orientation.
- When the PC is off or unavailable, show a clear disconnected/standby state.
- When the PC pre-boot service is available, show Windows 10 and Ubuntu choices.
- After OS startup, connect to a privileged, authenticated PC agent over USB and expose:
  - pointer/touchpad and keyboard;
  - volume and media controls;
  - launch/close approved applications;
  - lock, sleep, restart, and shutdown with confirmation;
  - system telemetry and screenshot/desktop streaming where supported.
- The PC-side boot selector must be independent of Windows and Ubuntu. The phone cannot directly change GRUB merely by being plugged into USB. A separate pre-boot environment/firmware-side receiver is required, and the exact PC motherboard/firmware capabilities must be established.

## Device baseline (user-supplied historical data; verify before flashing)
- Device: TECNO Spark 20C, model/codename BG7n
- SoC: MediaTek MT6765
- OS/build: Android 13, build BG7n-XE674SGHO-T-RU-260121V1768
- ABI: arm64-v8a
- RAM: 4 GB nominal; Memory Fusion is storage-backed and is not physical RAM
- User previously reported bootloader unlocked; this state has not been independently verified in this repository.
- Stock firmware package was previously reported as approximately 3.53 GB; exact archive and checksum are not currently stored here.

## Research findings
- A public device-tree lead exists for TECNO-BG7n, generated for an Android 12 vendor build. It is a lead for inspection, not proof of Android 13 compatibility.
- A public MT6765 Transsion common-tree lead exists. Its repository description and board configuration must be inspected before reuse; do not assume it targets BG7n.
- Public firmware indexes list BG7/MT6765 stock packages. Use only an exact BG7n build match and verify checksums before extracting or flashing.
- No verified, complete BG7n Android 13 kernel source + vendor blobs + working AOSP device configuration has yet been established.

## Work packages
1. Preserve the exact stock firmware and extract partition map, boot/vendor_boot (if present), dtbo, vbmeta, super/dynamic partitions, recovery, and vendor files.
2. Inspect bootloader state, partition layout, AVB, slot scheme, kernel config, and device-tree compatibility. No flash commands until this inventory is complete.
3. Locate and validate the exact kernel source and proprietary vendor components for BG7n / Android 13.
4. Build a reproducible device configuration and boot-test a minimal recovery/engineering image.
5. Build the NEXUS Control UI and system services, then validate display, touch, USB host/device role, charging, audio, thermal, suspend, and reboot.
6. Implement the PC agent for Windows and Ubuntu, then a separately tested pre-boot selection path.
7. Produce signed/testable images, checksums, recovery instructions, and a release only after repeatable device tests.

## Critical constraints
- Do not flash a generic MT6765 image or an Android 12 tree blindly onto the Android 13 BG7n.
- Do not erase userdata, vbmeta, boot, or vendor partitions as an exploratory step.
- AOSP source builds are too large for the user's previously described desktop (Athlon II X4 640, 7.2 GB RAM). Source development and cloud CI can be prepared, but a local full AOSP build is not realistic on that machine.
- USB-A 3.0 is only the PC connector type; the phone's USB role, OTG behavior, cable, and pre-boot enumeration must be tested. Normal ADB/USB control after Android boots does not solve pre-boot communication.

## Acceptance criteria
- Phone boots reliably to NEXUS Control in landscape.
- USB link survives PC reboot and is detected by the phone-side service.
- OS selection is received and acknowledged by the PC pre-boot component before the OS starts.
- Windows and Ubuntu both boot correctly after selection.
- Authenticated control works after boot, with confirmations for destructive/power actions.
- A tested stock recovery path and matching checksums are published with each image.

## Status
Planning and source discovery only. No BG7n image has been built, flashed, or tested yet.
