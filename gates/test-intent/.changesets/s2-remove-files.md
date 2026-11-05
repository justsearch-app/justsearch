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
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RemovalModelsFixture.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLaunchRefusalTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalPresenceTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedInstallerGenerationBootProjectionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RemovalLinks.java"
      ],
      "class": "New",
      "behaviour": "Remove files and Download again end to end below the real system: the engine against a temporary models folder only (each ownership rule's refusal, every retained Best match first variant and partial, the answer model with its picture-reading file, capture-before-verify cases, crash recovery, unreadable or future records, rename failure, refusals deleting nothing); the lock in both orders for install, Repair, Download again, pack import, runtime activation and deactivation, settings switch-on and undecline, and the managed answer-server launch (registered while its process starts, scoped to the answer model, raced against a removal); the runtime owners' in-use answers; the install owner with the real settings owner (decline recorded, Repair skips the part, Download again preview and accepted start); the HIGH operation's arguments, refusals and outcomes; the HTTP routes over the production route table; and the page section with its inline confirmation and focus. An open installed-model activation (also one continuing after a restart) refuses the removal. Native sessions on Windows, real model files and a running Engine are not exercised here. Repair round 2: the answer model's in-use answer comes from the processes the answer runtime started (a supplier the composition binds to the runtime's physical owners) instead of the server's mode, and the accepted Download again carries the previewed plan identity; ModelFileRemovalTest, ModelFileRemovalSafetyTest, ModelFileRemovalFenceTest, ModelFileRemovalRoutesTest, ManagedLaunchRefusalTest and LlamaServerLaunchFenceTest are tagged cross-platform so the Windows lane runs them: folder links are junctions on Windows (RemovalLinks), the file link case skips where the platform refuses file symbolic links, the hard-link case runs on Windows through the platform link count, and the rename-failure case runs only where POSIX permissions exist.",
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
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, ownership boundary: rule 2 also covers the models folder and every folder above it, read without following links (on Windows also without reparse points). If the models folder or any ancestor is a link or junction the whole removal refuses with 'The models folder is a link or inside a linked folder, so nothing can be removed here' and nothing is deleted.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, recovery: a removal record is acted on only when it is complete and well formed (version 1, a removable part, known states, a non-null file list with non-null entries, each a relative canonical path inside the models folder outside the removal folder, each captured name of the form .removing/<uuid>.removing). Anything else is the existing unreadable-record report ('The removal record could not be read; files in the removal folder were kept'), never an error response. A recorded file is put back only when every folder on its captured and canonical paths (and above the models folder) is a plain folder and the captured entry is a regular file with one link; otherwise it is kept untouched and reported as 'kept - not touched: its place could not be checked'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, restoring: a captured file goes back only by the atomic no-replace link-then-unlink publication. When that is unavailable nothing else is tried (the existence check plus move fallback is removed), the file stays in the removal folder and is reported. If the captured name cannot be dropped after linking, the file is at its place and its captured name is still reported; nothing at the canonical place is deleted to undo it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, failure handling: when a removal stops (for example a checkpoint that cannot be saved), every unfinished file is settled in memory before the lock is released: captured files are put back where their place is free, otherwise kept in the removal folder, and files never reached are 'kept - the removal stopped before this file'. If the settled record cannot be saved it is held in memory for reports and saved by the next recovery; a record that may still say running is reconciled by the next read, never left running without an owner.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, presence: a part is 'downloaded' only when one variant's model file and every required supporting file beside it are on disk (in a candidate folder or the historical folder); files left without that whole set make it 'incomplete' (shown as 'Incomplete, not usable' with its measured size, still offering Remove files where removable and Download again when declined). After a removal the install owner turns the part's retained 'installed' row into a skipped-by-choice row when it is no longer usable, and the page takes current disk presence (models/status) over the run's package rows whenever no download is running.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, answer server: each managed launch registers with the removal lock as a user of the answer model's files from just before its process starts until it has started (after that the runtime reports the server running, which keeps refusing the removal). Registrations are per part: a launch neither blocks nor is blocked by a Best match first removal. A launch refused by a removal throws ManagedLaunchRefusedException (reason ALREADY_TRANSITIONING, message 'Removing files - try again when it finishes'), which is never latched as a chat start failure, so the next start after the removal launches normally.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, installed-model activation: removal also refuses with 'Downloaded models are being activated - try again when it finishes' while the recorded-ingestion owner has an open installer activation row (accepted, running, or continuing after a restart). Before that row exists the activation's own entry checks the lock, and its accepted execution re-prepares and refuses or goes stale if the files changed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, page: the finished removal outcome lists the deleted files with their sizes as returned, then the kept files with their reasons.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, data versions: the removal record's format version (AiInstallService.MODEL_FILE_REMOVAL_RECORD_VERSION, from ModelFileRemoval.JOURNAL_VERSION) is listed in the boot data-version marker (SupportedDataVersions as ai-model-file-removal-journal), as its store-register row's versionSource requires; the register row itself is unchanged.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/RemoveAiFilesHandlerTest.java#downloadAgainCarriesItsPartsAndThePreviewedPlanToTheAcceptedStart",
        "modules/ui/src/test/java/io/justsearch/ui/api/DownloadedFilesHttpTest.java#theDownloadedFilesRowsAreServedFromTheInstallOwner",
        "modules/ui/src/test/java/io/justsearch/ui/api/DownloadedFilesHttpTest.java#downloadAgainPreviewsTheIncludedPartWithoutSavingAnyChoice",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#lists each part with its measured size, Remove files where allowed and the reason elsewhere",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#confirms inline with exactly what is deleted, its size and what is kept; Cancel deletes nothing",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#removes through the HIGH-risk operation with the part id and preview, then offers Download again",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#shows a refusal with its plain reason and keeps the row",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#while a removal runs elsewhere, Remove files is disabled and the page says so",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#Download again asks first; closing the consent saves nothing and accepting starts the included download",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFenceTest.java#aFencedStartLaunchesNothingAndIsARefusal",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFenceTest.java#anAdmittedLaunchIsRegisteredUntilItsProcessHasStarted",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceFileRemovalTest.java#anOpenInstalledModelActivationRefusesTheRemovalAndDeletesNothing",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedInstallerGenerationBootProjectionTest.java#anOpenInstallerActivationIsReportedSoRemoveFilesRefuses"
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
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalPresenceTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLaunchRefusalTest.java",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 1 of the reviewed candidate (3b99787c01): a models folder that is a link, or sits below a linked folder, refuses the whole removal and deletes nothing; recovery never puts a file back through a removal folder replaced by a link, never restores to a recorded place outside the models folder, and reports a malformed record (files null, a null entry, an invalid or missing path) as unreadable instead of failing; restoring never falls back to an existence check plus move; a checkpoint that cannot be saved after a capture puts the file back, releases the lock and leaves no running record; a part missing a required file is incomplete, not downloaded; after a removal the install owner's retained installed row no longer says installed, and on the page both switch-on routes (the feature switch and including the part in Downloaded files) ask first instead of switching on or withdrawing the decline; a managed answer-server start refused by a removal is a refusal that latches no failure, and the next start launches; the finished outcome lists deleted files with sizes. Each check failed by assertion on 3b99787c01; the answer-server check needed only the compatibility patch gates/test-intent/s2-remove-launch-refusal-before.patch (the new ManagedLaunchFence interface and an adapter onto the old reason fence, keeping the old refusal behaviour).",
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
          "choice": "Repair round 1, ownership boundary: rule 2 also covers the models folder and every folder above it, read without following links (on Windows also without reparse points). If the models folder or any ancestor is a link or junction the whole removal refuses with 'The models folder is a link or inside a linked folder, so nothing can be removed here' and nothing is deleted.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, recovery: a removal record is acted on only when it is complete and well formed (version 1, a removable part, known states, a non-null file list with non-null entries, each a relative canonical path inside the models folder outside the removal folder, each captured name of the form .removing/<uuid>.removing). Anything else is the existing unreadable-record report ('The removal record could not be read; files in the removal folder were kept'), never an error response. A recorded file is put back only when every folder on its captured and canonical paths (and above the models folder) is a plain folder and the captured entry is a regular file with one link; otherwise it is kept untouched and reported as 'kept - not touched: its place could not be checked'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, restoring: a captured file goes back only by the atomic no-replace link-then-unlink publication. When that is unavailable nothing else is tried (the existence check plus move fallback is removed), the file stays in the removal folder and is reported. If the captured name cannot be dropped after linking, the file is at its place and its captured name is still reported; nothing at the canonical place is deleted to undo it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, failure handling: when a removal stops (for example a checkpoint that cannot be saved), every unfinished file is settled in memory before the lock is released: captured files are put back where their place is free, otherwise kept in the removal folder, and files never reached are 'kept - the removal stopped before this file'. If the settled record cannot be saved it is held in memory for reports and saved by the next recovery; a record that may still say running is reconciled by the next read, never left running without an owner.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, presence: a part is 'downloaded' only when one variant's model file and every required supporting file beside it are on disk (in a candidate folder or the historical folder); files left without that whole set make it 'incomplete' (shown as 'Incomplete, not usable' with its measured size, still offering Remove files where removable and Download again when declined). After a removal the install owner turns the part's retained 'installed' row into a skipped-by-choice row when it is no longer usable, and the page takes current disk presence (models/status) over the run's package rows whenever no download is running.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, answer server: each managed launch registers with the removal lock as a user of the answer model's files from just before its process starts until it has started (after that the runtime reports the server running, which keeps refusing the removal). Registrations are per part: a launch neither blocks nor is blocked by a Best match first removal. A launch refused by a removal throws ManagedLaunchRefusedException (reason ALREADY_TRANSITIONING, message 'Removing files - try again when it finishes'), which is never latched as a chat start failure, so the next start after the removal launches normally.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, installed-model activation: removal also refuses with 'Downloaded models are being activated - try again when it finishes' while the recorded-ingestion owner has an open installer activation row (accepted, running, or continuing after a restart). Before that row exists the activation's own entry checks the lock, and its accepted execution re-prepares and refuses or goes stale if the files changed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 1, page: the finished removal outcome lists the deleted files with their sizes as returned, then the kept files with their reasons.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-repair1.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aModelsFolderThatIsALinkRefusesTheWholeRemovalAndDeletesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aLinkedFolderAboveTheModelsFolderRefusesTheWholeRemovalAndDeletesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#recoveryNeverPutsBackThroughARemovalFolderReplacedByALink",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#recoveryNeverRestoresToARecordedPlaceOutsideTheModelsFolder",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#restoringNeverFallsBackToAMoveWhenAnAtomicNoReplacePublicationIsUnavailable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aCheckpointThatCannotBeSavedAfterCapturePutsTheFileBackAndLeavesNoRunningRecord",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aPartMissingARequiredFileIsIncompleteNotDownloaded",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aRecordWithoutAFileListIsReportedAsUnreadableAndTouchesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aRecordWithANullFileEntryIsReportedAsUnreadableAndTouchesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aRecordWithAnInvalidPathIsReportedAsUnreadableAndTouchesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalSafetyTest.java#aRecordWithAMissingPathIsReportedAsUnreadableAndTouchesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalPresenceTest.java#aRemovedPartsRetainedInstalledRowNoLongerSaysInstalled",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLaunchRefusalTest.java#aStartRefusedWhileRemovingIsNotAStartFailureAndTheNextStartLaunches",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#lists the deleted files with their sizes in the finished outcome",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#a part left incomplete says it is not usable and keeps Remove files and Download again",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#stale installed rows: the Best match first switch asks first instead of switching on",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#stale installed rows: including the part in Downloaded files asks first instead of withdrawing the decline"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/AnswerFilesInUseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RemovalLinks.java",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 2 of the reviewed candidate (8a7ad0a3c8). The removal folder is private: created owner-only, and before each capture and each deletion still the same plain folder, owned by the Engine's user and writable by no other account; a shared-writable folder refuses the whole removal and deletes nothing, a staged file replaced after verification is neither deleted nor put back, and a removal folder replaced or opened to others after verification stops the removal with nothing in it touched. The removal record is replaced only atomically: a disk without atomic replacement refuses before anything is moved, saved or declined. A download cancelled before its first file completed is listed as incomplete with its staging files removable. A removal holds a must-complete upgrade-admission lease: it refuses while an update is being prepared, and an update prepared during it sees it as a blocker until it ends. The answer model's use is read from the processes the Engine started (a server whose stop failed while the mode says offline, a self-test candidate that outlived its stop), through the composed removal owner too. An accepted Download again is bound to the plan its consent showed: a change by another caller, or any save after the preview, refuses with 'The download changed - review it again' before anything is saved or downloaded; the reviewed plan is accepted. The page sends the plan identity it showed. Each check failed by assertion on 8a7ad0a3c8 with gates/test-intent/s2-remove-repair2-before.patch, which only adds the new call shapes over that revision's behaviour (a constructor taking a lease service it ignores, a record-replacement seam it never consults, a start taking a plan identity it ignores, an empty plan identity on the preview, the composition over the answer runtime that reads the mode, and the runtime's in-use answer as that revision's rule, the mode). ModelFileRemovalWindowsTest (a model held open without delete sharing is kept unchanged and reported in use, then taken once released) is a Windows-only test class and did not run on this Linux host; the Windows CI lane runs it with the cross-platform removal tests.",
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
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off, Best match first, picture reading and Pause answers while indexing switches keep their behaviour and files; search by exact words and by meaning, indexing and its pacing work as today; installs, consent, decline and Repair of parts not removed keep today's behaviour; source documents, the index and conversations are never touched.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, private removal folder: the removal folder is created owner-only (0700 on POSIX; on Windows the existing protected owner-and-SYSTEM DACL), an existing folder is never loosened or repaired, and before each capture, put-back and deletion it must be the same folder (file key; on Windows the NTFS file index), not a link or junction, owned by the Engine's user and writable by no other account (POSIX: no group or other write; Windows: no allow entry granting write rights beyond what a fresh owner-only folder grants). Otherwise the removal refuses ('The removal folder can be changed by another account, so nothing can be removed') or stops, and nothing in that folder is deleted, put back or moved. Where the platform has directory handles the deletion goes through the checked folder's own handle and re-checks that the captured name still holds the verified file.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, file identity on Windows: Java has no file key there, so the identity a capture is verified against is the NTFS volume serial and file index (read through the existing Windows entry helper), not size and times, which a same-size replacement could match.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, a staged file whose captured name no longer holds the verified file at deletion is kept in the removal folder and reported 'kept - it changed while being removed'; it is never put back at the canonical place.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, strict record: the removal record is saved only by an atomic replacement; when the first save fails (on a disk without atomic replacement: 'Nothing was removed: this disk can't save the removal record safely') nothing is captured, saved or declined.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, partial downloads: a part whose only files are the installer's staging files (a download cancelled before its first file completed) is 'incomplete', listed with those files and their measured size, and Remove files is offered when it is otherwise allowed.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, upgrade admission: a removal registers the must-complete operation lease 'ai.model-file-removal' after taking the removal lock and before its final checks, and releases it when it ends; while admission is frozen for an update it refuses with 'An update is being prepared - try again after it'.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, answer model in use: in use while a managed llama-server launch is inside the OS or a launched server has not been seen to exit (also after a failed stop), while a retained server is not confirmed gone, while an exit's descendants are unverified or something a server started that could not be ended still runs, and while a self-test candidate runs or outlived its stop; plus the existing picture-reading, self-test and install holds and a user-run answer server. The server's mode is no longer consulted. An owner that cannot answer refuses. The isolated self-test path used only when no chat controller exists is not tracked.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, Download again bound to its consent: the preview with included parts carries planDigest, the SHA-256 of the included parts, the declines the plan was made with, every planned file (part, path, hash) and the settings witness those declines were read from; the accepted start re-plans from one settings read, refuses with 'The download changed - review it again' (409) unless the identity matches, withdraws the declines against that read's witness (a commit refused as stale refuses the same way), and its acquisition planning uses exactly those declines. A refused start leaves the install status as it was. Any settings save after the preview changes the witness and asks for review.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 2, Windows evidence: the portable removal tests are tagged cross-platform; folder links are junctions on Windows, a platform that refuses file symbolic links skips only that case, and the POSIX-permission cases run only where POSIX permissions exist. Programs outside JustSearch that open files in the models folder are out of scope: on Windows a file held open without delete sharing fails the capture rename and is kept (ModelFileRemovalWindowsTest); on Linux such a program keeps reading its open file until it closes it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-repair2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aSharedWritableRemovalFolderRefusesTheWholeRemovalAndDeletesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#theRemovalFolderIsCreatedOwnerOnly",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aStagedFileReplacedAfterVerificationIsNeitherDeletedNorPutBack",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aRemovalFolderReplacedAfterVerificationStopsTheRemovalAndTouchesNothingInIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aRemovalFolderOpenedToOtherAccountsBeforeDeletionKeepsTheCapturedFile",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aDiskWithoutAtomicReplacementRefusesBeforeAnythingIsMoved",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#aDownloadCancelledBeforeItsFirstFileCompletedIsListedAndItsStagingFilesCanBeRemoved",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#anUpdateBeingPreparedRefusesTheRemovalAndDeletesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair2Test.java#anUpdatePreparedDuringTheRemovalWaitsForItAsAMustCompleteOperation",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java#aDownloadAgainAcceptedAfterAnotherCallerChangedTheChoicesIsRefusedBeforeAnythingIsSaved",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java#anUnrelatedSaveInAnotherWindowAlsoAsksForReviewBecauseThePlanWasReadBeforeIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java#theReviewedPlanIsAcceptedAndItsDeclinesAreWithdrawnAtStart",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java#aFailedAnswerServerStopOrASurvivingSelfTestRefusesTheRemovalThroughTheComposedOwner",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceRemovalRepair2Test.java#theInstallOwnersRemovalIsRefusedWhileAnUpdateIsBeingPrepared",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/AnswerFilesInUseTest.java#aFailedStopKeepsTheAnswerModelInUseWhileTheModeSaysOffline",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/AnswerFilesInUseTest.java#aSelfTestCandidateThatOutlivedItsStopKeepsTheAnswerModelInUse",
        "modules/ui-web/src/shell-v0/views/BrainSurface.remove-files.test.ts#accepting sends the identity of the plan the consent showed, not a fresh one"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair3Test.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/NoDirectoryHandlesFileSystem.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalWindowsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/AnswerFilesInUseTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 3 of the reviewed candidate (ba932039fe). On Windows a captured file is deleted only through the handle it was verified through; on POSIX its entry is checked and then unlinked by name relative to the checked removal folder's handle (under the threat model decided after the final review, programs running under the user's own account are trusted: such a program could swap a file at the moment of deletion, and can already change the user's files directly): on a platform without directory handles (a file system whose directory streams are not secure, as on Windows) a file swapped in under the captured name after the last check by name is kept in the removal folder ('kept - it changed while being removed'), and a still-captured verified file is put back ('kept - could not be deleted') instead of being deleted by name; before the change both were deleted by name. A new file written at a canonical place after its own file was captured (while that file is checked) is kept, measured and reported 'kept - a new file appeared here', and the removal says 'partly-removed', in the finished report, the page's report and after a crash; before the change the place was reported deleted and the removal 'removed'. A self-test candidate whose registration was refused and whose rollback stop failed keeps the answer model in use (the removal refuses with 'The answer model is being tested - try again when it finishes') until it is confirmed gone; before the change it was forgotten and the files were reported free. Each check failed by assertion on ba932039fe with only NoDirectoryHandlesFileSystem.java overlaid (a test file system over the real one whose directory streams are plain). AnswerFilesInUseTest and AiInstallServiceRemovalRepair2Test are now tagged cross-platform. The Windows handle path is covered by three Windows-only cases in ModelFileRemovalWindowsTest (a file swapped in before the deleting handle opens is not deleted; the captured name cannot be renamed while the verified handle is held, and that file is the one deleted; a captured file another program opened for writing cannot get the deleting handle and is kept); they did not run on this Linux host and run in the Windows CI lane.",
      "sources": [
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
          "choice": "Repair round 3, Windows deletion: the captured file is opened once (CreateFileW, read and delete access, read sharing only, not following a link or reparse point, no backup semantics so a folder cannot be opened); its NTFS identity, link count and size are read from that handle and its SHA-256 by reading the same handle; only then is the handle's delete disposition set (SetFileInformationByHandle, FileDispositionInfo) and the handle closed. The decision named StandardOpenOption.DELETE_ON_CLOSE; a delete-on-close open cannot be withdrawn after a failed check and would delete a mismatched file, so the disposition is set only after the checks pass, which deletes exactly the opened file. When the handle cannot be opened or marked (in use for writing, a sharing violation, the calls unavailable) the file is kept and put back ('kept - could not be deleted'). On POSIX the entry is checked to be the verified file and then unlinked by name relative to the checked removal folder's handle, and a put-back by hard link drops the captured name by name (also in recovery): a program running under the user's own account could swap a file at the moment of deletion; such a program can already change the user's files directly (threat model decided after the final review). A platform with neither handle keeps the file. A file another program had already opened with delete sharing is deleted once that program closes it, as on Linux.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 3, new files at a canonical place: after every file was handled, and also when a removal stops and when recovery reports an interrupted removal, each canonical place whose own file was captured (deleted or still in the removal folder) is looked at again; anything there is kept, never deleted, listed with its measured size (unknown stays unknown) and 'kept - a new file appeared here', and counts as a file left, so the removal is reported 'partly-removed' (or 'not-removed' when nothing was deleted), never 'removed'. A put-back that linked the captured file to its place but could not drop its captured name is the same file and is not reported as new. The page shows these entries in its existing Kept list.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 3, self-test survivors: a self-test candidate whose manifest registration was refused and whose rollback stop did not confirm its exit is retained with the processes it was seen to start, as the managed-server launch owner retains its survivor (ChildControl keeps it held and retries its stop before the next chat-server start); the answer model stays in use until it has exited and nothing it started still runs.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 3, Windows evidence: AnswerFilesInUseTest and AiInstallServiceRemovalRepair2Test are tagged cross-platform without guards: their cases use the portable fake llama-server launcher and the terminator wrapper the stop goes through on both platforms, temporary folders and the settings owner. The case without directory handles assumes a non-Windows host, because Windows deletes through the verified file handle instead (ModelFileRemovalWindowsTest).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-repair3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair3Test.java#whereThereAreNoDirectoryHandlesACapturedFileSwappedAfterItsLastCheckIsNeverDeletedByName",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair3Test.java#aNewFileWrittenAtTheCanonicalPlaceWhileTheCapturedFileIsCheckedIsKeptAndReported",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair3Test.java#aNewFileAtTheCanonicalPlaceOfADeletedFileIsReportedAfterACrash",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/AnswerFilesInUseTest.java#aSelfTestCandidateWhoseRegistrationWasRefusedAndWhoseRollbackFailedKeepsTheFilesInUse"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerRemovalQueryOwnersTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair4Test.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 4 of the reviewed candidate (c12aac8d47). Best match first's files are in use while any query owner of the index half still holds a reranker session, read from that owner at the call: after switching Best match first off, a query view issued before Off keeps the previous view and its reranker, and the owner says the session is held until that view is released, also when only a child lease forked from it remains, and nothing is confirmed (the removal refuses) while the Worker's boot model composition is still running; before the change the answer was the serving view's alone (as the Worker's health report showed it), so the files read as free while the issued view still held them. A file that appears during a removal at one of the part's canonical places that was empty when the removal was prepared is kept, measured and reported 'kept - a new file appeared here', and the removal is 'partly-removed', not 'removed': the answer model's picture-reading file written while the model is being checked, a Best match first variant written while another variant is being checked, and the same picture-reading file after a crash, reported by recovery; before the change those places were never looked at and the removal said 'removed' (or recovery listed nothing). The Best match first check failed by assertion on c12aac8d47 with gates/test-intent/s2-remove-repair4-before.patch, which only adds the owner's call shape over that revision's rule (the serving view's reranker, as the health report showed it); the new-file checks failed by assertion on c12aac8d47 with no overlay or patch.",
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
          "choice": "Repair round 4, Best match first in use: the removal asks, inside its lock and at that moment, the index half's own owner of query views (KnowledgeServer.rerankerSessionHeld through EngineRoot and WorkerHost.rerankerUse) instead of the Worker's cached health report. Its answer is built from the owners it already counts for its retained-encoder cap: the serving view, every retired view issued work has not released (forked child leases keep their view retained), the boot, candidate, recovery, preparing, composing and rejected query owners, the not-yet-owned boot surfaces and failed compositions; one holds Best match first while its reranker assembly (or a partial one kept after a failed build) is not closed or its reranker session handle has not retired. While a composition or swap holds the owner's runtime lock, and while the Worker's boot model composition has not finished (it composes on its own thread without that lock, so a reranker it is opening belongs to no owner yet; a started Worker that has not begun it counts as not finished), the answer is unknown and the removal refuses ('Search is changing its models - try again in a moment'); a closed or absent index half refuses as before ('Search is still starting - try again in a moment'); a held session refuses with 'Best match first is still loaded - try again in a moment'. Any held reranker session refuses, also one on a custom file. No new query owner can load Best match first while the lock is held: the settings owner refuses switching it on, pointing it at other files or withdrawing its decline before any component is prepared, and a switch-on prepared before the lock was taken is seen by this answer (its owner is preparing or composing) and loses to the removal's witness; recovery and restarts compose from the saved, switched-off choice.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Repair round 4, files at previously empty places: the removal record keeps the part's complete set of canonical places from preparation (every registry file of each variant considered, the historical folder the install contract records, and their staging files), present or not. Before the outcome is reported, when a removal stops, and in recovery, each of those places and each place the record names is looked at again; a file there that is not the part's own kept file (never captured, or put back) is kept, never deleted, measured (unknown stays unknown) and listed 'kept - a new file appeared here', and the outcome is 'partly-removed' (or 'not-removed' when nothing was deleted), never 'removed'. A record without the set (none was written by a released build) is read as having none.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement design D4 (narrow option) and its settlement for Remove files and Download again, within the settled scenarios X1-X4, N1 and N2.",
          "choice": "Threat model decided by the user after the final review ('Treat programs running under your own account as trusted (named as a limit)'): other accounts are kept out by the owner-only removal folder, as before. Two steps still act on a captured file by name and are kept: on POSIX the entry is checked to be the verified file and then unlinked by name relative to the checked removal folder's handle, and a put-back by hard link drops the captured name by name (also in recovery). Limit: another program running under your account could swap a file at the moment of deletion; such a program can already change your files directly. Other programs that open files in the models folder without JustSearch knowing are out of scope: on Windows the capture rename fails while a file is open, so that file is kept; on Linux an open file stays readable to that program until it closes it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-repair4.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerRemovalQueryOwnersTest.java#anIssuedViewFromBeforeOffKeepsBestMatchFirstInUseUntilItIsReleased",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerRemovalQueryOwnersTest.java#aForkedChildLeaseKeepsBestMatchFirstInUseAfterItsParentIsReleased",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerRemovalQueryOwnersTest.java#nothingIsConfirmedWhileTheBootModelCompositionIsStillRunning",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair4Test.java#aProjectorThatAppearsWhileTheAnswerModelIsHashedIsKeptAndTheRemovalIsNotComplete",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair4Test.java#aNewlyAppearingBestMatchFirstVariantIsKeptAndTheRemovalIsNotComplete",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalRepair4Test.java#aFileAtAPreviouslyEmptyPlaceIsReportedByRecoveryAfterACrash"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalQueryOwnersTest.java"
      ],
      "class": "New",
      "behaviour": "The removal lock and the runtime owners' in-use answers, re-run after repair round 4 changed how Best match first's use is read: the lock refuses users while a removal holds it and a removal while a user is registered, a second removal cannot start, the answer-server launch and the answer model's removal never both proceed; Best match first is in use until the index half's query owners, asked at each call, say none holds a session (held, a composition running, or the index half not running all refuse, and an owner that cannot answer refuses); the answer model is in use while a process may hold it or a hold is open; an unbound probe fails closed. These checks moved here from the first entry because the file changed; the first entry keeps its other checks and evidence. ModelFileRemovalQueryOwnersTest drives the removal through the composed probe: while the query owners report a held session it refuses with 'Best match first is still loaded - try again in a moment', deletes and declines nothing, and once they report it released the same removal deletes every retained variant; the owners are asked again at each removal, never a remembered answer (that the usage probe is read after the lock is taken is shown by the code order in ModelFileRemoval.prepare, not asserted by this check).",
      "sources": [
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
          "choice": "Repair round 4, Best match first in use: the removal asks, inside its lock and at that moment, the index half's own owner of query views (KnowledgeServer.rerankerSessionHeld through EngineRoot and WorkerHost.rerankerUse) instead of the Worker's cached health report. Its answer is built from the owners it already counts for its retained-encoder cap: the serving view, every retired view issued work has not released (forked child leases keep their view retained), the boot, candidate, recovery, preparing, composing and rejected query owners, the not-yet-owned boot surfaces and failed compositions; one holds Best match first while its reranker assembly (or a partial one kept after a failed build) is not closed or its reranker session handle has not retired. While a composition or swap holds the owner's runtime lock, and while the Worker's boot model composition has not finished (it composes on its own thread without that lock, so a reranker it is opening belongs to no owner yet; a started Worker that has not begun it counts as not finished), the answer is unknown and the removal refuses ('Search is changing its models - try again in a moment'); a closed or absent index half refuses as before ('Search is still starting - try again in a moment'); a held session refuses with 'Best match first is still loaded - try again in a moment'. Any held reranker session refuses, also one on a custom file. No new query owner can load Best match first while the lock is held: the settings owner refuses switching it on, pointing it at other files or withdrawing its decline before any component is prepared, and a switch-on prepared before the lock was taken is seen by this answer (its owner is preparing or composing) and loses to the removal's witness; recovery and restarts compose from the saved, switched-off choice.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-remove-files-repair4-fence.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aUserCannotEnterWhileARemovalHoldsTheLock",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aRemovalCannotBeginWhileAUserIsRegisteredAndNamesIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aSecondRemovalCannotStartWhileOneRuns",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#bestMatchFirstIsInUseUntilTheQueryOwnersSayNoneHoldsASession",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#theAnswerModelIsInUseWhileAProcessMayHoldItOrAHoldIsOpen",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#anUnboundProbeFailsClosed",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#anAnswerServerLaunchInProgressRefusesTheAnswerModelRemovalOnly",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aRemovalOfTheAnswerModelRefusesALaunchAndOtherRemovalsDoNot",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalFenceTest.java#aLaunchAndARemovalRacingNeverBothProceed",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ModelFileRemovalQueryOwnersTest.java#aHeldQueryOwnerRefusesTheRemovalAndItsReleaseLetsTheSameRemovalProceed"
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
