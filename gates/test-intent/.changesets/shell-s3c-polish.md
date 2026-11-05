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
- L17: the protected dialog says who asks, what it acts on, its risk, whether it can be undone and the
  exact phrase to type, never the Engine's gate text or internal names other than that phrase.
- L19: a failed own action whose engine part is not running says so in plain words
  (`OperationClient.invoke`, `CAPABILITY_UNAVAILABLE`); the capability stays in the error details.
- L20: the folder undo checks whether the folder is already back (the existing add preview) and then
  says so instead of sending another add.
- L18/L21: a collection band closes for good when its source leaves; `primitives/focusKeeper.ts` keeps
  keyboard focus on its control across reorders, and hands it to a neighbour when its row leaves
  (Library, Settings, failed files); a failed-files refresh keeps listed rows and their bands.
- L6 and the top bar: plugin tooltips read the item's rendered text through its shadow root (or its
  declared label); the provenance chip keeps whole words; the narrow area menu closes once its button
  is gone; a notice beside the pill wraps instead of pushing the switcher off centre; the pill shows
  the number of problems as it does for needs-you.

No engine or persisted-format change. Evidence runs used a temporary Git command adapter (a
before-state is a shared clone in the session's temporary folder; nothing is registered in the
source repository) and a Node command adapter that adds `--maxWorkers=4` to Vitest. The evidence
producer is unchanged. Focus, layout and size checks run in isolated headless Chromium over private
Vite servers; no shared dev stack or another session's service is used.

`shell-s3c-repaired.json` holds every repaired check with its fail-before against 7ed8454d85.
`shell-s3c-all.json` re-runs every check in every edited or added test file on the candidate.

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
          "kind": "owner-words",
          "quote": "L12: an agent request that names no source opens its dialog at once instead of waiting in Needs you. Expected: it waits in Needs you like every other outside request (one live announcement); only plugin consent the person's own action triggered opens at once. An existing check pins the old behaviour: change it with a recorded reason (orchestrator decision).",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
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
          "kind": "owner-words",
          "quote": "L12: an agent request that names no source opens its dialog at once instead of waiting in Needs you. Expected: it waits in Needs you like every other outside request (one live announcement); only plugin consent the person's own action triggered opens at once. An existing check pins the old behaviour: change it with a recorded reason (orchestrator decision).",
          "location": "task t-20261103-shell request (S3c builder brief, 2026-11-05)"
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
      "behaviour": "From the ledger rows one Allow writes (gate APPROVED with its request id, the one-time permission ISSUED and CONSUMED, the operation's outcome), Earlier shows one row: 'Allowed: <operation>' with its latest state (none, 'Allowed · started', 'Allowed · completed', 'Allowed · failed' with a failure tone). Two Allows keep their own rows and outcomes. A lasting 'always allow' permission and a later run of the same operation without a used permission stay rows of their own.",
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
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.test.ts#renders risk, args summary, and an irreversibility warning when provided; never the Engine's gate text",
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
      "note": "Retired expectations: the dialog shows the prompt's `purpose` (the Engine's gate message) as `authorization-purpose`; the risk reads the raw tier ('HIGH'); the dialog's 'always allow' line names the internal id ('core.ingest-files')."
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
      "evidence": "gates/test-intent/evidence/shell-s3c-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 the narrow area menu closes when the window widens past its button, and focus stays on the switcher",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 at 1280 px a notice beside a problem leaves the switcher centred; pill and notice stay readable",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 at 600 px the pill shows the number of problems, as it does for needs-you",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#L6 a plugin item whose words are in its own shadow root keeps them as its tooltip; an empty one its declared label",
        "modules/ui-web/src/shell-v0/chrome/Shell.s3c-lockdown.test.ts#11 a long plugin item's provenance chip keeps whole words; its full text stays available"
      ]
    }
  ]
}
```

## Acceptance records
