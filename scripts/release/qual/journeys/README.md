# Release journeys

Implementation status: experimental. Full Linux acceptance has not passed.
The runner and native adapters need further qualification before this can serve
as a release gate; an unavailable check never certifies a release.

`journeys.json` is the journey definition and applicability matrix. `runner.py`
executes it against an **installed** package. `linux.py` implements J1–J8;
`windows.py` implements Windows as described below; GPU and J9 remain outside this assignment.
The scenario wording is copied verbatim into every summary and checked by tests.
This suite changes no product code and enables no application test-build feature.

The hosted workflow is `qualify-release-linux.yml` (`workflow_dispatch`, `tag`).
It requires an existing **draft**, downloads its `.deb`, MCPB and `SHA256SUMS`,
and checks both executable assets before using either. It fetches the registry's
`server.json` from that same tag. It has `contents: read`, no signing secrets,
and uploads observations even when preparation fails. Dispatching it does not
publish a release. Do not rebuild the downloaded app to qualify it.

## Running on disposable Ubuntu machines

Tools: Python 3, Pillow, DejaVu Sans, Node **22.20.0**, MCP Inspector **0.21.1**,
tauri-driver **2.0.4**, WebKitWebDriver matching the installed WebKitGTK,
Tesseract, scrot, xdotool, a real text viewer (Mousepad), Xvfb, D-Bus and Openbox.
The app must find its own bundled JVM. Developer Java, CUDA and model-path
overrides are removed from the child environment. The hosted workflow removes
preinstalled JDK packages; its wrapper also removes JDK-specific PATH entries.
A remaining `javac` on PATH fails the clean-install prerequisite.

The hosted entry point is for disposable runners only: it runs apt and changes
that user's text-file association. It refuses to run unless both `GITHUB_ACTIONS=true` and
`RUNNER_ENVIRONMENT=github-hosted`
or a root-owned guest marker written by the VM wrapper is present. The apt
adapter enforces this too, including direct runner invocations.
**Do not run it on the release owner's host.**

```sh
xvfb-run -a -s '-screen 0 1440x1000x24' dbus-run-session -- \
  python3 scripts/release/qual/journeys/hosted.py \
    --assets /path/to/draft-assets --package JustSearch_0.3.1_amd64.deb \
    --registry /path/to/tagged/server.json --evidence /path/to/new-evidence
```

For the release host's clean VMs, first read the parent [harness README](../README.md).
The wrapper waits for the harness, owns one fresh headless overlay at a time,
joins the graphical session's display/runtime/D-Bus environment, caps native
thread pools at eight, and stops only the VM run it started on every outcome.
Default guest memory is 12288 MiB for every lane, with eight pinned threads.
Before launch, vm.py waits up to 15 minutes for host `/proc/meminfo`
MemAvailable to reach 14 GiB before taking the shared VM lock, then refuses
with a diagnostic if it remains lower. Launch rechecks without waiting under
the lock.
J3 and J5 exhausted 6144 MiB; J5 recovery at 8192 MiB wrote 12.58 GB to swap.
The nondefault `--memory-mib 8192` requires `--memory-reason` with journey evidence.
It installs tools **inside the guest**. No host sudo, desktop window or GPU is used.
Supply a tauri-driver 2.0.4 binary built separately as qualification tooling.
Between overlays it waits up to 90 seconds for the stopped guest's ports to be
bindable, including TCP TIME_WAIT. Native GNOME launches wait for the command
dialog with block-text OCR and for a script-entry acknowledgment; a missed
desktop launch is an infrastructure failure before any product assertion.

```sh
export QUAL_OWNER=w4a
python3 scripts/release/qual/vm.py status
python3 scripts/release/qual/journeys/vm-run.py \
  --session wayland \
  --assets /path/to/draft-assets --package JustSearch_0.3.1_amd64.deb \
  --registry /path/to/tagged/server.json \
  --predecessor /path/to/parent/JustSearch_0.3.0_amd64.deb \
  --mirror /path/to/signed-mirror-tree \
  --driver /path/to/tauri-driver --evidence /path/to/new-wayland-evidence
```

