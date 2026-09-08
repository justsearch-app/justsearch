# B17 recovery proofs — 2026-09-08

Implementation checkpoint after B15 `cb760f409`; stage-end verification remains pending.

The installed-process JUnit entry point is now `EngineSupervisedRecoveryE2ETest`.
Its five scenarios cover fatal writer/PENDING recovery, migration start/promotion/rollback,
hostile locks before boot and during ingestion, and durable PROCESSING replay. The lock
workload moved from the embedded Engine stress class without relaxing its thread counts,
lock holds, 100-document corpus, accepted-work assertion or 180-second search bound.
An acquisition counter excludes a no-op attack. The intruder releases its handles before
the owned dev-runner cleanup; the old embedded test and stress-policy entry are removed.

The PROCESSING scenario waits for a real queue claim and entry into the existing chaos
extraction child, verifies PID/creation time/full command with the repository identity
helper, and deliberately crashes only that fixture's Engine. The host's test-only cooldown
allows a fresh read-only SQLite observation of PROCESSING after actual death and before
the successor starts. Switching the owned test parser argfile to the real parser lets
startup recovery, DONE and an exact path/content search hit prove replay. The first
attempt stopped before crashing because the test compared a canonical lowercase Windows
path case-sensitively; the corrected five-case run passed in 1m47s. This identity collector
proof is Windows-only; the wrapper declares that limitation rather than bypassing it.

The Tauri conformance actuator now owns no parallel Child or cached port. It uses
EngineHost admission, manifest/current-binding validation, reap, kill, state publication
and local handoff methods; every running publication asserts matching owned/manifest/state
PID and instance. Existing 17 cases all pass through that binding. Both independent
read-only reviews found no remaining blockers. AppHandle setup and graphical event sinks
remain source-reviewed, and signed installer/user-store proof remains in stage E.

At this checkpoint: installed cases 5/5; Tauri adapter 17/17; Rust library 77/77;
build excluding tests passed (325 tasks, 12s); script lint, UI gates 27/27, runtime
closure (3420 files/0), recoverability and readiness/stress policy checks passed.
Producing the license inputs also found thirteen stale notices for removed gRPC
runtime dependencies; regenerated THIRD_PARTY_NOTICES removes that Stage A residue.
The unfiltered eight-set `regen-all --check` now passes.

Raw logs remain in ignored `tmp/`; the final stage record will inventory the completed
candidate, including the strengthened before-successor PROCESSING assertion, full unit
and stress run, main integration and final installed-process rerun.
