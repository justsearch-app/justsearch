---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b14-shell-r2
---

# Test-intent entries

Spec F section 8.1 shell conformance form: the six updater checks are New, use candidate-only execution evidence and name their case and exact base binary. The unchanged bases have no usable hold/updater control. All six before results are UNVERIFIED; no missing-hook or fixture-timeout failure is claimed as an assertion witness. Candidate conformance cases pass. See b14v-shell-r2/report.md. No acceptance is added.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/supervisor.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "poll_exit consumes an exit before a concurrent host quit and is the only reaper; quit during restart cooldown publishes stopped and spawns nothing; startup quit requests and records one exit. These three checks fail by their intended assertion on 547b96083 with neutral interface scaffolding.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-direct.json",
      "newChecks": [
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::try_reap_called_only_by_poll_exit",
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_cooldown_spawns_nothing",
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_starting_is_recorded"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/supervisor.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "SuspendAwareClock excludes suspended time and grants resume grace. Per Spec F section 8.1 shell conformance form, suspend-gap-is-not-a-hang uses the candidate Tauri adapter against the exact supervisor binary built at 547b96083. The neutral interface clock alone cannot witness the baseline monotonic clock; its stub assertion is not claimed as fail-before proof. See b14u-shell-r2/report.md for the assertion failure and candidate result.",
      "sources": [
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
        "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::suspend_aware_clock_excludes_sleep_and_preserves_resume_grace"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "updater-hold-returns-at-once-and-stays-supervised: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "updater-actor-records-upgrade-before-empty-handoff: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "updater-held-exit-is-unexpected-without-restart: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::exit_while_held_is_unexpected_and_not_restarted"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "updater-quit-while-held-refuses-upgrade-and-stays-staged: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "updater-failed-prepare-releases-the-same-actor: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-r2-direct.json",
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
      "whatMoved": "Real-child handoff fixtures install the shared actuator and actor, then post and await a terminal stop. Test-only child helpers moved inside the cfg(test) impl so interface extraction preserves their owning type. Existing installer ordering, job-empty refusal, containment and spawn ownership oracles remain. Assertions that hold itself joins/cancels or that failed prepare leaves admission held are handled by the Obsolete and New entries below. Slice-1 items changed by slice 2 are engine_host.rs#cfg(test) and updater_handoff_tests.rs. The slice-1 checks forced_shell_stop_observes_supervisor_forced, uncontained_incarnation_is_published_as_shell_unavailable, observed_engine_exit_ends_the_helpers_it_left_running and a_dropped_timed_out_job_is_not_an_empty_updater_witness retain their original oracles; fixture wiring/formatting and phase: None are Adaptation. p5_u2_nonempty_job_never_launches_the_installer keeps its zero-launch, repair-required and resume oracles; its fixture now uses the actor. The changed hold/cancel and failed-prepare held oracles are Obsolete plus the actor New entries, not preservation.",
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
      "conflictSearch": "Binding amendment 1 explicitly replaces hold joining/cancellation and requires release after failed prepare; earlier Spec F hold interpretation and the previous builder report conflict and are superseded. No stronger conflicting owner instruction found. Specifically replaced: hold_linearizes_with_supervisor_installation_and_joins_that_generation and replacement_hold_joins_old_supervisor_blocks_spawn_and_resumes_once require an immediate admission hold with the actor still running; handoff_failures_keep_hold_explicit_and_never_invent_a_dead_engine_fallback changes the prepare arm to released admission and one resume. These are the changed earlier oracles; installer/job-empty oracles are retained."
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "New",
      "behaviour": "Failed prepare releases admission and keeps the same actor available; preparation against the live Engine retains installer launch and job-empty gates. The held-crash and quit cases are witnessed by the production actor checks named here, alongside the preserved real coordinator suite. The conformance cases updater-failed-prepare-releases-the-same-actor, updater-actor-records-upgrade-before-empty-handoff and updater-quit-while-held-refuses-upgrade-and-stays-staged pass on the candidate. Before 547b96083 SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED (missing controls, no assertion witness); no run-evidence before is claimed.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
      ]
    }
  ]
}
```

## Prior builder record (history, b14u-shell-r2)

> ---
> schema: test-intent.v1
> task: t-20261005-b6d536
> author-role: builder
> author-session: b14-shell-r2
> ---
>
> # Test-intent entries
>
> Spec F steps 6, 7, 8 and 12 plus binding updater-hold amendment 1. Script conformance checks are outside product-test scope (Spec F section 8.1); their before/after results are in report-2.md. No acceptance is authored by this builder.
>
> ```json
> {
>   "entries": [
>     {
>       "items": [
>         "modules/shell/src-tauri/src/supervisor.rs#cfg(test)"
>       ],
>       "class": "New",
>       "repaired": true,
>       "behaviour": "poll_exit consumes an exit before a concurrent host quit and is the only reaper; quit during restart cooldown publishes stopped and spawns nothing; startup quit requests and records one exit. These three checks fail by their intended assertion on 547b96083 with neutral interface scaffolding.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S11",
>           "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P9",
>           "quote": "The E6 hang-recovery bound still holds.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r2-direct.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::try_reap_called_only_by_poll_exit",
>         "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_cooldown_spawns_nothing",
>         "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::quit_during_starting_is_recorded"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/supervisor.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "SuspendAwareClock excludes suspended time and grants resume grace. Per Spec F section 8.1 shell conformance form, suspend-gap-is-not-a-hang uses the candidate Tauri adapter against the exact supervisor binary built at 547b96083. The neutral interface clock alone cannot witness the baseline monotonic clock; its stub assertion is not claimed as fail-before proof. See b14u-shell-r2/report.md for the assertion failure and candidate result.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S11",
>           "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P9",
>           "quote": "The E6 hang-recovery bound still holds.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/supervisor.rs#supervisor::tests::suspend_aware_clock_excludes_sleep_and_preserves_resume_grace"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "repaired": true,
>       "behaviour": "An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S15",
>           "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::exit_while_held_is_unexpected_and_not_restarted",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/lib.rs#cfg(test)"
>       ],
>       "class": "New",
>       "repaired": true,
>       "behaviour": "The real cleanup boundary preserves a live incumbent child when the terminal exit is DATA_DIR_LOCKED (2), and still cleans matching children for ordinary exhaustion.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r2-direct.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/lib.rs#tests::exhaustion_cleans_registered_children"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/supervisor.rs#cfg(test)",
>         "modules/shell/src-tauri/src/lib.rs#cfg(test)"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature",
>         "instrumentation"
>       ],
>       "whatMoved": "Existing cancellation-shaped fixtures return stopped/NoEngine after Outcome::Cancelled removal. They still assert no sweep, no replacement spawn, identity protection, handle-release floor, restart budget and unchanged essential-readiness timing. ClockJumpActuator completes the cooperative stop through the actor instead of returning cancellation.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature",
>         "instrumentation"
>       ],
>       "whatMoved": "Real-child handoff fixtures install the shared actuator and actor, then post and await a terminal stop. Test-only child helpers moved inside the cfg(test) impl so interface extraction preserves their owning type. Existing installer ordering, job-empty refusal, containment and spawn ownership oracles remain. Assertions that hold itself joins/cancels or that failed prepare leaves admission held are handled by the Obsolete and New entries below. Slice-1 items changed by slice 2 are engine_host.rs#cfg(test) and updater_handoff_tests.rs. The slice-1 checks forced_shell_stop_observes_supervisor_forced, uncontained_incarnation_is_published_as_shell_unavailable, observed_engine_exit_ends_the_helpers_it_left_running and a_dropped_timed_out_job_is_not_an_empty_updater_witness retain their original oracles; fixture wiring/formatting and phase: None are Adaptation. p5_u2_nonempty_job_never_launches_the_installer keeps its zero-launch, repair-required and resume oracles; its fixture now uses the actor. The changed hold/cancel and failed-prepare held oracles are Obsolete plus the actor New entries, not preservation.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S15",
>           "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P8",
>           "quote": "Opened links, Explorer windows and the update installer outlive the app.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "Obsolete",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-words",
>           "quote": "Read it, then build steps 6, 7, 8 and 12 with it, including its new checks.",
>           "location": "task t-20261005-b6d536 request second-run addendum"
>         }
>       ],
>       "conflictSearch": "Binding amendment 1 explicitly replaces hold joining/cancellation and requires release after failed prepare; earlier Spec F hold interpretation and the previous builder report conflict and are superseded. No stronger conflicting owner instruction found. Specifically replaced: hold_linearizes_with_supervisor_installation_and_joins_that_generation and replacement_hold_joins_old_supervisor_blocks_spawn_and_resumes_once require an immediate admission hold with the actor still running; handoff_failures_keep_hold_explicit_and_never_invent_a_dead_engine_fallback changes the prepare arm to released admission and one resume. These are the changed earlier oracles; installer/job-empty oracles are retained."
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "New",
>       "repaired": true,
>       "behaviour": "Failed prepare releases admission and keeps the same actor available; preparation against the live Engine retains installer launch and job-empty gates. The held-crash and quit cases are witnessed by the production actor checks named here, alongside the preserved real coordinator suite.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S15",
>           "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S16",
>           "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r2-repaired.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
>       ]
>     }
>   ]
> }
> ```
>
> Refresh by builder b14u-shell-r2: the before patch adds only types, signatures, neutral defaults and no-op stubs; it preserves the baseline production methods. Direct fail-before proof for the three supervisor checks and the DATA_DIR_LOCKED cleanup check is b6d536-shell-r2-direct.json. The clock uses the named conformance case above; one-exit-record-per-incarnation, quit-during-start and quit-during-cooldown-spawns-nothing additionally exercise the real baseline binary with candidate adapter bytes.
>
> Limits: the five EngineHost actor/hold checks in b6d536-shell-r2-repaired.json fail to compile before because candidate test-only take_child/kill_and_reap collide with the retained baseline production methods. No contract conformance case covers the same hold/upgrade/release behaviour. They remain repaired entries with unverified fail-before proof; no acceptance is written. The refreshed slice-1 updater check fails its actor fixture's publication timeout before reaching the zero-launch oracle, so it is not valid fail-before proof even though the mechanical evidence classifier labels it assertion. See the b14u-shell-r2 report for exact logs and requirements not met.

## Acceptance records