Repeat sequentially with `--session x11` and a new evidence directory; that
lane runs J1 only. Omit `--predecessor` and `--mirror`; X11 J1 uses the same
12288 MiB harness default.
For the hosted equivalent locally without installing anything on the host, use the
same wrapper with `--session hosted` and omit `--predecessor`/`--mirror`. This
runs the exact hosted entry point under Xvfb and D-Bus in a fresh Ubuntu VM.
The default VM runs use QMP through the existing `screen.py`; the hosted run
uses xdotool-class input under X11. By the round-two decision, the hosted lane
adds the corpus through the production HTTP API and records the native picker
as skipped by decision. The Wayland VM exercises the system picker.

Wayland runs J1–J4 and J7–J8 on a fresh **candidate** overlay. The wrapper stops
that guest and starts a second fresh overlay, installing the **predecessor from
scratch**. J1–J4 prepare that predecessor's folder, default AI installation,
bookmark, setting and restart; J5 then updates it to the candidate and J6 checks
install-over and retained data. No candidate data is moved aside or reused by
the predecessor. Each overlay retains its own summary; the wrapper combines the
candidate observations and predecessor update/retention observations into one
Wayland summary. Predecessor preparation remains separately recorded.

Fresh qualification (`--mode fresh`, the default) starts from the clean OS
base and uses production downloads. After J1–J3 (J1–J4 for the predecessor),
the wrapper stops the guest cleanly and renames its overlay into a setup
snapshot, without copying or flattening its data. Remaining journeys run in
a thin overlay backed by that snapshot. The fresh setup observations are part
of that same qualification report; predecessor setup stays separately recorded.
Snapshots contain generated corpus, installed package, downloaded models and
stopped runner state. Their manifests pin asset checksums, predecessor,
registry, driver, session, phase and OS base. Keep snapshot and base paths in
place while their child overlays exist. No base image is edited.

Development reruns reuse one such snapshot, with the same package/tool/mirror
arguments and **explicit** journey IDs, for example:

```sh
python3 scripts/release/qual/journeys/vm-run.py \
  --session wayland --mode rerun --journeys J7 \
  --snapshot /path/to/fresh-evidence/candidate-setup/snapshot \
  --assets /path/to/draft-assets --package JustSearch_0.3.1_amd64.deb \
  --registry /path/to/tagged/server.json \
  --predecessor /path/to/JustSearch_0.3.0_amd64.deb --mirror /path/to/mirror \
  --driver /path/to/tauri-driver --evidence /path/to/new-rerun-evidence
```

Only the named journeys run; prerequisites are restored from the stopped
checkpoint, never silently rerun. Candidate snapshots support J4/J7/J8;
predecessor snapshots support J5/J6. Cached J3 waits up to 15 minutes for
activation_required or active runtime encoders before deciding whether to activate.
It ignores transient installedFully status for that decision; an unsettled resume
is an infrastructure failure. Use fresh J3 for acceptance. J1/J2 require a fresh lane
(`--mode fresh --journeys J1 J2 J3`, with exactly the prerequisites required
by that selection). Named fresh subsets and snapshot reruns set
`execution.releaseQualification` to false. A failed setup assertion remains a
failure in its source report; using its state cannot certify that assertion.
The hosted VM uses persistent, isolated XDG directories for these two stages;
the actual hosted workflow keeps its temporary XDG directories.

