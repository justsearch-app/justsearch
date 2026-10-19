---
title: "Runbook: `index.start-error`"
type: runbook
status: stable
description: "Operator response when the Engine index component fails to start."
---

# Runbook: `index.start-error`

The indexer failed to start. The Engine was unable to bring the index half (the Knowledge Server) to a ready state at all - this is structurally distinct from `index.unavailable`, which is reported once the index half is up but the index is not serving. Since lane F stage A this is an in-process composition failure inside the Engine JVM (`KnowledgeServerBootstrap`), not a child-process spawn failure. The lane F retained `INDEX_CONTROL_PLANE` dimension now reports the index failure with reason `index.failed`.

## Symptoms

- Health view shows the **Indexer failed to start** banner.
- `/api/health/events/stream` emits an `AssertedCondition` with `id="index.start-error"` and `status=TRUE`.
- `/api/status` reports the lane F retained `INDEX_CONTROL_PLANE` dimension in `NOT_READY` with reason `index.failed`.

## Likely causes

- The Engine distribution is missing or stale (e.g., `:modules:ui:installDist` not run since pull), so the index half is not on its classpath.
- The Knowledge Server throws during bootstrap because of a bad config (missing models directory, unreadable index root).
- A native dependency the index half pulls in (ORT) failed to load.
- File permissions prevent the Engine from writing to its data directory.

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


1. Read the embedded error detail in the `AssertedCondition` body - the Engine records the index startup cause there.
2. Read the most recent Knowledge Server bootstrap attempt in the Engine log. Item A13 deleted the Worker's own logback config, so there is no separate `worker.log` file (retired filename); the index half logs into the one Engine log:

   ```powershell
   Get-Content (Join-Path $env:LOCALAPPDATA 'JustSearch\logs\engine.log') -Tail 200
   ```

3. Verify the Engine distribution exists and carries the index half:

   ```powershell
   Test-Path .\modules\ui\build\install\ui\bin\ui.bat
   Get-ChildItem .\modules\ui\build\install\ui\lib -Filter 'indexer-worker-*.jar'
   ```

   Linux distribution checks, from the repository root:

   ```sh
   test -x modules/ui/build/install/ui/bin/ui
   ls modules/ui/build/install/ui/lib/indexer-worker-*.jar
   ```

## Remediation

- **Missing distribution** - run `node scripts/lib/tool-invoker.cjs gradle :modules:ui:installDist`.
- **Models directory missing** - set `JUSTSEARCH_MODELS_DIR` to a populated directory (default is the repo's `models/`).
- **Native dep failure** - check the loaded library list in the Engine bootstrap log; common causes are CUDA driver mismatch or a stale ORT cache.

## Related

- `index.unavailable` - once the index half is up but unhealthy (see [`index-unavailable.md`](index-unavailable.md)).
- Engine lifecycle: `docs/explanation/01-system-overview.md`.
- Stale-distribution pitfall: `installDist` is now wired into `assemble`, so a fresh `node scripts/lib/tool-invoker.cjs gradle build` produces a runnable Engine.
