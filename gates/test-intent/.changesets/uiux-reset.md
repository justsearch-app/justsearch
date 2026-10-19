---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-nxw19h73
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rn3_normal_start_does_not_enumerate_or_modify_stores"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/factory_reset.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Removing a top-level native-bin symlink never traverses the external target or its runtime directory.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "RS7",
          "quote": "Nothing outside the app's data folder is touched: not the user's indexed files, not folders the user picked, not other applications. A link or junction inside the data folder is removed as a link; its target is untouched.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
      "newChecks": [
        "modules/shell/src-tauri/src/factory_reset.rs#factory_reset::tests::rs7_native_bin_link_is_removed_without_traversing_its_target"
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
          "quote": "The outcome is reported to the user as either complete or a list of what could not be removed. It is never reported complete while something remains, for example a file held by an Engine or AI runtime left over from the previous session. An incomplete wipe is retried at the next start and stays reported until it completes. It never stops the app from starting.",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
      "evidence": "gates/test-intent/evidence/uiux-reset.json",
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
    }
  ]
}
```

## Acceptance records
