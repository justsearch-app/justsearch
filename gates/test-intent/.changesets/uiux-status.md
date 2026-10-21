---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: builder-uiux-status-followup-6-20261021
---

# Test-intent entries

Durable WP3.3 checks, updated for the second verifier's S33.4 observation-age repair. Additional exact-candidate fail-before evidence is in `gates/test-intent/evidence/uiux-status-freshness-4c59d07c0.json`; the original-base evidence below is regenerated for all changed check files. The incompatible placement field and its isolated worker/mapper tests are retired together: all their production members are removed, and the complete scheduler/mapper source files return to f6a14b5a6. The regenerated fixture also returns byte-for-byte to f6a14b5a6. No branch-base test or compatibility baseline is removed. Existing acceptance records are retained below for history; independent verification must accept the final digest. The builder does not write acceptance.

Third-verifier follow-up: Brain honours explicit stale flags as well as observation age. Additional exact-candidate fail-before evidence is in `gates/test-intent/evidence/uiux-status-explicit-stale-a53780a50.json`; the original-base evidence is regenerated after the final test edits. The active measured fixture variants are stamped at response time; their registered gate checks are unchanged.

Fourth-verifier follow-up: all remaining current indexing claims use shared confirmation and hold scope, including Health connection and summary, Brain deferral, file rows, both Search paths and declared liveness. The final durable sweep has original-base and exact-repair-base evidence. Existing fixture adaptations preserve their assertions; independent final acceptance remains required.

Fifth-verifier follow-up: the independent inventory adds worker encoder/passage readiness, stale migration readouts and empty roots, overlay/fallback-dot animations, all indexing live regions, and Brain runtime queue counters. New regression evidence includes both the original and exact repair baseline; existing author evidence is regenerated after final test edits. No acceptance record is authored here.

