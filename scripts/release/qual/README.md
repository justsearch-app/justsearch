# Clean release test VMs

Plain QEMU/KVM; one guest at a time, eight vCPUs pinned to eight allowed host
threads, 6144 MiB RAM. No libvirt, guest agent or AI login. Requires existing
`qemu-system-x86_64`, `qemu-img`, `taskset`, `ssh`, `scp`, `ssh-keygen`,
`openssl`, `xorriso`, `genisoimage`, KVM access and `/usr/share/OVMF/*_4M.fd`.
Python code uses the standard library. No host package installs or sudo.

Run from this directory (or use absolute script paths):

```sh
export QUAL_OWNER=my-release-session
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
`reset` stops the caller's guest and removes both OS run overlays; the next `start`
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
`status` reports ownership, liveness signals, and the visible PID's CPU affinity and kernel peak RSS.
Stopped-run resource measurements are saved in `evidence/resources.jsonl`.
Evidence and installation logs live in the data directory. Acceptance results,
including the two Ubuntu session types and the visible check, are recorded there.

On-machine validation (2026-10-27): both bases built unattended and passed the
requested SSH, desktop/input, copy-in, reset and CPU-cap checks. Ubuntu's original
Wayland/Xorg and visible checks remain recorded; its fresh-overlay SSH and desktop
were checked again. Windows' fresh build took 1142.7 seconds and produced a
22,015,508,480-byte qcow2. Its visible window was confirmed on `DISPLAY=:0` and
closed within 0.366 seconds.

An earlier Windows installed-disk boot stalled. The preserved image subsequently
booted with the original CPU/disk settings, including after clean shutdown and
installer-media removal; a new build also passed its first installed-disk boot.
The stall's cause remains unconfirmed. No CPU flags, topology or disk-bus changes
were made. Full results, logs and PNGs:
`/home/elias/dev/justsearch-qual/evidence/results.md`.

Sharing and ownership
---------------------

Set a session token before any operation that controls a guest:

```sh
export QUAL_OWNER=my-release-session
python3 vm.py wait-free --timeout 28800
python3 vm.py start ubuntu
```

Never use another session's token without its owner's agreement. `running.json`
records the token. `stop`, `reset`, `ssh`, `copy-in`, mirror setup/removal, and
all screen commands refuse a different or missing token and name the owner.
A missing token reports `QUAL_OWNER is required (VM owner X; ...)`; when no
record exists the owner is `unknown`. A collaborating exploring session
deliberately uses the same token. Owner SSH, file copies, screen commands and
broker requests can run concurrently, including during a long SSH command.
Exclusive control locks are reserved for harness state changes.

`start` checks both guests before changing config, disks or firmware: the VM's
lifetime flock (inherited and retained by QEMU), the running record, connect
probes on all four ports, visible harness processes, and QEMU's image locking
on existing overlays/install disks. Unavailable probes count as occupied.
This protects a VM even when a sandbox hides its process. `status` reports
`running (owner X, process not visible)` when other signals remain. Run status
on the host for complete diagnostics; a sandbox's denied probes are reported.

A running record always blocks a new start, including after a crash. A failed
launch reaps its own QEMU process, removes its own running record, and reports
that launch's QEMU error. A failed fresh start also removes its newly created
run disk and firmware; a failed visible preparation boot keeps the prepared
disk and firmware. Crash records are never implicitly cleared. After
investigating a stale record, run on the host:

```sh
python3 vm.py clear-stale
```

This rechecks the lifetime lock, all ports, image locks and visible processes,
then removes only the record if every check is free. It never deletes a disk.
`wait-free` polls every five seconds and returns zero only when the record and
all live signals are absent; timeout is nonzero and names the blocking signals.
A stale record requires `clear-stale` before queued sessions can proceed.

Owner preparation
-----------------

```sh
export QUAL_OWNER=owner-release-round
python3 vm.py prepare-owner ubuntu --package /path/JustSearch.deb --corpus /path/corpus
python3 vm.py prepare-owner windows --package /path/JustSearch-setup.exe --corpus /path/corpus
```

Run one at a time. Preparation creates a fresh overlay, waits for SSH, installs
headlessly, copies the corpus folder's contents into the user's Documents,
shuts down cleanly, then boots the **same prepared overlay** with a software GTK
window on `DISPLAY`. The app has not been started. Ubuntu installs with apt;
Windows runs NSIS `/S` via an interactive scheduled task as the logged-in `qual`
user, and checks its exit status. The task is removed after completion. The
sealed Ubuntu user's disposable password is supplied to guest sudo on stdin;
no host sudo is used. Package and corpus validation happens before starting.
Corpora may contain regular files/directories only, not symlinks.

Windows may omit `--package` to qualify corpus copying and visible boot when
no Windows installer is available. This does not qualify Windows installation.
A successful preparation intentionally leaves the VM running for its owner;
use `vm.py stop` with the same token when finished. Keep qualification-only
visible checks brief while the owner uses the desktop.

HTTPS update mirror (Ubuntu)
----------------------------

Lay out a folder as a URL tree, for example:

```
mirror/
  justsearch-app/justsearch/releases/latest/download/release.v2.json
  justsearch-app/justsearch/releases/latest/download/release.v2.json.sig
  justsearch-app/justsearch/releases/latest/download/latest.json
  justsearch-app/justsearch/releases/download/v0.3.1/JustSearch_0.3.1_amd64.deb
