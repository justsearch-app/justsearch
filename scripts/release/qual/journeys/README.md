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
that user's text-file association. It refuses to run unless `GITHUB_ACTIONS=true`
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
Apport still records crashes; raw reports and key fields are retained, and a
JustSearch or bundled Engine crash is a product finding. Journald and swap remain enabled.
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

- J1: apt, installed payload checksums, bundled JVM, normal packaged window and
  first-run WebDriver session against `/usr/bin/JustSearch`.
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
- J4: a UI-authored bookmark and theme, normal restart, retained settings,
  watched folder, indexed fixture, authored records and model file hashes.
  After normal restart, wait for restored Settings to mount, then Escape and
  confirm it closed. A physical click at the
  previously DOM-measured bookmarks control opens the popup in that same normal
  process. The focused window must belong to this run; OCR is restricted to the
  previously measured popup area and must transition from no Library to Library.
- J5 (VM): parent-state preparation, signed HTTPS update offer, cancelled
  PolicyKit prompt with old Engine recovery and re-offer, the **real 300-second
  dpkg lock timeout**, then accepted elevation and automatic candidate relaunch.
  The offer is entered through the banner's **Review and install** action,
  which opens Settings. A direct Settings fallback is allowed only when the
  visible banner has no action, and the chosen entry is recorded. Recovery
  can recreate Settings at Appearance; the runner closes that window, follows
  the banner again, and waits for an enabled Install update control before
  claiming a re-offer. The observed time and readiness signal are recorded.
- J6 (VM): reinstall, apt remove, retained app data, reinstall and state checks.
- J7: menu entry, cold/warm OS deep links, X11 registration or the physically
  invoked GNOME Wayland `--focus-search` binding (Ctrl+Super+J, avoiding the
  default input-source switch chord), and scanned-PDF OCR. Cold and
  warm OS links run without WebDriver; the receiving app must be focused and
  its app window must contain a query derived from this run ID for the cold
  link. That query must be absent from the DOM and app-window OCR before
  dispatch. The warm link must name Library in the cropped top bar.
  Shortcut input stays in a
  WebDriver session, where compositor focus, document focus, the actual focused
  search control, and its exact newly typed value must agree. The Wayland binding
  runs a wrapper that records invocation before executing `--focus-search`. Missing
  dispatch is infrastructure failure; dispatch followed by failed app/search focus
  is product failure, with compositor session and focused-window evidence.
- J8: release MCPB and tagged registry version/URL/checksum/platform checks;
  pinned Inspector CLI over stdio through the **extracted release bridge** for
  tools/list, status and a fixture-returning search.

WebDriver sends pointer actions to DOM-derived viewport coordinates, after
scrolling controls into view. This avoids WebKitGTK's nested-shadow-root
element-click interception. Native dialog handling uses keyboard input and QMP
screenshots. Full-screen OCR uses sparse-text segmentation; screenshots remain
the primary evidence for diagnosing missed text and focus. Startup, persistence,
deep links and updater relaunch are also checked without WebDriver automation.
Native screenshots record when each new app window is first observed. J4's
bookmarks click and J7's warm OS dispatch record elapsed time, indexing and worker
state. These observations add no indexing-idle prerequisite.

On the disposable Wayland guest only, the wrapper opens GNOME Looking Glass and
sets `global.context.unsafe_mode = true` to query focused-window PID and geometry
through the compositor debugger. This changes no product state and disappears
with the overlay. Never enable it on the owner's desktop. X11 reads the focused
window from xdotool. A window counts as the app only when its PID is an owned
JustSearch process. Native OCR crops are saved with their window identity;
whole-screen OCR cannot certify navigation or focus.

`corpus.py` generates eight small, original CC0 documents: text, long Markdown
and text reports, CSV, HTML, email, DOCX and an image-only PDF. The unique open
fixture is `JustSearch-journey-orchid-open.txt`; only the scanned PDF contains
`cobalt marmalade`. Per-run corpus hashes are retained. No external documents,
personal information, models or generated corpus binary files are committed.
The small retained screenshot in `fixtures/predecessor-bookmark-ocr.png` is a
regression fixture from the independent W4a verifier: block segmentation reads
“Ubrary”, while sparse segmentation reads “Library”. Native destination OCR
retries sparse segmentation on a miss. Missing OCR text without corroborating
app state is infrastructure, rather than a product absence verdict.

## Results and tests

Each check has one of `passed`, `failed-product`, `failed-infrastructure`,
`skipped-by-decision`, or `unavailable`, a reason and evidence links. Missing
prerequisites stay unavailable. Assertions about product outcomes fail as
product failures; driver, focus, tool and transport failures are infrastructure.
Unknown harness exceptions also stay infrastructure. Skips and unavailability
never add a pass. An empty or incomplete report cannot report `passed: true`.
The summary passes only with at least one passed check, every check applicable
to the full lane present, and no failed or unavailable checks. Decision skips
do not prevent a full lane from passing. The process exits nonzero whenever
that verdict is false, including a successful partial development rerun;
settled VM-only omissions in the hosted lane remain explicit decision skips.

`summary.json` includes asset hashes, exact scenario wording, scope and all
check outcomes. JSON observations redact credentials. Logs, source/corpus
hashes, screenshots and exception traces remain alongside it. The VM wrapper
also retains provisioning logs and hosted evidence as `evidence.tar`. A green
Linux lane does not qualify the unavailable Windows, GPU or J9 work.

```sh
python3 -m unittest discover -s scripts/release/qual/journeys -p 'test_*.py' -v
python3 -m unittest discover -s scripts/release/qual -p 'test_*.py' -v
```

The OCR fixture check requires Tesseract. These isolated tests never touch
the shared VM data, a display, running app,
network service or installed package. Before-state tests belong in a shared
clone under `$TMPDIR`; do not stash or revert the worktree for a baseline check.
