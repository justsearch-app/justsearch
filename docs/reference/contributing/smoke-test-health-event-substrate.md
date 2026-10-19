---
title: "Smoke test: HealthEvent Resource substrate (slice 430)"
type: reference
status: stable
description: "Manual + scripted verification procedure for the HealthEvent Resource substrate; runs verification gate items 3-10 + 7a from tempdoc 430."
---

# Smoke test: HealthEvent Resource substrate (slice 430)

This procedure executes the `verification gate` items 3-10 + 7a deferred at slice 430's merge to `main`. It is the closure step for slice 1.1.a (HealthEvent Resource).

Run this procedure once before relying on the substrate for downstream slices (Log/Table Resource instances inheriting `ResourceAreaValidator`).

## Prerequisites

- Backend not already running on port 33221.
- Distribution built: `node scripts/lib/tool-invoker.cjs gradle assemble` (wires in `installDist`).
- Models directory present (or set `JUSTSEARCH_MODELS_DIR`).
- Item 8 additionally requires an activated chat runtime (Brain/llama-server). Skip item 8 if not testing AI-mediated paths.

## Quick procedure

In one terminal, start the backend in eval mode:

```text
node scripts/lib/tool-invoker.cjs gradle :modules:ui:runHeadlessEval
```

Wait for Engine readiness. The former `scripts/smoke/health-event-substrate.ps1`
is not in the repository. Run the read-only checks below instead; the Node commands
work in Bash, PowerShell and cmd.exe with the project's supported Node version.
The former separate-worker fault in item 6 is explicitly unverified for the current
single-Engine architecture.

## Items

### Item 3 — `/api/registry/resources` returns `health.events`

```text
node -e "fetch('http://127.0.0.1:33221/api/registry/resources').then(r=>r.json()).then(x=>console.log(x.entries.filter(e=>e.id.value==='core.health-events')))"
```

Expected: one entry with `subscriptionMode = "SSE_STREAM"`, `endpoint = "/api/health/events/stream"`, `kind = "health-event-stream"`. Note: the `id` field serializes as `{"value": "core.health-events"}` (an `OperationId` value object), not a bare string.

### Item 4 — `/api/messages/health-events/en` serves the i18n catalog

```text
node -e "fetch('http://127.0.0.1:33221/api/messages/health-events/en').then(async r=>{console.log(r.headers.get('etag'),r.headers.get('cache-control'));console.log(Object.keys((await r.json()).keys).length)})"
```

Expected: ETag header present; `Cache-Control: max-age=3600`; key count >= 23 (one per backend-emitted catalog ID).

### Item 5 — `/api/health/events/stream` SSE works

```text
node -e "fetch('http://127.0.0.1:33221/api/health/events/stream',{headers:{Accept:'text/event-stream'}}).then(async r=>{for await(const chunk of r.body)process.stdout.write(chunk)})"
```

Expected: `event: snapshot` followed by JSON containing current `conditions` + recent `occurrences`. Heartbeats every 15s (per `HealthEventStreamController.HEARTBEAT_SECONDS`). Leave this running; it will receive `event: delta` frames as later items fire.

**Important**: the `Accept: text/event-stream` header is required. Without it, the endpoint returns `200 OK` with `Content-Type: text/plain` and `Content-Length: 0` (Javalin content-negotiation default). SSE clients (`EventSource`, `@microsoft/fetch-event-source`) send the header automatically; ad-hoc curl/PowerShell users must include it explicitly.

### Item 6 — Condition transition (former worker-process fault)

The Engine now hosts the worker in the same JVM. There is no separate worker PID
to stop while keeping this SSE connection alive. Never select a process by its
image name or window title. The former `index.unavailable` asserted/removed
transition from killing that separate worker remains **unverified** here; a whole
Engine restart is not evidence of that same transition.

Use the existing supervisor campaign for process-lifetime and recovery checks:

```text
node scripts/supervisor-conformance/run.mjs --adapter dev-runner
```

That campaign owns and records its fixture processes and stops them by identity.
It does not claim to validate this former separate-worker SSE transition.

