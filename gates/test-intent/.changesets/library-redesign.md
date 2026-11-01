---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: builder-uiimpl-lib-20261101
---

# Test-intent entries

Structural Library redesign: classify the changed tests by oracle class in one pass against the
agent-drafted, owner-adopted scenarios. Adaptation entries preserve the prior oracle; New entries
name their added or intentionally relocated expectations. The preserved portions of mixed files
are described explicitly. No test is deleted, skipped or disabled.

Entries 18 and 19 cite the earlier S33.4 expectation verbatim from
`gates/test-intent/.changesets/uiux-status.md:60`. The age bound is an agent choice under the
owner's quoted delegation, not owner wording or the orchestrator's round decision.

Test ledger (all entries are durable product checks):

- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.transitions.test.ts — L2, L10 — shared catching-up/restart wording, empty roots and recovery, real migration preservation — durable

- Tests added or rewritten: modules/ui-web/src/shell-v0/chrome/Shell.test.ts — L14, P9 — navigation/history and recovery — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts — L14 — indexing-rules route — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts — L13, L12 — folder switching and late replies — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/IndexingControls.toolbar.test.ts — L1, L15, P5 — pause durations and combined holds — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/IndexingPause.test.ts — P5 — recovery control selector — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/StatusDeck.test.ts — L2 — energy-wait wording — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/SurfaceTabs.test.ts — L1 — actions slot and tab keyboard semantics — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts — L2, L10 — empty and unknown-state headlines — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts — L2, L4 — status wording — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts — L2, L4 — status wording and shared row projection — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.removal.test.ts — P8, L5 — expanded row removal — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.test.ts — P8, L5 — expanded row removal intent — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts — P5 — energy-wait wording — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/state/indexingStatus.test.ts — L2 — indexing activity wording — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts — P4, P6 — accept-gaps label — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts — L2 — shared activity wording — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.addCollection.test.ts — P1 — Add folder label — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.bulkOutcome.test.ts — P4, P6 — accept-gaps label — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts — L7 — visible override and line-input description — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts — L7 — line-input/review selectors and transport identity — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.failedChip.test.ts — L16 — last-known failure state selector — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts — L12, P1, P2 — pending Add across member tabs — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts — P6 — readable gap reasons and accept label — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts — L2, L5, L16, L20, D1, V1 — headline honesty and stability, disclosure focus and shared row parity — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts — L2, L4, L5, L10 — moved heading and row facts — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts — L7 — retained-base settings transport and line inputs — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts — L14 — delayed load, layout settling and user focus — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts — L12, L14, L19, B, V3 — focus requests, witnessed saves, external conflicts — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts — L8, L14, L15 — moved editor and remedies — durable

Continuation at 7c124c7c22 (all durable):

- Tests added or rewritten: modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts — L2 shared read-cadence bound, frozen samples, receipt-age fallback — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts — liveness expiry on every shared surface — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts — retained migration and unknown-state liveness matrix — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts — cross-surface liveness matrix — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts — remaining surface liveness matrix — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts — L2 lost-contact versus real rebuild, L16 retained-failure expiry — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts — recovery confirmation and focus survive expired status — durable
- Tests added or rewritten: modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts — L14 in view after layout settles and nonvacuous focus cancellation — durable

The continuation's evidence runs use a separate shared clone under the session temporary directory.
Its runner-owned before-worktrees and copied dependencies never modify the source repository.
Entry 17's mutant removes only the arrival scroll's active-element guard; the strengthened check
fails on the extra scroll call while it passes with the guard. Entry 16 asserts layout settling,
not an exact one-second product requirement. No test is skipped or deleted.