Guest-only provisioning masks Snap refresh, firmware update, APT timers and
the desktop file indexer, and sets swappiness to 1 to prefer reclaiming file
cache over writing anonymous pages. At first boot, desktop preparation disables
Ubuntu update-notifier crash pop-ups and renews the guest login session if needed.
Screenshots and OCR text are retained for triage. OCR never determines a
product pass: shell banners, notification-center text and foreign windows can
cover app pixels even when its compositor PID and geometry are stable. Shell
modal/banner guards, repeated OCR before-reads and unique OCR tokens are removed.
Apport still records crashes; only bounded diagnostic fields are transferred.
Crashes collected while dpkg-query confirms the package is absent are retained
separately under `before-install/`. On an installed snapshot, the setup sweep
attributes crashes normally; snapshot boot never exempts product crashes.
The hosted workflow installs systemd-coredump and attributes its JSON entries after
the run, using the same crash rules and recorded app processes. Product crashes
fail the lane; unknown crashes require owner validation; collection errors fail as
infrastructure. Hosted collection is not proven armed or finished: every hosted
collection summary includes the owner-validation row `hosted crash collection unproven`,
linking `coredumps.json` and `hosted-crashes.json`. Even an empty journal cannot
yield better than `passed-pending-owner-validation` (exit 2); a product crash
still fails the lane. CoreDump bytes are skipped while scanning, so later diagnostic fields are retained.
Installed package paths are read and every report is attributed
before reports move aside, so a lookup failure leaves reports available for retry.
A report naming Package: just-search, an installed package executable, a bundled
JVM or a WebKit subprocess is a product finding, as is a report belonging to the
observed app process tree. Process observations retain PID/start identities and
parent relationships. Other reports add an owner-validation row with their summary
and crash record as evidence; crash owner-validation rows in both VM and hosted
lanes do not require a screenshot. Every other owner-validation row still requires
a screenshot. Command-line mentions alone do not blame the product.
Both lanes also collect `hs_err_pid*.log` files modified during the run from the
Engine working directory, `/tmp`, the app user's home and the app's `crashes/`
directory. The shared runner records its start timestamp on the target before
launches, ignores older snapshot logs, and collects after stopping its processes.
`jvm-crashes.json` retains source paths and diagnostic contents (up to 4 MB per
file and 20 MB total, with truncation recorded). Any such file fails the lane as
a bundled JVM product crash, even without an Apport or coredump record.
Journald and swap remain enabled.
Every journey records synced whole-device write and swap-counter deltas,
without double-counting disk partitions. Overlay summaries record boot through
pre-shutdown guest counters, QMP counters and QEMU's host process I/O; the top
summary totals guest bytes across overlays. These counters describe writes,
unlike qcow2 allocated size. Shutdown writes are outside the measured interval;
unavailable measurements are explicit. Use one driver at a time and reuse
setup snapshots for infrastructure reruns instead of redoing full sets.

For local test builds, build the predecessor from the candidate's **parent** in
a shared clone inside `$TMPDIR`, with a separate build directory. Use the same
generated test metadata root pins for both packages; give the candidate a newer
test version. Use `app-release-assets.mjs` to produce and verify the descriptor
and artifact signatures. Follow the harness README's HTTPS mirror tree layout.
Do not set `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE`. `mirror-guest.py` provides
guest-only HTTPS trust and production-URL mapping; the updater still uses the
normal platform TLS verifier, descriptor v2, PolicyKit and apt. No production
private signing key is needed or used for these tests.

## What is asserted

- J1: apt, installed payload checksums and bundled JVM; launch through the
  installed desktop entry, require a mapped window owned by a run-owned process
  whose executable path exactly equals the installed binary, and Engine readiness.
  First-run visible text in that desktop-launched window is `owner-validation`
  with `J1-desktop-first-run.png`. A subsequent harness-launched WebDriver session
  checks its own mounted DOM and prepares later journeys; it does not certify
  the preceding desktop window's visible text.
- J2: the real chooser, Ctrl+L and a generated corpus path on the Wayland VM;
  production API folder addition on hosted; index and search observations;
  **Open file** followed by the exact fixture path in the receiving Mousepad
  process's `/proc/<pid>/cmdline` or open file descriptor, plus a screenshot.
  The process must belong to this run. Window titles and maximization do not
  determine which file was opened.
- J3: default production downloads, then **Activate downloaded models** and the
  UI's typed authorization when requested (immediate operation acceptance is
  also observed). All four reported retrieval models must be active
  and installation fully complete. A semantic vector query must return the
  fixture with a dense-only execution trace and an executed cross-encoder, without
  keyword fallback. Fresh downloads also require the session acquisition capability;
  cached reboots prove readiness through active runtime encoders and that query.
  Activation polls record `/proc/meminfo` samples about every five seconds. The CPU
  plan and chat-unavailable explanation are also checked. Downloads are not seeded.
