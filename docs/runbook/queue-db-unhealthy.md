---
title: "Runbook: `queue-db.unhealthy`"
type: runbook
status: stable
description: "Operator response when the Worker queue database reports unhealthy state; ingestion is paused."
---

# Runbook: `queue-db.unhealthy`

The Worker queue database (the durable inbox the Worker uses to track pending indexing jobs) is reporting unhealthy. Ingestion is paused; the Worker will not accept new jobs.

This is distinct from `queue-db.check-failed`, which reports a soft failure of the most recent integrity probe and does not block ingestion.

## Symptoms

- Health view shows the **Queue DB unhealthy** banner.
- `/api/health/events/stream` emits an `AssertedCondition` with `id="queue-db.unhealthy"` and `status=TRUE`.
- `/api/status` shows the Worker as reachable but `queueDbHealthy=false`.
- New ingestion requests are queued in the head but not picked up by the Worker.

## Likely causes (by reason code)

| Reason code | Cause | Urgency |
| --- | --- | --- |
| `IntegrityFailed` | The queue DB's on-disk integrity check failed. | High — durability is at risk. |
| `DiskFull` | The Worker cannot grow the queue DB because the disk is full. | High — block on free space. |

## Diagnostics

1. Check disk free space on the volume hosting the Worker data directory:

   ```powershell
   Get-PSDrive C
   ```

2. Look at the queue DB file size and last-modified time:

   ```powershell
   $dataDir = Join-Path $env:LOCALAPPDATA 'JustSearch\worker'
   Get-ChildItem $dataDir -Recurse -Include *.db, *.db-wal, *.db-shm | Select-Object FullName, Length, LastWriteTime
   ```

3. Read the most recent integrity-check failure in the Engine log — since lane F stage A there is no separate `worker.log`; the index half logs into the one Engine log:

   ```powershell
   Get-Content (Join-Path $env:LOCALAPPDATA 'JustSearch\logs\engine.log') -Tail 500 |
     Select-String -Pattern 'queue-db|integrity|sqlite'
   ```

## Remediation

- **`DiskFull`** — free space on the data volume; the condition will clear automatically once the Worker can grow the DB again.
- **`IntegrityFailed`**:
  1. Stop the Worker.
  2. Back up the queue DB files (do not delete — the audit trail is useful even if the DB is unrecoverable).
  3. If the DB is recoverable, the Worker may self-repair on the next start. If it isn't, the queue DB can be reset; pending unindexed work will need to be re-submitted.
- **Unknown cause** — preserve `engine.log` for diagnosis before any destructive action.

## Related

- `queue-db.check-failed` — soft integrity-check failure that does not pause ingestion.
- `index.unavailable` — separate condition; if both are asserted, address `queue-db.unhealthy` first since it blocks recovery.
- Architecture: `docs/explanation/01-system-overview.md` (Worker queue DB role).
