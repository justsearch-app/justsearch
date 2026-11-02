---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: builder-uiimpl-brain-20261102
---

# Test-intent entries

AI Brain window redesign, step 1 (task t-20261101-uiimpl). The Brain tab becomes one page:
headline, generated sentence, path of steps, graphics card bar, links and Advanced settings in place.
Developer telemetry and its fetching move to Health's AI Engine section. Existing state owners and
operations stay; the entries below describe the final tests against main (344bdaf03c).
The page-level Simple/Detailed toggle is removed from this page only. No acceptance record is
written by the builder.

Evidence is generated only by the unmodified run-evidence.mjs, in a shared clone in the session
temporary directory with its own copied dependencies. Repairs of behaviour broken at main fail by
assertion on main: the consent checks on aeaa3e0413 (an earlier main). The B20 graphics-card and
runtime-variant checks are not marked repaired and carry no fail-before: run on main they fail only because
main lacks the new slider and Other options controls. An independent acceptor demonstrated both defects on
main through main's own controls; that demonstration is not part of this changeset's evidence (their
entry says so). Repairs of defects introduced and found inside this change
carry their own intermediate before-state in the entry and evidence (e4bb40ed0e, 6680864f7d,
f2f1945c23, 13a89c28aa, 1b5ceea94b, 234d87ca0b, 2c7f1e8d99, 1489cf0340, 4c6637f1b6, 591a7ad461,
7f435cfc7e).

Changed test files against main (all durable product checks):

