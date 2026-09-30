---
description: "TRIGGER when: starting or stopping the dev stack, calling MCP dev tools, encountering port conflicts, running runHeadless/runHeadlessEval, or debugging backend startup issues. Loads operational knowledge for dev stack management."
user-invocable: true
---

# Dev Stack Operations

Operational reference for the JustSearch dev stack. Load this before
starting backends, debugging port issues, or running pipeline profiling.

> **Tool inventory lives in one place.** The canonical list of `justsearch.dev.*` tools, the
> `fetch_api_json` endpoint keys, and the `api_call` allowlist are in
> [`docs/reference/contributing/mcp-dev-tools.md`](../../../docs/reference/contributing/mcp-dev-tools.md),
> and `scripts/ci/check-dev-mcp-doc-sync.mjs` holds that page and the server together.
> This skill deliberately does **not** restate them — the same table lived in both files and both
> were wrong for months (tempdoc 844 §6.3). Read that reference for *which tool*; read this skill
> for *how to operate the shared stack*.

## Key Operational Facts

- **Cold start:** ~6s to port emit, ~38s to Worker ready
- **Default MCP timeout:** `waitTimeoutMs` is 60s
- **Occupied ports require identity evidence** — use `justsearch.dev.stop` for a dev-runner-owned
  process. Never kill an unattributed listener from port evidence alone; identify its owning
  lifecycle first and use that lifecycle's cleanup path.
- **jseval's eval backend cannot activate inference:** `--start-backend --llm` is rejected because `runHeadlessEval` pins read-only settings. For isolated full-inference measurement, use the normal owned dev runner with writable settings and `JAVA_OPTS` containing `-Djustsearch.eval.mode=true` to skip bundled help. Follow the exact isolation/preflight recipe in `docs/reference/jseval-pipeline-reference.md`; verify zero initial documents before ingestion.
- **`--clean none`** preserves embedding progress across backend restarts
- **`RERANK_MODEL_PATH`** must be absolute (Gradle `runHeadless` CWD differs from repo root)
- **Dev stacks default to the compact chat profile** (tempdoc 842) — smaller/faster, frees VRAM. `ai_activate {chatProfile:"standard"}` switches to the standard (user-facing) model; the engine reload takes single-digit seconds either direction. Compact model files aren't installed by default — fetch them with `node scripts/dev/fetch-compact-model.mjs` (sha-verified).

## Hot-Reload Iteration Loop

Instead of full rebuild + restart, use `reload` (~2-3s):

```bash
reload                                    # push bytecode via JDWP
cd scripts/jseval
python -m jseval run --dataset scifact --max-queries 0 --pipeline --reset
```

`hotReload` defaults true on `start` (tempdoc 844), so the JDWP listener and service
reconstruction are there unless you opted out with `hotReload: false` — in which case `reload`
refuses with `HOT_RELOAD_NOT_ENABLED` instead of reporting a push it did not make. `reload`
compiles from the tree the running stack was launched from (not your cwd), is ownership-gated,
verifies the target VM's identity before redefining anything, and only ever pushes the module the
run recorded. **`skipBuild: true` can cost you hot reload**: the classes dir goes first on the
Worker classpath, which is only sound when it and the installDist jars are one build — when
`skipBuild` leaves them unpaired the dev-runner turns hot reload off for that run and records why,
rather than running a half-new classpath. Start without `skipBuild` to get it back. Full behaviour
and error codes: `docs/reference/contributing/mcp-dev-tools.md` § Hot Reload.

## Do NOT Write Manual Polling Loops

Never use `sleep`, `curl` in a loop, or `Thread.sleep()` for process coordination. Use `jseval` for the full lifecycle:

```bash
cd scripts/jseval
python -m jseval run --dataset scifact --max-queries 0 --pipeline \
  --start-backend --clean --timeline tmp/timeline.tsv --json
```

This starts the backend, ingests, waits for enrichments, captures timing, and stops the backend.

## Freshness Preamble — run it BEFORE debugging an unexpected negative (tempdoc 637)

When a live validation shows an unexpected **negative** — "0 results", "data lost",
"feature broken", an empty/blank surface — the cause is very often a **stale
environment state masquerading one layer up**, not a product bug. Stale states are
the single highest-time-cost trap (tempdoc 637): a dead FE→backend binding, a stale
installed jar, a cold/embedding-blocked index, or a clobbered lockfile each surface
as a *different* problem at a higher layer.

**Discipline (an extension of `verify-don't-guess` from lifecycle to freshness):**
run the freshness preamble FIRST, and only debug behaviour once every source reads
fresh. `quick_health` now returns a `freshness` block aggregating the sources at the
dev-tooling layer:

```
justsearch.dev.quick_health   → freshness: {
  buildArtifact: FRESH | STALE(reason, remedy)     # stale jar (#2) — run installDist
  indexWarmth:   FRESH | WARMING(reason, remedy)    # cold index (#3) — mode:text works meanwhile
  feBinding:     SELF_DECLARED                      # dead binding (#1) — the FE shows a loud banner
  locks:         DEFERRED                           # lockfile (#4) — pre-merge resolveAndLockAll
}
```

