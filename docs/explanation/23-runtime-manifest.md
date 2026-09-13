---
status: stable
description: Producer-published runtime manifest at <dataDir>/runtime/manifest.json (filesystem) and GET /api/runtime/manifest (HTTP). Canonical answer for "how does a non-JVM consumer find the running JustSearch instance?" — tempdoc 501 §6 closure rule.
---

# Runtime manifest — non-JVM service discovery

The JustSearch backend publishes one structured, versioned document
describing the currently-running instance. Every non-JVM consumer reads
this single document to answer **"what is the currently-running instance,
how do I reach it, and is it still alive?"** Tempdoc 501 introduced the
primitive; this page is the standing convention.

## The closure rule

Future questions of the form *"how does non-JVM consumer C find runtime
fact F?"* have exactly **one** acceptable answer:

> *"F is a field on the runtime manifest, served at the filesystem path
> and the HTTP endpoint, watchable via SSE, scoped to the current
> `instanceId`."*

When a future PR proposes a tenth mechanism — a new env var, a new
sibling file in `<dataDir>/runtime/`, a new query parameter, a new
stdout line consumers parse — the review answer is **"extend the
manifest."** The convention closes the door that today is open to nine
variant answers.

This rule is mechanically enforced by
`scripts/ci/check-runtime-manifest-closure.mjs`. The script greps for
any code that constructs `<dataDir>/runtime/<artifact>` paths and fails
if the artifact isn't on the allowlist inside the script itself.
Adding an artifact is therefore a deliberate design event, not a
routine merge.

## Anatomy

### Filesystem transport

`<dataDir>/runtime/manifest.json` — written by HeadlessApp. JSON
document with these fields:

- `schemaVersion` (int) — bump for breaking changes.
- `instanceId` (UUID) — fresh per process. Consumers cache by this
  field; when it changes, every consumer's cache invalidates by
  definition.
- `pid` (long) — for liveness checks. Consumers call
  `ProcessHandle.of(pid)` / `OpenProcess` / `kill -0` before trusting
  any other field.
- `startedAt`, `dataDir`.
- `lifecycle` (string) — `STARTING` | `READY` | `DEGRADED` | `ERROR`.
  Projection of `LifecycleProjection.derive(WorkerCapability,
  InferenceCapability)`. Updated whenever either capability transitions.
- `head` — `apiPort`, `apiBaseUrl`, `sessionToken` (filesystem only;
  see below), `readyAt`. Always present. The pre-bind ownership seed has
  these binding fields absent; the API-bind publication fills all three together.
- `worker` — `state` (`"pending"` | `"ready"` | `"failed"`),
  `indexBasePath`, `readyAt`, `spawnError`. Null until
  the first worker-state publish; the `state` discriminator is the
  authoritative tri-state surface (state=`failed` carries
  `spawnError` with the upstream reason). Updates on every
  `WorkerCapability` transition.
- `children` — filesystem-only managed child records containing child id,
  kind, PID, process start instant, executable identity, endpoint, and
  declared/realized configuration hashes. The successor carries predecessor
  records forward before child-capable bootstrap and reconciles them by all
  three OS identity axes before adoption or termination.
- `shutdownHandoff` — filesystem-only `pending`, `ready`, or `incomplete`
  disposition. The ordered shutdown marks pending early and completes the
  handoff only after all close outcomes are known. Restart/hang retains live
  ownership; quit/upgrade deletes the canonical manifest only after registered
  children are confirmed gone and the index reports `GRACEFUL`.
- `ai` — `phase` (`CapabilityHealth.name()` — `PENDING` / `READY` /
  `DEGRADED` / `OFFLINE` / `RECOVERING`), `required` (boolean —
  is inference configured?), `pendingReason` (string), `readyAt`
  (set when phase first reaches `READY`). Projection of
  `InferenceCapability`; updates on every transition.
- `reachability` — typed transport list (tempdoc 501 §13.4.2 / Phase
  30). Each entry: `kind` (`http-rest` | `sse` | `well-known` |
  `filesystem` | `mcp` | `probe`), `url` (URL for HTTP-class kinds;
  tool name for MCP; filesystem path for filesystem), `audience`
  (`public` | `full`). The HTTP transport carries only audience=public
  entries; the filesystem transport carries both. The publisher reads
  the list from `RuntimeTransportRegistry` populated at
  `LocalApiServer` route-registration time — single source of truth.
