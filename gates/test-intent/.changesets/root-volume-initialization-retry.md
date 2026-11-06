---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: 01a1b1c2-8540-7993-931e-3d0713b59388
---

# Retry failed root-volume initialization in the original Engine

Retry failed declared-root initialization and configured-root enrollment after the write
obstruction is released, including across producer replacement in the same Engine. The
original accepted operation can then recover without resubmission or restart.

Initialization state belongs to the Engine and passes through existing producer ownership.
Only the selected physical watcher owner may advance it. The carrier retains original boot
inputs and unfinished enrollment/completion state, with no runtime/service reference, new
persisted format, scheduler or registry. Current membership and configuration filter retries;
prepared or discarded candidates cannot consume the incumbent's work. Later declarations do
not acquire original boot-seeding authority, and bound-empty inputs remain distinct from
unbound inputs. Removal cannot resurrect a root; independent children, siblings and explicit
file-folder authority remain intact.

Upgrade evidence samples up to 50 indexed files and requires at least half to match both size
and modification time. An empty index is eligible; unreadable evidence is not. Established
conflicting authority is retained. Completed authority is not rewritten, and
rootOnRecordedVolume remains read-only. UUID-less completion stays completed within the same
Engine; a new Engine retains the existing configured-root reenrollment exception.

The first two entries retain stock-generated execution evidence for the exact named checks
against the partial `a2dcdd8593` baseline. Service checks use real ledger write paths with injected volume
identity and mocked index samples. Producer checks exercise constructor and ownership
interfaces with mocked runtimes. Neither establishes running-Engine or Windows lock recovery.
The Windows boundary harness remains New/repaired. Hosted run `42259081025` produced stock
Windows before/after evidence; independent raw-artifact admission and current acceptance are
in progress. The full hosted Windows tier remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/root-volume-initialization-retry.json",
      "behaviour": "Real directories at the pending-ledger or ledger temporary write path prevent atomic persistence. Existing refusal boundaries must retry authorized declared-root initialization and configured-root enrollment after release, including a configured root outside watched roots. Repeated failures grant no identity. Partial publication, creation of unrelated explicit-file authority during failed initialization, same-owner retry, restart, sibling progress, removal overlapping evidence, configuration replacement, unreadable indexed evidence, empty and half-matching size/mtime samples, conflicting established authority, explicit-file-folder authority, unchanged completed authority timestamps and UUID-less limits remain covered. rootOnRecordedVolume remains read-only. These are service/ledger checks with injected volume lookup and mocked serving-index samples, not real-Engine boundary proof. Same-Engine root handover retains the original captured inputs, current membership/configuration, unfinished enrollment, evidence checks, explicit-file authority and pending cleanup; removal overlapping evidence and handover cannot resurrect the removed root.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Block the real filesystem write target with a directory; keep existing request interfaces for fail-before, use deterministic indexed stamps and controlled volume lookup for policy cases, and hold an evidence callback to overlap removal.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java"
      ],
      "class": "New",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/root-volume-initialization-handoff.json",
      "behaviour": "Unfinished declared-root initialization and configured-only enrollment survive the existing selected deferred-writer, runtime-reload, native, serving and query producer handoffs after either pending-publication or ledger-creation failure. Repeated prepared/discarded candidates do not advance or lose the incumbent obligation. Original roots without native watches and unqueried siblings recover; unrelated later declarations do not gain boot-seeding authority. Current membership/configuration filter unfinished inputs; bound-empty and unbound initialization remain distinct. Completed identity-less initialization is not reenrolled or rewritten on same-Engine replacement. These are real constructor/ownership interfaces with mocked runtimes and injected volume identity, not running-Engine or hosted Windows proof.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Share a small Engine-lifetime initialization state through existing producer preparation and ownership facilities; only the selected physical watcher owner may advance it. Exercise the existing deferred writer, stopped-loop reload, native-to-Green, serving and query successor constructor/installation interfaces without before-tree compatibility helpers. Keep successful and UUID-less completion, exact original boot inputs, current membership/configuration and discarded-candidate isolation.",
          "label": "agent choice"
        }
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#unfinishedRootInitializationSurvivesSelectedProducerReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementPreservesBoundEmptyVersusUnboundBootInputs",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment"
      ]
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/RootVolumeInitializationRetryE2ETest.java"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "This handover covers the existing Windows CI repair, reconciliation and cleanup of merged E4c, and independent assessment of the already-built narrow P2b+c close.",
          "delegationLocation": "task t-20261102-redesign user turn 1",
          "delegationScope": "The reported Windows root-volume initialization write failure, recovery of its original accepted operation in the same Engine, and preservation of settled R1-R8 and RP1-RP4 refusal/authority cases only.",
          "choice": "Add a dedicated two-document scenario and JUnit lock holder, retaining existing fixture ownership, cleanup and 180/330/420-second bounds. Observe at least one actually admitted original path PENDING/ROOT_UNAVAILABLE before unlocking: RecordedIngestionCoordinator drives roots serially, so the later child need not exist yet. Require both expected documents at completion. Counterexamples reject missing fault, missing job refusal, restart, failed operation, nonzero failures and partial search.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed",
          "delegationLocation": "task t-20261102-redesign user turn 3, accepting the immediately preceding five-part Codex-only proposal",
          "delegationScope": "Correct Windows test lookup and prove the existing root-volume initialization repair after the real lock; no production change or stronger cleanup/freshness guarantee.",
          "choice": "Observe canonical queue keys using the existing absolute/normalized, Windows-only lowercase contract in SQL and Java proof membership. Model stored canonical keys in private SQLite fixtures and retain original paths, receipt, operation key and raw refusal rows.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Windows-only installed/supervised Engine boundary check for boot pending-ledger IOException under a real FileChannel lock, followed by recovery of the same accepted operation without resubmission or restart. Hosted run 42259081025 exercised the exact repaired method with test/harness-only overlays against original45 and shipping source e7191835f5 through disposable runner 35d08ce01b.",
        "callersAndStateOwners": "RootVolumeInitializationRetryE2ETest invokes EngineSupervisedRecoveryE2ETest.runScenario. JUnit owns the private lock and release acknowledgment; the installed Engine owns jobs.db, root-volume files and the operation history. RecordedIngestionCoordinator drives the two submitted file roots serially, so only the deferred first child may have reached jobs.db before release.",
        "indirectBehaviours": "Require the actual boot write-failure log and absent ledger/pending publication while the lock is held; prove an admitted original path PENDING/ROOT_UNAVAILABLE before release; submit once; correlate the original operation via /api/operation-history/{operationKey} because OperationOutcomeView has no operationKey field; reject Engine identity changes; require complete/SUCCESS, unitsFailed=0, both expected paths searchable and healthy HTTP within the unchanged bound. Existing owner cleanup and port-closure proof remain in force. Both refusal readers compare canonical queue keys while preserving original submitted identities; non-Windows path case remains significant.",
        "evidence": "Untouched stock run-evidence JSON from hosted Windows run 42259081025 records candidate 1 passed case, 0 skipped and no retry; original45 records 3 failed attempts with 0 skipped. Raw fixtures retain actual boot lock IOException, canonical original-child PENDING/ROOT_UNAVAILABLE before release and OS-release acknowledgment on both sides. Candidate completes the original operation SUCCESS with unitsFailed=0, both distinct submitted paths searchable and unchanged runId/pid/instanceId/incarnation. All 3 baseline attempts fail the post-release original-operation/search wait; none fails setup or the repaired path observer. Independent raw-artifact admission remains in progress; this metadata update grants no acceptance.",
        "unresolvedRisks": "Independent final acceptance and the full hosted Windows tier remain pending. This dedicated two-path run does not establish full R/RP/O live coverage. Existing early handle-close exception cleanup and unchanged stale-file freshness limitations remain; portsClosed does not establish complete historical process accounting. Prior failed stock artifact and historical rejection remain in Git history."
      },
      "repaired": true,
      "behaviour": "Windows-only installed/supervised Engine boundary check for boot pending-ledger IOException under a real FileChannel lock, followed by recovery of the same accepted operation without resubmission or restart. Require the actual boot write-failure log and absent ledger/pending publication while the lock is held; prove an admitted original path PENDING/ROOT_UNAVAILABLE before release; submit once; correlate the original operation via /api/operation-history/{operationKey} because OperationOutcomeView has no operationKey field; reject Engine identity changes; require complete/SUCCESS, unitsFailed=0, both expected paths searchable and healthy HTTP within the unchanged bound. Existing owner cleanup and port-closure proof remain in force.",
      "evidence": "gates/test-intent/evidence/root-volume-initialization-windows.json",
      "newChecks": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/RootVolumeInitializationRetryE2ETest.java#pendingLedgerLockRecoversOriginalOperationWithoutRestartOrResubmission"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerProducerOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing reaper scheduler/close-order test's fake DefaultWorkerAppServices now returns a mock ingest service, as a real owner does, so the new root-volume retry call can execute before the unchanged queue callback. No assertion, scheduler delay, interruption protocol, resource owner or close-order check changes."
    },
    {
      "items": [
        "modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Only dedicated scenario registration/dispatch: recognize the new Windows-only root-volume scenario, acquire its exact private lock before starting the existing Node fixture, service its release marker in the existing 100ms owner loop, close the lock in the existing cleanup path, and dispatch its proof assertions. Existing scenario selection, fault inputs, expected outcomes, tags, assumptions, cleanup and bounds remain unchanged. Production root-volume repair is not claimed as evidence that this fixture ran."
    }
  ]
}
```

The existing reaper mutation admission drives retry, including configured roots outside the
Head's watched-root cycles. Shared fixture changes only register and dispatch the dedicated
Windows scenario and retain existing ownership, cleanup and time bounds. Node fixture
selftests check orchestration, not Windows execution.

## Before and after reproduction

Use separate shared clones and build trees under the supplied temporary folder. Copy the same
candidate test source into the `a2dcdd8593` baseline; do not patch production sources or
helper signatures.
Use normal project JVM defaults (currently a 4 GiB Gradle heap) and the standard local
admission wrapper. These are reproduction recipes; they do not claim new runs.

The retained service and producer evidence files use partial candidate `a2dcdd8593` as their before state.
The current full RootVolumeInitializationRetryTest has five later handoff cases that call
retryRootVolumeSeeding. That API exists in `a2dcdd8593`, so the full current class can compile
on both sides. Against this baseline, handoff assertions establish the handoff repair, while
initialization-retry cases check preservation of the primitive retry already present there.
This baseline does not establish the original initialization repair against `45cb5755de`.

Carried independent portable evidence covers the primitive repair against original
`45cb5755de` separately from those partial-baseline stock files. It uses before-compatible
fundamental retry methods and preservation probes; this update claims no new portable run.
For original-baseline reproduction, use the fundamental retry methods from the original
test source committed at `a2dcdd8593`, or probes compatible with both runtimes. Do not
overlay the complete current class on `45cb5755de`: its missing retryRootVolumeSeeding API would cause compilation failure, not behavioral fail-before.
The dedicated hosted Windows run below supplies a separate real-lock original45 comparison;
it does not expand the portable checks into full R/RP/O live coverage.

For full service-class reproduction on the selected partial baseline and candidate:

```bash
candidate="$PWD"
git clone --quiet --shared --no-checkout . "$TMPDIR/before-retry"
git -C "$TMPDIR/before-retry" checkout --quiet --detach a2dcdd8593
service_test=modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java
cp "$service_test" "$TMPDIR/before-retry/$service_test"
cd "$TMPDIR/before-retry"
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  -PskipWebBuild=true \
  :modules:worker-services:test --tests '*RootVolumeInitializationRetryTest' --rerun
