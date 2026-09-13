---
description: Orient to the sandbox and the project via documentation
---

You are running inside a **Windows Sandbox** for validating a JustSearch release
candidate. You do **not** have access to the developer environment. This skill is
durable method; what *this* candidate must cover is in the staged
`coverage-brief.md`.

## What is NOT here

- No source code, no `modules/`, no `gradlew.bat`, no JDK or Node.js (unless you
  install Node for the MCP Inspector check)
- No `jseval`, no JustSearch **dev-tools MCP**, no worktrees, no agent telemetry —
  these are developer tooling, unrelated to the product `/mcp` endpoint you verify
- `nvidia-smi.exe` is not on PATH (don't use it as a CUDA probe)

## What IS here

- The JustSearch installer + `CLAUDE.md` (the mission; `AGENTS.md` is the
  same file under Codex's entry-point name) in the mapped folder
- `coverage-brief.md` + `coverage-manifest.json` — the per-candidate must-touch
  surfaces, derived from what this build ships
- `validation-mode.md` — model mode (`fresh-install` vs `pre-staged-models`)
- `collect-evidence.ps1` — the capture harness
- `sandbox-environment.md`, `docs/`, the SciFact corpus, sanitized `.claude/`
  (Claude Code) and `.codex/` + `.agents/skills/` (Codex) — this skill is
  staged for both harnesses

## What to do at session start

0. **Capability probe, before reading anything else.** Probe for GUI
   **capability**, not merely a tool — a false negative here previously wrote
   off all 14 surface-tier items in a round while a fully working GUI tier
   sat unused (tempdoc 727-followup). Run BOTH checks:
   - **Tool check**: does THIS session have a computer-use / screenshot
     capability? (Claude Code: search the tool list for screenshot/computer/
     browser terms. Codex: the Computer Use skill, if the operator enabled it.)

     **Codex — an empty unified `cua_repl` app inventory is NOT a negative
     result, and never the end of this probe.** Round 18 saw `cua_repl`
     report `apps=[]` (browser-only in that task) while the staged Computer
     Use skill's **`node_repl` + `@oai/sky`** path drove native windows in
     the same session. Before concluding "no native Computer Use", run that
     path: through `node_repl`, use `@oai/sky` to **list the native apps and
     capture one image to disk**, and judge on that result. Only if the
     `@oai/sky` probe ALSO fails is the tool check negative.

     **Verify the first capture's magic bytes** (`89 50 4E 47` = PNG).
     Round 18's Computer Use saved JPEG bytes under `.png` filenames, which
     nothing failed on because coverage credit is by filename token — see
     `CLAUDE.md` (*Mission*) for the one-liner and
     `gui\convert-cu-images.ps1` for the repair.
   - **Native-capture check**: run the staged `gui\snap.ps1` (see
     `gui/README.md`) and confirm it wrote a non-blank PNG you can read
     back. Windows provides screen capture and input natively — no tool is
     required for this to work, and it drives the real Tauri WebView2 shell.
     If the desktop capture fails with **`The handle is invalid`
     (E_HANDLE)** — Windows Sandbox's RDP indirect display does this to
     every `CopyFromScreen` — `snap.ps1` falls back on its own to a
     per-window `PrintWindow` capture, so read its exit code and the written
     PNG, not the desktop error line. Force that path with
     `.\gui\snap.ps1 -Out probe.png -ForceWindowCapture -ProcessName JustSearch`.
     A per-window capture counts: this check is about a PNG on disk.

   **Only if BOTH fail** is this an API-only round — record that immediately
   as a standing round-level gap (`staging-gaps.md` or your findings notes),
   and do not plan or ask the user anything that presupposes GUI access
   (screenshots, driving the Tauri shell). Doing this probe late costs a
   wasted round-trip to the user. Both checks have now produced a documented
   false negative (`cua_repl` `apps=[]`; `CopyFromScreen` E_HANDLE), so
   "the first thing I tried returned nothing" is not the finding — establish
   it with the second probe before writing off the GUI tier.

   If you have a computer-use capability, use it — it exercises the app the
   way a user does, which is what a release-validation round is for. The one
   thing the round needs from the GUI regardless of how you drive it is a PNG
   **on disk** with the coverage filename (see *Coverage & evidence*); if your
   tool cannot save a screenshot to a path you choose, `gui\snap.ps1` writes
   that file. With no computer-use capability, the native tier
   (`gui/README.md`) is the whole GUI path.
1. Read `coverage-brief.md`, `validation-mode.md`, `sandbox-environment.md`,
   and `staging-gaps.md` (assets the host failed to stage — each entry is a
   round-level coverage gap, not something to silently absorb).
2. Install Git and JustSearch (see `CLAUDE.md` → Setup). Your harness is
   already running — the operator installed it; the charter's Setup step 2
   says what differs per harness (Codex: confirm the charter was not
   truncated).
3. Launch JustSearch (the launcher enables request tracing via
   `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed`; if you launch it another way, set that
   env var first so `telemetry/traces.ndjson` records what you exercise).
4. Run `collect-evidence.ps1` to capture the port, the API sanity ladder, and the
   `/mcp` Inspector check.
5. Run the cheap sanity ladder, then work by user journey, covering every
   `sandbox`-tier item in `coverage-brief.md`.
6. At finalize, assert coverage (traces + evidence vs `coverage-manifest.json`) and
   route every confirmed finding to a regression home (see *Convergence* in
   `CLAUDE.md`). **Also write `evidence/retrospective.md`** — required, not
   optional (see *Retrospective* in `CLAUDE.md`'s *Writing results* section):
   what the harness/docs got wrong or made impossible, what you had to work
   around or build yourself, what slowed you down, and what you would change.
   The finalize coverage check fails closed if this file is absent or too thin.

## Validation rhythm — durable rules

These are method, not this-round specifics. Each earns its place from a real
past miss; apply them regardless of candidate.

1. **Don't apply a workaround without documenting the failure it hides.** If you
   have to work around something to proceed, the workaround is itself a finding —
   record the original failure with evidence before moving on. Making a failure
   invisible is not the same as fixing it.
2. **Verify positively, not by absence of failure.** "No error in the log" is not
   proof a feature works. Assert the positive signal (the expected field value,
   the rendered result, the tok/s), not merely the lack of a crash.
3. **Harvest the stack trace on any HTTP 500.** A 500 is a server-side exception —
   capture the backend log lines around it verbatim; the trace is the finding, the
   status code is just the pointer.
4. **Check install-dir version uniqueness before trusting runtime behaviour.** A
   stale jar left beside the fresh one produces confident-but-wrong results. Verify
   the install directory holds exactly one version of each artifact.
5. **There is one Engine log, not separate Head/Worker logs.** `engine.log` captures
   the whole Engine JVM (the Head and Worker halves were merged; there is no separate
   `worker.log`). Read it, and its `.1` rotation for the prior boot.
6. **`/api/status.worker.gpu.*` is field access, not a URL.** Those are nested JSON
   fields inside `/api/status`, not separate endpoints — read the object.
7. **Stderr is data, not noise.** Native-layer messages (ORT, llama, CUDA) surface
   on stderr; capture it. A "warning" there is often the real explanation.
8. **Save raw API responses verbatim.** Store the exact JSON, not a paraphrase — the
   finalize coverage check and any later triage depend on the literal bytes.
9. **The reranker is lazy-init; force a HYBRID query to exercise it.** A plain search
   may never load the reranker. Run a HYBRID/reranked query so the reranker path is
   actually tested.
10. **Restart once between Install AI and the final assessment.** Some wireup only
    takes effect after a cold restart; assess the post-restart state, not the
    first-boot state, as the real one. A restart means killing ALL FOUR processes —
    the Tauri shell (`JustSearch.exe`), Head (`javaw.exe` under `resources\headless\`),
    Worker (a second `java.exe`), and `llama-server.exe` if active. Closing only the
    shell window reconnects to the same still-running backend and proves nothing;
    verify a genuine restart by the runtime manifest's `instanceId`/`pid` changing.
    Filter by process **Path**, not bare `ProcessName` — a bare `java`/`llama` pattern
    can kill unrelated processes. The authority for that Path is the OS process
    `ExecutablePath` (`%LOCALAPPDATA%\JustSearch\JustSearch.exe`), **never a
    computer-use app identifier** — round 18's named a per-harness LocalCache copy
    of the app, so targeting it leaves the real shell running and the manifest's
    `instanceId`/`pid` unchanged, which is exactly the proof this rule asks for:
    ```powershell
    Get-Process | Where-Object { $_.Path -like "*\JustSearch\*" -or $_.Path -like "*io.justsearch.shell*" } | Stop-Process -Force
    ```
11. **Never patch product code from inside the sandbox.** There is no source tree
    here and no way to rebuild correctly; a "fix" attempted in-sandbox is invalid.
    Record the finding and let it be fixed in the repo, then re-cut the candidate.
12. **Terminate runaway processes deliberately.** A hung `llama-server` or Worker can
    skew GPU/throughput evidence; identify and kill it (`taskkill /F`) before
    re-measuring, and record that you did.
13. **Record quantitative GPU evidence.** Chat tok/s, ingest/enrichment wall time,
    and the runtime-status fields — measured when no ingest/enrichment/indexing job
    is running (contention lowers throughput; note it if present).
14. **Distinguish sandbox shortcuts from the production flow.** At least one round per
    candidate must be a TRUE fresh install (`--no-models`) so Install AI does the full
    clean download — a pre-staged-models shortcut masks production-flow regressions.
    `validation-mode.md` states which mode you are in; label shortcut evidence as such.
15. **Use the multi-cycle restart pattern when the target is lifecycle.** One cold
    restart suffices for a frontend-focused round; run three cycles when validating
    persistence, port binding, encoder init, or wireup, comparing state each cycle.
16. **Run a real Tauri/Lit shell GUI pass — the UI is the product.** APIs passing is
    not the app working. Drive the actual shell; UI/API disagreement (a "Ready" label
    over a degraded backend, a stale card, a toast occluding a control) is a finding
    even when every endpoint returns 200.
17. **Verify a fix by deliberately triggering its condition.** To confirm a reported
    regression is fixed, reproduce the exact trigger and show the new behaviour — don't
    infer the fix from unrelated green.
18. **Windows trust/security prompts are validation evidence.** SmartScreen, Defender,
    Smart App Control, and unsigned-publisher warnings are what a real first-run user
    faces. Capture the exact text/screenshots and whether silent-install (`/S`) was
    honoured. These cannot be reproduced in CI, so the Sandbox is their only check
    (they live as must-watch items in `coverage-brief.md`).

## Working mechanics

- **Inspect large JSON/logs filter-first.** Extract the specific fields
  (`Select-Object`, `ConvertFrom-Json`, `Select-String`) before ever reading a
  raw dump into context. This governs *reading*, not *saving* — raw dumps are
  still saved verbatim as evidence (rule 8 above stands).
- **Any command containing `$env:` or other PowerShell-specific syntax must run
  via the native PowerShell tool, never by shelling `powershell.exe -Command`
  from a bash tool.** Bash expands `$env` first and mangles the path — this
  happened 3 times in a prior round.
- **After installing anything that changes PATH** (Node, Git),
  resolve and reuse the absolute exe path for the rest of the session — shell
  state does not persist between tool calls, so a PATH update in one call is
  invisible to the next.

## What you can fast-path vs. keep doing thoroughly

- **Fast-path** once the cheap sanity ladder is green: don't re-enumerate every
  package/DLL or trace every install transition when `installedFully: true`, the
  runtime selects the GPU variant, and chat/reranker sanity pass. Expand only on a
  skip/fail, a missing DLL, low throughput, or degraded runtime status.
- **Keep doing thoroughly, every round:** the trust surfaces (Security & Privacy,
  Memory, Skins), the escalation-ladder honesty, the `/mcp` external-client check,
  Windows trust prompts, and any surface `coverage-brief.md` marks new-this-candidate.
  These are where a materially different build regresses user trust.

## When troubleshooting

Read the backend logs (Head and Worker, plus `.1` rotations), capture stderr, and
save raw API snapshots. Don't guess from symptoms — use `/api/debug/state` and
`/api/health` for lifecycle and the raw JSON for state. If you cannot reach a
surface that `coverage-brief.md` requires, that is itself a finding, not a reason
to move on silently.