```

Use the actual production descriptor filenames for the tested app (v1 for
older main, v2 for the Linux updater). The signed descriptor's artifact URL
must name the corresponding HTTPS `github.com` path in this tree. Generate
**test** metadata/artifact keys and use the repository's release producer;
never use real release signing keys. Build test `.deb` files with
`JUSTSEARCH_RELEASE_DESCRIPTOR_URL` set to the production HTTPS URL,
`JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID` and
`JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY` pinned to the test root. Do not
set `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE`: this journey exercises the normal
HTTPS client and rustls-platform-verifier's system trust store.

```sh
# The caller must own the running Ubuntu VM.
python3 vm.py mirror ubuntu /path/mirror --untrusted
# Launch the installed app from the guest desktop and check: TLS must fail.
python3 vm.py mirror ubuntu /path/mirror
# The generated test CA is now in the guest's system trust store. Check/install
# through the app; accept the guest's PolicyKit prompt for apt when it appears.
python3 vm.py mirror ubuntu --remove
```

Launch the app from the guest desktop (for example Alt+F2, `JustSearch`), so
PolicyKit can use the active desktop authentication agent. A process launched
through SSH belongs to the SSH session and may fail elevation with no
controlling terminal. This is distinct from a TLS or signature refusal.

Each setup replaces the previous mirror, copies the files into the guest,
generates a two-day test CA and a `github.com` server certificate, adds a marked
guest hosts-file entry pointing to guest loopback, and starts a Python HTTPS
server on guest port 443. The CA private key stays in a temporary host folder
and is deleted; only the server key and public CA enter the guest. Files retain
their URL paths. Server state and access logs are in
`/var/lib/justsearch-qual-mirror/`. Removal stops only the recorded server
identity, removes the marked hosts entry and test CA, refreshes the system
trust store with `update-ca-certificates --fresh` to remove test-CA symlinks,
restores the saved hosts-file bytes, and deletes the mirror files. Repeated
setup/removal cycles leave hosts and certificate contents unchanged. The base
is never changed.

Exploring sessions in restricted sandboxes
------------------------------------------

Host sessions can use the screen commands directly. On this host a restricted
Codex sandbox cannot connect to loopback QMP (`EPERM`). A file queue works when
the data directory is writable. Start this foreground broker on the host,
using the owning session's token, before starting the exploring session:

```sh
QUAL_OWNER=my-release-session python3 screen.py broker ubuntu
```

In the separate restricted session, with the same token and write access to
`/home/elias/dev/justsearch-qual`, use:

```sh
export QUAL_OWNER=my-release-session
python3 screen.py --broker shot ubuntu /path/inside/writable/root/desktop.png
python3 screen.py --broker click ubuntu 600 400
python3 screen.py --broker move ubuntu 700 400
python3 screen.py --broker key ubuntu ctrl+alt+t
python3 screen.py --broker type ubuntu 'Hello'
```

The broker accepts only `shot`, `click`, `move`, `key`, `type` with their exact
arguments. It rechecks the owner and VM run on each request. It has no SSH,
shell, file-copy or arbitrary QMP operation; screenshot output paths are
chosen by the client, not sent to the host broker. Queue files live under
`screen-queue/<pid>-<start ticks>` in the data directory; the client removes
its request/reply/image after receipt. The broker exits when that run ends;
it can also be interrupted. A disconnected client times out after six minutes.
Treat the token as cooperation between sessions of this host user, not as
isolation from a malicious host user who can edit the same data directory.

For a reproducible restricted probe on this host:

```sh
QUAL_OWNER=my-release-session codex sandbox \
  -c 'sandbox_mode="workspace-write"' \
  -c 'sandbox_workspace_write.writable_roots=["/home/elias/dev/justsearch-qual"]' \
  -- python3 /absolute/path/to/screen.py --broker shot ubuntu \
  /home/elias/dev/justsearch-qual/evidence/restricted.png
```

Isolated tests: `python3 -m unittest discover -s scripts/release/qual -p 'test_*.py' -v`
(from the repository root). They use their own temporary directories, locks,
processes and ports, and never touch the shared VM data.

W5 builder observations (2026-10-28): 29 isolated tests passed. A real second
start was refused without changing the running overlay, firmware or record
(hashes and inodes checked with the guest paused). A restricted session reported
the invisible owner correctly and took a screenshot plus clicked through the
file broker. Ubuntu owner preparation installed main's package, copied the
corpus and left the app unstarted; Windows copied the corpus and opened visibly.
Native Wayland compositor records confirmed visible windows, each under one
second for the final visibility checks. Windows NSIS installation was not tested
because no Windows package was available on this host.

The installed main app (a0022c79f1) refused the untrusted HTTPS mirror and reached
its production paths after CA installation. Separately built Linux updater
packages from detached updater-v2-linux 3d0cd9a1cf updated 0.3.0 to 0.3.1 through
the app UI, PolicyKit authentication, automatic restart and `COMMITTED`
reconciliation. Test pins and test signing keys were used, with no sandbox/HTTP
test switch. The harness branch contains no updater product changes. Evidence,
including exact commands, initial probe failures and corrected checks:
`/home/elias/dev/justsearch-qual/evidence/w5/acceptance.json`.