- modules/ui-web/src/boot/desktopRecoveryPolling.test.ts — Q3 — comment only beside the unchanged four-timer expectation
- modules/ui-web/src/shell-v0/components/StatusDeck.test.ts — Q5 — complete AiState factory supplies the observation flags
- modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts — B1/Q6 — Stop answers keeps restarted-process freshness
- modules/ui-web/src/shell-v0/state/brainPage.test.ts — B3/B15/B16 — table-driven sentence over install, switch and availability combinations (new)
- modules/ui-web/src/shell-v0/state/uiModeState.test.ts — Q3/Q5 — page-toggle persistence guarantees moved to the mode owner
- modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts — B8/B13 — immediate shared install reads (new)
- modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts — B13/Q3 — failed-read invalidation and lifecycle outcomes
- modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts — B2/Q5 — live observation fixtures
- modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts — B1/Q2/Q5 — installed fixtures, action at its new place
- modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts — B9/B17/Q5 — inventory in Advanced, native include switch
- modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts — B8/B13 — store-only presentation after operations (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts — B2/Q5 — live observation fixtures
- modules/ui-web/src/shell-v0/views/BrainSurface.installRefresh.test.ts — B13 — completion/activation refresh at the new placement
- modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts — B2–B8/B13/B15/B18/B20/Q3/Q7 — live redesign repairs (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts — B13/Q3 — focus, cadence and failed-read gating (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts — B2/Q5 — new headline in positive and negative checks
- modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts — B5–B8/B13–B19/Q2/Q7 — switches, consent, panel, GPU and races (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts — B8/B11/Q5 — repair authority; install action matched by label prefix
- modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts — B2/B4/B8/B13 — running Repair presentation (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.skipReason.test.ts — B9/Q5 — Advanced placement
- modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts — B3/B9/B13/B20/Q3/Q6 — Advanced controls, sliders, variants, races (new)
- modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts — B2/B9/B15/Q3/Q5 — answers action follows the saved choice and is refused while unconfirmed; offline Restart AI and answers non-queuing
- modules/ui-web/src/shell-v0/views/BrainSurface.sparkline.test.ts — B10/Q5 — sparkline checks at Health
- modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts — B10/B21 — Health ownership, empty/unavailable states, Brain no-poll
- modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts — B1/B20 — same page under either mode, Advanced never writes ui.mode
- modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts — Q5 — live observation fixtures
- modules/ui-web/src/shell-v0/views/SettingsSurface.ui-mode.test.ts — Q3/Q5 — rejection/timeout rollback at the settings owner

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "rename"
      ],
      "whatMoved": "componentList: the harness supplies a live install observation (not_installed verdict) and opens Advanced through the Advanced settings link; the Models accordion no longer exists and the inventory is read in Advanced, Downloaded files. Every original group, description, size, column, unavailable reason and decline request is asserted unchanged. The reranker opt-out is now a native switch: its accessible name reads \"Include Search reranker\" (was \"Search reranker\"), state is read from role switch and aria-checked true (was pressed/aria-pressed), and it is clicked as a real button; the single decline POST is unchanged. The honest-progress harness also supplies the live observation flags. chat-intent: fixtures supply installed answer files (completed install, installed chat in the plan), a successful settings read and a saved answer choice, and the action is read at its new place in the links below the path. The per-lifecycle-state oracle no longer decides the outcome: the fixture saves answers on for starting and online and off for offline, indexing and background, and the expected core.set-chat-enabled argument follows that saved answer choice (B15), not the lifecycle state; an independent acceptor showed that forcing every state to online still passes all 9 tests. The retired guarantee that each lifecycle state gets its own action has its own Obsolete entry. The expected arguments per case, the plan's unavailable reason, the two online-AI policy refusals and the never-core.switch-inference-mode assertion are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B17",
          "quote": "(challenger 4) SPLADE, entity recognition and citation scoring keep their individual include/skip choices: Advanced settings lists each optional part with its known size and choice (same consent rules); Best match first maps only to the reranker package; existing declines survive and change the install total.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/uiModeState.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ui-mode.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "The removed page-toggle persistence assertions move to enqueueUiModeSettings at uiModeState: compatible advanced/simple wire values, revision witness, UUID-v7 operation keys, ordering tokens, rapid-choice serialization and abort/queue release. Existing owner tests also retain cross-projection serialization, monotonic ordering across reload, lock timeout and subscriber/revision semantics. Rejection and timeout rollback now exercise the real settings owner through jf-save-settings, its transport, loadSettings reconciliation and clearing busy state. No production owner behaviour changes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implementing the agreed window designs on existing backend behaviour, AI Brain step 1.",
          "choice": "Use the Library headline and hairline presentation, replace removed visual assertions with semantic ready/start/stop assertions, and check mode persistence and rollback at their state/settings owners.",
          "label": "agent choice"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace the removed page toggle with assertions that either global mode renders exactly the same Brain page, the page has no toggle, opening Advanced does not POST or write global mode, and a settings refresh cannot overwrite a newer shared mode. Persistence and rollback checks are relocated to the owners in the separate Adaptation entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-redesign.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for Simple/Detailed, Detailed and ui.mode). Retired from main 344bdaf03c BrainSurface.ui-mode.test.ts: \"renders the stable advanced value as the user-facing Detailed label\" (the page toggle buttons read Simple/Detailed, are type button, and aria-pressed/active follow setUiMode). B1 removes the Simple/Detailed toggle from this page only; the same label, aria-pressed and live-mode guarantees for the title-bar toggle stay asserted by the unchanged chrome/Shell.test.ts (\"the topbar Simple/Detailed toggle reflects the live uiMode and drives it on click\"). Persistence and rollback of the page toggle are relocated to the owners in the uiModeState/SettingsSurface Adaptation entry."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts",
        "modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts"
      ],
      "class": "New",
      "behaviour": "Wording follows the redesign while the oracles stay. pausedInstall: the paused case asserts \"Download Paused\", the kept-bytes sentence and Resume Download, and that the page does not say \"AI isn't installed yet\" (was \"Not Installed\") or \"Install AI models to get started\"; the fresh case asserts \"AI isn't installed yet\" and \"Install AI models to get started\"; the activation-required case asserts the activation headline and \"Activate downloaded models\" and that it does not say \"AI isn't installed yet\" (was \"Not Installed\"). Mount-versus-click preview freshness is unchanged. snapshotLiveness: the status-panel fixture has a read, saved \"on\" answer choice (settings read, chatEnabled true) and a retained observation that chat ran, so only liveness differs between its renders. With the backend gone the panel does not show \"Chat and summaries ready.\" or the \"AI is ready\" headline, the Answer questions step reads \"Unknown — checking AI status\" (not the retained \"On\"), and the answers action reads \"Stop answers\" (the saved choice, B15) and is exactly unavailable with the reason \"Checking AI status before changing features.\", not transient; the live twin renders \"AI is ready\", \"Chat and summaries ready.\", the step state \"On\" and \"Stop answers\" available. The retired \"Online\", status-dot and label expectations are in the snapshotLiveness Obsolete entry; enrichment liveness, aggregate/scope, the offline Restart AI refusal (unavailable with the disconnection reason, not transient, never queued or replayed through a real jf-control) and its live twin are unchanged. New (Q3): the Stop answers control rendered over a dead backend is unavailable and not transient, and a real jf-control neither fires nor queues it, also after the backend returns. aiStateStore.restartInference: Stop answers (was Shut Down AI) keeps restarted-process freshness.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B15",
          "quote": "(challenger 2) The answer switch shows the saved intent (nullable chatEnabled: unset stays distinct from explicit off); the sentence explains current availability, including answers off while background work continues and answers temporarily unavailable while pictures are being read, and their completion.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implementing the agreed window designs on existing backend behaviour, AI Brain step 1.",
          "choice": "Use the Library headline and hairline presentation, replace removed visual assertions with semantic ready/start/stop assertions, and check mode persistence and rollback at their state/settings owners.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-redesign.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts",
        "modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Download consent is disabled until its manifest and every planned size are known, with a retry; consent excludes declined siblings and files already installed. These exact checks fail by assertion on aeaa3e0413. Other redesign scenarios in the isolated new test file are independently covered by the general evidence and the verifier.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B18",
          "quote": "(challenger 5) Selection and install races: while a choice is saving, consent is open, or a download is running or paused, feature switches that would change the plan are blocked with a reachable reason (\"Changes apply after this download\"); a late decline response never overwrites a newer choice; a choice made in another window shows here. Consent lists exactly the set that will download. Known limit: the backend start does not bind consent to the previewed set (atomic binding is backend work, deferred).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B19",
          "quote": "(challenger 6, broken today) Accept and download is disabled until the manifest and every size are known (failed or slow preview says so, with retry); Repair's confirmation lists what it will download with sizes and licences where known, else says the size is unknown.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-redesign-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts#consent lists only planned packages, excluding declined siblings and already installed files",
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts#accept is disabled until the manifest and every download size are known, with retry"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installRefresh.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.skipReason.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.ui-mode.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "signature"
      ],
      "whatMoved": "Existing install inventory, skip-reason, install refresh and global-mode disclosure checks observe the new Advanced placement. Their operation, persistence, rollback and failure expectations are unchanged. Repair authority and removed runtime-mode controls are classified separately below; they are not presentation-only adaptations.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B9",
          "quote": "Advanced settings expands in place: Answers (answer model with Change, reading memory, longest answer as sliders with values), Graphics card (graphics card use in layers, graphics card support with Other options, engine program with Use another), Downloaded files (each installed component's known size, \"in use\" only where the runtime observes it; Import an offline package), Maintenance (Restart AI, Repair installation, Rebuild search index). Every control keeps today's behaviour: witnessed settings saves with conflict handling, reload, runtime variant activation, pack preflight/import (desktop only), rebuild consent. \"Pause answers while indexing\" and \"Remove files\" are not shown in step 1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window design, step 1 slice B, and its independent-review repairs.",
          "choice": "Use in-place grouped controls and native range inputs; keep saved null values until a control is explicitly changed, retain acquisition choices, move telemetry fetching and presentation together to Health, and remove obsolete accordions while preserving their operations and observations.",
          "label": "agent choice"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sparkline.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "input re-encoding"
      ],
      "whatMoved": "The four sparkline checks run against the new owner, jf-health-surface, instead of jf-brain-surface; the harness gives Health a host fetch that never resolves so the injected inference generation and transitions stay in place. The assertions are unchanged: no sparkline at generation 0; no sparkline without transitions; with generation > 0 a polyline and one dot per transition row (3); failure rows contribute dots (2) whose fill is the var(--accent-danger) token, never the raw #f87171.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B10",
          "quote": "Developer telemetry (inference transitions, spans, the engine-instance sparkline) is shown in Health's AI Engine section instead of the AI Brain page, with its empty/unavailable states; \"Engine history in Health\" links there.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts"
      ],
      "class": "New",
      "behaviour": "Health owns developer telemetry. Opened first, under either global mode, Health requests /api/inference/transitions?limit=8 and /api/diagnostics/traces?limit=10 and renders in its AI Engine section \"Recent inference transitions\", \"engine generation:\", \"Recent spans\", the sparkline line, two transition rows and one span row. AI Brain makes no transitions or traces request within 10 seconds with Advanced open and renders neither the timeline nor the trace explorer. Empty data keeps both sections visible with \"No inference transitions yet.\" and \"No recent spans.\"; failed reads say \"Inference transitions unavailable.\" and \"Recent spans unavailable\" and show no rows. With tracesAvailable false the timeline stays, no supplied span is shown and the section says \"Recent spans unavailable\". Unmount aborts the pending read, no request follows, and a late response cannot replace the shown transitions. The Engine history link focuses the AI Engine section on a later mount and requestMemberTab focuses it on a mounted Health. Changed against the base: the base hid both sections when there was no data and hid the trace explorer when tracesAvailable was false; B21 replaces that with visible empty/unavailable states. The base Simple-panel absence assertions become the stronger Brain no-poll/no-render check, and the base Detailed label assertions are kept at Health. The S6 request-independence checks in this file are warranted by the step 1 review repair entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B10",
          "quote": "Developer telemetry (inference transitions, spans, the engine-instance sparkline) is shown in Health's AI Engine section instead of the AI Brain page, with its empty/unavailable states; \"Engine history in Health\" links there.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B21",
          "quote": "(challenger 14) Health owns the telemetry fetching it shows (works when Health is opened before or without AI Brain; stops when Health unmounts; the link works when Health is already mounted), and shows empty/unavailable states instead of hiding the sections. AI Brain no longer polls telemetry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window design, step 1 slice B, and its independent-review repairs.",
          "choice": "Use in-place grouped controls and native range inputs; keep saved null values until a control is explicitly changed, retain acquisition choices, move telemetry fetching and presentation together to Health, and remove obsolete accordions while preserving their operations and observations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-redesign.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "R1 distinguishes saved acquisition declines from files and runtime enablement across remount. R2 excludes unavailable and not-in-mode answers from promises using explicit availability for pictures. R3 reconciles individual live package completion during a staged run. R4 preserves unknown ONNX observations. R5 rejects an old GET after a witnessed LLM save. R6 retains the alert announcement and Repair action. Each named check fails by assertion on 6680864f7d and passes on this candidate.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B3",
          "quote": "The sentence is generated from real state for every combination of install state and switch state; it names only what is true now (for example \"It doesn't read text in scans and images yet\" only when that is off or missing) and ends with where it runs. Feature phrases carry their switch inline where a switch exists; the same switch appears at the step in the path, and both always show the same state and act the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B11",
          "quote": "Alerts appear once, above the path, in words with their action: runtime or operation failure with code, download or online-policy denial, install failure, repairable processor fallback, compatibility block, activation or reindex required.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q6",
          "quote": "(challenger 12) Backend restart and upgrade: retained bytes survive and resume; explicit answers-off stays off; newly registered packages stay \"available additions\"; optional missing supporting files do not become required repair failures. Saved values keep their meaning: declinedAiPackages is acquisition preference only (never runtime enablement); nullable chatEnabled; the six LLM settings with null semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-sliceB-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R1 acquisition decline does not hide existing answer files or replace their runtime switch, including after remount",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R2 unavailable answers are not promised, and pictures use explicit availability",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R2 not-in-mode answers are not promised, and pictures use explicit availability",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R3 live installed packages replace stale not-downloaded paths before the staged run ends",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R4 an unknown runtime observation stays unconfirmed in both sentence and path",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R5 a settings GET begun before a witnessed LLM save cannot revert the saved value",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#R6 the processor-fallback repair hint remains an accessible alert with its action"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts",
        "modules/ui-web/src/shell-v0/state/brainPage.test.ts"
      ],
      "class": "New",
      "behaviour": "Advanced opens in place (its four groups and the three sliders are present right after Advanced settings is opened, with no further section to expand) without writes and retains per-part choices. Range controls retain drafts through refresh/tab changes, commit once per change with witnesses, preserve all untouched settings and null semantics, distinguish automatic GPU allocation from zero, recover from conflicts, and converge across windows. Installed inactive variants activate using the real endpoint fields (that check and the Automatic-versus-zero check repair defects observed at main and have their own entry with assertion fail-before evidence through main’s existing Runtime controls). Settings reads resolve safely after disconnect and with focus in a sibling shadow tree. The sentence table covers unavailable and excluded packages.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B3",
          "quote": "The sentence is generated from real state for every combination of install state and switch state; it names only what is true now (for example \"It doesn't read text in scans and images yet\" only when that is off or missing) and ends with where it runs. Feature phrases carry their switch inline where a switch exists; the same switch appears at the step in the path, and both always show the same state and act the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B9",
          "quote": "Advanced settings expands in place: Answers (answer model with Change, reading memory, longest answer as sliders with values), Graphics card (graphics card use in layers, graphics card support with Other options, engine program with Use another), Downloaded files (each installed component's known size, \"in use\" only where the runtime observes it; Import an offline package), Maintenance (Restart AI, Repair installation, Rebuild search index). Every control keeps today's behaviour: witnessed settings saves with conflict handling, reload, runtime variant activation, pack preflight/import (desktop only), rebuild consent. \"Pause answers while indexing\" and \"Remove files\" are not shown in step 1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q6",
          "quote": "(challenger 12) Backend restart and upgrade: retained bytes survive and resume; explicit answers-off stays off; newly registered packages stay \"available additions\"; optional missing supporting files do not become required repair failures. Saved values keep their meaning: declinedAiPackages is acquisition preference only (never runtime enablement); nullable chatEnabled; the six LLM settings with null semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window design, step 1 slice B, and its independent-review repairs.",
          "choice": "Use in-place grouped controls and native range inputs; keep saved null values until a control is explicitly changed, retain acquisition choices, move telemetry fetching and presentation together to Health, and remove obsolete accordions while preserving their operations and observations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-redesign.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts",
        "modules/ui-web/src/shell-v0/state/brainPage.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "B20, behaviour broken at main (344bdaf03c). Graphics card use: with saved llm.gpuLayers null the Advanced slider reads \"Automatic\"; moving it to 0 saves exactly { gpuLayers: 0 }, reads \"0 layers · processor only\", and keeps maxTokens null, contextWindow 0 and modelPath. Runtime variants: with runtimeStatus.installedVariants [cuda12] and active.activeVariantId null, Other options offers Activate for the installed inactive variant and invokes core.activate-runtime-variant { variantId: cuda12 }. Main has its own controls for both: a GPU layers number input in its Runtime section, whose value is gpuLayers ?? 0, and GPU runtime variant rows with Activate and Deactivate buttons built from runtimeStatus.variants and activation.activeVariantId. Both checks are repairs. The execution evidence runs the unchanged assertions on main through its existing Runtime controls using gates/test-intent/evidence/brain-b20-main-controls.patch: the compatibility patch opens the existing Runtime section, aliases its GPU layers input with the candidate selector, and projects its unchanged displayed number as aria-valuetext. Main fails by assertion because saved Automatic (null) is displayed as '0' and the endpoint's real variant fields offer no Activate; both checks pass on the candidate. The patch changes neither the GPU value expression nor variant field reads, fixtures or assertions.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B9",
          "quote": "Advanced settings expands in place: Answers (answer model with Change, reading memory, longest answer as sliders with values), Graphics card (graphics card use in layers, graphics card support with Other options, engine program with Use another), Downloaded files (each installed component's known size, \"in use\" only where the runtime observes it; Import an offline package), Maintenance (Restart AI, Repair installation, Rebuild search index). Every control keeps today's behaviour: witnessed settings saves with conflict handling, reload, runtime variant activation, pack preflight/import (desktop only), rebuild consent. \"Pause answers while indexing\" and \"Remove files\" are not shown in step 1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q6",
          "quote": "(challenger 12) Backend restart and upgrade: retained bytes survive and resume; explicit answers-off stays off; newly registered packages stay \"available additions\"; optional missing supporting files do not become required repair failures. Saved values keep their meaning: declinedAiPackages is acquisition preference only (never runtime enablement); nullable chatEnabled; the six LLM settings with null semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-main-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#Automatic stays distinct from processor-only, and changing one slider preserves the other five settings",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#an installed inactive variant can be activated from the real endpoint fields"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The missing declined answer fixture now explicitly reports an offline engine, no model and no chat capability; it no longer contradicts its missing-file setup by publishing an active answer model. Size/licence consent, acceptance and the start-install assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B9",
          "quote": "Advanced settings expands in place: Answers (answer model with Change, reading memory, longest answer as sliders with values), Graphics card (graphics card use in layers, graphics card support with Other options, engine program with Use another), Downloaded files (each installed component's known size, \"in use\" only where the runtime observes it; Import an offline package), Maintenance (Restart AI, Repair installation, Rebuild search index). Every control keeps today's behaviour: witnessed settings saves with conflict handling, reload, runtime variant activation, pack preflight/import (desktop only), rebuild consent. \"Pause answers while indexing\" and \"Remove files\" are not shown in step 1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q6",
          "quote": "(challenger 12) Backend restart and upgrade: retained bytes survive and resume; explicit answers-off stays off; newly registered packages stay \"available additions\"; optional missing supporting files do not become required repair failures. Saved values keep their meaning: declinedAiPackages is acquisition preference only (never runtime enablement); nullable chatEnabled; the six LLM settings with null semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S1–S7 review repairs: supported Automatic semantics (GPU null display without a reset; context reset writes zero; no longest-answer reset), mock null-as-keep merge matching SettingsPatch, active status plus active model for On, initial settings-read uncertainty with reachable blocked reasons, selected additions Install action and exact consent, last-observed picture-reading wording on disconnect, independent bounded Health endpoint requests that skip overlapping ticks and ignore timed-out late results, and shared expanded/controls semantics for every feature opener. The GPU slider preservation check asserts its untouched-value and processor-only expectations.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B3",
          "quote": "The sentence is generated from real state for every combination of install state and switch state; it names only what is true now (for example \"It doesn't read text in scans and images yet\" only when that is off or missing) and ends with where it runs. Feature phrases carry their switch inline where a switch exists; the same switch appears at the step in the path, and both always show the same state and act the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B4",
          "quote": "Path: Your files (file count from status), Read the text (built in), Understand meaning (needed, its size, no switch), Best match first, Answer questions (model name and size when installed), with Text in scans and images as a branch off Read the text. Each step shows its known size (registry download/footprint size, never claimed as measured disk use) and its state in words (on, off, not downloaded, downloading x%, unavailable on this computer with the reason).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B6",
          "quote": "Selecting a feature (its phrase, its step or its switch's info affordance) opens one small panel at that step: \"You get\" and \"It costs\" in plain words, with only known numbers (download size, on-disk size, that it uses graphics memory while working); no made-up per-feature memory figures. Escape and outside click close it, focus returns; only one panel at a time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B15",
          "quote": "(challenger 2) The answer switch shows the saved intent (nullable chatEnabled: unset stays distinct from explicit off); the sentence explains current availability, including answers off while background work continues and answers temporarily unavailable while pictures are being read, and their completion.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B21",
          "quote": "(challenger 14) Health owns the telemetry fetching it shows (works when Health is opened before or without AI Brain; stops when Health unmounts; the link works when Health is already mounted), and shows empty/unavailable states instead of hiding the sections. AI Brain no longer polls telemetry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q6",
          "quote": "(challenger 12) Backend restart and upgrade: retained bytes survive and resume; explicit answers-off stays off; newly registered packages stay \"available additions\"; optional missing supporting files do not become required repair failures. Saved values keep their meaning: declinedAiPackages is acquisition preference only (never runtime enablement); nullable chatEnabled; the six LLM settings with null semantics.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window design, step 1, including independent-review repairs.",
          "choice": "Use each existing backend field's supported Automatic meaning, preserve unknown settings until a successful read, restore the existing size/licence consent for additions, and bound each independent Health telemetry request at 15 seconds while skipping overlapping ticks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-step1-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S1 only reading memory can reset automatically and persists zero through backend merge",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S1 reading memory Automatic sends zero rather than a null no-op",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S2 inactive loaded sessions are off and never promised, while unknown remains unknown",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S2 Advanced applies the same rule: a loaded but inactive feature is not in use or counted active",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S4 installed systems retain an additions Install action with selected size and exact consent",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S5 disconnect during picture reading uses last-observed wording until a live completion",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S7 every phrase, step and About opener shares expanded state and panel association",
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts#S6 a bounded pending read becomes unavailable and can retry, ignoring late results",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S3 saved answer intent is unknown and blocked while initial settings are pending",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts#S3 saved answer intent is unknown and blocked while initial settings are failed",
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts#S6 slow transitions survives a poll tick while the other endpoint publishes independently",
        "modules/ui-web/src/shell-v0/views/BrainSurface.telemetry-mode.test.ts#S6 slow traces survives a poll tick while the other endpoint publishes independently"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The direct-render harness marks its injected saved settings snapshot as successfully read, matching the new S3 initial-read guard, and saves an answer choice per case: on for starting and online, off for offline, indexing and background. The per-lifecycle-state oracle no longer decides the outcome: the expected action (the core.set-chat-enabled argument) now follows the saved answer choice (B15), not the lifecycle state, so forcing every state to online still passes all 9 tests, as an independent acceptor showed. The retired guarantee that each lifecycle state gets its own action is recorded in the Obsolete entry that follows. The expected argument values per case, the plan's unavailable reason, the online-AI policy refusals and the never-core.switch-inference-mode assertion are unchanged. Pending and failed settings reads are pinned separately by the rendered S3 regression checks.",
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B15",
          "quote": "(challenger 2) The answer switch shows the saved intent (nullable chatEnabled: unset stays distinct from explicit off); the sentence explains current availability, including answers off while background work continues and answers temporarily unavailable while pictures are being read, and their completion.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for set-chat-enabled, chat-enabled, chatEnabled, escape action, lifecycle state, Cancel, Shut Down AI, Start Chat AI and Resume Chat AI; the only hit, the 2026-10-20 amendment of ADR-0008, records that UiSettings persists chatEnabled and says nothing about which action a lifecycle state offers; ADR-0034's rule against enabling actions from stale or inferred backend state is not contradicted, because the saved choice is read from the backend settings). Retired from main 344bdaf03c BrainSurface.chat-intent.test.ts: each lifecycle state gets its own action, decided by the verdict alone (offline Start AI and indexing Resume Chat AI writing enabled true, background Start Chat AI writing enabled true, starting Cancel and online Shut Down AI writing enabled false). Under B15 the answer action shows the saved intent, so the expected core.set-chat-enabled argument now follows the saved answer choice; the test keeps the same five states and expected arguments only through its fixture, which saves the matching choice per state."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The one-page layout keeps the repair guarantees. The harness supplies a live install observation, opens Advanced, records the alert \"Repair\" (else the Maintenance \"Repair installation\") variant and availability, and records any install action by its label prefix \"Install AI\" or \"Install additions\" (the labels carry the size). Required gap (repairNeeded): Repair in the alert is primary, no install action is offered, and the page says \"A required component is missing\" and \"use Repair\". Required file missing while the capability runs: the \"Working, but an expected file is missing\" copy without the alarm, Repair primary. Required file missing and down: the full-strength copy, Repair primary. No observation fails closed to the full copy. Three failed repair passes: Repair is unavailable and not primary (ghost), no install action is offered, the manual fallback names the URL, file and 12 attempts and never says \"A required component is missing\" (the start of the repair sentence, \"A required component is missing — use Repair below.\"). The healthy-install and optional-only-gap checks of this file have their own entry with evidence; the base expectation that Install stays primary in those cases is retired in its own Obsolete entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B11",
          "quote": "Alerts appear once, above the path, in words with their action: runtime or operation failure with code, download or online-policy denial, install failure, repairable processor fallback, compatibility block, activation or reindex required.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-live-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#repairNeeded ⇒ Repair is the primary action in the alert",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#required file missing but the capability is observably running ⇒ the \"working, but\" copy",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#required file missing and the capability is down ⇒ the full-strength copy is unchanged",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#three failed repair passes ⇒ the manual fallback, not an unqualified Repair button"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts"
      ],
      "class": "New",
      "behaviour": "Replacement expectations for the retired \"Install stays primary\" checks, at the one-page layout with Advanced open. Healthy install (installedFully, no repair needed): Maintenance \"Repair installation\" is a ghost text button, no install action is offered (no button labelled \"Install AI …\" or \"Install additions …\"), and the page does not say \"A required component is missing\". Optional-only gap: deriveRepairRemedy is none and repairRemedySub null, the page does not say \"A required component is missing\", Repair is ghost, no install action is offered, and \"Optional files not installed\" names splade/config.json. These follow a design decision, not a repair, so they are not marked repaired. The evidence also runs them on main (344bdaf03c), where both fail by assertion at the Repair-is-ghost line (expected undefined to be 'ghost'): main keeps Install and Repair in its install accordion, which this harness does not open, so the before-run shows that main's page differs, not main's variant choice (main offered Install as primary and Repair as secondary).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B5",
          "quote": "Switches in step 1 drive only existing behaviour: before install, each optional feature switch selects or declines that package for the install (existing decline/undecline), and the Install button shows the total size of what is selected; after install, Answer questions turns answers on/off (core.set-chat-enabled; files kept). Best match first and Text in scans and images after install show their state with no live switch in step 1 (no control that does nothing); Text in scans and images before install appears only as what it is today (part of the answer model's files or unavailable), not as a separate download. Switches are real switches (role switch, aria-checked, keyboard operable, reachable reason when blocked).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implementing the agreed window designs on existing backend behaviour, AI Brain step 1.",
          "choice": "Install AI is the one teal main action and appears only while AI is not installed (or while a download is running, paused or awaiting activation); once AI is installed there is no Install AI button: missing additions are offered by the secondary text button \"Install additions · size\", and maintenance repair is the text button \"Repair installation\".",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-repair-remedy-main.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#no repair needed ⇒ Maintenance still offers repair without claiming a gap",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts#optional-only gap ⇒ no repair prompt at all, and the gap is still named"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B5",
          "quote": "Switches in step 1 drive only existing behaviour: before install, each optional feature switch selects or declines that package for the install (existing decline/undecline), and the Install button shows the total size of what is selected; after install, Answer questions turns answers on/off (core.set-chat-enabled; files kept). Best match first and Text in scans and images after install show their state with no live switch in step 1 (no control that does nothing); Text in scans and images before install appears only as what it is today (part of the answer model's files or unavailable), not as a separate download. Switches are real switches (role switch, aria-checked, keyboard operable, reachable reason when blocked).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implementing the agreed window designs on existing backend behaviour, AI Brain step 1.",
          "choice": "Install AI is the one teal main action and appears only while AI is not installed (or while a download is running, paused or awaiting activation); once AI is installed there is no Install AI button: missing additions are offered by the secondary text button \"Install additions · size\", and maintenance repair is the text button \"Repair installation\".",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for Install, primary action and repair wording). Retired from main 344bdaf03c BrainSurface.repairRemedy.test.ts: \"no repair needed ⇒ Install stays primary (the pre-existing default is untouched)\" (expect(variants.Install).toBe('primary'), expect(variants.Repair).not.toBe('primary')) and, in \"optional-only gap ⇒ no repair prompt at all\", expect(variants.Install).toBe('primary'). Their replacement expectations (no install action is offered for a healthy install or an optional-only gap, Repair a ghost text button) are the New entry above with evidence gates/test-intent/evidence/brain-repair-remedy-main.json. All Repair-is-primary, copy and manual-fallback expectations remain."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B1",
          "quote": "The AI Brain tab is one page using the whole window, same visual rules as the Library: headline, one sentence with feature phrases, the path of steps, graphics card bar, then text links (Advanced settings, Stop answers / Start answers, Engine history in Health). No card boxes, no outlined pill buttons, no Simple/Detailed toggle on this page (the title-bar toggle stays for other surfaces). Member tabs (AI Brain, Memory) and their keyboard semantics stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B5",
          "quote": "Switches in step 1 drive only existing behaviour: before install, each optional feature switch selects or declines that package for the install (existing decline/undecline), and the Install button shows the total size of what is selected; after install, Answer questions turns answers on/off (core.set-chat-enabled; files kept). Best match first and Text in scans and images after install show their state with no live switch in step 1 (no control that does nothing); Text in scans and images before install appears only as what it is today (part of the answer model's files or unavailable), not as a separate download. Switches are real switches (role switch, aria-checked, keyboard operable, reachable reason when blocked).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q2",
          "quote": "Turning answers off keeps files and does not stop picture reading or other background work that needs the engine; turning on again works without reinstall.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B15",
          "quote": "(challenger 2) The answer switch shows the saved intent (nullable chatEnabled: unset stays distinct from explicit off); the sentence explains current availability, including answers off while background work continues and answers temporarily unavailable while pictures are being read, and their completion.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "Searched docs/reference and docs/decisions for core.switch-inference-mode and Online/Indexing/Reload controls; no conflicting decision found. The brief explicitly replaces runtime modes with saved answer intent and permits rewritten removed presentation tests. Only the separate Online and Indexing presentation controls leave the original three-control test: B1 names Start answers / Stop answers; B5 defines their core.set-chat-enabled behavior; B9 retains Restart AI (formerly Reload). The no-offline-queue and positive live witness assertions remain at Restart AI, a new check pins the same non-queuing for the Start/Stop answers control, and the new D2 regression gates every remaining feature switch with its reachable reason. No backend operation, persistence contract or health observation is removed. Also retired from main 344bdaf03c BrainSurface.snapshotLiveness.test.ts, status card sentence (807 Part A): (a) the \"Online\" status word, expect(dom.textContent).not.toContain('Online') for the dead panel and toContain('Online') for the live one, and (b) the status dot, expect(dot?.getAttribute('tone')).not.toBe('success') dead and toBe('success') live. The page has no status word and no status dot (B1, B2: the headline comes from the shared verdict); the same guarantee is carried by the headline (\"AI is ready\" only when live) and by the Answer questions step state (\"On\" only when live, \"Unknown — checking AI status\" when dead, from the same retained on-observation). (c) expect(action?.label).not.toBe('Shut Down AI'): the action is now Start/Stop answers and its label follows the saved answer choice (B15), so with a saved \"on\" choice a dead backend correctly names \"Stop answers\"; the guarantee that no action presupposing a live backend is offered is carried by its availability, exactly unavailable with \"Checking AI status before changing features.\" when dead and available when live. Conflict search for (a) to (c): docs/decisions searched for status dot, Online status and Shut Down AI; none.",
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Legacy direct-render and activation harnesses explicitly supply the successful live install/verdict observations their scenarios already assume, so they pass through the page-wide observation gate. The GPU fixture supplies the same numeric total/used sample at the actual /api/status.gpu endpoint fields instead of operating.gpu; the adapter fixture stays at operating.gpu. Every existing assertion, refusal injection, retention/activation/consent outcome, saved-choice check and lifecycle operation argument is retained. The snapshot-liveness harness supplies the observed successful install alongside its existing live/dead witness, retaining every positive/negative and no-replay assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B7",
          "quote": "Graphics card bar: one bar with device memory in use of total (all apps, not attributed to JustSearch), the adapter name only where operating.gpu reports it; no per-feature split; several devices named as such. Absent or stale GPU data says unknown, never 0. (challenger 9)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q5",
          "quote": "Existing Brain behaviour tests keep asserting the same behaviour after selector and wording updates; tests of the removed Simple/Detailed toggle and telemetry placement are replaced by tests of the new placement; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "D1–D10 live redesign repairs: one confirmed-live page gate and installed repair wording with saved answer intent; passive preview reads never block real switch clicks or overwrite newer saves; sliders keep focus and drafts, serialize/coalesce the latest committed change, stop on conflict and distinguish Automatic-at-maximum from zero; all-app status GPU memory (device-zero semantics repaired separately in G5); sentence word boundaries; single-gesture panel handoff without focus scrolling; cancelled package bytes; the picture branch takes layout space; repair copy points to existing locations. Geometry and native keyboard behavior are additionally exercised in Chromium across viewport sizes, themes, forced colors and 200% reflow.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B3",
          "quote": "The sentence is generated from real state for every combination of install state and switch state; it names only what is true now (for example \"It doesn't read text in scans and images yet\" only when that is off or missing) and ends with where it runs. Feature phrases carry their switch inline where a switch exists; the same switch appears at the step in the path, and both always show the same state and act the same.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B6",
          "quote": "Selecting a feature (its phrase, its step or its switch's info affordance) opens one small panel at that step: \"You get\" and \"It costs\" in plain words, with only known numbers (download size, on-disk size, that it uses graphics memory while working); no made-up per-feature memory figures. Escape and outside click close it, focus returns; only one panel at a time.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B7",
          "quote": "Graphics card bar: one bar with device memory in use of total (all apps, not attributed to JustSearch), the adapter name only where operating.gpu reports it; no per-feature split; several devices named as such. Absent or stale GPU data says unknown, never 0. (challenger 9)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B15",
          "quote": "(challenger 2) The answer switch shows the saved intent (nullable chatEnabled: unset stays distinct from explicit off); the sentence explains current availability, including answers off while background work continues and answers temporarily unavailable while pictures are being read, and their completion.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B18",
          "quote": "(challenger 5) Selection and install races: while a choice is saving, consent is open, or a download is running or paused, feature switches that would change the plan are blocked with a reachable reason (\"Changes apply after this download\"); a late decline response never overwrites a newer choice; a choice made in another window shows here. Consent lists exactly the set that will download. Known limit: the backend start does not bind consent to the previewed set (atomic binding is backend work, deferred).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B20",
          "quote": "(challenger 10, 11, broken today) Runtime variant controls read the endpoint's real fields (installedVariants, active.activeVariantId) and work with an installed inactive variant; graphics card use distinguishes Automatic (null) from 0 layers (processor only); sliders keep untouched values, save once per change (not per drag step), are keyboard operable and handle a conflict response. Opening Advanced never writes the global ui.mode; the page is the same under either global mode.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q7",
          "quote": "(challenger 13) Duplicated switches (sentence and path) have distinct ids, one shared busy state and the same accessible name pattern; opening a feature's information never toggles it; at 600x400 and 200% zoom the panel scrolls, long failure and path text wraps, and focus goes somewhere sensible when the opener disappears after an install transition.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window design, step 1, including the specified live-check redesign repairs.",
          "choice": "Use one observation gate; keep passive previews out of user-choice locks; serialize the latest slider intent while keeping focus; place Automatic at the GPU slider maximum; use /api/status.gpu for total all-app memory; keep the picture branch in normal grid flow and hand panel focus over without scrolling.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-live-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D2 the whole page waits for live install and verdict: no observation",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D2 the whole page waits for live install and verdict: retained negative",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D2 the whole page waits for live install and verdict: unconfirmed verdict",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D6 repair history is installed and explicit answers-off stays off with Repair as primary",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D8 a pending idle preview does not block the real answer buttons and cannot overwrite a newer choice",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D8 a preview begun before an acquisition save cannot overwrite its new choices",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D10 Automatic GPU layers starts at maximum so the left endpoint can save processor-only zero",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D10 sliders retain focus and serialize the latest changed value during a pending witnessed save",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D10 a conflict stops queued slider saves and asks for review without changing the saved value",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D1 sentence boundaries keep whitespace outside phrase buttons and punctuation attached to preceding prose",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D3 a pointer gesture on another step opens it in one click without restoring the previous opener or scrolling focus",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D4 cancelled steps show retained bytes even when the last package record still says downloading",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D7 the picture branch participates in path layout instead of escaping into preceding alerts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D9 repair instructions name the visible page locations"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "G1–G5 review repairs: failed reads independently invalidate retained state while origin stays reachable; successful observations have no age, with the page gate also requiring app-wide snapshotLive; Advanced labels retained observations; a selected missing answer package uses runtime intent without package declines or download consent while Repair owns acquisition; passive previews finish before another passive batch starts and do not lock choices; graphics memory is device zero, one of N devices, never an aggregate. B14 declined-package consent remains asserted unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B5",
          "quote": "Switches in step 1 drive only existing behaviour: before install, each optional feature switch selects or declines that package for the install (existing decline/undecline), and the Install button shows the total size of what is selected; after install, Answer questions turns answers on/off (core.set-chat-enabled; files kept). Best match first and Text in scans and images after install show their state with no live switch in step 1 (no control that does nothing); Text in scans and images before install appears only as what it is today (part of the answer model's files or unavailable), not as a separate download. Switches are real switches (role switch, aria-checked, keyboard operable, reachable reason when blocked).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B7",
          "quote": "Graphics card bar: one bar with device memory in use of total (all apps, not attributed to JustSearch), the adapter name only where operating.gpu reports it; no per-feature split; several devices named as such. Absent or stale GPU data says unknown, never 0. (challenger 9)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B9",
          "quote": "Advanced settings expands in place: Answers (answer model with Change, reading memory, longest answer as sliders with values), Graphics card (graphics card use in layers, graphics card support with Other options, engine program with Use another), Downloaded files (each installed component's known size, \"in use\" only where the runtime observes it; Import an offline package), Maintenance (Restart AI, Repair installation, Rebuild search index). Every control keeps today's behaviour: witnessed settings saves with conflict handling, reload, runtime variant activation, pack preflight/import (desktop only), rebuild consent. \"Pause answers while indexing\" and \"Remove files\" are not shown in step 1.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B14",
          "quote": "(challenger 1) An installed AI system is not an installed feature: when a feature's package was declined at install (answers, best match first), switching it on offers the existing download consent for that package with its size and licence, then downloads it; it does not merely write intent. After a restart the choice and files hold.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window redesign, step 1.",
          "choice": "freshness narrowed to failed-read plus app-wide liveness: latest completed successful install/runtime reads count while snapshotLive holds; a failed read invalidates immediately; pending reads and admission waits have no independent freshness bound. Remove the age bound, unanswered-read tracking and freshness timers (task t-20261101-uiimpl).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-review-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G1 failed install reads override a reachable origin and settled verdict",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G1 failed runtime reads override a reachable origin and settled verdict",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G2 Advanced shares the page gate for connecting observations",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G2 Advanced shares the page gate for missing install observations",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G3 selected missing answer files use saved intent while Repair owns acquisition",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G4 passive previews complete before another batch starts and converge other windows without locking choices",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#G5 Linux memory uses device zero status GPU figures and labels one of several devices",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts#G1 a failed install read invalidates retained observations even with other endpoints reachable",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts#G1 a failed runtime read invalidates retained observations even with other endpoints reachable"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecoveryPolling.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.automatic-vdu.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.chat-intent.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.componentList.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installProgressAggregate.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.pausedInstall.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRemedy.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.sliceB.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts",
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "Successful-observation fixtures explicitly supply the install/runtime poll owners’ freshness flags. Existing expected outcomes, race injections and operation oracles remain unchanged. aiInstallPoll lifecycle snapshot equality includes completed-read outcomes; StatusDeck’s complete AiState factory supplies false for absent observations. The new G checks and corrected G5 device-zero assertion are separately warranted as repaired behavior. desktopRecoveryPolling.test.ts differs from main only by a comment beside its unchanged four-timer expectation; all five requests aborted, no later requests and zero timers after teardown remain asserted.",
      "newChecks": []
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "H1/H2: successful delayed polls preserve progress controls and keyboard focus, and either response order and both cadence transitions keep successful observations live. These checks fail by assertion on 1b5ceea94b, a candidate state inside this change. The longer admission-wait regression is recorded separately.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window redesign, step 1.",
          "choice": "freshness narrowed to failed-read plus app-wide liveness: latest completed successful install/runtime reads count while snapshotLive holds; a failed read invalidates immediately; pending reads and admission waits have no independent freshness bound. Remove the age bound, unanswered-read tracking and freshness timers (task t-20261101-uiimpl).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-freshness-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H1 delayed successful download polls preserve the same Pause control and keyboard focus",
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H2 reversed replies and both cadence transitions keep successful observations live: install first",
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H2 reversed replies and both cadence transitions keep successful observations live: runtime first"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "H3 preservation: either failed endpoint immediately closes the observation gate despite reachable origin, retains history, and recovers after a successful retry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-freshness-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H3 a failed install read closes the gate immediately and a successful retry recovers",
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H3 a failed runtime read closes the gate immediately and a successful retry recovers"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "H5: a server-directed 30-second admission wait does not close the observation gate while the app-wide snapshot stays live, and the successful retry preserves it. This admission-wait regression fails by assertion on 234d87ca0b, whose 15-second age bound invalidates the retained runtime observation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain window redesign, step 1.",
          "choice": "freshness narrowed to failed-read plus app-wide liveness: latest completed successful install/runtime reads count while snapshotLive holds; a failed read invalidates immediately; pending reads and admission waits have no independent freshness bound. Remove the age bound, unanswered-read tracking and freshness timers (task t-20261101-uiimpl).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-freshness-narrowing.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.observationFreshness.test.ts#H5 a directed admission wait longer than 15 seconds and its successful retry keep the gate open"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "V12: after a cancelled run only steps whose files are absent and that downloaded bytes say 'Cancelled · N kept'; present, pending and skipped packages keep their real state, and no step says Downloading unless the run is running (cancelled, failed or completed runs with a leftover downloading record) (a zero-byte interruption reads Not downloaded; a present package keeps its observed On/Off). The repair alert carries the 'A required component is missing' remedy once; the status line does not repeat it. These checks fail by assertion on 2c7f1e8d99 (live check verifier-12: 'Cancelled · 0 B kept' on an active reranker and installed answers; the remedy shown twice).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B4",
          "quote": "Path: Your files (file count from status), Read the text (built in), Understand meaning (needed, its size, no switch), Best match first, Answer questions (model name and size when installed), with Text in scans and images as a branch off Read the text. Each step shows its known size (registry download/footprint size, never claimed as measured disk use) and its state in words (on, off, not downloaded, downloading x%, unavailable on this computer with the reason).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B11",
          "quote": "Alerts appear once, above the path, in words with their action: runtime or operation failure with code, download or online-policy denial, install failure, repairable processor fallback, compatibility block, activation or reindex required.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-live-check-2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D4 after a cancelled run, steps whose files are present or that never downloaded are not labelled cancelled",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#B11 the repair remedy appears once, in the repair alert, not repeated by the status line",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D4 a cancelled run never leaves a step saying Downloading: zero-byte interruptions are not downloaded, present files keep their observed state",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D4 a failed run with a package record left downloading shows no step as Downloading",
        "modules/ui-web/src/shell-v0/views/BrainSurface.liveRepairs.test.ts#D4 a completed run with a package record left downloading shows no step as Downloading"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Running-install presentation after a shared store observation: packages queued after planning wait, and missing runtime observations, second windows and remount retain live download controls.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B4",
          "quote": "Path: Your files (file count from status), Read the text (built in), Understand meaning (needed, its size, no switch), Best match first, Answer questions (model name and size when installed), with Text in scans and images as a branch off Read the text. Each step shows its known size (registry download/footprint size, never claimed as measured disk use) and its state in words (on, off, not downloaded, downloading x%, unavailable on this computer with the reason).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain redesign and repair its review defects.",
          "choice": "Step wording after planning: 'Waiting to download' for a package queued in the current run instead of a stale percentage or 'On' (task t-20261101-uiimpl). Planning uses the run headline and each step’s own state under the later narrowing decision.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-running-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a pending package in the current run waits instead of claiming On from the old runtime",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#an additions run keeps controls through a missing runtime observation, a second window and remount"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A late Repair response never replaces the store install status with an older timestamp; a failed install read gates a retained running download or plan observation and recovery restores it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B4",
          "quote": "Path: Your files (file count from status), Read the text (built in), Understand meaning (needed, its size, no switch), Best match first, Answer questions (model name and size when installed), with Text in scans and images as a branch off Read the text. Each step shows its known size (registry download/footprint size, never claimed as measured disk use) and its state in words (on, off, not downloaded, downloading x%, unavailable on this computer with the reason).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-running-observation-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a late Repair response never replaces the store install status with an older timestamp",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a failed install read gates a retained running download observation and recovery restores it",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a failed install read gates a retained running plan observation and recovery restores it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "New",
      "behaviour": "A runtime-only read failure preserves current install progress and controls when the install observation remains live. This preservation check passes before and after the repair.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-running-runtime-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a runtime-only read failure keeps live install progress and controls"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "During preflight/plan no step says Preparing download, Waiting to download or Downloading, and the headline is Installing… without a percentage. Previous percentages and ready stages remain suppressed. Retained downloading records for declined, unavailable and not-in-mode packages stay ignored. When plan preview excludes an unavailable answer runtime but complete answer files are present, the step keeps its observed Off state; neither that step nor an unavailable one claims preparation or download activity (\"Preparing download\", \"Waiting to download\" or \"Downloading\").",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B4",
          "quote": "Path: Your files (file count from status), Read the text (built in), Understand meaning (needed, its size, no switch), Best match first, Answer questions (model name and size when installed), with Text in scans and images as a branch off Read the text. Each step shows its known size (registry download/footprint size, never claimed as measured disk use) and its state in words (on, off, not downloaded, downloading x%, unavailable on this computer with the reason).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain redesign and repair its review defects.",
          "choice": "Planning-label narrowing decision in the builder brief for 591a7ad461: no step ever says 'Preparing download'. Delete that branch of the state chain; while a run is planned, the existing run-level 'Installing…' headline without a percentage carries it and each step shows its own observed state. Keep package progress records ignored during preflight/plan and keep the existing meaning of unavailable, including when answer files are present.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-running-ties-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a new Repair in preflight never displays the previous run percentage or ready stages",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a new Repair in plan never displays the previous run percentage or ready stages",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#while a Repair run is being planned, a package unavailable on this computer is not said to be preparing",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#planning ignores retained downloading records for an excluded declined package",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#planning ignores retained downloading records for an excluded unavailable package",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#planning ignores retained downloading records for an excluded not-in-mode package",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#planning an unavailable answer runtime with complete answer files keeps the observed step state"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Only the shared store changes install presentation after operations. Successful Repair/Pause/Resume/Cancel request a read at once, without timestamp arbitration or an exception to failed-read gating. Previous observations remain until the shared read arrives. Refusal and retained-byte guarantees stay at their existing owners.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain redesign and repair its review defects.",
          "choice": "Final narrowing decision in this builder brief: remove retained operation responses and all identity/timestamp arbitration from BrainSurface; aiInstallPoll via aiStateStore alone owns install observations and the live install gate. A successful start, repair, pause, resume or cancel requests refresh now without awaiting it in the busy state. The previous state may remain visible between the operation reply and that read. Keep the round-3 planning rule unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-install-store-authority.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#Repair requests a read immediately and only store observations change progress, Pause, Resume and Cancel",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#Repair does not override unchanged store notifications while its requested read is pending",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#Pause requests an immediate read and waits for the store even at an equal timestamp",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#Resume requests an immediate read and waits for the store even at an equal timestamp",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#the shared store alone decides which install observation follows Pause regardless of timestamp",
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts#a successful Repair cannot reopen a failed install observation gate before recovery"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts"
      ],
      "class": "New",
      "behaviour": "Contract of the new immediate-read helper: refresh immediately or share one queued batch after the active batch; never overlap endpoint reads; continue the regular schedule; no-op with no subscribers; cancelled queued reads cannot affect a replacement lifecycle. Not claimed as a repair: the helper does not exist before this change, so its before-state runs fail only because it is absent; the caller-level repair and its fail-before are the next entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain redesign and repair its review defects.",
          "choice": "Final narrowing decision in this builder brief: remove retained operation responses and all identity/timestamp arbitration from BrainSurface; aiInstallPoll via aiStateStore alone owns install observations and the live install gate. A successful start, repair, pause, resume or cancel requests refresh now without awaiting it in the busy state. The previous state may remain visible between the operation reply and that read. Keep the round-3 planning rule unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-install-refresh-now.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts#reads immediately between ticks and resumes the regular schedule",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts#shares one queued read among concurrent callers without overlapping any endpoint",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts#does nothing without subscribers and drops queued reads when the last subscriber leaves",
        "modules/ui-web/src/shell-v0/utils/aiInstallPoll.refresh.test.ts#an obsolete queued read cannot refresh a replacement API base"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real aiInstallPoll/aiStateStore with fetch mocked: successful Pause/Resume followed by failed install reads hide current-tense progress and Pause/Resume/Cancel until recovery, including other windows/remount. Separate checks prove immediate read requests, store-only presentation and actual button busy release during a hung read.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B2",
          "quote": "Headline from the shared AI verdict for every lifecycle state it decides today: not installed, download paused/stopped, installing (with progress), install failed, starting/recovering, connecting/unconfirmed, ready, running on the processor instead of the graphics card, activation or reindex required. While status is unknown it says so and never claims ready.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B8",
          "quote": "Install: the Install AI button shows the selected total size and opens the existing licence/size consent; downloading shows progress, pause/resume, cancel with retained bytes, stalled and blocked stages, and search ready before completion, in the same page (no nested panel). Repair, retry after failure and the working-but-missing / manual fallback stay reachable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "B13",
          "quote": "Live behaviour: status, install progress and switch states update without collapsing open panels or Advanced settings, without losing a slider draft; package previews and LLM settings refresh when install state changes and when the window regains focus, ignoring out-of-order responses, so a second window converges; reload or tab switch mid-install keeps the progress and outcome. (challenger 7)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "Q3",
          "quote": "Settings saves keep witnesses and conflict review; offline runtime mutations are never queued; unknown/last-observed values stay labelled.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl, user turn after the WP1 summary",
          "delegationScope": "Implement the agreed AI Brain redesign and repair its review defects.",
          "choice": "Final narrowing decision in this builder brief: remove retained operation responses and all identity/timestamp arbitration from BrainSurface; aiInstallPoll via aiStateStore alone owns install observations and the live install gate. A successful start, repair, pause, resume or cancel requests refresh now without awaiting it in the busy state. The previous state may remain visible between the operation reply and that read. Keep the round-3 planning rule unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/brain-install-operation-observations.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts#Pause waits for the requested store read and releases busy state while it hangs",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts#Resume waits for the requested store read and releases busy state while it hangs",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts#Pause success then failed install reads close the gate until recovery across windows and remount",
        "modules/ui-web/src/shell-v0/views/BrainSurface.installOperations.test.ts#Resume success then failed install reads close the gate until recovery across windows and remount"
      ]
    }
  ]
}
```

Repair of the reviewer-25 audit (reject of digest 2abb2beb): checks on renamed labels now read the
current labels ("AI isn't installed yet", "Stop answers", install action by its "Install AI" /
"Install additions" prefix); the retired "Install stays primary" and page-toggle label expectations
have Obsolete entries; the superseded slice A telemetry entry is removed and the telemetry,
component-list and sparkline entries describe the final tests; the B20 graphics-card and variant
repairs carry fail-before evidence on 344bdaf03c; a Start/Stop answers offline non-queuing check is
added; Obsolete and Adaptation entries for expectations that never existed at main are removed, and
desktopRecoveryPolling is recorded as a comment-only change.

Repair of the verifier-16 acceptance and reviewer-26 audit (rejects of digest a7f0b419). repairRemedy: the
manual fallback must not say "A required component is missing" (the live sentence ends "use Repair below.");
the healthy-install and optional-only-gap replacement checks have their own entry and evidence, run also on
344bdaf03c. snapshotLiveness: the status-panel fixture carries a read, saved "on" answer choice; the dead panel's
answers action is "Stop answers" and unavailable with the liveness reason, the live one available; the dead
"Online" check is replaced by the Answer questions step state; the retired label, "Online" and status-dot
expectations are recorded in the Obsolete entry. B20: both checks are marked repaired, with assertion fail-before evidence through main’s existing Runtime controls using the before-only compatibility patch.
Sweep of the Brain test files changed against 344bdaf03c for negatives on text no product code contains: the
Health telemetry check "Recent mode transitions" is removed (the base's Brain-panel absence check is the
Brain no-render check), the Advanced ".accordion-header" absence check becomes a presence check of the three
sliders, and the two repairRun planning negatives add the current download words to "Preparing download" (the
defect wording the 591a7ad461 repair removed, kept as its fail-before oracle). Evidence the edits staled is
regenerated with each entry's --before; brain-main-repairs is regenerated with --before 344bdaf03c and --before-patch gates/test-intent/evidence/brain-b20-main-controls.patch.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-15c",
  "verdict": "accept",
  "digest": "sha256:2abb2bebdf36b2a7f05ee8c9ceb900e124b69f57b5333384fefe21193c03a0b7",
  "note": "Verdict of independent verifier session verifier-15c (live recheck of 4cc6dfb89f, digest sha256:2abb2beb...a0b7, all entries accepted); recorded by the orchestrator, who transcribed it, after a clean rebase that left the digest unchanged."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-25",
  "verdict": "reject",
  "digest": "sha256:2abb2bebdf36b2a7f05ee8c9ceb900e124b69f57b5333384fefe21193c03a0b7",
  "note": "Audit by independent reviewer session reviewer-25 (Claude): reject. M1 assertions on old labels can no longer fail (repairRemedy Install checks, pausedInstall 'Not Installed', snapshotLiveness 'Shut Down AI'); M2 superseded entry descriptions (componentList, telemetry slice A, sparkline); M3 B20 repairs recorded without fail-before. Recorded by the orchestrator, who transcribed it."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-16",
  "verdict": "reject",
  "digest": "sha256:a7f0b419fc3f63764386fddadca6bfdb27fa76cdbb66bdd4b7ecb9162640040d",
  "note": "Acceptance by independent verifier session verifier-16 (Claude): reject. F1 repairRemedy:237 matches retired text 'use Repair.'; F2 snapshotLiveness:359 'Online' cannot fail. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-26",
  "verdict": "reject",
  "digest": "sha256:a7f0b419fc3f63764386fddadca6bfdb27fa76cdbb66bdd4b7ecb9162640040d",
  "note": "Audit by independent reviewer session reviewer-26 (Claude): reject. M1 snapshotLiveness label/offline checks pass for the wrong reason; M2 B20 fail-before shows missing controls, not the defect; M3 repairRemedy replacement expectations lack evidence. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-28",
  "verdict": "reject",
  "digest": "sha256:b7c6db00aace06f8578db2ef4c7598751a89cc734a1fb554b1218b1a430f8537",
  "note": "Audit by independent reviewer session reviewer-28 (Codex): reject only entry 34 (aiInstallPoll.refresh.test.ts marked repaired; its before-state fails because the helper is absent); all other entries accepted. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-18c",
  "verdict": "accept",
  "digest": "sha256:8daa604994186e47d94bb3716acda31599c62b66d6482c9bada571ebb2c19e0d",
  "note": "Acceptance by independent verifier session verifier-18c (Codex): accept, all entries; reproduced both B20 failures on main. Transcribed by the orchestrator (Codex sandbox cannot run accept)."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-30",
  "verdict": "accept",
  "digest": "sha256:8daa604994186e47d94bb3716acda31599c62b66d6482c9bada571ebb2c19e0d",
  "note": "Audit by independent reviewer session reviewer-30 (Codex): accept, no must-fix. Transcribed by the orchestrator."
}
```