- `runtimeContract` — the JustSearch Runtime Contract descriptor (tempdoc
  654): the coarse `version` plus a `constituents` block pinning the
  manifest-schema / lifecycle-schema / MCP-protocol / MCP-tool-surface
  versions. A projection over existing version single-sources
  (`RuntimeContract.current()` in `app-api`), nullable and `NON_NULL`, so it
  is carried at schema v2. This is the field an external agent reads to learn
  "what is promised, at what version." Full definition, compatibility matrix,
  stability policy, and surface classification:
  [The Runtime Contract](28-runtime-contract.md) +
  [Runtime Contract reference](../reference/runtime-contract.md).

History per instance lands at `<dataDir>/runtime/instances/<instanceId>/`
(tempdoc 501 §13.4.4 / Phase 24-25):

- `manifest.json` — terminal snapshot, overwritten on each publish.
  Convenient for one-shot postmortem readers.
- `manifest.log.ndjson` — append-only series (one compact JSON object
  per publish event). The canonical history of every publish during
  this instance's lifetime.
- `start.log` — append-only human-readable narrative; one timestamped
  line per publisher transition (`publisher-constructed`,
  `publishHead`, `publishWorkerReady/Failed`, `publishAi`,
  `publishLifecycle`, `publisher-close`).

Retention: by-count default 50 instance directories, pruned on
publisher construction.

Producer-owned primitives (`instanceId`, `pid`, `dataDir`, `startedAt`,
history) live in the manifest because they're identity facts; everything
else is a projection of `LifecycleProjection.derive` /
`WorkerCapability` / `InferenceCapability` per tempdoc 501 §12.1.

### HTTP transport

