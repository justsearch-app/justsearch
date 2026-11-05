---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: d0e4b16a-ce65-48b0-a9a8-e378ec24c39a
---

# Frame status, decision and confirmation polish (S3c)

Eleven rough edges left by the status, review, own-action and frame slices, each fixed at its owner:

- L12: a broadcast request that names no source waits in Needs you like every other outside request
  (`operations/authorizationBroker.ts` `isOutsideTransport`, used by the bridge). Only the person's own
  window gestures (BUTTON, PALETTE, RAIL) and plugin consent they trigger open a dialog at once.
- L13: the agent in Search's request carries its own `reject` (the run's `/api/chat/reject`), so a Deny
  the run refuses keeps the request in Needs you, answerable, saying it could not be cancelled.
- L15: after a reload or in a later window, the bridge rediscovers an outside request from the
  action ledger's open gate row (HTTP callers have no retained record) and lists it silently; Earlier
  shows such a row only on an outcome, its expiry, or once the Engine says it no longer waits.
- L16: one Earlier row per decision: an Allow's one-time permission (ISSUED, CONSUMED) and the
  operation it allowed become its latest state ("Allowed · completed"); Activity keeps every row.
  The rows are linked by the request's own id, never by operation and timing: the Engine writes the
  request's `pendingId` (an optional, additive field) on the grant ISSUED and CONSUMED rows of a
  permission an Allow minted (`ConsentCapsuleService`) and on the completion row of the run that
  permission admitted (`OperationExecutorImpl` links the run's operation key to the request when it
  uses the permission; `ActionLedgerChangeRegistry` writes it on that key's live and journaled
  completion row). Rows without it (older rows; durable "always allow" grants; capsules minted outside
  a pending request, such as the agent in Search's own approvals; completions projected after a
  restart, agent-loop tool rows and uncommitted storage-failure rows) are never joined: the decision
  then reads just "Allowed" and those rows keep their own words. The first three L16 checks' grant and
  operation fixtures now carry that id, as the Engine writes it; their assertions are unchanged.
- L17: the protected dialog says who asks, what it acts on, its risk, whether it can be undone and the
  exact phrase to type, never the Engine's gate text or internal names other than that phrase.
- L19: a failed own action whose engine part is not running says so in plain words
  (`OperationClient.invoke`, `CAPABILITY_UNAVAILABLE`); the capability stays in the error details.
- L20: the folder undo checks whether the folder is already back (the existing add preview) and then
  says so instead of sending another add.
- L18/L21: a collection band closes for good when its source leaves; `primitives/focusKeeper.ts` keeps
  keyboard focus on its control across reorders, and hands it to a neighbour when its row leaves
  (Library, Settings, failed files); a failed-files refresh keeps listed rows and their bands. The
  keeper knows a control by its row's key (`data-focus-row` on folder, source, attention, failed-file
  and theme rows) and its role in that row (its name, else its place), never by its position: after
  any reorder focus is on the same row's same control (a move is what the DOM says: an ancestor of the
  control was taken out), and only when that row leaves does focus go to the next row's same control,
  else the list's fallback ("Add folder", the drawer's Close), else the nearest control left.
- L6 and the top bar: plugin tooltips read the item's rendered text through its shadow root (or its
  declared label); the provenance chip keeps whole words; the narrow area menu closes once its button
  is gone; a notice beside the pill wraps instead of pushing the switcher off centre; the pill shows
  the number of problems as it does for needs-you.

Engine change (L16 only): one optional field, `pendingId`, on the action ledger's grant and operation
rows (wire, live ring and audit journal); readers tolerate its absence, row ids are unchanged, no
migration. Evidence runs used a temporary Git command adapter (a
before-state is a shared clone in the session's temporary folder; nothing is registered in the
source repository) and a Node command adapter that adds `--maxWorkers=4` to Vitest. The evidence
producer is unchanged. Focus, layout and size checks run in isolated headless Chromium over private
Vite servers; no shared dev stack or another session's service is used.