- J4: a UI-authored bookmark and theme, restart, retained settings,
  watched folder, indexed fixture, authored records and model file hashes.
  Bookmark rendering after restart is checked in the harness's own newly launched
  WebDriver session: the open list must contain the authored Library entry with
  its exact label and URL. The persisted record (including ID) must also match
  through the Web Storage API (`localStorage.getItem`) in that session. Bookmarks
  are app-local browser state; the Engine exposes no bookmark endpoint. Existing
  read-only saved-file checks also retain the record and model hashes.
- J5 (VM): parent-state preparation, signed HTTPS update offer, cancelled
  PolicyKit prompt with old Engine recovery and re-offer, the **real 300-second
  dpkg lock timeout**, then accepted elevation and automatic candidate relaunch.
  The offer is entered through the banner's **Review and install** action,
  which opens Settings. A direct Settings fallback is allowed only when the
  visible banner has no action, and the chosen entry is recorded. Recovery
  can recreate Settings at Appearance; the runner closes that window, follows
  the banner again, and waits for an enabled Install update control before
  claiming a re-offer. Cancellation requires this run's pkexec apt-get install
  process with the exact candidate artifact checksum before Escape, its exit
  afterward, and unchanged installed version. Success records the old shell and
  Engine identities and requires both to exit after automatic candidate launch.
  The observed time and readiness signal are recorded.
- J6 (VM): reinstall, apt remove, retained app data, reinstall and state checks.
- J7: menu entry, cold/warm OS deep links, X11 registration or the physically
  invoked GNOME Wayland `--focus-search` binding (Ctrl+Super+J), and scanned-PDF OCR.
  Menu entry requires an owned, mapped installed app window and Engine readiness.
  `deep-link-cold` stops the app, dispatches through the desktop handler and
  requires exactly one run-owned UI process executing the exact installed binary,
  with its mapped compositor window and Engine ready. Its visible query destination
  is `owner-validation`, pictured in `J7-deep-link-cold.png`.
  `deep-link-warm` launches the installed app under the harness's WebDriver,
  establishes Search as the baseline, then dispatches the OS link. The session's
  DOM must report the Library route and visible heading. The mapped receiver
  retains its original PID and process start identity. An extra single-instance
  forwarder may exit within three seconds, before the destination is accepted;
  an extra JustSearch process alive at acceptance or after that grace period is
  a product failure. Reported product failures end polling immediately.
  Shortcut input stays in that harness-owned WebDriver session: compositor focus,
  document focus, the focused search control and its exact typed value must agree.
  The Wayland wrapper records invocation before executing `--focus-search`.
  Missing dispatch is infrastructure failure; dispatch followed by failed search
  focus is product failure, with session and focused-window evidence.
- J8: release MCPB and tagged registry version/URL/checksum/platform checks;
  pinned Inspector CLI over stdio through the **extracted release bridge** for
  tools/list, status and a fixture-returning search.

WebDriver sends pointer actions to DOM-derived viewport coordinates, after
scrolling controls into view. This avoids WebKitGTK's nested-shadow-root
element-click interception. Native dialog handling uses keyboard input and screenshots. OCR may gate
harness interaction with desktop dialogs, but only DOM, production app/Engine
APIs, exact installed process identity or mapped compositor window identity can
supply pass evidence. Picker and PolicyKit text alone cannot certify an app result.
Startup and updater relaunch also use process/window identity and Engine readiness.
Before rail navigation the harness closes any restored Settings window; a delivered
Close Settings action that leaves the window open is a product failure. Launch observation excludes pre-launch identities,
and stopping waits for each recorded process identity to exit.
Interaction observations retain elapsed time, indexing and worker state without
adding an indexing-idle prerequisite.

On the disposable Wayland guest only, the wrapper opens GNOME Looking Glass and
sets `global.context.unsafe_mode = true` for compositor queries. This disappears
with the overlay; never enable it on the owner's desktop. Each observation uses
one Shell query for mapped windows and focus. X11 uses xdotool. An app window's
PID must belong to this run and execute exactly the installed executable path;
a same-named executable in another directory cannot qualify it. A mapped window
can establish launch even when unfocused. Shortcut checks additionally require
that exact app window to have compositor focus.

