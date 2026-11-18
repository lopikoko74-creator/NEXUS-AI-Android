# NEXUS CONTROL — PC dashboard

A local, cross-platform control panel for a Windows 10 / Ubuntu PC, designed for an Android phone mounted in landscape orientation and connected by USB.

## Current implementation

- Python PC agent for Windows and Linux.
- Live hostname, OS, CPU load, RAM, disk free space, uptime, architecture and process list (requires psutil).
- Authenticated local HTTP API.
- Lock, shutdown and restart actions with confirmation in the phone dashboard.
- Android USB access through ADB reverse: phone browser -> 127.0.0.1:8765 -> USB ADB tunnel -> PC agent.
- Linux systemd user service installer and Windows logon scheduled-task installer.
- GRUB menu template for Windows 10 and Ubuntu.

## Security model

The agent binds ONLY to 127.0.0.1. It is not exposed to the LAN or internet. The phone reaches it over an explicitly authorized ADB USB connection using `adb reverse tcp:8765 tcp:8765`. API requests require a random bearer token stored in `~/.nexus-control-token` (Linux) or `%USERPROFILE%\.nexus-control-token` (Windows). Do not share this token.

Power actions are allowlisted. No arbitrary shell endpoint exists. The dashboard asks for confirmation before shutdown/restart.

## Install on Ubuntu

1. Install Python 3, venv support and Android platform-tools (adb).
2. From the repository root run `bash nexus-control/install/linux/install.sh`.
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

The GRUB file in `boot/grub-nexus.cfg` is a template only. A safe, working dual-boot menu must use the actual EFI partition layout and bootloader entries from the target PC. It is deliberately not auto-installed because a wrong chainloader path can make both systems harder to boot. Once installed correctly, GRUB shows Windows 10 and Ubuntu on the PC monitor before either OS starts.

The phone cannot receive a menu from ordinary PC USB-A while the PC firmware is still running: USB-A 3.0 on the PC is a host port, and Android normally presents itself as a USB device. The practical USB design here is ADB reverse after Android has booted and authorized USB debugging. Selecting the next OS from the phone before boot would require a separate UEFI/firmware communication endpoint or a supported USB HID gadget implementation on the phone; that is not assumed.

## Known limitations in v0.1

- Volume, mouse, keyboard injection, screen streaming, app launch/kill and file transfer are not implemented yet.
- Process list and detailed metrics require psutil.
- The GRUB file must be adapted to the actual PC; no automatic disk changes are made.
- The agent has not been installed or tested on the user's physical PC or phone from this cloud environment.
