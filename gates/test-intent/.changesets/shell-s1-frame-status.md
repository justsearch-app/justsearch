---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: bfcbc069-5551-4add-9cc6-733e42abdac8
---

# Frame status (S1): one status pill and status panel replace the retired status presenters

The slice retires the status bar, task tray, toast stack, inbox drawer, rail bell, agent digest,
proposed-effect cards, agent activity panel, update banner, plugin error cards, resolution toast and
indexing overlay (ST10), and adds a pure derivation (`state/frameStatus.ts`), a status pill and a
status panel. Tests of removed presenters are Obsolete with their production files; tests that
observed a still-existing behaviour through a removed presenter now observe it at the new owner
(Adaptation); new expectations are New with execution evidence. Repaired behaviour (ST11 wording,
C6 replay, C12 typed agent MEDIUM consent, the single status owner and the Ctrl+Z undo route) fails
before on the merge-base `bfc6a52032` with assertion failures
(`gates/test-intent/evidence/shell-s1-frame-status-repair.json`). New behaviour that only exists in
new modules (pill, panel, derivation) cannot load on the before-state; its checks are recorded
without a fail-before (`gates/test-intent/evidence/shell-s1-frame-status-new.json`).

`IndexingOverlay.test.ts` also carries New entries in `s0-frame-defects.md` (left unedited); its
deletion is the Obsolete entry here.

## Old test → new owner

