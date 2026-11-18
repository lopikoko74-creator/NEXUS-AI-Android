# BG7n online research update

Research date: 2026-11-18
Target: TECNO Spark 20C BG7n, owner-reported RU build BG7n-XE674SGHO-T-RU-260121V1768.

## New, directly inspected GitHub finding: ROM filesystem dump

Repository: https://github.com/andrejkuroglo8/tecno-bg7n_dump
Description: "ROM Dump from Tecno Spark 20c"
Default branch: `lineage-20.0`
Latest inspected commit: `5b56890d8ab7d1fb6d61fe4d0bdc48b68725f7bc` (2026-09-16; unsigned).
Recursive tree: 7,818 files.

This is the strongest newly found public BG7n material so far. It contains an extracted ROM filesystem, including system/product/vendor trees and binary components, plus `boot.img`, `dtbo.img`, and the extracted boot kernel. It is useful for identifying proprietary blobs, HALs, permissions, init scripts, firmware layout and build properties. It is NOT kernel source and is not a complete reproducible source tree.

Directly inspected `product/etc/build.prop`:
- model: TECNO BG7n
- product default device name: TECNO SPARK 20C
- product fingerprint: `TECNO/BG7n-RU/TECNO-BG7n:13/TP1A.220624.014/250908V1763:user/release-keys`
- product version: `BG7n-XE674SGHO-T-RU-250908V1763`
- Android release: 13, SDK 33, VNDK 33
- build date: 2025-09-08

Directly inspected `system/system/build.prop` also reports Android 13 / SDK 33 and a system build from 2025-09-08. It identifies the system image as a generic Transsion TSSI 64-bit system. This is strong evidence the dump is from a BG7n Android 13 software family, but it is NOT the exact owner-reported RU build V1768 (it is V1763). It must not be flashed as the owner's stock image.

Boot metadata in `boot/info.txt`:
- header version 4
- AOSP image type
- AVBv2
- OS version field 12.0.0 (do not interpret this alone as Android release; build.prop says Android 13)
- original boot image size 33,554,432 bytes
The repository's root contains a kernel binary, not kernel source. No source defconfig or matching kernel source commit was found in this repository inspection.

## New Android 13-era recovery device-tree lead

Repository: https://github.com/andrejkuroglo8/TECNO-BG7n_twrp
Branch: `a13`
README identifies it as a TWRP device tree for TECNO Spark 20C / TECNO-BG7n.
BoardConfig directly inspected:
- arm64-v8a, runtime CPU variant cortex-a53
- MT6765 platform
- screen 720x1612, density 320
- boot header v4
- separate DTBO
- prebuilt DTB
- `TARGET_NO_KERNEL := true` (does not build kernel)
- dynamic super partition configured with system, system_ext, vendor and product
- AVB enabled
- TWRP-specific configuration

This is a valuable Android 13-era recovery configuration and device-layout reference, but it is still a recovery tree, not a complete LineageOS/AOSP ROM device tree. Its `device.mk` contains VNDK 31 / shipping API 31 settings, so its vendor compatibility must be reconciled against the VNDK 33 Android 13 dump before use. README says USB-OTG input devices (keyboard/mouse/disks) are not supported; this is distinct from USB gadget networking or ADB tethering and does not settle phone-to-PC USB control feasibility.

A second generated recovery tree exists:
https://github.com/naden01/TECNO-BG7n_twrp
It is another TWRP tree and not evidence of a full Android 13 ROM source.

## Exact owner build firmware archive found in public listings

Several third-party firmware index pages list:
`BG7n-XE674SGHO-T-RU-260121V1768.zip`, about 3.53 GB:
- FileWale listing: https://filewale.com/folders/tecno-spark-20c-bg7n/15906
- HalabTech listing: https://support.halabtech.com/index.php?a=downloads&b=file&id=1048013
- FirmwareUpdate24 listing: https://firmwareupdate24.com/index.php?a=downloads&b=file&id=62278

These are third-party service/firmware listings, not verified official TECNO distribution. The archive bytes, authenticity, SHA-256, scatter/partition map, and exact contents have NOT been downloaded or validated in this research. Treat as leads only; do not flash or trust until independently verified.

## Current engineering conclusion

The previous statement "no BG7n Android 13 material exists" would be too broad. There is now:
1. A large BG7n Android 13 filesystem/ROM dump for V1763 with vendor/product/system binaries and boot artifacts.
2. An Android 13-era TWRP device tree for BG7n.
3. Public listings for the exact V1768 Russian firmware archive.

Still missing for a safe reproducible custom OS build:
- the exact V1768 archive downloaded and SHA-256 verified from a trusted source;
- full partition images and metadata from that exact build, especially vendor/vendor_boot, vbmeta, super metadata and scatter/partition definitions;
- a matching BG7n kernel source tree, exact kernel release and defconfig (not merely a prebuilt kernel);
- a proper AOSP/LineageOS product/device tree with proprietary-files extraction manifest;
- validation of bootloader unlock state, AVB/rollback behavior and a tested restore path on the physical device.

## USB / boot-selection architecture correction

The phone cannot simply display a remote boot menu over a normal USB-A cable while the PC is still in BIOS/UEFI unless the PC firmware or a pre-boot service provides a communication endpoint. A more robust design is:
- PC boots a small local NEXUS boot chooser (GRUB/systemd-boot or custom UEFI application);
- chooser exposes a supported preboot input channel, or accepts a USB HID keyboard emulation device;
- Android phone USB gadget mode / HID support must be validated on BG7n; do not assume it works from the recovery USB-OTG notes;
- simpler reliable fallback: the phone selects the default OS remotely after a small always-on management service has booted, or the user uses a physical/remote HID input at boot.

The USB-A 3.0 connector on the PC does not itself make a USB connection available before OS startup. The phone's USB role and host/gadget capabilities determine the feasible direction.

## Work completed in this research pass

- Searched GitHub repository metadata for BG7n, kernel and device-tree sources.
- Inspected the BG7n dump tree, build properties and boot metadata.
- Inspected the A13 TWRP BoardConfig and device.mk.
- Searched the public web for the exact V1768 firmware identifier and found three third-party index listings.
- No files were downloaded, no images were built, and no device was flashed.
