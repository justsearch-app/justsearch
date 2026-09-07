---
title: MCP Dev Tools Reference
type: reference
status: stable
description: "Dev orchestration tools for starting, monitoring, and verifying the JustSearch dev stack."
---

# MCP Dev Tools Reference

The `justsearch-dev-mcp` server is the agent-facing control surface for the local development stack. It wraps the dev runner, selected Local API calls, search/ingest helpers, and AI runtime toggles.

This file is the **canonical inventory** of that surface: the tool list, the `fetch_api_json` endpoint keys, and the `api_call` allowlist. `scripts/ci/check-dev-mcp-doc-sync.mjs` asserts all three against the running server, so a drift between this page and `scripts/dev/justsearch-dev-mcp/server.mjs` fails the build instead of quietly misleading an agent (tempdoc 844 §6.3 measured four inventories, all wrong at once).

For the operational side — shared-stack ownership and contention, worktree FE serving, troubleshooting — load the `/dev-stack` skill. It links here rather than restating the inventory.

## Bootstrap and availability

`justsearch-dev` is a required, revision-local server. It must initialize from a freshly checked-out
Git worktree with Node.js 24 or newer, even before that worktree has a root `node_modules` directory.
The application server and `dev-runner.cjs` always come from the caller's checkout. The bootstrap
pins that checkout before loading application code, so an inherited `JUSTSEARCH_REPO_ROOT` cannot
redirect lifecycle behavior into another revision; another checkout is never used as a fallback.

The external startup dependency boundary is a generated projection:

| File | Authority |
|---|---|
| `scripts/dev/justsearch-dev-mcp/runtime-entry.mjs` | Hand-authored list of SDK/Zod exports required at runtime. |
| `scripts/dev/justsearch-dev-mcp/runtime.generated.mjs` | Committed ESM projection of the locked third-party dependency closure. Never edit manually. |
| `scripts/dev/justsearch-dev-mcp/runtime.generated.LEGAL.txt` | Committed package/version/license text for every package represented in the bundle. |
| `scripts/dev/generate-dev-mcp-runtime.mjs` | Generator and `--check` freshness/license authority. |

After changing root MCP dependencies or the runtime entry, install the root development
dependencies and run:

```powershell
node scripts/dev/generate-dev-mcp-runtime.mjs
node scripts/dev/generate-dev-mcp-runtime.mjs --check
node scripts/dev/generate-dev-mcp-runtime.test.mjs
node scripts/dev/test-dev-mcp-bootstrap.mjs
```

The bootstrap before `server.mjs` uses Node built-ins only. It dynamically imports the application
so import failures cross a real error boundary, emits no stdout on failure, and writes a
best-effort per-instance diagnostic under `tmp/justsearch-dev-mcp/bootstrap-failure.<instance>.json`.
`bootstrap-failure.json` is a convenient pointer to the most recently observed failure, not a
current-health verdict. A successful concurrent launch does not erase another instance's evidence;
old per-instance records are pruned after seven days. Use `quick_health` to establish current state.

| Bootstrap code | Meaning |
|---|---|
| `DEV_MCP_BOOT_UNSUPPORTED_NODE` | The launcher is older than the supported Node.js runtime. |
| `DEV_MCP_BOOT_RUNTIME_MISSING` | The committed generated runtime is absent. Regenerate it in an installed checkout. |
| `DEV_MCP_BOOT_MODULE_NOT_FOUND` | Another revision-local application module is absent. |
| `DEV_MCP_BOOT_IMPORT_FAILED` | The server or generated runtime could not be parsed or imported. |
| `DEV_MCP_BOOT_MAIN_FAILED` | Imports succeeded but server initialization rejected. |
| `DEV_MCP_RUNTIME_UNCAUGHT` / `DEV_MCP_RUNTIME_UNHANDLED_REJECTION` | A fatal post-bootstrap process error terminated the server. |

## Available Tools

The dev MCP surface exposes exactly these **12** tools:

