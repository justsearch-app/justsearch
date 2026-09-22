# D1-3 applied revision verification

## Implemented boundary

AppliedIndexGeneration reuses IndexTargetSnapshot's digest/byte contract and retains
the generation ID. IndexGenerationManager shares its strict authoritative read
between idle mutation checks and a serving observation that permits a Green build.
WorkerIngestService retains its fixed search runtime and reads one committed map,
validates size/hash before parsing strict object JSON, and checks the active path
before/after. Missing initial authority is UNAVAILABLE; observed movement is ABORTED.
No desired metadata source participates.

EngineKnowledgeClient uses the existing bounded call and verifies the published
WorkerAppServices identity after capture. EngineRoot verifies both its client identity
and registry revision, then hashes only four applied component versions, generation
ID and recursively canonical committed inputs. No new persisted revision or epoch.
All four applied versions must be established. HeadAssembly now publishes explicit
intentional generative absence using the same captured lite gate as its existence
decision; unapplied manager resource values are null. Enabled factory failure leaves
versions unset rather than certifying failure as absence.

## Focused evidence

All runs below use4bec3f00b plus the bounded source changes. Logs, XML/counts and the
2418 source inventory are retained under the named tmp prefixes.

- 2411 compile caught redundant self-assignment in the record compact constructor;
  replaced with the same null check without assignment. No validation removed.
- 2412 executes21 cases:17 record/manager cases pass, four service cases fail because
  Mockito cannot mock sealed LuceneRuntime. The fixture now mocks the existing
  concrete RunningRuntime, matching nearby tests;2413 passes all four service cases.
- 2414 real-runtime fixture initially referenced a test helper unavailable to this
  module. It now uses the existing ResolvedConfigBuilder without a dependency change.
  2415 catches the fixture's missing required document UID; that field is supplied.
  2416 passes five service cases, including two real physical generation commits,
  same target/different IDs, and no change from an uncommitted metadata producer update.
- 2417 passes33 HeadAssembly/InferenceDecision cases across six suites: actual disabled
  composition publishes the expected digest; actual enabled factory failure leaves
  versions unset; all eight gate vectors and retained-lite sampling are checked.
- 2418 passes47 cases/18 suites across app-engine and app-launcher, with Spotless/PMD:
  nine digest/composed-client/root cases plus38 unreferenced-code cases. Zero failures,
  errors or skips. Both independent roots and actual bounded-call race paths are tested.
  The app-engine service fixtures are mocks; real Lucene readback is separately2416.

Independent production review found the enabled-factory-null ambiguity, which was
fixed before2417/2418. Its bounded rereview is clear and confirms the real-commit
fixture closes the former mock-only gap. Review source evidence is retained in
tmp/2403-overall-revision-design.md; it is not execution evidence.

## Negative controls

tmp/2419-applied-fence-negative-proof.py removes one production fence at a time,
runs its exact regression, captures XML, and restores original bytes in finally.
2419 disables the service-owner refusal;2420 removes only the registry fence;
2421 removes only the root-client fence. Each test fails with exactly one missing
expected exception (the unsafe observation was accepted), not a compiler/fixture error.
Exact EngineRoot and EngineKnowledgeClient bytes are restored after the series.

## Integrated and installed evidence

At23cc8929a837277b76773a2f00490b8b36e1a312,2422 runs
`./gradlew.bat spotlessCheck pmdAll test -PincludeStress=true :modules:ui:installDist --continue --console=plain`.
It passes in10m31s:11523 cases, zero failures/errors,31 recorded skips,
1802 suites across34 test tasks. Eighteen test tasks reuse unchanged results;
the captured counts distinguish those from fresh execution. Full log, XML,
counts, skips and15-file source inventory are retained at
`tmp/2422-applied-revision-integrated*`. The whole-log failure index is empty.

Installed2423 uses captured LLM_ENABLED=false: schema2 reports API/index/encoders
READY and generative ABSENT. Run62b70f4f-a8da-42b9-bee2-9530a73c760c uses API57484;
official stop confirms portsClosed:true. Evidence: `tmp/2423-disabled-*.json`.

Installed2424 uses the standard model profile and online intent: all four
components READY, runtime-client contract0.4.0 live smoke passes. Real-model jseval
executes one query with zero errors, exact/intersection/context accuracy100%,
retrieve1974ms, LLM2950ms and65 completion tokens. This is plumbing/regression
proof, not broad retrieval-quality evidence. Run c82d8056-8c3d-430b-9723-5f9f40053565
uses API50685; official stop confirms portsClosed:true. Evidence:
`tmp/2424-normal-*.json`, `tmp/2424-online-intent.json`,
`tmp/2424-runtime-smoke.txt`, `tmp/2424-model-query.txt` and
`tmp/2424-model-query/tier2-eval.json`.

## Remaining scope

These checks establish the implemented applied-revision boundary, not later-stage acceptance.
D1-4 dispatch/atomic publishing, D1-12's fourteen fingerprint/model-binding gaps,
and all remaining D1/D2/E/F work remain required.
