---
title: "Runbook: `index.unavailable`"
type: runbook
status: stable
description: "Operator response when the indexer reports unavailable; search and ingestion are paused."
---

# Runbook: `index.unavailable`

The indexer is reporting unavailable. Search queries fail; ingestion is paused.

## Symptoms

- Health view shows the **Indexer unavailable** banner.
- `/api/health/events/stream` emits an `AssertedCondition` with `id="index.unavailable"` and `status=TRUE`.
- `/api/status` shows `INDEX_SERVING` in `NOT_READY` or `NOT_CONFIGURED`.

## Reason vocabulary

`/api/status` and `/api/debug/state` are authoritative. Their schema 2 lifecycle envelope uses dotted `LifecycleReasonCode` values. The health event stream projects that value into PascalCase `AssertedCondition.reason` (for example, `index.starting` becomes `IndexStarting`). The generic condition wire shape also permits names such as `WorkerStarting`, `WorkerCrashed`, and `IndexCorrupted`; these names do not identify a separate worker process. Use the dotted status reason for diagnosis.

| Current status reason | Meaning and action |
| --- | --- |
| `index.starting` | The Engine index component is opening. Poll the readiness envelope; do not infer failure from elapsed time alone. |
| `index.failed` | Index startup or health failed; Engine recovery may still be in progress. Read the Engine log and follow the state until it becomes ready or reaches `component.recovery_exhausted`. |
| `component.start_deadline` | The index component missed its startup deadline. Inspect the Engine bootstrap detail and follow [`index-start-error.md`](index-start-error.md). |
| `index.corrupt` | Essential index corruption. The ordered, counted Engine restart path is requested; inspect the post-restart detail and log. Do not promise a Health-view rebuild. |
| `index.schema_open_refused` | The stored index cannot open under the active schema policy. This essential failure uses the same ordered, counted Engine restart path; inspect the policy detail after restart. Do not promise a Health-view rebuild. |
| `component.recovery_exhausted` | The component recovery budget is spent. The recovery handler directs the operator to restart the application before retrying. |
| `index.not_healthy` | The serving health check failed. Use `core.rebuild-index` only when the current Health payload declares that recovery operation for this reason. |
| `index.unavailable`, `index.shut_down`, `engine.not_started` | The index is not serving. Check composition and startup state in the Engine log; start or restart the application when the Engine is stopped. |

The system overview defines corruption and schema mismatch in an essential index as an ordered, counted Engine restart (exit code 5). The runtime does not promise a read-only incumbent or a UI rebuild action for those fatal causes. See [the system overview](../explanation/01-system-overview.md) for that contract.

## Diagnostics

1. Check `/api/status` and `/api/debug/state`. Record the lane F retained `INDEX_CONTROL_PLANE` and `INDEX_SERVING` states and their dotted reason codes.

2. Check `/api/health` and `/api/health/events/stream` for the lifecycle envelope and condition detail. Treat the PascalCase event reason as a projection; use the dotted status reason when selecting the response above.

3. Look for stack traces in the Engine log - since lane F stage A there is no separate `worker.log` file (retired filename); the index half logs into the one Engine log:

   ```powershell
   Get-Content (Join-Path $env:LOCALAPPDATA 'JustSearch\logs\engine.log') -Tail 200
   ```

## Remediation

- **`index.starting`** - keep polling `/api/status` and `/api/debug/state`. There is no fixed timeout promise. If the reason changes to `index.failed` or `component.recovery_exhausted`, follow that row and inspect `engine.log`.
- **`index.failed` or `component.start_deadline`** - read the failure detail and allow an admitted Engine recovery attempt to report its outcome. If recovery reaches `component.recovery_exhausted`, restart the application to reset the recovery budget; if startup fails again, see [`index-start-error.md`](index-start-error.md).
- **`index.corrupt` or `index.schema_open_refused`** - preserve the reported detail, allow the ordered Engine restart to complete, and inspect the new `/api/status` and `/api/debug/state` values. If the Engine did not restart, relaunch JustSearch. Follow any explicit policy or data-repair instruction in the detail; do not trigger a generic Health-view rebuild.
- **`index.not_healthy`** - use `core.rebuild-index` only when the current Health payload declares it for this reason; do not infer this remedy for corruption or schema-open refusal.
- **`index.unavailable`, `index.shut_down`, or `engine.not_started`** - confirm the Engine composition and startup state in `engine.log`. Start or restart JustSearch when the Engine is stopped, then verify `/api/health` and `/api/status`.
- **No reason** - there is no Worker port to check: the index half runs in the Engine JVM behind in-process ports (ADR-0049), so an unreachable index means the knowledge client has not been composed or the serving component is down. Read `engine.log` and use the lifecycle reason once it is published.

## Related

- `index.start-error` - when the index component fails to start (see [`index-start-error.md`](index-start-error.md)).
- Index schema migration and its policy details: [`11-index-schema-migration.md`](../explanation/11-index-schema-migration.md).
- Architecture and restart contract: [`01-system-overview.md`](../explanation/01-system-overview.md).
