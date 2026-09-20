# Agent Instructions for JustSearch Sandbox Validation

(Staged as both `CLAUDE.md` and `AGENTS.md` — one charter for either harness,
Claude Code or Codex. Harness-specific bits are called out where they differ.)

You are running inside a **Windows Sandbox** — an ephemeral, clean Windows
environment with no development tools, no source code, and no pre-existing
models. Your job is to validate a JustSearch **release candidate** end-to-end on
a clean machine and report where a first-time user would be confused, blocked,
misled, or scared.

## Read these first (per-round authority)

Five staged files govern this round. Read them before launching JustSearch:

1. **`coverage-brief.md`** — the generated, per-candidate list of the surfaces
   this release *must* exercise, derived from what the candidate actually ships
   (endpoints, UI surfaces, interaction rungs) plus the claims it publishes. This
   is the authority for *what to cover*; it cannot silently omit a newly-shipped
   surface. If a surface is on it and you cannot reach it, that is a finding.
2. **`validation-mode.md`** — the model mode for this instance (`fresh-install`
   vs `pre-staged-models` vs `upgrade-from-release` vs
   `in-app-update-from-release`). Overrides any static
   wording about host models.
   **Round-mode policy:** a release's FIRST round and its FINAL qualifying round
   MUST run `fresh-install` (the only mode that covers the real download path);
   intermediate convergence rounds MAY use `pre-staged-models` for iteration
   speed, and their evidence must be labeled as such. A release's qualifying set
   must additionally include **at least one `upgrade-from-release` round**
   (install the previous public release, seed minimal data, install the candidate
   over it) — real users arrive from the previous version, not only from a clean
   machine, and the strongest defect repro this harness ever produced came from a
   non-fresh arrival state (tempdoc 734 A.1, round 2). Tempdoc 750 Part C.
   For `in-app-update-from-release`, also follow
   `updater-qualification.md`: the installed source is a previous-source
   Sandbox build with the updater test gate, the target is served from the
   authenticated loopback closed set, and `collect-updater-evidence.ps1`
   captures the durable recovery oracle before interruption and after restart.
3. **`charter.md`** — this round's pre-registration (SBTM's *charter*, adapted —
   see *Retrospective / debrief* below): what the round is FOR, each open
   blocker's needs-round vs. needs-dig classification, the chosen mode and why,
   and expectations. Written before the round runs; the debrief is read against
   it. A round asked to verify something the charter classifies needs-dig should
   flag that, not silently spend itself on it.
   **A watch item that names only a broken signature is under-specified — do not
   file a finding on the signature alone.** Charters must state what the signal
   looks like when the build is HEALTHY as well as when it is broken (the rule
   lives in `docs/how-to/cut-a-release.md` step 2, addressed to whoever writes
   the charter). When one does not, establish the discriminator yourself before
   calling anything a defect, and record the charter's imprecision as a
   harness finding. Worked example (round 8, 2026-07-31): the charter said
   `Combined backfill: docs=N (embed=0,splade=0,chunks=0)` "repeating at high
   frequency … is the livelock; it must not appear." It appeared **143 times,
   six within 142 ms — and was not the livelock**: the lines carried real
   progress, the run terminated on its own, enrichment completed, and a
   60-second idle window afterwards produced zero new lines. Healthy backfill
   emits that exact signature. The defect is **non-termination** — the
   signature still firing while every coverage counter is static and ingest
   jobs are starved. Following that charter literally would have produced a
   false HIGH against a working build.
4. **`sandbox-environment.md`** — directory layout, what is staged, environment
   characteristics.
5. **`staging-gaps.md`** — assets the host failed to stage this round (e.g. the
   SciFact corpus, a Node installer). Each entry must be recorded as a
   round-level coverage gap, not silently absorbed.
6. **This candidate's convergence tempdoc** (`docs/tempdocs/NNN-<version>-sandbox-convergence.md`,
   staged read-only under `docs/`) — states what this round is *for*: which prior
   findings it exists to re-confirm fixed, and which are still open. A tempdoc is
   **dated history, not current truth** — it reflects what was known when it was
   written, not the state of the build you are running. Use it to know what to
   verify, not as a substitute for verifying it: check every claim it makes
   against the running candidate, don't just cite the tempdoc's own words back.

The final validation summary must state the mode explicitly, report coverage
against `coverage-brief.md`, and quote `candidate-provenance.md` (the staged
record of the installer's filename, SHA-256 and — when the build made it
derivable — its commit) so the archived evidence identifies the build it came
from without host-side archaeology afterwards.

## Mission (durable)

Prove the installed candidate works on a clean machine **and** that a first-time
user can understand it during setup and heavy local work. Two layers:

- **Cheap end-to-end sanity ladder** — installer boots, Install AI reaches a
  complete state, the selected runtime activates, one chat completion succeeds,
  one HYBRID/reranker query runs, and state survives a restart. This anchors that
  the core path is intact for *this* candidate. Do not assume a prior candidate's
  pass carries over — a release candidate is, by definition, a materially
  different build (new backend jars, new endpoints, new trust surfaces), so the
  thing that changed is exactly the thing most worth testing.
- **Frontend / trust truthfulness** — after sanity passes, organize by **user
  journey**, not backend subsystem. The highest-value findings are where the UI
  disagrees with reality: a label that says "Ready" while the API says otherwise,
  a mode that misrepresents the response, a trust surface (encryption status,
  memory, skins) that misleads. UI/API disagreement is a finding even when the
  API passes.

**GUI-capture launch requirement.** Surface-tier coverage (screenshots, the
Frontend / trust truthfulness layer above) needs a PNG on disk, not a
computer-use tool — nothing requires the screenshot come from a tool call.

**Claude-for-Chrome (`claude --chrome`) is RESOLVED NEGATIVE — do not attempt
it again.** A verification spike (tempdoc 727-followup smoke round) found it
doubly blocked: (1) the paired Chrome is the operator's **HOST** browser, not
one inside the sandbox — `list_connected_browsers` reports `isLocal: true`
for it regardless, which is a trap, since pairing follows the Claude account
to the host machine, not the sandbox; sandbox loopback (`127.0.0.1:<port>`)
is unreachable from it while a public page like `example.com` loads fine. (2)
An installed build serves **no HTTP SPA at all** — every route probed returns
404 except the API surface, so even a sandbox-local Chrome would have nothing
to point at. Either blocker alone is fatal; both apply. Do not stage or
recommend the Chrome MSI for this purpose again.

**If your harness has a computer-use capability (Codex: Computer Use), drive
the GUI with it** — it exercises the app the way a user does, which is the
point of this round. The only hard requirement is that each surface ends up
as a PNG on disk under its coverage filename.

**Codex: an empty `cua_repl` app inventory is NOT a negative result.** Round
18's unified `cua_repl` surface reported `apps=[]` (it was browser-only in
that task) while the staged Computer Use skill's `node_repl` + `@oai/sky`
path drove native windows and captured File Explorer in the same session.
Probe `@oai/sky` through `node_repl` — list the native apps and save one
image to disk — **before** reading an empty unified inventory as "no native
Computer Use". Only both probes failing is a negative (see the Step-0 probe
in the `/start` skill).

**Codex: verify your first capture's magic bytes.** Round 18's Computer Use
wrote its screenshots straight to the coverage filenames but as **JPEG bytes
under `.png` names**; coverage credit is by filename token, so nothing failed
and the whole round's evidence was mislabelled bytes. After the FIRST capture:

```powershell
$b = [System.IO.File]::ReadAllBytes("evidence\01-first-paint.png"); '{0:X2} {1:X2} {2:X2} {3:X2}' -f $b[0],$b[1],$b[2],$b[3]
# 89 50 4E 47 = PNG (good).  FF D8 ... = JPEG -> convert, then keep checking.
```

