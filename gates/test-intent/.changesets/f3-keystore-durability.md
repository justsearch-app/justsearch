---
schema: test-intent.v1
task: t-20261021-f3
author-role: builder
author-session: f3-builder-20261021
---

# Test-intent entries

F3 only: forced atomic keystore publication and a validated replica holding current wraps.
Sources below quote the agreed scenarios in the original orchestrator assignment.

Durability target (S12): process termination only. Parent-directory flush is missing;
physical power-loss durability is not claimed. Windows checks are tagged for the hosted lane;
local evidence records Linux only.

The evidence runner executes from an isolated shared candidate clone. Its temporary before
worktrees belong to that clone, leaving the source repository metadata untouched. A separate
shared `before` clone records preservation checks on 50eafae23. Rust before-state checks use
`f3-reset-before-interface.patch`, a signature-only extraction of the original reset body.
No repaired behavior is included in that patch. Preservation-before evidence is recorded in
`f3-keystore-preserved-before.json` and `f3-reset-preserved-before.json`; after evidence is cited
in the entries below. Unchanged lifecycle, PBKDF2, strict-writer and backup-import checks are
included in the preservation runs alongside the new checks.

Round 2 follows the orchestrator's decisions after independent verifier cases V5 and V6
rejected 36d97a92f. The new checks use that exact revision as their before-state. The original
F3 evidence retains its original before-state, 50eafae23, and is rerun on the revised candidate.
The V5/V6 source was read from the verifier's own/VerifierOwnCasesTest.java and its recorded
candidate failures; the new port preserves restart-without-retry and known-credential observations.

