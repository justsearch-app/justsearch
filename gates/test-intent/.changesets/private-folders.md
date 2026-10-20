---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15776-d065-7d12-8b21-f9e5b2e63fdb
---

# Test-intent entries

P1–P9 were adopted by the owner on 2026-10-20: "i agree, proceed accordingly"
(task t-20261019-5ec1a1 agreement). Scenario quotes below reproduce the first builder brief exactly.
The Windows distinct-account scenario remains unverified without a Windows host. The delegated
residual leaves explicit ACLs on copied-in files unchanged. No acceptance record is authored here.

This changeset covers the folder-permission checks. Items owned by the accepted
`token-bootstrap.md` changeset and their overlapping evidence are excluded.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "behaviour": "Owner-only creation and upgrade repair preserve old bytes and parent modes; keystore setup, passphrase/recovery replacement, interruption and same-owner restart remain usable. Permission failures warn once without deletion. Engine symlinks and junctions warn once and continue without repairing through the link. The before-parent run specifically pins the formerly fatal link/refusal change; the initial folder-permission repair has separate before-4975ceedf evidence.",
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
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Engine response to a linked credential folder, which P7 constrains to no traversal during repair but does not specify startup failure versus continuation.",
          "choice": "A symlinked runtime or encryption folder warns once and continues; permissions on the link target and its parent are left unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-link-continuation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/encryption/EncryptionKeystorePermissionsTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/OwnerOnlyDirectoryTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java"
      ],
      "class": "New",
      "behaviour": "Engine-first exit-ledger append and folder recreation protect runtime at 0700 while keeping records readable to the owner and preserving the data-folder mode.",
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
          "scenario": "P5",
          "quote": "Exit ledger and other runtime writers: shell-first and Engine-first startup, ledger appends, rotation, `.pending` and temporary files and recreated folders are inside an owner-only folder from creation, not only after the Engine's repair (the shell creates the folder owner-only too).",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-initial-repair.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerDirectoryPermissionsTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "behaviour": "Protected owner+SYSTEM inheritable full-control DACL on create and repair; repair changes neither owner/group nor SACL. The before-state compatibility patch exposes the legacy NIO policy in the new descriptor/flag vocabulary; the old runtime still uses its unchanged NIO code. These are portable policy-construction comparisons, not distinct-account Windows access evidence.",
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
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Residual Windows ACL policy for files copied in with explicit grants; folder DACL protection itself follows P9.",
          "choice": "Protect only runtime and encryption folder DACLs; copied-in files with explicit grants retain those grants.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-windows-policy.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java#creationDescriptorIsProtectedAndGrantsOnlyOwnerAndSystemInheritableFullControl",
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java#repairSetsDaclAndProtectsItWithoutChangingOwnerGroupOrSacl"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Shell-first ledger append, repair and recreation protect runtime/ledger folders and preserve the data-parent mode. A linked runtime is refused without touching the target. The before patch adds only test-visible constant names required by the candidate test module; the legacy writer never uses them.",
      "sources": [
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
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-shell.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_first_ledger_and_recreation_are_private",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::shell_refuses_linked_runtime_without_touching_target"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "class": "New",
      "behaviour": "Invoking the new Windows-only native backend on Linux is unsupported: one warning, no deletion and no mode/content change. This is a new backend-boundary check; the Engine permission-denial repair is independently pinned against 4975ceedf.",
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
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Internal choices are settled by the orchestrator",
          "delegationLocation": "task t-20261019-5ec1a1 user turn (owner delegation, supplied in the test-intent assignment)",
          "delegationScope": "Residual Windows ACL policy for files copied in with explicit grants; folder DACL protection itself follows P9.",
          "choice": "Protect only runtime and encryption folder DACLs; copied-in files with explicit grants retain those grants.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-policy.json",
      "newChecks": [
        "modules/configuration/src/test/java/io/justsearch/configuration/persistence/WindowsOwnerOnlyDirectoryTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The shell Windows descriptor retains its already protected owner+SYSTEM inheritable full-control DACL and repair flags. This portable construction guard does not claim Windows account-level verification.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "P9",
          "quote": "Windows: owner and SYSTEM only on the folders and the effective access to known existing files (history, keystore, temporary files with explicit ACLs) after upgrade, also after a parent ACL change, with real distinct accounts (no Windows host).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/private-folders-policy.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::windows_directory_descriptor_is_protected_and_only_owner_and_system_inherit_full_control"
      ],
      "repaired": false
    }
  ]
}
```

The D4 assertion-only fail-before requirement required three durable test adaptations:
`OwnerOnlyDirectoryTest` wraps the prior bare `OwnerOnlyDirectory.ensure(...)` link call;
`EncryptionKeystorePermissionsTest` wraps the prior bare `restart()` calls;
`RuntimeDirectoryPermissionsTest` wraps the prior bare publisher constructors and the prior
`Mockito.verify(warning, Mockito.times(1)).warn(...)` call in `assertDoesNotThrow`.
No original expected permission, content, count, status, recovery result or lifecycle outcome was removed.

The two compatibility patches are evidence scaffolding only. The Rust patch supplies unused
test-visible constant names; it leaves the legacy ledger writer unchanged. The Windows patch
exposes the known legacy unprotected NIO ACL policy through the candidate's descriptor/flag
vocabulary; it does not replace or repair the old Engine's runtime path. Neither patch establishes
real Windows account-level access. Their exact bytes/digests are recorded by the evidence runner.

## Acceptance records