`corpus.py` generates eight small, original CC0 documents: text, long Markdown
and text reports, CSV, HTML, email, DOCX and an image-only PDF. The unique open
fixture is `JustSearch-journey-orchid-open.txt`; only the scanned PDF contains
`cobalt marmalade`. Per-run corpus hashes are retained. No external documents,
personal information, models or generated corpus binary files are committed.
## Results and tests

Each executed check has one of `passed`, `failed-product`,
`failed-infrastructure`, `unavailable`, or `owner-validation`, with a reason and
evidence links. Existing applicability omissions remain `skipped-by-decision`;
they never add a pass. Product assertions and reported end states fail as product
failures; driver/tool/transport failures are infrastructure. A terminal AI install,
activation failure/cancellation or knowledge-server start error stops immediately,
so a later recovery cannot turn that observation into a pass. Only transient API
failures are retried; a product API failure seen during the deadline retains its
attribution if the last observation is an infrastructure error.

A complete lane without failed or unavailable checks reports `status: passed`
only when every executed result is automatically proven. Any owner-validation
rows change it to `passed-pending-owner-validation`; `ownerValidation` lists
journey/check IDs and screenshot paths, or crash evidence paths for crash rows.
Its `passed` boolean stays false and
its process exits with code 2 (failed or incomplete lanes use code 1). Review those
screenshots and crash records as part of the owner's final
visual release validation and record that validation alongside the release
evidence. The runner never converts an owner-validation row into a pass.
Failed or unavailable rows keep the lane failing and cannot be cleared by visual
validation. A partial development rerun reports incomplete (or failed when a
check fails); it cannot qualify the full release. Empty reports cannot pass.

`summary.json` includes asset hashes, exact scenario wording, scope and all
check outcomes. JSON observations redact credentials. Logs, source/corpus
hashes, screenshots and exception traces remain alongside it. The VM wrapper
also retains provisioning logs and hosted evidence as `evidence.tar`. A green
Linux lane does not qualify Windows, GPU or J9 work.

```sh
python3 -m unittest discover -s scripts/release/qual/journeys -p 'test_*.py' -v
python3 -m unittest discover -s scripts/release/qual -p 'test_*.py' -v
```

These isolated tests never touch
the shared VM data, a display, running app,
network service or installed package. The compositor query unit tests evaluate
JavaScript with the existing Node tool. Before-state tests belong in a shared
clone under `$TMPDIR`; do not stash or revert the worktree for a baseline check.

## Windows journeys

The Windows implementation is experimental. Fixture tests and tooling probes do
not qualify an installed app. `windows.py` is a platform adapter used by the same
`runner.py`, result classification, crash rules and exit codes as Linux.
`qualify-release-windows.yml` is an advisory, read-only `workflow_dispatch` lane
for an existing draft. It verifies the NSIS/MCPB hashes before use and reads
`server.json` from the draft tag. No signing secret, release creation or publication
is involved. Its first step rejects a non-GitHub-hosted runner.

The guest/hosted tooling script is `windows-tools.ps1`. It requires a disposable
runner or the VM marker and runs elevated during provisioning. It installs Python
3.13.7, Pillow 11.3.0, comtypes 1.4.13 and Node 22.20.0. The text viewer is
PowerShell ISE already installed in the OS. Its actual image/hash/label are
recorded in `tools.json`. `windows-association.py` uses native Default Apps
Settings to select it when needed, then verifies the OS association API and a
normal OS file open. The VM does this once before sealing and verifies the
association with the limited user after reboot. Hosted provisioning performs
the same native selection. Protected UserChoice hashes are never manufactured
or deleted. The tooling script also downloads
Microsoft's signed msedgedriver for the installed WebView2 version. Supply a
separately built **tauri-driver 2.0.4 Windows binary** and an adjacent
`tauri-driver.exe.json` receipt containing `version: "2.0.4"` and the binary
`sha256`. The producer records this after `cargo install tauri-driver --version
2.0.4 --locked`; the hosted provisioner reads Cargo's installation metadata.
The tool has no `--version` switch. Its receipt and bytes are checked before use. A WebView2/driver mismatch
is infrastructure. The app's child environment drops developer JVM/model/CUDA
variables and JDK PATH entries; hosted provisioning also removes its known JDK
installations. A remaining `javac` refuses clean-install qualification. Developer
build tooling stays on a separate build overlay, never the clean journey overlay.

