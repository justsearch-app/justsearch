# C2-2 authoritative generation capture

September12, base c4fbcb53f plus this bounded generation/availability change.
The Java indexing port now captures the serving generation from one strict state.json
observation. IndexGenerationManager returns the active identifier from the same parsed
state that establishes IDLE, absent building generation, existing directory and exact
captured runtime target. WorkerIngestService requires the serving runtime, manager and
target; missing, malformed, migrating or stale authority returns UNAVAILABLE. Capture
does not consult the status cache, recover missing state, rewrite authority or mutate
the index. Cancellation is checked before and after observation.

Independent review found the real service-replacement interval: EngineRoot supplies
started::appServices, and KnowledgeServer publishes null between incumbent and successor.
EngineKnowledgeClient now resolves once per call and maps absent composition or selected
service to typed UNAVAILABLE across search, ingest, health, scan and subscription.
This uses the existing supplier and error vocabulary; it adds no cache or lifecycle state.
Unrelated selector/body bugs retain their original exception behavior. Correction review
found no remaining substantive defect in this bounded change.

## Verification

Windows/Java25 focused768 passes55 cases/9 suites, zero failures/errors/skips and affected
PMD. app-engine executes27 cases; app-api's21 reuse unchanged passing767 inputs and
worker-core's7 reuse765. The app-api cases cover the concurrent immutable root-plan item,
which is committed separately. The Engine fixture uses a real generation manager and
SQLite queue with mocked runtime operations; it is not a live ingestion proof.

```text
gradlew.bat :modules:app-api:test --tests *RecordedRootPlanTest
  --tests *OperationDescriptorPreparationTest
  :modules:worker-core:test --tests *IndexGenerationVduEligibilityTest
  :modules:app-engine:test --tests *EngineGenerationCaptureTest
  --tests *EngineContextPortPropagationTest --tests *EngineWorkCancellationTest
  --tests *EngineScanRootFlowTest --tests *EngineIndexingJobsFlowTest
  :modules:app-api:pmdMain :modules:app-api:pmdTest
  :modules:worker-core:pmdMain :modules:worker-core:pmdTest
  :modules:worker-services:pmdMain :modules:app-engine:pmdMain :modules:app-engine:pmdTest
  -PtestParallelism=1 --max-workers=4 --console=plain
```

764 fails compilation in concurrent app-api work before generation tests. 765 and767
each run55 cases with one new fixture failure: context reference equality incorrectly
assumed admission did not construct a fresh immutable view. The corrected fixture admits
the caller first and compares all context values, including its owned work ID. 765 also
flags an unused resource variable, corrected without suppression. Negative769 replaces
requireService with the old dereference behavior: three intended NPE/status failures
in12 cases. Exact production source bytes are restored afterward. Its command is
the app-engine task above restricted to EngineGenerationCaptureTest, without PMD.

Artifacts: worktree tmp/c2-2-generation-root-{765,767,768} and
tmp/c2-2-generation-negative-769, with .txt, -counts.json and -xml/ suffixes.
764 has tmp/c2-2-generation-capture-764.txt. Retain through lane acceptance plus30 days
and export before releasing this worktree.

Capture is an observation, not D1's transition lease. The recorded root producer,
per-batch admission/covering-commit revalidation, child identity, committed ingestion
and recovery remain owed in recorded-root-preparation.md. Full repository, hosted and
live proof of that integration remains required; C2-2 is open.
