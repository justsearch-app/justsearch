---
schema: test-intent.v1
task: updater-v2-round3
author-role: builder
author-session: codex-builder-round3
---

# Test-intent entries

The independent verifier supplies acceptance after checking the candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "After successful reconciliation and a durable COMMITTED intent, delete every staged attempt package (both .deb and .exe names) and empty attempt folders, retain launch evidence, and retry interrupted cleanup on committed startup even if the committed package is already gone.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait and preserve Windows presentation. After successful reconciliation is durably committed, remove every staged attempt package and empty attempt folder. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging. Remove the redundant installation-status polling; the boot route owns the updating surface.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round4.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::committed_reconciliation_removes_all_staged_packages_and_empty_attempts",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::startup_retries_committed_package_cleanup"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Preserve packages in every non-committed phase, including repair-required and cancellation; a failed durable commit write never deletes them. Cleanup never follows a package or attempt-folder symlink out of shell staging.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait and preserve Windows presentation. After successful reconciliation is durably committed, remove every staged attempt package and empty attempt folder. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging. Remove the redundant installation-status polling; the boot route owns the updating surface.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round4.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::uncommitted_or_repair_required_packages_remain_available",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::failed_commit_write_keeps_the_staged_package",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::committed_cleanup_never_follows_a_package_outside_staging"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecoveryUpdate.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Using the production boot routing and supervisor/status IPC bridges, a Linux upgrade stop replaces the mounted application with the existing recovery surface; the host status projection shows Updating JustSearch through a three-minute installer lock wait. Late boot mounting cannot replace it. Success/cancellation expose the final status and original cancellation error, and resumed Engine reloads normal boot. Windows keeps its existing application presentation. A failed partial installation keeps recovery visible and displays the host-provided manual reinstall instruction in the existing repair paragraph.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait and preserve Windows presentation. After successful reconciliation is durably committed, remove every staged attempt package and empty attempt folder. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging. Remove the redundant installation-status polling; the boot route owns the updating surface.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round3-ui.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecoveryUpdate.test.ts"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Committed cleanup refuses a staging root that is a symbolic link: packages and folders behind the link are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "After a confirmed update, the cleanup of staged update packages acts only on the shell's own staging folder: when that folder is itself a link, nothing behind it is deleted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round4.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::committed_cleanup_never_follows_a_linked_staging_root"
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
  "session": "claude-verifier-60x_8huq",
  "verdict": "accept",
  "digest": "sha256:31d122bed91613d20c07ed465589028902fffcb860ea71d187a610239808aa32",
  "note": "Verifier of d0895c8587. Independently reproduced fail-before on c138996c9e with only the candidate's test hunks applied: removes_all_staged_packages (older cancelled package kept), startup_retries (older attempt kept), linked_staging_root (package behind link deleted) fail by assertion; the three preservation checks pass before and after. VM: after commit all staged packages and empty attempt folders were removed, a folder with a non-package file and a stray file were kept. desktopRecoveryUpdate.test.ts 4/4; appUpdateState.ts identical to main. Delegation quote not checked against the task record (not available to the verifier)."
}
```