`shell-s3c-repaired.json` holds every repaired check with its fail-before against 7ed8454d85 (re-run
after the L16/L21 repair edited two of its files). `shell-s3c-l16-l21-repaired.json` holds the checks
of the L16/L21 repair with their fail-before against 511b14e88d; its before-state patch
(`shell-s3c-l16-compat.patch`) adds only the two new entry points as no-ops (`linkApproval`,
`setApprovalLinkSink`) so the Java checks compile there. `shell-s3c-all.json` re-runs every check in
every edited or added test file on the candidate.

Top-bar follow-up: `shell-s3c-topbar-repaired.json` records the redirect repair against
`c8b58b5e25cf509dac9313d6085054a70508c46f`; `shell-s3c-topbar-preserved.json` re-runs the other
five checks in the edited Chromium file against their original before-state. At 600 px notices and
actions then shared two rows inside the bar with tooltip text when clamped. The font-metrics repair below supersedes that placement and clamping expectation.
Both evidence runs use temporary shared clones and copied dependencies, with the unchanged producer.

Font-metrics repair: the baseline 1fc4f0bcec passes 24 default-font Chromium checks. DejaVu Sans
with 3 px notice-only letter spacing reproduces both CI failures by assertion (900×700 unknown
window; 1280 px notice beside a problem). Line clamping hid overflow. Notices now use measured
inline geometry and move under the top bar when more than two lines are needed; text is never
clamped. ResizeObserver rechecks text, font and frame dimensions, including returning beside the
pill after widening. `shell-s3c-fonts-repaired.json` records the new regressions against
1fc4f0bcece024cc30624560627ee0e9bd7b7307; `shell-s3c-fonts-preserved.json` refreshes the earlier
checks against their original baseline 7ed8454d85; `shell-s3c-fonts-repaired.json` also reruns every check in
both edited Chromium files. The unchanged producer uses a temporary Git adapter for shared clones
and a Node adapter limiting Vitest to four workers. All checkouts use copied dependencies, private
Vite ports and owned headless Chromium processes; no shared service is touched.

L17 follow-up: `shell-s3c-purpose-repaired.json` records whole-line engine-text hiding, including
mixed purposes, with assertion fail-before against baseline 911102bd9f9b9363aac09af520633c150c671ff4.
Plain-purpose visibility is preservation, not a repair. `shell-s3c-purpose-l17-repaired.json` refreshes
the original engine-text/requester checks against 7ed8454d85; `shell-s3c-purpose-preserved.json`
reruns the two plain-purpose checks and every check in the edited AuthorizationHost test file,
without a fail-before claim. The unchanged producer again uses temporary
shared clones and copied dependencies. The placement retirement below cites the orchestrator
decision; its replacement checks keep their existing font-metrics evidence.