```sh
export QUAL_OWNER=w4b
python3 scripts/release/qual/vm.py status
windows_inputs=( \
  --assets /path/to/windows-assets --package JustSearch_0.3.2_x64-setup.exe \
  --registry /path/to/candidate/server.json \
  --driver /path/to/windows/tauri-driver.exe \
  --predecessor /path/to/parent/JustSearch_0.3.1_x64-setup.exe \
  --mirror /path/to/signed-test-mirror )
# Once: tools, cached pinned Inspector, driver, corpus and release/mirror staging.
python3 scripts/release/qual/journeys/vm-windows.py "${windows_inputs[@]}" \
  --mode prepare --evidence /path/to/windows-tools-snapshot
# Development: work on one journey, from a thin overlay of that snapshot.
python3 scripts/release/qual/journeys/vm-windows.py "${windows_inputs[@]}" \
  --setup-snapshot /path/to/windows-tools-snapshot \
  --journeys J1 --evidence /path/to/new-j1-evidence
```

Preparation first caches Inspector 0.21.1 through npm and proves offline package
resolution (`inspector-cache.json`); later overlays reuse that cache. It applies
`windows-quiet.ps1` as SYSTEM only in the owned guest, disabling Windows Update,
Edge Update (edgeupdate/edgeupdatem, its scheduled tasks and both default and
WebView2 Update policies), SysMain, Windows Search, diagnostics/telemetry upload,
their scheduled work, scheduled optimisation and
Defender real-time/scheduled scans. If Defender refuses, all test and app data
folders must be excluded instead; the actual mode is recorded. Hibernation is
off and the page file is system-managed, as on a default Windows installation.
Fixed 512 MiB and 2048 MiB qualification settings caused commit/allocation
failures during CPU model use in a 12 GiB guest. A full shutdown/reboot precedes
verification of each observed service, task, policy, exclusion and page-file
allocation in `quiet-verify.json`. An unverified setting refuses snapshot
sealing and all journeys. Setup caches EdgeDriver by the exact four-part WebView2
version, validates its vendor signature, and verifies the match again after reboot.
Snapshot metadata records both versions; a runtime mismatch records both and fails
as infrastructure. WER remains enabled; the limited app task receives
read access to existing machine crash reports during SYSTEM provisioning.
The guest's existing WER tree is given medium integrity as well as a read ACL:
high-integrity archive labels otherwise deny reads even with the ACL. After
reboot, a separate limited task must enumerate and open the reports before
sealing (`crash-access.json`). Any later unreadable report fails collection.
DoSvc is a recorded protected-service exception: its startup mode is observed,
and `DODownloadMode=99` is verified after reboot instead. UpdateOrchestrator tasks
are also recorded exceptions because Windows re-enables them. Update services
`wuauserv` and `UsoSvc` must remain stopped; a refused/reverted startup mode is
recorded. Policies `NoAutoUpdate=1` and `AUOptions=2` are required. Every journey
overlay retains `update-activity.json`: before/after SoftwareDistribution download
folder sizes and inventories, service/policy observations and Windows Update
events. At each boundary the collector reapplies Stop to a restarted update
service and records its original state and any refusal in `controlExceptions`.
Changed download files or download/install events fail the environment
check. The per-run measured write limit remains required.
Guest-only outbound service rules also block `wuauserv` and `UsoSvc`. Windows
removed the attempted WinDefend rule after reboot; that is a recorded exception,
and the same per-run download/install evidence remains required.