`GET /api/runtime/manifest` returns the same JSON document, with one
explicit redaction: `head.sessionToken` is stripped. The filesystem
manifest is gated by filesystem permissions; the HTTP endpoint is
reachable by anything that can resolve the loopback port. Tools that
need the token use the existing `/api/mcp/token` endpoint (in-process)
or stdout drain (the Tauri sidecar's redaction-aware reader).

Change notification rides a dedicated SSE stream:
`GET /api/runtime/manifest/stream` with `streamId
registry:runtime-manifest`. UPDATE frames fire on each manifest
rewrite (HTTP bind, Worker connect, Worker post-boot transition,
Inference transition).

A standard-discovery mirror is served at
`GET /.well-known/justsearch/manifest.json` (RFC 8615). Same
redacted body; convention-driven discoverability for external
HTTP-only consumers (browser extensions, third-party tooling).

MCP clients reach the same projection via the
`justsearch_runtime_manifest` curated tool, served through
`POST /mcp` (`tools/call` with `name: "justsearch_runtime_manifest"`).
Result content + structuredContent both carry the public projection.

#### Time-axis reader endpoints

`GET /api/runtime/instances` — list known instance directories, newest
first. Carries metadata for each (hasSnapshot, hasLog, logLines).

`GET /api/runtime/instances/{id}` — terminal snapshot + ndjson publish
log + start.log lines for one instance. Pagination via
`?fromLine=<n>&limit=<n>` (default limit 200, max 2000); response
carries `logFromLine`, `logLimit`, `logTotalLines`, `logTruncated`.
Streaming read so a long-running instance doesn't load the entire log
into memory per request.

#### Readiness / liveness probes (k8s pattern)

`GET /api/runtime/ready` → 200 with `{ready: true, lifecycle, instanceId}`
when `lifecycle === "READY"`; 503 with `{ready: false, lifecycle,
instanceId}` otherwise. `HEAD` returns the same status with no body.

`GET /api/runtime/live` → 200 with `{alive: true, pid, instanceId}`
unconditionally (by definition true if the request reaches the
handler). `HEAD` returns 200 with no body. The manifest cannot
self-detect its own producer dying from inside the dying process;
external probes at the HTTP layer are the canonical liveness signal
(tempdoc 501 §13.7 Q6).

### Audience axis — typed public projection

Each manifest record (top-level + `HeadInfo` + `WorkerInfo` + `AiInfo`
+ `Reachability`) declares a `publicProjection()` method. The HTTP /
SSE / MCP / well-known transports serve `manifest.publicProjection()`;
the filesystem transport serves the full record. Today's
`publicProjection()` implementations strip `head.sessionToken` and
filter `reachability` to audience=public entries — adding a future
credential-class field forces an update at the record's declaration
site, not at a single static helper.

### Multi-instance enforcement

`<dataDir>/app.lock` — OS-level `FileChannel.tryLock` with PID +
actual process-start metadata (when available). Metadata is diagnostic only; OS
locks release on process exit and refusal never deletes the lock file. Same-JVM
contenders are refused before a second channel can disturb the native lock. Held by HeadlessApp for
the life of the process; a second Head against the same dataDir exits
with a structured diagnostic and code 2.

## What stays unchanged

- **MMF** (`MmfWorkerSignalLayoutV1`) — this no longer exists. Lane F stage A
  (item A10) deleted the memory-mapped Head ↔ Worker signal bus along with
  the Worker process itself (item A11); the scheduling signals it carried
  now live in the in-process `GpuSchedulingGauge`
  (`modules/core/src/main/java/io/justsearch/core/scheduling/GpuSchedulingGauge.java`).
  Different domain from this manifest either way — this bullet is retained
  only to record that the mechanism it once named is gone, not replaced.
- **`/api/status`** — cheap readiness probe. Kept for sandboxes and
  remote callers where PID inspection is awkward.
- **`JUSTSEARCH_API_PORT` env var as configuration** — "try to bind
  to port X" is an *input* to the backend. The manifest reverses the
  direction. Not a collision.
- **`?api_port=N` URL escape hatch** — manual override for debugging.
- **Stdout `JUSTSEARCH_API_PORT=...` line** — kept as human-readable
  log output; no consumer parses it. The Tauri sidecar's stdout drain
  still redacts `JUSTSEARCH_SESSION_TOKEN=` lines so they don't land
  in the log file.

## Cross-language data-dir contract

`<dataDir>` itself is resolved by an identically-shaped contract
across every language that touches the manifest:

- Reference: `modules/configuration/src/main/java/io/justsearch/configuration/PlatformPaths.java`
- Spec: `contracts/platform-paths/spec.v1.json` (env-var/sysprop
  precedence, platform-default templates, fixture set).
- Node: `scripts/lib/platform-paths.mjs`.
- Rust: `modules/shell/src-tauri/src/platform_paths.rs`.
- Contract tests in Java + Node + Rust enforce identical outputs.

## Adding a new fact

Step-by-step when a non-JVM consumer needs runtime fact F:

1. **Name the authoritative source** the field projects (tempdoc 501
   §12.1 closure-rule companion: "one new field must name its
   source"). If the field has no authoritative source — if the
   manifest itself would be inventing the state — that's either a
   producer-owned primitive (small list: instanceId, pid, dataDir,
   startedAt, history pointer) or it's wrong and belongs as state in
   one of the canonical graphs first.
2. Add a field on `RuntimeManifest` (or one of its sub-records) in
   `modules/app-api/src/main/java/io/justsearch/app/api/runtime/`.
3. Populate it in `RuntimeManifestPublisher.publishHead` /
   `publishWorkerReady` / `publishWorkerFailed` / `publishAi` /
   `publishLifecycle` (or add a new public method that builds the
   mutated manifest and ends with `return commit(manifest, "...")` —
   the shared commit helper handles write + listener notify + start-log
   append in postmortem-resilient order).
4. If F is sensitive (credential-class), declare it stripped in the
   relevant record's `publicProjection()` method. The type system
   enforces that adding a new sensitive field forces an update at
   the record's declaration site, not at a single static helper.
5. Wire a listener on the authoritative source (Capability or
   equivalent) so the projection stays current after boot —
   `RuntimeManifestListenerWiring.wire(...)` in
   `modules/ui/src/main/java/io/justsearch/ui/runtime/` is the
   established collector for these. Add a clause there if the new
   field needs its own transition source.
6. Bump `RuntimeManifest.CURRENT_SCHEMA_VERSION` if the addition is
   a breaking change for older readers (rare — new optional fields
   don't break, per `RuntimeManifestSchemaCompatibilityTest`).
7. If F is a new transport (HTTP route, MCP tool, well-known mirror,
   filesystem path), also register it with `RuntimeTransportRegistry`
   in `RuntimeApiRoutes` so the manifest's `reachability` field
   advertises it. The closure-check script verifies the registry
   matches actually-registered routes.
8. Read the field in the consumer. HTTP / SSE / filesystem /
   well-known / MCP transports all carry it automatically.
9. Do **not** add a new sibling file, env var, or stdout line. The
   closure-check script (`scripts/ci/check-runtime-manifest-closure.mjs`)
   blocks new sibling files, unsanctioned stdout `JUSTSEARCH_X=` emits,
   and `Files.write*` calls into `<dataDir>/runtime/` from outside the
   publisher package — at PR time.
