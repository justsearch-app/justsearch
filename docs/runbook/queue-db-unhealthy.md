---
title: "Runbook: `queue-db.unhealthy`"
type: runbook
status: stable
description: "Operator response when the Worker queue database reports unresolved recorded database-operation failures."
---

# Runbook: `queue-db.unhealthy`

The Worker queue database (the durable inbox the Worker uses to track pending indexing jobs) has unresolved recorded operation failures. This is diagnostic evidence; the health field does not itself pause ingestion or control admission, deletion or recovery. A failing database operation can still prevent the work that requires it.

This is distinct from `queue-db.check-failed`, which reports failure of the most recent integrity probe. Only a later complete passing quick-check recovers integrity evidence.

## Symptoms

- Health view shows the **Queue DB unhealthy** banner.
- `/api/health/events/stream` emits an `AssertedCondition` with `id="queue-db.unhealthy"` and `status=TRUE`.
- A fresh `/api/status` sample shows grouped `queueHealth.healthy=false` for a reachable Worker.
- Logs identify the failing queue operation; the aggregate health field does not expose individual unresolved operation kinds.

## Likely causes

| Evidence | Cause | Urgency |
| --- | --- | --- |
| Failed integrity probe | The queue DB's on-disk integrity check failed. | High — durability is at risk. |
| SQLite disk-full error | The Worker cannot grow the queue DB because the disk is full. | High — block on free space. |

## Diagnostics

The PowerShell examples below apply to Windows. On Linux, take the actual data
folder from the runtime manifest (`dataDir`); the desktop shell uses its platform
app-data folder under `XDG_DATA_HOME`, while headless launches can select a data
folder explicitly. With that path assigned to `data_dir` in Bash:

```sh
df -h "$data_dir"
find "$data_dir" -type f \( -name '*.db' -o -name '*.db-wal' -o -name '*.db-shm' \) -ls
tail -n 500 "$data_dir/logs/engine.log"
```


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

- **Disk full** — free space on the data volume. A later complete successful execution of each previously failing operation kind can recover its diagnostic failure. Fixing a dependency does not execute an inactive operation or promise a retry schedule.
- **Integrity probe failed**:
  1. Stop the Worker.
  2. Back up the queue DB files (do not delete — the audit trail is useful even if the DB is unrecoverable).
  3. Diagnose and restore the database using preserved files and logs. Opening runs the real integrity check; a passing quick-check clears integrity failure only, and cannot certify an unrelated failed query.
- **Unknown cause** — preserve `engine.log` for diagnosis before any destructive action.

Health history is local to one queue object. Reopening that object retains unresolved nonmatching operation history; creating a new object does not persist it. Successful recovery preserves the historical last-error timestamp. Conditions clear after a fresh healthy sample; stale or unreachable views cannot prove recovery.

## Related

- `queue-db.check-failed` — result of the latest integrity check.
- `index.unavailable` — a separate condition; diagnose its evidence independently.
- Architecture: `docs/explanation/01-system-overview.md` (Worker queue DB role).
