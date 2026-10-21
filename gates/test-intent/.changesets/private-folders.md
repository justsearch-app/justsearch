---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15776-d065-7d12-8b21-f9e5b2e63fdb
---

# Test-intent entries

P1–P9 were adopted on 2026-10-20: "i agree, proceed accordingly".
The scenario quotes remain exactly those of the first brief. Delegated choices are agent
choices; their sole owner delegation quote is "resume with remaining work autonomously".
The superseded choice to leave Windows explicit file ACLs untouched is removed: P9 requires
repair of known existing files too. Links and multiply-linked regular files are explicit no-repair exceptions.

Only this branch's private-folder test items and one main keystore fixture adaptation are covered here. Token-only files,
including their changeset and evidence, match the merged main exactly. The current PR/gate base is
`eae9993ac7ec8fd0e17a0d0c8adb935ee3121741`. The v5 repair and preservation receipts
refresh the changed runtime harness against that revision; the unchanged supplemental API
and keystore-startup receipts retain their earlier reviewed revisions. The separate
hard-link/iterator regression receipt retains the requested historical state `46816f593`.
No receipt uses hand-written patches or production overlays. Older receipts and every
review record remain as history. The Windows account-level P9 check remains unverified.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Post-merge repair controls against pinned main eae9993ac7ec8fd0e17a0d0c8adb935ee3121741: fresh/restored runtime and encryption folders are private; existing single-link descendants receive 0700/0600 or the protected owner+SYSTEM DACL; symlinks/junctions are skipped, parents unchanged, denied permissions warn without blocking publication. The shell creates private folders and warns once while keeping linked-runtime writers working. The keystore privacy checks now include both main’s primary and recovery replica at setup, passphrase/recovery changes and restored-folder load, preserving the current main save/rollback protocol. All 13 named checks fail by assertion on this main revision and pass on the merged candidate, without production overlays or patches.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P1",
          "quote": "Fresh Linux install under umask 002: `<data>/runtime` and `<data>/encryption` are 0700; another account cannot list, read, create, rename or delete inside them. Fails today (0775).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P2",
          "quote": "Upgrade, Linux, data folder outside the home folder: runtime 0775 with history 0664 holding recognizable old tokens and a readable keystore, written by the old version; also a data folder copied or restored with broader permissions (cp/rsync/tar without preserving modes). After the first start of the new version, another account cannot read history, manifest or keystore; history readers (instance API, stop-exit census) and keystore unlock still work. Fails today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P3",
          "quote": "The keystore stays owner-only after setup, passphrase change and recovery-key regeneration (atomic replacement), and still unlocks after restart; a replacement interrupted midway (kill, or denied write) leaves the last usable keystore and its recovery material intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P4",
          "quote": "The manifest keeps its owner-only file and temporary-file guarantee from item 2; a kill between temp write and move leaves nothing readable to others and the next start is normal.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P5",
          "quote": "Exit ledger and other runtime writers: shell-first and Engine-first startup, ledger appends, rotation, `.pending` and temporary files and recreated folders are inside an owner-only folder from creation, not only after the Engine's repair (the shell creates the folder owner-only too).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P8",
          "quote": "Same-owner readers keep working: desktop shell discovery, MCP discovery, dev runner, jseval, diagnostics, shutdown recovery, on Linux (and Windows, unverified).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "The agreed decision is that a linked folder warns once and continues, never changes the link target's permissions or any parent.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "I’ll repair real Linux descendants to 0600/0700 and skip permission repair throughout a linked shell subtree.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "protected DACL owner+SYSTEM on create and repair as the shell does",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "Main includes a newer keystore save path with a recovery replica. I’ll keep that behavior and apply folder protection before its reads and writes. The privacy test’s old “one file remains” assertion needs to include both durable files.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-merge-repair-v5.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#setupReplacementAndRestartKeepPrivateAndUsable",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#broadRestoredFolderIsRepairedBeforeLoad",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#missingDataDirectoryCanStillBeConfigured",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_first_ledger_and_recreation_are_private",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_linked_runtime_warns_once_without_repairing_through_link",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#privateFromConstructionIncludingStartLogAndRecreation",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#repairsRestoredHistoryBeforeReadingOrPublishing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#missingDataDirectoryStillBootstrapsWithoutChangingItsParent",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#directoryReparsePointStillPublishesAndWarnsOnceWithoutChangingPermissions",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#failedPermissionSetterWarnsOnceAndDoesNotPreventPublishing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#repairsExistingDescendantsWhenRootIsAlreadyPrivate",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#windowsRepairIncludesExistingFilesAndSkipsEveryReparsePoint"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": false,
      "preservation": true,
      "behaviour": "Post-merge preservation controls against pinned main eae9993ac7ec8fd0e17a0d0c8adb935ee3121741: linked runtime and encryption paths retain successful owner writes/unlock without permission repair through the link or parent. Interrupted keystore replacement preserves both the primary and recovery replica bytes, passphrase/recovery usability and exactly the two durable filenames, without abandoned temporary files. The old single-file count assertion is superseded only because main now intentionally keeps a replica. Both revisions pass all four checks; they are not claimed repairs.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P3",
          "quote": "The keystore stays owner-only after setup, passphrase change and recovery-key regeneration (atomic replacement), and still unlocks after restart; a replacement interrupted midway (kill, or denied write) leaves the last usable keystore and its recovery material intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P8",
          "quote": "Same-owner readers keep working: desktop shell discovery, MCP discovery, dev runner, jseval, diagnostics, shutdown recovery, on Linux (and Windows, unverified).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "The agreed decision is that a linked folder warns once and continues, never changes the link target's permissions or any parent.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "Main includes a newer keystore save path with a recovery replica. I’ll keep that behavior and apply folder protection before its reads and writes. The privacy test’s old “one file remains” assertion needs to include both durable files.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-merge-preservation-v5.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#interruptedReplacementPreservesPassphraseAndRecoveryMaterial",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#linkedEncryptionStillUnlocksWithoutChangingTargetOrParentPermissions",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_linked_runtime_keeps_all_writers_working",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#linkedRuntimeStillPublishesWithoutChangingTargetOrParentPermissions"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Supplemental candidate contracts for the new helper/backend APIs: private directory creation, recursive single-link file repair, one warning for denied/unsupported setters and no mutation through links. Windows descriptor entries/protection flags remain literal assertions; the already-opened native-information guard rejects multiply-linked files and reparse points, allows single-link files and real directories, and interprets the link count as unsigned. The shell descriptor oracle is unchanged. Main lacks these helper APIs, so these are candidate API checks; real compatible publisher/writer controls in the repair receipt establish the before/after observable repairs. Windows native execution remains unverified.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P1",
          "quote": "Fresh Linux install under umask 002: `<data>/runtime` and `<data>/encryption` are 0700; another account cannot list, read, create, rename or delete inside them. Fails today (0775).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P2",
          "quote": "Upgrade, Linux, data folder outside the home folder: runtime 0775 with history 0664 holding recognizable old tokens and a readable keystore, written by the old version; also a data folder copied or restored with broader permissions (cp/rsync/tar without preserving modes). After the first start of the new version, another account cannot read history, manifest or keystore; history readers (instance API, stop-exit census) and keystore unlock still work. Fails today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "I’ll repair real Linux descendants to 0600/0700 and skip permission repair throughout a linked shell subtree.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "protected DACL owner+SYSTEM on create and repair as the shell does",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "during repair, a regular file with more than one hard link is left unchanged with one warning per process, like a symlink: on Linux via the `unix:nlink` attribute read without following links; on Windows via nNumberOfLinks from GetFileInformationByHandle on the already-opened handle (FFM, no new dependency).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-merge-api-v4.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::windows_directory_descriptor_is_protected_and_only_owner_and_system_inherit_full_control",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Hard-link repair exception and iterator recovery: an existing runtime file hard-linked outside the data folder retains its 0664 mode, bytes and inode alias, with one warning despite repeated publication; parents and target-directory modes remain unchanged, the runtime stays private and the manifest is published owner-only. An injected DirectoryIteratorException from hasNext must produce one warning across repeated repairs, allow publisher construction and manifest publication, and preserve the existing history. Both checks fail by assertion on 46816f593 for the respective outside chmod and escaping iterator error, and pass after. No existing assertion was weakened. Refreshed after the merge; 46816f593 remains the explicitly requested historical regression state, while the current PR base is eae9993ac7ec8fd0e17a0d0c8adb935ee3121741.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P7",
          "quote": "Repair never follows a symlink or junction out of the data folder and never changes a parent folder.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "during repair, a regular file with more than one hard link is left unchanged with one warning per process, like a symlink: on Linux via the `unix:nlink` attribute read without following links; on Windows via nNumberOfLinks from GetFileInformationByHandle on the already-opened handle (FFM, no new dependency).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-merge-hardlinks-iterator-v5.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#hardLinkedHistoryKeepsOutsidePermissionsAndWarnsOnceWhilePublishing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#failingDirectoryIteratorWarnsOnceAndStartupContinuesWithoutDeletingFiles"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreStartupAvailabilityHttpTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Retain main’s exact parameter rows, startup/log/HTTP/unlock/retry assertions and real read-only directory precondition. The new privacy repair would otherwise make mode 0500 writable (0700) before the intended replica write failure. For the two read-only rows only, additionally deny the folder permission setter so the same genuine OS replica write failure still occurs; all other Files operations call through to the real filesystem. No assertion was changed, weakened or removed. The identical final fixture is executed on pinned main and the merged candidate with all eight cases passing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P3",
          "quote": "The keystore stays owner-only after setup, passphrase change and recovery-key regeneration (atomic replacement), and still unlocks after restart; a replacement interrupted midway (kill, or denied write) leaves the last usable keystore and its recovery material intact.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P6",
          "quote": "Permissions cannot be set (unsupported filesystem or denied): one warning, the Engine starts, nothing is deleted; unit test with a failing fake. The alternative (refuse to create new secrets there) is the user's call.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed folder-protection scenarios P1-P9",
          "choice": "Main’s read-only keystore fixture also becomes writable through the new repair; I’ll keep its assertions and make repair fail in that fixture so the original OS write failure still occurs.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-merge-startup-fixture-v4.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KeystoreStartupAvailabilityHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Rename the unused try-with-resources static mock binding nativeSecurity to the unnamed variable _. The conditional initializer, static-mock answer, resource order, lifetime and automatic close are identical. Every input, precondition, injected failure, assertion, tag and platform condition is unchanged. Main/test PMD and compilation pass for all four touched JVM modules; the focused runtime permission suite passes. The three v5 execution receipts refresh this file’s digest without changing their check selection or before-state assertions.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261019-5ec1a1 request",
          "quote": "Make the smallest change that keeps the mock's scope and behavior exactly the same; an unnamed `_` resource is one option, if both PMD and Error Prone accept it."
        }
      ]
    }
  ]
}
```

Execution/harness details and real command exits: [evidence notes](../evidence/private-folders-notes.md).
The existing rejection and acceptance records below are preserved byte-for-byte. The author supplies no new review record.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "bcf1b113-48d6-447b-9022-7d1f51ea26fb",
  "verdict": "reject",
  "digest": "sha256:335e8254068c6eeab34d170fcc4b62dec19d4775511e6943e3fe42507fe65ef4",
  "note": "Delegated choices quote words the owner did not write instead of 'resume with remaining work autonomously', and the link and Windows-descriptor fail-before claims run against intermediate commit bc2a09ede (one with a hand-written patch) although at the before-state 4975ceedf a linked folder already worked."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "f20f1489-121b-409d-b017-07a36f5b198a",
  "verdict": "accept",
  "digest": "sha256:76342878bfb28711157e92630e3070e411a2855a6199d1ebc23d6c1e648974e9",
  "note": "Entries match P1-P9 and the delegated choices; at 4975ceedf the 13 repair checks fail by assertion, the four link and keystore preservation checks pass on both, and the live Linux Engine plus shell ledger checks agree."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "68cbdc5d-edcd-48f2-877a-6ee743af71e9",
  "verdict": "accept",
  "digest": "sha256:97dce9d5814fe39b6e7e7b9728076c259ea9def3cb5dcca93ecd4a8bbecf0a70",
  "note": "Hard-link and iterator entries follow P6/P7 and the delegated hard-link choice; both new checks fail by assertion at 46816f593 (outside file chmod to 0600, escaping DirectoryIteratorException) and pass on f4fafcb26, and the live Linux Engine confirms hard-linked outside files keep mode 0664 with a warning while P1/P2 still hold."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "146de75c-e9bc-4471-8262-0d63604c31e6",
  "verdict": "accept",
  "digest": "sha256:aab87b23d0f89721090da8b839eb062b1b9b899579f96c14b54977c4881ca643",
  "note": "Entries match P1-P9 and the delegated choices; on main eae9993ac the 11 JVM repair and 2 hard-link/iterator checks fail by assertion (the 2 Rust repair checks only per receipt) while the preservation checks and the adapted keystore fixture (assertions unchanged) pass, all pass on da6d23a8b, and live Linux runs confirm owner-only keystore files at every #857 crash point; the preamble's 'PR/gate base 4f5954535' is stale but the flagged before-content is unchanged."
}
```
