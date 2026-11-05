---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: builder-s2-remove-20261105
---

# Remove files and Download again test intent

The brief's adopted scenarios are quoted below; choices the scenarios leave open are recorded as delegated choices under "proceed accordingly". Evidence is written only by run-evidence.mjs. Deletion tests use temporary models folders only. The repaired page checks fail before the change on the unchanged base, run from a shared clone inside the session temporary directory. These are builder checks; the independent verifier covers the real system (a running Engine with real model files, a second window, process death and Windows native sessions). No acceptance record is written here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRoutesTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/DownloadedFilesHttpTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RemovalModelsFixture.java"
      ],
      "class": "New",
      "behaviour": "Remove files and Download again end to end below the real system: the engine against a temporary models folder only (each ownership rule's refusal, every retained Best match first variant and partial, the answer model with its picture-reading file, capture-before-verify cases, crash recovery, unreadable or future records, rename failure, refusals deleting nothing); the lock in both orders for install, Repair, Download again, pack import, runtime activation and deactivation, settings switch-on and undecline, and the managed answer-server launch; the runtime owners' in-use answers; the install owner with the real settings owner (decline recorded, Repair skips the part, Download again preview and accepted start); the HIGH operation's arguments, refusals and outcomes; the HTTP routes over the production route table; and the page section with its inline confirmation and focus. Native sessions on Windows, real model files and a running Engine are not exercised here.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X1",
          "quote": "Advanced settings > Downloaded files offers Remove files for best match first, for answers (its model and picture-reading file together, only when answers and picture reading are both off), and for other optional parts (SPLADE, entity recognition, citation scoring) when nothing uses them; the confirmation lists exactly what is deleted, its measured size, and what is kept because another part needs it. Not offered for picture reading alone (its file belongs to the answer model download), Understand meaning, runtime libraries, or imported, discovered or custom files. (challenger-4 #1, #10)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X2",
          "quote": "Removal refuses with a plain reason (switch it off first; a download, repair, import or activation is running; an index generation or recovery still needs it; a file is shared with a wanted part) and deletes nothing in that case. After removal: the part shows Not downloaded, Repair does not bring it back, the install total counts it again, \"Download again\" asks for consent and downloads only what is missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X3",
          "quote": "Removal is safe under failure: permission or sharing errors, a path replaced by a link, process death between steps, restart midway: the page reports the files actually left, never \"removed\" while files remain, and never re-enables the part; a second window or a repeated click cannot start a second removal. Source documents, the index and conversations are untouched.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X4",
          "quote": "Removal cannot race new users: Remove versus switching the part on, Repair, import, activation or index generation preparation, in both orders and through the page, agents and HTTP; once removal starts, no new user takes the files until it ends. On Windows removal waits for or refuses until native sessions and child processes have released the files. After partial deletion or a crash, removal intent and the files left are reported; recovery does not turn it into a deferred deletion. Linked or junctioned parent folders are refused. (challenger-4 #9, #10)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Fourth decision 1 narrows X1's 'other optional parts': only Best match first (every retained variant whose files pass the ownership rules) and Answers (model plus picture-reading file together) are removable in this step. SPLADE, entity recognition and citation scoring are listed with their measured size and 'Removal not available yet'; Understand meaning says 'Needed for search by meaning'; runtime libraries say 'Runtime libraries are not removed'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "A file is deleted only when the registry lists it for that part (any variant's candidate folder, or the historical folder the install contract records), it sits at that canonical path inside the managed models folder with no link or junction on the way and resolves inside it, it is a regular file with one link, its full SHA-256 equals the registry's immediately before deletion, and no other wanted (not declined) part lists the same path. A file failing a rule is kept and listed with its plain reason. Partial downloads are the installer's own .partial and .partial.resume files beside a canonical path.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Capture before verify: per file a journal checkpoint, then an atomic rename into models/.removing/<random>.removing (no model extension, so discovery never picks it up), then verification (same file key resolved before the lock, or size plus creation and change time where the platform has no key; regular; one link; registry hash; partials skip the hash), then delete. A failed rename keeps the file (a Windows sharing violation refuses that file). A failed check puts the file back by link-then-unlink, which never replaces an existing file, or keeps it in the removal folder and says so.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Outcome states: removed (every file that passed was deleted; files that failed an ownership check at deletion were never JustSearch's and are listed as kept), partly-removed, not-removed, refused, interrupted. The page never says removed while a file that passed remains.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "The removal record is model-file-removal.v1.json in AI Home, rewritten atomically at each checkpoint and registered in governance/store-recoverability.v1.json. After a crash the next read reports the interrupted removal with the files left, puts captured files back if their place is free, otherwise keeps them in the removal folder, and never deletes. An unreadable or other-version record and stray files in the removal folder are reported and kept.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Fourth decision 2: one lock owned by the AI install owner (ModelFileRemovalFence). Install, Repair and Download again, pack import, and runtime activation and deactivation register as users for their whole run; the removal takes the lock only when no user is registered, and a user cannot register while it is held (both orders, under one monitor). The settings owner refuses, under its commit mutex, a candidate that switches the part on, changes its model path, sets a chat profile or withdraws its decline while it is held; installed-model activation and the managed answer-server launch refuse at their entry. The lock is taken before the final eligibility check. The decline is committed through the settings owner against the witness of that check (always, even when already declined), so a switch-on that landed first refuses the removal.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Fourth decision 2a/3: Best match first must be saved Off and the Worker must report the reranker with no live session (no report yet refuses). Answers must not be On, picture reading must be saved Off (no saved choice means automatic, which counts as on), no managed or user-run answer server may run, no self-test, picture-reading, install or freeze hold may be open, and no external endpoint may be configured ('An answer server you run yourself is configured'). Operator overrides of the part's model refuse, and downloaded files waiting to be activated refuse. Other programs that open model files without JustSearch knowing are out of scope: on Windows the capture rename fails while a file is open, so that file is kept; on Linux an open file stays readable to that program until it closes it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "The removal is the HIGH-risk operation core.remove-ai-files (inline confirmation, metadata audit, no retry, UI executor, USER audience) taking only a part id and the preview digest of the delete list the confirmation showed; it refuses with 'The files changed since this list was shown - review it again' if the list changed. It completes asynchronously and the page follows GET /api/ai/install/downloaded-files. Its operation record is the existing invocation audit; the per-file checkpoints live in the removal record, not in a new durable operation kind. Refusals use FILES_NOT_REMOVED and fenced routes MODEL_FILES_REMOVING.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Third decision: Download again and every switch-on of a part whose files are missing (Best match first, picture reading, answers and other parts after install) preview GET /api/ai/install/plan-preview?include=<id> without saving anything; the accepted core.start-ai-install carries includePackages and the install owner withdraws those declines through the settings owner when the run starts, against the witness read then. Cancel or closing the consent changes nothing and the page re-reads the saved plan.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Windows (not run here; CI Windows lanes): junctions and other reparse points on the path are refused with WindowsOwnerOnlyDirectory.requireNonReparsePoint, the link count is read with WindowsOwnerOnlyDirectory.requireSingleLink, a sharing violation fails the capture rename so the file is kept, and the answer server, self-test and holds must be stopped before removal starts, so removal refuses rather than waits.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Page: Advanced settings > Downloaded files lists downloaded parts (and declined removable parts) with measured size, Remove files or the reason; the inline confirmation is a labelled group listing each deleted and kept file with size and reason; focus moves to its title and returns to the row's action; a running removal (any window) disables Remove files and every page route that adds files, and the page follows it until it reports what is left.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-checks.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#removesEveryRetainedRerankerVariantAndItsPartialAndRecordsTheDecline",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#answersRemovesTheModelAndItsPictureReadingFileTogether",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aCustomFileAtTheCanonicalNameWithADifferentHashIsPutBackUnchanged",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aDifferentSizedFileIsKeptWithoutBeingTouched",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aLinkedParentFolderKeepsEverythingBehindIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aFileReplacedByALinkBeforeCaptureIsKeptAndTheTargetIsUntouched",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aHardLinkedFileIsKept",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aFileAnotherWantedPartListsIsKeptAndGoesOnlyWhenThatPartIsDeclined",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aFileOutsideTheModelsFolderIsKept",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aReplacementBetweenResolveAndCaptureIsKeptAndPutBack",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aFailedVerificationNeverOverwritesAFileThatTookTheCanonicalPlace",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aCrashAfterCaptureIsReportedAndTheFileIsPutBackOnRestartWithoutAnyDeletion",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aFutureOrUnreadableRemovalRecordIsReportedAndNothingIsRestoredOrDeleted",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#filesInTheRemovalFolderWithoutAnyRecordAreReported",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aRenameFailureKeepsTheFileAndReportsNotRemoved",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#refusalsDeleteNothingAndRecordNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#aDeclineTheSettingsOwnerRefusesDeletesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#eligibilityIsReadAgainUnderTheLock",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalTest.java#partsThisStepCannotRemoveAreShownWithTheirReasonAndMeasuredSize",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aUserCannotEnterWhileARemovalHoldsTheLock",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aRemovalCannotBeginWhileAUserIsRegisteredAndNamesIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aSecondRemovalCannotStartWhileOneRuns",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#bestMatchFirstIsInUseUntilTheWorkerReportsNoSession",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#theAnswerModelIsInUseWhileAServerRunsOrAHoldIsOpen",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#anUnboundProbeFailsClosed",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRoutesTest.java#aPackImportIsRefusedWhileRemovingAndARunningImportRefusesTheRemoval",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRoutesTest.java#aRuntimeActivationOrDeactivationIsRefusedWhileRemovingAndARunningOneRefusesTheRemoval",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#theDeclineIsRecordedThroughTheSettingsOwnerAndRepairDoesNotBringThePartBack",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#aSwitchedOnPartIsRefusedWithAPlainReasonAndNothingChanges",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#answersRequirePictureReadingOffToo",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#switchingOnUndecliningOrInstallingWhileRemovingIsRefused",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#aSwitchOnThatLandedFirstRefusesTheRemoval",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#aRunningDownloadRefusesTheRemovalAndARemovalRefusesTheDownload",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#downloadAgainPreviewsWithoutChangingTheChoiceAndCancelLeavesRepairUnchanged",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#anAcceptedDownloadAgainWithdrawsTheDeclineAtStart",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#aPartIdAndItsPreviewReachTheOwnerAndAFullRemovalSucceeds",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#filesLeftBehindNeverReportSuccess",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#aRefusalIsReturnedWithItsPlainReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#pathsAndUnknownPartsAreRefusedBeforeTheOwnerIsAsked",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#theApprovalNamesWhatIsRemovedAndWhatIsNotTouched",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#downloadAgainCarriesItsPartsToTheAcceptedStart",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFenceTest.java#aFencedStartLaunchesNothingAndSaysWhy",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFenceTest.java#anOpenFenceLaunchesAsBefore",
        "modules/ui/src/test/java/io/justsearch/ui/api/DownloadedFilesHttpTest.java#theDownloadedFilesRowsAreServedFromTheInstallOwner",
        "modules/ui/src/test/java/io/justsearch/ui/api/DownloadedFilesHttpTest.java#downloadAgainPreviewsTheIncludedPartWithoutSavingAnyChoice",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#lists each part with its measured size, Remove files where allowed and the reason elsewhere",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#confirms inline with exactly what is deleted, its size and what is kept; Cancel deletes nothing",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#removes through the HIGH-risk operation with the part id and preview, then offers Download again",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#shows a refusal with its plain reason and keeps the row",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#while a removal runs elsewhere, Remove files is disabled and the page says so",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#Download again asks first; closing the consent saves nothing and accepting starts the included download"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Switching on Best match first, answers or picture reading whose files were removed (declined) saves nothing before consent: Cancel leaves the decline, so Repair cannot restore the files, and the accepted start carries includePackages. Before the change the page withdrew the decline with DELETE /api/ai/install/packages/<id>/decline before showing the consent. The file's other checks are new behaviour and are covered, with their sources, by the first entry's evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X2",
          "quote": "Removal refuses with a plain reason (switch it off first; a download, repair, import or activation is running; an index generation or recovery still needs it; a file is shared with a wanted part) and deletes nothing in that case. After removal: the part shows Not downloaded, Repair does not bring it back, the install total counts it again, \"Download again\" asks for consent and downloads only what is missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X3",
          "quote": "Removal is safe under failure: permission or sharing errors, a path replaced by a link, process death between steps, restart midway: the page reports the files actually left, never \"removed\" while files remain, and never re-enables the part; a second window or a repeated click cannot start a second removal. Source documents, the index and conversations are untouched.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Third decision: Download again and every switch-on of a part whose files are missing (Best match first, picture reading, answers and other parts after install) preview GET /api/ai/install/plan-preview?include=<id> without saving anything; the accepted core.start-ai-install carries includePackages and the install owner withdraws those declines through the settings owner when the run starts, against the witness read then. Cancel or closing the consent changes nothing and the page re-reads the saved plan.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-consent-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#Best match first: Cancel leaves the decline, Accept carries it to the install start",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#Answers and picture reading: a declined answer model is undeclined only by the accepted start"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "class": "New",
      "behaviour": "The emitted core operation wire gains core.remove-ai-files (HIGH risk, inline confirmation, metadata audit, no undo, UI executor, USER audience, destructive presentation, inputs packageId in {reranker, chat} and a 64-hex previewDigest, nothing else) and core.start-ai-install gains the optional includePackages string array. Every other operation's wire is byte-identical to the base.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X1",
          "quote": "Advanced settings > Downloaded files offers Remove files for best match first, for answers (its model and picture-reading file together, only when answers and picture reading are both off), and for other optional parts (SPLADE, entity recognition, citation scoring) when nothing uses them; the confirmation lists exactly what is deleted, its measured size, and what is kept because another part needs it. Not offered for picture reading alone (its file belongs to the answer model download), Understand meaning, runtime libraries, or imported, discovered or custom files. (challenger-4 #1, #10)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X2",
          "quote": "Removal refuses with a plain reason (switch it off first; a download, repair, import or activation is running; an index generation or recovery still needs it; a file is shared with a wanted part) and deletes nothing in that case. After removal: the part shows Not downloaded, Repair does not bring it back, the install total counts it again, \"Download again\" asks for consent and downloads only what is missing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "The removal is the HIGH-risk operation core.remove-ai-files (inline confirmation, metadata audit, no retry, UI executor, USER audience) taking only a part id and the preview digest of the delete list the confirmation showed; it refuses with 'The files changed since this list was shown - review it again' if the list changed. It completes asynchronously and the page follows GET /api/ai/install/downloaded-files. Its operation record is the existing invocation audit; the per-file checkpoints live in the removal record, not in a new durable operation kind. Refusals use FILES_NOT_REMOVED and fenced routes MODEL_FILES_REMOVING.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Third decision: Download again and every switch-on of a part whose files are missing (Best match first, picture reading, answers and other parts after install) preview GET /api/ai/install/plan-preview?include=<id> without saving anything; the accepted core.start-ai-install carries includePackages and the install owner withdraws those declines through the settings owner when the run starts, against the witness read then. Cancel or closing the consent changes nothing and the page re-reads the saved plan.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Fourth decision 1 narrows X1's 'other optional parts': only Best match first (every retained variant whose files pass the ownership rules) and Answers (model plus picture-reading file together) are removable in this step. SPLADE, entity recognition and citation scoring are listed with their measured size and 'Removal not available yet'; Understand meaning says 'Needed for search by meaning'; runtime libraries say 'Runtime libraries are not removed'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-checks.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "class": "New",
      "behaviour": "The core catalog lists core.remove-ai-files, and that operation is HIGH risk with inline confirmation, the UI executor only, and inputs of exactly packageId (reranker or chat) and previewDigest with no additional properties, so no caller can supply a path. The other expected ids are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "X1",
          "quote": "Advanced settings > Downloaded files offers Remove files for best match first, for answers (its model and picture-reading file together, only when answers and picture reading are both off), and for other optional parts (SPLADE, entity recognition, citation scoring) when nothing uses them; the confirmation lists exactly what is deleted, its measured size, and what is kept because another part needs it. Not offered for picture reading alone (its file belongs to the answer model download), Understand meaning, runtime libraries, or imported, discovered or custom files. (challenger-4 #1, #10)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "The removal is the HIGH-risk operation core.remove-ai-files (inline confirmation, metadata audit, no retry, UI executor, USER audience) taking only a part id and the preview digest of the delete list the confirmation showed; it refuses with 'The files changed since this list was shown - review it again' if the list changed. It completes asynchronously and the page follows GET /api/ai/install/downloaded-files. Its operation record is the existing invocation audit; the per-file checkpoints live in the removal record, not in a new durable operation kind. Refusals use FILES_NOT_REMOVED and fenced routes MODEL_FILES_REMOVING.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Fourth decision 1 narrows X1's 'other optional parts': only Best match first (every retained variant whose files pass the ownership rules) and Answers (model plus picture-reading file together) are removable in this step. SPLADE, entity recognition and citation scoring are listed with their measured size and 'Removal not available yet'; Understand meaning says 'Needed for search by meaning'; runtime libraries say 'Runtime libraries are not removed'.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-checks.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java#definitionsContainTheExpectedSeedEntries",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java#removeAiFilesIsHighRiskInlineConfirmTakingOnlyAPartIdAndItsPreview"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/validator/ValidatorRunnerTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every core catalog operation declares an executor and its binding resolves to a registered handler; the fixture mirrors the production handler registrations in OperationHandlerRegistrations.",
      "whySurvives": "Only the new operation's handler registration is added, exactly as OperationHandlerRegistrations registers it; every validator, the catalogs it runs against and every assertion are unchanged, so the predicate is as strict as before.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "The removal is the HIGH-risk operation core.remove-ai-files (inline confirmation, metadata audit, no retry, UI executor, USER audience) taking only a part id and the preview digest of the delete list the confirmation showed; it refuses with 'The files changed since this list was shown - review it again' if the list changed. It completes asynchronously and the page follows GET /api/ai/install/downloaded-files. Its operation record is the existing invocation audit; the per-file checkpoints live in the removal record, not in a new durable operation kind. Refusals use FILES_NOT_REMOVED and fenced routes MODEL_FILES_REMOVING.",
          "label": "agent choice"
        }
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
      "whatMoved": "'a missing declined answer package asks for its real size and licence and downloads after consent' used to reach the consent through DELETE .../chat/decline, whose fixture response flipped the declined component to to-download before the plan preview was read. The page now reads that same plan as GET /api/ai/install/plan-preview?include=chat without saving anything first (third decision), so the fixture serves the included plan for that request in the same shape the decline used to produce. Inputs, the click, the observed dialog text and licence, and the expected operations (core.start-ai-install then core.set-chat-enabled) are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Third decision: Download again and every switch-on of a part whose files are missing (Best match first, picture reading, answers and other parts after install) preview GET /api/ai/install/plan-preview?include=<id> without saving anything; the accepted core.start-ai-install carries includePackages and the install owner withdraws those declines through the settings owner when the run starts, against the witness read then. Cancel or closing the consent changes nothing and the page re-reads the saved plan.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off, Best match first, picture reading and Pause answers while indexing switches keep their behaviour and files; search by exact words and by meaning, indexing and its pacing work as today; installs, consent, decline and Repair of parts not removed keep today's behaviour; source documents, the index and conversations are never touched.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records