Recorded reason (L12, orchestrator decision): `pendingAuthorizationBridge.test.ts` pinned that a
broadcast with no source keeps its dialog on arrival. The brief retires that: such a request is not
the person's own action, so it waits in Needs you. The own-transport cases of that check stay.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts"
      ],
      "class": "New",
      "behaviour": "A broadcast request that names no source waits in Needs you as an outside request (arrival needs-you, live, its asking time and requester kept), with one live announcement and no dialog on arrival.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a broadcast request naming no source waits in Needs you like other outside requests, with one live announcement and no dialog on arrival; only the person’s own window gestures and plugin consent they trigger open immediately",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a undefined request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts#L12 a live request that names no source waits in Needs you with one announcement; no dialog opens"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "a broadcast request naming no source waits in Needs you like other outside requests, with one live announcement and no dialog on arrival; only the person’s own window gestures and plugin consent they trigger open immediately",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions, \"transport\", \"authorization\", \"needs you\"): none names a dialog for a request without a source.",
      "note": "Retired expectation: the case 'DE3 the person's own undefined request keeps its dialog on arrival' (no arrival on a sourceless broadcast). The BUTTON, PALETTE and RAIL cases of the same check are kept unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/authorizationReject.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts",
        "modules/ui-web/src/api/statusCompletion.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The broadcast fixture (`detail` in the two operations checks; the inline payload of statusCompletion's typed Settings approval) gains transport 'BUTTON' (the person's own window gesture) so its dialog still opens on arrival, as these checks of the dialog's Deny, cancellation, withdrawal, stale-answer handling and approval completion need. Before L12 a sourceless broadcast opened the dialog; now it waits in Needs you. Every assertion, input and observation is unchanged; the fixture's other fields and the peek responses are the same.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L12: an agent request that names no source opens its dialog at once instead of waiting in Needs you. Expected: it waits in Needs you like every other outside request (one live announcement); only plugin consent the person's own action triggered opens at once.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-all.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.denyFailure.test.ts"
      ],
      "class": "New",
      "behaviour": "The agent in Search asks; Deny in the panel posts the run's rejection first. When the run refuses it (503) the request stays in Needs you, answerable, with 'could not be cancelled', and the call stays pending; the next Deny that goes through ends it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L13: Search's in-app Deny gets /api/chat/reject 503 → the request leaves Needs you while the run keeps waiting (unanswerable until it expires). Expected: a failed Deny keeps the request in Needs you, answerable, and says it could not be cancelled (as the backend-request Deny already does).",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.denyFailure.test.ts#L13 a Deny the run could not take keeps the request in Needs you, answerable, saying it could not be cancelled"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts"
      ],
      "class": "New",
      "behaviour": "After a reload, an HTTP request whose only trace is its open ledger gate row, and which the Engine still holds, is listed in Needs you without an announcement or notification, never as 'no longer pending'; it stays across refreshes and moves to Earlier on its outcome. An open row the Engine no longer holds reads 'Approval no longer pending' once the Engine says so.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L15: after a reload or in a later window, an HTTP-sourced waiting request leaves Needs you and reads \"Approval no longer pending\" in Earlier while the engine still holds it pending. Expected: restored requests stay in Needs you while the engine holds them (silently, no announcement); they move to Earlier only on a terminal outcome.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts#L15 after a reload an HTTP request the Engine still holds stays in Needs you, silently, until its outcome"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: an open gate row whose request the Engine no longer holds (404) is shown in Earlier as 'Approval no longer pending'. It passes before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Expected: restored requests stay in Needs you while the engine holds them (silently, no announcement); they move to Earlier only on a terminal outcome.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.restore.test.ts#L15 an open ledger row the Engine no longer holds reads \"no longer pending\" once it says so"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "L4b's 'answered' case modelled an answer elsewhere as only this window no longer listing the request, which is also what a reload looks like (L15). It now also carries the Engine's withdrawal (`withdrawn` with the request id), the signal an answered or expired request produces in this window. Its assertions are unchanged: needs empty, the keyed row and the older row both in Earlier; the waiting and expired cases are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Expected: restored requests stay in Needs you while the engine holds them (silently, no announcement); they move to Earlier only on a terminal outcome.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-all.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts"
      ],
      "class": "New",
      "behaviour": "From the ledger rows one Allow writes (gate APPROVED with its request id, the one-time permission ISSUED and CONSUMED and the operation's outcome, each naming that request id), Earlier shows one row: 'Allowed: <operation>' with its latest state (none, 'Allowed · started', 'Allowed · completed', 'Allowed · failed' with a failure tone). Two Allows keep their own rows and outcomes. A lasting 'always allow' permission and a later run of the same operation without a used permission stay rows of their own.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L16: one Allow adds four Earlier rows (Allowed, Permission Allowed, Permission Used, Completed). Expected: one row per decision showing its latest state (e.g. \"Allowed · completed\"); details stay in Activity.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 one Allow is one Earlier row showing its latest state",
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 each of two Allows keeps its own row and its own outcome",
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 rows that are not this decision's steps stay their own rows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.scope.test.ts"
      ],
      "class": "New",
      "behaviour": "The protected dialog asks 'Let <who> run “<operation name>”?', says who asks ('Asked by Claude Desktop, an agent on this computer'; 'the agent in Search, in this window'), what it acts on, the risk in words ('High risk') and 'No undo'. It never shows the Engine's gate text (gate=, sourceTier, UNTRUSTED, TYPED_CONFIRM, 'requires confirmation') or the operation id except as the phrase to type ('To allow it, type <operation id>:'); its 'always allow' line names the operation by name. The protected header stays.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L17: the protected security dialog shows raw engine text (\"…gate=TYPED_CONFIRM, sourceTier=UNTRUSTED\"). Expected: plain words (who asks, what happens, what to type); no internal names; the typed phrase and top-layer protection unchanged.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-purpose-l17-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#a typed agent request says who asks, what happens and what to type, with no internal names",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#the agent in Search is named as such",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.scope.test.ts#C protected dialog consent names operation and access scope for requester undefined",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.scope.test.ts#C protected dialog consent names operation and access scope for requester Claude Desktop"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationArrival.scope.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L17: the protected security dialog shows raw engine text (\"…gate=TYPED_CONFIRM, sourceTier=UNTRUSTED\"). Expected: plain words (who asks, what happens, what to type); no internal names; the typed phrase and top-layer protection unchanged.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions, \"ceremony\", \"typed\", \"authorization\"): the typed confirmation word stays the operation id and the dialog stays modal in the top layer; none other.",
      "note": "Retired expectations: the dialog shows Engine diagnostics in `purpose` as `authorization-purpose`; the risk reads the raw tier ('HIGH'); the dialog's 'always allow' line names the internal id ('core.ingest-files'). A plain-language purpose remains visible, as the L17 follow-up below records."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Three S1 checks waited for the one-click prompt by the text 'core.first', 'core.second' and 'core.only'; L17 removes the internal id from a one-click prompt, so they wait for its question ('Run “First”?', 'Run “Second”?', 'Run “Only”?') instead. Only the wait anchor moved: every click, timing, checkbox state and approval-body assertion is unchanged. The typed checks still wait for the typed phrase.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L17: the protected security dialog shows raw engine text (\"…gate=TYPED_CONFIRM, sourceTier=UNTRUSTED\"). Expected: plain words (who asks, what happens, what to type); no internal names; the typed phrase and top-layer protection unchanged.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-all.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/OperationClient.test.ts"
      ],
      "class": "New",
      "behaviour": "An own action refused because a part of the Engine is not running (CAPABILITY_UNAVAILABLE, worker-online or inference-online) fails with plain words saying what is not running and what to do next (try again, System › Health; AI Brain for the AI models), without worker-online, inference-online, core.* or 'capability'; the capability and code stay in the error details.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L19: a failed own action's message shows internal names (\"worker-online\", \"core.recover-component\"). Expected: plain words saying what failed and what to do next; internal names only in Activity details or logs.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/OperationClient.test.ts#L19 a failed own action for a stopped worker-online says what failed and what to do, without internal names",
        "modules/ui-web/src/shell-v0/operations/OperationClient.test.ts#L19 a failed own action for a stopped inference-online says what failed and what to do, without internal names"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "New",
      "behaviour": "After a folder removal, when another window has already added the folder back (the Engine's add preview reports it watched), Ctrl+Z sends no add, says \"Can't add docs again: docs is already back in your library.\", never 'Added docs again', and the offer no longer applies to a later Ctrl+Z.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L20: after another window already re-added a folder, Ctrl+Z still says \"Added Notes again\" and sends one more add (a no-op). Expected: an undo that no longer applies is dropped (no notice, no request), or says the folder is already back.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts#L20 Ctrl+Z after another window already added the folder back says so and sends no add"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.focusKeeper-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real keyboard focus in Chromium. A collection's band closes when its source leaves and does not reopen when it returns or updates; a press opens it. Focus stays on the focused control while folder rows (plain and themed) or sources reorder. When a band's item is removed by another update, after a confirmed collection removal, and after a confirmed delete of the last custom theme, focus moves to the next item's control (a named control), never the page. A failed-files refresh keeps the open band of a file still listed, with focus; when that file leaves, focus moves to the next file.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L18: a collection's removal band reopens without a click after the collection disappears and comes back. Expected: a band closes for good when its item disappears; it opens only on a press.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "L21: keyboard focus falls to the page body after a row reorders, after the opener's item is removed by another update, after a confirmed delete/revoke/removal; a global failed-files refresh closes an open band. Expected: focus stays on the focused control across reorders; when its item goes away focus moves to a sensible neighbour (the next row's action, else the list's heading or the \"Add folder\" button), never the body; a failed-files refresh keeps an open band for a file that still exists.",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L18 a collection band closes for good when its source leaves; it opens again only on a press",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus stays on its control when the folder rows reorder (themed=false)",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus stays on its control when the folder rows reorder (themed=true)",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus stays on a source's control when the sources reorder",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 a band whose folder another update removed hands focus to the next folder, never the page (themed=false)",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 a band whose folder another update removed hands focus to the next folder, never the page (themed=true)",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 a band whose source another update removed hands focus to the next source",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 a confirmed collection removal hands focus to the next source",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 a failed-files refresh keeps an open band for a file still listed, with focus; a file that leaves hands focus on",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.focusKeeper-lockdown.test.ts#L21 a confirmed delete of the last custom theme by keyboard hands focus to a neighbour, never the page"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/ApprovalLinkedRowsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/intent/ConsentCapsuleServiceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/OperationExecutorImplTest.java"
      ],
      "class": "New",
      "behaviour": "History is true: Earlier joins an Allow's permission and outcome rows only by the request id the Engine wrote on them. Two Allows of one operation 1.5 s apart (first run succeeds, second fails) read 'Allowed · completed' and 'Allowed · failed' on their own rows; a long first run that fails after a later Allow was used keeps its failure on its own decision; rows without the id (older rows) are never joined, the decision reads 'Allowed' and they keep their own words. The Engine writes the id on the ISSUED and CONSUMED rows of a capsule an Allow minted (none for other capsules) and on the live and journaled completion row of each run that capsule admitted; a run no request allowed carries none, and legacy rows read back unchanged. (Linking by the request's own id is the orchestrator's design for this scenario, recorded in the task's round decision; an Earlier row that names another request's outcome is a false record under ST7.)",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-l16-l21-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 two Allows of one operation 1.5 s apart keep their own outcomes when the first run succeeds and the second fails",
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 a long first run that fails after a later Allow was used keeps its failure on its own decision",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/ApprovalLinkedRowsTest.java#grantAndOperationRowsKeepTheirRequestIdentityAcrossTheJournalAndLegacyRowsHaveNone",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/ApprovalLinkedRowsTest.java#eachRunsCompletionRowNamesTheRequestThatAllowedIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/intent/ConsentCapsuleServiceTest.java#anAllowsPermissionRowsNameItsRequestAndOtherCapsulesNameNone",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/OperationExecutorImplTest.java#anAllowedRunsCompletionRowNamesTheRequestThatAllowedIt"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts"
      ],
      "class": "New",
      "behaviour": "Rows without a request id (older ledger rows, paths that cannot know it) are never joined to a decision: the decision reads 'Allowed' and the other rows keep their own words. This already held on the baseline (it did not join rows at all) and is pinned here because the repair adds id-based joining.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with read/unread (existing acknowledgements), Mark all read, and Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed. Read/unread, seen/unseen and active/resolved stay separate.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-l16-legacy.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.decisions.test.ts#L16 rows without a request id are never joined to a decision; each keeps its own words"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Real keyboard focus in Chromium over four expanded folder rows a, b, c, d. With focus on any row's 'Remove from library', the reported orders (b,c,d,a then b,d,a,c then b,d,c,a) and then all 24 orders of the four rows leave focus on that row's same control every time, never the page. When the focused row leaves, focus moves to the next row's same control; when the last row leaves, to 'Add folder'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L21, focus. The focus keeper (primitives/focusKeeper.ts) compares a control's position with one recorded at an older focus event; with folders a, b, c, d and focus on a's \"Remove from library\", reordering to b,c,d,a then b,d,a,c then b,d,c,a loses focus to the page body (across all 24 orders: a lost 4 times, b/c/d once each). Expected: track the focused control by its row's identity (key) and the control's role within the row; after any reorder focus is on the same row's same control; only when that row leaves does focus move to a neighbour (next row's same control, else the list heading or \"Add folder\").",
          "location": "task t-20261103-shell request (S3c repair brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-l16-l21-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus on folder a's \"Remove from library\" stays there through all 24 orders of four rows",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus on folder b's \"Remove from library\" stays there through all 24 orders of four rows",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus on folder c's \"Remove from library\" stays there through all 24 orders of four rows",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 focus on folder d's \"Remove from library\" stays there through all 24 orders of four rows",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.focusKeeper-lockdown.test.ts#L21 when a focused row leaves, focus moves to the next row's same control, else to \"Add folder\""
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The real Shell in Chromium. At 600 px the area menu opened from its button closes at 800 and 1100 px (its button gone), focus moving to the active area in the switcher. At 1280 px, with a problem showing and the unknown-address notice beside the pill, the switcher stays centred (within 1 px), the pill's words are not cut and the notice stays readable with 'Choose destination'. At 600 px a problem shows a count badge '1', not '!'. A plugin item whose words are in its own shadow root has them as its tooltip; one without words has its declared label. A long plugin item's provenance chip keeps every word on one line and its title names the contributor.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Top bar polish (from the frame slice): the narrow area menu stays open, detached from its button, when the window widens past 1000 px (expected: it closes); a long plugin item's provenance chip breaks mid-word (expected: whole words, or truncation with full text available); at 1280 px an unknown-address notice pushes the switcher off centre (expected: the switcher stays centred); at 600 px the pill shows \"!\" for problems instead of a count (expected: the count, as for needs-you).",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "L6: a plugin status item whose text sits inside its own shadow root is clipped with an empty tooltip. Expected: the full text is reachable (tooltip or accessible name taken from the item's rendered text, including its shadow root, or from its declared label).",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-fonts-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 the narrow area menu closes when the window widens past its button, and focus stays on the switcher",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 at 1280 px a notice beside a problem leaves the switcher centred; pill and notice stay readable (default font)",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 at 600 px the pill shows the number of problems, as it does for needs-you",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#L6 a plugin item whose words are in its own shadow root keeps them as its tooltip; an empty one its declared label",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 a long plugin item's provenance chip keeps whole words; its full text stays available"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Redirect notices name windows plainly (Opened System instead of Health), retaining an id only when no plain name is available. Placement follows the orchestrator decision recorded below: beside the pill within two lines, otherwise under the top bar.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "redirect notices name windows by their plain names and use the id only when no plain name is available",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-fonts-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#redirect notices use window names and retain an id only when no name is available"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "task t-20261103-shell user turn (working agreement, Deciding)",
          "delegationScope": "The orchestrator's bounded repair of this PR's own unshipped top-bar notice placement check; preserve all centring, pill and action assertions.",
          "choice": "Orchestrator decision after builder-4: the notice is readable in full; beside the pill within two lines, otherwise under the top bar; never covering the pill or switcher; switcher centred ±2 px. This is the orchestrator's decision, not the owner's.",
          "label": "agent choice (orchestrator decision)"
        }
      ],
      "removedChecks": "In Shell.s3c-lockdown.test.ts, 'redirect notices stay inside the bar in at most two lines with a centred switcher at every size': the unconditional two-line height cap, top/bottom bounds and paintedInside expectations within the bar, truncation with full tooltip/accessible name, and the action staying inside the bar. Those placement expectations from the earlier bounded top-bar brief (also quoted by former entry 19) are superseded. Centring within 2 px, an uncut pill, readable notice and every offered action remain required. Replacement default/wide-font readability checks and their evidence are in the following New entry.",
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md; notice, top bar, redirect, centre/center, clamp): none requires notices or their actions to stay inside the bar, a universal two-line cap, or truncation instead of full readability."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "With DejaVu Sans plus 3 px notice-only letter spacing, the unknown-address notice remains readable in full at 900×700 and the 1280 px notice beside a problem remains readable, without cutting the pill or losing Choose destination. Redirect notices stay fully readable under default and wide metrics, beside the pill when they fit in two lines and otherwise below the bar, with no overlap and centred navigation at 1280×800, 900×700/800 and 600×400.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "task t-20261103-shell user turn (working agreement, Deciding)",
          "delegationScope": "The orchestrator's bounded repair of this PR's own unshipped top-bar notice placement check; preserve all centring, pill and action assertions.",
          "choice": "Orchestrator decision after builder-4: replace the redirect-notice placement assertions with full readability; beside the pill when at most two lines fit, otherwise under the bar; never cover the pill or switcher; keep centring within 2 px, an uncut pill and every offered action. This is the orchestrator's decision, not the owner's.",
          "label": "agent choice (orchestrator decision)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X2",
          "quote": "Sizes 1280×800, 900×800, 600×400: nothing overflows sideways",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "unknown destinations … show at the place of the action … or in the pill; never silently dropped",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-fonts-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts#L3 a notice for a window that does not exist is readable at every size (wide font) 900×700: the notice can be read, the pill is not cut and \"Choose destination\" is offered",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 at 1280 px a notice beside a problem leaves the switcher centred; pill and notice stay readable (wide font)",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#redirect notices stay readable in full beside the pill when they fit, otherwise below the bar; the switcher stays centred (default font)",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#redirect notices stay readable in full beside the pill when they fit, otherwise below the bar; the switcher stays centred (wide font)"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Every check in both edited Chromium files is rerun, including default and wide unknown-address sizes, unknown-id forms and every existing navigation, settings, forced-colour, plugin, name and action assertion. The wide variant forces DejaVu Sans and deterministic 3 px letter spacing on notice text, without widening action labels or changing their assertions. Navigation and readiness both have 45 second timeouts.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Decide what you can judge better than the user, which is most work: scope, scenarios, fixing now or deferring, design options, retries, restarting or resuming work the host stopped.",
          "delegationLocation": "task t-20261103-shell user turn (working agreement, Deciding)",
          "delegationScope": "The orchestrator's bounded repair of this PR's own unshipped top-bar notice placement check; preserve all centring, pill and action assertions.",
          "choice": "Orchestrator decision after builder-4: replace the redirect-notice placement assertions with full readability; beside the pill when at most two lines fit, otherwise under the bar; never cover the pill or switcher; keep centring within 2 px, an uncut pill and every offered action. This is the orchestrator's decision, not the owner's.",
          "label": "agent choice (orchestrator decision)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X2",
          "quote": "Sizes 1280×800, 900×800, 600×400: nothing overflows sideways",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "unknown destinations … show at the place of the action … or in the pill; never silently dropped",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-s3c-fonts-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.frame-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the protected dialog shows a request's purpose only when the whole line is plain words and hides it otherwise",
          "label": "agent choice"
        }
      ],
      "removedChecks": "In 'renders risk, args summary, and an irreversibility warning when provided; never the Engine's gate text', the assertion that the plain purpose 'Rebuild the entire index' is absent. Only that erroneous expectation is retired; risk, arguments, irreversibility, decision, engine-text hiding and typed-phrase checks remain. The expectation that bracketed plain words stay visible is also retired: brackets hide the whole purpose under the same rule.",
      "conflictSearch": "decision records searched for a conflict (docs/decisions/*.md; purpose, ceremony, typed, authorization): none requires hiding a purpose already in plain words. Typed confirmation and top-layer protection remain unchanged."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: the protected dialog retains the plain purpose Rebuild the entire index, together with its typed phrase and security header. The enriched-prompt check also retains its risk, arguments, irreversibility and Deny assertions. Plain-purpose visibility is not a repair against the baseline.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the protected dialog shows a request's purpose only when the whole line is plain words and hides it otherwise",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3c-purpose-preserved.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#renders risk, args summary, and an irreversibility warning when provided; never the Engine's gate text",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#a plain-language purpose stays visible in the protected dialog"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts"
      ],
      "class": "New",
      "behaviour": "Repair: hide the whole purpose if any braces, brackets, equals sign, dotted id token or gate/tier name occurs anywhere in it, while retaining the exact typed phrase. This includes mixed plain and engine text; no partial filtering.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly. tell me when you need me to juge, try to ask me sparely",
          "delegationLocation": "task t-20261103-shell user turn approving the build plan, 2026-11-03",
          "delegationScope": "design decisions of the frame build, within the canonical design",
          "choice": "the protected dialog shows a request's purpose only when the whole line is plain words and hides it otherwise",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3c-purpose-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: TYPED_CONFIRM",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: INLINE_CONFIRM",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: UNTRUSTED",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: TRUSTED",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: HIGH",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: gate=typed",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: sourceTier=untrusted",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Run core.bulk-reindex",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Run vendor.rebuild-index",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Then run core.recover-component",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: {\"scope\":\"all\"}",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: [\"all\"]",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Rebuild {\"scope\":\"all\"}",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Rebuild [\"all\"]",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Rebuild [the index] {\"scope\":\"all\"}",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Rebuild {\"scope\":\"all\"} then {\"mode\":\"replace\"}",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides an engine purpose: Rebuild, then run vendor.rebuild-index",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#hides the whole purpose when plain words contain brackets"
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
  "session": "7787f244-bdf8-46c5-9462-c2403aaf9886",
  "verdict": "accept",
  "digest": "sha256:424d96854c7abc64f06e0c3d2d27cd010ea35f77b2eba0fafc8ce60c9d382217",
  "note": "Round 2 on 279a8dea3b: all 11 items pass, every repaired check fails before by assertion; item 4 held across 1440 orderings of two same-operation Allows; item 9 across all 24 orders. Labelling note: entry 14 labels an orchestrator decision as owner-adopted; its never-join-without-an-id expectation passes on the baseline and follows from L16. Round 1 (Claude verifier 531730fa-109e-421d-aecf-e039bd1fd924) accepted an earlier digest with items 4 and 9 failing; the Codex attempt before it stopped at model capacity. Transcribed by the orchestrator from the verifier's answer."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1acb7-7409-79f0-94de-70154dcc3019",
  "verdict": "accept",
  "digest": "sha256:aeed90971fed7b2f4923c48fc665145ca96a5eab4875bcc1de9a7d63d0e2e692",
  "note": "After the top-bar notice repair: redirect notices stay within the bar at three sizes with full tooltip and accessible name, windows named plainly with an id fallback (54 Chromium cases, centre error 0 px); the unknown-address notice unchanged. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1accc-5bee-7942-a44e-5c61d3bd6f73",
  "verdict": "reject",
  "digest": "sha256:aeed90971fed7b2f4923c48fc665145ca96a5eab4875bcc1de9a7d63d0e2e692",
  "note": "Audit (PR audit-selected): entry 15 cited an orchestrator decision as owner-words, and its repaired checks included the legacy no-request-id case, which passes on the baseline (its recorded failure came from an intermediate revision). Evidence integrity and the other 39 repair checks held. Repaired since: entry 15 cites ST7; the legacy case moved to its own non-repaired entry with its own evidence. Transcribed by the orchestrator from the auditor's answer (Codex sandbox)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1ace1-2a6b-78e1-abd6-d85653ddb1e5",
  "verdict": "accept",
  "digest": "sha256:e73a805573acef8f39d3f6a9bfd24772d38de82258c817d5dd8294643d2163df",
  "note": "After the audit corrections: entry 15 cites ST7 and its expectations follow from it; the legacy no-request-id check is a separate non-repaired entry with valid evidence; all 45 checks' evidence validates, 43 repaired with assertion fail-before; covered tests unchanged. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a1acd5-944a-7e23-8496-c8cedd011975",
  "verdict": "accept",
  "digest": "sha256:e73a805573acef8f39d3f6a9bfd24772d38de82258c817d5dd8294643d2163df",
  "note": "Independent audit (PR audit-selected): all 45 listed checks covered, 43 repaired checks with assertion fail-before; mixed keyed and legacy history across 120 orders fails before and passes after; existing assertion changes accounted for. Transcribed by the orchestrator from the auditor's answer (Codex sandbox)."
}
```
