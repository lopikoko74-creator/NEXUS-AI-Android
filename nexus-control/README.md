# NEXUS CONTROL — PC dashboard

Cross-platform local PC dashboard for Windows 10 and Ubuntu, designed for an Android phone mounted in landscape orientation and connected by USB.

## Implemented

- Python PC agent for Windows and Linux.
- Live hostname, OS, CPU load, RAM, disk free space, uptime, architecture and process list (sorted by resident memory).
- Authenticated local HTTP API; agent binds only to 127.0.0.1.
- Lock, shutdown, restart and Windows shutdown cancellation, with confirmation in the dashboard.
- Mouse touchpad (relative movement, left/right/middle click, scroll).
- Keyboard text entry and a strict shortcut allowlist (Alt+Tab, Win+D, Ctrl+C/V/Z/S, Ctrl+Shift+Esc, Alt+F4, Enter, Escape).
- On-demand desktop screenshot.
- System volume control (Windows via pycaw; Linux via pactl/amixer).
- Safe process termination for processes owned by the current logged-in user only; protected/system and other-user processes are rejected.
- Android USB access through ADB reverse: phone browser -> 127.0.0.1:8765 -> USB ADB tunnel -> PC agent.
- Linux systemd user service installer and Windows logon scheduled-task installer.
- GRUB dual-boot menu template for Windows 10 and Ubuntu.
- GitHub Actions syntax/unit tests and downloadable PC install ZIP artifact.

## Security

The agent binds ONLY to 127.0.0.1, not the LAN or internet. The phone reaches it over an explicitly authorized ADB USB connection using `adb reverse tcp:8765 tcp:8765`. API requests require a random bearer token stored in `~/.nexus-control-token` (Linux) or `%USERPROFILE%\.nexus-control-token` (Windows). Do not share this token.

Power actions are allowlisted and confirmed in the UI. Input endpoints accept only specific mouse/keyboard operations; arbitrary shell execution is not exposed. Keep USB debugging authorized only on devices you trust.

## Install on Ubuntu

1. Install Python 3, venv support and Android platform-tools (adb).
2. From repository root run `bash nexus-control/install/linux/install.sh`.
3. Enable USB debugging on Android and authorize the PC.
4. Connect the phone and run `bash nexus-control/install/usb/connect-android.sh`.
5. Read the token with `cat ~/.nexus-control-token`.
6. Open `http://127.0.0.1:8765` in the phone browser and enter the token.

## Install on Windows 10

1. Install Python 3 and Google's Android platform-tools (adb).
2. Run PowerShell as the logged-in user: `powershell -ExecutionPolicy Bypass -File nexus-control/install/windows/install.ps1`.
3. Enable Android USB debugging and authorize the PC.
4. Run `powershell -ExecutionPolicy Bypass -File nexus-control/install/usb/connect-android.ps1`.
5. Read the token from `%USERPROFILE%\.nexus-control-token`.
6. Open `http://127.0.0.1:8765` in the phone browser and enter the token.

## Boot selection

The GRUB file in `boot/grub-nexus.cfg` is a template only. A working dual-boot menu must use the actual EFI partition layout and bootloader entries from the target PC. It is deliberately not auto-installed because an incorrect chainloader path can make both systems harder to boot. Once installed correctly, GRUB shows Windows 10 and Ubuntu on the PC monitor before either OS starts.

The phone cannot receive a menu from ordinary PC USB-A while PC firmware is running: USB-A 3.0 on the PC is a host port, and Android normally presents itself as a USB device. The practical USB design here is ADB reverse after Android has booted and authorized USB debugging. Selecting the next OS from the phone before boot would require a separate UEFI/firmware communication endpoint or supported USB HID gadget firmware on the phone; this is not assumed.

## Current limitations

- No continuous remote video stream yet; screenshot is on demand.
- File transfer and launching applications are not implemented yet. Volume control depends on pycaw (Windows) or pactl/amixer (Linux).
- Mouse/keyboard automation requires an interactive desktop session; Linux Wayland restrictions may prevent global input injection. X11 is the expected Linux desktop backend.
- Detailed metrics/process list require psutil.
- This package has passed GitHub CI syntax and unit tests, but has not been installed or hardware-tested on the user's PC/phone in this environment.
