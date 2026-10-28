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
that user's text-file association. **Do not run it on the release owner's host.**

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
lane runs J1 only and does not require `--predecessor` or `--mirror`. To run
the hosted equivalent locally without installing anything on the host, use the
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
- J3: default production downloads, CPU search plan and receipts, search, and
  the plan's chat-unavailable explanation in the UI. Downloads are not seeded.
- J4: a UI-authored bookmark and theme, normal restart, retained settings,
  watched folder, indexed fixture, authored records and model file hashes.
  Escape closes existing popovers before one bookmarks click, with a wait for
  none open. A repeated failure records a suspected product defect and one
  plain-browser reproduction (or the concrete reason it could not run), then
  continues the remaining checks.
- J5 (VM): parent-state preparation, signed HTTPS update offer, cancelled
  PolicyKit prompt with old Engine recovery and re-offer, the **real 300-second
  dpkg lock timeout**, then accepted elevation and automatic candidate relaunch.
- J6 (VM): reinstall, apt remove, retained app data, reinstall and state checks.
- J7: menu entry, cold/warm OS deep links, X11 registration or the physically
  invoked GNOME Wayland `--focus-search` binding, and scanned-PDF OCR.
- J8: release MCPB and tagged registry version/URL/checksum/platform checks;
  pinned Inspector CLI over stdio through the **extracted release bridge** for
  tools/list, status and a fixture-returning search.

WebDriver sends pointer actions to DOM-derived viewport coordinates, after
scrolling controls into view. This avoids WebKitGTK's nested-shadow-root
element-click interception. Native dialog handling uses keyboard input and QMP
screenshots. Full-screen OCR uses sparse-text segmentation; screenshots remain
the primary evidence for diagnosing missed text and focus. Startup, persistence,
deep links and updater relaunch are also checked without WebDriver automation.

`corpus.py` generates eight small, original CC0 documents: text, long Markdown
and text reports, CSV, HTML, email, DOCX and an image-only PDF. The unique open
fixture is `JustSearch-journey-orchid-open.txt`; only the scanned PDF contains
`cobalt marmalade`. Per-run corpus hashes are retained. No external documents,
personal information, models or generated binary fixture files are committed.

## Results and tests

Each check has one of `passed`, `failed-product`, `failed-infrastructure`,
`skipped-by-decision`, or `unavailable`, a reason and evidence links. Missing
prerequisites stay unavailable. Assertions about product outcomes fail as
product failures; driver, focus, tool and transport failures are infrastructure.
Unknown harness exceptions also stay infrastructure. Skips and unavailability
never add a pass. An empty or incomplete report cannot report `passed: true`.
The process exits nonzero for an applicable failure or unavailable check;
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

These isolated tests never touch the shared VM data, a display, running app,
network service or installed package. Before-state tests belong in a shared
clone under `$TMPDIR`; do not stash or revert the worktree for a baseline check.