The wrapper owns one headless, eight-thread Windows VM at a time and honors the
harness's 14 GiB host-memory threshold. It runs the adapter through a **limited,
interactive scheduled task**, rather than SSH's session zero. The host performs approved NSIS `/S` operations
in the existing harness's highest interactive context, checking exact package
paths/checksums and retaining PID, creation/exit time and native exit code. App
and WebDriver stay limited. The bundled VC++ prerequisite otherwise waits for
elevation on a clean OS; its standard-token consent UI is not certified by this
installer context. The hosted equivalent uses the hosted runner's elevated caller
context. It stops only its
recorded guest on every outcome. Both candidate and predecessor start with fresh
J1–J2, then J3 downloads from production in its own thin overlay. A clean shutdown
seals each stage by renaming its disk/firmware into a snapshot. Remaining journeys use thin child
overlays; no model cache or candidate data is moved into the predecessor.
Cleanly stopped failed stages retain their checkpoint as well as their
failed summary, so a diagnostic rerun can reuse production downloads without
certifying the failed stage. Refreshing an application snapshot's guest controls
keeps its original phase and checkpoint. Cached J3 also recognizes complete
acquisition through the production install-status API; activation, all four
runtime components and semantic retrieval still have to pass. Windows J3 retains
the actual activation click events and disabled control state through its own
WebDriver session in `J3-activation-input.json`.

Windows native-window discovery enumerates visible top-level HWNDs and reads
each through UI Automation. UIA's desktop children omit owned dialogs, including
the folder picker. Picker input still requires the exact installed image, PID
and creation time; the app's returned DOM path and Engine roots decide success.
Native actions use the OS IUIAutomation COM interface through comtypes; the
legacy managed PowerShell client exposes standard Win32 controls as panes with
no action patterns in this guest. The pinned client and generated interface
bindings are cached before sealing (`uia-client.json`). Picker input uses UIA
Value and Invoke patterns, with exact returned-value checks. The picker control
inventory is retained for diagnosis.