cd "$candidate"
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  -PskipWebBuild=true \
  :modules:worker-services:test --tests '*RootVolumeInitializationRetryTest' --rerun
```

Producer handoff baseline (`a2dcdd8593`), followed by the identical four selectors in the
candidate. Parameterized XML names include the method, invocation index and arguments so
each exact evidence selector can identify its invocations.

```bash
git clone --quiet --shared --no-checkout . "$TMPDIR/before-handoff"
git -C "$TMPDIR/before-handoff" checkout --quiet --detach a2dcdd8593
producer_test=modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java
cp "$producer_test" "$TMPDIR/before-handoff/$producer_test"
cd "$TMPDIR/before-handoff"
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  -PskipWebBuild=true :modules:worker-services:test \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.unfinishedRootInitializationSurvivesSelectedProducerReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementPreservesBoundEmptyVersusUnboundBootInputs' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment' \
  --rerun
cd "$candidate"
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew \
  -PskipWebBuild=true :modules:worker-services:test \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.unfinishedRootInitializationSurvivesSelectedProducerReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementPreservesBoundEmptyVersusUnboundBootInputs' \
  --tests '*DefaultWorkerAppServicesProducerTransferTest.replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment' \
  --rerun
```

Generate the two referenced JSON evidence files with the same partial before state and
existing check paths (commands for the orchestrator; not executed here):

```bash
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/root-volume-initialization-retry.json \
  --before a2dcdd8593 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RootVolumeInitializationRetryTest.java
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/root-volume-initialization-handoff.json \
  --before a2dcdd8593 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#unfinishedRootInitializationSurvivesSelectedProducerReplacement \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#uuidlessCompletionIsNotReenrolledByPreparedOrSelectedReplacement \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementPreservesBoundEmptyVersusUnboundBootInputs \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesProducerTransferTest.java#replacementUsesCurrentConfigurationAndMembershipForUnfinishedEnrollment