| Tool | Purpose |
|------|---------|
| `justsearch.dev.start` | Start the backend and frontend dev stack. Readiness waiting is part of this tool via its wait options. |
| `justsearch.dev.stop` | Stop the active dev run and clean up owned processes. |
| `justsearch.dev.quick_health` | Fast orientation check with typed run/probe state, plus `foreignRuns` — registered or observed listeners outside the positively identified owned listener. `detail: "full"` adds the dev-runner process/port/readiness payload. |
| `justsearch.dev.preflight` | Run dev preflight checks with typed `PASS`/`FAIL`/`UNKNOWN`/`SKIPPED` outcomes. Takes `distFrom` so the dist checks run against the tree `start` will launch from. |
| `justsearch.dev.acquire_when_free` | Block until the shared stack is acquirable, then return how to take it (the documented remedy for `OWNER_CONFLICT`). |
| `justsearch.dev.tail_log` | Read recent backend, frontend, or runner log lines. |
| `justsearch.dev.fetch_api_json` | Fetch predefined JSON endpoints by key. |
| `justsearch.dev.api_call` | Call allowlisted Local API endpoints with explicit method/path/body. |
| `justsearch.dev.search_query` | Execute `POST /api/knowledge/search`. |
| `justsearch.dev.ingest` | Execute `POST /api/knowledge/ingest`. |
| `justsearch.dev.ai_activate` | Activate the online AI runtime. |
| `justsearch.dev.reload` | Hot-reload the running stack's Engine: compile from the tree that stack was launched from, push method-body changes over identity-checked JDWP, reconstruct services with the ONNX encoders still loaded. Ownership-gated. |

Legacy underscore-style dev tool names and standalone readiness/listing/suggestion/cleanup tools are obsolete. Agents should use the dotted names above.

**Retired in tempdoc 844 P1** — do not reintroduce these names in tooling or prompts:

| Retired tool | Why | Use instead |
|---|---|---|
| `justsearch.dev.status` | 1 invocation in six weeks vs 61 for `quick_health`, whose description already told agents to prefer it. | `quick_health { detail: "full" }` |
| `justsearch.dev.agent_chat` | 0 invocations; superseded in practice by browser-driven UI validation and `jseval` harnesses. | `jseval`, or the chat API directly |
| `justsearch.dev.capture_evidence` | 0 invocations; the wrapper crashes on Windows with a libuv fail-fast. | `node modules/ui-web/scripts/capture-evidence-bundle.mjs` |
| `justsearch.dev.validate_evidence` | 0 invocations (its capture counterpart never produced a bundle to validate). | `node scripts/evidence/validate-evidencebundle-v1.mjs <bundleDir>` |

The **EvidenceBundle format itself is live and load-bearing** — only the two MCP wrappers were removed. `scripts/evidence/validate-evidencebundle-v1.mjs` still gates the `installer_verify` job.

## Standard Workflow

1. Orient with `justsearch.dev.quick_health` — the compact readiness check, and the first call after compaction.
   - `runState` is `ACTIVE`, `ABSENT`, or `UNKNOWN`. The compatibility field `running` is
     `true`, `false`, or `null`; `null` means the record/probe evidence could not establish either
     outcome. Treat `UNKNOWN` as a blocker to claiming the shared stack is free.
   - `probes.api`, `probes.worker`, and `probes.inference`, when present, distinguish
     `REACHABLE`, `REFUSED`, `TIMED_OUT`, and `ERROR`. A timeout is not a connection refusal.
   - Add `detail: "full"` when process state, ports, or runner metadata matter; it is the only mode that spawns a subprocess.
   - Read `foreignRuns` before concluding the machine is free. It lists registered or observed JustSearch-shaped listener candidates outside the positively identified owned listener (a `jseval` backend on `33221`, a bare `runHeadless`, an unattributed listener on the inference port). The tri-state is load-bearing: `[]` = probed and found none, `null` = did not probe (`probe: false`), and a non-empty array carries `attribution: "unowned"` only when non-ownership is proven. If the owned run record is corrupt or unreadable, attribution stays `"unknown"`. Ownership verdicts and `running` describe the dev-runner's own run only — before tempdoc 844 a false "free" verdict preceded a 100%-GPU neighbour and contaminated a measurement round.
   - `foreignRuns` merges two sources and says which is which. `source: "registered"` means the producer declared the run in the foreign-run register (below) — the entry then carries identity (`producer`, `repoRoot`, `pid`, `dataDir`, `sessionId`, `gpuBound`) and a `state` that was *verified*, not assumed: `live` (its declared port answered), `unreachable` (port silent, pid alive — booting or wedged), `stale` (port silent **and** pid gone: a record whose producer was killed before it could clean up, so nothing is running — delete `recordFile` if it is yours), `unreadable` (the record file could not be parsed). A `live` entry additionally carries `identityStale: true` when the port answers but the recorded pid is gone — the listener is verified, the identity behind it is not. `source: "observed"` means only that a port answered with nothing declaring it. A registered port is never also listed as observed.