| Old test (group) | New test | Kept / retired |
| --- | --- | --- |
| StatusDeck: verdict announcer polite/assertive (595 §15.1 E1) | StatusPill.transitions "verdict and AI-engine announcers" | kept (wording per ST11) |
| StatusDeck: N1 completion toast once; first load and reconnect silent; via checking | StatusPill.transitions "completion edges"; StatusPill.feedback ST8; frameStatus ST8 C6 | kept |
| StatusDeck 663: AI install settled/failed toasts once; none without installing | StatusPill.transitions "completion edges" (it.each online/install_failed) | kept |
| StatusDeck: retry waiting announced with backlog and deadline | StatusPill.transitions "announces retry waiting…" | kept |
| StatusDeck: queue chip, energy saver, 813 enriching percent, blocked stage, E2 retained values, fresh serving count in migration, unavailable enrichment visible | StatusPanel.running; frameStatus "indexing detail…"; indexingStatusSurfaces/indexingSurfaceSweep/indexingMissedSurfaces (adapted) | kept |
| StatusDeck: connection/memory dots, endpoint label, files/size/memory chips, system pill tone, inference-mode pill routing, 814 §D5 chip/banner arbitration | — | retired with the bottom bar (ST10): the pill carries one state (ST1); engine and AI state live in Health and AI Brain (ST11), both reachable from the panel footer/palette |
| TaskList: aggregate card (indeterminate/determinate bar, store-derived drain, byte weight, enriching percent, null percent, unknown, non-live) | StatusPanel.running "Running…" and "progress readout facts"; frameStatus ST13 | kept |
| TaskList: indicative and enrichment estimates, "at the current rate" qualifier, none without basis, none while enriching | StatusPanel.running "indexing and meaning-search estimates…", "unknown fractions and estimates…" | kept |
| TaskList: per-stage detail, ✓ only when finished, extraction without fraction, files below stages | frameStatus "stage rows mark only finished stages…"; StatusPanel.running "stage failures…" | kept |
| TaskList: running task with progress and Cancel | StatusPill.transitions "a cancellable running task…" | kept |
| TaskList: queued backlog as one count, 8-row window | indexingJobsQueueRail (adapted) | kept |
| TaskList: terminal state on active→ready only, F1 "indexed and enriched" honesty, failures withdraw completion | StatusPill.feedback ST8; StatusPanel.running "persisted vectors…", "blocked meaning search…"; frameStatus "a terminal indexing failure…" | kept |
| TaskList: collapse when empty, disclosure toggle | StatusPanel.history "omits empty sections…"; StatusPill.test ST15 expanded row | kept (panel form) |
| IndexingOverlay: unconfirmed header, queue rows, liveness withdrawal, retry waiting not active, one projection's counts | StatusPanel.running "stale retained indexing…", "a missing observation…"; IndexingPause (adapted) | kept (panel form) |
| IndexingPause F3: focused status item kept outside the status bar's adaptive overflow | — | retired with the status bar (ST10): the panel's indexing controls never enter an overflow cut |
| indexingMissedSurfaces: status-bar mode chip label ('Engine gone'/'unavailable' engine label); overlay host absent for 'Engine gone' | indexingMissedSurfaces (adapted: Running rebuild fact; Running progress bar) | chip label and overlay host retired (ST10); the qualification itself is kept |
| IndexingOverlay: Go online CTA, its S0 failure notice, dismiss, modality authority (864 F2), Sv3Main 864 F2 | — | retired with the overlay (ST10); Go online stays in AI Brain (core.set-chat-enabled, failures in that window's runtime error) |
| AdvisoryToastHost: snapshot seeds silently, pre-snapshot updates silent, new advisories after snapshot | AdvisoryStore C6 (repaired); StatusPill.feedback C6; frameStatus ST8 C6 | kept |
| AdvisoryToastHost 655: desktop notification for live REQUIRES_ACK while unfocused only, never for replay | StatusPill.transitions "only a live REQUIRES_ACK arrival…"; StatusPill.feedback C6 | kept (ST14) |
| AdvisoryToastHost 613 §14: severity → assertive/polite | StatusPill.transitions "a local %s receipt announces %s"; StatusPill.feedback C1 | kept |
| AdvisoryToastHost XD2: focused action keeps identity, retained during focus | StatusPill.feedback "undo pauses while hovered or focused…" | kept |
| AdvisoryToastHost 941 F5: humanized recoverable copy, relative time, operation labels, interpolation | StatusPanel.history "humanizes recoverable conditions…" | kept |
| AdvisoryToastHost: 3-toast stack with "+N earlier", per-toast timers, dismiss buttons, sticky errors, XD2 overflow announcement | — | retired: the pill shows one transient at a time and queues the rest; every receipt stays in Earlier (C1, ST7) |
| AdvisoryInboxDrawer: newest first, unread, acknowledge on open, Mark all read soft-unavailable, diagnostics link/text, unknown class chrome, humanized titles and reasons | StatusPanel.history (all cases) | kept |
| AdvisoryInboxDrawer: EPHEMERAL hidden | StatusPanel.history "retains local receipts in Earlier without making them unread" | changed by C1/ST7 (receipts recoverable in Earlier) |
| AdvisoryInboxDrawer: class filter chips, unread-only toggle | — | retired with the drawer; full history and filtering stay in All activity |
| AdvisoryRailBadge: unread count, 99+, toggle event, stale-feed dot | — | retired (ST10); unread count shows as "N unread" in Earlier; ST11 removes the poll-staleness "offline" signal |
| AgentActivityPanel / PendingEffectQueue: cards, accept/reject, confidence chip and order, typed HIGH and agent MEDIUM, user MEDIUM immediate | StatusPanel.approvals (all cases); acceptPendingEffect C12 | kept |
| AiActivityDigest: undo all with preview, irreversible remainder, save as macro | ActivitySurface.actions (same three cases) | kept (moved to Activity) |
| AiActivityDigest: summary chip, seen cursor, live update, auto-hide window | — | retired with the digest; agent actions list in Earlier and Activity |
| AppUpdateBanner: available release → Settings; absent otherwise | frameStatus "ST4 update %s retains its existing Settings action" | kept |

## Round 7 changes to this slice's own checks

The R1 contract (a passing notice never displaces a more urgent state) changes where three existing
checks of this slice read a notice; R4 changes what names a folder row. No assertion is dropped:

| Check | Change | Reason |
| --- | --- | --- |
| StatusPill.feedback "undo pauses while hovered or focused…" | the fixture records engine contact before the 9 s step; assertions unchanged | without contact the engine reads unreachable after the 10 s grace, which now (correctly) keeps the pill; the check is about a notice when nothing is more urgent |
| StatusPill.feedback "ST5 plugin errors … copy failures have visible feedback" | the copy failure is asserted beside the pill (`status-notice`) and the pill is asserted to keep "Problems right now (1)" | R1: an active problem keeps the pill; the feedback stays visible next to it (C1) |
| StatusPill.design "below 1000 px the pill is an icon…" | the C1 notice is asserted beside the icon, and the pill's name is asserted to stay "1 needs you" | R1, as above |
| frameStatus "B1 a folder whose scan errored…" | supplies the resolved folder path; same title assertions | R4: folder rows are named by folder, not by collection |

## Round 8 changes to this slice's own checks

The status panel no longer holds a ceremony back until Review: every authorization ceremony opens on
arrival as on main (CI Measured axe, `ui_agent_flows.setup_run_approval`), stays listed in Needs you,
and Review focuses it or reopens it if it was closed. The host's `status-panel` switch is retired with
the gate it carried. No assertion is dropped:

| Check | Change | Reason |
| --- | --- | --- |
| AuthorizationHost.toplayer "A1 selecting … from the status panel …" (6 cases) | uses the host as the shell mounts it (no `status-panel` attribute); "nothing opens until one is selected" becomes "the first opens on arrival in its own modality, both are listed", and reviewing the open one keeps it open in that modality; the selection and modality assertions are unchanged | opening on arrival is the restored behaviour; the cases still fail before for the A1 reason (`<second> modality`) |
| StatusPanel.approvals (8 cases) | the fixture no longer sets the retired `statusPanel` property; assertions unchanged | the property is removed |

## Round 9 changes to this slice's own checks

A newly presented ceremony ignores its decision buttons until a fresh press that starts at least
400 ms after it was presented, so one click or key press answers one prompt (a double-click, a
repeated activation or a key's auto-repeat no longer answers the next queued prompt). Existing checks
that click a decision button the instant a prompt appears now let that delay pass first; no assertion
is dropped or changed:

| Check | Change | Reason |
| --- | --- | --- |
| AuthorizationHost.test (all cases that decide), AuthorizationHost.toplayer (queue and A1 cases), StatusPanel.approvals (decision cases) | fake `performance` clock only; the activation helper advances it 400 ms before the click | the prompt arms after 400 ms; no other timer is faked |
| pendingAuthorizationWithdrawal (7 cases), statusCompletion "mounted authorization host and real pending bridge…" | the activation helper advances the already-fake clock 400 ms before the decision click | as above; these files already fake all timers |

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AgentActivityPanel.test.ts",
        "modules/ui-web/src/shell-v0/components/AiActivityDigest.test.ts",
        "modules/ui-web/src/shell-v0/components/AppUpdateBanner.test.ts",
        "modules/ui-web/src/shell-v0/components/PendingEffectQueue.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusDeck.test.ts",
        "modules/ui-web/src/shell-v0/components/TaskList.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryInboxDrawer.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryRailBadge.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.focus.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.identity.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.keyboardFocus.test.ts",
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.overflow.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "Every check in these files pinned the retired presenter it renders (status bar, task tray, toast stack, inbox drawer, rail bell, agent digest, proposed-effect cards, agent activity panel, update banner). Behaviours that still exist are pinned at their new owner in this change; the old-to-new mapping table above the JSON lists each group, its new check, and the groups retired with their presenter and why.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | ST11 Wording: \"All systems operational\" is gone; the calm state claims only what is checked (\"Up to date\" covers search; AI health shown separately in AI Brain/Health). The advisory \"offline\" indicator no longer derives from poll staleness alone.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/AgentActivityPanel.ts",
            "modules/ui-web/src/shell-v0/components/AiActivityDigest.ts",
            "modules/ui-web/src/shell-v0/components/AppUpdateBanner.ts",
            "modules/ui-web/src/shell-v0/components/PendingEffectQueue.ts",
            "modules/ui-web/src/shell-v0/components/StatusDeck.ts",
            "modules/ui-web/src/shell-v0/components/TaskList.ts",
            "modules/ui-web/src/shell-v0/components/advisory/AdvisoryInboxDrawer.ts",
            "modules/ui-web/src/shell-v0/components/advisory/AdvisoryRailBadge.ts",
            "modules/ui-web/src/shell-v0/components/advisory/AdvisoryToastHost.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for StatusDeck, status bar, TaskList, task list, AdvisoryToastHost, toast, AdvisoryInbox, inbox drawer, rail badge, AiActivityDigest, PendingEffectQueue, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay, ResolutionToast, IndexingOverlay, indexing overlay: no matches"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "All checks of the full-screen indexing overlay and its host, including the S0 'Go online failures' cases (s0-frame-defects.md entries, left unedited). The overlay is retired by ST10; indexing progress, holds and Pause/Resume moved into the non-modal status panel (IndexingPause.test.ts, StatusPanel.running.test.ts), its liveness withdrawal became the panel's 'last seen' qualification (StatusPanel.running 'stale retained indexing suppresses bars…'), and 'Go online' (core.set-chat-enabled) stays reachable from the AI Brain chat-engine buttons, whose failures show in that window's runtime error. The overlay's own modality and dismiss behaviour leave with it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/IndexingOverlay.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for StatusDeck, status bar, TaskList, task list, AdvisoryToastHost, toast, AdvisoryInbox, inbox drawer, rail badge, AiActivityDigest, PendingEffectQueue, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay, ResolutionToast, IndexingOverlay, indexing overlay: no matches"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.navigation.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "'857 PR-A — the guards 864 F2: stands down while the indexing overlay is up': it drove the transcript guard with the full-screen indexing overlay as the modal. The overlay is removed (indexing progress is in the non-modal status panel); the modality guard stays pinned by the palette case in the same describe.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/IndexingOverlay.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for StatusDeck, status bar, TaskList, task list, AdvisoryToastHost, toast, AdvisoryInbox, inbox drawer, rail badge, AiActivityDigest, PendingEffectQueue, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay, ResolutionToast, IndexingOverlay, indexing overlay: no matches"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "F2 'preserves a sensible focus and announces remote quiet-queue Resume once': the assertions that the task tray stays mounted after a remote resume (no data-empty), keeps focus on its task title and reads 'Indexing is already running'. Those belonged to the task tray (TaskList.ts, removed). In the panel a resolved hold leaves Needs you (ST4/ST5 active-only) and focus moves to the panel itself; the announcement-once assertion is kept (see the New entry). Also removed: F3 'keeps the focused status item outside hidden adaptive overflow', which measured the status bar's adaptive overflow (.adaptive-bar / .item, the bar's OverflowController cut) to keep the focused Pause/Resume item visible and let it overflow again after blur. The status bar is removed (StatusDeck.ts); in the panel the indexing controls sit in their row and never enter an overflow cut (the plugin row keeps its own OverflowController, pinned by StatusPill.test ST9/ST15).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST5 Problems right now lists active conditions only: folder unavailable, unreadable files (per folder, linking to Library), search limited, plugin load failure, AI install failed. A resolved condition leaves the section; history of it remains in Earlier/Activity.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/TaskList.ts",
            "modules/ui-web/src/shell-v0/components/StatusDeck.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for StatusDeck, status bar, TaskList, task list, AdvisoryToastHost, toast, AdvisoryInbox, inbox drawer, rail badge, AiActivityDigest, PendingEffectQueue, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay, ResolutionToast, IndexingOverlay, indexing overlay: no matches"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "Two presenter-only assertions inside otherwise adapted tests (the rest of each test is the Adaptation entry): (1) 'S33.4 S33.6 migration mode pills qualify retained rebuilding while preserving fresh migration' asserted the status bar's inference-mode chip label (inferenceChipReadout().label): the engine's statusLabel for 'Engine gone' and 'unavailable', the unconfirmed placeholder otherwise. The chip is removed with the status bar (StatusDeck.ts); the test keeps the retained-rebuilding qualification at the new owner (the Running rebuild fact is stale and carries no fraction when unconfirmed), and the engine label stays pinned by 'declared liveness readouts qualify retained migration and its accessible name' in the same file. (2) 'S33.1 S33.4 overlay activity animation stops during deferred work and uncertainty, then resumes' asserted that the full-screen overlay host renders no jf-indexing-overlay for 'Engine gone' and read the overlay's .icon-box spin animation. The overlay is removed (IndexingOverlay.ts); the same on/off condition is now read from the Running row's progress bar for every case, 'Engine gone' included.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | ST11 Wording: \"All systems operational\" is gone; the calm state claims only what is checked (\"Up to date\" covers search; AI health shown separately in AI Brain/Health). The advisory \"offline\" indicator no longer derives from poll staleness alone.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/StatusDeck.ts",
            "modules/ui-web/src/shell-v0/components/IndexingOverlay.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none — docs/decisions/*.md (54 records) searched for StatusDeck, status bar, TaskList, task list, AdvisoryToastHost, toast, AdvisoryInbox, inbox drawer, rail badge, AiActivityDigest, PendingEffectQueue, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay, ResolutionToast, IndexingOverlay, indexing overlay: no matches"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingMissedSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusFollowup.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingStatusSurfaces.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.indexingRules.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.structuredOutcome.test.ts",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/platform-open-guard.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation",
        "input re-encoding"
      ],
      "whatMoved": "The observation point moved from the retired presenters to their new owner; inputs, preconditions, fault injection and expected outcomes are kept. IndexingPause: the shared Pause/Resume surface cases mount jf-status-pill (panel open) instead of jf-task-list / jf-status-deck / jf-indexing-overlay and read jf-indexing-controls inside the panel; the indexing announcer is the pill's; 'uncertainty must not hide a focused action' reads control.isConnected instead of the tray's data-empty attribute; overlay cases feed the same snapshot through the store instead of assigning a precomputed projection. indexingMissedSurfaces: 'mode pill qualifies retained rebuilding' reads the Running rebuild fact (stale when unconfirmed, no fraction) instead of the status-bar chip label (the chip-label values are the separate Obsolete entry); 'overlay animation stops during deferred work and uncertainty' reads the indexing row's progress bar presence with the identical condition (the overlay-host absence for 'Engine gone' is the separate Obsolete entry); 'announcements use one region and withdraw stale rebuild claims' keeps both original assertions (one Rebuilding notice, none after the stale observation) on the pill. indexingSurfaceSweep 'Running and System self-view qualify retained indexing rows': the System self-view half is unchanged (its row reads 'last observed running' and has no .bar when unconfirmed), and the task-tray half reads the panel's per-file detail line (same words, no progress bar) and the indexing row's bar; 'status cannot assert indexing from the parked AI runtime alone' keeps its input (statusLabel 'Indexing', aiEngine indexing) and reads the Running indexing fact's title with the same expected sentences. indexingStatusFollowup/indexingStatusSurfaces/indexingSurfaceSweep: the same states render through jf-status-pill / jf-status-panel (deriveFrameStatus) in place of jf-status-deck / jf-task-list / jf-indexing-overlay-host, with the same expected sentences, 'last observed'/'last seen' qualification and absence of bars; the stale stage disclosure's qualifier is the panel's 'last seen' instead of the tray's heading 'Last observed indexing and enrichment details'. indexingTaskWaiting: the running list is the panel's Running section; the delayed file is still not counted (Running ids are exactly the waiting aggregate and the agent task; '3 running' absent). indexingJobsQueueRail: the queued/failed totals and the 8-row file window read from the indexing row's details in the panel instead of the tray's aggregate cards; a fetch stub keeps the pill's status-cadence reads off any real origin. SettingsSurface.indexingRules: the schedule-hold row moves from the task tray to the panel's Needs you. SearchV3View.structuredOutcome: the polite announcement is the pill's polite live region and the outcome's local record, instead of one toast per outcome. platform-open-guard: the refusal toast's renderer is the pill instead of the toast host. Shell.test (slice 492): the mounted status owner is jf-status-pill instead of jf-status-deck. HealthSurface.render: the negative assertions 'never claims the green phrase' follow the phrase's new wording ('Up to date' instead of 'All systems operational').",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps. | ST13 (must not break) Freshness distinctions: last-known values, unknown counts, keyword-ready vs enrichment pending vs blocked vs complete; no invented percentages or ETAs. | C7 (must not break) Pause/resume truth: overlapping holds (manual, schedule, battery, settings recovery) are all named; pausing vs paused kept; a failed resume says so; an accepted resume shows \"resuming\" until the engine reports work running.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/verdict.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "The operational verdict reads 'Search available' (headline and polite announcement) and Health's calm footer reads 'Up to date'; 'All systems operational' is gone.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST11 Wording: \"All systems operational\" is gone; the calm state claims only what is checked (\"Up to date\" covers search; AI health shown separately in AI Brain/Health). The advisory \"offline\" indicator no longer derives from poll staleness alone.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/verdict.test.ts#verdictHeadline words each kind, distinguishing cosmetic degraded from a hard one",
        "modules/ui-web/src/shell-v0/state/verdict.test.ts#presentVerdict (595 §15.1) — the ONE verdict-presentation projection announces politely for non-error verdicts, assertively for an error verdict",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#HealthSurface — recommendedActions panel renders \"All healthy\" when recommendedActions is empty and the verdict is operational"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryStore.test.ts"
      ],
      "class": "New",
      "behaviour": "Only an UPDATE frame after the stream's snapshot marks an advisory as a live arrival; snapshots, reconnect snapshots and pre-snapshot updates are replay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C6 (must not break) Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements. | ST14 (must not break) Desktop OS notifications for REQUIRES_ACK arrivals keep working until S3 replaces the routing.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/advisory/AdvisoryStore.test.ts#advisoryReducer C6 only an UPDATE after the snapshot is a live arrival; snapshots and pre-snapshot updates are replay"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/acceptPendingEffect.test.ts"
      ],
      "class": "New",
      "behaviour": "Accepting an agent MEDIUM-risk proposal after its typed confirmation dispatches it as consented without a client-minted confirmation token.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST12 (must not break) Typed confirmation of high-risk and agent medium-risk proposals; acceptance and rejection outcomes; exact-target binding of backend pending authorizations; withdrawal/expiry reconciliation (transport failure is not expiry). | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/acceptPendingEffect.test.ts#C12 typed agent MEDIUM acceptance preserves consent without minting a token"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts"
      ],
      "class": "New",
      "behaviour": "The shell mounts exactly one status pill with its panel and none of the retired presenters; the shell Undo command (Ctrl+Z) reverses the undo notice showing in the pill (not while typing), also after a shell remount.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | C10 (must not break) Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#ST10 the frame has one status destination and no retired presenter mounts",
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#ref-07 the shell Undo command (Ctrl+Z) reverses the undo notice showing in the pill, not while typing, after a shell remount too"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "The pure derivation of the pill state and panel sections: urgency order with every concurrent fact listed, stale/unknown and the reconnect grace, durable versus invocation completion, read/unread versus active/resolved, replay silence, all overlapping holds, one decision route per authorization, relative agent request deadlines and the needs-you count.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST1 (Concurrent facts stay discoverable: whatever the pill shows, every actionable condition is listed in the panel.) One status pill in the top bar shows exactly one state, chosen in this order: engine unreachable > search limited (index unavailable, reindex required, schema refused) > needs you (count) > problems right now > working (most important running item, with progress when known) > just finished (6 s) > calm. Calm reads \"Up to date\" with a green dot at window width ≥ 1000 px, dot only below, same accessible name. | ST2 \"Not sure\": with no fresh observation the pill shows the last known state with \"last seen …\" and never claims calm or ready; reachability via the event stream and poll freshness stay separate (a reachable engine with a stale poll is not \"can't reach\"). A short grace (value chosen in design, ≥ 1 poll) before showing \"reconnecting\". | ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST5 Problems right now lists active conditions only: folder unavailable, unreadable files (per folder, linking to Library), search limited, plugin load failure, AI install failed. A resolved condition leaves the section; history of it remains in Earlier/Activity. | ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps. | ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | ST8 Just finished: when long work completes (indexing settled after an observed active phase, reindex, install, rebuild), the pill shows it for about 6 s and a polite live announcement is made; this happens whatever the layout (no longer tied to the status bar being mounted). | ST13 (must not break) Freshness distinctions: last-known values, unknown counts, keyword-ready vs enrichment pending vs blocked vs complete; no invented percentages or ETAs. | C6 (must not break) Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements. | C7 (must not break) Pause/resume truth: overlapping holds (manual, schedule, battery, settings recovery) are all named; pausing vs paused kept; a failed resume says so; an accepted resume shows \"resuming\" until the engine reports work running. | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST1 keeps concurrent facts discoverable while the pill chooses one urgency",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST2 separates contact from poll freshness and waits a full poll before unreachable",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST6 durable acceptance and gaps suppress an invocation-return completion",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST7 read state never decides active/resolved and Earlier stays newest first",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST8 C6 terminal replay is silent; only an observed active-to-ready edge completes",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST6 AI downloads preserve measured progress and the existing pause/resume route",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ref-09 agent request deadlines read as their consequence relative to now, never as a raw time",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ref-09 the needs-you pill carries its count for the badge",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection C12 the broadcast advisory and the request-scoped prompt for one authorization give one decision route",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST1 working shows the most important running item with its measured progress only",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection ST2 a reachable engine with a stale poll says checking, never reconnecting, and never claims calm",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#frame status projection C12 deduplicates the same backend authorization by pending id",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#indexing detail moved from the task tray (813 §5/§20) stage rows mark only finished stages, extraction has no fraction, and file rows come after stages",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#indexing detail moved from the task tray (813 §5/§20) a terminal indexing failure is a current problem, never a completion",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST1 search restriction index.failed precedes decisions and remains actionable",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST1 search restriction index.blocked_legacy precedes decisions and remains actionable",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST1 search restriction index.schema_open_refused precedes decisions and remains actionable",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST4 keeps all decisions, holds and running work visible together, including all overlapping holds",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST5 active folder failures and AI failure leave Problems on recovery without turning acknowledgement into recovery",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST6 answering, per-folder work, rebuild and background runs retain separate freshness and real fractions",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST7 unread sorts ahead of a newer read item and remains separate from seen and active state",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST4 update available retains its existing Settings action",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts ST4 update repair_required retains its existing Settings action"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts"
      ],
      "class": "New",
      "behaviour": "Needs you keeps each decision's existing accept/reject and typed-confirmation behaviour through the protected host, executes once, and shows the deadline as its consequence.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST12 (must not break) Typed confirmation of high-risk and agent medium-risk proposals; acceptance and rejection outcomes; exact-target binding of backend pending authorizations; withdrawal/expiry reconciliation (transport failure is not expiry). | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) accept executes once and records acceptance; reject records rejection without applying",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) multiple proposals preserve rationale and rank the least-confident first without inventing confidence",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) HIGH/agent requires the exact typed operation and preserves consent provenance",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) HIGH/user requires the exact typed operation and preserves consent provenance",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) MEDIUM/agent requires the exact typed operation and preserves consent provenance",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) user MEDIUM proposal dispatches without a ceremony or client-minted confirmation token",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) plugin capability requests remain reachable through the protected host",
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts#Needs you (moved from proposed-effect cards and agent activity) C12 announcement and panel representations share one decision; stale clicks cannot approve the next request"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ],
      "class": "New",
      "behaviour": "Earlier: sections omitted when empty, calm in one line, unread before read then newest first, Mark all read through the existing acknowledgement, safe diagnostics, humanized titles, Undo only where reversal exists and 'Add back to search' for forget.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | C1 Ordinary operation feedback keeps a visible, accessible destination with the panel closed: clipboard failures, unknown destinations, operation failures and successes of the user's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped (ST10, DE5). | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) omits empty sections; calm has one line and the two history destinations",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) unread comes first, then newest within read state; history does not reactivate a resolved condition",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) Mark all read uses the existing acknowledgement and stays focusable with a reason when already read",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) diagnostics preserve safe navigation or plain text for https://example.test/diagnostics",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) diagnostics preserve safe navigation or plain text for diagnostics.i18n.key",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) diagnostics preserve safe navigation or plain text for javascript:alert(1)",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) retains local receipts in Earlier without making them unread",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) unknown advisory classes use default chrome, without exposing their raw class id as the title",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) humanizes recoverable conditions, authored reasons and operation actions",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) forget offers reindexing while backend supported reversal alone is called Undo"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts"
      ],
      "class": "New",
      "behaviour": "Running: measured drains and fractions only, stage fractions, blocked and stale work without invented counts, percentages or ETAs, faithful byte weights and qualified estimates.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps. | ST13 (must not break) Freshness distinctions: last-known values, unknown counts, keyword-ready vs enrichment pending vs blocked vs complete; no invented percentages or ETAs. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) an observed drain supplies 1600 remaining and the honest fraction null",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) an observed drain supplies 400 remaining and the honest fraction 75",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) enrichment stays discoverable without SSE tasks and exposes its real stage fractions",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) blocked meaning search never announces completion or invents an enrichment fraction",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) persisted vectors with the model removed do not fabricate unfinished work",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) stale retained indexing suppresses bars and qualifies its last-known activity",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) a missing observation provides no counts, percentage or ETA",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#Running (moved from the task tray and indexing overlay) stage failures and missing artifacts are never presented as finished",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#progress readout facts only a faithful byte weight is shown (null)",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#progress readout facts only a faithful byte weight is shown (8589934592)",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#progress readout facts indexing and meaning-search estimates use their own rates and retain the indicative qualifier",
        "modules/ui-web/src/shell-v0/components/StatusPanel.running.test.ts#progress readout facts unknown fractions and estimates leave no invented placeholders"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts"
      ],
      "class": "New",
      "behaviour": "The pill and panel: open/close/focus and the keyboard shortcut (yielding to typing, IME, repeat and modals), live updates keeping scroll and expanded rows, plugin row with provenance and removal, layout hiding only plugin items, own-action feedback in the pill and Earlier, completion and announcement edges, desktop escalation, durable completion, the needs-you badge, the progress ring, the icon-only pill below 1000 px and the undo notice with Ctrl+Z.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST1 (Concurrent facts stay discoverable: whatever the pill shows, every actionable condition is listed in the panel.) One status pill in the top bar shows exactly one state, chosen in this order: engine unreachable > search limited (index unavailable, reindex required, schema refused) > needs you (count) > problems right now > working (most important running item, with progress when known) > just finished (6 s) > calm. Calm reads \"Up to date\" with a green dot at window width ≥ 1000 px, dot only below, same accessible name. | ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | ST8 Just finished: when long work completes (indexing settled after an observed active phase, reindex, install, rebuild), the pill shows it for about 6 s and a polite live announcement is made; this happens whatever the layout (no longer tied to the status bar being mounted). | ST9 Plugin status items (StatusBarRegistry) render unchanged in the panel's plugin row with their provenance; while calm the pill can show a small count of plugin items. Uninstalling a plugin removes its items. | ST14 (must not break) Desktop OS notifications for REQUIRES_ACK arrivals keep working until S3 replaces the routing. | ST15 Live behaviour: the panel updates while open without losing scroll or an expanded item; a second window shows the same state; reload keeps unread state; switching windows with the panel open closes it. | C1 Ordinary operation feedback keeps a visible, accessible destination with the panel closed: clipboard failures, unknown destinations, operation failures and successes of the user's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped (ST10, DE5). | C6 (must not break) Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements. | C10 (must not break) Host lifecycle: shell teardown/remount and desktop hide-to-tray/reopen don't duplicate streams or listeners, orphan prompts, lose activity reporting or break idle locking. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing ref-09 needs you shows its count in a badge followed by the words, and names both",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing working with a known fraction draws a ring instead of \"· N%\" text; unknown fractions draw none",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing below 1000 px the pill is an icon (badge or dot) with the same accessible name, without truncated text",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing ref-07 the undo notice names Ctrl+Z, and the shell undo command reverses that notice once",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing a notice whose action is not an undo neither claims Ctrl+Z nor takes the undo command",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership C1 ST7 local action outcomes stay visible in the closed pill and recoverable in Earlier",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership ST8 a witnessed active-to-ready edge completes once; initial ready and reconnect replay stay silent",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership undo pauses while hovered or focused, survives a concurrent arrival, and keeps its action identity",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership C6 retained snapshots and late discovery stay silent; live REQUIRES_ACK arrivals retain desktop escalation",
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership ST5 plugin errors leave active Problems on recovery and keep history; copy failures have visible feedback",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel ST3 opens, closes on pill, Escape and outside pointer, and returns focus",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel C10 teardown and remount keep exactly one activity subscription",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel ST3 C8 shortcut yields to typing, IME, repeat and modal ownership",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel ST15 updates preserve the scrolling element, expanded row and keyed plugin node",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel ST9 renders original plugin content and provenance, removes on uninstall, and layout only hides plugins",
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel ST15 window switch closes the panel; teardown removes keyboard and blur listeners",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#verdict and AI-engine announcers (595 §15.1 E1, moved from the status bar) mirrors the verdict politely and an error verdict assertively, never as \"All systems operational\"",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#verdict and AI-engine announcers (595 §15.1 E1, moved from the status bar) announces the AI engine through its sibling region",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#completion edges (595 §15.3 N1, 663 AI engine; ST8, C6) a witnessed transition completes once, through an intermediate checking stop",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#completion edges (595 §15.3 N1, 663 AI engine; ST8, C6) first load and reconnect never announce a completion (C6)",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#completion edges (595 §15.3 N1, 663 AI engine; ST8, C6) an indexing transition and its ready edge announce one completion, not two",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#completion edges (595 §15.3 N1, 663 AI engine; ST8, C6) installing → online fires exactly one outcome",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#completion edges (595 §15.3 N1, 663 AI engine; ST8, C6) installing → install_failed fires exactly one outcome",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#own-action receipts and desktop escalation (C1, ST14) a local warning receipt announces assertive",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#own-action receipts and desktop escalation (C1, ST14) a local success receipt announces polite",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#own-action receipts and desktop escalation (C1, ST14) a local info receipt announces polite",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#own-action receipts and desktop escalation (C1, ST14) only a live REQUIRES_ACK arrival while unfocused reaches the desktop",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#ST6 durable operations finish with the backend, not on invocation return keeps the invocation task running until the operation history reports complete",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#ST6 durable operations finish with the backend, not on invocation return complete-with-gaps stays a decision and never finishes the task",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#ST6 durable operations finish with the backend, not on invocation return a response arriving after teardown cannot write into the remounted pill",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#ST1/ST9 calm pill keeps the \"Up to date\" accessible name (the label hides by width only) and counts plugin items",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#task rows and announcements moved from the task tray and status bar a cancellable running task shows its measured progress and cancels through the substrate",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts#task rows and announcements moved from the task tray and status bar announces retry waiting with its backlog and deadline"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts"
      ],
      "class": "New",
      "behaviour": "Retired element names stay defined as inert elements so presentations that reference them keep validating, and the frame's status entry stays reserved from authored presentations.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | X3 (must not break) Existing jseval UI steps and release journeys keep working or are updated with recorded reasons; no test is weakened or dropped without a recorded reason. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-status-deck is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-task-list is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-pending-effect-queue is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-agent-activity-panel is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-ai-activity-digest is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-app-update-banner is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-plugin-error-overlay is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-indexing-overlay is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-indexing-overlay-host is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-advisory-toast-host is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-advisory-inbox-drawer is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-advisory-rail-badge is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#ST10 frozen jf-resolution-toast is defined and inert without transports or listeners",
        "modules/ui-web/src/shell-v0/components/RetiredStatusElements.test.ts#the frame decision entry and read view remain reserved from authored presentations"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts"
      ],
      "class": "New",
      "behaviour": "Activity owns the retired digest's actions: 'Undo all AI actions' with its confirm preview naming the irreversible remainder, and 'Save as macro'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places. | ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts#Activity owns the retired digest actions 543-fwd #8 — \"Undo all AI actions\" stages a confirm preview, then confirm undoes",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts#Activity owns the retired digest actions 543-fwd #9 — \"Save as macro\" captures the agent's actions into a macro",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts#Activity owns the retired digest actions 577 #16 — the undo-confirm names the irreversible remainder (honest partial undo)"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts"
      ],
      "class": "New",
      "behaviour": "Opening Settings or switching windows closes the status panel and returns focus to the pill.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST15 Live behaviour: the panel updates while open without losing scroll or an expanded item; a second window shows the same state; reload keeps unread state; switching windows with the panel open closes it. | ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.test.ts#Shell — settings window vs. stage navigation (tempdoc 855) ST15 opening Settings or switching windows closes the status panel and returns focus to the pill"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts"
      ],
      "class": "New",
      "behaviour": "After a remote resume of a quiet queue the resolved hold leaves the panel, focus moves to the panel rather than the page, and 'Indexing resumed.' is announced once. C7: an accepted Resume reads 'Resuming' (and the pill keeps 'Indexing paused') until the engine reports work running; a failed Resume says it could not confirm and drops 'Resuming'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST5 Problems right now lists active conditions only: folder unavailable, unreadable files (per folder, linking to Library), search limited, plugin load failure, AI install failed. A resolved condition leaves the section; history of it remains in Earlier/Activity. | C7 (must not break) Pause/resume truth: overlapping holds (manual, schedule, battery, settings recovery) are all named; pausing vs paused kept; a failed resume says so; an accepted resume shows \"resuming\" until the engine reports work running. | ST15 Live behaviour: the panel updates while open without losing scroll or an expanded item; a second window shows the same state; reload keeps unread state; switching windows with the panel open closes it. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#WP3.7 verifier layout and focus repairs F2 preserves a sensible focus and announces remote quiet-queue Resume once",
        "modules/ui-web/src/shell-v0/components/IndexingPause.test.ts#WP3.7 verifier layout and focus repairs C7 an accepted resume reads \"resuming\" until the engine reports work; a failed resume says so"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts"
      ],
      "class": "New",
      "behaviour": "A delayed indexing file is not counted as running work: Running holds the waiting aggregate and independent agent work only, and the header count says so.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps. | ST13 (must not break) Freshness distinctions: last-known values, unknown counts, keyword-ready vs enrichment pending vs blocked vs complete; no invented percentages or ETAs. | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts#withdraws delayed file work from the running chip while preserving independent agent work"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts"
      ],
      "class": "New",
      "behaviour": "After the aggregate reads ready, a retained SSE file row is qualified 'last observed running' and never makes the pill claim work.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. A frontend task marked done on invocation return is not shown as finished while the durable operation is still running or complete-with-gaps. | ST13 (must not break) Freshness distinctions: last-known values, unknown counts, keyword-ready vs enrichment pending vs blocked vs complete; no invented percentages or ETAs. | ST2 \"Not sure\": with no fresh observation the pill shows the last known state with \"last seen …\" and never claims calm or ready; reachability via the event stream and poll freshness stay separate (a reachable engine with a stale poll is not \"can't reach\"). A short grace (value chosen in design, ≥ 1 poll) before showing \"reconnecting\". | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts#standalone million-row rail shows one real queued count, retry until claim, liveness, both failures, DONE, cancel and resync",
        "modules/ui-web/src/shell-v0/substrates/tasks/indexingJobsQueueRail.test.ts#multiplexed million-row rail shows one real queued count, retry until claim, liveness, both failures, DONE, cancel and resync"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts"
      ],
      "class": "New",
      "behaviour": "Every ceremony opens on arrival in its own modality; selecting another request from the status panel while one is open presents the selected one in its own modality: typed approvals and plugin capability requests modal in the top layer (scroll lock, modality authority), lightweight approvals non-modal.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST12 (must not break) Typed confirmation of high-risk and agent medium-risk proposals; acceptance and rejection outcomes; exact-target binding of backend pending authorizations; withdrawal/expiry reconciliation (transport failure is not expiry). | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 6 (verifier A1) restates the contract from PR #1115: typed approvals and plugin capability consent are modal in the browser top layer whatever is open or queued, from every entry point (queue, panel selection, announcement); lightweight approvals are non-modal.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting lightweight → typed from the status panel applies the selected prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting lightweight → capability from the status panel applies the selected prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting typed → lightweight from the status panel applies the selected prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting capability → lightweight from the status panel applies the selected prompt’s own modality"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts"
      ],
      "class": "New",
      "behaviour": "Selecting a typed approval while a plugin capability request is open (or the reverse) keeps the selected security prompt modal in the top layer: the swap never leaves a modal ceremony non-modal (must-not-break around the A1 repair; both modes are modal on the candidate too).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST12 (must not break) Typed confirmation of high-risk and agent medium-risk proposals; acceptance and rejection outcomes; exact-target binding of backend pending authorizations; withdrawal/expiry reconciliation (transport failure is not expiry). | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 6 (verifier A1) restates the contract from PR #1115: typed approvals and plugin capability consent are modal in the browser top layer whatever is open or queued, from every entry point (queue, panel selection, announcement); lightweight approvals are non-modal.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting typed → capability from the status panel applies the selected prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer A1 selecting capability → typed from the status panel applies the selected prompt’s own modality"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts"
      ],
      "class": "New",
      "behaviour": "In the shell as mounted, a lightweight approval, a typed approval and a plugin capability request each open on arrival in their own modality (typed and capability modal, lightweight non-modal), are listed in Needs you, and the panel's Review reopens a closed ceremony and focuses it (typed field, otherwise Deny).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 8 (CI Measured axe, ui_agent_flows setup_run_approval; regression against main): every authorization ceremony (backend operation approvals, live agent tool approvals, plugin capability consent) opens on arrival exactly as on main, typed approvals and capability consent modal in the browser top layer and lightweight approvals non-modal; the request is also listed in the panel's Needs you, and the panel's Review action focuses (or reopens if dismissed) the same ceremony.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round8-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#C12 a lightweight ceremony opens on arrival in the shell, is listed in Needs you, and Review returns to it",
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#C12 a typed ceremony opens on arrival in the shell, is listed in Needs you, and Review returns to it",
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#C12 a capability ceremony opens on arrival in the shell, is listed in Needs you, and Review returns to it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "A folder whose last scan errored (walkError, e.g. INCOMPLETE_COVERAGE) or whose deletions are unverified is listed in Problems right now in plain words, with the Library action, exactly as the Library headline counts it; while one is listed the pill is not calm.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST1 (Concurrent facts stay discoverable: whatever the pill shows, every actionable condition is listed in the panel.) One status pill in the top bar shows exactly one state, chosen in this order: engine unreachable > search limited (index unavailable, reindex required, schema refused) > needs you (count) > problems right now > working (most important running item, with progress when known) > just finished (6 s) > calm. Calm reads \"Up to date\" with a green dot at window width ≥ 1000 px, dot only below, same accessible name. | ST5 Problems right now lists active conditions only: folder unavailable, unreadable files (per folder, linking to Library), search limited, plugin load failure, AI install failed. A resolved condition leaves the section; history of it remains in Earlier/Activity.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#concurrent source facts B1 a folder whose scan errored is a problem right now, as the Library headline counts it, and the pill never claims calm"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "New",
      "behaviour": "After a reload or in a second window, an MCP request still waiting in the Engine (re-discovered from the retained advisory snapshot and confirmed by id) rejoins the one ceremony queue with its deadline, once; expired, answered or non-request records are not presented, and a failed read is retried rather than treated as expiry.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions. | ST15 Live behaviour: the panel updates while open without losing scroll or an expanded item; a second window shows the same state; reload keeps unread state; switching windows with the panel open closes it. | ST12 (must not break) Typed confirmation of high-risk and agent medium-risk proposals; acceptance and rejection outcomes; exact-target binding of backend pending authorizations; withdrawal/expiry reconciliation (transport failure is not expiry). | C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#pendingAuthorizationBridge (tempdoc 655) B2 after a reload or in a second window, requests still waiting in the Engine rejoin the ceremony queue once"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts"
      ],
      "class": "New",
      "behaviour": "An indexing hold first observed at startup (or after a reconnect) is listed in Needs you without an assertive announcement; a hold that arrives while observed is announced once, as '1 item needs you.'.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C6 (must not break) Historical replay stays silent: startup snapshots, reconnects and late resource discovery list retained records in Earlier without new notices, sounds, badges or completion announcements. | ST4 Needs you lists: backend pending authorizations (agent/MCP operation approvals) with their deadline, frontend proposed effects (agent suggestions), plugin capability requests waiting, indexing holds (manual pause, schedule, battery, settings recovery), app update available or repair required, rebuild gap decision. Each with its existing actions.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts#status feedback ownership B3 C6 a hold observed at startup or after a reconnect is silent; a live hold arrival is announced once in plain grammar"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts"
      ],
      "class": "New",
      "behaviour": "Ctrl+Shift+S does not open the panel while the shell command palette or the Search palette is open, works again once it closed, and opening either palette closes an open panel (one overlay at a time).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | C8 (orchestrator acceptance class for ST3's shortcut) shortcuts ignored in text fields, IME, repeat and under a modal; repair round 6 (verifier B4): under another modal/overlay the panel shortcut must not fire, and opening the palette closes the panel (one overlay at a time).",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel B4 C8 one overlay at a time: the shortcut stands down under either palette, and opening a palette closes the panel"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts"
      ],
      "class": "New",
      "behaviour": "The palette entry \"What's going on\" still opens the status panel: the palette closes (leaving its modality) before the chosen command runs (must-not-break around the B4 repair).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | C8 (orchestrator acceptance class for ST3's shortcut) shortcuts ignored in text fields, IME, repeat and under a modal; repair round 6 (verifier B4): under another modal/overlay the panel shortcut must not fire, and opening the palette closes the panel (one overlay at a time).",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts#status pill and panel B4 the palette entry \"What's going on\" opens the panel once the palette has closed"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.landmarks.test.ts"
      ],
      "class": "New",
      "behaviour": "The open panel adds no banner or contentinfo landmark to the page (axe: landmark-banner-is-top-level, landmark-contentinfo-is-top-level, no duplicate banner/contentinfo, landmark-unique).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "X2 Sizes 1280×800, 900×800, 600×400: nothing overflows sideways; ui-a11y-gate shows no new violations; the proportion baseline is updated deliberately.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.landmarks.test.ts#B5 X2 the open panel adds no page-level banner or contentinfo landmark"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts"
      ],
      "class": "New",
      "behaviour": "In Earlier a failed operation is drawn as a failure (×) and a success as a check, with the outcome in words; ledger codes (GATED, SUCCESS, FAILURE, TYPED_CONFIRM) are never shown raw; row titles share a fixed icon column; at 600 px the panel arrow points at the pill's centre after the panel shifted to fit.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | X1 Dark, light and Windows high contrast for every frame component; contrast ≥ 4.5:1 for text; colour never the only signal; reduced motion respected; no new hard-coded colours, sizes or z-indexes (ui-web gates). | X2 Sizes 1280×800, 900×800, 600×400: nothing overflows sideways; ui-a11y-gate shows no new violations; the proportion baseline is updated deliberately. | Frame design (design-system.md §7 Status panel; ref-09, ref-21/22): the panel is anchored under the pill with its arrow at the pill.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round6-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) a failed operation reads as a failure in Earlier, and ledger codes read as plain words",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#Earlier (moved from the inbox drawer) row titles share one icon column that never stretches with the title",
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts#status pill drawing at 600 px the panel arrow points at the pill where the panel had to shift to fit"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts"
      ],
      "class": "New",
      "behaviour": "A passing notice (own-action receipt, undo, \"Search is up to date\") never takes the pill from engine unreachable, search limited, needs you or problems: the pill keeps its state and the notice shows beside it for its window, with its action; a hover or focus pause cannot hold it longer; the engine's own update/busy wait notices are not shown while the engine is unreachable; the pill and the panel header agree; the notice stays in Earlier.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST1 (Concurrent facts stay discoverable: whatever the pill shows, every actionable condition is listed in the panel.) One status pill in the top bar shows exactly one state, chosen in this order: engine unreachable > search limited (index unavailable, reindex required, schema refused) > needs you (count) > problems right now > working (most important running item, with progress when known) > just finished (6 s) > calm. Calm reads \"Up to date\" with a green dot at window width ≥ 1000 px, dot only below, same accessible name. | C1 Ordinary operation feedback keeps a visible, accessible destination with the panel closed: clipboard failures, unknown destinations, operation failures and successes of the user's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped (ST10, DE5). | Repair round 7 (verifier R1): a transient never displaces a more urgent state (engine unreachable > search limited > needs you > problems); hover/focus pause cannot extend a transient past its window while something more urgent exists; engine unreachable always wins over update/preparing states; pill and panel header agree.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round7-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts#R1 notice precedence needs you keeps the pill while a notice shows beside it; hovering the open pill cannot hold the notice past its window",
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts#R1 notice precedence an own-action undo keeps its action beside an urgent pill and the pill keeps its state",
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts#R1 notice precedence an unreachable engine keeps the pill through repeated update-wait notices, and the panel header agrees"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts"
      ],
      "class": "New",
      "behaviour": "With the panel open, the page dim starts below the bar the pill sits in, so the pill keeps its own contrast; at 600 px the panel keeps an 8 px margin from both window edges and its arrow points at the pill, staying on the straight part of the panel's top edge.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "X1 Dark, light and Windows high contrast for every frame component; contrast ≥ 4.5:1 for text; colour never the only signal; reduced motion respected; no new hard-coded colours, sizes or z-indexes (ui-web gates). | X2 Sizes 1280×800, 900×800, 600×400: nothing overflows sideways; ui-a11y-gate shows no new violations; the proportion baseline is updated deliberately. | Repair round 7 (verifier R2, R4): the pill stays above the page dim (or the dim excludes the top bar) and keeps ≥ 4.5:1 in dark, light and forced colors; at 600 px the panel keeps an 8 px margin and its arrow points at the pill. Frame design (design-system.md §7 Status panel; ref-09, ref-21/22): anchored under the pill with an arrow, over a 28 % dim.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round7-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts#R2 R4 panel placement R2 the page dim starts below the bar the pill sits in",
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts#R2 R4 panel placement R4 at 600 px the panel keeps an 8 px margin from both edges and the arrow stays on its top edge, pointing at the pill"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts",
        "modules/ui-web/src/shell-v0/operations/ActionLedgerClient.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "New",
      "behaviour": "A completed Library removal leaves a notice, recoverable in Earlier, that offers \"Add <folder> again\": it re-adds the same path once (repeat clicks are idempotent), works after leaving the Library, says that adding it again reads its files again, and reports a failed re-add so it can be tried again. Permission (grant) rows in Earlier read \"Allowed\", \"Used\", \"Withdrawn\" or \"Expired\", from a structured grant facet on the ledger row, never the raw action code.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST7 Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate. | C1 Ordinary operation feedback keeps a visible, accessible destination with the panel closed: clipboard failures, unknown destinations, operation failures and successes of the user's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped (ST10, DE5). | Repair round 7 (verifier R3): after Library › Remove from library the Earlier row offers \"Add <folder name> again\" (re-adds the same path, reads it again; plain wording of the consequence), since removal has no undo; approval rows use plain words (\"Allowed\", \"Used\", \"Expired\"…) instead of raw \"grant CONSUMED\"/\"grant ISSUED\".",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round7-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#round 7 repairs R3 permission rows read in plain words, never the raw grant action",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#round 7 repairs R3 a removal notice keeps its add-again action and plain consequence in Earlier",
        "modules/ui-web/src/shell-v0/operations/ActionLedgerClient.test.ts#scan rollup + index tiering (tempdoc 812) R3 grant rows carry their action and humanized subject as a structured facet",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts#R3 a completed removal offers to add the same folder again, once, with its plain consequence",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts#R3 a failed add-again says so and can be tried again"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "Folder rows in Running and Problems right now are named by the folder (last path segment, either separator), with the full path as detail; the collection name never titles a folder, and an unresolved path reads \"A watched folder\".",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST5 Problems right now lists active conditions only: folder unavailable, unreadable files (per folder, linking to Library), search limited, plugin load failure, AI install failed. | ST6 Running lists indexing and meaning-search progress (aggregate plus per-folder where known, \"last seen\" when stale), AI downloads/installs (pause/resume where the existing flow offers it), answering in Search, rebuilds, background agent runs. | Repair round 7 (verifier R4): folder rows in the panel are titled by the folder name with the path as detail, not by collection name (\"default: default\").",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round7-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#round 7 repairs R4 folder rows are named by folder with the path as detail, never by collection"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "The notice placement rule as a pure function: beside the pill for engine unreachable, search limited, needs you and problems; in the pill otherwise; an engine wait notice is hidden while the engine is unreachable. (The function is new in this round; its observable effect is pinned with a fail-before by the StatusPill.precedence R1 checks.)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST1 (Concurrent facts stay discoverable: whatever the pill shows, every actionable condition is listed in the panel.) One status pill in the top bar shows exactly one state, chosen in this order: engine unreachable > search limited (index unavailable, reindex required, schema refused) > needs you (count) > problems right now > working (most important running item, with progress when known) > just finished (6 s) > calm. Calm reads \"Up to date\" with a green dot at window width ≥ 1000 px, dot only below, same accessible name. | Repair round 7 (verifier R1): a transient never displaces a more urgent state (engine unreachable > search limited > needs you > problems); hover/focus pause cannot extend a transient past its window while something more urgent exists; engine unreachable always wins over update/preparing states; pill and panel header agree.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#round 7 repairs R1 a passing notice never takes the pill from a more urgent state; engine waits say nothing while unreachable"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/__test-setup__/happyDomShadowFocus.ts",
        "modules/ui-web/vite.config.js"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST3 Clicking the pill (or a keyboard shortcut, listed in the palette) opens the status panel over the page, anchored under the pill; Escape, outside click or the pill closes it and returns focus to the pill. Sections in order: Needs you, Problems right now, Running, Earlier, plugin row; footer All activity, Health. Empty sections are omitted; an all-calm panel says so in one line. | ST15 Live behaviour: the panel updates while open without losing scroll or an expanded item; a second window shows the same state; reload keeps unread state; switching windows with the panel open closes it.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Work around a Happy DOM 21 defect: ShadowRoot.activeElement throws a TypeError when focus is inside a different shadow tree (its host walk reads undefined.getRootNode()). The setup returns the browser's answer (null) for exactly that throw; vite.config.js adds the file to the vitest setupFiles.",
        "callersAndStateOwners": "Every Happy DOM test in modules/ui-web; the patched getter belongs to Happy DOM's ShadowRoot prototype in the test process only. No production code imports it.",
        "indirectBehaviours": "Only the TypeError path changes (from a crash to null, the value a browser returns when focus is not inside that root). Every other read returns Happy DOM's own value; non-TypeError errors still throw. No test selection, filter, timeout or retry changed; the setupFiles line only appends.",
        "evidence": "Full ui-web vitest run on the final tree: 693 files (8786 tests), all pass (see the builder report). The status panel focus tests (StatusPill.test.ts ST3/ST15, Shell.test.ts ST15) exercise focus inside the panel while other components read their own shadow focus, the case the setup covers.",
        "unresolvedRisks": "A test that relied on the TypeError would now see null; none does in the full run. The workaround should be removed when Happy DOM fixes the getter."
      }
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "X1 Dark, light and Windows high contrast for every frame component; contrast ≥ 4.5:1 for text; colour never the only signal; reduced motion respected; no new hard-coded colours, sizes or z-indexes (ui-web gates). | Design decided in the plan for S1: consolidate presentation over the existing stores; pure derivation functions compute the pill state and panel sections; a status pill in the title bar and a status panel anchored under it (popover, Escape/outside click/pill close it, focus returns); transition detection and live announcements in always-mounted shell code; own-action feedback in the pill's transient slot and recoverable in Earlier; typed ceremonies keep the existing protected host. Frame design (design-system.md §7, §9; ref-07, ref-09): needs-you count in a badge, a progress ring when the fraction is known, the pill as an icon with count or state dot below 1000 px, agent request deadlines as their consequence ('Denied automatically in 4 min'), the undo notice in the pill slot with Ctrl+Z.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Generated list of design-token names (scripts/ci/gen-token-names.mjs over src/styles/tokens.css). Regenerated after adding the frame tokens (--frame-* geometry, including --frame-status-ring for the pill's badge and ring) so frame components use tokens instead of hard-coded sizes.",
        "callersAndStateOwners": "tokens.css is the owner; the generator writes this file; the token-name tests and theme validation read it.",
        "indirectBehaviours": "Additive: nine --frame-* names. No existing token name is removed or renamed.",
        "evidence": "node scripts/ci/gen-token-names.mjs reports 232 tokens and rewrites the file identically on a second run; the ui-web gates (31/31) and the full vitest run pass on the final tree.",
        "unresolvedRisks": "None known."
      }
    },
    {
      "items": [
        "gates/dead-code/baseline.txt"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Relax one dead-code pin: src/api/generated/schema-types/status-response.ts may now hold 9 unused exports instead of 8, because this slice retires the only consumer (StatusDeck's per-component connection dot) of the generated ComponentState export. The predicate is weaker for this one generated file; hand-written exports orphaned by the same retirement were deleted instead of re-pinned.",
        "callersAndStateOwners": "The file is generated from the status-response JSON Schema (the generator owns it; hand edits are overwritten). The dead-code gate (knip report + scripts/governance/run.mjs --gate dead-code) reads gates/dead-code/baseline.txt; no runtime code reads the pin.",
        "indirectBehaviours": "No product behaviour changes: the export is a type with no runtime effect. The gate still fails on any further unused export in this file (pin 9) and on every other file unchanged.",
        "evidence": "knip report on the candidate lists ComponentState in status-response.ts as unused and nothing else new there; the dead-code gate passes with pin 9 and fails with pin 8 on the candidate (the retired StatusDeck was its only importer at the base).",
        "unresolvedRisks": "A later consumer-free export added to this generated file would be absorbed within the same pin only if another is removed; the pin should drop back when the generator stops emitting ComponentState or a consumer returns."
      },
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "ST10 Retired after S1: StatusDeck status bar, rail AI dots and advisory rail badge, AdvisoryToastHost pop-ups, AdvisoryInboxDrawer, AiActivityDigest, TaskList, PendingEffectQueue cards, AgentActivityPanel, AppUpdateBanner, PluginErrorOverlay cards, ResolutionToast (moved into the panel or an inline notice), IndexingOverlay backdrop. Their actions remain reachable from the panel or their window. No mechanism is shown in two places.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/DispatchSource.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "Checks of the retired dispatch-source presenter, its transport chrome, or the agent recall digest helper leave with their production files, which have no remaining production consumers.",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/DispatchSource.ts",
            "modules/ui-web/src/shell-v0/components/TransportChrome.ts",
            "modules/ui-web/src/shell-v0/substrates/recall/agentRecall.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/TransportChrome.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "Checks of the retired dispatch-source presenter, its transport chrome, or the agent recall digest helper leave with their production files, which have no remaining production consumers.",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/DispatchSource.ts",
            "modules/ui-web/src/shell-v0/components/TransportChrome.ts",
            "modules/ui-web/src/shell-v0/substrates/recall/agentRecall.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/substrates/recall/agentRecall.test.ts"
      ],
      "class": "Obsolete",
      "removedChecks": "Checks of the retired dispatch-source presenter, its transport chrome, or the agent recall digest helper leave with their production files, which have no remaining production consumers.",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/ui-web/src/shell-v0/components/DispatchSource.ts",
            "modules/ui-web/src/shell-v0/components/TransportChrome.ts",
            "modules/ui-web/src/shell-v0/substrates/recall/agentRecall.ts"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "One deliberate interaction answers one prompt. A newly presented ceremony (lightweight approval, typed approval, plugin capability request; on arrival, after the previous one is answered, or brought forward by Review) ignores its decision buttons until a press that starts at least 400 ms after it was presented: a double-click, repeated activation of the same native button, a pointer press begun earlier, or Enter/Space pressed again or auto-repeating cannot answer the next queued prompt, and a plugin grant made that way is not persisted. Keyboard activation of a decision completes on the release of an unrepeated press; an activation with no pointer or key press (assistive technology's default action) waits only for the delay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 9 (independent verifier, real Chromium: with two plugin capability requests queued, a native double-click on the first prompt's Allow always granted both plugins, as did repeated activation of the same button, and the grant survived reload): one click or key press answers exactly one permission or approval prompt. After a prompt is answered and the next queued prompt is presented, its decision buttons ignore activation until a fresh, deliberate interaction: the pointer released and pressed again on the new prompt after a short arming delay (at least 400 ms); keyboard Enter/Space auto-repeat must not answer the next prompt either. Applies to every ceremony kind (operation approvals, live agent approvals, capability consent); modality per kind, top layer, fail-closed, focus and one-decision-executes-once are kept.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round9-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a double-click answers only the first queued lightweight prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a double-click answers only the first queued typed prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a double-click answers only the first queued capability prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#repeated activation of the same native button answers only the first lightweight prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#repeated activation of the same native button answers only the first typed prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#repeated activation of the same native button answers only the first capability prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#held Enter auto-repeat answers only the first lightweight prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#held Enter auto-repeat answers only the first typed prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#held Enter auto-repeat answers only the first capability prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a key pressed again before the next prompt arms does not answer it (\"Enter\")",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a key pressed again before the next prompt arms does not answer it (\" \")",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a pointer press that began before the next prompt armed does not answer it",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a fresh \"Enter\" press after the delay answers the next prompt on release",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#assistive-technology activation (no pointer or key press) answers an armed prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#Review re-presents the open prompt: the activation that asked for it cannot answer it",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#a native double-click on Allow always grants only the first plugin, across reload",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#repeated native activation of the same button answers one prompt",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#Enter pressed and held on the next prompt before it arms answers nothing"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Once a prompt has armed, a deliberate pointer click or a fresh Enter/Space press answers it exactly once, for every ceremony kind, and the dialog closes when the queue is empty.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 9 (independent verifier, real Chromium: with two plugin capability requests queued, a native double-click on the first prompt's Allow always granted both plugins, as did repeated activation of the same button, and the grant survived reload): one click or key press answers exactly one permission or approval prompt. After a prompt is answered and the next queued prompt is presented, its decision buttons ignore activation until a fresh, deliberate interaction: the pointer released and pressed again on the new prompt after a short arming delay (at least 400 ms); keyboard Enter/Space auto-repeat must not answer the next prompt either. Applies to every ceremony kind (operation approvals, live agent approvals, capability consent); modality per kind, top layer, fail-closed, focus and one-decision-executes-once are kept.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s1-frame-status-round9-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a fresh press after the delay answers the next lightweight prompt exactly once",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a fresh press after the delay answers the next typed prompt exactly once",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a fresh press after the delay answers the next capability prompt exactly once",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation.test.ts#a fresh \" \" press after the delay answers the next prompt on release",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#a deliberate click after the delay answers the next prompt exactly once",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#a fresh Enter press after the delay answers the next prompt exactly once"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts",
        "modules/ui-web/src/api/statusCompletion.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The decision-click helpers let the ceremony's 400 ms arming delay pass before clicking: AuthorizationHost.test fakes only the performance clock (vi.useFakeTimers({ toFake: ['performance'] }) in beforeEach, real timers restored in afterEach) and activateJfButton advances it 400 ms; pendingAuthorizationWithdrawal's approve() and statusCompletion's mounted-host case already fake all timers and advance them 400 ms before the click. Inputs, prompts, preconditions, the observed decisions, requests and notes, and every expected outcome are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "the frame-build plan: S1 · Status: the pill and status panel, built on the existing data, replacing about a dozen old pieces (the status bar, bell, pop-ups, task list and others)",
          "choice": "C12 (must not break) One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests (run cancelled, engine restart, answered elsewhere) cannot execute and leave the panel. | Repair round 9 (independent verifier, real Chromium: with two plugin capability requests queued, a native double-click on the first prompt's Allow always granted both plugins, as did repeated activation of the same button, and the grant survived reload): one click or key press answers exactly one permission or approval prompt. After a prompt is answered and the next queued prompt is presented, its decision buttons ignore activation until a fresh, deliberate interaction: the pointer released and pressed again on the new prompt after a short arming delay (at least 400 ms); keyboard Enter/Space auto-repeat must not answer the next prompt either. Applies to every ceremony kind (operation approvals, live agent approvals, capability consent); modality per kind, top layer, fail-closed, focus and one-decision-executes-once are kept.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a289-de22-74c0-a1bb-704f5745fe73",
  "verdict": "reject",
  "digest": "sha256:82de380d06c092dd16f73949a3f699723e66e27d4a94fda88750a97b32cfbaab",
  "note": "Rejected: selecting a typed approval or plugin-consent request from the status panel while a lightweight approval is open leaves the security dialog non-modal and outside the top layer (real Chrome); the Adaptation entry drops existing assertions (SystemSelfView stale-row progress check); the new IndexingPause C7 check lacks named evidence. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a2d8-2a33-7311-bdea-29958ebbeda6",
  "verdict": "accept",
  "digest": "sha256:cf0e7037f27c393eb0ae955e4fc72a9112c31a5ce04396945406b0260955e8dd",
  "note": "Round 2: 19 assertion failures reproduced before, 131 named checks pass after; A1 verified in Chromium across queue, panel and announcement paths; Adaptation oracle retained; C7 evidence valid. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a334-d0ab-7bc0-b586-e714dd3bed4e",
  "verdict": "reject",
  "digest": "sha256:e7b088cb4f414621d582dc50c668304b256a3f8ae2c1788dd839fa87396a3ea2",
  "note": "Rejected only entry 38 (dead-code baseline 8 -> 9 classified 'Structural rule kept' although the predicate is weaker). Everything else accepted: 30 assertion failures before, 143 named checks pass after, 17 Chromium security checks pass. Transcribed by the orchestrator; the entry was then reclassified as Incidental as the verifier asked."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a37e-8fa8-7971-9669-5348028cc136",
  "verdict": "accept",
  "digest": "sha256:fcfd63724f94781d6d307b3c3ac5e19a8b1df72937878722aca5ac08a887a01a",
  "note": "Final acceptance on b4ee2624ce: the 38 earlier entries unchanged (accepted in rounds 2 and 4); three new Obsolete entries with production-removal sources; decision-record conflict search repeated, none. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
