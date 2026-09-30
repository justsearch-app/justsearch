---
classification: declared-growth
tempdoc: 936
---

Declares ONE new `sysaccess-allowlist` entry,
`io.justsearch.indexerworker.services.WorkerHealthService#parseLongEnv`, and it is a **rename, not
a new system-access site**. Lane F stage A item A3 converted the worker services off gRPC in place;
`GrpcHealthService` became `WorkerHealthService` because the class no longer implements a generated
`ImplBase` and naming it after the transport had stopped being true. The method, its body and the
one environment variable it reads (`JUSTSEARCH_WORKER_HEALTH_SYNTHETIC_DELAY_MS`, a test-only
synthetic-delay hook, default 0/off, 374 alpha.25 R13-A) are byte-identical.

The gate is right to stop here anyway. It compares FQCN sets, and an FQCN set cannot tell a rename
from a new call site — that is the whole reason the ratchet exists, because "it's just a rename" is
exactly what a genuinely new `System.getenv` would also claim. So it is declared rather than
silently absorbed, and the claim is checkable: `git show 1a72bbf91^` (and `main`) carry
`io.justsearch.indexerworker.services.GrpcHealthService#parseLongEnv` at the same position in this
file, and the rename commit is `5ac222b6b`.

## Net direction on this branch

The list **shrinks by five**. Against `origin/main` this branch removes six entries and adds this
one:

| entry | direction | why |
| :--- | :--- | :--- |
| `indexerworker.services.GrpcHealthService#parseLongEnv` | removed | renamed to the row below (A3) |
| `indexerworker.services.WorkerHealthService#parseLongEnv` | **added** | the rename's landing site |
| `app.services.worker.WorkerSpawner#buildCommand` | removed | item A11 deleted the spawner |
| `app.services.worker.WorkerSpawner#isWindows` | removed | as above |
| `app.services.worker.WorkerSpawner#spawnWorker` | removed | as above |
| `app.services.worker.WorkerSpawner#workerJavaBinary` | removed | as above |
| `app.util.WindowsJobObject#isWindows` | removed | item A11 deleted the class (its only caller was the spawner) |

Five of those six are the point of the lane: building a child process's command line and choosing
its Java binary are direct `System.getProperty`/`getenv` reads that exist *because* there is a
second process to launch. There is not one. The ratchet's direction and this lane's direction are
the same direction, and stage A is expected to keep removing entries from this file rather than
adding them.

No baseline metric moves for this entry — `sysaccess-allowlist.txt` is a set, not a counted metric
in `gates/config-surface/baseline.txt`. The counted metrics move down in the same branch
(`env_sysprop_pairs` 251 → 249, from the two dead dev keys item A11 orphaned and this branch
deleted); that is a shrink and needs no declaration.
