# C2-4 integrated verification and journal factory correction

2026-09-13. Full1180 uses source identical to73b100dec (launched onfa2c2e560 plus
the watchdog test committed unchanged as73b100dec). The command is `./gradlew.bat
build pmdAll :modules:ui:compileIntegrationTestJava --continue -PtestParallelism=1
--max-workers=4 --console=plain`. It finishes FAILED in22m39:10342 represented tests,
8286 executed/failed-task cases,2056 reused; one failure, zero errors and35 inherited
skips. All other test tasks, compilation, PMD and UI integration compilation pass.

The one failure is UnreferencedCodeTest: the package-private three-argument
ActionEventJournal.at factory has only test callers. The force checkpoint created
an unnecessary parallel factory route. Consolidate normal and disabled construction
through that existing durability-injection factory and retire the redundant
two-argument constructor. The same production forceFile barrier is supplied; no
test, rule, exemption or warning is weakened. Full1180 is the actual negative proof
that the architecture gate detects the disconnected path.

Focused1182 executes64 passing cases in21 suites, zero failures/errors/skips,
including UnreferencedCodeTest, OperationStoreArchitectureTest, journal durability,
rotated retry, retention and registry behavior. PMD/format/integration compilation
also pass. This repairs the specific integrated finding; it does not turn the earlier
failed full run into a successful one. A new coherent full boundary remains owed.

The UI watchdog correction separately has independent read-only review clear and
typecheck1181 passing, following all6511 UI cases in1179. No production streaming
change is included in that correction. Existing JVM CDS/Unsafe and LightGBM test
fixture advisories remain visible in raw output.

[Commands, counts, source and artifact hashes](integrated1180-verification.json)
point to the retained raw log, portable XML zip and targeted XML under lane tmp.
Retain until lane acceptance plus30 days; export before worktree release. Read-only
thread dumps explain the long quiet interval: the Engine worker progresses from
search/write to directory synchronization, and its full test task ultimately passes.
Hosted snapshot1180 still reports CI34757709842 pending and CLA34757708793 queued
at73b100dec. No hosted/installed-v5 success or lane closure is claimed.