Sixth-verifier follow-up: Library empty roots and its rebuild/activation panel now use shared observation confirmation, and Search v3 Ask leaves observed-state announcements to the deck while preserving the reachable refusal description. The durable follow-up file has generated original-base evidence below and exact repair-base evidence at `gates/test-intent/evidence/uiux-status-followup-6-af0242136.json`. Existing acceptance records are preserved; the final digest requires independent acceptance.

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
      "behaviour": "Missing status keeps Health Now body present and explicitly unconfirmed, with no System idle claim. Other heading and helper expectations are retained; the handcrafted progress helper gains the required activity field. The handcrafted progress fixture gains the nullable rebuildPercent signature field; existing assertions are preserved.",
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
      "behaviour": "Replace the old whole-queue paused expectation under energy saver with continued text indexing and enrichment deferral; the positive queued-work/power fixture remains the same. The handcrafted AiState fixture gains indexingStatusLive, following its existing live override unless explicitly supplied; all existing oracles are retained. Migration announcements now use the single indexing region, supplied with a confirmed worker observation; the pre-existing polite role/hidden/headline expectations remain. An unconfirmed migration cannot emit a second current rebuild claim.",
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
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#S33.1 energy saver names continued text indexing and deferred enrichment",
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts#595 \u00a715.1 (E1): a visually-hidden aria-live announcer mirrors the verdict, politely"
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
      "behaviour": "Unknown indexing is explicitly unconfirmed, with no aggregate phase, bar or counts, even while a separate agent task is shown. Existing projection fixtures gain an activity object whose confirmation follows phase and liveness; the other task actions, timing, percent, stage detail and estimate assertions are retained. The handcrafted progress fixture gains the nullable rebuildPercent signature field; existing assertions are preserved.",
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
      "behaviour": "S33.4 Brain enrichment reports an expired observation as unconfirmed without claiming the reachable Engine is disconnected or animating current activity; fresh status restores the live presentation. The existing harness gains the indexingStatusLive field paired with its existing liveness input; the previous preservation oracles are retained. A fresh worker timestamp does not override meta.workerRpcStale or an explicitly stale indexing lane: Brain uses the shared activity confirmation, retains figures as last observed without a spinner, and recovers on the next confirmed observation.",
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
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts#S33.4 an expired indexing observation is unconfirmed even while the Engine remains reachable, then recovers",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts#S33.4 a fresh timestamp with 'worker RPC stale' is unconfirmed, then recovers",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts#S33.4 a fresh timestamp with 'indexing lane stale' is unconfirmed, then recovers"
      ],
      "repaired": true
    },
    {
      "items": [
        "scripts/jseval/jseval/ui_fixtures.py"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Only the indexing and enriching measured-capture variants replace the canonical January 2024 meta.workerRpcAtMs with the response-time UTC timestamp on every poll. They continue to represent confirmed ongoing work. All counters, phases, stale flags, freshness windows, selectors, axe baselines and proportion assertions are preserved. The measured registered gates fail on a53780a50 waiting for task-aggregate; complete before/after sweeps are retained under tmp/uiux-status/explicit-stale/. Script fixture support is outside product test-intent scope; recorded here for review.",
      "sources": [
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
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.1\u2013S33.6: sweep every live indexing/readiness/progress consumer across energy saver, game, external GPU, chat, expired observations, explicit staleness and Engine loss. Health connection, summary/header, rebuild, queues and chat-off reason; Brain runtime, enrichment and compatibility cards; both Library render paths; Tasks/stages, System self-view and running-job chip; AI-mode pill and declared liveness; both Search banners and composer. Retained file rows are last observed while independent agent tasks remain current. Deferred enrichment does not animate. Parked runtime mode alone cannot promise work; unfinished rebuild fractions stay below 100. The deck alone announces indexing activity. All 21 checks fail by assertion on f6a14b5a6 and 1427aad2f (additional exact evidence in uiux-status-sweep-1427aad2f.json). These are durable behavior checks using real renderers and the production observation clock, without transport or timer leakage. Brain runtime checks include awaitingChatEnable both false and true, and compatibility diagnostics include both coded and fallback leads; neither branch may hide hold reasons or confirmation.",
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
      "evidence": "gates/test-intent/evidence/uiux-status-sweep.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 S33.6 Health index-state row uses confirmation and scoped reasons in every case",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Health header and summary withhold operational claims for all unconfirmed observations",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Health rebuild bar never asserts current progress without confirmation",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.2 S33.3 S33.4 S33.6 Brain cards explain holds and never animate deferred or unconfirmed enrichment",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 S33.6 Brain simple panel describes observed work rather than inferring activity from runtime mode",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Library row cannot show a completion glyph from an unconfirmed index observation",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Tasks and System self-view qualify retained indexing rows while preserving independent agent tasks",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 status-deck running-job chip marks retained indexing activity as last observed",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 Health AI queue rows use the status observation and its hold reasons instead of inference residue",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Health queue health qualifies its retained diagnostic when indexing is unconfirmed",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 declared Library cards consume the same confirmation verdict as fallback cards",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Tasks label the entire stale stage disclosure as last observed",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 mode pills cannot assert indexing from the parked AI runtime alone",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 Search composer and chat-off explanations use observed indexing activity and scoped reasons",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Search v3 diagnostic causes cannot assert stale indexing activity",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 a parked indexing runtime with no observed work does not promise active indexing to the Search composer",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.5 an unfinished rebuild never presents a completion figure when the old generation count is reached",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.1 S33.4 S33.6 declared liveness readouts share indexing confirmation and hold wording",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.6 the deck has one indexing announcement even while the AI runtime is in indexing mode",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 Brain compatibility diagnostics qualify retained indexing readiness",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts#S33.4 shipped Search banner qualifies index diagnostics on the real renderer"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/availability.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.degradation.test.ts",
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts",
        "modules/ui-web/src/shell-v0/components/CapabilityMap.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Existing capability, empty-corpus remedy and readiness-banner scenarios now supply confirmed worker observations alongside their capability verdicts. The indexing case supplies a positive observed file queue rather than relying on runtime mode alone; idle cases supply an empty healthy queue. The same preconditions and all expected wording, availability, remedies and disclosure predicates are retained; one test title names confirmed status instead of runtime mode. All six adapted fixture suites (including TaskList/SystemSelfView rebuildPercent signature additions) pass 352 checks on 1427aad2f in tmp/uiux-status/resume-fixtures-before-1427.log. Candidate evidence is attached. The mounted Search v3 feed also supplies the same healthy IDLE worker observation as its pure projection fixtures; existing headline/remedy assertions remain unchanged. Both the mounted Search v3 and Brain Runtime fixture suites pass 53 checks on unchanged 8dbeb7557 (tmp/uiux-status/continuation-preservation-before.log). Final candidate fixture evidence is regenerated.",
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
          "scenario": "S334",
          "quote": "S33.4 When a reason clears, its explanation disappears after the next fresh status observation; when status is stale or unavailable, surfaces mark the observation as unconfirmed instead of asserting current activity or quiescence.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S33.4"
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
      "evidence": "gates/test-intent/evidence/uiux-status-fixtures.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts"
      ],
      "class": "New",
      "behaviour": "Verifier-5 independent inventory regressions: worker-derived encoder and passage readiness across both Search paths, Browse empty roots and migration mode/readout accessible names, conditional overlay animation through the real host with text indexing preserved and total-loss withdrawal, exactly one live indexing announcement (including migration) and silent repeated/numeric polls, Brain runtime queues from the shared worker projection during partial status loss, and Health fallback dot confirmation. Cases cover energy, game, external GPU, chat, expired observations, explicit worker/indexing staleness, unavailable worker and Engine loss. No lifecycle or wire change. Nine durable checks assertion-fail on 8dbeb7557 and f6a14b5a6; the exact-base evidence is uiux-status-missed-8dbeb7557.json. Unknown worker queues render dashes, never invented zero counts.",
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
      "evidence": "gates/test-intent/evidence/uiux-status-missed.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 S33.6 encoder and passage readiness observations are qualified on both Search paths",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 S33.6 Browse empty roots never infer a current rebuild from unconfirmed migration",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 S33.6 migration mode pills qualify retained rebuilding while preserving fresh migration",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 S33.6 declared liveness readouts qualify retained migration and its accessible name",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.1 S33.4 overlay activity animation stops during deferred work and uncertainty, then resumes",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.6 Search and deck announce an indexing transition once, with silent numeric and identical polls",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 S33.6 migration announcements use one region and withdraw stale rebuild claims",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 Brain runtime queues use worker counts and confirmation even with fresh inference contact",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts#S33.4 Health fallback queue dot cannot animate an unconfirmed observation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The healthy Runtime preservation case supplies its original 4,789 pending embeddings through worker.enrichment.embeddingPendingCount as well as inference.embeddingQueueSize. The shared worker projection owns this readout now. All existing wording, count, control availability and tense assertions are identical; the enrichment-card fixtures and assertions retain their original 5,084 pending count. The complete adapted suite also passes on unchanged 8dbeb7557 (repair5-preservation-before.log).",
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
      "evidence": "gates/test-intent/evidence/uiux-status-missed-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts"
      ],
      "class": "New",
      "behaviour": "S33.4/S33.6: Library empty roots and rebuild/activation controls qualify retained MIGRATING, SWITCHING and AWAITING_ACCEPTANCE observations after age expiry, explicit worker/indexing staleness, unavailable index and Engine contact loss; fresh observations restore the current wording and a settled empty Library keeps its no-folders state. The gap list and controls remain reachable. Search v3 Ask keeps its refusal description reachable from Send while the deck owns the single indexing announcement; semantic stale/fresh/energy changes announce once and identical polls remain silent. Three assertions fail on af0242136; original-base evidence is also retained. This adds no wire field or scheduling change.",
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
      "evidence": "gates/test-intent/evidence/uiux-status-followup-6.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts"
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-omzh4wns",
  "verdict": "accept",
  "digest": "sha256:aa33ccd3247f4abc97a03f401897296019a288b60254d22268fac26a596c786b",
  "note": "Expectations follow S33.1-S33.6, N33.3 and the supplied compatibility requirement. Independently reran run-evidence and before-state assertions; live partial-status loss and recovery pass on 46952a486 and fail on f6a14b5a6. Compatibility also fails by assertion on 8237e63d9. Acceptance concerns test intent only: product verdict remains fail for Brain ignoring explicit stale flags and registered measured gates timing out on old fixture timestamps."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-qpaxzu4",
  "verdict": "accept",
  "digest": "sha256:d2836f07164192020ce52a2bf740b62065c8f5c5bb89d3611d5a860cd281d92a",
  "note": "Expectations follow the supplied agreed scenarios and compatibility obligation. Independent UI evidence rerun passed; transplanted tests fail by assertion on f6a14b5a6, and serialization compatibility fails by assertion on 8237e63d9 while passing both original and candidate. Fixture changes only active observation timestamps; registered assertions and baselines are identical. Acceptance concerns test intent only: product verification fails for uncaveated stale Health index-state values and Brain animating energy-deferred idle enrichment."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-g2c532fw",
  "verdict": "accept",
  "digest": "sha256:53a37d76e86eaa6961853fa3e43ccf99f4c6648f303cea5b78355183051ecf5e",
  "note": "Expectations follow the agreed scenarios and delegated compatibility decision. Independent UI/Java evidence reruns passed; 53 baseline assertions fail and 300 adapted-fixture checks pass. Compatibility fails on 8237e63d9 and passes before/after. Test intent only: product fails stale diagnostic, migration, animation and announcement cases."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-g9ic134p",
  "verdict": "accept",
  "digest": "sha256:bd6dd9c39ac8ae3832758cebf86767c6509b5ef503fd182f0ea1ab4cd6827f25",
  "note": "Expectations follow S33.1-S33.6 and the delegated compatibility decision. Independent run-evidence reruns pass; 63 baseline UI assertions fail for the repaired behavior, and adapted preservation checks pass. Compatibility passes before/after and assertion-fails on 8237e63d9. Acceptance is of test intent only. Product verdict fails S33.4/S33.6: Library empty-root migration remains current when stale or gone, and Search v3 Ask duplicates indexing announcements."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "wp33-verifier-n9bsny97",
  "verdict": "accept",
  "digest": "sha256:7cf62fd38aeed66d53614ac9fb3d1d851345aa511326c2071727c811f96e5eb1",
  "note": "Expectations follow S33.1-S33.6 and decision 4. Independent run-evidence reruns pass; 66 original-baseline UI assertions fail and 300 adapted preservation cases pass. All three followup assertions fail on f0e351814; live Library expiry/loss and Ask announcements pass on fa8d37021. Compatibility passes before/after and assertion-fails on 8237e63d9. Test intent only; hardware, chat generation and spoken screen-reader output remain unverified."
}
```
