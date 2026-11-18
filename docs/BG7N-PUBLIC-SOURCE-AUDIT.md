# BG7n public-source audit

Audit date: 2026-11-18

Target reported by the owner: TECNO Spark 20C, model BG7n, MediaTek MT6765, Android 13 build `BG7n-XE674SGHO-T-RU-260121V1768`, arm64-v8a, 4 GB physical RAM. These are historical owner-provided values and must be confirmed from the device before any image is built or flashed.

## Confirmed public GitHub leads

### 1. TeamWin device tree
- Repository: https://github.com/TeamWin/android_device_tecno_BG7n
- Branch: `android-12.1`
- This is a real BG7n TWRP device tree. Its README lists working recovery touch/display, ADB, backup/restore, MTP, reboot-to-bootloader and other recovery functions.
- BoardConfig identifies MT6765, arm64-v8a, 720x1612, a prebuilt DTB, boot header v4, dynamic partitions, AVB and `TARGET_NO_KERNEL := true`.
- Critical limitation: this is a recovery/device tree, not a complete AOSP product/device port. It explicitly does not build the kernel and uses a prebuilt DTB. It targets an Android 12.1-era vendor baseline (shipping API 31 / VNDK 31 in device.mk), not the owner's reported Android 13 build. It is a source of partition/device clues only until compatibility is proven.
- README reports USB-OTG keyboard/mouse/storage input as not supported by the device. This must be investigated against the actual USB role and hardware; it does not by itself prove that USB gadget/ADB communication to a PC is unavailable.

### 2. Generated BG7n recovery tree
- Repository: https://github.com/twrpdtgen/android_device_tecno_TECNO-BG7n
- Default branch: `vnd_bg7n_xe674s-user-12-SP1A.210812.016-584496-release-keys`
- Contains a generated recovery device tree, prebuilt kernel and DTB. Treat as a second recovery reference, not as Android 13 kernel source or a validated ROM tree.

### 3. Fork of generated recovery tree
- Repository: https://github.com/NotGlitchy32/android_device_tecno_bg7n
- Contains substantially the same generated recovery-tree layout. It is not independent proof of Android 13 compatibility.

## Kernel-source search result

GitHub repository search for `android_kernel_mediatek_mt6765g` finds multiple MT6765G kernel repositories, including:
- https://github.com/dandelion64-Archives/android_kernel_mediatek_mt6765g (branch android-11.0)
- https://github.com/Cr0699123/android_kernel_mediatek_mt6765g (branch android-11.0)
- https://github.com/dhamar19/android_kernel_mediatek_mt6765g (branch android-11.0)

These are generic/other-device MT6765G kernel candidates. No evidence found in this audit ties them to TECNO BG7n, its exact kernel release, its Android 13 vendor build, or its defconfig. Do not use them to build or flash BG7n images without matching kernel release/config/device source evidence.

A public MT6765 vendor-kernel-modules repository also exists:
- https://github.com/OnePlusOSS/android_vendor_mediatek_kernel_modules_mt6765
It is for OnePlus Nord N20 SE / Android 12.1 and is not a BG7n vendor-binary source.

## What is still missing for a genuine BG7n Android 13 system build

1. Exact stock firmware archive for build `BG7n-XE674SGHO-T-RU-260121V1768`, with trusted origin and SHA-256.
2. Exact matching kernel source (or verifiable source commit) and kernel defconfig/config matching the running BG7n kernel.
3. Matching Android 13 vendor blobs and proprietary-files manifest from the exact firmware.
4. Verified partition map, super metadata, boot/vendor_boot/dtbo/vbmeta layout, AVB and slot details from the actual device/firmware.
5. Confirmed bootloader state and a tested restore path.
6. Device validation of display/touch, modem, Wi-Fi, Bluetooth, camera, audio, sensors, thermal, charging and USB gadget behavior.

## Project correction

The existing `NEXUS-AI-Android` repository is currently an Android app project whose README targets POCO F6. Its CI builds an APK. The BG7n folder and NEXUS Control roadmap are research documentation only. No NEXUS OS image, BG7n recovery image, kernel, or firmware has been built or tested by this audit.

## Safe next steps

- Continue source discovery and compare all candidate device trees with the exact stock build.
- Obtain/extract firmware and read-only device inventory before choosing a build base.
- Keep the current phone untouched; no exploratory flashing, vbmeta changes, userdata wipes, or cross-device MT6765 images.
- First technical milestone should be a reproducible read-only firmware/partition inventory and a verified recovery/restore plan, not a speculative ROM flash.