Save results distinguish SAVED, FAILED_ROLLED_BACK and SAVED_WITHOUT_REPLICA. The existing
String/void manager entry points preserve compatibility; the control endpoints consume the typed
results directly. Stale replica invalidation precedes publication, so a failed rollback cannot
revive revoked wraps even if subsequent storage operations also fail. Abandoned temp removal
also precedes publication, so cleanup refusal leaves the prior credentials in force. Existing
SystemNotice rendering on Security & Privacy displays restoration and degraded-save warnings;
no new notice transport or startup channel is introduced. Windows remains hosted-CI-only.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java"
      ],
      "class": "New",
      "behaviour": "S1: strict forced-writer selection and replica creation are separate checks; replica wraps must match the live key, successful rotations revoke prior credentials through both copies, and real child processes are killed before/after each primary/replica rename. A refused replica publication leaves ciphertext and export keys usable, and a retry repairs both copies. S2: restoration starts from an explicitly seeded valid replica and reports recovery; bad records produce a named error, unavailable storage never becomes first-time setup, and a future replica is refused without writes. S3b: legacy records load byte-for-byte unchanged, gain a replica on their next save. All tests carry cross-platform selection for hosted Windows CI.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S1",
          "quote": "A keystore save (setup, password change, recovery rotation) forces its bytes to disk before it replaces the file. It keeps a validated recovery replica with the current wraps, and reports success only after both are durable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S1",
          "quote": "If the process is killed at any point in a save, the next start unlocks with the old password or the new one, never neither.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S1",
          "quote": "A failure between primary and replica followed by a retry in the same process: existing ciphertext still decrypts, a retried rotation works, and export works. After reported success, revoked passwords and recovery keys fail through either copy.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S2",
          "quote": "If keystore.json is missing or empty but the replica is valid, the next start restores the key from the replica and says so. Unavailable storage (denied access, I/O error) or a future schema never triggers replacement from an older replica: it is refused without changing anything. Both files absent still means first-time setup. If both are bad, startup reports a named error and changes nothing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S3b",
          "quote": "A keystore with no replica (every installation today) loads unchanged, and the next save creates the replica. A successful factory reset removes both files. A failed deletion is reported, not claimed as success.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S11",
          "quote": "Linux and Windows both where the path exists. Windows is only available through hosted CI here: write the Windows-relevant checks so CI runs them, and mark anything you could not run.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-keystore-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#savesBothCopiesThroughForceBeforeMoveWriter",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#setupCreatesValidatedCurrentReplica",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#successfulRotationsRevokeCredentialsThroughEitherCopy",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#missingAndEmptyPrimaryRestoreReplicaAndReportRecovery",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#badCopiesGiveNamedErrorAndRemainUntouched",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#unavailableReplicaNeverTurnsMissingPrimaryIntoFirstTimeSetup",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#failureBetweenCopiesCanBeRetriedWithoutLosingCiphertextOrExportKey",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#existingInstallationLoadsUnchangedAndNextSaveCreatesReplica",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#unavailableStorageRefusesSilentSetupOrReplicaReplacement",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#futureReplicaCannotReplaceMissingPrimary",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#processKillAtEachPublicationStepRetainsUnlockableData"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: a future primary version is refused without rewriting either the primary or its older replica.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S2",
          "quote": "If keystore.json is missing or empty but the replica is valid, the next start restores the key from the replica and says so. Unavailable storage (denied access, I/O error) or a future schema never triggers replacement from an older replica: it is refused without changing anything. Both files absent still means first-time setup. If both are bad, startup reports a named error and changes nothing.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S3",
          "quote": "First-time setup; unlock and recovery; password change; existing PBKDF2 records; export and import of wrapped keys through conversation backup; the existing future-version refusal (DataKeyManager lifecycle, PBKDF2 and backup tests).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-keystore-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreDurabilityTest.java#futureSchemaNeverRestoresOlderReplica"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreBackupDurabilityHttpTest.java"
      ],
      "class": "New",
      "behaviour": "After replica publication fails, real HTTP export/import still decrypt with the old passphrase and recovery key. Retrying the rotation produces an export decryptable with the new passphrase and preserved recovery key. Tests are selected in hosted Windows CI.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S1",
          "quote": "A failure between primary and replica followed by a retry in the same process: existing ciphertext still decrypts, a retried rotation works, and export works. After reported success, revoked passwords and recovery keys fail through either copy.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S11",
          "quote": "Linux and Windows both where the path exists. Windows is only available through hosted CI here: write the Windows-relevant checks so CI runs them, and mark anything you could not run.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-keystore-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreBackupDurabilityHttpTest.java#failedRotationAndRetryKeepHttpExportsDecryptable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreBackupDurabilityHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: HTTP export/import between independent key managers retains authored entries and supports either the bundled passphrase or recovery wrap.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S3",
          "quote": "First-time setup; unlock and recovery; password change; existing PBKDF2 records; export and import of wrapped keys through conversation backup; the existing future-version refusal (DataKeyManager lifecycle, PBKDF2 and backup tests).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S11",
          "quote": "Linux and Windows both where the path exists. Windows is only available through hosted CI here: write the Windows-relevant checks so CI runs them, and mark anything you could not run.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-keystore-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreBackupDurabilityHttpTest.java#wrappedKeyExportAndImportWorkAcrossIndependentManagers"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A refused deletion of the encryption directory returns a named error, preserves the retry marker and leaves the obstructing state unchanged. The before-state patch extracts the existing reset body behind the same path-based signature without adding the repair.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S3b",
          "quote": "A keystore with no replica (every installation today) loads unchanged, and the next save creates the replica. A successful factory reset removes both files. A failed deletion is reported, not claimed as success.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-reset-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#tests::reset_reports_keystore_deletion_failure_and_keeps_retry_marker"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Preservation: successful reset removes both keystore filenames and the reset marker. The test seeds both filenames on the original reset implementation too; its existing recursive deletion already satisfies this case. Existing Rust tests and expectations remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261021-f3",
          "scenario": "S3b",
          "quote": "A keystore with no replica (every installation today) loads unchanged, and the next save creates the replica. A successful factory reset removes both files. A failed deletion is reported, not claimed as success.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-reset-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#tests::reset_removes_primary_and_replica_before_reporting_success"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreRollbackTest.java"
      ],
      "class": "New",
      "behaviour": "Verifier V5/V6 are ported with their original restart-without-retry observations: a reported failure preserves the known recovery key and old password. A separate injected replica-publication failure observes two forced primary writes and exact-byte rollback. The next successful save removes only the keystore writers' abandoned temp files.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If the replica write fails after the primary was replaced, the save restores the primary to its previous bytes (forced write and atomic replace, as for any save) and reports the failure. The user's state then matches what they were told: the old password and old recovery key work.",
          "location": "task t-20261021-f3 user turn 4"
        },
        {
          "kind": "owner-words",
          "quote": "Leftover temporary files from a killed save that still hold old wraps: remove them on the next successful save.",
          "location": "task t-20261021-f3 user turn 4"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-save-outcomes-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreRollbackTest.java#failedRecoveryRotationWithoutRetryKeepsKnownRecoveryKeyAfterRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreRollbackTest.java#failedPasswordChangeWithoutRetryKeepsOldPasswordAfterRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreRollbackTest.java#replicaPublicationFailureRestoresPreviousBytesThroughForcedWriter",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/KeystoreRollbackTest.java#nextSuccessfulSaveRemovesOnlyAbandonedKeystoreTemps"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreSaveOutcomeHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through real HTTP handlers, setup, password change and recovery rotation report each of the three save outcomes. A failed rollback returns success-with-warning and the committed recovery key when applicable, advances in-memory wraps to match the primary, preserves existing ciphertext, and leaves no stale replica that could revive old credentials. A successful rollback reports failure with the old primary bytes and credentials preserved. Full saves identify the saved outcome and retain identical durable copies. Restored-key status exposes the recovery warning.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If the replica write fails after the primary was replaced, the save restores the primary to its previous bytes (forced write and atomic replace, as for any save) and reports the failure. The user's state then matches what they were told: the old password and old recovery key work.",
          "location": "task t-20261021-f3 user turn 4"
        },
        {
          "kind": "owner-words",
          "quote": "If that restore also fails, report the true state instead of a plain failure. The new credentials are in force, so the caller must get the new recovery key to show, plus a warning that the backup copy could not be written. Invalidate the stale replica so revoked credentials can't come back through it. Make the result type say which of the three outcomes happened (saved; failed and rolled back; saved without replica), and make the callers (setup, password change, recovery rotation, through their real interfaces) handle each.",
          "location": "task t-20261021-f3 user turn 4"
        },
        {
          "kind": "owner-words",
          "quote": "Restoring from the replica at start is currently only a warning in the Engine log. If the app has an existing user-visible notice or startup-warning channel, use it. If there is none, keep the log line and say so; don't build a new channel.",
          "location": "task t-20261021-f3 user turn 4"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-save-outcomes-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreSaveOutcomeHttpTest.java#rollbackFailureReportsSavedWithoutReplica",
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreSaveOutcomeHttpTest.java#replicaFailureReportsRolledBackThroughHttp",
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreSaveOutcomeHttpTest.java#recoveryFromReplicaIsExposedThroughEncryptionStatus",
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreSaveOutcomeHttpTest.java#successfulSaveReportsSavedThroughHttp"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SecuritySurface.keystore-warning.test.ts"
      ],
      "class": "New",
      "behaviour": "The existing Security & Privacy surface renders the existing SystemNotice component for restored-key and saved-without-replica warnings. Setup and recovery rotation retain the returned one-time key and the existing save-key gate while displaying the warning; password changes show the same warning without claiming ordinary full success.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If that restore also fails, report the true state instead of a plain failure. The new credentials are in force, so the caller must get the new recovery key to show, plus a warning that the backup copy could not be written. Invalidate the stale replica so revoked credentials can't come back through it. Make the result type say which of the three outcomes happened (saved; failed and rolled back; saved without replica), and make the callers (setup, password change, recovery rotation, through their real interfaces) handle each.",
          "location": "task t-20261021-f3 user turn 4"
        },
        {
          "kind": "owner-words",
          "quote": "Restoring from the replica at start is currently only a warning in the Engine log. If the app has an existing user-visible notice or startup-warning channel, use it. If there is none, keep the log line and say so; don't build a new channel.",
          "location": "task t-20261021-f3 user turn 4"
        }
      ],
      "evidence": "gates/test-intent/evidence/f3-save-notices-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SecuritySurface.keystore-warning.test.ts"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
