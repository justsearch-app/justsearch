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

## Remaining proof and scope

Full integrated Java/static/stress/installDist and installed live checks are pending.
No complete D1-3 or later-stage acceptance is claimed from these focused results.
D1-4 dispatch/atomic publishing, D1-12's fourteen fingerprint/model-binding gaps,
and all remaining D1/D2/E/F work remain required.
