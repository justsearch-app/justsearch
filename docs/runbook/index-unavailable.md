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

## Likely causes (by reason code)

| Reason code | Cause | Urgency |
| --- | --- | --- |
| `WorkerStarting` | Worker process is still coming up after a restart. | Low — should clear within ~30s. |
| `WorkerCrashed` | Worker process crashed; supervisor is restarting it. | Medium — investigate the crash log. |
| `IndexCorrupted` | Lucene index integrity check failed. | High — manual rebuild required. |
| (no reason) | Worker is unreachable; reason wasn't classified. | Investigate before treating as transient. |

## Diagnostics

1. Check Worker process state:

   ```powershell
   Get-Process -Name java | Where-Object { $_.MainWindowTitle -like '*indexer-worker*' }
   ```

2. Check `/api/health` and `/api/debug/state` for the lifecycle envelope. Look at `WORKER_CONTROL_PLANE` and `INDEX_SERVING` dimensions and their reason codes.

3. Look for stack traces in the Engine log — since lane F stage A there is no separate `worker.log`; the index half logs into the one Engine log:

   ```powershell
   Get-Content (Join-Path $env:LOCALAPPDATA 'JustSearch\logs\engine.log') -Tail 200
   ```

## Remediation

- **`WorkerStarting`** — wait. If it doesn't clear within a minute, treat as `WorkerCrashed`.
- **`WorkerCrashed`** — read the last stack trace in `engine.log`; if startup is failing repeatedly, see [`index-start-error.md`](index-start-error.md).
- **`IndexCorrupted`** — trigger a full reindex from the Health view, or in dev with `jseval run --reset`.
- **Worker unreachable, no reason** — there is no Worker port to check: the index half runs in the Engine JVM behind in-process ports (ADR-0049), so "unreachable" means the knowledge client is unset because the index half has not composed. Read `engine.log` for the composition failure.

## Related

- `index.start-error` — when the indexer fails to start at all (see [`index-start-error.md`](index-start-error.md)).
- `schema.blocked` — schema mismatch (different remediation path; reindex via Health view).
- Architecture: `docs/explanation/01-system-overview.md` (Worker process role).