2. Run `justsearch.dev.preflight` if the stack is not running.
   - Pass the **same** `distFrom` you will pass to `start` (a path, or a bare worktree name). Preflight then checks the dists in the tree `start` will launch from and reports it as `distCheckedRoot`; without it, preflight validated the invoking checkout while `start` used another — a false green.
   - Read `checkStates` as the authoritative result. Legacy `checks` booleans remain for older
     clients, but both `FAIL` and `UNKNOWN` project to `false`. Every gating state must be `PASS`
     before `ready` can be `true`; unreadable files and indeterminate probes never become `OK`.
3. Start the stack with `justsearch.dev.start`.
   - Use the tool's wait options instead of a separate wait-ready tool.
   - `waitTimeoutMs` may need to be higher than the default on cold machines or after clean builds.
   - `chatProfile?: "compact" | "standard"` (tempdoc 842) selects the llama-server chat model pair delivered as `JUSTSEARCH_CHAT_PROFILE` in the spawn env. Defaults to `compact` — dev stacks run the small dev-tier model unless told otherwise.
   - On `OWNER_CONFLICT`, `justsearch.dev.acquire_when_free` waits for the stack instead of a conflict → ask → manual-retry loop.
   - `hotReload` **defaults true** (tempdoc 844): the Engine gets a JDWP listener on a per-run port, recorded in `run.json`. Pass `hotReload: false` to opt out; `reload` then refuses with `HOT_RELOAD_NOT_ENABLED` rather than reporting a push it could not make.
4. Use `justsearch.dev.fetch_api_json` for common read-only diagnostics.
5. Use `justsearch.dev.api_call` only when the endpoint is in the explicit allowlist.
6. Use `justsearch.dev.stop` when the run should be shut down. It cleans up only processes whose
   identity is established by the dev-runner ownership record. A response from an unattributed
   inference-port listener is reported as unknown and is never, by itself, authority to kill it.

## Prerequisites

Starting the required MCP itself needs only the tracked checkout, Git, and Node.js 24 or newer. A
root npm installation is still required to regenerate its third-party runtime or run repository
JavaScript development checks; it is not a task-creation prerequisite.

Build the Engine distribution and UI assets before relying on the dev stack:

```bash
./gradlew.bat :modules:ui:installDist
```

