# Installers

Use the platform-specific installer in this directory. These installers configure the PC agent only; they do not modify boot partitions or install GRUB. After installation, pair the phone through Android USB debugging and ADB reverse as described in ../README.md.

The Windows installer creates a per-user logon scheduled task. The Ubuntu installer creates a systemd user service. Both agents bind to loopback only.
