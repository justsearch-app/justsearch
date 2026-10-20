---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: builder-uiux-status-freshness-20261020
---

# Test-intent entries

Durable WP3.3 checks, updated for the second verifier's S33.4 observation-age repair. Additional exact-candidate fail-before evidence is in `gates/test-intent/evidence/uiux-status-freshness-4c59d07c0.json`; the original-base evidence below is regenerated for all changed check files. The incompatible placement field and its isolated worker/mapper tests are retired together: all their production members are removed, and the complete scheduler/mapper source files return to f6a14b5a6. The regenerated fixture also returns byte-for-byte to f6a14b5a6. No branch-base test or compatibility baseline is removed. Existing acceptance records are retained below for history; independent verification must accept the final digest. The builder does not write acceptance.

The gate accepts only letter/digit scenario identifiers: S331–S336 encode S33.1–S33.6. Each source retains its original ID in agreedScenario and its verbatim agreed quote.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.1 energy saver defers idle enrichment while text indexing and interleaved sparse work can continue. S33.2 both GPU and CPU observations receive qualified scheduling scopes: GPU-bound idle work waits, CPU-only idle work may continue, and all sparse work between files waits. No placement field is added to the strict status shape. S33.3 chat is named only when present, without a placement guess. S33.4 stale/missing/unavailable status stays unconfirmed and reasons clear. S33.5 preserves capped tails and no invented starting percentages. The compatibility assertion preserves f6a14b5a6 and fails on 8237e63d9; other repaired assertions fail on f6a14b5a6 as recorded.",
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
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Preserve old strict clients by retaining the original public status shape. Explain idle enrichment and interleaved sparse scheduling separately: energy saver defers idle work, GPU holds also defer sparse work between files on CPU. Placement is unreported; CPU-only idle work may continue under GPU holds. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
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
      "behaviour": "All shared surfaces use qualified idle/interleaved hold wording, clear reasons, mark stale status unconfirmed, and announce semantic changes once. Library root with zero settled documents has no starting 0%. Deferred enrichment retains the measured task aggregate and counts without an active progress bar; this preservation assertion passes on f6a14b5a6 and fails on 8237e63d9. S33.4 worker observations expire by age even while inference remains live; all six mounted surfaces drop current-work and hold explanations, and recover on a fresh observation with the cleared reason absent.",
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
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Preserve old strict clients by retaining the original public status shape. Explain idle enrichment and interleaved sparse scheduling separately: energy saver defers idle work, GPU holds also defer sparse work between files on CPU. Placement is unreported; CPU-only idle work may continue under GPU holds. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N333",
          "quote": "N33.3 Both themes, High Contrast, axe and proportion gates; the task list stays clear of the rail.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "N33.3"
        },
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
      "behaviour": "Replace the old whole-queue paused expectation under energy saver with continued text indexing and enrichment deferral; the positive queued-work/power fixture remains the same. The handcrafted AiState fixture gains indexingStatusLive, following its existing live override unless explicitly supplied; all existing oracles are retained.",
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
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.5: a root with positive coverage denominators and no settled work remains enriching without inventing a starting percentage, whether or not index-wide pending is reported. Existing exact-completion, failed/unavailable/unverified and stage-applicability checks are retained.",
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
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts#S33.5 zero settled stays enriching without a fabricated starting percentage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusBackfillCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Serializing enrichment, including a populated placement field on the regressed producer, must not emit the new key that rejects the entire status in old strict clients. The original f6a14b5a6 shape is preserved; canonical regression evidence uses 8237e63d9, the version that introduced the incompatible key.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Old strict clients: the before-state web UI's generated status schema rejects the new worker.enrichment.backfillGpuBound key, so an old UI on a new Engine shows \"System idle\" with work running.",
          "location": "task t-20261019-bd718f user turn 2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S332",
          "quote": "S33.2 Under a game or external GPU load, surfaces name the reason and the work it holds, checked separately with enrichment on GPU and on CPU; they never claim that unaffected work stopped.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.2"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-compatibility.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusBackfillCompatibilityTest.java#enrichmentDoesNotPublishAnIncompatiblePlacementKey"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.4 the real store interval expires a retained worker sample after the reported window even while SSE keeps Engine contact live, notifies subscribers without a new poll, and restores confirmation on the next fresh observation. Without worker timestamp metadata, receipt age and the reported freshness window bound confirmation. Quiet mocked subscriptions isolate the timer; they are not live transport evidence. The original before-state lacks the shared activity projection; additional 4c59d07c0 evidence pins the specific confirmed-Ready and receipt-age failures.",
      "sources": [
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
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Preserve old strict clients by retaining the original public status shape. Explain idle enrichment and interleaved sparse scheduling separately: energy saver defers idle work, GPU holds also defer sparse work between files on CPU. Placement is unreported; CPU-only idle work may continue under GPU holds. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.4 Brain enrichment reports an expired observation as unconfirmed without claiming the reachable Engine is disconnected or animating current activity; fresh status restores the live presentation. The existing harness gains the indexingStatusLive field paired with its existing liveness input; the previous preservation oracles are retained.",
      "sources": [
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
          "choice": "Decision 4: keep operating.indexing as is; the UI words reasons by the work they hold, and the contract docs state that PAUSED with automatic reasons means GPU bulk/enrichment held (no wire state change). Preserve old strict clients by retaining the original public status shape. Explain idle enrichment and interleaved sparse scheduling separately: energy saver defers idle work, GPU holds also defer sparse work between files on CPU. Placement is unreported; CPU-only idle work may continue under GPU holds. Fixed wording is authored once in the shared activity projection; the status deck owns its semantic live announcement.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-status-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts#S33.4 an expired indexing observation is unconfirmed even while the Engine remains reachable, then recovers"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-am01rzj3j2",
  "verdict": "accept",
  "digest": "sha256:6c698f97958057c9b6c32ec9082d8cefc86095fbf695463d6f79042d7d79530f",
  "note": "Test expectations have scenario or delegated-choice sources. Named UI and Java checks passed independently and fail by assertion on f6a14b5a6; run-evidence rerun to retained scratch evidence. Fixture adds only an unknown optional observation. This accepts test intent only; the product verdict is fail for scheduler-path wording, old-client compatibility, Library zero-percent coverage, and the measured Library axe gate."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-momfoz0",
  "verdict": "accept",
  "digest": "sha256:8fabf08ea13c4647b3e2bf3b5ce520c9fd396e259a5a964a94cc572fee7bbe8d",
  "note": "Intent follows S33.1-S33.6, N33.3 and the explicit compatibility repair brief. Independently reran named checks; 25 UI assertions fail on f6a14b5a6 and pass on dbcd1c111. Compatibility passes f6a14b5a6 and dbcd1c111 and fails by assertion on 8237e63d9. Scratch run-evidence retained. Qualified CPU/GPU and idle/interleaved scopes match scheduler probes. Acceptance is of test intent only; hardware and full-suite verification limits are reported separately."
}
```
