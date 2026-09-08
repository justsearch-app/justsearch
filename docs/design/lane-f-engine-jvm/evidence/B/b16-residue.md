# B16 residue sweep (2026-09-08)

Source sweep above `a92a56174`; full-suite acceptance remains pending combined
integration with B11–B12. Independent read-only reviewer `review_b14_java` found
no source blockers and verified the evidence inventory. The removed
`KnowledgeServer.isRunning()` method had no
production caller. Its three state-only unit tests retire with it; boot tests now
assert the existing service surface, preserving their migration, corruption and
read-only-serving assertions. The real `awaitClosed` consumer in
`EngineRoot.java:223` retains the shutdown-completion latch and its existing tests.

`SupervisionContractTest` moves within app-services from `worker` to `supervision`;
all six checks still run. The retired register boundary loses its phantom method
name. The four named config-snapshot surfaces already contain only labelled
retirement history: the runtime allowlist entry, producer and reader were removed
in A19. No live entry is retained or newly exempted.

The focused run passed 51 tests (indexer-worker 43, app-services 6, dead-code-audit
2), with zero failures/errors/skips, plus affected PMD and formatting in 41 seconds.
The whole-program rule counts classes; its baseline is unchanged, with no growth.
Runtime-manifest closure scanned 3,408 files with zero violations. Final XML was
copied before subsequent filtered runs. No full Java suite is claimed here.

Raw evidence remains ignored; SHA-256 inventory:

| artifact | SHA-256 |
|---|---|
| `tmp/b16-focused.txt` | `e309d54fc84b1e46a47a2d37fc01b27c3873b355644fa38924fad8acbf9030a8` |
| `tmp/b16-focused-snapshot/summary.json` | `73c7d6ef4ac5b452acf525f2b37e75bc598773d81a1c7f86dbaecd53daf94257` |
| `tmp/b16-runtime-closure.txt` | `4a62b8c30964bd3cbe9aebf4e5190f268e8d4c66194ef29ec9fc55276e2fcefc` |