(One task, one distribution: lane F stage A item A13 deleted the separate Worker distribution — the index half is now jars inside the Engine's own `lib/`.)

If AI runtime behavior is part of the investigation, also verify model files, native runtime availability, and GPU/runtime prerequisites with the project-specific preflight scripts before drawing conclusions from failures.

Operational checks that are still worth doing before longer investigations:

| Area | Check |
|------|-------|
| Engine distribution | `modules/ui/build/install/ui/` should exist after the Gradle command above, and its `lib/` should contain `indexer-worker-*.jar`. |
| UI assets | `modules/ui-web/dist/` should exist when testing packaged/static UI behavior. |
| Models | Online LLM paths and Worker ONNX encoder assets must match the current settings/model manifest. Do not assume old GGUF embedding paths. |
| Runtime variant | CPU-only online runtime is valid but slow; GPU behavior requires a GPU-capable runtime variant and matching configuration. |
| Dev data | The default dev data directory is `modules/ui-web/.dev-data`; stale indexes there can hide ingestion/search changes. |

## Predefined JSON Endpoints

`justsearch.dev.fetch_api_json` accepts these endpoint keys, and only these. The key -> path mapping is the one in `FETCH_API_ENDPOINT_MAP` (`scripts/dev/justsearch-dev-mcp/server.mjs`), asserted by the doc-sync gate:

| Key | Endpoint |
|-----|----------|
| `status` | `/api/status` |
| `health` | `/api/health` |
| `effective_config` | `/api/debug/effective-config` |
| `debug_state` | `/api/debug/state` |
| `policy_effective` | `/api/policy/effective` |
| `inference_status` | `/api/inference/status` |
| `gpu_capabilities` | `/api/gpu/capabilities` |
| `ui_ready` | `/api/ui/ready` |
| `ai_runtime_status` | `/api/ai/runtime/status` |

Prefer these keys over generic URL calls when they cover the diagnostic need.

### Projection and size limits (tempdoc 844)

Both `fetch_api_json` and `api_call` accept `jsonPath`, sharing one implementation:

- Dot-path with array indices — `"llm.model_path"`, `"results[0].fields.path"`.
- On a **miss** the tool returns `error.code: "JSON_PATH_MISS"` naming the deepest segment that resolved plus `jsonPathAvailable` (the keys, or the array length, at that level), and **withholds the body**. Previously a miss discarded the parsed JSON and returned the raw `textTail` — the largest possible payload as the answer to a one-character typo.
- A malformed expression returns `JSON_PATH_INVALID` rather than silently missing.

`maxBytes` is a **read** budget, not an output budget. Exceeding it now truncates and returns `error.code: "RESPONSE_TRUNCATED"` with `truncated`, `bytesRead`, and `maxBytesLimit`; it used to fail the whole call with `response_too_large`. A truncated body does not parse as JSON, so lowering `maxBytes` cannot shrink a large response — use `jsonPath`, `outputMode: "compact"`, or `summaryOnly` for that. The same notice is emitted by `search_query` and `ingest`.

## Generic API Calls

`justsearch.dev.api_call` is allowlisted: any path not in the table below is refused. This is the complete list, mirroring `API_CALL_ALLOWLIST` (`scripts/dev/justsearch-dev-mcp/server.mjs`) — the doc-sync gate fails if the two diverge in either direction.

| Path | Methods |
|------|---------|
| `/api/settings/v2` | GET, POST |
| `/api/preview` | GET |
| `/api/indexing/roots` | GET, POST, DELETE |
| `/api/indexing-roots/substrate` | GET |
| `/api/indexing-roots/preview` | POST |
| `/api/indexing-jobs/failed` | GET |
| `/api/indexing-jobs/failed/by-prefix` | GET |
| `/api/indexing/reindex` | POST |
| `/api/indexing/excludes/apply` | POST |
| `/api/indexing/migration/start` | POST |
| `/api/indexing/migration/cutover` | POST |
| `/api/indexing/migration/rollback` | POST |
| `/api/indexing/migration/pause` | POST |
| `/api/indexing/migration/resume` | POST |
| `/api/indexing/gc` | POST |
| `/api/indexing/settle` | POST |
| `/api/inference/status` | GET |
| `/api/inference/mode` | POST |
| `/api/inference/reload` | POST |
| `/api/worker/restart` | POST |
| `/api/ai/install/status` | GET |
| `/api/ai/install/manifest` | GET |
| `/api/ai/install/plan-preview` | GET |
| `/api/ai/install/start` | POST |
| `/api/ai/install/cancel` | POST |
| `/api/ai/install/repair` | POST |
| `/api/ai/install/pause` | POST |
| `/api/ai/install/resume` | POST |
| `/api/ai/install/packages/{packageId}/decline` | POST, DELETE |
| `/api/ai/runtime/status` | GET |
| `/api/ai/runtime/activate` | POST |
| `/api/ai/runtime/deactivate` | POST |
| `/api/ai/packs/status` | GET |
| `/api/ai/packs/installed` | GET |
| `/api/ai/packs/preflight` | POST |
| `/api/ai/packs/import` | POST |
| `/api/policy/validate` | GET |
| `/api/policy/user/create` | POST |
| `/api/policy/user/allowlist/pack-manifest/add` | POST |
| `/api/diagnostics/export` | POST |
| `/api/knowledge/status` | GET |
| `/api/schemas/{name}` | GET |
| `/api/debug/events` | GET |
| `/api/debug/engine-log` | GET |
| `/api/telemetry/health` | GET |
| `/api/action-ledger` | GET |

**Path shape.** The allowlist is applied to the path that will actually be **sent**, not to the raw
input. `path` must be absolute, must contain no backslash, and must have no empty, `.` or `..`
segment (checked after percent-decoding), and the resolved URL must still carry that exact pathname.
Before tempdoc 844 M4 the `{packageId}` pattern's `[A-Za-z0-9._-]+` matched `..` and the match ran
*before* `new URL(path, base)` normalized the string, so `/api/ai/install/packages/../decline` passed
the allowlist and was sent as `/api/ai/install/decline` — one segment of escape past `api_call`'s
only boundary.

When an endpoint is not allowlisted, update the dev MCP implementation and this table together instead of bypassing the tool. (Whether the allowlist should exist at all is an open question — tempdoc 844 §11.5 argues a control with a sanctioned `curl` bypass is a tax rather than a control. Until that is decided, this table documents what is, not what should be.)

## Search and Ingest

- Use `justsearch.dev.search_query` for search checks instead of constructing search requests manually.
- Use `justsearch.dev.ingest` for indexing targeted paths during dev investigations. Paths must be under the repo root.

## AI Runtime Tools

- Use `justsearch.dev.ai_activate` when an investigation requires the online local AI runtime. It takes an optional `chatProfile?: "compact" | "standard"` (tempdoc 842) — activation is when llama-server spawns, so it's the switch point for changing chat model pair; measured switch cost is single-digit seconds either direction.
- **The chat model is also runtime-configurable by explicit path — no installer pack-import or `-D` restart needed.**
  `POST /api/settings/v2` with `{"llm":{"modelPath":"<gguf>","gpuLayers":99}}`, then `ai_activate`. An explicit
  path is operator-owned and wins over the profile (tempdoc 842 precedence); prefer `chatProfile` unless you
  need a model outside the registry pairs.
- Do not treat embedding readiness and online LLM readiness as the same thing. Embeddings are Worker-side; online chat/QA uses the app inference runtime.
- `justsearch.dev.quick_health` reports `aiActive` (real tri-state: `true`/`false` for a reachable stack, `null` when unreachable) plus a `model` block (`chatProfile`, `modelPath`) when the runtime reports realized chat identity, and a declared `freshness` block (tempdoc 637) aggregating build/index/binding/lock staleness sources.

## The Foreign-Run Register

Two dev-stack lifecycles cannot see each other (tempdoc 844 §6.1): the MCP tools know only what
`dev-runner.cjs` started, while `jseval` boots its own backend on the hardcoded port `33221`. That
already cost a measurement round. `quick_health` therefore *probes* known ports — and, since D3, a
producer can also **declare** its run, so `foreignRuns` reports identity rather than inferring
"something is listening".

**Location.** `<main-checkout>/tmp/dev-runner/foreign/<producer>-<pid>.json`, one file per backend.
Both sides honour `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` (the isolated-dev-runner override used by
integration tests and throwaway stacks), so a reader is never pointed at a directory the producer
does not write to.

Deliberately a *sibling* of the dev-runner's own state, never inside it. `dev-runner.cjs` never
enumerates its state root — it globs only `runs/` (which it also **prunes**) and reads
`active.json`, `active.lock.json`, `op-leases.json`, `sessions/` and `interference-events.ndjson`
by exact name. So `foreign/` is invisible to the 271/542 lease and admission logic, cannot be
mistaken for one of its runs, and cannot be deleted by run retention. Nothing here writes
`active.json` or an op-lease.

**Producer.** `scripts/jseval/jseval/run_register.py`. `start_backend` writes the record at spawn
(not after health — the JVM holds ports, the data dir and the GPU from the moment it starts, and
that boot window is exactly what a neighbour's "is the machine free?" check must not miss);
`stop_backend` removes it, keyed by pid. Writes are a temp file plus an atomic `os.replace`, so a
torn record is never readable. Registration is best-effort: a failed write logs a warning and
leaves the run merely *observed*, never failing the eval run.

**Record (v1).** Small and versioned; a reader that does not know the version reports the record as
`unreadable` rather than guessing.

| Field | Meaning |
|---|---|
| `schemaVersion` | `1`. Bumped only for a breaking shape change. |
| `producer` | `"jseval"`. |
| `recordId` / `pid` | `jseval-<pid>`, and the producer process id used for liveness. |
| `ports.api` | The backend's HTTP port (`33221` for jseval). |
| `repoRoot` / `dataDir` | Which tree it was launched from, and which data dir it holds. |
| `workload` | `"eval-backend"` (`:modules:ui:runHeadlessEval`). |
| `inferenceRequested` | Whether `-Pllm=true` was asked for. |
| `gpuBound` | `"unverified"` — the producer does **not** measure GPU residency, so it declines to claim it either way. Treat a live eval backend as GPU contention anyway: its Engine loads the ONNX encoder stack and this repo has no CPU fallback. |
| `sessionId` | The agent session that owns it, from `tmp/agent-telemetry/current-session-id`, or `null`. |
| `startedAt` | UTC ISO-8601, second precision. |

**The record makes no liveness claim** — by design. A killed `jseval` never runs its cleanup, so a
leaked record must never masquerade as a live backend. Liveness is decided by the *reader*
(`probeForeignRuns`, `scripts/dev/justsearch-dev-mcp/server.mjs`) from the pid and the port, and
reported as the `state` described under Standard Workflow. A `stale` record is reported, never
silently deleted: deleting another lifecycle's state on a read would be exactly the confident guess
this surface is supposed to have stopped making. The entry names `recordFile` so removal is trivial.

The reader keeps the same distinction about *itself*: `foreignRuns: []` means "I listed the register
and it was empty", and only a genuinely absent directory (`ENOENT`/`ENOTDIR`) produces it. Any other
directory-read failure — a permission denial, a handle exhaustion — propagates and the field becomes
`null`, "I did not look". Lane F stage A removed a clause here that claimed `stop_backend` also sweeps for an orphan Worker
JVM before retiring the record. There is no second JVM to be orphaned, and there is no such sweep in
the code (`grep -n orphan scripts/dev/dev-runner.cjs scripts/dev/justsearch-dev-mcp/server.mjs`
returns nothing). The process-tree kill covers the one process.

**Scope.** This is one producer, one consumer and one small record — not a general run registry
(tempdoc 844 §12.4 rules that out). Registration is not enforced anywhere; a bare
`gradlew runHeadless` still registers nothing, which is precisely why the port probe stays as the
fallback that keeps the register honest about what it does not cover.

## Hot Reload

`justsearch.dev.reload` compiles a module, pushes the changed **method bodies** into the running
Engine over JDWP, and signals `DevReloadManager` to reconstruct the Engine's index-half services — carrying
`ModelContext` (embedding, SPLADE, NER, compat controller) across the reconstruction. Keeping the
ONNX encoders loaded is the capability; a warm restart reloads them and costs ~40s to worker-ready.

What it does **not** do: structural changes (added/removed methods, fields, constructors) are
rejected by standard HotSwap. They are reported as `structuralChangeDetected` + `restartRequired`,
never staged or half-applied — restart the stack for those.

Parameters: `module` (restricted to the module the run recorded — see below), `skipCompile`,
`takeover`, `debugPort` (a diagnostics-only override of the recorded port), `sessionId`.

**The classpath guarantee, and what `skipBuild` does to it.** The Engine's classpath carries the
hot-reload classes dir **first**, ahead of the installDist jars, so the classes a push redefines and
the classes loaded afterwards come from one build. That only holds when the classes dir and the jars
*are* one build. `start` establishes it two ways and never assumes it:

| Start | How the pairing is established | Result |
|---|---|---|
| default (build step runs) | `assemble` + both `installDist` tasks in one Gradle invocation produce both artifacts | classes dir goes first on the classpath; hot reload on |
| `skipBuild: true`, classes no newer than the installed `worker-services-*.jar` | the jar is built *from* those classes, so a jar at least as new contains them | as above |
| `skipBuild: true`, classes **newer** than the jar, or either side unreadable | it cannot be established | **hot reload is OFF for the run**, `run.json` records `hotReload.reason` + `classpathVerdict`, and the classpath is exactly the pre-844 jar set |

The third row is the point: a self-consistent (possibly stale) jar set is an honest state, a
worker-services classes dir in front of another build's jars is not. `reload` then refuses with
`HOT_RELOAD_NOT_ENABLED` quoting the dev-runner's own recorded reason rather than inventing one.
Restarting without `skipBuild` restores hot reload. Note `skipBuild` also skips the two `installDist`
tasks, so a Java edit you have not installed is not in the running stack either (tempdoc 844 F4).

**`module` is restricted to the module the run recorded.** The run's classpath carries exactly one
hot-reload classes dir (`worker-services`), and the identity check only ever sees that one — so a
push from any other module's classes dir passed `IDENTITY_OK`, redefined already-loaded classes by
name, and left every class of that module loaded later coming from the stale jar, all reported as
`REDEFINED` / `hotSwapOk: true`. Another module is now refused with
`RELOAD_MODULE_NOT_ON_CLASSPATH`; restart the stack to pick up a change there.

Reported per call: `compiledFrom` (the tree it compiled and pushed from), `debugPort`,
`identityVerified`, `classesChanged` / `classesRedefined` / `classesNotLoaded`, `hotSwapOutcome`,
`signalWritten`, and `signalSkippedReason` when services were **not** reconstructed.

Three properties are load-bearing, and each replaces a measured silent failure (tempdoc 844 §5.6):

- **The compile root is the run's tree, not the caller's.** `reload` reads `repoRoot` from the
  active run record and compiles, pushes and reads the build stamp there. Previously it took the
  target from the run record and the bytecode from `process.cwd()` frozen at MCP-server launch, so
  under `distFrom` — 125 of 162 measured starts — a worktree agent's classes were redefined into
  another agent's JVM and reported as success.
- **The target VM proves its identity before anything is redefined.** The dev-runner records the
  hot-reload classes dir in `run.json` and puts that same absolute path first on the Engine's
  classpath itself (`assessHotReloadClasspath`, `dev-runner.cjs:901`) — `WorkerSpawner` did this
  until lane F stage A item A11 deleted it along with the process it spawned; `HotSwapPush` reads the attached VM's own classpath back over JDI and refuses
  unless the entry is there. Attaching to "whatever listens on 5005" is no longer possible.
- **Success is confirmed, not assumed.** A push that redefined zero classes is not success, the
  marker file is not advanced, and the reload signal is **not** written — a failed push no longer
  tears down and reconstructs a stack's services with no new bytecode to show for it. `REDEFINED n`
  is printed only after the JVM's redefinition returned, and any non-zero exit reports
  `classesRedefined: 0` — a JVMTI redefinition is atomic, so a failed push replaced nothing (tempdoc
  844 F2: a failed structural push reported three classes redefined).

### Reload Error Codes

| Code | Cause | Resolution |
|------|-------|------------|
| `NO_ACTIVE_RUN` | No active dev stack. | `start` first. |
| `OWNER_CONFLICT` | `reload` mutates a run, so it is ownership-gated like `start`/`stop`. | Same remedy as the start-tool code below. `takeover` authorizes the call; it does **not** transfer the lease. |
| `RUN_ROOT_UNRESOLVED` | The active run record does not say which checkout it was launched from, or that checkout is gone. | Stop and start the stack again. `reload` refuses rather than falling back to the caller's tree — that fallback was the defect. |
| `HOT_RELOAD_NOT_ENABLED` | The stack was started with `hotReload: false` (no JDWP listener exists); or its run record predates the per-run hot-reload record; or hot reload **was** requested and the dev-runner turned it off at start, in which case the message quotes its recorded `reason` and `classpathVerdict` (`CLASSES_NEWER_THAN_DIST`, `CLASSES_DIR_EMPTY`, `DIST_JAR_UNREADABLE`, `DEBUG_PORT_UNAVAILABLE`). | Restart the stack; `hotReload` defaults true. For a classpath verdict, restart **without** `skipBuild`. For `DEBUG_PORT_UNAVAILABLE`, free a port in 5005-5024 or set `JUSTSEARCH_DEV_DEBUG_PORT`. |
| `RELOAD_MODULE_NOT_ON_CLASSPATH` | `module` named something other than the module the run recorded as its hot-reload classes dir. Pushing it would redefine classes from a directory the target VM does not load from — reported as success while the module kept loading from its stale jar (tempdoc 844 M5). | Nothing was pushed. Omit `module`, or restart the stack to pick up a change in that module. |
| `HOTSWAP_TIMED_OUT` | The pusher was killed by its own timeout. Whether bytecode was redefined is **unknown** — the kill may have landed on either side of the JVM's `redefineClasses` call — so `classesRedefined` is `null` and no reconstruction signal was written. | Do **not** assume the Engine is unchanged. Restart the stack to get back to a known state. |
| `COMPILE_FAILED` | Gradle `compileJava` failed in the run's tree. | Fix the compile error; the tail of the Gradle output is in the message. |
| `TARGET_IDENTITY_MISMATCH` | The JVM on the run's JDWP port was not launched from the tree the run record names — **none** of its classpath entries lie under that tree. | Nothing was pushed. Re-orient with `quick_health` — a foreign or stale backend is holding that port. |
| `HOT_RELOAD_CLASSPATH_ABSENT` | The JVM **was** launched from the run record's tree, but without the hot-reload classes dir on its classpath — a distribution built before that classpath existed. Distinct from the cross-tree case above, which it used to be misreported as (tempdoc 844 F3). | Nothing was pushed. Rebuild the dist in that tree (`./gradlew.bat :modules:ui:installDist`) and restart the stack. |
| `TARGET_IDENTITY_UNVERIFIED` | The push tool did not confirm the target's identity (e.g. an older `HotSwapPush` copy). | Treated as not-confirmed rather than success; rebuild/refresh the checkout. |
| `NO_CLASSES_REDEFINED` | Changed classes existed, but none is loaded in the target VM, so no bytecode was replaced. | Not a success and not a signal-worthy event. Exercise the code path first, or restart. |
| `STRUCTURAL_CHANGE` | Added/removed methods or fields — standard HotSwap cannot apply it. Detected from the JVM's own wording too (`HotSwap not supported by target VM: add method not implemented` and the rest of that JDI family), which is what a real structural change actually prints; matching only the pusher's phrasing made this code unreachable until tempdoc 844 F1. | Restart the dev stack. |
| `HOTSWAP_FAILED` | The push exited non-zero for another reason (JDWP unreachable, JDI error). A *timeout* is `HOTSWAP_TIMED_OUT` above, not this. | `hotSwapOutput` carries the tail; check the stack is alive. |

A call that finds no changed class file since the last push returns `ok: true` with `noOp: true`
and writes no signal — distinct from a push that failed.

## Start-Tool Error Codes

`justsearch.dev.start` can refuse to launch with one of these codes (see tempdoc 271 + 542 for the ownership and operation-lease models). The first four are admission-gate refusals; the last two are pre-launch refusals about the checkout being launched from:

| Code | Cause | Resolution |
|------|-------|------------|
| `OWNER_CONFLICT` | Another session holds a fresh lease on the stack; takeover policy is `deny` (the default). | Inspect `quick_health.ownership.holder`. With user approval, retry with `takeover: "warn"` — or call `acquire_when_free` and act on its `recommendedTakeover`. |
| `HANDSHAKE_REQUIRED` | The holder is running a `MUST_COMPLETE` op-lease (migration, bulk-reindex, index GC, etc.); `warn` takeover is upgraded to a sync handshake. Response includes `criticalOps[]`. | Wait for the op to complete (use the per-op `expectedDurationSec` to estimate), or escalate to `takeover: "force"` with user approval (records a `forcibly_interrupted_critical_op` disposition in the stop-report). |
| `REQUIRES_CONFIRMATION` | A `force` takeover hit an `UNSAFE_TO_INTERRUPT` op-lease. | Pass `--confirm-interrupt=<opId>` matching one of the `criticalOps[].opId` values in the response. The typed token guards against typo'd reclaims of unsafe-to-interrupt ops. |
| `RUN_NOT_FOUND` / `NO_API_URL` | The active run record references a runId that no longer exists or has no `apiBaseUrl`. | Call `quick_health` to re-orient; the run may have partially failed. |
| `DIST_NOT_BUILT` | The checkout being launched from has no Head dist (`modules/ui/build/install/ui/bin/ui.bat`) — typically a fresh worktree, or `skipBuild: true` without a prior `installDist`. `error.details` carries `distPath`, `repoRoot`, and `remedy`. | `node scripts/dev/prepare-worktree.cjs` in that checkout, or `./gradlew.bat :modules:ui:installDist`. Run `preflight { distFrom }` with the same value first — it checks the dists in the tree `start` will use. |
| `START_TIMED_OUT` | The dev-runner start subprocess did not report a result inside `startTimeoutMs`. **Readiness was not confirmed and it is not established that this call started anything.** Whatever could be read off disk afterwards is under `observed` (active runId, recorded ports/URLs, and one `/api/health` status code) — observations, not a started stack. Before tempdoc 844 this branch synthesized `ok: true` from any runId in `active.json`, so a start that died mid-boot, or a run that was already there, was reported as a successful start. | Call `quick_health { probe: true }` to find out what is actually running, and `tail_log { kind: "backend_stderr" }` if the boot failed. |
| `INVALID_DIST_FROM` | `distFrom` is neither the main repo nor a sibling worktree under `.claude/worktrees`, or that checkout has no `scripts/dev/dev-runner.cjs`. A **bare worktree name** (`"round14"`) is resolved against `.claude/worktrees/<name>`; when no such directory exists the message lists the names that do. | Pass a worktree name, a path to a sibling worktree, or the main repo root. |

Before tempdoc 844, the missing-dist case returned `UNHANDLED` — a fully-understood, recoverable condition classified as an unhandled exception on 16 of 20 measured `start` errors. It is now classified at the layer that detects it (`scripts/dev/dev-runner.cjs`), so severity and code match reality.

`quick_health.ownership.opLeases[]` (added tempdoc 542) surfaces the active critical op-leases on the holder so an agent can see what would be interrupted before requesting takeover.

The ownership/contention model those codes belong to — verdicts, leases, takeover policy, `distFrom`, campaign-length holds — lives in the `/dev-stack` skill, which is also where troubleshooting and worktree-FE serving live.
