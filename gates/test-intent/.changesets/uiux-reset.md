---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-06wpvp_0
---

# Test-intent entries

WP5.1 only. Sources are the owner's adopted area5 scenarios, with dot-free gate ids
S51.n -> RSn and N51.n -> RNn. Quotations retain the scenario text exactly. The
pass-2 incomplete-reset amendment overrides the earlier normal-start wording.

The producer ran each check on the candidate and c00a0cb24. The explicit compatibility
patch exposes the legacy shell wipe through the new path API; it retains its swallowed
errors and native-bin link traversal, and implements no new recovery/storage behavior.
The patch is scaffolding, not product code. The Rust production file first exists in
that patch, so the producer transplants only the candidate test items afterward.
Preservation checks intentionally pass before; only assertion fail-before checks have
repaired: true. The producer's aggregate exit 1 reflects preservation passing before.

The evidence run uses a temporary npx launcher that appends --maxWorkers=4, without
changing selection; recorded command strings precede that addition. TMPDIR and
--before-root keep temporary results and detached before checkouts under tmp/wp51;
node_modules is linked and detached before removal. Cargo uses a shared target cache,
and TAURI_CONFIG points at the source-only frontend asset fixture in tmp/wp51 so
missing packaged resources do not replace behavioral failures with compilation errors.

Scope limits: shell filesystem and component/boot checks do not establish packaged
Windows restart/uninstall, Windows held-file/reparse behavior, actual power-loss
durability, model inference, whole-app absence, timing, real assistive-technology or
visual theme/narrow-layout behavior. Those remain unverified. Existing token checks
also run before and after, with their assertions unchanged. No acceptance is written.

Review history: 2026-10-20 — builder agent-session-91l8ise0 repaired the independently
reported reload failure and added kept-link coverage. Previous acceptance is retained;
this round requires independent acceptance on the final digest.

Round 2 evidence: uiux-reset-boot-repair.json uses --before 97dc55c62 with no
compatibility patch. Boot tests build the production frontend in a subprocess, run
headless Chromium with real SES, and delay the reset module until Symbol is frozen.
Only native IPC and HTTP responses are doubles. The browser tests run separately via
`npx playwright-core install chromium` then `npm run test:unit:lockdown -- --maxWorkers=4`;
browser installation and this check are not currently wired in CI. The new shell
check exercises real Unix symlinks (including dangling targets); its Windows junction
branch is unverified here. Runtime inference and real Windows desktop reloads remain
unverified. uiux-reset-shell-recheck.json refreshes source digests for the original
Rust checks using their original c00a0cb24 compatibility seam; original evidence is
retained unchanged. TMPDIR and --before-root stay under tmp/wp51-repair, npx appends
--maxWorkers=4, and Cargo uses two build jobs and the existing source-only asset fixture.

Review history: 2026-10-20 — builder agent-session-_sq_p_57 applied the continuation
decision to linked ancestors of kept assets, adapted the old parent-link expectation
as explicitly requested, and added a persisted/displayed kept-link receipt with
assertion fail-before checks on 97dc55c62. The prior acceptance remains unchanged.
Round 3 refreshes the new-check evidence and the original checks for modified source
files. The c00a0cb24 compatibility seam is unchanged. The renamed parent-link check
is included in 97dc55c62 evidence and is no longer claimed against the legacy seam.
No Java files were changed. Actual Windows junction/WebView checks remain unverified.

Review history: 2026-10-20 — builder agent-session-06wpvp_0 repaired bounded
retry/settling for recreated storage (RA3), allowed the linked data root (RA2),
reclassified entry 7 as New, replaced rejected brief/orchestrator sources with
agreed RA1/RA4, and removed the superseded RS4 startup sentence. Existing review
records and evidence files are retained unchanged; independent acceptance is pending.

