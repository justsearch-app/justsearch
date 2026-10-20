---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: builder-uiux-status-20261020
---

# Test-intent entries

Durable WP3.3 checks. The task owns the agreed scenarios; the builder does not write acceptance.

The gate accepts only letter/digit scenario identifiers: S331–S336 encode S33.1–S33.6. Each source retains its original ID in agreedScenario and its verbatim agreed quote.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.1 energy holds only enrichment, including file-queue-empty and wire IDLE; S33.2 GPU/CPU/unknown placement is preserved by the generated strict status validator and controls the explanation for game/external holds; simultaneous reasons do not falsely claim CPU progress under energy saver. S33.3 chat appears only while present. S33.4 stale, missing, failed and unavailable observations are unconfirmed and old reasons clear. S33.5 indexing and denominator-less enrichment tails cap at 99, and undrained work has no invented start percentage.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S331",
          "quote": "S33.1 (broken today) With energy saver on and files queued, no surface says indexing as a whole is paused; surfaces say that text indexing continues and which enrichment waits and why. With the file queue empty but enrichment waiting, they say enrichment is deferred to save energy, not Ready and not Paused.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S333",
          "quote": "S33.3 While chat generates, surfaces either name chat as the reason indexing is slower or show no reason; never a reason that is not present.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.3"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S335",
          "quote": "S33.5 (broken today) With positive remaining work, no progress figure claims completion; before any drain is observed, no percentage is invented (indexing and enrichment percent alike).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.5"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S336",
          "quote": "S33.6 The same state is described consistently on status deck, task list, Health, Library rows and the overlay; changes are announced once to screen readers, not on every poll.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.6"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.3 automatic-hold wording and read-only status observability; automatic hold actuation remains unchanged.",
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Add nullable worker.enrichment.backfillGpuBound, preserving true/false/unknown through IPC, mapper and generated validator; true is embedding GPU or sparse/NER GPU availability and includes a mixed cycle. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingStatus.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts"
      ],
      "class": "New",
      "behaviour": "All five named surfaces plus Health Now consume the shared energy, GPU and CPU explanations; pure enrichment under energy saver is deferred, reasons replace/clear, stale observations are unconfirmed. One status-deck announcer changes once when semantics change, remains unchanged on identical polls, and is absent from other surfaces.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S331",
          "quote": "S33.1 (broken today) With energy saver on and files queued, no surface says indexing as a whole is paused; surfaces say that text indexing continues and which enrichment waits and why. With the file queue empty but enrichment waiting, they say enrichment is deferred to save energy, not Ready and not Paused.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S333",
          "quote": "S33.3 While chat generates, surfaces either name chat as the reason indexing is slower or show no reason; never a reason that is not present.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.3"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S336",
          "quote": "S33.6 The same state is described consistently on status deck, task list, Health, Library rows and the overlay; changes are announced once to screen readers, not on every poll.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.6"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.3 automatic-hold wording and read-only status observability; automatic hold actuation remains unchanged.",
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Add nullable worker.enrichment.backfillGpuBound, preserving true/false/unknown through IPC, mapper and generated validator; true is embedding GPU or sparse/NER GPU availability and includes a mixed cycle. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace the old invented zero expectation for pending above its denominator with null; counts and phase inputs are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S335",
          "quote": "S33.5 (broken today) With positive remaining work, no progress figure claims completion; before any drain is observed, no percentage is invented (indexing and enrichment percent alike).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.5"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts#S33.5 an undrained stage with pending above its total has no percentage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SystemSelfView.test.ts"
      ],
      "class": "New",
      "behaviour": "Missing status keeps Health Now body present and explicitly unconfirmed, with no System idle claim. Other heading and helper expectations are retained; the handcrafted progress helper gains the required activity field.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SystemSelfView.test.ts#S33.4 strip variant keeps the body and marks a missing observation unconfirmed"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace the old whole-queue paused expectation under energy saver with continued text indexing and enrichment deferral; the positive queued-work/power fixture remains the same.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S331",
          "quote": "S33.1 (broken today) With energy saver on and files queued, no surface says indexing as a whole is paused; surfaces say that text indexing continues and which enrichment waits and why. With the file queue empty but enrichment waiting, they say enrichment is deferred to save energy, not Ready and not Paused.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.1"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#S33.1 energy saver names continued text indexing and deferred enrichment"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace the old Health energy pause expectation with text indexing continuing and enrichment deferred; other Queue card, chat, failure and readiness expectations remain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S331",
          "quote": "S33.1 (broken today) With energy saver on and files queued, no surface says indexing as a whole is paused; surfaces say that text indexing continues and which enrichment waits and why. With the file queue empty but enrichment waiting, they say enrichment is deferred to save energy, not Ready and not Paused.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.1"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#S33.1 energy saver explains enrichment deferral while text indexing continues"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts"
      ],
      "class": "New",
      "behaviour": "A presentational overlay without an observation says unconfirmed and waits for a fresh status instead of asserting active embedding work; queue, actions, modality and host liveness checks are retained.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#S33.4 without an observation renders an unconfirmed header and explanation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts"
      ],
      "class": "New",
      "behaviour": "Unknown indexing is explicitly unconfirmed, with no aggregate phase, bar or counts, even while a separate agent task is shown. Existing projection fixtures gain an activity object whose confirmation follows phase and liveness; the other task actions, timing, percent, stage detail and estimate assertions are retained.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts#S33.4 unknown indexing is unconfirmed without a phase, bar or counts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsBackfillPlacementTest.java"
      ],
      "class": "New",
      "behaviour": "The status-only scheduler accessor observes each dense, SPLADE, BGE-M3 and NER placement change, including mixed CPU/GPU. Worker status publishes true and false from the loop and leaves the optional fact absent without a loop. Reflection allows assertion fail-before without adding compatibility production code.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.3 automatic-hold wording and read-only status observability; automatic hold actuation remains unchanged.",
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Add nullable worker.enrichment.backfillGpuBound, preserving true/false/unknown through IPC, mapper and generated validator; true is embedding GPU or sparse/NER GPU availability and includes a mixed cycle. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexStatusOpsBackfillPlacementTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperBackfillPlacementTest.java"
      ],
      "class": "New",
      "behaviour": "The IPC-to-status mapper preserves GPU true, CPU false and absent/unknown null, rather than guessing placement from the GPU profile. Reflection and protobuf descriptors keep the baseline failure an assertion, not compilation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.3 automatic-hold wording and read-only status observability; automatic hold actuation remains unchanged.",
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Add nullable worker.enrichment.backfillGpuBound, preserving true/false/unknown through IPC, mapper and generated validator; true is embedding GPU or sparse/NER GPU availability and includes a mixed cycle. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperBackfillPlacementTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Regenerated by StatusRecordSchemaTest.regenerateContractFixtures after adding the nullable read-only backfillGpuBound record component. Exactly one new unknown/null property is added; every existing fixture input is unchanged. The four existing status schema-consumer assertions are unchanged and pass before and after. The new validator check in indexingStatus.test.ts independently preserves true, false, null and undefined observations; its fixture construction explicitly overrides the placement value so the null fixture default cannot replace an undefined input.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.3 automatic-hold wording and read-only status observability; automatic hold actuation remains unchanged.",
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Add nullable worker.enrichment.backfillGpuBound, preserving true/false/unknown through IPC, mapper and generated validator; true is embedding GPU or sparse/NER GPU availability and includes a mixed cycle. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-contract.json"
    }
  ]
}
```

## Acceptance records
