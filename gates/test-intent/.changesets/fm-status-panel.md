---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: builder-fm-s8-20261116
---

# Slice 8 test intent: the status panel in the first minutes

The status panel opened from the top-bar status follows the agreed picture fm-15 (owner-adopted
design; the picture's words are quoted in each entry because tmp/design is not in git), the owner's
two rules for the picture set, and the orchestrator's delegated decisions H1-H7 as revised after the
challenger (H2', H2'', H3', H3'', H4', H5', S8-7', S8-8', S8-9'). Frontend only.

The three new checks were run by run-evidence with --before e94eb4098f (the base; origin/main is not
valid in this clone), one --check per file, no overlays; gates/test-intent/evidence/fm-status-panel.json. Every file passes on the candidate
and fails on the base by assertion. Per case on the base (a shared clone with the new files copied in):
frameStatus.firstMinutes 29 of 49 fail; the 20 that hold guard the change (unknown amounts, a failed
stage without a reason keeps its row, the four non-operational, non-degraded verdicts, 0 and unknown
files and a stale observation under H2' and again under H2'' in the install state, a stale worker RPC
and a stale indexing source under H2' (RV8-1), a folder searchable at the first snapshot, no id, an
unconfirmed observation, a removal after a failed refresh and a re-add already searchable (RV8-6), a denial, S8-5). StatusPanel.firstMinutes 7 of 9 fail; the developer-tools
view (today's panel) and the hidden notification row hold. StatusPanel.firstMinutes-lockdown 6 of 8 fail; the two "preserved" focus and re-arming cases hold. The five RV8 checks (two RV8-1 stale cases,
RV8-2, RV8-3, RV8-4) also fail by assertion on the first slice-8 candidate 9e35771878; the RV8-5 and RV8-6
checks fail by assertion on the second candidate 558a9ed7af.

H2'' (after the first evidence run): the pictured install state now carries the search verdict a real
Engine without AI models reports (degraded, warn: index.dense_unavailable, lambdamart.not_configured,
inference.deactivated, encoders.health.embedding_not_ready, verbatim from unrequestedAi.test.ts's fresh
install) in all three files, so "Search works" there is shown by the H2'' rule, not by an operational
verdict the picture's moment never has. "degraded" left the H2' list of verdicts that never show it.

Named for the acceptor. Not built for lack of data (owner rule): the picture's "8 files" under the
folder row, because no searchable count per folder exists (fileCount is a disk count). Choices inside
the decisions: (a) "unknown" (an unconfirmed folder observation, e.g. during a rebuild) is no
observation for the witness, so a rebuild never makes a "became searchable" row; (b) "N h" rounds to
the nearest hour (not pinned: the checks read whole hours only); (c) a folder whose path is not yet resolved reads "A watched folder is searchable"
until it resolves, age kept; (d) RV8-3: only successful permission history (issued, used)
leaves the default view with "Allowed" rows; withdrawn and expired permissions, and denials, stay; (e) the outage sentence is the pill's words for
"Can't reach the engine" and "Search limited" only; (f) the stage reason is a second line under the
amounts ("<stage>: <reason>"); (g) the per-row "Unread" marker goes with the unread count behind
developer tools; (h) a clicked notification's row without controls takes focus itself; (i) with
developer tools on, the panel is today's panel (gate rows folded as before, no separate failure row).

Review repairs (orchestrator's decisions on RV8-1..4, 2026-11-16): "Search works" also needs a confirmed
indexing observation (RV8-1); the folder Earlier row shows only while the folder is searchable now, its
witnessed time kept through a rebuild or a failed count (RV8-2); withdrawn and expired permission rows
stay in the default view (RV8-3, reverses part of frameStatus.test.ts R3's Obsolete removal); Details a
developer tool opened close when developer tools turn off, failure rows keep theirs (RV8-4).
Second review repairs (orchestrator's decisions on RV8-5..6, 2026-11-16): the folder Earlier row also
needs live folder evidence, the witnessed time kept for recovery (RV8-5); a confirmed removal forgets the
folder's witnessed time whatever the list's freshness, so a re-add already searchable shows no old row
(RV8-6). The list drops a folder only on a confirmed removal or a reset (profile switch, new Engine
process, both of which already clear the panel's folder memory), so the memory now forgets every
unlisted folder without asking whether the list is live.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.firstMinutes.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-status-panel.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.firstMinutes.test.ts"
      ],
      "behaviour": "The status panel's model in the first minutes. One \"Installing AI\" row with \"1.3 of 2.6 GB · about 24 min left\" and Pause, Resume and \"· Paused\" while paused, no time left while paused, preparing, verifying or setting up or without an estimate, no \"of\" without amounts; the AI install's stage rows folded into it, a blocked stage's reason on the row, a failed stage without a reason keeps its own row; one install entry after a reload (S8-1, H3', S8-7'). \"Search works\" only from a fresh verdict with own searchable files, operational or degraded with no search-unavailable cause: shown in the pictured install, AI not installed or installing with only the AI-absent reasons a real Engine reports; never while checking, connecting, transitioning, unreachable, stale or with 0 or unknown files, nor with a search-unavailable cause (index.failed, index.corrupt, component.start_deadline) or a reindex cause (index.embedding_mismatch), nor while the worker RPC or the indexing source is stale even with a positive searchable count (RV8-1); an outage says \"Can't reach the engine\" / \"Search limited\" instead (H2', H2'', S8-6). A folder: reading while it runs; \"Your Documents folder is searchable\" in Earlier only when this window saw it go from reading to searchable, aged \"just now\", \"12 min\", \"3 h\" from that moment, no file count; none for a folder searchable at the first observation, an unconfirmed observation, a folder without an id or a rebuild; the age survives path resolution; leaving searchable or removal takes the row away and a later searchable or re-add is new; a rebuild, a failed count or a failed folder refresh after it was witnessed hides the row and searchable again on live folder evidence shows it with its first age (RV8-2, RV8-5); a folder removed after a failed refresh and added back already searchable has no old row (RV8-6); during a rebuild the folder row does not claim reading; the retained failure count reaches the problem row (H3'', H6). Successful add-folder outcomes, \"Allowed\" history (gate, grant, retained request notice) and \"Draft kept\" are out of the default view and back with developer tools; an allowed operation that failed or finished incomplete becomes its own row in today's words; a denial keeps its row; withdrawn and expired permissions keep theirs, issued and used ones leave the default view (H4', RV8-3). Approvals keep target, risk and no-undo lines; a folder needing attention and reduced search keep their rows and actions (S8-5).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "Anything a picture shows that the app has no data for: build the screen without it and name it in the delivery. Never fill it with a made-up value.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H2' (orchestrator, 2026-11-16, verbatim): \"\\\"Search works\\\" only when computeVerdict kind === 'operational' and fresh own searchable files > 0 (not checking/connecting/transitioning/degraded/unreachable, not stale). The sentence slot under the heading otherwise shows today's summary when it is a problem (e.g. \\\"Can't reach the engine\\\"), or nothing.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H2'' (orchestrator, 2026-11-16, verbatim, delegated, reversible): \"H2'' \"Search works\" also when verdict kind is 'degraded' and none of its reasons is a search-unavailable cause (isSearchUnavailableCause) and the pill is not 'limited'/'unreachable' — i.e. exact-word search runs while only AI parts are missing (fm-15 pictures it during the install). Other conditions of H2' unchanged.\" H2' was: only when connected, fresh (not stale), and own searchable files > 0; otherwise the outage sentence in today's words, or nothing.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H3' (orchestrator, 2026-11-16, verbatim): \"Install: one \\\"Installing AI\\\" row; `aiinstall:` stage tasks suppressed while it exists, but a blocked/failed stage's reason shows on that row (if the row can't carry it, the stage row stays). \\\"X of Y\\\" only with known amounts; ETA only while downloading with remainingSeconds > 0 (same rule as AI Brain: none while paused, preparing, verifying, setting up). Paused/failed keep 7b's words.\" With H3: \"with fm-12's \\\"X of Y · about N min left\\\" (ETA only when > 0) and \\\"Pause\\\"/\\\"Resume\\\"\".",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H3'' (orchestrator, 2026-11-16, verbatim): \"Folder: Earlier \\\"Your <name> folder is searchable\\\" only when this window saw that root (stable root id/pathHash; no id, no row) go from a non-ready state to folderStatus 'ready' in the same profile and engine epoch; age from that moment (\\\"just now\\\", \\\"N min\\\", \\\"N h\\\"), not reset by path resolution or refresh. A ready root at first snapshot gets no Earlier row. When it leaves ready (reading again, failure, removed) the Earlier row goes and the latest state shows as today; re-added = new. Other folder states keep today's rows and their Details/actions. Fix the panel's folderStatus inputs to the shared ones (provisional, retained failure count) — today's bug, in scope as it decides the row.\" With H3: \"The file count is shown only from a searchable count for that folder; no such count exists, so no count (named; fileCount is a disk count). Successful add-folder operation outcomes do not appear in Earlier (the folder row says it); failures stay.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H6 (orchestrator, 2026-11-16, verbatim): \"After a reload Earlier shows only what this window can witness or durable history already shows today (no reconstructed \\\"became searchable\\\" rows); named.\" S8-7' (verbatim): \"dedup guarantee bounded to install and folder rows.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S8-5 (orchestrator's scenario, 2026-11-16): \"Approvals pending, failures, folder problems and reduced search keep their rows and actions (safety unchanged).\" S8-6: \"\\\"Search works\\\" absent while unknown, stale, zero files, or search unavailable; never shown with a problem row that says search is unavailable.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decisions on review findings (2026-11-16, verbatim, delegated, reversible): \"RV8-1 accepted: \\\"Search works\\\" also requires fresh indexing evidence (not workerRpcStale, staleSources without indexing).\" \"RV8-2 accepted: the folder Earlier row shows only while folderStatus is 'ready' now; memory of the witnessed time may stay, row suppressed while unknown/rebuilding/count failed.\" \"RV8-3 accepted (H4' says successful history only): withdrawn and expired permission rows stay in the default view; only successful issuance/consumption hidden.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decisions on review findings (2026-11-16, verbatim, delegated, reversible): \"RV8-5 the folder Earlier row needs live folder evidence (rootsLive) to show; witnessed time kept for recovery. RV8-6 confirmed removal (forgetIndexedRoots) clears the folder's witnessed memory regardless of observation freshness; re-add already ready shows no old row.\"",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-status-panel.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes.test.ts"
      ],
      "behaviour": "The rendered panel as the picture shows it, with the AI-absent search verdict a real Engine reports while AI installs (H2''): \"What's going on\", \"Search works\", headings \"Running\" and \"Earlier\" without counts, \"Installing AI\" with \"1.3 of 2.6 GB · about 24 min left\" and Pause only on AI Brain, \"Your Documents folder is searchable\" aged \"12 min\" with no file count and no controls, footer \"All activity\" and \"Health\"; no unread marker, unread count, Mark all read, Draft kept, Allowed or operation name (S8-1, H1, H4'). \"Open AI Brain\" on Search and Library, not on AI Brain, following the area while open (S8-3, H5'). Earlier Details only on a failure by default; developer tools bring back the unread count, the Unread marker, Mark all read, Details and \"Allowed: …\"; an Earlier row's Details opened with developer tools close when they turn off and stay closed when they return, a failure's stay open (S8-4, H4', RV8-4). Through the real status pill: Pause posts /api/ai/install/pause as today, keeps focus, and the same control reads Resume once the status says paused; a refused request shows \"Could not pause the download.\" with focus kept (S8-9'); the panel follows the per-profile developer-tools preference; a clicked notification for a row the default view leaves out opens the panel without error (S8-8').",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H1 (orchestrator, 2026-11-16, verbatim): \"Sections: \\\"Needs you\\\" and \\\"Problems right now\\\" stay (only when they have entries, today's words for approvals/safety); then \\\"Running\\\" and \\\"Earlier\\\" as pictured. No counts in the header or section headings; no unread count, no \\\"Mark all read\\\" in the default view (acknowledgement storage and its writes unchanged; behind developer tools if kept at all).\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H5/H5' (orchestrator, 2026-11-16, verbatim): \"\\\"Open AI Brain\\\" only when the current area is not AI Brain (active area passed from Shell).\" \"as H5, updating when the area changes with the panel open.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S8-9' (orchestrator, 2026-11-16, verbatim): \"Pause posts the request as today; the label follows observed state (Resume only when status says paused); request failure shows today's error; focus stays on the control.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S8-8' (orchestrator, 2026-11-16, verbatim): \"concrete: focus recovery when a focused action/row disappears, approval rows keep arming when rows move, Escape as today, forced colors, notification activation for a hidden row opens the panel without error.\" S8-8 (draft scenario): \"Keyboard, focus, Escape, announcements unchanged; light and dark; 600 px; 200% text.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decision on review finding RV8-4 (2026-11-16, verbatim, delegated, reversible): \"RV8-4 partly: H4' keeps Details on failure rows (recovery), including Earlier failures — no change there; but expanded Details of a developer-gated row must close/hide when developer tools turns off — fix.\"",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes-lockdown.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "repaired": true,
      "evidence": "gates/test-intent/evidence/fm-status-panel.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.firstMinutes-lockdown.test.ts"
      ],
      "behaviour": "Real headless Chromium over the real panel and derivation, the pictured install carrying the AI-absent search verdict (H2''): a focused \"Open AI Brain\" that leaves on arriving at AI Brain hands focus to Pause in its row; a focused row that leaves hands focus to the panel (preserved); Pause keeps focus and the same control reads Resume with \"Installing AI · Paused\"; an Allow control that moved re-arms before a click counts (preserved); developer tools on and off bring the unread count, Mark all read and Details and take them away; at 600 px with text at 200% in dark and light the pictured words are all present, the panel stays within the window with nothing clipped and readable text; in forced colours keyboard focus on Pause has a visible outline (S8-8', S8-9', H5', H4').",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H5/H5' (orchestrator, 2026-11-16, verbatim): \"\\\"Open AI Brain\\\" only when the current area is not AI Brain (active area passed from Shell).\" \"as H5, updating when the area changes with the panel open.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S8-9' (orchestrator, 2026-11-16, verbatim): \"Pause posts the request as today; the label follows observed state (Resume only when status says paused); request failure shows today's error; focus stays on the control.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "S8-8' (orchestrator, 2026-11-16, verbatim): \"concrete: focus recovery when a focused action/row disappears, approval rows keep arming when rows move, Escape as today, forced colors, notification activation for a hidden row opens the panel without error.\" S8-8 (draft scenario): \"Keyboard, focus, Escape, announcements unchanged; light and dark; 600 px; 200% text.\"",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/indexingTaskWaiting.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H1 (orchestrator, 2026-11-16, verbatim): \"Sections: \\\"Needs you\\\" and \\\"Problems right now\\\" stay (only when they have entries, today's words for approvals/safety); then \\\"Running\\\" and \\\"Earlier\\\" as pictured. No counts in the header or section headings; no unread count, no \\\"Mark all read\\\" in the default view (acknowledgement storage and its writes unchanged; behind developer tools if kept at all).\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "The panel header no longer says \"2 running\" (H1: no counts in the header or headings). The check now reads the two rows listed under Running (the waiting aggregate and the agent task) and that no \"N running\" count shows; the model assertion that the delayed file is not running work is unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H1 (orchestrator, 2026-11-16, verbatim): \"Sections: \\\"Needs you\\\" and \\\"Problems right now\\\" stay (only when they have entries, today's words for approvals/safety); then \\\"Running\\\" and \\\"Earlier\\\" as pictured. No counts in the header or section headings; no unread count, no \\\"Mark all read\\\" in the default view (acknowledgement storage and its writes unchanged; behind developer tools if kept at all).\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "Earlier's unread count, Unread markers, Mark all read and Details on Earlier rows are what Settings › Advanced › Show developer tools shows (H1, H4'); the five cases that read them (the unread marker, Mark all read, the three diagnostics cases) turn that per-profile preference on (reset per test), the other seven run with it off. The cases' inputs and expectations are unchanged; the default view's absence of these is pinned in StatusPanel.firstMinutes.test.ts."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.precedence.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H1 (orchestrator, 2026-11-16, verbatim): \"Sections: \\\"Needs you\\\" and \\\"Problems right now\\\" stay (only when they have entries, today's words for approvals/safety); then \\\"Running\\\" and \\\"Earlier\\\" as pictured. No counts in the header or section headings; no unread count, no \\\"Mark all read\\\" in the default view (acknowledgement storage and its writes unchanged; behind developer tools if kept at all).\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "The open panel's header said \"1 need you\"; with no counts in the header (H1) the check reads the one request listed under Needs you and that the header has no count. The pill's \"1 needs you\" name, the notice beside it and its window are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "ST15 expands an Earlier row's Details to check keyed updates keep scroll and disclosure; Details on Earlier rows is a developer tool (H4'), so the case sets the panel's developer view first. Its inputs and expectations are unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "An allowed request's history (\"Allowed\") is out of the default view and back with developer tools (fm-15 \"never … \\\"Allowed: …\\\"\", H4'). Three cases now assert the default view leaves out only the allowed row (denials and automatic denials stay) and run their original expectations with developer: true."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "L16's one row per decision (\"Allowed: Reindex\" with \"Allowed · completed/failed\") is the developer-tools view of gate history (H4'); the helper derives with developer: true. Every input and expected row is unchanged; the default view (no Allowed row, a failed run as its own row) is pinned in frameStatus.firstMinutes.test.ts."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "fm-15 status panel picture: heading \"What's going on\"; \"Search works\"; Running: \"Installing AI\", \"1.3 of 2.6 GB · about 24 min left\", \"Pause\"; Earlier: \"Your Documents folder is searchable\", \"8 files\", \"12 min\"; footer \"All activity\", \"Health\". Note: \"Status panel in the first minutes. One entry per thing, showing its latest state, in the user's words; \\\"Open AI Brain\\\" appears only on other pages. never operation names (\\\"Add Watched Folder\\\", \\\"Allowed: …\\\"). No \\\"Draft kept\\\", no \\\"0 unread\\\".\"",
          "location": "task t-20261113-fmimpl agreement, picture note fm-15",
          "label": "owner words"
        },
        {
          "kind": "owner-words",
          "quote": "What a picture does not show is not on that screen by default: it goes behind Settings › Advanced › Show developer tools, or away.",
          "location": "task t-20261113-fmimpl agreement, owner rules for the picture set",
          "label": "owner words"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H3' (orchestrator, 2026-11-16, verbatim): \"Install: one \\\"Installing AI\\\" row; `aiinstall:` stage tasks suppressed while it exists, but a blocked/failed stage's reason shows on that row (if the row can't carry it, the stage row stays). \\\"X of Y\\\" only with known amounts; ETA only while downloading with remainingSeconds > 0 (same rule as AI Brain: none while paused, preparing, verifying, setting up). Paused/failed keep 7b's words.\" With H3: \"with fm-12's \\\"X of Y · about N min left\\\" (ETA only when > 0) and \\\"Pause\\\"/\\\"Resume\\\"\".",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H3'' (orchestrator, 2026-11-16, verbatim): \"Folder: Earlier \\\"Your <name> folder is searchable\\\" only when this window saw that root (stable root id/pathHash; no id, no row) go from a non-ready state to folderStatus 'ready' in the same profile and engine epoch; age from that moment (\\\"just now\\\", \\\"N min\\\", \\\"N h\\\"), not reset by path resolution or refresh. A ready root at first snapshot gets no Earlier row. When it leaves ready (reading again, failure, removed) the Earlier row goes and the latest state shows as today; re-added = new. Other folder states keep today's rows and their Details/actions. Fix the panel's folderStatus inputs to the shared ones (provisional, retained failure count) — today's bug, in scope as it decides the row.\" With H3: \"The file count is shown only from a searchable count for that folder; no such count exists, so no count (named; fileCount is a disk count). Successful add-folder operation outcomes do not appear in Earlier (the folder row says it); failures stay.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "H4' (orchestrator, 2026-11-16, verbatim): \"Narrowed: hidden in the default view: successful add-folder outcomes (the folder row says it), successful gate history (\\\"Allowed: …\\\" rows), \\\"Draft kept\\\" receipts, Details on Earlier rows, unread counts and \\\"Mark all read\\\". An operation failure or incomplete outcome folded into a gate row today becomes its own row in the default view (its existing failure words, no operation-name title change beyond today). Approval rows keep target, risk and irreversibility. Details on active/failure rows stay. Other producers' titles unchanged in this slice (named gap). Developer tools = existing per-profile showDeveloperTools; with it on, gate history, Details, unread count and Mark all read return with their acknowledgement writes. No automatic acknowledgement; recall seen cursor untouched.\"",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "You are supposed to be the implementation session",
          "delegationLocation": "task t-20261113-fmimpl request, the user's turn of 2026-11-13T15:05Z",
          "delegationScope": "implement the agreed first-minutes design set",
          "choice": "Orchestrator decisions on review findings (2026-11-16, verbatim, delegated, reversible): \"RV8-1 accepted: \\\"Search works\\\" also requires fresh indexing evidence (not workerRpcStale, staleSources without indexing).\" \"RV8-2 accepted: the folder Earlier row shows only while folderStatus is 'ready' now; memory of the witnessed time may stay, row suppressed while unknown/rebuilding/count failed.\" \"RV8-3 accepted (H4' says successful history only): withdrawn and expired permission rows stay in the default view; only successful issuance/consumption hidden.\"",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions: status panel, status pill, unread, Mark all read, Earlier, What's going on, gate/grant history): none",
      "whatChanged": "Three cases: ST6 install route labels are \"Pause\"/\"Resume\" (fm-15's \"Pause\", H3') instead of \"Pause download\"/\"Resume download\", target and kind unchanged; ST6 concurrent facts: with Library's folder inputs (H3'') a folder during a rebuild is unconfirmed and has no reading row, so the rebuild/answering/background order is checked without it and the folder row's staleness is checked in a settled derivation; R3: issued and used permission rows are out of the default view and keep their plain words with developer tools (H4'); withdrawn and expired permission rows stay in the default view (RV8-3 reverses this part of slice 8's first removal, which had taken every permission row out)."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-mbmmjnil",
  "verdict": "accept",
  "digest": "sha256:a22c8e5b2e40edad6cb935da02cab2a2b17ba82ff9ea62ff959219925933377e"
}
```