WebDriver uses the normal installed app's `LOCALAPPDATA/io.justsearch.shell`
profile through `tauri:options.webviewOptions.userDataFolder`. Owned WebView2
process arguments must confirm that profile (`webdriver-profile.json`).
[EdgeDriver otherwise creates a temporary profile](https://learn.microsoft.com/en-us/microsoft-edge/webdriver/capabilities-edge-options),
which cannot prove saved bookmarks survive a restart. The shared client's
default Linux session request is unchanged. Each child run also gets a distinct
guest evidence directory, since stopped checkpoints retain previous evidence.

Use `--session hosted` without predecessor/mirror arguments for a local hosted
equivalent in a fresh Windows VM. This executes `windows-hosted.py`, the same
entry point as Actions. Prepare its tools snapshot with that same session first.
To continue from an installed checkpoint, supply `--mode rerun --snapshot
/path/to/new-j1-evidence/selected/snapshot --journeys J2`. Each development
rerun selects one journey, restores its prerequisite checkpoint and seals the
resulting stopped state for the next journey. J1 starts fresh from the tools
snapshot. Snapshot pins include packages, registry, driver, mirror contents,
provisioning sources and OS base. Tools, corpus, packages and mirror bytes are
never recopied or reinstalled on a rerun; only changed harness source is staged.
Explicit subsets and development reruns remain incomplete and cannot qualify
a release. Omit `--journeys` only for final full-suite evidence. Keep backing
snapshots in place; unused child overlays are deleted after evidence collection.

J1 checks shipped per-user NSIS completion, installed version/image, bundled JVM,
UI Automation window identity and Engine readiness. Normal first-run text needs
owner validation with a screenshot. Later DOM checks belong only to the harness's
own WebDriver session. J2 drives the real system picker with UIA Value/Invoke
patterns, then requires the returned path in the app DOM and watched-folder API.
Open-file evidence requires the OS default viewer's exact image, a new PID and
creation time, the exact fixture argument, and its UIA window. Titles and OCR
cannot pass any check. J3 and J4 reuse the Linux DOM/API AI and authored-state
contracts; Windows bookmarks are read through Web Storage, never guessed from a
WebView database. J6 hashes actual downloaded models/settings across reinstall
and uninstall/reinstall. It uses the preservation method of
`update-preserves-models-test.ps1`; that script's Actions-only guard is preserved.
J8 uses the release bridge and pinned MCP Inspector over stdio on Windows too.
J7 remains Linux-only; Windows GPU is explicitly skipped by owner decision.

For Windows CPU J3, the product deliberately reports `installedFully=false`
when chat and CUDA are hardware-skipped. That status is accepted only with
completed activation, no repair needed, retrieval-core acquisition ready, all
five downloadable model packages installed without errors, and exactly those
two hardware skips. The semantic query, executed retrieval stages, chat
unavailability and all four active runtime components remain required. Linux's
existing completion checks are unchanged.

J5 uses the production HTTPS URL, descriptor v2, generated test trust pins and a
VM-local mirror. The shipped installer is `currentUser` and requests no UAC;
the cancellation leg declines the **actual witnessed NSIS installer**. It must
resume the old Engine and re-offer without a harness restart. Success requires
identified NSIS actions, automatic candidate relaunch, retirement of both old
shell and Engine identities, and retained data. A later harness-owned WebDriver
restart observes persisted Web Storage; it does not certify the preceding
relaunch's pixels. The updater source checks descriptor/artifact signatures and
does not require Authenticode or the production signing key. Runtime acceptance
of unsigned locally built NSIS bytes still requires a real run. SmartScreen or
Mark-of-the-Web blocking is an environment finding, never a product pass/failure.
W8 must repeat qualification on the immutable draft NSIS/MCPB/registry bytes and
provide a compatible predecessor and authenticated mirror/feed for those pins.

Build local NSIS packages **inside a separate Windows build overlay** using the
existing `scripts/ci/build-installer-windows.ps1` producer. That wrapper calls
`package-installer-win.ps1` and applies `packaging/signed-mirrors.v1.json`; bypassing
it can compare unsigned upstream Tesseract against the signed per-file manifest.
Provision MSVC, Rust, Node, JDK 25 and 7-Zip there. Build the predecessor from the
candidate parent's shared clone under `$TMPDIR`, in its own build folder, with
identical generated test pins and a lower test version. Do not enable
`JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE`. The existing Gradle hook may use
`--no-daemon` inside this disposable guest; host Gradle remains exclusively
`js-gradle-slot ./gradlew` and must not stop shared daemons.

Crash evidence includes readable Application event records, WER reports, local
dump records and newly written `hs_err_pid*.log` files. Elevated provisioning arms
HKLM WER LocalDumps; configuration is recorded before the app starts. Process
observations retain image, PID, creation time and parent creation time. Windows
CIM creation dates lose precision, so process creation times come from the held
Windows process handle/.NET process object instead. Installed-image, observed app
tree, its WebView2 children or bundled JVM crashes fail as product. Unknown crashes
require owner validation with their original crash records. Old crashes are
recorded separately. The hosted lane always adds the crash-coverage owner-validation
row until collection is proven armed and finished in that same run; an empty
journal cannot produce exit 0. Crash records are bounded; truncation is explicit.

Every journey records physical-disk raw-counter deltas without `_Total` double
counting. Each overlay summary records `guestWrittenBytes`, QEMU's actual
`/proc/<pid>/io` `write_bytes` as `hostQemuWrittenBytes`, and allocated qcow2
growth as `overlayGrowthBytes`. Guest and QEMU counters end before shutdown;
overlay allocation is measured after shutdown. A background watcher also covers
blocking guest commands. Exceeding **15 GB** in any measured write/growth counter
stops only this owned VM and fails the run; report it before another run.
The dedicated J3 overlay has a **25 GB** allowance for production model downloads,
extraction and the default page file; every other overlay retains 15 GB. A write
stop terminates polling immediately and prevents later full-run stages from
starting. During J3, `J3-resources.jsonl` retains system commit charge/limit,
available RAM, page-file allocation/use, app-tree private bytes and working sets,
data-directory file sizes and recent Engine logs on the host every 30 seconds.
These diagnostics survive a guarded VM shutdown. File sizes describe allocation,
not cumulative writes; guest raw counters and QEMU I/O remain the write evidence.
Preparation retains separate counters for both boots. Windows swap bytes are
explicitly unavailable rather than zero. Allocation growth is not an I/O counter.

```sh
python3 -m unittest discover -s scripts/release/qual/journeys -p 'test_*.py'
# Inside Windows: adapter fixtures and owned short-lived processes; no host VM modules.
python -m unittest discover -s scripts/release/qual/journeys -p test_windows.py
```