Execution evidence was produced by the repository's unmodified run-evidence.mjs in a shared clone
inside the session temporary directory, with its own copied dependencies. Its detached before
checkouts belong to that clone and do not write into the source repository. VITEST_MAX_WORKERS=4.
Failed-files repair compares with 08a63452b2; the verifier's V1/V3 repairs compare with 7c9a5fe9ce. Round-3 B/C checks compare with d19aa236c7. D1 repairs compare with 9e08da1084. V2 geometry is exercised in real Chromium by jseval arrival steps; happy-dom only checks focus intent delivery.
Browser viewport and actual native-shell verification are independent of these unit checks.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts",
        "modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.removal.test.ts",
        "modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.test.ts",
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.addCollection.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bulkOutcome.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.failedChip.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.settings-attempt.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "split",
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Pause recovery selects the non-toolbar remedy; row Remove is reached by opening its disclosure and selecting Remove from library; Add Folder is now Add folder; accept-gaps is now Use the new index without them, with the same exact gap reason expressed in words. Skip rules moved from one newline-separated textarea to individual line inputs; saved-pattern review selects its named details element. Instrumentation moved: settings-request counts select the original witnessed/AbortSignal transport, excluding the new editor's independent settings read. Announcements select the surface action status after the toolbar gained a status node; failed metadata selects the folder state containing the same retry/rebuild qualifiers. Inputs, preconditions, observation, fault injection, expected outcome, tags and conditions are kept; only presentation selectors, control encoding and wording moved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L1",
          "quote": "The Folders tab is one page using the whole window: headline, one summary line, attention items (when any), folder list, skip rules and indexing times, then one-line links to other apps' documents and Activity. Window actions sit beside the member tabs (Folders, Browse): Pause indexing with until-I-resume / 15 min / 1 h, a \u22ef menu with Reindex everything and Process pending work, and a filled Add folder. No card boxes or outlined pill buttons. Tab semantics, keyboard navigation and focus restoration of SurfaceTabs stay intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L5",
          "quote": "A row expands in place (button with aria-expanded, keyboard operable; several may be open): collection, searchable-by-words verdict, meaning processing percent worded as progress (not coverage), indexed X ago, verified X ago, unreadable count opening the failed-files list, and actions Open in Explorer and Remove from library. Open rows stay open through live refreshes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L7",
          "quote": "Skip rules edited on the page, one per line with Add a rule, keeping every existing exclude-pattern behaviour: explicit save and reload, uncertain-save retention, conflict review, preview, typed-consent apply, admin/env override notice, partial results, exact-path keep-outs with remove.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "Add folder: picker or typed path, collection, preview, refusal of missing, non-folder, duplicate, overlapping and reserved-collection entries.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Remove: nested and enclosing-root confirmation, overlapping-removal suppression, lost-response reconcile, retry, focus restore.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P4",
          "quote": "Reindex everything and process pending work, with operation-history outcomes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P5",
          "quote": "Pause/resume with durations; schedule and battery holds shown; settings-recovery path.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P6",
          "quote": "Rebuild decision: accept gaps / cancel with the exact gap list and recorded outcomes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P8",
          "quote": "A themed presentation that replaces the folder rows still receives the existing fields and its remove / show-failed intents still work (parity of the new rows is L20).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts",
        "modules/ui-web/src/shell-v0/state/indexingStatus.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "split",
        "input re-encoding"
      ],
      "whatMoved": "The distinct energy and GPU summary labels are preserved as Meaning processing is waiting to save energy / Meaning processing is waiting for the GPU; exact status-deck chip and selector expectations keep those causes separate; save energy becomes saves energy; sparse enrichment becomes some meaning processing. The same reason matrices, freshness transitions, no-active-progress, aggregate, announcements and ready-versus-enriching outcomes remain. The Library sweep calls renderCardsRegion after renderCard was retired, with the original root as its single input. Inputs, preconditions, observation, fault injection, expected outcome, tags and conditions are kept; only presentation selectors, control encoding and wording moved. Library alone states the energy cause once using the detail sentence, replacing the duplicate short summary; the cross-surface check retains the cause and adds an exact once-only assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L4",
          "quote": "Folder row: name, path, one status in words from folderStatus for every state it decides today (removal incomplete, provisional/unknown, unavailable, walk failure, indexing/waiting manual/rules/retry, scanning, failed jobs, deletion unverified, enriching, keyword-only/blocked, ready, no indexable files), a thin progress bar when the state has a fraction, and the file count labelled as files found. Last-known and unknown qualifiers kept.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "split",
        "input re-encoding"
      ],
      "whatMoved": "The true-empty state is observed in the add-a-folder headline. Word-search readiness, enrichment progress, last-indexed time and terminal Up to date status now occupy the shared row's summary and expanded facts; tests open the disclosure and read those locations. The existing incomplete-versus-settled distinction is retained, with an explicit nonterminal assertion for incomplete meaning processing. Inputs, preconditions, observation, fault injection, expected outcome, tags and conditions are kept; only presentation selectors, control encoding and wording moved.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L4",
          "quote": "Folder row: name, path, one status in words from folderStatus for every state it decides today (removal incomplete, provisional/unknown, unavailable, walk failure, indexing/waiting manual/rules/retry, scanning, failed jobs, deletion unverified, enriching, keyword-only/blocked, ready, no indexable files), a thin progress bar when the state has a fraction, and the file count labelled as files found. Last-known and unknown qualifiers kept.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L5",
          "quote": "A row expands in place (button with aria-expanded, keyboard operable; several may be open): collection, searchable-by-words verdict, meaning processing percent worded as progress (not coverage), indexed X ago, verified X ago, unreadable count opening the failed-files list, and actions Open in Explorer and Remove from library. Open rows stay open through live refreshes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L10",
          "quote": "Empty and rebuilding: no folders shows an add-a-folder headline and Add folder; rebuilding with no folders shown says folders will reappear. Browser mode: typed path entry for Add folder and one quiet note, not a banner.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts",
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts"
      ],
      "class": "New",
      "behaviour": "L8/L14 relocate the rule editor, command-palette entry and hours/battery remedies from Settings to Library, also from Browse. Shell navigation consumes focus once, excludes focus from history, and preserves plain Back/Forward destinations; legacy rule-focus navigation redirects to Library while recovery remains in Settings. Existing validation, rule-pair payloads, consent, disabled schedule retention, battery-only null schedule, busy/read-only behavior, manual-only Resume and combined-hold assertions are retained at the extracted editor/shared controls. Checkbox-off input is re-encoded through the Any time radio. Shell energy wording alone changes to saves energy.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L8",
          "quote": "When to index is edited on the Library page only: any time, or only between two times on chosen weekdays (overnight allowed), and pause while on battery; written through core.set-indexing-rules with the existing validation, draft, busy, read-only and conflict handling. Settings no longer edits them and shows a link to this section instead. Saved meaning is unchanged: an absent schedule stays absent (battery-only saves keep schedule null), a disabled schedule keeps its days and times, and the battery preference, manual-pause deadline and recovery reason survive saves and restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L14",
          "quote": "Every route to the moved controls lands on the Library's When-to-index section with keyboard focus: Change hours / Change battery from the Tasks panel and shared indexing controls, the command palette, including when the Browse tab is selected. Settings recovery still leads to Settings, Reset to defaults, and a rule save never clears that hold.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L15",
          "quote": "Combined holds: manual pause, schedule and battery at the same time (including pausing and an empty queue) are all named; Resume clears only the manual pause and says what still holds indexing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P5",
          "quote": "Pause/resume with durations; schedule and battery holds shown; settings-recovery path.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P9",
          "quote": "Deep links to the Library and to its Browse member tab land on the right tab.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts",
        "modules/ui-web/src/shell-v0/commands/CommandPaletteProjection.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/SurfaceTabs.test.ts"
      ],
      "class": "New",
      "behaviour": "The actions slot is beside, outside the tablist, does not gain tab semantics, and leaves ArrowRight tab navigation and focus intact. Existing tab checks remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L1",
          "quote": "The Folders tab is one page using the whole window: headline, one summary line, attention items (when any), folder list, skip rules and indexing times, then one-line links to other apps' documents and Activity. Window actions sit beside the member tabs (Folders, Browse): Pause indexing with until-I-resume / 15 min / 1 h, a \u22ef menu with Reindex everything and Process pending work, and a filled Add folder. No card boxes or outlined pill buttons. Tab semantics, keyboard navigation and focus restoration of SurfaceTabs stay intact.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/SurfaceTabs.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingControls.toolbar.test.ts"
      ],
      "class": "New",
      "behaviour": "The pause menu preserves until-I-resume, 15-minute and one-hour deadlines; suppresses concurrent requests; Escape restores trigger focus; Resume clears only manual pause and reports remaining confirmed schedule and battery holds.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L1",
          "quote": "The Folders tab is one page using the whole window: headline, one summary line, attention items (when any), folder list, skip rules and indexing times, then one-line links to other apps' documents and Activity. Window actions sit beside the member tabs (Folders, Browse): Pause indexing with until-I-resume / 15 min / 1 h, a \u22ef menu with Reindex everything and Process pending work, and a filled Add folder. No card boxes or outlined pill buttons. Tab semantics, keyboard navigation and focus restoration of SurfaceTabs stay intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L15",
          "quote": "Combined holds: manual pause, schedule and battery at the same time (including pausing and an empty queue) are all named; Resume clears only the manual pause and says what still holds indexing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P5",
          "quote": "Pause/resume with durations; schedule and battery holds shown; settings-recovery path.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingControls.toolbar.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts"
      ],
      "class": "New",
      "behaviour": "Existing retained migration checks keep inputs and outcomes; the empty observation moves to the add-a-folder headline. The rebuild decision heading itself must carry the existing unknown qualifier, as well as the panel body, for retained/nonfresh observations. Its detached Library harness now passes the same complete progress projection and transition headline/rebuild identity as the live subscription, including the existing liveness verdict; every empty/rebuilding/freshness expectation is unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L3",
          "quote": "Each attention item appears once above the list with what it means and its actions: folder can't be found (worded \"reconnect the drive or remove it\", no drive-vs-folder claim) with Check again (core.reconcile-root) and Remove; unreadable files named with reasons and Try again; indexing held: manual pause with Resume, schedule or battery hold with \"Change when to index\" (no Resume-now override, none exists), settings recovery with Open Settings; rebuild running or needing a decision with the existing accept-gaps / cancel and exact gap list; unfinished removals with retry / withdraw and their consent; deletions not verified with Reindex; bulk reindex failed or unknown with Check outcome.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L10",
          "quote": "Empty and rebuilding: no folders shows an add-a-folder headline and Add folder; rebuilding with no folders shown says folders will reappear. Browser mode: typed path entry for Add folder and one quiet note, not a banner.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-liveness-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing admin/env override notice remains visible without opening How rules work and still names source, exact override patterns and accessible description. The editor reference moves from textarea to the first skip-rule input; all preview/apply payload and consent checks remain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L7",
          "quote": "Skip rules edited on the page, one per line with Add a rule, keeping every existing exclude-pattern behaviour: explicit save and reload, uncertain-save retention, conflict review, preview, typed-consent apply, admin/env override notice, partial results, exact-path keep-outs with remove.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.excludes-override.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "New",
      "behaviour": "Add stays pending with the same path/collection draft while the real member tabs switch to Browse; the single accepted result appears on return to Folders. Existing add, picker, removal, stale-response and nested-confirmation oracles are preserved: controls move into expanded rows, labels change to Add folder / Remove from library, and announcements select their surface status node.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "Add folder: picker or typed path, collection, preview, refusal of missing, non-folder, duplicate, overlapping and reserved-collection entries.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Remove: nested and enclosing-root confirmation, overlapping-removal suppression, lost-response reconcile, retry, focus restore.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P9",
          "quote": "Deep links to the Library and to its Browse member tab land on the right tab.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ],
      "class": "New",
      "behaviour": "Observed document counts determine truthful headings; actionable rebuild outcomes deduplicate; missing-root/requeued failures stay named and reachable until settled. Shared themed/fallback disclosures keep independent expansion, full-path tooltips, files-found counts and word/progress facts. Explorer is hidden in browser mode, uses each resolved path on desktop, and reports unresolved/removed/disconnected/native refusal through the existing bridge, including Unicode and commas in the path. Two mounted windows observe the same holds while preserving a draft. Expanded facts replace duplicate metadata; unconfirmed/waiting status is stated once. Fallback focus preservation is checked alongside the repaired themed focus check covered separately below. Native shell execution remains for desktop verification.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L3",
          "quote": "Each attention item appears once above the list with what it means and its actions: folder can't be found (worded \"reconnect the drive or remove it\", no drive-vs-folder claim) with Check again (core.reconcile-root) and Remove; unreadable files named with reasons and Try again; indexing held: manual pause with Resume, schedule or battery hold with \"Change when to index\" (no Resume-now override, none exists), settings recovery with Open Settings; rebuild running or needing a decision with the existing accept-gaps / cancel and exact gap list; unfinished removals with retry / withdraw and their consent; deletions not verified with Reindex; bulk reindex failed or unknown with Check outcome.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L4",
          "quote": "Folder row: name, path, one status in words from folderStatus for every state it decides today (removal incomplete, provisional/unknown, unavailable, walk failure, indexing/waiting manual/rules/retry, scanning, failed jobs, deletion unverified, enriching, keyword-only/blocked, ready, no indexable files), a thin progress bar when the state has a fraction, and the file count labelled as files found. Last-known and unknown qualifiers kept.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L5",
          "quote": "A row expands in place (button with aria-expanded, keyboard operable; several may be open): collection, searchable-by-words verdict, meaning processing percent worded as progress (not coverage), indexed X ago, verified X ago, unreadable count opening the failed-files list, and actions Open in Explorer and Remove from library. Open rows stay open through live refreshes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L6",
          "quote": "Open in Explorer in the desktop app opens the folder through the existing reveal-in-explorer bridge; it is absent in browser mode and disabled with a reason for a folder that can't be found.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L16",
          "quote": "Failure evidence during retry: when failed counts drop to zero only because jobs requeue, the problem stays listed as last known with its files reachable until a settled observation clears it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L17",
          "quote": "A folder that can't be found recovers through the worker's background observation: after the drive returns, both the live page and a freshly loaded page show the row restored, keeping last-known counts and any separate failures.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L18",
          "quote": "Open in Explorer targets the resolved path, not the name: two folders with the same name open their own paths; an unresolved path, a folder removed or disconnected after rendering, and a native refusal each show a reason, never success; paths with spaces, commas and Unicode work through the real shell; existing refusals of network and device paths stay.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L20",
          "quote": "The default themed folder rows (FolderCardRenderer) and the Library's own fallback rows both deliver L3 to L5 (they differ today: the default lacks the full-path tooltip and \"Reindex to verify\").",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-liveness-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts"
      ],
      "class": "New",
      "behaviour": "Skip rules edit/add/remove one line at a time with explicit Save. Both save orders and quick succession preserve both settings pairs after reload without a self-conflict, including subsequent saves. Serialized writes survive tab changes; a rule-save completion survives reload while another window keeps its own dirty draft. Settings links to the Library editor and saves retain recovery/manual deadlines. Live and cold focus requests coalesce once, select Folders from Browse, and request focus once; actual scrolling is checked by the jseval Chromium arrival steps. External changes to the respective settings still conflict in either editor and never overwrite that change or either local draft. Deferred focus assertions wait for completion of the arrival task without changing their exact target or outcome.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L7",
          "quote": "Skip rules edited on the page, one per line with Add a rule, keeping every existing exclude-pattern behaviour: explicit save and reload, uncertain-save retention, conflict review, preview, typed-consent apply, admin/env override notice, partial results, exact-path keep-outs with remove.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L8",
          "quote": "When to index is edited on the Library page only: any time, or only between two times on chosen weekdays (overnight allowed), and pause while on battery; written through core.set-indexing-rules with the existing validation, draft, busy, read-only and conflict handling. Settings no longer edits them and shows a link to this section instead. Saved meaning is unchanged: an absent schedule stays absent (battery-only saves keep schedule null), a disabled schedule keeps its days and times, and the battery preference, manual-pause deadline and recovery reason survive saves and restart.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L14",
          "quote": "Every route to the moved controls lands on the Library's When-to-index section with keyboard focus: Change hours / Change battery from the Tasks panel and shared indexing controls, the command palette, including when the Browse tab is selected. Settings recovery still leads to Settings, Reset to defaults, and a rule save never clears that hold.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L19",
          "quote": "Exclusion saves and indexing-rule saves never overwrite each other, in either order, including quick successive saves and version conflicts; both read back intact after reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts"
      ],
      "class": "New",
      "behaviour": "Changing an open failed-files drawer from A to B replaces evidence and retry row keys with B, ignores late A, and reads the current folder again on reopen/remount.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L13",
          "quote": "Repair (broken today): with the failed-files list open for folder A, choosing folder B's problem replaces the rows and retry targets with B's.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-failed-files.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#switches an open drawer from A to B and ignores A arriving late",
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#loads the current target after a drawer is remounted while its store is open"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts"
      ],
      "class": "New",
      "behaviour": "Repair pins for V1/L5: themed row focus remains on its toggle on open, live refresh and close, with aria-expanded synchronized. V3/L19: both sequential save orders and quick successive saves share local successors, permit a following save and persist both values after reload. Real Chromium arrival geometry replaces the three V2 happy-dom scroll-argument checks; jseval captures governance tooling outside the flagged product test set.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L5",
          "quote": "A row expands in place (button with aria-expanded, keyboard operable; several may be open): collection, searchable-by-words verdict, meaning processing percent worded as progress (not coverage), indexed X ago, verified X ago, unreadable count opening the failed-files list, and actions Open in Explorer and Remove from library. Open rows stay open through live refreshes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L12",
          "quote": "Live behaviour: statuses update without collapsing rows or losing a rules draft; a second window shows the same state; reload or switching tabs mid-operation keeps pending outcomes (existing race classes).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L14",
          "quote": "Every route to the moved controls lands on the Library's When-to-index section with keyboard focus: Change hours / Change battery from the Tasks panel and shared indexing controls, the command palette, including when the Browse tab is selected. Settings recovery still leads to Settings, Reset to defaults, and a rule save never clears that hold.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L19",
          "quote": "Exclusion saves and indexing-rule saves never overwrite each other, in either order, including quick successive saves and version conflicts; both read back intact after reload.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#V1 themed disclosure retains focus on toggle and live refresh",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#L19 rules first saves both editors without a local conflict and keeps edits after reload",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#L19 exclusions first saves both editors without a local conflict and keeps edits after reload",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#V3 quick successive saves rules first share only local witnesses",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#V3 quick successive saves exclusions first share only local witnesses",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#L19 configured disabled schedule survives successive rule saves and skip save"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts"
      ],
      "class": "New",
      "behaviour": "Own Pause/Resume/time-save receipts advance only a directly succeeding held witness; missing, gapped and mismatched receipts cannot refresh a skip-rule draft. External settings writes keep skip-rule conflicts until explicit reload/review; the baseline absolute indexing-times write still succeeds after an unrelated external write. Exact distinct energy/GPU labels reach the status-deck chip. Headline and retained failure evidence checks are recorded separately against L2/L16 below.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L19",
          "quote": "Exclusion saves and indexing-rule saves never overwrite each other, in either order, including quick successive saves and version conflicts; both read back intact after reload.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L16",
          "quote": "Failure evidence during retry: when failed counts drop to zero only because jobs requeue, the problem stays listed as last known with its files reachable until a settled observation clears it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P10",
          "quote": "Existing Library behaviour tests keep asserting the same behaviour after selector and wording updates; none is weakened or dropped without a recorded reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-round3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#B own Pause then Resume then time save, and time save then Pause then skip save, chain committed witnesses",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#B unrelated external write conflicts with skip rules, keeps the draft until explicit review, and permits the baseline absolute time save",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#B missing operation witness never refreshes a skip-rule draft",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#B gap operation witness never refreshes a skip-rule draft",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.slice2.test.ts#B wrong key operation witness never refreshes a skip-rule draft",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts#C distinct energy_saver summary reaches the status chip",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts#C distinct game_mode summary reaches the status chip",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts#C distinct gpu_busy_external summary reaches the status chip"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ],
      "class": "New",
      "behaviour": "Unconfirmed observations never claim searchable. Manual pause and retried-file attention remain reachable across 25 alternating-confirmation samples, become explicitly unconfirmed after the shared freshness threshold and clear only on a confirmed settled observation. Energy/GPU summary suppression is driven by real status projection inputs rather than an obsolete injected label.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L3",
          "quote": "Each attention item appears once above the list with what it means and its actions: folder can't be found (worded \"reconnect the drive or remove it\", no drive-vs-folder claim) with Check again (core.reconcile-root) and Remove; unreadable files named with reasons and Try again; indexing held: manual pause with Resume, schedule or battery hold with \"Change when to index\" (no Resume-now override, none exists), settings recovery with Open Settings; rebuild running or needing a decision with the existing accept-gaps / cancel and exact gap list; unfinished removals with retry / withdraw and their consent; deletions not verified with Reindex; bulk reindex failed or unknown with Check outcome.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L16",
          "quote": "Failure evidence during retry: when failed counts drop to zero only because jobs requeue, the problem stays listed as last known with its files reachable until a settled observation clears it.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-d1-liveness-refresh.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#D1 keeps attention and retried files through 25 alternating unconfirmed polls and clears on a settled observation",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#D1 never claims searchable during unconfirmed polls, including 42 seconds past the shared threshold",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#A5 energy_saver waits are stated once through the real projection",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#A5 gpu_busy_external waits are stated once through the real projection"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts"
      ],
      "class": "New",
      "behaviour": "Arrival focus waits for loaded indexing rules and keeps the destination in view while layout settles. Real geometry remains checked by the jseval arrival steps, including never-mounted palette navigation and a previously mounted warm route. No exact one-second contract is asserted.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L14",
          "quote": "Every route to the moved controls lands on the Library's When-to-index section with keyboard focus: Change hours / Change battery from the Tasks panel and shared indexing controls, the command palette, including when the Browse tab is selected. Settings recovery still leads to Settings, Reset to defaults, and a rule save never clears that hold.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-arrival-settling.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts#L14 waits for loaded rules, keeps the destination in view while layout settles"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts"
      ],
      "class": "New",
      "behaviour": "A later user focus move cancels arrival scrolling, preserving keyboard control. The check first witnesses active arrival scrolling, then moves the target within the arrival window after focus moves. Removing the focus guard produces an assertion failure; unchanged geometry can no longer make this check pass vacuously.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L14",
          "quote": "Every route to the moved controls lands on the Library's When-to-index section with keyboard focus: Change hours / Change battery from the Tasks panel and shared indexing controls, the command palette, including when the Browse tab is selected. Settings recovery still leads to Settings, Reset to defaults, and a rule save never clears that hold.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-arrival-cancellation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingRulesEditor.arrival.test.ts#L14 a later user focus move cancels arrival scrolling"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "Shared-authority bound change, preserving every liveness oracle: indexingFreshness worker-age test moves the confirmed wait from 20s to 30s and expiry from 25s to 35s (sample starts 7s old, bound 30s + 10s); no-worker-timestamp receipt-age test moves the inclusive confirmation check from 10s to 20s, then expires at 25s (10s + 10s). indexingStatusSurfaces moves 23,001ms to 33,001ms because the sample starts 7s old and the bound is now 40s. indexingStatusFollowup, indexingSurfaceSweep and indexingMissedSurfaces move age inputs 30,001ms to 40,001ms, preserving every cross-surface unconfirmed-state assertion. LibrarySurface.redesign moves the D1 retained-failure expiry observation from 31s to 41s, keeping its attention and file reachability checks. SettingsSurface.settingsRecovery moves 31s to 41s for USER, OPERATOR and DEVELOPER, preserving confirmation, Yes focus, retained hold and explicit clear behavior. No check is skipped or removed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L16",
          "quote": "Failure evidence during retry: when failed counts drop to zero only because jobs requeue, the problem stays listed as last known with its files reachable until a settled observation clears it.",
          "label": "agent-drafted, owner-adopted"
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
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl user turn after the WP1 summary",
          "delegationScope": "Proceed autonomously with implementing the agreed window designs and choosing implementation details within the adopted scenarios, including the shared observation-age bound.",
          "choice": "stale only after staleAfterMs plus the client's read interval",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-liveness-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts"
      ],
      "class": "New",
      "behaviour": "Fresh six-second worker observations stay confirmed between ten-second client reads over 40 seconds. Frozen worker timestamps still expire despite successful HTTP reads. Lost status contact is unconfirmed rather than a rebuild, with and without roots; a real confirmed migration still shows rebuilding. Fake timers exercise the authority; jseval library-poll-cadence-fresh/frozen/failed exercise real Chromium timers and status transport, including the status bar.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L2",
          "quote": "Headline: \"Your files are searchable\" when nothing needs attention; \"N things need your attention\" counting distinct actionable items, deduplicated; while status is unconfirmed or loading it says so and never claims searchable. Summary line: searchable documents and folder count from status, plus what is running now.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "L16",
          "quote": "Failure evidence during retry: when failed counts drop to zero only because jobs requeue, the problem stays listed as last known with its files reachable until a settled observation clears it.",
          "label": "agent-drafted, owner-adopted"
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
          "delegationQuote": "well from now on you can just proceed autonomously accordinglky",
          "delegationLocation": "task t-20261101-uiimpl user turn after the WP1 summary",
          "delegationScope": "Proceed autonomously with implementing the agreed window designs and choosing implementation details within the adopted scenarios, including the shared observation-age bound.",
          "choice": "stale only after staleAfterMs plus the client's read interval",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/library-redesign-liveness.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts#L2 fresh 6-second worker samples remain confirmed between 10-second reads for 40 seconds",
        "modules/ui-web/src/shell-v0/state/indexingFreshness.test.ts#L2 a frozen worker sample expires after 6 seconds plus one read interval despite successful reads",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#L2 lost status contact never describes a rebuild (empty roots: false)",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.redesign.test.ts#L2 lost status contact never describes a rebuild (empty roots: true)"
      ],
      "repaired": true
    }
  ]
}
```
