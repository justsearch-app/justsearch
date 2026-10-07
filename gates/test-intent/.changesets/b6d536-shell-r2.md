---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b14-shell-r2
---

# Test-intent entries

b17 repair refresh: direct hold API and original P5 launch-oracle before witnesses are recorded; five actor-fixture before results remain explicitly UNVERIFIED. Native cancelled-session shell exit and held-crash installer completion have fail-before assertions against f6b5b019a. All prior entries and verifier verdicts remain history; this builder adds no acceptance.

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
      "evidence": "gates/test-intent/evidence/b6d536-shell-direct-b17.json",
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
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::exit_while_held_is_unexpected_and_not_restarted"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-direct-b17.json",
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
      "whatMoved": "Real-child handoff fixtures install the shared actuator and actor, then post and await a terminal stop. Test-only child helpers moved inside the cfg(test) impl so interface extraction preserves their owning type. Existing installer ordering, job-empty refusal, containment and spawn ownership oracles remain. Assertions that hold itself joins/cancels or that failed prepare leaves admission held are handled by the Obsolete and New entries below. Slice-1 items changed by slice 2 are engine_host.rs#cfg(test) and updater_handoff_tests.rs. The slice-1 checks forced_shell_stop_observes_supervisor_forced, uncontained_incarnation_is_published_as_shell_unavailable, observed_engine_exit_ends_the_helpers_it_left_running and a_dropped_timed_out_job_is_not_an_empty_updater_witness retain their original oracles; fixture wiring/formatting and phase: None are Adaptation. p5_u2_nonempty_job_never_launches_the_installer keeps its zero-launch, repair-required and resume oracles; its fixture now uses the actor. The changed hold/cancel and failed-prepare held oracles are Obsolete plus the actor New entries, not preservation. b17: test-only take_child and kill_and_reap are renamed fixture_take_child and fixture_kill_and_reap, including test call sites; no oracle changes.",
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
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Amendment 1 point 1: acquire_replacement_hold must leave the live Engine supervised. The real-child hold API witness asserts actor alive, should_continue=true and Engine live after hold returns. Before 547b96083 reaches this exact oracle with continues=false, actor=false, live=true; candidate passes. No actuator implementation is transplanted.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::hold_api_tests::hold_api_preserves_live_engine_supervision"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/quit.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S16 / Spec F 4.4: a quit begun for session end requests Tauri exit once even when Windows cancels ENDSESSION; a query before Tauri setup also closes once the app handle is ready. Non-session stops retain their exit behavior. f6b5b019a fails the two exit-count assertions (0 instead of 1) with only no-op interface scaffolding. The real Tauri shell receives native QUERY then END(FALSE) and has an empty process census afterwards.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-repair-f6-final.json",
      "newChecks": [
        "modules/shell/src-tauri/src/quit.rs#quit::tests::cancelled_session_end_still_requests_shell_exit_once",
        "modules/shell/src-tauri/src/quit.rs#quit::tests::early_session_query_is_closed_when_tauri_is_ready"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Amendment 1 point 5: finish_staged_update launches the installer after the held actor records a pre-commit Engine crash and the containment job empties. The exit stays UNEXPECTED, no UPGRADE intent is fabricated and admission stays held. f6b5b019a fails the installer count assertion (0 instead of 1); candidate launches once. Errors after the commit request is taken for HTTP still require normal upgrade receipts.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-repair-f6-final.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs#updater::handoff_tests::held_crash_before_commit_still_launches_installer"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/quit.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Preservation: tray, window and upgrade causes do not request the session-only exit callback.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-quit-preserved-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/quit.rs#quit::tests::other_stop_causes_do_not_request_session_exit"
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

## Earlier verifier verdicts retained as history

> ## Acceptance records
>
> ```json
> {
>   "kind": "acceptance",
>   "role": "verifier",
>   "session": "codex-verify-shell-r2-20261007",
>   "verdict": "reject",
>   "digest": "sha256:2528f6e1a61ff1da28755f4bdc0b92ea6659a1bc244d2070129f821dcbbcf414",
>   "note": "Direct reaper and DATA_DIR_LOCKED fail-before assertions reproduced; suspend and startup conformance failures reproduced on the exact baseline binary. Baseline production hold witness exists and was missed. Refreshed repaired actor entries still lack valid before assertions. Candidate session-end case failed 7/20 isolated repetitions; real shell remains running after cancelled session end, violating Spec F 4.4. See F:/agent-sandbox/design-b6d536/codex/verify-shell-r2/report.md."
> }
> ```

## History retained by b17-shell-r2-repair

> # Test-intent entries
>
> Spec F section 8.1 shell conformance form: the six updater checks are New, use candidate-only execution evidence and name their case and exact base binary. The unchanged bases have no usable hold/updater control. All six before results are UNVERIFIED; no missing-hook or fixture-timeout failure is claimed as an assertion witness. Candidate conformance cases pass. See b14v-shell-r2/report.md. No acceptance is added.
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
>       "behaviour": "updater-hold-returns-at-once-and-stays-supervised: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "updater-actor-records-upgrade-before-empty-handoff: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "updater-held-exit-is-unexpected-without-restart: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::exit_while_held_is_unexpected_and_not_restarted"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "updater-quit-while-held-refuses-upgrade-and-stays-staged: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "updater-failed-prepare-releases-the-same-actor: An admission hold returns immediately with the one actor still supervising; actor upgrade records UPGRADE, reaps once and confirms job empty before handoff; a held crash is unexpected and cannot restart; quit while held wins and refuses upgrade; release after failed preparation resumes the same actor and its normal cooldown. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Candidate PASS. Before 547b96083 binary SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED, no usable hold/updater control, no assertion witness. Missing hooks do not establish the requested behaviour failure. See b14v-shell-r2/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
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
>       "behaviour": "Failed prepare releases admission and keeps the same actor available; preparation against the live Engine retains installer launch and job-empty gates. The held-crash and quit cases are witnessed by the production actor checks named here, alongside the preserved real coordinator suite. The conformance cases updater-failed-prepare-releases-the-same-actor, updater-actor-records-upgrade-before-empty-handoff and updater-quit-while-held-refuses-upgrade-and-stays-staged pass on the candidate. Before 547b96083 SHA-256 7b61524c4f521060aedefec8fbb24c9a8368cacd4837c3fe0ca762b4258baf46: UNVERIFIED (missing controls, no assertion witness); no run-evidence before is claimed.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-updater-conformance.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
>       ]
>     }
>   ]
> }
> ```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verify-shell-r2b-20261007",
  "verdict": "reject",
  "digest": "sha256:c098e9c6da392c755278dea06b0b96f03301d5a3bb4c793973af82ff1e22efe0",
  "note": "Independent recheck fails amendment 1 point 5: reused real held-crash updater driver launches only 5/20 times; 15 traces have prepare and no commit, zero installer launches and repair_required. F1 and F3 pass; repair/hold/P5 fail-before assertions reproduced. Five actor fixtures retain unverified behavioral before results. Evidence/report: F:/agent-sandbox/design-b6d536/codex/verify-shell-r2b/report.md."
}
```
