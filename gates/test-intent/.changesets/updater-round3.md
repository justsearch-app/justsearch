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
      "behaviour": "After successful reconciliation and a durable COMMITTED intent, delete only the staged package of that attempt (both .deb and .exe names), retain other attempts and launch evidence, and retry interrupted cleanup on committed startup.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait, preserve Windows presentation, and remove each staged update package only after successful reconciliation is durably committed. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round3-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::committed_reconciliation_removes_only_its_staged_package",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::startup_retries_committed_package_cleanup"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Preserve the package in every non-committed phase, including repair-required and cancellation; a failed durable commit write never deletes it. Cleanup never follows a symlink out of shell staging.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait, preserve Windows presentation, and remove each staged update package only after successful reconciliation is durably committed. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round3-preserved.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::uncommitted_or_repair_required_packages_remain_available",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::failed_commit_write_keeps_the_staged_package",
        "modules/shell/src-tauri/src/updater.rs#updater::tests::committed_cleanup_never_follows_a_package_outside_staging"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EngineRecovery.update.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "instrumentation"
      ],
      "whatMoved": "The complete pending-install oracle (success/cancellation, status polling, updating text, final state/error and timer cleanup) moves into desktopRecoveryUpdate.test.ts. It now starts with a running application mounted by the production boot router and delivers the production upgrade-stop event; it no longer directly mounts the component whose visibility was in question.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait, preserve Windows presentation, and remove each staged update package only after successful reconciliation is durably committed. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecoveryUpdate.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Using the production boot routing and supervisor/status IPC bridges, a Linux upgrade stop replaces the mounted application with the existing recovery surface; the host status projection shows Updating JustSearch through a three-minute installer lock wait. Late boot mounting cannot replace it. Success/cancellation stop polling with the final status and original cancellation error, and resumed Engine reloads normal boot. Windows keeps its existing application presentation. A failed partial installation keeps recovery visible and displays the host-provided manual reinstall instruction in the existing repair paragraph.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Route the Linux upgrade hold to the existing updating surface for the entire package-manager wait, preserve Windows presentation, and remove each staged update package only after successful reconciliation is durably committed. Preserve packages before commit and for repair, including after a failed commit write; cleanup stays confined to shell staging.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round3-ui.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecoveryUpdate.test.ts"
      ]
    }
  ]
}
```

## Acceptance records
