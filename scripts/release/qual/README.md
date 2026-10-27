# Clean release test VMs

Plain QEMU/KVM; one guest at a time, eight vCPUs pinned to eight allowed host
threads, 6144 MiB RAM. No libvirt, guest agent or AI login. Requires existing
`qemu-system-x86_64`, `qemu-img`, `taskset`, `ssh`, `scp`, `ssh-keygen`,
`openssl`, `xorriso`, `genisoimage`, KVM access and `/usr/share/OVMF/*_4M.fd`.
Python code uses the standard library. No host package installs or sudo.

Run from this directory (or use absolute script paths):

```sh
python3 vm.py build-base ubuntu
python3 vm.py build-base windows
python3 vm.py start ubuntu
python3 vm.py ssh ubuntu 'uname -a'
python3 vm.py copy-in ubuntu ./README.md /home/qual/README.md
python3 screen.py shot ubuntu /home/elias/dev/justsearch-qual/evidence/desktop.png
python3 screen.py click ubuntu 600 400
python3 screen.py move ubuntu 700 400
python3 screen.py key ubuntu ctrl+alt+t
python3 screen.py type ubuntu 'echo Hello'
python3 screen.py key ubuntu enter
python3 vm.py status
python3 vm.py stop
python3 vm.py reset
python3 vm.py start windows
python3 vm.py ssh windows 'ver'
python3 screen.py key windows win+r
python3 screen.py type windows 'notepad'
python3 screen.py key windows enter
python3 vm.py stop
```

`start` returns when QMP is ready; SSH becomes available after the guest boots.
Every `start` discards that OS's previous run overlay and firmware variables.
`reset` stops the guest and removes both OS run overlays; the next `start`
boots clean. Files inside the base, including guest SSH host keys, are retained.
Never edit a base while an overlay uses it. Bases are sealed only after an
installed-disk boot passes the SSH readiness check and shuts down cleanly. Builds refuse
to replace existing bases. To rebuild, first stop the VM and explicitly move its
OS folder aside. A failed build keeps `install.qcow2`, firmware, logs and seed
media for diagnosis. A retry creates a fresh installation disk.

`start <os> --visible` uses a software QEMU GTK window on the current `DISPLAY`;
headless is the default. Stop it with `vm.py stop`. For a brief visible check,
keep the window open no more than ten seconds while the owner uses the desktop.

Paths and loopback ports are defined in `vm.py`: data at
`/home/elias/dev/justsearch-qual`, Ubuntu SSH/QMP 22224/44424,
Windows SSH/QMP 22211/44411. Config, password, SSH private key, per-OS known
hosts, installer media and all large files live there, outside Git. `config.json`
is created with a random local test password (mode 0600); both accounts are
`qual`. SSH uses that directory's dedicated key and host-key records.
QMP is unauthenticated and bound to 127.0.0.1 only, as are SSH forwards.
QMP screenshots and keyboard/mouse injection work without guest software.
`type` supports printable US ASCII, newline and tab; other characters fail
before typing. `key` accepts QEMU qcodes joined with `+`, plus aliases such as
`win`, `enter`, `escape` and `ctrl`.

Ubuntu is the official Ubuntu 24.04.5.1 LTS **Desktop** ISO, pinned to its
[release SHA256SUMS](https://releases.ubuntu.com/24.04/SHA256SUMS).
Autoinstall supplies the account, public key, OpenSSH server, and automatic GDM
login. Both stock Ubuntu Wayland and Ubuntu on Xorg sessions are retained.
APT periodic upgrades and unattended-upgrades are disabled for testing.

Windows is the official Windows 11 Enterprise evaluation, English US x64,
currently version 26H2. The [official download page](https://www.microsoft.com/en-us/evalcenter/download-windows-11-enterprise)
provides a direct ISO link without submitting a registration form. Its pinned
SHA256 is from [Microsoft's authenticity page](https://aka.ms/VerifyWindowsISO).
No personal information is submitted. The XML uses a local test administrator,
no product key, automatic login, and LabConfig TPM/Secure Boot check bypasses.
First logon installs the inbox OpenSSH Server feature from Microsoft, authorizes
the dedicated host key, then disables Windows Update and scheduled Defender
scans for tests. Real-time Defender protection remains enabled. These bases are
for disposable qualification runs; the Microsoft evaluation expires after 90 days.

Build durations and disk sizes are written to `<os>/base.json`.
`status` reports the running PID, CPU affinity, current RSS and kernel peak RSS.
Stopped-run resource measurements are saved in `evidence/resources.jsonl`.
Evidence and installation logs live in the data directory. Acceptance results,
including the two Ubuntu session types and the visible check, are recorded there.

On-machine validation (2026-10-27): Ubuntu passed the requested checks, including
Wayland/Xorg, reset and a brief visible window. Windows installed unattended and
passed initial SSH readiness, but its subsequent boot without installer media
failed the ten-minute SSH readiness check. No Windows base was sealed; its
remaining overlay/reset/visible checks are unverified. Full results and PNGs:
`/home/elias/dev/justsearch-qual/evidence/results.md`.