```

Before evidence must distinguish behavior assertion failures from preservation passes,
compilation/setup failures and platform skips. Only an assertion failure for the intended
behavior establishes fail-before; the full service class contains primitive-retry preservation
checks alongside handoff-repair checks. This evidence-framing decision does not reset repair
history or establish a broader original-baseline claim.
No preceding known-good implementation of the new retry behavior is supplied. The
orchestrator owns independent verification/acceptance of the referenced evidence.

## Hosted Windows boundary observation

Hosted run `42259081025` used the stock producer with original
`45cb5755de846d6edb634d481dbb69f901b24ca1`, shipping source
`e7191835f5eb5b1d0989e7377e2c02ceae198120` and disposable runner
`35d08ce01b86fc0febf65d269c17a01e6b6c4fcb`. The source pin and identical
file-only test/harness overlays leave the original45 runtime unchanged. The raw candidate
XML has one passed case, zero skips and no retry; baseline XML has three failed attempts
under existing retry defaults. All baseline attempts acknowledge release of the real OS
lock before timing out in the original-operation/search recovery wait. Candidate raw proof
records both distinct expected paths and unchanged Engine identity.

To reproduce the dedicated method on Windows using normal project JVM defaults:

```powershell
.\gradlew.bat -PskipWebBuild=true `
  :modules:system-tests:integrationTest `
  --tests 'io.justsearch.systemtests.supervision.RootVolumeInitializationRetryE2ETest.pendingLedgerLockRecoversOriginalOperationWithoutRestartOrResubmission' --rerun
