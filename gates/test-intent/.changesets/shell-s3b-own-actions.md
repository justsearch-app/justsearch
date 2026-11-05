---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s3b-builder
---

# Own risky actions: confirm in place, typed only for wide acts, undo where a reversal exists (S3b)

Own risky actions ask in a band under the item (`components/ConfirmBand.ts`): remove folder,
remove another app's collection, cancel rebuild, delete custom theme, revoke plugin, and the
catalog's inline confirmations (clear failed jobs, rebuild, reset settings), each with a
consequence sentence instead of "Are you sure?". Delete all local data keeps its typed
confirmation, now in a modal dialog; apply exclusions, exact-target forget and typed approvals are
unchanged. Pausing an AI download no longer asks; repairing keeps a non-danger dialog. Saved
skip-rule changes and folder removals offer a real reversal in the pill, Earlier and Activity, and
Ctrl+Z runs it (C8 guards in the key dispatcher). Undo previews execute exactly the previewed set.

L1: the removal's durable receipt (operations.db `result_json`) gains an optional `removedFolder`
object (path, collection). The history projector hands it to the action ledger (live ring and the
journaled wire row, `removedFolder`), and every window offers "Add <folder> again" from it. The
metadata-only operation-history entry and its proto are unchanged. Receipts and journal rows written
before the field existed still read and offer no re-add.

Fail-before: `shell-s3b-repaired.json` runs against a42d2cbed2 with the compatibility patch
`gates/test-intent/compat/shell-s3b-removed-folder-api.patch`, which adds only the new Java types
and overloads (the folder is never recorded, read or carried there) so the Java checks compile and
fail by assertion. `shell-s3b-preservation.json` records checks that pass before and after.
`shell-s3b-new.json` covers new behaviour on new components (their before-state cannot load them),
and `shell-s3b-adapted.json` the rewritten existing checks.

Journal inverse repair: `shell-s3b-folder-undo-repaired.json` runs against a279e9812f;
`shell-s3b-folder-undo-preservation.json` covers visible re-add failure. Only the existing
inverse Effect shape is persisted: successful receipts supply its path and collection.

All added checks are durable; none is scaffolding. No independent acceptance is recorded yet.

Acceptor-1 repair: `shell-s3b-acceptor-repaired.json` compares the four repaired items with
`b1b5ae0f3c400c3ca672fafddd43869412891d41`; focus and real quota failures run in isolated Chromium.
`shell-s3b-acceptor-preservation.json` re-runs every check in the edited ordering files; the prior
folder-removal repair assertions are re-run against their original baseline in
`shell-s3b-acceptor-folder-preservation.json`. Evidence runs use temporary shared clones and private
dependency copies. A temporary Git command adapter makes the evidence runner create shared clones
instead of registering worktrees; a Node command adapter adds `--maxWorkers=4` to its Vitest calls.
The evidence producer itself is unchanged, and source Git metadata is untouched.

