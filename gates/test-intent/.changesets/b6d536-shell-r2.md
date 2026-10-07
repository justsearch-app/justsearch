---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b14-shell-r2
---

# Test-intent entries

b18: repair the consumed-queue held-crash race using the actor transport no-write fact and bounded actor observation. Earlier entries and verdicts remain history; no acceptance added.

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
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md. b20: the fixture-deadline before for hold_returns_at_once_and_engine_stays_supervised is superseded by the shared-API lifetime witness in updater_actor_hold_witness_tests.rs (b6d536-shell-actor-witness-b20.json). The original candidate check and its oracle remain; no claim that a lifetime witness proves the actor ledger or restart-after-held-exit behavior.",
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
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md. b20: the fixture-deadline before for release_after_failed_prepare_resumes_the_same_actor is superseded by the shared-API lifetime witness in updater_actor_hold_witness_tests.rs (b6d536-shell-actor-witness-b20.json). The original candidate check and its oracle remain; no claim that a lifetime witness proves the actor ledger or restart-after-held-exit behavior.",
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
      "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md. b20: the fixture-deadline before for release_after_failed_prepare_resumes_the_same_actor is superseded by the shared-API lifetime witness in updater_actor_hold_witness_tests.rs (b6d536-shell-actor-witness-b20.json). The original candidate check and its oracle remain; no claim that a lifetime witness proves the actor ledger or restart-after-held-exit behavior.",
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
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Deterministic actor/transport seam consumes the request queue first, observes the held exit before, during and after a failed connection, and returns the transport no-write fact. The real post-staging coordinator must launch exactly one installer in every order. Fails at the installer-count assertion on 99373ec68 and passes after. The before patch adds only test declarations, the no-write constant and an inert result-preserving helper; it adds no repaired coordinator or transport behavior. Real actor/job/ledger evidence: b18-held-crash/report.md, 50 held-crash installs.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The outcome must not depend on scheduling. The decision rests on facts the updater and actor can establish, not on queue state",
          "location": "task t-20261005-b6d536 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-held-crash-b18.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs#updater::commit_delivery_tests::consumed_commit_queue_held_crash_installs_for_every_exit_observation_order"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs",
        "modules/shell/src-tauri/src/shell_actuator.rs#cfg(test)",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "New",
      "behaviour": "Real loopback sockets prove that a failed connection or exit guard writes no bytes, while a request write followed by a missing receipt is ambiguous delivery. The coordinator keeps normal receipt rules after sending begins, and refuses an unsent handoff without actor exit evidence. These are additional candidate counterexamples, not fail-before claims.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "The outcome must not depend on scheduling. The decision rests on facts the updater and actor can establish, not on queue state",
          "location": "task t-20261005-b6d536 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-held-crash-boundaries-b18.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs#updater::commit_delivery_tests::sent_commit_failure_keeps_receipt_rules_and_unsent_without_exit_is_refused",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::connect_failure_and_observed_exit_send_no_commit_bytes",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::request_written_then_missing_receipt_is_ambiguous_delivery",
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs#updater::commit_delivery_tests::actor_without_terminal_observation_cannot_authorize_install"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_actor_hold_witness_tests.rs"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Two real Windows-child shared-API lifetime witnesses reach their behavior assertions on 547b96083 without actuator or host API stubs. Hold returns with the child live, should_continue=false and the original actor ended. The production finish_staged_update path reaches injected failed prepare; the original actor is gone, so the same-actor assertion fails. Candidate holds admission without ending the actor and failed prepare releases it once, preserving the same actor and child. The existing restart-after-held-exit and ledger oracles are retained independently; upgrade, held-exit ledger and quit before proof remain UNVERIFIED.",
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
      "evidence": "gates/test-intent/evidence/b6d536-shell-actor-witness-b20.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_actor_hold_witness_tests.rs#updater::actor_hold_witness_tests::hold_returns_at_once_and_engine_stays_supervised",
        "modules/shell/src-tauri/src/updater_actor_hold_witness_tests.rs#updater::actor_hold_witness_tests::release_after_failed_prepare_resumes_the_same_actor"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Registers a separate test-only module for shared-API lifetime witnesses. Existing updater tests and their oracles are unchanged. The before patch registers the same test-only module and provides the ignored shell-build resource placeholder; it changes no production function, API, signature or behavior.",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verify-shell-r2c-20261007",
  "verdict": "reject",
  "digest": "sha256:9866380da672c2e101f6a44a8532a6e436c5ff5793a0bfeff542c2ff29d7ba41",
  "note": "Narrow held-crash recheck passes 50/50; parent passes 18/20. Delivery faults retain staged update and forbid installation; new consumed-queue fail-before independently reproduces the correct assertion. Slice 2 as a whole still lacks behavioral fail-before proof for five amendment-1 actor checks: independent reproduction on 547b96083 fails only at child-admission setup. Invariant 4 and amendment 1 point 8 remain unmet; prior mechanical assertion classifications are not behavioral proof. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-shell-r2c/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verify-shell-r2c-20261007",
  "verdict": "reject",
  "digest": "sha256:e3734e6034d7e34406703336be4b954e38c939185721dccda5799c03addd1829",
  "note": "Same slice-2 rejection bound to 547b96083 comparison: five actor checks still lack behavioral fail-before, despite the repaired held-crash race passing 50/50 and its new regression failing correctly on 99373ec68."
}
```

## History retained by b20-actor-witness

Prior active entries amended only to name their superseding before witnesses; original wording follows. No acceptance was added.

> ```json
> {
>   "entries": [
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::hold_returns_at_once_and_engine_stays_supervised"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "New",
>       "behaviour": "Candidate actor/hold check passes. Renamed test-only child helpers avoid baseline production-method collisions without changing the oracle. Before 547b96083 compiles, but the neutral actuator fixture reaches its child-admission deadline before this behavior oracle: UNVERIFIED. The mechanical assertion classification is not behavioral proof. See b17-shell-r2-repair/report.md.",
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
>       "evidence": "gates/test-intent/evidence/b6d536-shell-actors-b17.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::release_after_failed_prepare_resumes_the_same_actor",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::actor_upgrade_records_intent_and_empty_job_before_handoff",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::quit_while_held_wins_and_refuses_upgrade"
>       ]
>     }
>   ]
> }
> ```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verify-shell-ti-20261007",
  "verdict": "accept",
  "digest": "sha256:077e18210555114b78005570f16605f0a5b5c3e7a3f892ab00fe04746dc65f07",
  "note": "Narrow test-intent acceptance: both shared-API hold/release witnesses independently fail at their intended assertions on 547b96083 and pass on c1a9eb017; library 139/139. Three remaining actor behavioral before assertions explicitly remain UNVERIFIED under the stated orchestrator ruling; base has no shared actor-ticket/ledger path to their exact oracles, and prior independent real-process evidence applies to unchanged production code. Report F:/agent-sandbox/design-b6d536/codex/verify-shell-ti/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verify-shell-ti-20261007",
  "verdict": "accept",
  "digest": "sha256:059483a18e40f74d864ade315bf6a38a91cf468d0ec4ba72c0f9a0385f77652c",
  "note": "Same narrow test-intent acceptance bound to comparison base 547b96083. The upgrade, held-exit ledger, and quit-held behavioral before results remain UNVERIFIED; acceptance applies the stated orchestrator ruling, not a claim that setup failures prove behavior. Both new lifetime witnesses and 139 library tests independently reproduced. Report F:/agent-sandbox/design-b6d536/codex/verify-shell-ti/report.md."
}
```
