# C2-4 keyed outcome query — 2026-09-13

Implemented at eac25abb3 plus the source inventory in
[keyed-outcome-query-verification.json](keyed-outcome-query-verification.json).
This is the keyed read cut; C2-4 remains open. The recent-history deque, durable
completion projection/replay and reconnect boundary are the next owning cuts.

## Behavior and evidence

- `OperationOutcomeQueryTest.sixAnswersAgreeWithOperationsAndActualJobsAcrossRestart`
  checks accepted/running/complete/failed/unknown/expired against the real operations
  store and real jobs store. One done and two failed jobs match persisted counts;
  reopen preserves those answers. An evicted row answers expired, while a retained
  row below the advanced fence still wins. Negative1077 changes the row branch to
  require keyTime >= fence and fails exactly that assertion (failed vs expired).
- The other two query cases cover cancellation, awaiting-acceptance typed gaps,
  missing far-future and malformed keys, executionId refusal and no acceptance on
  reads. The gap fixture populates the reserved column; it does not claim D1's
  producer exists. D1 section1 I14 inherits the typed projection contract.
- `OperationOutcomeTransportTest` compares serialized HTTP/MCP completed receipts,
  checks private content exclusion and no dispatcher interactions, preserves the
  nonempty recent-history GET, and verifies matching typed invalid-key failures.
- `OperationHistorySchemaTest` pins the new lowercase state enum to actual Jackson
  serialization. The new schema is mirrored to UI resources and served by the
  existing allowlist. Route contract tests pin its declared response schema.
- Final1079:78 represented cases in12 suites,0 failures/errors/skips. UI executes71;
  engine3 and observability4 reuse successful unchanged1078 inputs. PMD for affected
  sources, integration-test compilation, schema sync and installDist pass.1078's
  only failure was the old17-schema inventory; the test now explicitly requires
  the new schema and18 entries.1068's old optional-key assumption was corrected to
  require this read tool's key while preserving optional mutation transport keys;
  1071 then passed65 cases.1075 captured the wrong uppercase enum baseline;1076
  captured the corrected baseline with the new serialization-parity test passing.
- Live1089, owned run `032948be-2371-44d7-9333-37e1be2b8e72`: health200; discovered
  read-only seventh tool; identical HTTP/MCP unknown response; matching typed
  invalid-key errors; schema200 with lowercase enum; hostile Host403 and MCP
  Origin403. The official dev stop reports portsClosed:true. This is installed
  current-dist API plumbing proof, not an installed recovery/model quality tier.
- Live route capture1089 regenerates246 routes, OpenAPI and apiRoutes.ts from the
  same digest. Besides the new query it catches prior missing chat-approval and
  eval document-ID routes (`AgentRoutes.java:41`, `PreviewController.java:46`) and
  the manifest schemaVersion.1090 verifies the OpenAPI derivative and affected
  formatting;1091 frontend typecheck passes.1092 docs, seven hermetic generated
  sets and both operation-surface/register-guard-resolution gates pass.

## Refute-first review and proof limits

The read selects projection columns plus metadata in one SQLite statement; it
cannot combine a pre-prune row miss with a post-prune fence. Present-row precedence
is independently challenged by the negative control. No dispatcher/admission or
sealed preparation is involved. The store and jobs assertions establish the
counts' origin; a DTO-only expected-value test would not. The real route/schema
capture and live trust-boundary requests establish wiring outside this plan.
No substantive objection survives this keyed-read review. The batch's independent
review remains required after the durable history/completion/SSE cuts.

Intermediate live1082 failed because the listener disappeared after the one-shot
stdio client exited; health1083 confirmed dead supervisor/backend and no API
listener. The campaign now retains the client until its owned stop; no product
lifecycle conclusion is inferred.1085 incorrectly parsed human-readable MCP error
text as JSON,1086 used singular /api/schema, and1088 used fetch's Host override
which did not exercise the intended header. The final raw HTTP request verifies
Host rejection. These failures remain evidence; only1089 is the accepted live run.

Hosted queue allocation remains external and unproved. Nine superseded CLA cancel
requests were accepted and seven returned GitHub500/502;1070 records each result.
Do not infer all were cancelled, a global GitHub outage, or a fresh hosted pass.
The latest full local boundary is full1059 at13b6bd680, predating this focused cut.

The verification JSON contains the exact final Gradle command, task reuse and
source/raw SHA-256 inventories. Raw artifacts are in this worktree's tmp directory,
including final/negative XML and live probes/campaign records. Retain through lane
acceptance plus30days and export before releasing the worktree. Full1059's required
hosted boundary and C2-4's remaining acceptance are not waived by this commit.

## Launcher fixture correction after the query cut

1095 proves a compile omission at9a72be964: the deliberately unauthorized
OperationStore implementation in OperationStoreArchitectureTest lacked the new
outcome method. Implement that method as unsupported, retaining the architecture
negative fixture.1096 passes35 represented cases,0 failures/errors and launcher test PMD.
The launcher test source set now belongs in every store-port change's focused
verification set. [Exact proof](keyed-outcome-launcher-verification.json). This
corrects the missing consumer compilation; it does not expand the earlier78-case
claim into a full integrated pass.
