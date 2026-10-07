---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b14-shell-r2
---

# Test-intent entries

Spec F steps 6, 7, 8 and 12 plus binding updater-hold amendment 1. Script conformance checks are outside product-test scope (Spec F section 8.1); their before/after results are in report-2.md. No acceptance is authored by this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/supervisor.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "poll_exit consumes an exit before a concurrent host quit and is the only reaper; quit during restart cooldown publishes stopped and spawns nothing; startup quit requests and records one exit; broadcasts and detected sleep gaps preserve active deadlines and restore hang detection after resume grace. The original 117 checks remain passing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P9",
          "quote": "The E6 hang-recovery bound still holds.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::try_reap_called_only_by_poll_exit",
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_cooldown_spawns_nothing",
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_starting_is_recorded",
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::suspend_aware_clock_excludes_sleep_and_preserves_resume_grace"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::exit_while_held_is_unexpected_and_not_restarted",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The real cleanup boundary preserves a live incumbent child when the terminal exit is DATA_DIR_LOCKED (2), and still cleans matching children for ordinary exhaustion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/lib.rs#tests::exhaustion_cleans_registered_children"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/supervisor.rs#cfg(test)",
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Existing cancellation-shaped fixtures return stopped/NoEngine after Outcome::Cancelled removal. They still assert no sweep, no replacement spawn, identity protection, handle-release floor, restart budget and unchanged essential-readiness timing. ClockJumpActuator completes the cooperative stop through the actor instead of returning cancellation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Real-child handoff fixtures install the shared actuator and actor, then post and await a terminal stop. Test-only child helpers moved inside the cfg(test) impl so interface extraction preserves their owning type. Existing installer ordering, job-empty refusal, containment and spawn ownership oracles remain. Assertions that hold itself joins/cancels or that failed prepare leaves admission held are handled by the Obsolete and New entries below.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P8",
          "quote": "Opened links, Explorer windows and the update installer outlive the app.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Read it, then build steps 6, 7, 8 and 12 with it, including its new checks.",
          "location": "task t-20261005-b6d536 request second-run addendum"
        }
      ],
      "conflictSearch": "Binding amendment 1 explicitly replaces hold joining/cancellation and requires release after failed prepare; earlier Spec F hold interpretation and the previous builder report conflict and are superseded. No stronger conflicting owner instruction found."
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Failed prepare releases admission and keeps the same actor available; preparation against the live Engine retains installer launch and job-empty gates. The held-crash and quit cases are witnessed by the production actor checks named here, alongside the preserved real coordinator suite.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
      ]
    }
  ]
}
```

## Acceptance records