And: when an unexpected negative appears in the UI, **hit the API ground-truth
directly first** (`/api/status`, `/api/knowledge/search`) — data layer first,
presentation last. The one time the API is queried directly, it usually localizes
the fault immediately (e.g. to the FE), saving many wrong-layer turns.

## Shared-stack ownership & coordination (multi-agent worktrees)

Only one dev stack runs at a time (memory/port). The dev-runner tracks ownership in `tmp/dev-runner/active.json` (lease-based). Before starting, call `quick_health`; if a stack is running, its response carries `ownership.holder` + `ownership.verdict` + `ownership.recommendedAction` from one authority — act on the verdict rather than inferring from raw lease fields (tempdoc 606):

- `TAKEOVER_ABANDONED` — the owning session went silent; `start` self-serve-proceeds (no user prompt needed).
- `IDLE_HOLD` — the owner is alive but idle; the response recommends `takeover: "warn"`, self-authorizable without a user round-trip.
- `CONTENTION` — the owner is actively using the stack: the genuine ask-the-user case (the `OWNER_CONFLICT` error). A `force` takeover needs explicit user direction.
- `acquire_when_free` blocks until the stack is acquirable and returns a `recommendedTakeover` — it replaces the conflict → ask → manual-retry loop.
- `ownership.provenance` + `rebuildFirst` flag when the running stack was built from a different worktree/commit than yours; `start { distFrom: "<worktree>" }` launches your own code on the one shared lease.
- `ownership.displacedNotice` surfaces at your next call if a stack you previously owned was taken over while you were away.
- `start { leaseDurationSec: <30-7200> }` (tempdoc 735 G6) declares a campaign-length ownership hold instead of relying on the default 30s passive-expiry window: the lease's `expiresAt` is renewed against this declared duration on every renewal cycle, so a long measurement campaign that goes minutes without a Claude Code session touch (busy running `jseval`/Gradle) doesn't lapse into a `TAKEOVER_ABANDONED`/`IDLE_HOLD` verdict mid-run. Values are clamped server-side to `[30, 7200]`; the default (param omitted) is unchanged 30s behavior. Explicit takeover semantics are untouched — `force`/`warn` still work normally; this only stretches the passive-expiry window. `quick_health` reports the remaining hold at `ownership.lease.remainingSec`.

The four admission error codes `start` can return (`OWNER_CONFLICT`, `HANDSHAKE_REQUIRED`, `REQUIRES_CONFIRMATION`, `RUN_NOT_FOUND`/`NO_API_URL`) and their resolutions are tabulated in the [MCP dev tools reference](../../../docs/reference/contributing/mcp-dev-tools.md#start-tool-error-codes).

A stack abandoned past a grace period is reaped automatically (the supervisor self-terminates), so a long-gone session stops holding VRAM/ports. Stop the stack when you finish so other agents can use it.

**Honest limit (tempdoc 844 §6.1):** the lease only knows runs the dev-runner started. A `jseval` backend (hardcoded port 33221), a bare `gradlew runHeadless`, or a `runHeadlessEval` JVM is invisible to `quick_health` — so a "free" verdict can sit next to a 100%-GPU neighbour. This has already contaminated one measurement round. Check the ports before trusting a free verdict during eval work.

Overnight/long GPU windows (tempdoc 743 P-N, arming step): an unattended multi-hour run starts only on an explicit, recent founder go for *that window*  -  a budget remark or standing goal is not an arming; declare the window with `leaseDurationSec` sized to it, and when a chain is halted mid-window, stopping the stack is part of the halt, not a follow-up. For supervising the run itself, `node scripts/dev/run-watcher.mjs` (liveness pulse + `check` verdicts) replaces hand-rolled per-session watcher scripts; notify-on-failure/completion is the default posture  -  per-step progress belongs on disk, read at the coarse tick (743 P-M(c), founder-approved 2026-07-17).

## Live-validate a worktree's frontend (FE-only work)

To see *this worktree's* FE in a browser without starting your own stack, borrow the running backend (read-only) and serve the worktree's Vite:

```bash
node scripts/dev/serve-worktree-fe.cjs   # picks a free port, auto-detects the running backend
```

It serves from the worktree's `modules/ui-web` (the served code is the worktree's by construction) and prints the branch + backend it bound to — the sanctioned path for the contention/port/wrong-code frictions in tempdoc 618 §7 (no `start` needed, so it works even when another session owns the stack).

## Troubleshooting

- If startup is slow, check `justsearch.dev.tail_log` and retry `justsearch.dev.quick_health` before assuming the stack is broken.
- If a UI/API check fails, compare `quick_health` (add `detail: "full"` for process/port state) with the relevant predefined JSON endpoint output.
- If a generic API call is rejected, the endpoint is outside the dev MCP allowlist — see the allowlist table in the reference.
- If hot reload reports `structuralChangeDetected`, stop/start the dev stack instead of continuing to rely on hot swap.
- If search results look stale after field/catalog changes, reset or rebuild the dev index instead of debugging query behavior first.
- If AI activation fails, separate online runtime readiness from Engine encoder readiness; they use different lifecycle controls.