### Item 7 — Threshold via bridge (throughput stall)

Source-of-emit: `LifecycleSnapshotTap` (per rev 3.7 §B.T.1; the `WorkerHealthEvents` bridge was dropped from V1).

**Status: trigger flag not built.** The 430 spec referenced `jseval run --reduce-throughput`; this flag does not exist in the current jseval CLI (`python -m jseval run --help` confirms). Achieving a deterministic throughput stall requires either:

- Adding a `--reduce-throughput` flag to jseval (deliberate slowdown injection), or
- Manually exercising a degraded scenario (e.g., starve the worker of CPU via `Set-ProcessAffinity`, then submit an indexing workload).

Until either path is in place, item 7's substrate path is validated indirectly: the relevant unit checks exercise `LifecycleSnapshotTap` → `ConditionStore`; the current live worker-death and throughput-specific SSE transitions remain unverified.

Deferred and currently **unowned** — no active tempdoc holds this item (slice 430 has no tempdoc file); whoever next opens the health-event substrate decides whether to add the flag. (Formerly tracked in the observations store, retired in tempdoc 872.)

### Item 7a — Threshold via rule engine (memory pressure)

Apply heap pressure (load a large corpus or run an indexing job that holds memory):

```text
cd scripts/jseval
python -X utf8 -m jseval ingest-bench --dataset scifact --concurrent 8
```

After `for: 60s` dwell, watch the SSE stream for `memory.pressure` ThresholdState `FIRING`. Stop the workload; after `keep_firing_for: 30s` grace, observe `RESOLVED`.

**Note: heap pressure is on the HEAD process, not the worker** (the rule engine consumes head-side `RrdMetricStore` per slice 430 §A.9). `ingest-bench --concurrent 8` primarily exercises the worker; head heap doesn't necessarily exceed 90% from this workload alone. To reliably trigger 7a, you may need to lower `-Xmx` on `runHeadlessEval` (e.g., 256m) or run a JMH-style head-side allocation harness. As of 2026-05-05, no built-in trigger exists; the rule engine's substrate is unit-tested (`DwellTimeSchedulerTest`, `RuleRunnerTest` per slice 430), but the live end-to-end dwell-time path is unvalidated.

### Item 8 — Lifecycle event (agent session)

Requires an activated chat runtime. The jseval `--llm` path is deliberately refused; activate chat through the sanctioned UI/API workflow instead. Complete an agent session (e.g., via the FE agent surface or directly via `/api/chat/agent/...`).

Watch the SSE stream: `agent.session.completed` LifecycleEvent should appear when the session finishes normally.

### Item 9 — Capability catalog mutation broadcast

```text
node -e "fetch('http://127.0.0.1:33221/infra/capabilities/stream',{headers:{Accept:'text/event-stream'}}).then(async r=>{for await(const chunk of r.body)process.stdout.write(chunk)})"
```

At backend boot, `HealthResourceCatalog.register()` increments `catalogVersion`; subscribers see a `modified` event. To trigger again mid-session, restart the backend with the SSE connection from a third terminal still running.

### Item 10 — FE consumer renders streamed events

With the dev stack running and the FE dev server up:

```text
cd modules/ui-web
npm run dev
```

Open the Health view. The former item 6 worker-kill trigger is unavailable in the current architecture; do not mark this banner check passed from a whole-Engine restart. When a supported condition trigger is available, the banner must come from the SSE stream rather than `/api/status` polling. Verify by killing `/api/status` connection at devtools network panel — Health view should still update.

## Outcome recording

When the smoke is complete, record the result in the task evidence directory as the closure entry, recording:

- Items passed.
- Items skipped + reason (e.g., item 8 skipped because `--llm` not configured).
- Any deltas observed that don't match the spec (these become rev 3.21+ entries or new tempdoc findings).

## Reusable for future Resource-instance slices

Slices 1.3 (Log Resource, Table Resource) inherit `ResourceAreaValidator` from 430 §B.AG. The structure of this smoke procedure (registry entry + i18n catalog + SSE stream + condition/event triggers) generalizes; adapt the per-item commands when those slices ship.
