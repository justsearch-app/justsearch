# C2 hosted verification

September12: [CI34702144423](https://github.com/justsearch-app/justsearch/actions/runs/34702144423)
at3b425cebb (guard cleanup) and
[CI34703161196](https://github.com/justsearch-app/justsearch/actions/runs/34703161196)
at6b1d6db4f (strict backlog reads) finish successfully. Exact run metadata is retained
in tmp/c2-hosted-{34702144423,34703161196}-status.json. These are overall workflow
results; this note does not infer per-test execution or replace the detailed artifact
audit below. Direct VDU commit/recovery corrections are later source and need fresh CI.

Current projection/orphan proof: [checkpoint656](projection-checkpoint-656.md).
CI34700660160 at758aeb2eb has a clean first-attempt orphan case and unskipped queue/
snapshot tests; its sole red job is a documentation encoding error fixed0bdf584bc.
The older producer and orphan records below preserve their original tested limits.

September12: CI34695294553 at3be553d89 passes12 jobs but Build (no model blobs)
fails in the all-source-set static-analysis step: ui compileIntegrationTestJava,
SchemaMismatchStatusContractTest.java:107 still calls EngineRoot without the now
required OperationAttemptRunner. The focused unit compilation missed this source
set. CI34696855256 at4e800f61d repeats that exact failure in completed build job
103561729483; the overall run was still active when inspected. These are required
red checks, not PMD violations or waived platform limits.

Correction supplies the explicit mocked runner alongside the existing mocked store
in this schema-status fixture, whose property is index mismatch reporting and whose
production bootstrap dependencies are required even though the test does not perform
recorded mutations. No constructor fallback or test suppression is added.

Sources: [earlier run](https://github.com/justsearch-app/justsearch/actions/runs/34695294553),
[current build](https://github.com/justsearch-app/justsearch/actions/runs/34696855256/job/103561729483).
Raw logs: tmp/c2-hosted-34695294553-failed.txt and
 tmp/c2-hosted-34696855256-build.txt. The attempt to retrieve the overall active run
log is retained in tmp/c2-hosted-34696855256-failed.txt (not a failure log).
Retain with the lane's evidence inventory through acceptance plus30 days.


Local633 passes :modules:ui:compileIntegrationTestJava and :modules:ui:pmdIntegrationTest
in10s. Log: tmp/c2-2-hosted-fixture-633.txt. Input is the fixture correction atop4e800f61d
plus the independently verified pending pack completion item; that item does not change
EngineRoot's constructor contract. Fresh hosted success remains required after push.


## September 12 producer checkpoint 70e9e577c

[CI 34697406424](https://github.com/justsearch-app/justsearch/actions/runs/34697406424)
finishes with all 13 jobs successful, including Build, Windows-native and system
integration. The old missing-runner constructor failure is resolved. New hosted
unit XML includes OperationAdmissionLifetimeTest (5), AiPackOperationCompletionTest
(4), AiInstallOperationCompletionTest (5), RuntimeActivationCompletionTest (5),
NonDispatchedMutationTest (4), RequestEngineContextTest (6), and
EngineAdmissionTransportTest (9), all without failures/errors/skips. This includes
the C1 MCP identity correction and asynchronous quota correction on the hosted path.

**Retry audit remains actionable.** The system integration artifact preserves 90
case entries in 21 suites, 42 skips and one failed attempt. ExtractionSandboxOrphanE2ETest
first fails at line 126 (the parser PID must not be in the pre-root descendant set),
then its retry witnesses the wedged parser and native child, kills the Engine and
observes both descendants die. The integration job succeeds under the existing retry
policy. Do not describe this as a clean first-attempt orphan proof. The first failure
is under source investigation before changing its identity witness; no validation is
waived. The successful IndexingLedgerCoherenceTest is unskipped.

The app-ui artifact totals 5,228 cases/807 suites, 4 skips, no failures/errors;
search-worker totals 3,264/583, 43 skips, no failures/errors. These are artifact totals,
not claims that cached unchanged tests executed again. Full job metadata and task logs
remain the execution authority. Local retained copies:

- tmp/c2-hosted-34697406424.json and -artifacts.jsonl (ids, sizes, expiry).
- tmp/c2-hosted-34697406424-artifacts/ (app-ui, search-worker and integration payloads).
- tmp/c2-hosted-34697406424-counts.json (raw totals and focused suite paths).
- tmp/c2-hosted-34697406424-integration.txt (failed attempt, successful retry and job result).

Hosted artifact expiry is December 11, 2026; the local copies follow the lane's
acceptance-plus-30-day retention and must be exported before worktree deletion.


## Orphan witness correction during C2

September12 source review establishes a valid persistent-pool reuse path. The separate
boot probe is closed before the production pool is constructed; it is not reused.
KnowledgeServerBootstrap publishes READY before ready initialization, which can submit
bundled help documents, and PersistentExtractionSandbox deliberately reuses a live child
within its request budget. Thus a valid production parser can predate the test's root-add
snapshot. The original hosted artifact did not log identities, so its exact first-failure
cause remains unknown; this is a proven invalid assertion, not a claimed historical PID
reuse diagnosis.

The test now explicitly prewarms the one-member production pool with a harmless request,
then requires the hanging native-descendant request to reuse that exact instance. Fixture
witnesses atomically publish PID, process start instant and normalized executable at
creation/response; the exact target witness, live direct Engine/parser/native parent chain,
current private EXTRACTION manifest registration and Engine descendant membership all
must match before forced Engine death. Exit observations compare full identities; unknown
live identity cannot prove death. Failure cleanup waits boundedly for both owned children,
retains primary failures, and preserves backend logs. No production parser behavior or
containment requirement was relaxed.

Local Windows/Java25 proof:

-650 passes first attempt in25s: Engine23364, reused parser35816, native11184; both
reaped519ms. -652 passes in13s after the observation-race correction, reaped312ms.
-653 is the final identity-publication/cleanup version: first attempt17s, Engine31408,
parser31200, native37676; both reaped522ms. Each has1 test and zero failures/errors/skips.
-651 and final-654 temporarily restore the old freshness assertion and fail exactly on
it, because the deliberately reused parser exists in the pre-request list. Final-654
preserves that primary assertion with no suppressed cleanup failure; bounded cleanup
runs before fixture teardown. -655 restores the exact -653 code and reuses its test
result FROM-CACHE; it is not another live execution. Compile and test-fixture/integration
PMD pass. Independent source review and independent re-reading of -650/-653 pass after
both review findings were fixed.

Evidence: tmp/c2-2-orphan-reuse-{650,652,653,655}.txt, matching -xml/ and -counts.json;
tmp/c2-2-orphan-reuse-negative-{651,654}.txt, matching -xml/ and -logs/ (backend/engine
failure logs copied out before later runs). Retain through lane acceptance plus30 days.
Corrected hosted first-attempt proof and integrated/named stress checks are still owed
at this checkpoint; the earlier hosted retry is not retroactively converted to clean proof.