```

Require ROOT_VOLUME_INITIALIZATION_REFUSED and ROOT_VOLUME_INITIALIZATION_RETRY_PASS in
fixture-output.txt. Their JSON must identify the exact .tmp path and actual boot write
failure, at least one admitted original path PENDING/ROOT_UNAVAILABLE before release, the
original operation key and /api/operation-history/{operationKey} endpoint, complete/SUCCESS,
unitsFailed=0, matchedCount=2, and identical submitted/recovered runId, pid, instanceId and
incarnation. Roots are admitted serially, so the later child need not be queued before
release; both expected paths must be searchable at completion. Require root-volume-lock-held,
root-volume-lock-released, healthy HTTP and the existing portsClosed=true stop proof within
the unchanged 180/330/420-second bounds.

The stock JSON is imported byte for byte. Independent raw-artifact admission and final
acceptance remain in progress; the full hosted Windows tier is still pending. This single
two-path recovery run does not establish full R/RP/O live coverage. Compilation, static
checks, Node selftests and Linux platform skips remain narrower evidence. The existing
early handle-close cleanup exception, unchanged stale-file freshness and historical
process-census limits remain unchanged.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1b150-24e1-7741-9de5-49f21c4f6946",
  "verdict": "reject",
  "digest": "sha256:2bc882b6f576b374c7cee7ab2bf49e93d71f93a2b78db069e75de1c06b0205c2",
  "note": "Formal source/oracle review: entries1,2,4,5 accept; entry3 new Windows oracle requires New/repaired and generated Windows assertion fail-before/pass-after. Retain rejection; Windows pending."
}
```

The observation repair leaves production admission, normalization, queue, ledger and runtime APIs unchanged.
The historical failed Windows artifact remains preserved in Git history and the rejection
above is retained unchanged; neither is a pass for the repaired source.
