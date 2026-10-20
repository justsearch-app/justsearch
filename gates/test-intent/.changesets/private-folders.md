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

Only this change's six product-test items are covered here. Token-bootstrap items/evidence
and its records are unchanged. Windows account-level P9 remains unverified without a host.
Original repair/preservation receipts use the specified before-state `4975ceedf`.
The hard-link and iterator regressions compare against `46816f593`, as requested in
the follow-up. No receipt uses patches or production overlays. The repository's currently computed merge-base is `c00a0cb24`;
`4975ceedf` is used as explicitly requested, rather than calling an intermediate folder
implementation a before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair controls: fresh/restored runtime and encryption folders are private; existing real descendant directories/files become 0700/0600 on POSIX and receive the same protected owner+SYSTEM DACL on Windows. The real publisher with a fake native setter must repair existing history, keystore and pending entries, skip symbolic links and junctions and stay inside the protected root. Denied permissions and reparse points warn once without deletion. The shell adds one warning for a linked runtime while preserving ledger, supervisor and shutdown writes. Every named check below fails an assertion against the untouched production code at 4975ceedf; no patch is applied.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-repair-v2.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#setupReplacementAndRestartKeepPrivateAndUsable",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#broadRestoredFolderIsRepairedBeforeLoad",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#missingDataDirectoryCanStillBeConfigured",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_first_ledger_and_recreation_are_private",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_linked_runtime_warns_once_without_repairing_through_link"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": false,
      "preservation": true,
      "behaviour": "New preservation controls, not claimed repairs: a linked runtime/encryption folder already works at 4975ceedf. Both before and candidate keep same-owner writes, keystore unlock/recovery and interrupted-replacement recovery working without changing link-target or parent modes. The shell preservation case exercises all three real writers. The same checks are executed on the before-state and pass there; the producer records passed rather than a fabricated assertion failure.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-preservation-v2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#interruptedReplacementPreservesPassphraseAndRecoveryMaterial",
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java#linkedEncryptionStillUnlocksWithoutChangingTargetOrParentPermissions",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_linked_runtime_keeps_all_writers_working"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Supplemental contracts of the newly introduced helper/backend APIs: private creation and recursive repair preserve parent modes and file contents; denied/unsupported setters warn once, including multiple denied children; links are never passed to the setter. The Windows descriptor is protected with exactly owner+SYSTEM inheritable full control, repair sets only DACL/protection flags, and Linux cannot execute the Windows backend but remains usable after its warning. The shell retains the identical literal descriptor/flag oracle. These APIs do not exist at 4975ceedf; they are candidate API checks, not standalone fail-before claims. The compatible real-caller harness in the repair receipt proves the repaired observable expectations before and after without introducing the new API into production at the before-state.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-api-v2.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::windows_directory_descriptor_is_protected_and_only_owner_and_system_inherit_full_control"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Existing runtime repair controls retain their inputs and assertions: fresh/restored and recreated folders are private; real descendants receive private modes or protected Windows ACLs; links/reparse points and denied permissions warn without preventing publication or changing parents/targets. These seven checks still fail by assertion at the original before-state 4975ceedf. They are re-executed only because adding follow-up checks changes the file digest.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-runtime-repair-v3.json",
      "newChecks": [
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
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": false,
      "preservation": true,
      "behaviour": "Unchanged preservation oracle: a linked runtime still publishes without changing target or parent permissions, both at 4975ceedf and now. Re-executed to refresh the changed test file digest; this is not a claimed repair.",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-runtime-preservation-v3.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#linkedRuntimeStillPublishesWithoutChangingTargetOrParentPermissions"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "The Windows descriptor and repair-mask literal assertions and unavailable-backend preservation assertions remain unchanged. A new Linux-runnable API check supplies BY_HANDLE_FILE_INFORMATION bytes to the actual pre-DACL guard: regular files with unsigned nNumberOfLinks > 1 and reparse points must be rejected, single-link files and real directories allowed. The guard consumes the already-opened handle information before SetSecurityInfo. This is a supplemental new API contract, not a claim of executing Win32 on Linux; the hard-link repair expectation is separately pinned at the real publisher boundary against 46816f593.",
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
          "choice": "protected DACL owner+SYSTEM on create and repair as the shell does",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-windows-api-v3.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Hard-link repair exception and iterator recovery: an existing runtime file hard-linked outside the data folder retains its 0664 mode, bytes and inode alias, with one warning despite repeated publication; parents and target-directory modes remain unchanged, the runtime stays private and the manifest is published owner-only. An injected DirectoryIteratorException from hasNext must produce one warning across repeated repairs, allow publisher construction and manifest publication, and preserve the existing history. Both checks fail by assertion on 46816f593 for the respective outside chmod and escaping iterator error, and pass after. No existing assertion was weakened.",
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
      "evidence": "gates/test-intent/evidence/private-folders-hardlinks-iterator-v3.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#hardLinkedHistoryKeepsOutsidePermissionsAndWarnsOnceWhilePublishing",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#failingDirectoryIteratorWarnsOnceAndStartupContinuesWithoutDeletingFiles"
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