`gui\convert-cu-images.ps1 -Directory evidence` converts JPEG-bytes-in-`.png`
files in place (promoted from round 18's own `round-tools/`), but repairing at
finalize is the fallback — checking after the first capture is the fix.

**Guaranteed floor: the native PowerShell GUI tier**, staged at
`<mapped folder>\gui\` (`snap.ps1`, `win-capture.ps1`, `click.ps1`,
`crop.ps1`, `gui-approve.ps1` — see `gui/README.md`). It drives the **real**
Tauri WebView2 shell via screen/window capture and
`SendKeys`/`mouse_event` input — proven end-to-end including a full
GUI-driven TYPED_CONFIRM approval (backend-verified: grant issued, docCount
incremented, file searchable). It needs no tool, no extension, no pairing, no
account, and no network, and it caught a HIGH-severity trust-surface finding
(an expired pending authorization presenting a live-looking but dead
Approve/Deny ceremony) that the API tier's clean PASS on the same feature
could not see. Coverage credits the PNGs these scripts write, exactly like any
other screenshot — so it is also the fallback for writing the evidence file
when a computer-use tool cannot save its screenshot to a path you choose.

**Precisely, the floor is `snap.ps1`, which falls back to a per-window
capture when the desktop DC is unavailable** — it is not "`CopyFromScreen`
always works". Round 18 (finding H2) hit the counter-example: on the
Sandbox's RDP indirect display (`rdpidd.inf`) every `CopyFromScreen` throws
**`The handle is invalid` (E_HANDLE)** under PowerShell 7 and 5.1 and writes
no PNG, while per-window capture and Windows Graphics Capture kept working.
`snap.ps1` now retries such a failure with
`PrintWindow(PW_RENDERFULLCONTENT)` against the JustSearch shell window
(located by the OS process `ExecutablePath`, or by `-Hwnd`/`-ProcessName`),
and `win-capture.ps1`/`click.ps1` retry through the same primitive — so a
whole-desktop failure costs you the desktop frame, not the GUI tier. It
remains a finding worth recording if you hit it; it is no longer a blocker.
Details and the `-ForceWindowCapture` switch: `gui/README.md`.

Alternative for the future: the tauri-driver/WebView2 path (tempdoc 374 item
4, POC'd) — structured, element-based targeting instead of pixel coordinates,
worth having eventually but not currently blocking anything.

Either way, the Step-0 capability probe (staged as the `/start` skill,
`sandbox-start-SKILL.md`) remains the fail-loud guard — it now checks BOTH a
computer-use tool AND the native-capture path before declaring a round
API-only (see that skill for the amended rule).

Cover every `sandbox`-tier item in `coverage-brief.md`, or record why an item was
not reachable. Items marked "covered elsewhere (host tier)" are verified by a host
test and need only a reachability spot-check here; items marked exempt are not
Sandbox-validated. At finalize, run the coverage check (see *Coverage & evidence*)
so an untouched required surface fails the round rather than being forgotten.

## The `/mcp` product endpoint (verify it — it is the point of this release line)

JustSearch serves a production **MCP endpoint** at `POST /mcp` (loopback), the
"private retrieval backend for agents" the README advertises. This is a *product*
surface, distinct from the developer MCP dev-tools that are absent in the sandbox.
**The documented Inspector CLI reachability check now FAILS on this build** (round
10, tempdoc 734/804): the packaged candidate boots `prod=true` (see *Key API
endpoints* below), `POST /mcp` enforces the session token like every other
mutating route, and a plain `npx @modelcontextprotocol/inspector --cli
"http://127.0.0.1:<port>/mcp" --transport http --method tools/list` 401s with no
`WWW-Authenticate` header — the Inspector CLI infers OAuth from the bare 401 and
demands an interactive TTY it cannot get in a round. **Verify reachability with a
raw `POST /mcp` JSON-RPC `tools/list` call carrying the session token instead**
(fetch it from `GET /api/mcp/token` — see *Key API endpoints* below for the
token-fetch pattern):

```powershell
$port = (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
$token = (Invoke-RestMethod -UseBasicParsing "http://127.0.0.1:$port/api/mcp/token").token
Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$port/mcp" `
  -Headers @{ "X-JustSearch-Session" = $token } -ContentType "application/json" `
  -Body '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expect the tool set to come back. If you must use the Inspector CLI itself, it has
no flag for a custom auth header on this build, so it is not currently a working
substitute — record the limitation and use the raw-POST workaround above. If
`node` is not installed at all (for the mutating-tool step below), record that MCP
was not exercised and why (a gap to close), rather than skipping silently.
Protocol conformance itself is owned by a host integration test; the Sandbox's
unique job is proving clean-install reachability and discoverability.

**The mutating-tool step needs a different client.** The Inspector CLI's
`--tool-arg` string-coerces every value and cannot express `justsearch_ingest`'s
`paths: string[]` argument, so `tools/call justsearch_ingest` cannot be driven
through it. Use the staged `mcp-client\mcp-typed-confirm.mjs` instead (run as
`node mcp-client\mcp-typed-confirm.mjs --target <path>`; see
`mcp-client\README.md`) — it drives the REAL shipped MCPB stdio bridge
(`mcp-client\index.js`, a verbatim copy of `packaging/mcpb/server/index.js`) to
exercise the TYPED_CONFIRM procedure. See `governance/sandbox-coverage.v1.json`'s
`cohort:mcp` `validateHow` (surfaced in your `coverage-brief.md`) for the exact
required sequence.

## What's available

- **Mapped folder** at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` —
  contains the JustSearch installer, this file (as `CLAUDE.md` and `AGENTS.md`),
  `coverage-brief.md`, `validation-mode.md`, `docs/`, `.claude/` (Claude Code),
  `.codex/` + `.agents/` (Codex), `collect-evidence.ps1`, the
  `gui/` native GUI capture/input harness (see *GUI-capture launch
  requirement* below and `gui/README.md`), and a `tools/` directory for
  installers staged from the host.
- **Models** may be mapped at
  `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\` only in
  `pre-staged-models` mode. Read `validation-mode.md`; never set
  `JUSTSEARCH_MODELS_DIR` during a `fresh-install` round.
- **PowerShell** and standard Windows tools; **internet access** (for model
  downloads, Claude OAuth / Codex sign-in, Git/Chrome installs if not pre-staged
  in `tools/`).

## What's NOT available

- No source code, no Gradle, no JDK, no Node.js (unless you install it)
- No `jseval`, no JustSearch **dev-tools MCP**, no worktrees, no agent telemetry
  (these are developer tooling — unrelated to the product `/mcp` endpoint above)
- `nvidia-smi.exe` is NOT on PATH in the sandbox (don't use it as a CUDA probe)
- No automatic install — you install Git and JustSearch yourself (the operator
  installed the harness you are running in).

## GPU characteristics (durable)

Windows Sandbox passes the host's NVIDIA card through for vGPU. The probes that
matter:

- **NVML** (`nvml.dll`, System32): works — reports VRAM + driver version;
  `/api/ai/runtime/status` shows `vramDetectionSource: "nvml"` with a non-zero
  total.
- **`nvcuda.dll`** (CUDA driver API, System32): loadable; `cuInit` +
  `cuDeviceGetCount` succeed. The install gate uses this driver-API probe, so
  chat installs and runs on the bundled cuda12 llama-server variant at GPU speed
  without a system CUDA toolkit.
- **`nvidia-smi.exe`**: NOT on PATH — the legacy shell-out detector returns -1
  here. Use `/api/ai/runtime/status` (NVML-first) for authoritative GPU metadata.

On a low-VRAM host (< the chat package's VRAM floor), Install AI *skips* chat and
the GPU runtime package and everything falls back to CPU — that is expected
behaviour on that profile, not a regression. Installer size and the model /
GPU-runtime package set are candidate-specific and change per release — do not
hard-code figures from memory; the authority for *this* candidate's asset set is
its published `SHA256SUMS` / GitHub Release (the 726 asset pipeline), per
`docs/how-to/cut-a-release.md`.

## Setup (manual)

1. **Git** — run `tools\Git-Setup.exe /VERYSILENT /NORESTART /NOCANCEL /SP-`
   (or download Git for Windows if not pre-staged).
2. **Your harness is already installed and configured** — the operator did
   that (install, sign-in, trust: `sandbox-environment.md`, operator-facing).
   One harness-specific check, Codex only: this file reaches you as
   `AGENTS.md`, and Codex stops reading at 32 KiB unless the staged
   `.codex/config.toml` was loaded (it only loads for a trusted folder).
   **Confirm you can see this file's last section ("Independence invariant")
   before doing anything else**; if you cannot, stop and tell the operator to
   trust the folder and restart you. Permissions are already handled for
   either harness (bypass mode / no inner sandbox — Windows Sandbox is the
   isolation boundary).
3. **JustSearch** — run the `*-setup.exe` in the mapped folder. Per ADR-0024, the
   NSIS installer is **per-user** and lands at `%LOCALAPPDATA%\JustSearch\`, NOT
   `C:\JustSearch\`. User data (downloaded models, index, logs, runtime state)
   lives separately at `%APPDATA%\io.justsearch.shell\`.

## Coverage & evidence (mechanize capture, keep judgment)

Two staged tools make rounds repeatable and make coverage fail closed:

- **`collect-evidence.ps1`** captures the mechanical layer: it discovers the
  backend port from the runtime manifest, hits the API sanity ladder, exercises
  `/mcp` via the Inspector CLI, and saves raw snapshots into `evidence/`. Run it
  early and after each major step. It *captures*; the honesty/scary-UI judgment
  stays with you. Re-running it is now **non-destructive**: each invocation also
  copies its ladder snapshots into `evidence/api-history/<UTC timestamp>/` and
  appends a line to `evidence/collect-runs.ndjson`, so the product's progression
  through install/enrichment survives (the fixed-name snapshots at the evidence
  root are still overwritten in place — they are the "latest" copies other tools
  read by name).
- **Endpoint tracing** — launch JustSearch with `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`
  so every API request is recorded to `%APPDATA%\io.justsearch.shell\telemetry\traces.ndjson`.
  This is the empirical record of which endpoints the round actually exercised.
  (The `.wsb` launcher sets this env var for you via `setx`; if you launch the app
  another way, set it yourself first. Accepted values: `none` (default, no spans),
  `sample` (1%), `detailed` (all).) `collect-evidence.ps1` **copies** this file into
  the evidence dir so it survives sandbox teardown.
- **Save all evidence under the mapped folder** (`Desktop\JustSearchTest\evidence\`) —
  only that folder persists on the host. Name UI screenshots so the surface is
  identifiable (e.g. `NN-security-panel.png`, `NN-rag-ask-answer.png`); **surface
  coverage is credited from screenshots only** (image files), never from the API-JSON
  snapshots the harness also writes there.
  **The match is a literal substring of the filename against a per-surface
  `evidenceToken`, not a semantic check** — for the chat/search escalation-ladder
  surface (`core.unified-chat-surface`), the required token is `unified-chat`
  (derived mechanically from the surface id: strip `core.` and `-surface`).
  Round 15 screenshotted that surface heavily under names like `09-tour-step2.png`
  and never once used the literal substring `unified-chat`, so a genuinely
  well-covered surface read as uncovered at finalize. **Every chat-surface capture's
  filename must contain `unified-chat`** (e.g. `12-unified-chat-search.png`,
  `13-unified-chat-rag-ask-answer.png`) — do not rely on a looser word like "chat" or
  "search" alone to satisfy this token. The `shape` items carry a SECOND token of
  their own (`core.rag-ask` → `rag-ask`, `core.extract` → `extract`, `core.agent-run`
  → `agent-run`): the Documents-rung answer capture must carry `rag-ask` as well as
  `unified-chat`, in ONE filename — one file naming both tokens is the sanctioned way
  to credit both (round 18 named its Documents-rung answer `…-unified-chat-ask-answer.png`
  and the shape read as uncovered at finalize).
- **At finalize (host-side)**, the round's coverage is asserted by diffing the
  must-touch set against the exercised endpoints + screenshots. Because the sandbox
  has no Python, this runs on the **host** against the persisted evidence dir after the
  round:
  ```
  python scripts/sandbox/check_coverage.py \
    --manifest tmp/sandbox/share/coverage-manifest.json \
    --traces   tmp/sandbox/share/evidence/traces.ndjson \
    --evidence-dir tmp/sandbox/share/evidence
  ```
  An untouched `sandbox`-tier surface is a **blocking finding** (non-zero exit).
  The same check also fails closed if `evidence/retrospective.md` is missing or
  too thin — see *Retrospective* below — and if `evidence/evidence-review.v1.json`
  is missing, omits a credit-eligible screenshot, or reports a mismatch — see
  *Evidence review* below. Filename-token matching proves a screenshot's NAME
  claims a surface; it cannot prove the PIXELS show it, so a reader pass over
  every credited screenshot is a required, separately fail-closed gate, not an
  optional judgment call.
  Four further artifacts are required by the same check, each separately
  fail-closed (all under *Writing results* below): **`findings.md`** (this round's
  defect report as its own artifact — see *Findings* below),
  **`mustwatch-verdicts.v1.json`**
  (a verdict for every must-watch id in this round's brief),
  **`session-analysis.md`** (the session-vs-harness debrief), and
  **`mutating-probe.v1.json`** (written for you by `collect-evidence.ps1` — a
  `status: "fail"` there means the product's whole mutating surface was dead while
  every GET rung read green, and it now fails the round instead of only printing).
- **Token health (host-side, tempdoc 805 Part G.4)** -- the same `traces.ndjson`
  is also checked for round 11's discriminator: any POST/PUT/DELETE span
  answered 401 in under 5ms is the auth-filter-rejection fingerprint (a
  missing/stale session token reaching the webview), promoted from prose to a
  mechanical, fail-closed gate:
  ```
  python scripts/sandbox/check_token_health.py tmp/sandbox/share/evidence/traces.ndjson
  ```
  Allowlisted: a fast 401 on `/mcp` immediately followed (within 60s) by a 200
  on `/mcp` -- an external MCP client probing once without a token and then
  retrying with one is expected, not a defect. A blocking finding here means a
  webview call fired without (or with a stale) session token during the round.
- **`traces.ndjson` cannot be parsed line-by-line as JSON — two traps, both
  already hit once (round 12, tempdoc 806 W3 item 2).** (1) The HTTP
  attribute container in each span is **`attrs`**, not `attributes`. (2)
  Span `attrs` embed document excerpts containing **CRLFs**, so a "line" of
  the NDJSON is often not a complete JSON document -- `Get-Content |
  ConvertFrom-Json` throws on many lines. Round 12's first in-round self-check
  reported **"mutating spans: 0; of those 401: 0"** from exactly this bug --
  a **false clean pass** on the token-health discriminator above, caught only
  because the round applied *"absence of signal is not evidence of
  absence"* and re-derived the field names before trusting the answer. The
  working method is **regex over raw text, never JSON parsing**:
  ```powershell
  # discover the real attribute keys first
  Select-String -Path $tr -Pattern '"(http\.[a-z_.]+)"' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } |
    Group-Object | Sort-Object Count -Descending
  # then the token-health discriminator
  $hits = Select-String -Path $tr -Pattern '"http\.method":"(POST|PUT|DELETE)"' -AllMatches
  $hits | Where-Object { $_.Line -match '"http\.status_code":"?401' }
  ```
  This is already staged as **`analyze-traces.ps1`** (next to `oracle.ps1` /
  `chat-ask.ps1` / `redact.ps1`) -- run it against a live round's traces
  file before trusting a manual re-derivation:
  ```powershell
  .\analyze-traces.ps1 -TracesPath "$env:APPDATA\io.justsearch.shell\telemetry\traces.ndjson"
  ```
  It prints the discovered `http.*` attribute keys with counts, the
  mutating-span count, and any mutating span answered 401 -- the same
  numbers `check_token_health.py` asserts on host-side at finalize, so a
  round can catch a real blocker (or confirm a clean build) itself instead
  of waiting for the host-side re-run.

### Search parity (golden queries)

The Sandbox cannot measure absolute search quality (no jseval here). Instead the round checks
**parity with dev**: the operator generated a per-candidate "golden" expected-results baseline
(`golden-parity.json`) by running the fixed query set (`golden-queries.json`) against the dev
stack on the SAME build + SAME corpus (scifact) this round uses. Your job in-round is only the
capture step, already wired into `collect-evidence.ps1` — if `golden-queries.json` is staged next
to it, the script POSTs each query to `/api/knowledge/search` (hybrid, limit 10) against your
running candidate and saves the raw responses to `evidence/golden/<queryId>.json`. No judgment is
required from you here; the tolerance comparison against the baseline runs host-side at finalize
via `check_golden_parity.py`.

**The capture is gated on a warm GPU embedding session (round 16, tempdoc 823 §3).** Before
capturing, `collect-evidence.ps1` reads the `embed` entry of `/api/ai/runtime/status`'s
`onnxFeatures` array (note: an ARRAY keyed by `id`, not an object with an `embed` property) and
requires `executionProvider: "cuda"` with `gpuFallback: false`. The CUDA session is created
lazily on the first inference batch, so a capture taken too early runs CPU-FP32 query vectors
against a GPU-FP16 baseline — exactly what made round 16's parity check exit 1 on three queries
with the dense leg alone collapsing while SPLADE and text stayed high. If the session is cold the
script triggers a vector-mode warm-up search and polls for up to 3 minutes; if the session is still
reported as fallen back to CPU (`gpuFallback: true`) it **auto-skips the capture** with a note (same
mechanism as the corpus-ratio floor above) rather than producing evidence that reads as a ranking
regression. A host that is genuinely CPU-only — `executionProvider: "cpu"` with no `gpuFallback` —
has no GPU session to wait for, so the capture **proceeds** and is labelled
`captureCondition: "cpu-native"` with the reasoning in `golden-capture-note.txt`; comparability is
then host-side's call (`check_golden_parity.py` fails typed on the embedding-fingerprint mismatch,
and such a host needs its own CPU-generated baseline). Either way the observed state, including
`captureCondition`, is written to `evidence/golden-capture-ep.json`, so finalize can always see the
condition the capture was taken under. A skip is a recorded gap: re-run `collect-evidence.ps1` after
real search/enrichment traffic has warmed the session. If `staging-gaps.md` lists a missing golden-parity baseline for this
candidate, record that as a round-level coverage gap (per the protocol above) rather than
attempting to judge search quality yourself.

Parity is only measurable when the round ran the **same embedding weights** as the baseline —
`check_golden_parity.py` checks this automatically via `embeddingFingerprintCurrent`
(`/api/knowledge/status`), which IS the SHA-256 of the loaded embedding model file (audited
2026-07-14). A `pre-staged-models` round maps the host's models; a `fresh-install` round downloads
them — on GPU these are byte-identical, but a CPU-only round loads FP32 (`model.onnx`) where a
GPU-generated baseline used FP16 (`model_fp16.onnx`), so a CPU round needs its own CPU-generated
baseline, not the GPU one. The finalize check also fails closed if the round's indexed corpus is
far smaller than the baseline's, or if any captured golden response shows `dense-retrieval`
skipped (hybrid silently collapsed to BM25) — both would otherwise surface as a phantom ranking
regression instead of the real cause.

**Calibration provenance (every threshold names the population it was sampled on —
tempdoc 750 P2 "calibration names its axes"; applying a threshold across an unsampled
axis is itself a finding, not a formality):**

- **Same-machine dev-rebuild population** (n=3 clean dev rebuilds, scifact, GPU-FP16,
  same corpus/code/model, 30 query-observations, 2026-07-15): overlap never drops
  below 9/10, top-1 never moves (0/30), only 2 queries ever shift, by exactly one
  doc. Axes held constant: GPU + driver, embedding-inference environment, index
  build environment. This envelope covers **rebuild noise only**.
- **Sandbox↔sandbox population** (round 5 vs round 6, two different builds,
  fingerprint-identical weights, 2026-07-17 — tempdoc 750 §Derisk): all 10 queries
  share 10/10 result docs across the two rounds; per-hit **dense-leg score deltas
  never exceed 1.8e-4**. Embedding inference inside the Sandbox environment is
  highly reproducible; this is the measured basis for the baseline's
  `denseScoreEnvelopeAbs`.
- **Dev↔sandbox (the axis the finalize comparison actually spans) is NOT yet a
  calibrated population.** Rounds 5 and 6 both diverged from the dev baseline
  identically (q04 6/10, q06 5/10, q08 4/10) — a **systematic** dev-vs-sandbox
  difference, not round noise. Its cause is 734 finding 5's open question; the v2
  baseline's per-pair dense-score comparison is the discriminating instrument
  (embedding-output variance vs. HNSW selection variance).

Two earlier readings recorded here are superseded on evidence: "likely
HNSW/approximate tail churn" (refuted by the dev-rebuild calibration — `q08` is
10/10 across every rebuild pair) and the CPU-FP32-vs-GPU-FP16 hypothesis (refuted by
round 6, which ran GPU-FP16 fingerprint-identical to the baseline and still
diverged). The FP16/FP32 note above still matters operationally — a CPU round needs
a CPU-generated baseline — it just is not finding 5's cause. Treat a sub-7 overlap
as a **finding to explain, not** noise to wave through; the check's report now
carries typed reasons and per-leg attribution so the explanation can start
host-side instead of costing another round.

**Policy status (owner decision 2026-07-30, tempdoc 798 B7 / 750 option A4):** a
sub-7 overlap is **DESCRIPTIVE — reported in full, but it does not block the
round**. The floor was calibrated only on the same-machine dev-rebuild
population; a dev to Sandbox comparison crosses a boundary that calibration never
sampled, so a sub-floor overlap there is an uncalibrated measurement rather than a
demonstrated ranking regression. The **blocking** assertion is the
environment-robust one: golden #1 within the captured top-3. This is a demotion of
what the number decides, not of what is measured or reported — explaining a
sub-floor overlap is still expected of the round.

## Required validation phases

1. **Installer launch and security prompts** — run the installer from the mapped
   folder. Capture any SmartScreen / Defender / Smart App Control /
   unsigned-publisher UI, any failure to honour `/S`, and the exact action needed
   to continue. (These Windows-trust prompts cannot be CI-gated — they are the
   Sandbox's unique responsibility; see the must-watch items in `coverage-brief.md`.)
   **The no-admin claim is verified host-side, not by this round.** The
   README's "no admin rights needed" claim is now asserted mechanically by
   `scripts/ci/check-installer-execution-level.mjs`, which checks both the
   source config (`bundle.windows.nsis.installMode: currentUser`, ADR-0024)
   and — when a built installer is available — the BUILT installer's embedded
   Windows manifest (`requestedExecutionLevel="asInvoker"`). The round does
   not need to prove this. Your one residual job: **if any elevation prompt
   appears during the JustSearch install, that is a finding** — note the
   publisher shown and report it.
   **Elevation posture: every round in this image runs ELEVATED, and no round
   can observe a UAC prompt. This is a property of the image, not of the
   round.** The Windows Sandbox image has `EnableLUA=0`, so Windows issues no
   filtered/split token at all: `explorer.exe` itself runs elevated, every
   process inherits that, and the de-elevation tricks (relaunch via Explorer,
   `runas /trustlevel`) have no non-elevated token to inherit. Round 16
   established this to root cause; the evidence is
   `evidence/elevation-check.txt` from that round (2026-08-12), and
   `collect-evidence.ps1` re-writes that file at Step 0 every round.
   Consequences you must record rather than work around:
   - **Any UAC-observation item is `unobservable` with this reason**, never a
     pass and never silence. UAC also renders on the secure desktop, which
     screenshots cannot capture — but the elevated process tree is the binding
     constraint: it is never re-prompted in the first place.
   - **The round does not reproduce a normal user's integrity level** and can
     therefore mask permission defects a real user would hit — a pass that
     depends on an environment precondition is not a pass (this repo's
     `green-masked-destructive` principle). Say so in the round's summary.
   - The earlier instruction here ("Run this round non-elevated") was
     **unfollowable as written** and cost round 16 a real detour. It is
     removed rather than softened.
   **Posture decision PENDING (owner):** either `generate_wsb` sets
   `EnableLUA=1` (costs one reboot inside sandbox boot; restores a normal
   user's integrity level and makes the trust prompts reproducible), or the
   Sandbox tier drops the non-elevated expectation and accepts the blind spot
   explicitly. Until that is decided, the `.wsb` posture is unchanged and this
   text states the fact — do not treat the blind spot as closed.
   **Launch the installer DETACHED, never with `-Wait` (round 12, tempdoc 806
   W3 item 5).** The candidate installer is interactive and parks on a wizard
   page — `Start-Process -FilePath ... -Wait` blocks until the process exits,
   which never happens until you click through it, and the tool call's own
   10-minute timeout then kills the whole process tree (round 12 lost ~12
   minutes this way; the machine stayed cleanly at the prior version, but the
   round still had to redo the launch). Launch detached and drive the wizard
   from separate calls, one page per tool call:
   ```powershell
   $p = Start-Process -FilePath ".\JustSearch_0.2.0_x64-setup.exe" -PassThru   # NO -Wait
   # then: capture -> crop -> read -> click.ps1, one wizard page per tool call
   ```
2. **First app launch** — does `%LOCALAPPDATA%\JustSearch\JustSearch.exe` start
   and render? Save `evidence/NN-first-paint.png`.
3. **Backend health** — read the runtime manifest, then save raw `/api/health`,
   `/api/status`, `/api/ai/runtime/status`, and `/api/inference/status` snapshots
   (`collect-evidence.ps1` does this).
4. **Install-dir hygiene** — run the jar-uniqueness check from `/start` before
   trusting runtime behaviour.
5. **Pre-Install-AI UI sanity** — help search, Library surface, Health/System,
   Brain/Install AI, status badges, console errors.
6. **Install AI** — in `fresh-install` mode, validate the full model + GPU-runtime
   download through the UI, backed by `/api/ai/install/status` snapshots. In
   `pre-staged-models` mode, label the evidence shortcut-only.
   **On ANY package download failure, run the staged manual-fetch control
   BEFORE forming a hypothesis** (round 16, tempdoc 823 §4): `.\probe-download.ps1
   -Url <the failing URL from the manifest> [-ExpectedSha256 <digest>]`. It
   fetches with the same `curl.exe` flag set the product falls back to and prints
   the BITS service state, HTTP status, bytes, elapsed time and SHA-256 — one
   command that partitions environment-vs-product. Round 16's wedged package
   fetched by hand in 0.41 s with a matching digest, which reframed the whole
   finding (and refuted the round's leading root-cause hypothesis).
7. **Library / indexing journey** — add a folder through the UI where possible;
   for the full corpus, ingest `Desktop\JustSearchTest\scifact\`. Verify
   per-folder row state, Tasks panel live updates, and `/api/knowledge/status`
   agree.
8. **Search / Chat / Brain / Health UI** — run real searches from the UI and
   compare rendered results with API responses. Walk the escalation ladder
   (Search → Documents → Structured → Agent); each rung's label/affordance must be
   honest, and AI-requiring rungs must disable with a clear reason when AI is
   offline (not a dead click).
9. **`/mcp` external-client check** — see *The `/mcp` product endpoint* above.
10. **Trust surfaces** — Security & Privacy (encryption-status truthfulness + the
    irreversible chat-passphrase flow + a recovery key), Memory (inspect/forget;
    empty state reads private-by-default), and Appearance/Skins (apply a built-in
    skin, use the editor, import a skin JSON — never leave an illegible/broken
    surface; the choice survives restart). These map to the privacy/threat-model
    claims the release publishes, so a misleading surface here is high-severity.
11. **Restart cycles** — one cold restart for a frontend-focused round; three when
    the target is lifecycle, persistence, port binding, or wireup. A restart means
    killing ALL FOUR processes — the Tauri shell (`JustSearch.exe`), Head
    (`javaw.exe` under `resources\headless\`), Worker (a second `java.exe`), and
    `llama-server.exe` if active. Closing only the shell window reconnects to the
    same still-running backend and proves nothing; verify a genuine restart by the
    runtime manifest's `instanceId`/`pid` changing. Filter kills by process **Path**
    (`*\JustSearch\*` / `*io.justsearch.shell*`), not bare `ProcessName` — a bare
    `java`/`llama` pattern can kill unrelated processes.
    **The authority for "which process is the shell" is the OS process
    `ExecutablePath`** (`Get-Process ... | Select-Object Path`, i.e.
    `%LOCALAPPDATA%\JustSearch\JustSearch.exe` per ADR-0024) — **never a
    computer-use app identifier**: round 18's Computer Use inventory named a
    per-harness LocalCache COPY of the app, which is not the process the
    installer runs, so killing or targeting "the app" by that identifier
    leaves the real shell alive. The two checks are one procedure: kill by
    `ExecutablePath`, then prove the restart happened by the manifest's
    top-level `instanceId`/`pid` changing. A "restart" that did not move
    those values did not restart anything, whatever the window did.
12. **Uninstall** — run only after all evidence is saved, unless told to defer.
    Verify program files are removed and user-data behaviour matches ADR-0024.

## How to test

The backend binds to `127.0.0.1` on a port disclosed two ways:

1. **Read the runtime manifest** (canonical, fastest; tempdoc 501):
   ```powershell
   (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
   ```
2. Fallback if the file is missing:
   ```powershell
   Get-NetTCPConnection -State Listen | Where-Object { $_.LocalAddress -eq '127.0.0.1' }
   ```

**Manifest field paths.** `instanceId`, `pid`, and `lifecycle` are **top-level**
in `runtime/manifest.json`, NOT under `.head` — only `apiPort`, `apiBaseUrl`,
and `readyAt` are nested under `.head`. The restart check in phase 11 above
reads `instanceId`/`pid` directly off the manifest root (`$j.instanceId`, not
`$j.head.pid`, which reads empty).

**PowerShell 5.1 hides API errors unless you ask.** Always pass
`-UseBasicParsing` to `Invoke-WebRequest`/`Invoke-RestMethod` — without it a
call can silently write a 0-byte evidence file instead of failing loud.
Separately, `Invoke-RestMethod` throws on any non-2xx response and by default
**discards the response body** — a JSON error like `{"error":"missing
pendingId"}` is invisible unless you catch the exception and read
`$_.Exception.Response`. A prior round burned 15 minutes brute-forcing
request shapes against a server that was telling it exactly what was wrong
the whole time. Wrap mutating calls in `try/catch` and print the body on
failure before concluding the endpoint is broken.

**The packaged candidate boots `prod=true` — every mutating call needs the session
token.** This is NOT the dev stack: a Sandbox candidate is the SHIPPED package,
and `ApiSecurityFilters` enforces the session token on every `POST`/`PUT`/`DELETE`
globally, with no path exemption (it applies to `/mcp` too). `GET`/`OPTIONS` need
no token. Fetch the token once from `GET /api/mcp/token` (itself unauthenticated by
design — the desktop UI/shipped MCPB bridge use exactly this pattern) and attach it
as the `X-JustSearch-Session` header on every mutating call; omit it and you get a
`401` with `{"error":"Missing or invalid session token","errorCode":"UI_TOKEN_REQUIRED"}`,
not the endpoint's normal response. Worked example:

```powershell
$port = (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
$token = (Invoke-RestMethod -UseBasicParsing "http://127.0.0.1:$port/api/mcp/token").token
$headers = @{ "X-JustSearch-Session" = $token }
Invoke-RestMethod -UseBasicParsing -Method Post -Uri "http://127.0.0.1:$port/api/knowledge/search" `
  -Headers $headers -ContentType "application/json" -Body '{"query":"test","limit":5}'
```

Key API endpoints (`GET` needs no token; every other method needs the
`X-JustSearch-Session` header above):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/health` | GET | Lifecycle state |
| `/api/status` | GET | Full system status |
| `/api/knowledge/search` | POST | Search (`{"query":"...","limit":5}`) |
| `/api/knowledge/ingest` | POST | Prepared ingest (`{"paths":["..."]}`); require `success:true`, then query `/api/operation-history/{operationKey}` using `structuredData.operationKey` |
| `/api/knowledge/status` | GET | Index/enrichment progress |
| `/api/indexing-jobs/failed` | GET | Failed extraction jobs, **substrate shape — rows carry `scanId`** (also `/by-prefix`). This is the discriminator for any scan-id claim; the legacy `GET /api/indexing/failed-jobs` returns a `FailedJob` record that has **never** carried `scanId` by design, so reading it "proves" a missing id that was never there (round 18 F2). |
| `/api/indexing/roots` | POST | Add a folder to the library (`{"path":"...","collection"?:"..."}` — `path` must be an existing directory; 400 names the offending field) |
| `/api/chat/ask` | POST | RAG Q&A (`{"question":"..."}` — NOT `query`). **Response is an SSE stream, not JSON** — see below. |
| `/api/ai/install/start` | POST | Start model download (`{"acceptTerms":true}`) |
| `/api/ai/install/status` | GET | Download progress. Top-level `state: "completed"` does NOT mean all packages installed; check `installedFully: true` and per-package `state`. |
| `/api/ai/runtime/status` | GET | Per-feature runtime status (NVML VRAM, ONNX `modelActive` flags) |
| `/api/inference/status` | GET | LLM runtime state |
| `POST /mcp` | POST | **Production MCP endpoint** (Streamable HTTP) — the agent-facing retrieval backend. Needs the session token like any other POST; see *The `/mcp` product endpoint* above for the current verification procedure. |
| `/api/mcp/token` | GET | Session-token issuance (unauthenticated by design — this is how a legitimate client gets the token in the first place) |

Full body shapes for every endpoint live in `docs/reference/api-contract-map.md` (staged under `docs/`).

**SSE requires the `Accept` header.** `GET /api/advisory/authorization-pending/stream`
called WITHOUT an `Accept: text/event-stream` header returns `200` with empty
`text/plain` body and closes immediately — indistinguishable from "no pendings
outstanding." Send the header to get the real `text/event-stream` response
(a `snapshot` frame plus live `UPDATE` frames). A host-side fix/test for the
server's silent-200 behavior is tracked separately (see the SSE observation
note filed against this finding).

**`/api/chat/ask` is a Server-Sent Events stream, not a JSON endpoint** — the
controller initializes SSE response headers before writing anything
(`ChatController.java`, its class doc + `initSseHeaders` call sites). Piping
the raw response through `ConvertFrom-Json` looks exactly like an empty
answer; it is actually a stream of frames. Consume it as SSE, not as a single
JSON body.

**Search hits key on `id`, not `path`.** `results[]` in a
`/api/knowledge/search` response is `id`, `score`, `fields` (a metadata map),
plus optional `matchedFields`/`matchSpans`/`excerptRegions` — there is no
`path` field. Guessing `.path` returns empty and looks exactly like "never
indexed." The full shape is already documented and staged at
`docs/reference/api-contract-map.md` (search under "Current hit shape in
`results[]`") — read it before asserting anything about a search response,
don't guess field names.

**Queue visibility** — `worker.core.pendingJobs` in `/api/status` is the FULL queue
depth (PENDING+PROCESSING) despite its name. `worker.migration.pendingJobsCount` in
`/api/knowledge/status` is PENDING-only and can read 0 while a job is stuck in
PROCESSING — always check its sibling `processingJobsCount` (same payload, also in
`/api/debug/state`) before concluding the queue is idle.

**Producing a retained failed-file row needs a CORRUPT file, not an empty or
locked one (round 18).** The long-standing recipe — "drop a zero-byte `.pdf`
or a file locked open by another process into a watched folder and rescan" —
produced **no failed-job row at all**: a zero-byte or unreadable file is
handled as *no content*, which is not an extraction failure and is not
retained as one. The fixture that works is a file whose header claims a
format its body then violates, e.g. a `%PDF-1.4` line followed by garbage:

```powershell
$f = "$env:USERPROFILE\Desktop\JustSearchTest\round-fixture\corrupt.pdf"
[System.IO.File]::WriteAllBytes($f, [byte[]](
  [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n") +
  [System.Text.Encoding]::ASCII.GetBytes("not a pdf body at all, no xref, no trailer`n")))
```

Then rescan and read `GET /api/indexing-jobs/failed` (see the endpoint table
above). The ingest response returns a durable operation key, not the internal job
`scanId`; query operation history by that key for committed progress and outcome.
The failed job is a separate wire fact; verify its identity and retained failure
independently from whether the failed-files drawer renders its scan metadata.

**A 401 renders as zero results in any client that doesn't check status.** The
packaged candidate boots `prod=true` (see *Key API endpoints* above); a `POST`
issued without the `X-JustSearch-Session` header 401s, and a client that only
inspects the parsed response shape — not the HTTP status code — sees
`{"error":"...","errorCode":"UI_TOKEN_REQUIRED"}` shaped exactly like an empty
result set. Round 10 nearly filed a catastrophic false HIGH finding ("the
upgrade emptied the index") for exactly this reason: a post-upgrade oracle run
read `hits: 0` on every query purely because its POSTs were 401ing, and only
the visible `UI_TOKEN_REQUIRED` error body caught it before the round wrote
the finding. Before concluding an index is empty or a search found nothing,
check the HTTP status code and error body first — never trust an empty-looking
result shape on its own.

**Absence of signal is not evidence of absence.** A wrong field name, a genuine
negative result, a silent no-op click, and a surface that never backfills all
render as "empty" — and they are indistinguishable from each other until you
check which one you're looking at. Before filing any negative finding ("X not
indexed", "Y not shown", "Z never fired"), confirm you are reading the right
field/endpoint/surface at all — ideally by first proving the positive control
works (a query you know should return hits, a WARN you know already fired).
The four traps above (hidden error bodies, the SSE-vs-JSON mismatch, `id` vs
`path`, and a 401 rendering as zero results) are concrete instances of this;
treat it as the general case, not just those four.

## Named diagnostic techniques

### Renamed-aside data dir

Used twice (round 9 and round 10, tempdoc 734) to reclassify a blocker as
**build-level** (reproduces on a pristine install, nothing to do with the
upgrade) vs **upgrade-specific** (only reproduces against carried-over user
state): stop all four processes (see *Restart cycles* above — the Tauri shell,
Head `javaw.exe`, Worker `java.exe`, and `llama-server.exe` if active), **first
copy the current `%APPDATA%\io.justsearch.shell\telemetry\traces.ndjson` to a
timestamped safe name under `evidence\`** (e.g.
`Copy-Item "$env:APPDATA\io.justsearch.shell\telemetry\traces.ndjson"
"evidence\traces-pre-rename-$(Get-Date -Format 'yyyyMMddTHHmmssZ').ndjson"`
— skip silently if the file does not exist yet), rename
`%APPDATA%\io.justsearch.shell` aside (e.g. to `io.justsearch.shell.bak` —
rename, do not delete, so the original round's data is recoverable), relaunch
the candidate against the now-pristine (non-existent) data dir, and re-test the
failing behaviour. If it still reproduces against a fresh data dir, the defect
is build-level, not an upgrade artifact — restore the renamed-aside directory
afterwards to resume the original round on its real data. This is what turned
round 10's F7 ("upgrade defect?") into "shipped-UI blocker, reproduced on a
pristine data dir" — that round's single highest-value diagnostic act.

**Why the explicit copy matters (round 15, tempdoc 817 finding 5):** the
pristine instance launched against the renamed-aside (non-existent) data dir
writes its OWN fresh, short `traces.ndjson` from scratch. `collect-evidence.ps1`
now auto-archives the evidence-root copy to `evidence\api-history\` before an
overwrite when the existing copy's first span predates the new file's first
span (so a plain re-run after restoring the real data dir will not silently
lose the earlier record) — but the round's own explicit copy above is still the
first line of defence, taken at the moment of highest risk (right before the
rename), not after the fact. Round 15's F1 reproduction skipped this and a
subsequent `collect-evidence.ps1` run copied the pristine instance's ~7-minute-
short trace over the round's full trace record; the finalize coverage check
then reported four false "uncovered" items because the real spans were gone.
**After restoring the renamed-aside directory, run the host-side coverage
check against the UNION of `evidence\traces.ndjson`, any
`evidence\traces-pre-rename-*.ndjson` you saved, and anything
`collect-evidence.ps1` auto-archived under `evidence\api-history\` —
not against the root file alone.**

### Verify-the-active-surface-before-clicking (GUI capture)

The DEFAULT loop for every GUI action, not an occasional precaution: **capture**
a fresh screenshot → **crop** the tab-strip/header region (`crop.ps1`, see
`gui/README.md`) so you can read which surface is actually active without
burning context on the full image → **confirm** the active surface matches
what you expect → **click**. Pixel-coordinate automation has no notion of "did
my click land on the surface I think is showing," and a screenshot taken even
one step earlier can be stale by the time the click fires — round 10 lost four
captures to exactly this coordinate drift (a click landed on the wrong tab
because the active surface had moved since the last capture). Do not click from
a screenshot older than the immediately-preceding capture, and do not treat a
zero exit code as proof the click landed on the intended surface — confirm from
the crop, then re-capture afterward to verify the action registered (`click.ps1`
already fails closed on a foreground-focus mismatch; `Assert-AppSurface` in
`gui/README.md` is the API-side confirmation for the same problem).

## Convergence — every finding gets a regression home

The release loop is build → verify → fix → rebuild at the same candidate number →
converge to zero findings (see `docs/how-to/cut-a-release.md`). For the loop to
converge instead of re-finding the same class, every confirmed finding must get a
regression home — **exactly one of**:

- a **gate/test in its natural tier** — backend/API regression → a host unit or
  live-stack test; UI-truthfulness → a ui-shot / RAIL step assertion; or
- a **`sandbox-must-watch` entry** in `governance/sandbox-coverage.v1.json` — for
  findings that cannot be CI-gated (Windows-trust prompts, clean-environment
  timing). These are re-injected into every future `coverage-brief.md`.

A **HIGH-severity finding must get a deliberate second reproduction under varied
conditions** (e.g. fresh install vs. warm reinstall) before the round closes — one
observation is a report, two varied reproductions are evidence. Same discipline as
rule 17 in the `/start` skill (verify a fix by triggering its condition), applied
to findings.

Record each round's findings and their routing decision in this candidate's
**convergence tempdoc** (`docs/tempdocs/NNN-<version>-sandbox-convergence.md`);
the durable "how" stays in `cut-a-release.md`, which does not accrete per release.

## Writing results

Files written to the mapped folder
(`C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest`) persist on the host after
the sandbox closes. Anywhere else (`C:\`, the user profile) is wiped on shutdown.
Report findings by journey with screenshot filenames and raw API/log evidence, and
state the coverage result against `coverage-brief.md`.

**Any tool you build during the round is saved under the mapped share, not the
sandbox-side scratchpad.** The scratchpad is wiped with the sandbox like
everything else outside the mapped folder. Round 16 built four working
instruments in it — `hwnd-drive.ps1`, `nav.ps1`, `ui-search.ps1`,
`pick-folder.ps1` — and all four were lost at shutdown; only their prose
descriptions in the retrospective survived, and re-deriving them costs the next
round the same time again. Write in-round tooling to
`Desktop\JustSearchTest\round-tools\` (any name; the directory is not
credit-eligible evidence and is excluded from nothing — it is simply
persisted), and name it in the retrospective so the harness can promote what
earned its keep into `scripts/sandbox/`.

### Findings (required — the defect report as its own artifact)

Every round must write `evidence/findings.md`. Round 16 filed five findings, one
of them blocking, and wrote no findings file: they lived scattered across
`mustwatch-verdicts.v1.json`, `retrospective.md` and `session-analysis.md`, so
reassembling the round's actual defect list meant reading three artifacts written
for three other purposes. The convention existed only as the "report findings by
journey" sentence above; it is now checked.

Write one entry per finding, each carrying:

- **A severity** — blocking/HIGH/MEDIUM/LOW, and what the severity is grounded in.
- **What was observed, with an evidence pointer** — the screenshot filename, the
  API snapshot, the log line. A finding a reader cannot re-open is a claim.
- **Its regression home** — a gate/test in its natural tier, or a
  `sandbox-must-watch` entry (see *Convergence* above). One of the two, named.

`evidence/findings.md` is checked at finalize and the round **fails closed** if it
is missing or too thin. A round that genuinely found nothing still writes the file
and **says so explicitly at the start of a line** ("No findings this round."),
describing what it exercised to reach that conclusion — an explicit clean-round
declaration satisfies the check; silence does not. The declaration is matched
line-anchored on purpose: a report that merely contains the phrase inside a scoped
sentence ("no findings in the search journey, but …") is a report WITH findings and
stays subject to the per-finding topic checks above.

### Evidence review (required — a reader, not just a filename, must confirm coverage)

This was measured, not assumed: known-bad artefacts planted into a copy of a
real round's evidence showed `check_coverage.py`'s own filename-token match
(`check_surface`/`check_shape`) credits a **mislabeled capture** (right bytes,
wrong claim — e.g. a command-palette screenshot named/credited as the logs
surface) **0 times out of 4**, while three independent blind readers caught it
4/4, 4/4, 4/4. Four such plants alone flip a correct FAIL into a clean exit-0 —
every gap gets "credited" by a screenshot of something else. No content hash
catches this; only a reader who looks at the pixels can.

**The rule: a capture must EVIDENCE the claimed surface/shape.** A filename is
a claim; the pixels are the evidence. An honestly-named blank capture (a file
genuinely named `-blank` that genuinely is blank) still does **not** evidence
the surface it's filed under — an honest name for a non-evidencing capture is
still not coverage.

Before finalize, open every credit-eligible screenshot (every image file at or
above the size floor `check_coverage.py` enforces — see `MIN_SCREENSHOT_BYTES`)
and write `evidence/evidence-review.v1.json`
(schema: `scripts/sandbox/evidence-review.schema.json`):

- **`examined`** — every screenshot you actually opened, by filename. This is
  the coverage assertion's enumerable list: the finalize check fails closed if
  ANY credit-eligible screenshot in the evidence dir is missing from it. Do not
  pad this list with files you did not look at, and if you run out of budget
  partway through, leave the un-opened files OUT of it and report a partial
  review — a truncated list that silently reads as "reviewed, no issues" on
  the files it never opened is exactly the failure this file exists to close.
- **`mismatches`** — any screenshot whose filename claims something the pixels
  do not support: `{file, claims, shows}`. Any non-empty `mismatches` fails the
  round closed, no matter how the rest of coverage reads — a review that finds
  a lie and passes anyway is decoration.
- **`uncertain`** — screenshots you could not confidently confirm or refute
  (occluded, ambiguous crop): `{file, reason}`. Non-blocking, but report it —
  do not resolve a genuine doubt into a false mismatch or a false clean pass.

**Sharding**: ~90 images is near one agent's practical review budget in a
single pass. On a round with more evidence than that, shard the review across
multiple passes/agents and **reconcile into one `evidence-review.v1.json`**
before finalize (union the `examined` lists, concatenate `mismatches` and
`uncertain`) — do not finalize on an unreconciled partial shard.

**Bulk/periodic capture frames go in `evidence/raw-frames/`, not the evidence
root (round 12, tempdoc 806 W3 item 1).** A driver that watches a long
install/upgrade by saving a screenshot every ~1.5s can produce hundreds of
near-identical frames in a single run (round 12: 947) — every one clears the
size floor, so at the evidence root each one becomes credit-eligible and this
required reader pass would have to open all of them, which is structurally
impossible against the ~90-image budget above. Write periodic/bulk frames
into a subdirectory literally named `raw-frames/` (direct child of
`evidence/`) instead: `check_coverage.py` still counts them as present
evidence (nothing is hidden or deleted) but excludes them from the
credit-eligible set — they are NOT required in `examined`, and they can NOT
by themselves satisfy a `mustTouch` surface/shape token. If one particular
bulk frame turns out to genuinely evidence a requirement (e.g. it caught the
one moment a dialog appeared), copy or rename that ONE frame out to the
evidence root with a real, claim-bearing name — it is then a normal
screenshot, credit-eligible and required in `examined` like any other.
De-duplicating at capture time (only write on visible change, not on a fixed
interval) is the better fix when a driver can do it; `raw-frames/` is the
fallback for drivers that can't or didn't.

**Investigation that happens AFTER finalize writes screenshots/notes into
`evidence/post-round/`, not the evidence root (round 15, tempdoc 817).** A
round's evidence review is complete and correct at the moment the round
finalizes — but the same mapped share can outlive that finalize, and a later
same-share investigation session (e.g. following up on a filed finding) can
add new screenshots into the same evidence dir. Round 15's finalized review
was already complete when a post-finalize investigation added ~52 new
screenshots, and re-running `check_coverage.py` afterward failed the
already-correct, already-finalized review as incomplete, because none of the
new files could have been (or needed to be) in that review's `examined` list.
Same treatment as `raw-frames/` above: write post-finalize investigation
output into a subdirectory literally named `post-round/` (direct child of
`evidence/`) — `check_coverage.py` still counts it as present evidence
(nothing hidden or deleted) but excludes it from the credit-eligible set: not
required in `examined`, and it cannot satisfy a `mustTouch` surface/shape
token by itself. If a post-round capture turns out to genuinely matter to the
original round's coverage, that is itself a sign the original review should
be revisited, not silently patched by dropping a new file into the old
evidence dir.

`evidence/evidence-review.v1.json` is checked at finalize (see *Coverage &
evidence* above) and the round **fails closed** if it is missing, malformed,
omits a credit-eligible screenshot, or reports a mismatch.

### Retrospective / debrief (required — the loop only improves via this channel)

Every round must write `evidence/retrospective.md`. This is not an optional
afterthought: a prior round's spontaneous "Part B" harness/process retrospective
drove roughly 15 harness fixes, and a later round that skipped one produced zero —
not because nothing went wrong, but because nothing asked for it. The harness only
gets better if every round leaves this behind, on purpose, every time.

This artifact is the round's *debrief* in the Session-Based Test Management sense
(J. Bach & J. Bach, STQE, 2000 — the charter/session/debrief/time-accounting
vocabulary here is adopted from SBTM, adapted to agent-driven rounds, not
invented). Read it against `charter.md`: what the charter asked vs. what happened.

**Time accounting (required section, TBS-adapted).** Include a section headed
`## Time accounting` breaking the round's hours into: **setup**, **install-wait**,
**coverage work**, **findings investigation**, and **write-up**, plus the split of
**on-charter vs. on-opportunity** work (off-charter pursuit of something important
you stumbled into is sanctioned — measure it, don't hide it). Estimates are fine;
the point is that "where do a round's hours go" stops being unanswerable
(tempdoc 734 D.9 asked; nothing before this measured it). The finalize check also
prints a report-only timeline computed from evidence-file timestamps as an
independent cross-check of your self-report.

Cover, at minimum, four things:

- **What the harness/docs got WRONG or made impossible** — a documented procedure
  you could not follow as written (wrong field path, an API call that doesn't do
  what the doc claims, a tool flag that can't express what's needed).
- **What you had to work around or build yourself** to get the job done, and why
  the documented path didn't work.
- **What slowed you down** — friction, ambiguity, a missing staged asset, a dead
  end that cost real time.
- **What you would change** — the concrete fix, not just the complaint.

**Every friction item gets an explicit disposition before it becomes a harness
note: harness defect OR candidate product finding.** Write the disposition next to
the item, in one clause. The two are not alternatives you pick by where the pain
landed — the same event is routinely both, and a friction item filed only as
process cost silently discards the product half.

The discriminator: **friction you resolved by consulting ground truth a real user
does not have is a discoverability finding, not just process cost.** Reading a
head log, an API field, a tempdoc or the source of a staged helper are all things
you can do and a first-time user cannot. If that is what unstuck you, the surface
did not tell the user what it told you.

Worked example, round 14 (2026-08-05). Chat is OFF by default after a successful
Install AI: with `installedFully: true` on all seven packages and a fully enriched
index, inference sat at `mode=offline, available=false`, `/api/chat/ask` returned
`AI_OFFLINE` without attempting startup, and a cold restart did not change it. The
round was ~2 minutes from a false HIGH; what saved it was **reading the Head log**,
where one INFO line explains everything ("AI auto-start not configured; engine
follows the persisted runtime spec"). It cost ~20 minutes and was routed entirely
to harness docs — "put this in CLAUDE.md and the do-not-refile list" — which is
correct and insufficient. A user who installs 10 GB and finds chat dead has no head
log; the product's own answer (Brain → "AI Offline → Start AI") was one surface away
and nothing pointed there. That is a **product discoverability finding** as well as
a harness gap, and only the harness half was recorded.

`evidence/retrospective.md` is checked at finalize (see *Coverage & evidence*
above) and the round **fails closed** if it is missing or too thin to be a real
retrospective — a stub file does not satisfy this. The dispositions are not
mechanically graded; like the rest of this artifact, they are graded by being
written down at all.

### Session self-analysis (required — separate from the retrospective, on purpose)

Every round must also write `evidence/session-analysis.md`. The retrospective
above debriefs **the round against its charter**; this one debriefs **the session
against the harness**. They are not the same artifact and merging them loses the
second one every time the first is long enough to feel done.

This exists because it already happened once, unasked: round 12 wrote a
session-level self-analysis nobody requested, and it produced roughly **11 adopted
harness fixes** — the single highest-yield artifact of the whole campaign — while
nothing in the harness collected it, so it happened exactly once. Write it in that
shape:

- **What the harness, charter, or instructions made HARD** — not just wrong (that
  is the retrospective's first bullet), but *expensive*: the step that took four
  attempts, the thing you had to re-derive because no staged file carried it, the
  instruction that was technically followable but pointed the wrong way first.
- **What you did off-charter, and why** — off-charter pursuit of something
  important you stumbled into is sanctioned. Name it and say what made it worth
  the detour; an unrecorded detour looks like drift afterwards.
- **What the NEXT round should do differently** — concrete, addressed to the next
  agent, not to the harness authors.

`evidence/session-analysis.md` is checked at finalize and the round **fails
closed** if it is missing or a stub. Its content is **not graded** — no keyword
check, no topic list, no quality judgment (the finalize check cannot judge quality
and does not pretend to). The value is that the artifact exists at all.

### Must-watch verdicts (required — a watch nobody recorded is a watch nobody did)

`coverage-brief.md` lists this round's **must-watch** items with their
`validateHow` notes. Until now nothing checked them: a round could observe
**nothing** on every single one and still exit 0 — a recorded claim nothing
verifies, which is the exact defect class this campaign exists to find, sitting in
the harness itself.

Before finalize, write `evidence/mustwatch-verdicts.v1.json`:

```json
{ "schema": "mustwatch-verdicts.v1",
  "items": [ { "id": "<must-watch id, exactly as in coverage-brief.md>",
               "verdict": "observed-pass | observed-fail | unobservable",
               "note": "what was actually seen / why unobservable",
               "evidence": ["optional artifact filenames"] } ] }
```

- **Every must-watch id in this round's brief needs an entry.** The finalize check
  fails closed on any omission. An item nobody got to is `unobservable` **with a
  note**, not a missing row.
- **`unobservable` requires a non-empty `note`** saying *why* — the same honesty
  rule the register itself applies to `install-trust-prompts` (its
  `observability: blocked-by-posture` carries the reason the prompts cannot fire
  under the current sandbox posture). "Not observable this round" is an acceptable
  answer only when it says why.
- **`observed-fail` does NOT fail the round by itself.** It prints prominently in
  the finalize report and then goes through the findings process like any other
  defect — what a failed watch *means* is your call and the owner's, not a byte
  count's. But an observed-fail with no corresponding finding write-up is an
  unfinished round.

What is graded is the **recording**, never the outcome: the check exists to make
the *claim of observation* verifiable, not to decide what you saw.

## Independence invariant (durable — do not "streamline" this away)

The round's self-report and the host-side mechanical re-run at finalize are two
deliberately separate authorities, and they stay separate. The verifier (you)
never sees or edits its own coverage bookkeeping's verdict; the host re-runs
`check_coverage.py` / `check_golden_parity.py` against the persisted evidence and
the two are compared. This pairing is the workflow's best-performing control: in
both GUI-capable rounds to date the mechanical re-run surfaced something the
round's own narrative missed (round 5: mislabeled screenshots and a surface with
zero genuine evidence; round 6: a cohort tested against the wrong port, 26/28 not
the self-reported 9/9 clean). Any future change that merges the self-report with
the finalize check, lets the round grade its own coverage, or drops the
independent re-run "because the round already reports it" removes that control —
if it is ever done, it must be done as an explicit, argued decision, not as
streamlining. (Tempdoc 750 Part E; lineage: 734 Part D.5-D.6/E.9 — the round is
the one tier that can be wrong without failing.)
