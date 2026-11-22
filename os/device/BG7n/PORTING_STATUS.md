# BG7n NEXUS OS port status

**Status: source discovery / pre-build. No flashable image exists.**

## Known leads

- Android 13 BG7n filesystem dump exists for build V1763, not the owner's reported V1768. It contains vendor/product/system binaries and boot artifacts, not kernel source.
- Android 13-era TWRP device-tree lead exists, but it is recovery-specific and contains compatibility settings that require reconciliation.
- Older BG7n recovery trees target Android 12.1 and are not a drop-in Android 13 ROM base.
- Generic MT6765 kernel repositories are not proven to match BG7n.

## Blockers before first build

- [ ] Run os/tools/collect_device_inventory.py on the actual handset.
- [ ] Obtain the exact V1768 stock firmware from a trusted source and verify SHA-256.
- [ ] Compare stock partition metadata against live /dev/block/by-name and read-only fastboot output.
- [ ] Identify exact kernel release, source repository, commit and defconfig.
- [ ] Validate Android 13 VNDK/VINTF compatibility for the selected device tree.
- [ ] Generate proprietary blob extraction manifest from the exact stock build.
- [ ] Document a tested stock restore procedure and confirm bootloader/AVB state.
- [ ] Pin AOSP/LineageOS base and reproducible manifest revisions.

## Hardware acceptance matrix

| Component | Required validation | Status |
|---|---|---|
| Boot / recovery | cold boot, recovery, reboot, restore | Not tested |
| Display / touch | panel init, brightness, multi-touch | Not tested |
| USB | ADB, charging, host/device role, reconnect | Not tested |
| Radio | SIM, calls, mobile data, emergency behavior | Not tested |
| Wi-Fi / Bluetooth | connect, reconnect, throughput | Not tested |
| Audio | speaker, earpiece, mic, headset | Not tested |
| Camera | front/rear, video, flash | Not tested |
| Sensors | proximity, light, accelerometer, gyro | Not tested |
| Power / thermal | charging, suspend, idle drain, thermal limits | Not tested |

## Release gate

A build may be called an installable NEXUS OS preview only after it boots on the exact BG7n hardware, the core hardware matrix is documented, and the matching stock restore package plus checksums are published. Until then, label artifacts as engineering-only and non-flashable.