Round 4: uiux-reset-root-retry.json uses --before 769eb6ec6 for both new checks.
The RA3 check starts and joins its own separate Unix process, which recreates storage
750 ms after deletion. RA2 exercises a real linked root and an internal external link;
the Windows junction branch remains unverified here. Existing Rust source digests
are refreshed in uiux-reset-original-shell-refresh.json against the unchanged
c00a0cb24 compatibility seam and uiux-reset-links-refresh.json against 97dc55c62.
Preservation checks passing before cause aggregate producer exit 1 in the original
refresh; each repaired check must still be an assertion failure before. No frontend
tests or production files are changed this round; their evidence is retained.
All evidence uses two Cargo jobs, source-only frontend assets, and temporary
before worktrees below tmp/wp51-retry, removed by the official producer.

Review history: 2026-10-20 — builder agent-session-kx3eb62n added the agreed RA5
source to entry 6 and split entry 25: its two RN3 checks are New preservation
checks against c00a0cb24, without repaired: true; the two RS4 checks remain repaired.
Existing candidate evidence remains valid and is retained unchanged; the gate does
not require new fail-before evidence for preservation. Acceptance records are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Disk reset removes each authored store, blocks backend, retains intent until window-storage acknowledgement and exposes a complete receipt only afterward.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS3",
          "quote": "After the user confirms, the deletion happens without the user having to start the app again by hand (decision DR1). If the user confirms and then uninstalls, nothing the reset said it removed is left on disk, except what the reset itself reported as not removed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS6",
          "quote": "After a completed reset, nothing from before appears anywhere in the app:\n  - no conversations, memories, agent history, indexed documents, watched folders, settings or logs;\n  - no recent searches, composer drafts or conversation titles kept in window storage (decision DR3).\n  Kept models work without being downloaded again.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs3_wipes_every_authored_store_before_start_and_waits_for_storage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A failed owned-process cleanup leaves stores intact and durable incomplete status; a later launch retries successfully.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs4_child_cleanup_failure_preserves_intent_and_stores_until_retry"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Legacy intent is resumed; failure can be cancelled without removing the remaining conversation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs5_legacy_intent_resumes_and_cancellation_keeps_remaining_data"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Cancellation commits non-destructive intent before new work; a leftover uncommitted staging write cannot resurrect reset, and a later explicitly requested reset can still arm.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs5_cancel_commits_non_destructive_intent_before_new_work"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "After disk cleanup, repeated launches awaiting storage acknowledgement or carrying the complete receipt never run deletion again.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS6",
          "quote": "After a completed reset, nothing from before appears anywhere in the app:\n  - no conversations, memories, agent history, indexed documents, watched folders, settings or logs;\n  - no recent searches, composer drafts or conversation titles kept in window storage (decision DR3).\n  Kept models work without being downloaded again.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs5_interruption_after_disk_wipe_never_erases_successor_data"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Option A retains existing model/runtime bytes while removing the rebuildable OCR runtime.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN1",
          "quote": "Models and the AI runtime are kept and keep working (Option A).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA5",
          "quote": "Copies of files that the app restores from its own installation at every start (the bundled OCR runtime, native-bin/tesseract) are removed by the reset like other app data and are restored at the next start; they are not user data and need not be named before or after the reset. Models and the AI runtime stay kept as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rn1_models_and_runtime_keep_their_bytes"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "An idle shell reset probe never calls process cleanup or modifies authored stores.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN3",
          "quote": "A normal start with no pending reset deletes nothing and starts as quickly as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rn3_normal_start_does_not_enumerate_or_modify_stores"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA1",
          "quote": "A link or junction anywhere on the path to a kept item (models, native-bin/llama-server, including native-bin itself) is kept as it is: never deleted, never traversed; the outcome names it as kept.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "behaviour": "A linked native-bin parent is retained as it is; its external runtime and other-application sentinel remain untouched.",
      "evidence": "gates/test-intent/evidence/uiux-reset-links-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs7_native_bin_link_is_kept_without_traversing_its_target"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Removing nested and dangling symlinks preserves the external indexed source file.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS7",
          "quote": "Nothing outside the app's data folder is touched: not the user's indexed files, not folders the user picked, not other applications. A link or junction inside the data folder is removed as a link; its target is untouched.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs7_nested_links_leave_indexed_files_and_other_applications_untouched"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A real filesystem permission-denied removal is reported with its path, persists across status reload, preserves held content, and retries after permissions are restored.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-original-shell-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs4_actual_delete_error_is_reported_and_retried_after_restart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "incomplete reset blocks API discovery and offers retry with each remaining path.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS4 incomplete reset blocks API discovery and offers retry with each remaining path"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "Cancel reset keeps remaining data and restarts without opening ordinary stores.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS5 Cancel reset keeps remaining data and restarts without opening ordinary stores"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "clears drafts, recents, titles and per-window pointers before acknowledging cleanup.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS6",
          "quote": "After a completed reset, nothing from before appears anywhere in the app:\n  - no conversations, memories, agent history, indexed documents, watched folders, settings or logs;\n  - no recent searches, composer drafts or conversation titles kept in window storage (decision DR3).\n  Kept models work without being downloaded again.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS6 clears drafts, recents, titles and per-window pointers before acknowledging cleanup"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "a storage-cleanup failure stays visible and is never acknowledged as complete.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS6",
          "quote": "After a completed reset, nothing from before appears anywhere in the app:\n  - no conversations, memories, agent history, indexed documents, watched folders, settings or logs;\n  - no recent searches, composer drafts or conversation titles kept in window storage (decision DR3).\n  Kept models work without being downloaded again.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS6 a storage-cleanup failure stays visible and is never acknowledged as complete"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "completion is announced on the first screen and dismissed only by Continue.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS4 completion is announced on the first screen and dismissed only by Continue"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "an unavailable reset-status channel blocks startup with a visible error.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS5",
          "quote": "A crash or power loss during the wipe can be resumed. The next start finishes it or reports what remains. The app never presents a half-deleted store as ordinary data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RS5 an unavailable reset-status channel blocks startup with a visible error"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "no pending reset preserves window storage and resolves the backend normally.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN3",
          "quote": "A normal start with no pending reset deletes nothing and starts as quickly as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-shell-recheck.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RN3 no pending reset preserves window storage and resolves the backend normally"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "discloses authored stores, permanent loss, preserved assets, automatic timing and a copy path without encryption.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS1",
          "quote": "Before the user confirms, the control states (judged by meaning, not wording):\n  - what is deleted: conversations, memories, agent history, the index, watched folders, settings and logs, whether or not encryption is set up;\n  - what is kept: downloaded AI models and the AI runtime;\n  - when the deletion happens.\n  It says these items cannot be recovered, encrypted or not, and offers a way to keep conversations that works without encryption, or says plainly that there is none. The help line beside the control does not present it as the way to uninstall unless it also says what stays behind.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RS1 discloses authored stores, permanent loss, preserved assets, automatic timing and a copy path without encryption"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "Cancel and an incomplete typed confirmation never invoke deletion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS2",
          "quote": "Cancel, Escape, or not completing the typed confirmation changes nothing.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RS2 Cancel and an incomplete typed confirmation never invoke deletion"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "Escape cancels, restores focus, and the typed input has an associated description.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS8",
          "quote": "The control and its confirmation:\n  - work by keyboard and screen reader;\n  - work in light, dark and forced-colors themes, and at narrow width;\n  - in a browser rather than the desktop app, explain why the control is unavailable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS2",
          "quote": "Cancel, Escape, or not completing the typed confirmation changes nothing.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RS8 Escape cancels, restores focus, and the typed input has an associated description"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "browser explains the disabled control in visible text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS8",
          "quote": "The control and its confirmation:\n  - work by keyboard and screen reader;\n  - work in light, dark and forced-colors themes, and at narrow width;\n  - in a browser rather than the desktop app, explain why the control is unavailable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RS8 browser explains the disabled control in visible text"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "typed confirmation sends the issued token once and describes automatic restart.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS3",
          "quote": "After the user confirms, the deletion happens without the user having to start the app again by hand (decision DR1). If the user confirms and then uninstalls, nothing the reset said it removed is left on disk, except what the reset itself reported as not removed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN4",
          "quote": "The confirmation-token check on the reset command is unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RS3 typed confirmation sends the issued token once and describes automatic restart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts"
      ],
      "class": "New",
      "behaviour": "settings-to-defaults remains the separate core.reset-settings operation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN2",
          "quote": "\"Reset settings to defaults\" is unchanged.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/SettingsSurface.dataReset.test.ts#RN2 settings-to-defaults remains the separate core.reset-settings operation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts",
        "modules/ui-web/src/boot/desktopRecoveryEntry.test.js",
        "modules/ui-web/src/boot/desktopRecoveryPolling.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Supply the newly queried factory_reset_status channel with an idle result, retaining every prior input, supervisor/updater response, fault, polling schedule, teardown and assertion. The same tests pass before and after in uiux-reset-recovery-preservation.json.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN3",
          "quote": "A normal start with no pending reset deletes nothing and starts as quickly as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "The real production boot and SES lockdown mount reset surfaces on cold starts and actual page reloads. Reset completion Continue and status error Try again reload to a mounted surface; incomplete reset and storage cleanup block normal UI/API discovery.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA4",
          "quote": "Every reload or restart path of the window (reset outcome screens, Engine recovery) shows the app again; none leaves an empty window.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS4",
          "quote": "Finding 1 decided: while a reset is incomplete, the app does not start into normal use; it shows the reset as incomplete, what remains, and offers Retry or Cancel reset (Cancel keeps the remaining data as ordinary data). No new work exists during an incomplete reset, so retries cannot erase it. Verify failure -> restart -> retry and failure -> cancel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-boot-repair.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts#RS4 reset Continue and Try again reload into a mounted app under real lockdown",
        "modules/ui-web/src/boot/boot-lockdown.test.ts#RS4 incomplete and storage-cleanup starts stay gated under real lockdown"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Kept models and native-bin/llama-server links retain their original directory entries and targets while authored stores are removed. Unix also covers dangling links; the Windows junction branch requires Windows execution.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA1",
          "quote": "A link or junction anywhere on the path to a kept item (models, native-bin/llama-server, including native-bin itself) is kept as it is: never deleted, never traversed; the outcome names it as kept.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-links-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rn1_kept_links_are_retained_without_traversing_targets"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A linked native-bin parent is preserved without traversing it, its contents are outside reset scope rather than remaining app data, and its kept-link receipt survives storage completion and restart.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA1",
          "quote": "A link or junction anywhere on the path to a kept item (models, native-bin/llama-server, including native-bin itself) is kept as it is: never deleted, never traversed; the outcome names it as kept.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-links-refresh.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rn1_kept_parent_link_is_reported_through_storage_completion_and_restart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/factoryReset.test.ts"
      ],
      "class": "New",
      "behaviour": "Incomplete and complete reset receipts visibly identify kept native-bin and models links as links to folders outside app data, separately from failures; backend discovery remains blocked.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA1",
          "quote": "A link or junction anywhere on the path to a kept item (models, native-bin/llama-server, including native-bin itself) is kept as it is: never deleted, never traversed; the outcome names it as kept.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-boot-repair.json",
      "newChecks": [
        "modules/ui-web/src/boot/factoryReset.test.ts#RN1 the receipt names kept links outside app data separately from failures"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A linked data root can request and complete a reset through dismissal, clearing the actual data directory while retaining models and the root link and unlinking internal links without touching their targets.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA2",
          "quote": "If the app data folder itself is a link or junction (for example the user moved it), the reset clears the folder it points to, the folder the app uses for its data; links inside it are still never followed.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-root-retry.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::ra2_linked_data_root_is_cleared_without_following_internal_links"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "A separate previous-session stand-in process recreates storage after the initial deletion; the bounded wipe removes that storage before returning a clean disk outcome.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA3",
          "quote": "A file or folder recreated during the wipe by a process of the previous session that has not yet exited is retried for a short, bounded time before the wipe reports anything as remaining; the outcome stays honest if it still remains.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-root-retry.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::ra3_retries_storage_recreated_by_a_previous_session_process"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "New preservation checks relative to c00a0cb24: Engine recovery Retry and both desktop event reloads mount under real lockdown, and normal desktop/browser starts and debug routes mount after lockdown. The retained evidence establishes candidate passes; its assertion failures against intermediate commit 97dc55c62 do not classify these checks as repaired against c00a0cb24.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RA4",
          "quote": "Every reload or restart path of the window (reset outcome screens, Engine recovery) shows the app again; none leaves an empty window.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RN3",
          "quote": "A normal start with no pending reset deletes nothing and starts as quickly as before.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset-boot-repair.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts#RN3 Engine recovery Retry and both desktop event reloads mount under real lockdown",
        "modules/ui-web/src/boot/boot-lockdown.test.ts#RN3 normal desktop and browser starts and debug routes mount after lockdown"
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
  "session": "agent-session-zy9i0tyh",
  "verdict": "accept",
  "digest": "sha256:bc3d5d76462345e88e981272f78707b5a997677a03dbe5079ddfb55bb877bd07",
  "note": "Accepted after reading the before-state tests, area5 WP5.1 + pass-2 amendments and the challenger report before the diff. New entries: each expectation follows from the cited S51.x/N51.x text or the Finding-1 amendment; fail-before re-run to a scratch path reproduced every outcome (all 26 pass on the candidate; every repaired check fails before with an assertion). The baseline seam patch was compared line by line with the c00a0cb24 wipe and reproduces it (errors ignored, marker cleared, backend never blocked). Adaptation: the three boot tests only route factory_reset_status to an idle reply; inputs, faults and assertions unchanged; re-run passes before and after. Minor, not blocking: rn1 also asserts native-bin/tesseract is removed, which RN1 does not state (it matches the locked Option A that area5 cites, and passes before); several RS4 quotes still carry the superseded 'never stops the app from starting' sentence, overridden by the cited amendment."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-xxz8uk16",
  "verdict": "reject",
  "digest": "sha256:ff9d36ff4b42020f9ca8688c5e6ab521aa1d0fed0e3ec23f0f69cd7509408a79",
  "note": "Reject. Re-ran all four evidence files to scratch: every existing check matches its committed outcome and fail-before class (rs7_native_bin_link_is_removed... is now renamed; its successor passes in uiux-reset-boot-repair.json). Omissions: (1) Entries 7, 24 (first source), 25, 26 and 27 cite owner-adopted-scenario sources whose quotes are not in the agreed scenarios (area5.md): they quote orchestrator decisions ('Decision (orchestrator): ...') or repair-brief text ('Repair the defect at its cause ...'), which test-intent.md counts as no source; entries 25-27 have no valid expectation source. Cite the agreed text (RN1 'Models and the AI runtime are kept and keep working (Option A)') and/or a delegated-choice quoting the owner's grant with its scope. (2) Entry 7 is classified Adaptation but changes the oracle (link deleted -> kept) of a test absent at the before-state c00a0cb24; an oracle change is not an Adaptation. Reclassify as New with source, evidence (boot-repair evidence already holds its pass and 97dc55c62 assertion fail-before) and newChecks. Minor: RS4 quotes keep the superseded 'It never stops the app from starting.'; the boot-lockdown checks are not run in CI."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-4xxb6j6p",
  "verdict": "reject",
  "digest": "sha256:522167dfb13b783707c590a39bb1b47d1481831c07e80cc6268df65355deeb84",
  "note": "Reject for one omission. Entry 6 (New, factory_reset::tests::rn1_models_and_runtime_keep_their_bytes): its behaviour and check assert that the OCR runtime native-bin/tesseract is removed ('while removing the rebuildable OCR runtime'), but its only source, RN1 'Models and the AI runtime are kept and keep working (Option A).', states only what is kept; no agreed scenario says the OCR runtime is deleted, and S51.1 lists what is deleted without it. Cite a source for that expectation or drop the tesseract assertion. Everything else checked: other entries' quotes match area5.md (S51.x, RS/RN, RA1-RA4, Pass-2 amendment); entry 24 Adaptation keeps the oracle (adds only an idle factory_reset_status mock); all evidence re-run to scratch on the candidate (c00a0cb24 with the seam patch, 97dc55c62, 769eb6ec6), with every check passing and fail-before classes matching the committed files. Note, not a rejection reason: entry 25 marks 'RN3 Engine recovery Retry...' and 'RN3 normal desktop ... debug routes mount' repaired against intermediate commit 97dc55c62; against c00a0cb24 they guard preserved behaviour."
}
```
