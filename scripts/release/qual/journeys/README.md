# Linux release journeys

Implementation status: experimental. Full Linux acceptance has not passed.
The runner and native adapters need further qualification before this can serve
as a release gate; an unavailable check never certifies a release.

`journeys.json` is the journey definition and applicability matrix. `runner.py`
executes it against an **installed** package. `linux.py` implements J1–J8;
the Windows, GPU and J9 lanes remain explicitly unavailable in the definition.
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
CoreDump bytes are skipped while scanning, so later diagnostic fields are retained.
Installed package paths are read and every report is attributed
before reports move aside, so a lookup failure leaves reports available for retry.
A report naming Package: just-search, an installed package executable, a bundled
JVM or a WebKit subprocess is a product finding, as is a report belonging to the
observed app process tree. Process observations retain PID/start identities and
parent relationships. Other reports add an owner-validation row with their
summary and a plain screenshot; command-line mentions alone do not blame the product.
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
Before rail navigation the harness closes any restored Settings window; failure
to close it is infrastructure. Launch observation excludes pre-launch identities,
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
journey/check IDs and screenshot paths. Its `passed` boolean stays false and
its process exits with code 2 (failed or incomplete lanes use code 1). Review those
screenshots as part of the owner's final
visual release validation and record that validation alongside the release
evidence. The runner never converts an owner-validation row into a pass.
Failed or unavailable rows keep the lane failing and cannot be cleared by visual
validation. A partial development rerun reports incomplete (or failed when a
check fails); it cannot qualify the full release. Empty reports cannot pass.

`summary.json` includes asset hashes, exact scenario wording, scope and all
check outcomes. JSON observations redact credentials. Logs, source/corpus
hashes, screenshots and exception traces remain alongside it. The VM wrapper
also retains provisioning logs and hosted evidence as `evidence.tar`. A green
Linux lane does not qualify the unavailable Windows, GPU or J9 work.

```sh
python3 -m unittest discover -s scripts/release/qual/journeys -p 'test_*.py' -v
python3 -m unittest discover -s scripts/release/qual -p 'test_*.py' -v
```

These isolated tests never touch
the shared VM data, a display, running app,
network service or installed package. The compositor query unit tests evaluate
JavaScript with the existing Node tool. Before-state tests belong in a shared
clone under `$TMPDIR`; do not stash or revert the worktree for a baseline check.
