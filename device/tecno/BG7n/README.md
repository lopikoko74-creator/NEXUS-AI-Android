# TECNO Spark 20C BG7n — Device Notes

## User-provided baseline
- Model: BG7n
- SoC: MediaTek MT6765
- Android: 13
- Build: BG7n-XE674SGHO-T-RU-260121V1768
- Fingerprint: TECNO/BG7n-RU/TECNO-BG7n:13/TP1A.220624.014/260121V1768:user/release-keys
- ABI: arm64-v8a
- RAM: 4 GB nominal (+4 GB Memory Fusion reported; not physical RAM)
- Bootloader: user previously reported unlocked; must verify by read-only fastboot state query before any write.

## Known public leads (not validated)
- A device-tree lead named android_device_tecno_TECNO-BG7n was published for a BG7n Android 12 build. It must be inspected and adapted/validated against the user's Android 13 vendor build.
- A Transsion MT6765 common-tree repository is another lead, but its README mentions conflicting device families; inspect before use.
- Public BG7 firmware indexes contain MT6765 packages. They are not interchangeable with the exact BG7n regional build.

## Required read-only inventory before porting
1. Exact stock firmware archive and SHA-256.
2. Partition table and dynamic-partition metadata.
3. boot/recovery/dtbo/vbmeta/vendor_boot images as applicable.
4. Kernel release string and available kernel source commit.
5. Vendor manifest, proprietary blob list, VINTF compatibility matrix.
6. AVB state, slot layout, fastboot variables, and verified boot state.
7. Display panel/touch controller, USB controller role, audio, modem, Wi-Fi, Bluetooth, sensors, camera, thermal and power HAL dependencies.

## Safety gate
No partition writes until the exact image-to-partition mapping, matching stock restore package, and recovery procedure have been independently checked. Never flash an image built for another MT6765 handset.