Acceptor-2 repair: `shell-s3b-acceptor2-repaired.json` compares cross-kind single-band ownership,
missing success receipts and long-name containment against `2ba5a3ebf37f0c2684e22444af65ed08798a4bba`.
The band/focus/layout and visible polite theme receipt checks run in real isolated Chromium.
`shell-s3b-acceptor2-all.json` re-executes every check in every edited/added test file, plus the
existing folder, cancel and reindex receipt checks. `shell-s3b-acceptor2-prior.json` re-executes
the prior repaired browser assertions against their original b1b5ae0f3c baseline. Temporary Git/Node
command adapters use shared clones, private dependency copies and `--maxWorkers=4`; no source git
metadata, shared dev stack or another session's service is used. No existing assertion is weakened.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts"
      ],
      "class": "New",
      "behaviour": "A palette/user or agent removal becomes reversible only when its successful receipt records the folder. Ctrl+Z re-adds that exact resolved path and collection. Pathless receipts and older argument-less persisted inverses offer no Undo or Ctrl+Z target; a working inverse survives restore. Activity hides its bulk Undo when no reversible agent action exists.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Design rule (binding): never show Undo where no real reversal exists.",
          "location": "task t-20261103-shell request (bounded journal-inverse repair brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-folder-undo-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#a user removal followed by Ctrl+Z re-adds the folder from the successful receipt",
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#a agent removal followed by Ctrl+Z re-adds the folder from the successful receipt",
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#a successful removal without a recorded path offers no Undo or Ctrl+Z target",
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#an older persisted removal with an argument-less inverse offers no Undo after restore",
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#a removal receipt keeps its working re-add inverse across a journal restore",
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#Activity offers no Undo when the agent only removed a folder without a recorded path"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: a failed re-add reports its failure through the shell and the status pill.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A re-add that fails says so at the place of the action or in the pill (never silent).",
          "location": "task t-20261103-shell request (bounded journal-inverse repair brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-folder-undo-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.folderUndo.test.ts#a failed re-add reports the failure in the status pill"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveWatchedRootHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "A completed folder removal's durable receipt keeps the removed folder (path, and collection when not the default), the history projector hands it to the action ledger (live ring and journal), and every window builds Earlier's \"Removed <folder> from your library\" with \"Add <folder> again\" from that ledger row, including after a reload or engine restart; the window that removed it shows one row (its own notice), whose action goes once it can no longer run. The removal handler records the folder only on success; a folder that cannot be recorded never fails the removal; unreadable journal values read as no folder.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE7",
          "quote": "Fail-closed teardown, single-use pending ids, exact server-bound arguments, rebuild gap key+hash binding, forget abort on navigation, durable grants list and revocation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-repaired.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java#aCompletedRemovalReachesTheLedgerWithItsFolderAndTheJournalKeepsItAcrossARestart",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java#theJournalRoundTripsTheFolderAndReadsOlderOrUnreadableRowsWithoutOne",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java#aFolderThatCannotBeRecordedNeverFailsTheRemoval",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveWatchedRootHandlerTest.java#successfulRemovalRecordsTheFolderItRemovedForTheReceipt",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveWatchedRootHandlerTest.java#aDefaultCollectionRemovalRecordsNoCollectionAndAFailureRecordsNoFolder"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: an older removal record without a path, a failed removal and other operations offer no re-add; the re-add is not offered while the folder is watched again; the metadata-only history entry and a receipt written before the field existed still read; Earlier offers Undo only where a backend undo exists and \"Add back to search\" for a forget. Passes before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-preservation.json",
      "newChecks": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java#aFailedRemovalRecordsNoFolderToAddBack",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/RemovedFolderRecordTest.java#aReceiptWrittenBeforeTheFieldExistedStillReadsAndOffersNoReAdd"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts"
      ],
      "class": "New",
      "behaviour": "Ctrl+Z (and Ctrl+Shift+Z) keeps its text-editing meaning inside an input, textarea or contentEditable (also inside a shadow root) and under another modal: the shell Undo does not run and the key is not taken; a user rebinding of Undo keeps the same guard.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: Ctrl+Z (undo) never fires inside text fields where it would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#inside a input it edits the text: nothing runs and the key is not taken",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#inside a textarea it edits the text: nothing runs and the key is not taken",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#inside a contentEditable it edits the text: nothing runs and the key is not taken",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#a field inside a shadow root is still a text field",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#under another modal nothing runs, and it works again once the modal is gone",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#a user rebinding of Undo keeps the same guard"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: outside a text field Ctrl+Z runs the shell Undo and Ctrl+Shift+Z its Redo; during IME composition and on key repeat nothing runs. Passes before and after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: Ctrl+Z (undo) never fires inside text fields where it would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#outside a text field Ctrl+Z runs the shell Undo and Ctrl+Shift+Z its Redo",
        "modules/ui-web/src/shell-v0/commands/KeybindingRegistry.undoGuard.test.ts#during IME composition and on key repeat nothing runs"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts"
      ],
      "class": "New",
      "behaviour": "Confirming an undo preview (Activity's Undo all AI actions, the audit log's Restore to here) reverses exactly the previewed entries, once, and never one recorded after the preview was drawn; the preview says how many actions in its scope cannot be undone.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE6",
          "quote": "Undo where real reversal exists (skip rule add/remove, others the journal supports): the pill shows the undo for a few seconds (paused while hovered/focused), Ctrl+Z works afterwards, and Activity lists it. Undo previews execute exactly the previewed set and disclose what can't be undone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Undo: where the journal or backend supports a real reversal (skip-rule add and remove, folder removal via re-add, and any other the journal already reverses): the pill shows \"<what happened> · Undo\" for a few seconds, held while hovered or focused; Ctrl+Z undoes the last reversible action afterwards (C8 guards); Activity/Earlier lists it with Undo while reversible. An undo preview executes exactly the previewed set and says what cannot be undone. Never show Undo where no real reversal exists.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/ActivitySurface.actions.test.ts#DE6 confirming the undo preview reverses exactly the previewed set, once, not an action that arrived since",
        "modules/ui-web/src/shell-v0/components/EffectAuditLog.test.ts#DE6 — \"restore to here\" says what cannot be undone and reverses exactly the previewed actions"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts"
      ],
      "class": "New",
      "behaviour": "The in-place band (ConfirmBand): consequence sentence, danger button named with the verb and object, Keep it; focus to Keep it; Escape or Keep it cancels once and returns focus to the opener without reaching the window behind; the verb answers once (double-click, repeated activation, auto-repeating Enter/Space); no answer while the host runs the action. Library Remove (under the folder, or under the problem that offered it), catalog operations with an inline confirmation (each with a consequence sentence, a fallback that says it runs now with no undo), Settings' delete custom theme and revoke plugin use it; a folder removed in another window while its band is open closes the band and nothing is removed twice.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "Ordinary operation feedback keeps a visible, accessible destination: failures and successes of the person's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts#an operation without its own sentence still says it runs now with no undo",
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts#Escape in the band cancels it without firing",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts#deleting a custom theme asks in a band naming the theme; Keep it deletes nothing, the verb deletes once",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts#revoking a plugin asks in a band under its row; the verb revokes it"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts"
      ],
      "class": "New",
      "behaviour": "The in-place band (ConfirmBand): consequence sentence, danger button named with the verb and object, Keep it; focus to Keep it; Escape or Keep it cancels once and returns focus to the opener without reaching the window behind; the verb answers once (double-click, repeated activation, auto-repeating Enter/Space); no answer while the host runs the action. Library Remove (under the folder, or under the problem that offered it), catalog operations with an inline confirmation (each with a consequence sentence, a fallback that says it runs now with no undo), Settings' delete custom theme and revoke plugin use it; a folder removed in another window while its band is open closes the band and nothing is removed twice.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "Ordinary operation feedback keeps a visible, accessible destination: failures and successes of the person's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#says the consequence, names the verb and object, offers Keep it, and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#Escape cancels and returns focus to the control that opened it; it does not reach the window behind",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#Keep it cancels once and returns focus to the opener",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#a double-click on the danger button answers once, and no later answer runs",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#an auto-repeating Enter or Space (a key held since the opener) answers nothing",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.test.ts#while the host runs the action, neither answer is offered",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#opens the band under the folder with its consequence and verb, focuses Keep it, and keeping removes nothing",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#a double-click on the verb removes once; Escape while it runs does not cancel it",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#a second window removed the same folder while the band was open: the band goes and nothing is removed twice",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#Remove offered by a missing folder's problem asks under that problem"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts"
      ],
      "class": "New",
      "behaviour": "Delete all local data stays a typed confirmation in a modal dialog (who asked, the question, what is deleted, what stays, that it cannot be undone, the exact phrase with letters remaining, Cancel left and the verb right). Host confirmation dialogs ask a question and answer with a verb (\"Continue?\" / \"Continue\" by default, never \"Confirm\"), and only a destructive request is drawn as danger (repairing an install is not).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Typed confirmation in a dialog stays only for: delete all local data (keep its interaction seam for presentations), apply exclusions as today, exact-target forget, and typed operation approvals (already done in the previous step; do not change). Pausing a download and repairing an install are no longer danger dialogs.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts#opens a modal dialog that names who asked, what is deleted, what stays, that it cannot be undone, and the phrase"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.confirm.test.ts"
      ],
      "class": "New",
      "behaviour": "Delete all local data stays a typed confirmation in a modal dialog (who asked, the question, what is deleted, what stays, that it cannot be undone, the exact phrase with letters remaining, Cancel left and the verb right). Host confirmation dialogs ask a question and answer with a verb (\"Continue?\" / \"Continue\" by default, never \"Confirm\"), and only a destructive request is drawn as danger (repairing an install is not).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Typed confirmation in a dialog stays only for: delete all local data (keep its interaction seam for presentations), apply exclusions as today, exact-target forget, and typed operation approvals (already done in the previous step; do not change). Pausing a download and repairing an install are no longer danger dialogs.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.confirm.test.ts#without options it asks \"Continue?\" and answers \"Continue\", not as danger",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.confirm.test.ts#a titled, non-destructive request keeps its question and verb and is not a danger dialog",
        "modules/ui-web/src/shell-v0/plugin-api/capabilities/ui.confirm.test.ts#a destructive request is drawn as danger and keeps its typed phrase"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts",
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts",
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.ownReversals.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts"
      ],
      "class": "New",
      "behaviour": "A real reversal of the person's own action is offered as an undo notice in the pill (own-action class, Ctrl+Z hint) for a few seconds, held while the notice, its action or the pill is hovered or focused, then kept in Earlier and System › Activity with its action while reversible; it runs once at a time and never after it succeeded; an ordinary failure is reported in its own words and can be retried; a closed reversal says why and is not offered again; Ctrl+Z after the notice runs the newest open reversal unless the Effect Journal holds a newer reversible effect. Library offers it for saved skip-rule changes (writing the rules back once, bound to that save's witness, closing when the settings were saved again since) and offers nothing when nothing changed. The journal's undoEntries reverses exactly a previewed set and reports what it could not undo.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE6",
          "quote": "Undo where real reversal exists (skip rule add/remove, others the journal supports): the pill shows the undo for a few seconds (paused while hovered/focused), Ctrl+Z works afterwards, and Activity lists it. Undo previews execute exactly the previewed set and disclose what can't be undone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Undo: where the journal or backend supports a real reversal (skip-rule add and remove, folder removal via re-add, and any other the journal already reverses): the pill shows \"<what happened> · Undo\" for a few seconds, held while hovered or focused; Ctrl+Z undoes the last reversible action afterwards (C8 guards); Activity/Earlier lists it with Undo while reversible. An undo preview executes exactly the previewed set and says what cannot be undone. Never show Undo where no real reversal exists.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "Ordinary operation feedback keeps a visible, accessible destination: failures and successes of the person's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: Ctrl+Z (undo) never fires inside text fields where it would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts#offers an Undo notice in the own-action class, marked as a reversal with Ctrl+Z",
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts#runs once even when pressed twice, says it worked, and is then no longer offered",
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts#after an ordinary failure it says so in its own words and can be tried again",
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts#a reversal whose window closed says why and is not offered again",
        "modules/ui-web/src/shell-v0/state/ownReversals.test.ts#Ctrl+Z after the notice runs the newest open reversal first",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#shows \"<what happened> · Undo\" for a few seconds, then stays in Earlier with Undo while reversible",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#is held while hovered, and leaves after the time it had left once released",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#is held while focused, and leaves after the time it had left once released",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#the pill trigger holds the notice too",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#saving an added rule offers Undo in the pill; Undo writes the rules back once, bound to that save",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#a removed rule says so, and Ctrl+Z after the notice runs the same reversal",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#after the rules were saved again elsewhere, Undo closes and says why instead of overwriting",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.ownActions.test.ts#saving with nothing changed offers no Undo",
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts#DE6 after the undo notice has gone, Ctrl+Z runs the latest own reversal unless the journal holds a newer reversible effect",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.ownReversals.test.ts#DE6 Activity lists the person's own actions that can still be undone, with their reversal",
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts#reverses the previewed entries newest first and nothing recorded after the preview",
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts#an entry gone by the confirmation is skipped and reported, not counted",
        "modules/ui-web/src/shell-v0/substrates/effects/undoEntries.test.ts#a failing reversal stops the run; it and the rest are reported as not undone"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts"
      ],
      "class": "New",
      "behaviour": "Earlier's \"Add <folder> again\" adds exactly that folder (path and collection) back once however often it is pressed, says so, and reports a refusal so it can be tried again.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C1",
          "quote": "Ordinary operation feedback keeps a visible, accessible destination: failures and successes of the person's own actions show at the place of the action (inline receipt) or in the pill; never silently dropped.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#adds exactly that folder back once, however often it is pressed, and says so",
        "modules/ui-web/src/shell-v0/components/StatusPill.undo.test.ts#a refusal (the folder is already back, or gone from disk) is reported and can be tried again"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In headless Chromium, the in-place band, the undo notice in the pill and the typed delete-all-data dialog in dark, light and forced colours at 1280×800, 900×800 and 600×400: no sideways overflow, answers on screen (the dialog modal and its answers reachable), the band's danger edge visible and Keep it focused.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light and forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X2",
          "quote": "Dark, light and forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE6",
          "quote": "Undo where real reversal exists (skip rule add/remove, others the journal supports): the pill shows the undo for a few seconds (paused while hovered/focused), Ctrl+Z works afterwards, and Activity lists it. Undo previews execute exactly the previewed set and disclose what can't be undone.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-new.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in dark at 1280×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in dark at 900×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in dark at 600×400 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in light at 1280×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in light at 900×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in light at 600×400 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in forced at 1280×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in forced at 900×800 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the in-place band in forced at 600×400 fits, shows both answers and focuses Keep it",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in dark at 1280×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in dark at 900×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in dark at 600×400 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in light at 1280×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in light at 900×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in light at 600×400 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in forced at 1280×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in forced at 900×800 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the undo notice in forced at 600×400 keeps its words and its action on screen",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in dark at 1280×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in dark at 900×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in dark at 600×400 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in light at 1280×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in light at 900×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in light at 600×400 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in forced at 1280×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in forced at 900×800 is modal, fits, and reaches its answers",
        "modules/ui-web/src/shell-v0/components/ConfirmBand.frame-lockdown.test.ts#X1/X2 the typed delete-all-data dialog in forced at 600×400 is modal, fits, and reaches its answers"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Remove now asks in the in-place band instead of the mocked showConfirmDialog returning true: each removal is Remove then the band's verb (helper removeFolder). Kept oracles: removal progress and repeat suppression, the refused/uncertain/obsolete response handling, focus to Add folder, the add-again notice's label, message, detail, single re-add after leaving and its failure message and retry. The confirmation text the old dialog received (inner folders named, ancestor coverage) is now read from the band's consequence; keeping removes nothing. Added: the notice is marked as the reversal and names the folder it restores.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The collection's confirmation moved from showConfirmDialog to the band under the source: the oracle (the count and collection are named before anything is deleted, the DELETE carries exactly that collection, declining issues nothing) is read from the band's heading/consequence and answered with its verb or Keep it. The additional success-receipt oracle is recorded separately as New (C1); all original collection assertions remain.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Cancel rebuild asks in the band under the rebuild instead of showConfirmDialog: the first request is kept (Keep it) and invokes nothing, the second is answered with the verb; the consequence still says the current index stays available; the exact key, consent and recorded message are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE7",
          "quote": "Fail-closed teardown, single-use pending ids, exact server-bound arguments, rebuild gap key+hash binding, forget abort on navigation, durable grants list and revocation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.bulkOutcome.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The cancel case answers the band's verb after pressing Cancel rebuild; the terminal outcome, its code and the button's removal are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BulkOperationImmediateRefusal.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Rebuild (Health, Brain, Health's recommended action) and Library's Cancel rebuild are confirmed through the band's verb instead of the retired .confirm-row Yes button and the mocked dialog; refusal codes, retries, retention and navigation oracles are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HealthSurface.recovery.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The HIGH rebuild's inline confirmation is the band: nothing is invoked or approved before its verb, then the consent flows through the server gate exactly as before; the band's consequence is read where the old prompt was clicked.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "Reset's \"Yes\" is the band's verb (named by the operation) and \"Cancel\" is Keep it; the oracles (confirmation survives stale polling and a status gap with focus kept on the verb, cancellation and refusal and success handling, focus restoration after the request) are unchanged. The \"Are you sure?\" text expectation is retired (Obsolete entry) and replaced by the consequence sentence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The typed confirmation moved into a modal dialog that renders next to the always-present opener, so the verb is looked up inside dialog.delete-dialog instead of as the only danger button; disabled-until-typed, single-use token and Cancel oracles are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Typed confirmation in a dialog stays only for: delete all local data (keep its interaction seam for presentations), apply exclusions as today, exact-target forget, and typed operation approvals (already done in the previous step; do not change). Pausing a download and repairing an install are no longer danger dialogs.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "MEDIUM/INLINE: the prompt is the band; the verb fires once (Yes renamed to the verb) and Keep it returns to idle without firing (Cancel renamed). HIGH/TYPED: the typed form's answer is the verb (the label) instead of \"Confirm\"; enabling on the exact phrase and firing once are unchanged. Acceptor-2: restored the original generic index.flush oracle (no invocation before consent; exactly index.flush/MEDIUM after the verb) in the fallback check. The clear-failed-jobs sentence/invocation check is a separate New entry; the generic invocation oracle is not replaced by it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.design.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The undo notice is produced by offerReversal (the own-action reversal) instead of a raw toast labelled \"Undo\": it still names Ctrl+Z and the shell's undo command runs it once; added: it is not run again after it succeeded.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE6",
          "quote": "Undo where real reversal exists (skip rule add/remove, others the journal supports): the pill shows the undo for a few seconds (paused while hovered/focused), Ctrl+Z works afterwards, and Activity lists it. Undo previews execute exactly the previewed set and disclose what can't be undone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Undo: where the journal or backend supports a real reversal (skip-rule add and remove, folder removal via re-add, and any other the journal already reverses): the pill shows \"<what happened> · Undo\" for a few seconds, held while hovered or focused; Ctrl+Z undoes the last reversible action afterwards (C8 guards); Activity/Earlier lists it with Undo while reversible. An undo preview executes exactly the previewed set and says what cannot be undone. Never show Undo where no real reversal exists.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.status.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Same producer change: the notice comes from offerReversal; not while typing and once after a shell remount are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE6",
          "quote": "Undo where real reversal exists (skip rule add/remove, others the journal supports): the pill shows the undo for a few seconds (paused while hovered/focused), Ctrl+Z works afterwards, and Activity lists it. Undo previews execute exactly the previewed set and disclose what can't be undone.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: Ctrl+Z (undo) never fires inside text fields where it would edit text, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Pause, Resume, Cancel and Repair progress oracles are unchanged; the recorded confirmations are now only Repair (pausing no longer asks: Obsolete entry), and the Repair dialog is recorded as titled and not destructive.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Typed confirmation in a dialog stays only for: delete all local data (keep its interaction seam for presentations), apply exclusions as today, exact-target forget, and typed operation approvals (already done in the previous step; do not change). Pausing a download and repairing an install are no longer danger dialogs.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistoryProjectorTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The projector hands each row's removal record to the ledger through the two-argument persistOperation, so the spy that fails the journal sink stubs persistOperation(any(), any()); the oracle (a failed sink is retried by the real timer without another completion) is unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.settingsRecovery.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "Copy rules: verbs on buttons (\"Remove Notes\", \"Allow\", \"Try again\"); no \"OK\", \"Confirm\", \"Are you sure?\" (design-system §8 words, cited by the brief)",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions, \"confirm\"): 0030 says declared confirmation policy is enforced; the band still asks before an INLINE operation runs and the engine's own typed gate is unchanged, so no conflict. None other.",
      "note": "Retired expectations: the catalog INLINE prompt contains \"Are you sure?\" with a Yes button, and the TYPED form's answer is \"Confirm\". Replaced by the consequence sentence and the verb (New and Adaptation entries)."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE1",
          "quote": "Own risky actions confirm in place (band under the item: consequence sentence, verb button, Keep it; focus to Keep it; Escape cancels and restores focus): remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings. Catalog \"Are you sure?\" confirmations get consequence text.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The in-place band replaces today's window-blocking dialogs and \"Are you sure?\" prompts for: remove folder, remove other-app collection, cancel rebuild, delete custom theme, revoke plugin, clear failed jobs, rebuild, reset settings.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions, \"confirm\"): 0030 says declared confirmation policy is enforced; the band still asks before an INLINE operation runs and the engine's own typed gate is unchanged, so no conflict. None other.",
      "note": "Retired expectations: Remove, removing another app's collection and Cancel rebuild call the window-blocking host.ui.showConfirmDialog (with destructive: true / confirmLabel). They now ask in the in-place band; the tests assert the dialog is not called."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.repairRun.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE2",
          "quote": "Irreversible wide acts keep typed confirmation in a dialog: delete all local data (keeps its interaction seam for presentations), apply exclusions as today, exact-target forget, typed operation approvals. Pausing a download and repairing an install are no longer danger dialogs.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Typed confirmation in a dialog stays only for: delete all local data (keep its interaction seam for presentations), apply exclusions as today, exact-target forget, and typed operation approvals (already done in the previous step; do not change). Pausing a download and repairing an install are no longer danger dialogs.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions, \"confirm\"): 0030 says declared confirmation policy is enforced; the band still asks before an INLINE operation runs and the engine's own typed gate is unchanged, so no conflict. None other.",
      "note": "Retired expectation: Cancel during a download asks a \"Pause download\" confirmation. Pausing keeps everything downloaded and resumes later, so it no longer asks; a notice says what happened."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/themes/token-names.generated.ts"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Regenerated token-name union after adding the frame dialog tokens --frame-dialog-width and --frame-dialog-dim to styles/tokens.css (the typed delete-all-data dialog's width and dim, design system §2).",
        "callersAndStateOwners": "scripts/ci/gen-token-names.mjs writes it from tokens.css; tests and the theme editor import the union. No runtime state.",
        "indirectBehaviours": "Two names added; no name removed or renamed.",
        "evidence": "node scripts/ci/gen-token-names.mjs reported 240 tokens; npm run typecheck and node scripts/ci/run-ui-web-gates.mjs (31/31, including the contrast matrix) pass.",
        "unresolvedRisks": "None known."
      }
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "A completed folder removal's durable receipt keeps the removed folder (path, and collection when not the default), the history projector hands it to the action ledger (live ring and journal), and every window builds Earlier's \"Removed <folder> from your library\" with \"Add <folder> again\" from that ledger row, including after a reload or engine restart; the window that removed it shows one row (its own notice), whose action goes once it can no longer run. The removal handler records the folder only on success; a folder that cannot be recorded never fails the removal; unreadable journal values read as no folder.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE7",
          "quote": "Fail-closed teardown, single-use pending ids, exact server-bound arguments, rebuild gap key+hash binding, forget abort on navigation, durable grants list and revocation.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor-folder-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#a window that did not remove the folder (or a reload) offers \"Add <folder> again\" from the removal record",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#the window that removed it shows one row: its own notice, whose action goes once it can no longer run"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: an older removal record without a path, a failed removal and other operations offer no re-add; the re-add is not offered while the folder is watched again; the metadata-only history entry and a receipt written before the field existed still read; Earlier offers Undo only where a backend undo exists and \"Add back to search\" for a forget. Passes before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "\"Add <folder> again\" exists only in the window that removed the folder; after a reload or in another window the removal shows without a way back, because the backend removal record carries no path. Expected: the removal record keeps the folder's path (additive field in the persisted record; older records without it keep reading, they just offer no re-add), and every window offers \"Add <folder> again\" from Earlier and from the undo notice while it lasts.",
          "location": "task t-20261103-shell request (S3b builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST7",
          "quote": "Earlier lists persisted advisories and completed operations newest first, with Undo only where the journal/backend supports reversal; \"Add back to search\" (re-index) where forget cannot be reversed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#an older removal record without a path, a failed removal and other operations offer no re-add",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#the re-add is not offered while the folder is watched again (added back here or elsewhere)",
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#a completed operation without a backend undo offers no Undo; a forget offers \"Add back to search\""
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "DE1/C1/X1 repairs in real Chromium: switching from Delete Alpha to Delete Beta keeps one band, focuses Keep it and Escape returns to Delete Beta; a real localStorage quota failure keeps the theme, catalog, applied style and active selection unchanged and reports failure by the grid, for both active and inactive themes; freeing storage permits a successful delete which survives reload. The typed delete input follows the spacing token via its class.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Opening a band for another item closes the first (one band open at a time) and moves focus to the new band's \"Keep it\"; Escape on it returns focus to Delete Beta.",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        },
        {
          "kind": "owner-words",
          "quote": "The screen follows what was stored: the theme stays shown and the person sees that it could not be deleted (at the band/grid or in the pill).",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        },
        {
          "kind": "owner-words",
          "quote": "Use the existing size/spacing tokens via a class.",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-prior.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#DE1 switching from Delete Alpha to Delete Beta leaves one band, focuses Keep it, and Escape restores Delete Beta",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#C1 a real quota failure keeps the custom theme and its active state, reports it by the grid, and permits retry (active=false)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#C1 a real quota failure keeps the custom theme and its active state, reports it by the grid, and permits retry (active=true)",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#X1 the typed delete input uses the spacing token through its class"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ],
      "class": "New",
      "behaviour": "Earlier orders persisted advisories and completed operations newest first across read state; read/unread remains a marker and history never reactivates a resolved condition. DOM Details still acknowledges the selected unread row, even when it is second.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Earlier is ordered newest first regardless of read state (read/unread stays a separate marker).",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#ST7 Earlier orders advisories and completed operations newest first regardless of read state",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts#newest comes first regardless of read state; unread keeps its marker and history does not reactivate a resolved condition"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Earlier is ordered newest first regardless of read state (read/unread stays a separate marker).",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        }
      ],
      "conflictSearch": "The adopted ST7 scenario requires newest-first Earlier. The two unread-first assertions contradicted that scenario; read state remains independently asserted. No scenario was changed.",
      "note": "Replaced the frameStatus ST7 unread-first ordering assertion and StatusPanel.history unread-first ordering assertion with newest-first assertions. Preserved the unread marker, inactive history and acknowledgement checks, and added a completed-operation row between advisories."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ],
      "class": "New",
      "behaviour": "Re-executed every check in both edited ordering test files, including all unchanged frame status, history, recovery and reversal checks.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Re-run the evidence for every check in every test file you edit.",
          "location": "task t-20261103-shell request (bounded acceptor-1 repair brief, 2026-11-05; b1b5ae0f3c)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPanel.history.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts"
      ],
      "class": "New",
      "behaviour": "DE1: bands of every kind share one document-wide slot; folder→collection, collection→folder and transitions across Library/Settings leave one band, focus its Keep it and restore its own opener on Escape. C1: successful theme deletion, plugin revocation, collection removal and catalog clear-failed-jobs/rebuild/reset emit a success receipt to the existing pill channel, only after success; Chromium verifies visible Alpha deleted and its polite announcement. Rebuild acceptance does not claim completion. X2: a valid unbroken 200-character theme name wraps in heading/consequence/action text, with the complete action accessible and in its title; the band does not overflow and both buttons remain within the window at all three sizes. Every check fails by assertion on the accepted faulty candidate, then passes after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Expected: one band open in the whole window at a time, of any kind; opening any band closes the other, moves focus to the new band's \"Keep it\"; Escape returns focus to the control that opened it.",
          "location": "task t-20261103-shell request (bounded acceptor-2 repair brief on 2ba5a3ebf3, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "Expected: a receipt in the pill or at the grid (\"Alpha deleted\"), announced politely. Check the other band actions (remove folder, remove collection, cancel rebuild, revoke plugin, clear failed jobs, rebuild, reset settings) have a success receipt too; add one where missing.",
          "location": "task t-20261103-shell request (bounded acceptor-2 repair brief on 2ba5a3ebf3, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "Expected: long names wrap or truncate with their full text available (title/accessible name), the band and its buttons stay inside the window at 600×400, 900×800 and 1280×800.",
          "location": "task t-20261103-shell request (bounded acceptor-2 repair brief on 2ba5a3ebf3, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#DE1 folder, collection and theme bands share one window slot and Escape restores the latest opener",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#C1 deleting Alpha shows a success receipt in the pill and announces it politely",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#X2 a 200-character theme name keeps the band and both answers within 600×400",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#X2 a 200-character theme name keeps the band and both answers within 900×800",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts#X2 a 200-character theme name keeps the band and both answers within 1280×800",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts#deleting a custom theme asks in a band naming the theme; Keep it deletes nothing, the verb deletes once",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts#revoking a plugin asks in a band under its row; the verb revokes it",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts#confirms first, then issues the DELETE for that collection",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts#C1 core.clear-failed-jobs acknowledges success in the pill only after the band is answered and the response succeeds",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts#C1 core.rebuild-index acknowledges success in the pill only after the band is answered and the response succeeds",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts#C1 core.reset-settings acknowledges success in the pill only after the band is answered and the response succeeds"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Clear-failed-jobs has its own consequence and named verb, focuses Keep it, invokes exactly once, then closes. It supplements the restored generic index.flush invocation oracle.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Expected: restore a generic invocation check that actually invokes (the original oracle), and keep the clear-failed-jobs check as its own New entry.",
          "location": "task t-20261103-shell request (bounded acceptor-2 repair brief on 2ba5a3ebf3, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts#asks in place with the consequence and the verb, never \"Are you sure?\", and fires once on the verb"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts"
      ],
      "class": "New",
      "behaviour": "Re-executed every check in every edited or added test file. Preservation checks cover the original generic invocation oracle, band cancellation, typed/disabled/pending behavior, quota failure/retry, collection enumeration and refused catalog actions (no false success receipt). Also re-executed the existing Library folder-removal/re-add, reindex accepted/completed, and cancel-rebuild receipt checks; these existing success receipts remain intact.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Re-run the evidence for every check in every test file you edit.",
          "location": "task t-20261103-shell request (bounded acceptor-2 repair brief on 2ba5a3ebf3, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3b-acceptor2-all.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ActionButton.render.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.otherSources.test.ts",
        "modules/ui-web/src/shell-v0/views/SettingsSurface.ownActions-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/OpButton.ownReceipts.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.gapDecision.test.ts"
      ]
    }
  ]
}
```
