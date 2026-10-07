---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b13-shell-r1-20261007
---

# Test-intent entries

b17 repair refresh: direct hold API and original P5 launch-oracle before witnesses are recorded; five actor-fixture before results remain explicitly UNVERIFIED. Native cancelled-session shell exit and held-crash installer completion have fail-before assertions against f6b5b019a. All prior entries and verifier verdicts remain history; this builder adds no acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/containment.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Step 2: suspended spawn runs in a non-inheritable kill-on-close incarnation job; terminating and closing that job end Engine/helpers; failed spawn leaves no process; the shell never joins its job. The conformance cases independently prove supervisor death and helper survival failure on f7cd27765. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S13",
          "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P8",
          "quote": "Opened links, Explorer windows and the update installer outlive the app.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
      "newChecks": [
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::failed_spawn_reports_the_start_error",
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::closing_the_job_kills_what_is_left_in_it",
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::shell_never_joins_an_incarnation_job",
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::contained_spawn_runs_the_program_inside_the_job",
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::ending_an_incarnation_leaves_no_helper",
        "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::terminate_ends_the_engine_and_its_helpers_with_the_engine_stop_code"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Steps 2 and 4: observed Engine exit ends helpers within one second; a real fallback spawn publishes shell_unavailable; a forced shell stop yields SUPERVISOR_FORCED from the actual held process and the contract decoder. A timed-out predecessor job closes its handle at once; its negative witness prevents a missing handle being reported empty to the updater. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S13",
          "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r1-host-b17.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::a_dropped_timed_out_job_is_not_an_empty_updater_witness",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::forced_shell_stop_observes_supervisor_forced",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::uncontained_incarnation_is_published_as_shell_unavailable",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::observed_engine_exit_ends_the_helpers_it_left_running"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "terminal_record supplies containment: None for the additive state-file field. All pre-existing test inputs, expected outcomes and test selection remain unchanged.",
      "sources": [
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
        "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Step 5: every shared summary and 30 shared writer records match the Java format and Node canonical bytes; unknown timestamps obey the 2001 rule; rotation, hard cap, torn tail repair, pending gaps and interleaved appenders preserve readable generations. I/O failures return false without blocking the owner; caller bugs are rejected. Spec F Ã‚Â§8.1 proof for this new module is shared fixture parity with Java/Node, not per-test fail-before run-evidence.",
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
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
      "newChecks": [
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t11_unknown_times_and_java_calendar_edges",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t12_rotation_replaces_old_generation_and_respects_hard_cap",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_fixture_has_the_java_and_node_summary",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::write_failure_is_nonfatal_and_gap_precedes_next_success",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::torn_tail_is_closed_and_rotation_failure_records_a_gap",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_record_writes_the_same_bytes_as_node_and_java",
        "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::appender_follows_rotations_and_rejects_caller_bugs"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs",
        "modules/shell/src-tauri/src/updater_p5_oracle.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "P5-U2: the installer launch count remains zero while helpers are live; timeout reports repair_required and resumes supervision. Same original oracle, now in updater_p5_oracle.rs. run-evidence on f7cd27765 uses its original real-child fixture plus only neutral interface scaffolding. It reaches the launch assertion (1 instead of 0); candidate passes. The prior refreshed fixture deadline is retained as history and is not proof.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P5",
          "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shell-p5-original.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_p5_oracle.rs#updater::handoff_tests::p5_original::p5_u2_nonempty_job_never_launches_the_installer"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The handoff fixture implements await_contained_exit and terminate_contained through the real EngineHost. Only the new helpers-failure arm injects a timeout; every existing fake, assertion and scenario keeps its original outcome. b17: the unchanged P5-U2 oracle has a separate proof module so the before run retains the original fixture rather than an unavailable actor adapter. The current actor fixture and its original P5 test remain covered by the full Rust suite.",
      "sources": [
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
> author-session: codex-b13-shell-r1-20261007
> ---
>
> # Shell containment, updater wait, forced code and ledger (Spec F steps 2-5)
>
> The binding sources below are quoted verbatim from scenarios-v2.md and labelled agent-drafted,
> owner-adopted. Independent verifier acceptance remains pending; the builder does not accept.
>
> Spec F Ã‚Â§8.1 defines the new modules' proof: containment uses the conformance negative
> control, and ledger uses shared fixture parity; neither requires per-test fail-before
> run-evidence. For the containment and EngineHost New entries, the conformance cases
> `engine-exit-ends-its-helpers` and `supervisor-killed-ends-engine-and-helpers` in
> [the supervision contract](../../../governance/supervision-contract.v1.json), driven by
> [the Tauri adapter](../../../scripts/supervisor-conformance/adapters/tauri.mjs), fail on
> f7cd27765 by AssertionError (helper alive at 1 s and 2 s respectively). The forced-stop
> conformance assertion observes code 1 on the before-state. These script checks are outside
> the test-intent scope; their full logs are in the builder report. For the ledger New entry,
> the shared summary and writer fixtures prove Java/Node parity. The candidate-only execution
> evidence remains gates/test-intent/evidence/b6d536-shell-r1-new.json
> (digest sha256:8eee3a69348c7e9b32ff377401ca417f327219137495b7dfbf649987fc812062).
>
> P5-U2's repaired behavior separately uses run-evidence --before f7cd27765 with
> --before-patch gates/test-intent/evidence/b6d536-shell-r1-before-api.patch.
> Every added hunk is an interface declaration or an immediate no-op return; none changes
> process state, blocks, spawns or kills. The regenerated evidence is
> gates/test-intent/evidence/b6d536-shell-r1-updater.json (digest sha256:99eae54a3abe1332ed3ba9c40671d934e14917e1722f2d5b340196f397ceb865);
> the runner records patch digest sha256:a0db5b7aaf02689dad900c2b6d293bb5fa38244cf165be476d88d5c555c2e3fa.
> The candidate passes; the before-state fails the zero-launch assertion with launch count 1
> while the fixture reports a process still alive in the job. The before-state has a CI-style
> ignored headless placeholder. Temporary evidence worktrees live under the requested
> .claude/worktrees directory via TEMP/TMP and are removed afterwards; no node_modules junction
> is linked.
> Step 5 adds the reader/writer enabler; lifecycle record wiring remains step 6.
>
> ```json
> {
>   "entries": [
>     {
>       "items": [
>         "modules/shell/src-tauri/src/containment.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Step 2: suspended spawn runs in a non-inheritable kill-on-close incarnation job; terminating and closing that job end Engine/helpers; failed spawn leaves no process; the shell never joins its job. The conformance cases independently prove supervisor death and helper survival failure on f7cd27765. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S13",
>           "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S14",
>           "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P8",
>           "quote": "Opened links, Explorer windows and the update installer outlive the app.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::failed_spawn_reports_the_start_error",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::closing_the_job_kills_what_is_left_in_it",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::shell_never_joins_an_incarnation_job",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::contained_spawn_runs_the_program_inside_the_job",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::ending_an_incarnation_leaves_no_helper",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::terminate_ends_the_engine_and_its_helpers_with_the_engine_stop_code"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Steps 2 and 4: observed Engine exit ends helpers within one second; a real fallback spawn publishes shell_unavailable; a forced shell stop yields SUPERVISOR_FORCED from the actual held process and the contract decoder. A timed-out predecessor job closes its handle at once; its negative witness prevents a missing handle being reported empty to the updater. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S13",
>           "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S14",
>           "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
>           "label": "agent-drafted, owner-adopted"
>         },
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
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::a_dropped_timed_out_job_is_not_an_empty_updater_witness",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::forced_shell_stop_observes_supervisor_forced",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::uncontained_incarnation_is_published_as_shell_unavailable",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::observed_engine_exit_ends_the_helpers_it_left_running"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature"
>       ],
>       "whatMoved": "terminal_record supplies containment: None for the additive state-file field. All pre-existing test inputs, expected outcomes and test selection remain unchanged.",
>       "sources": [
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
>         "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Step 5: every shared summary and 30 shared writer records match the Java format and Node canonical bytes; unknown timestamps obey the 2001 rule; rotation, hard cap, torn tail repair, pending gaps and interleaved appenders preserve readable generations. I/O failures return false without blocking the owner; caller bugs are rejected. Spec F Ã‚Â§8.1 proof for this new module is shared fixture parity with Java/Node, not per-test fail-before run-evidence.",
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
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t11_unknown_times_and_java_calendar_edges",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t12_rotation_replaces_old_generation_and_respects_hard_cap",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_fixture_has_the_java_and_node_summary",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::write_failure_is_nonfatal_and_gap_precedes_next_success",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::torn_tail_is_closed_and_rotation_failure_records_a_gap",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_record_writes_the_same_bytes_as_node_and_java",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::appender_follows_rotations_and_rejects_caller_bugs"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "New",
>       "repaired": true,
>       "behaviour": "Step 3, P5-U2: a handoff edge reports one process still in the incarnation job after the root receipt, then after terminate confirmation; installer launch count stays zero, the intent becomes RepairRequired and supervised resume is attempted. Before-state interface scaffolding adds only signatures/no-op defaults; the old updater still launches and fails the zero-launch assertion.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-updater.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs#updater::handoff_tests::p5_u2_nonempty_job_never_launches_the_installer"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature"
>       ],
>       "whatMoved": "The handoff fixture implements await_contained_exit and terminate_contained through the real EngineHost. Only the new helpers-failure arm injects a timeout; every existing fake, assertion and scenario keeps its original outcome.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ]
>     }
>   ]
> }
> ```
>
> Combined-branch evidence refresh (builder b14u-shell-r2): b6d536-shell-r1-new.json was rerun on candidate 19c16294d and has digest sha256:85153bd0e4fc141dcbe5ae188d3293c9a7ecb4303ea558df5e78ccbdc532674a; both refreshed evidence files use --before f7cd27765 and the same interface-only before-api.patch. The new-module file records compilation failures before, which are not claimed as fail-before proof; its original Spec F section 8.1 conformance/fixture proof form is retained. b6d536-shell-r1-updater.json was rerun with its own before f7cd27765 and interface-only before-api.patch (additional ShellActuator and quit_force_margin_ms declarations required by slice-2 fixtures); refreshed digest sha256:21decbda4c0d93a1e5b816df8eb5a0e77b3a28dccdcb269efcba7e4dd1dd364c. Candidate P5-U2 passes. The before run now fails the actor fixture's latest_record timeout at updater_handoff_tests.rs:75, before its zero-launch assertion. This is an unverified fail-before gap, not an installer nonempty-job witness. Earlier rejection and acceptance records remain intact below; neither accepts these refreshed bytes. A fresh independent verifier acceptance is required.

## Earlier verifier verdicts retained as history

> ## Acceptance records
>
> ```json
> {
>   "kind": "acceptance",
>   "role": "verifier",
>   "session": "codex-independent-shell-r1-20261007",
>   "verdict": "reject",
>   "digest": "sha256:d97a371c1813bbc889347da6d2cbc06be36fe1acaea229dbe4b6de5e39758b45",
>   "note": "The before-api patch adds an active child.kill() in EngineHost.force_stop; the verifier contract permits interface types and no-op stubs only. Containment, forced-code and updater negative controls passed independently, but this compatibility patch restriction is violated."
> }
> ```
>
> ```json
> {
>   "kind": "acceptance",
>   "role": "verifier",
>   "session": "codex-independent-shell-r1b-20261007",
>   "verdict": "accept",
>   "digest": "sha256:b39a58d5a299d50a5cd121ee3ad1eb89839fd2e627c0b19d6f7077988378b172",
>   "note": "Narrow independent recheck: interface-only patch inspected line by line; fresh P5-U2 candidate pass and baseline assertion failure; both Spec F containment negative controls fail by assertion on detached f7cd27765 and pass on candidate; product files unchanged after 8e0ff7e77."
> }
> ```
>
> ```json
> {
>   "kind": "acceptance",
>   "role": "verifier",
>   "session": "codex-verify-shell-r2-20261007",
>   "verdict": "reject",
>   "digest": "sha256:fb6e842d8c26a6bffc8dd2f7c1ebc5d323c974c04c3bda40fd53f44a785994f3",
>   "note": "The refreshed P5-U2 evidence fails in actor fixture setup before reaching its installer oracle. Independent original slice-1 fixture on detached f7cd27765 does reproduce installer launch while nonempty, but the current evidence reference does not carry that witness. Both compatibility patches are interface-only. See F:/agent-sandbox/design-b6d536/codex/verify-shell-r2/report.md."
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
>         "modules/shell/src-tauri/src/containment.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Step 2: suspended spawn runs in a non-inheritable kill-on-close incarnation job; terminating and closing that job end Engine/helpers; failed spawn leaves no process; the shell never joins its job. The conformance cases independently prove supervisor death and helper survival failure on f7cd27765. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S13",
>           "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S14",
>           "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P8",
>           "quote": "Opened links, Explorer windows and the update installer outlive the app.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::failed_spawn_reports_the_start_error",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::closing_the_job_kills_what_is_left_in_it",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::shell_never_joins_an_incarnation_job",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::contained_spawn_runs_the_program_inside_the_job",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::ending_an_incarnation_leaves_no_helper",
>         "modules/shell/src-tauri/src/containment.rs#containment::tests::windows::terminate_ends_the_engine_and_its_helpers_with_the_engine_stop_code"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Steps 2 and 4: observed Engine exit ends helpers within one second; a real fallback spawn publishes shell_unavailable; a forced shell stop yields SUPERVISOR_FORCED from the actual held process and the contract decoder. A timed-out predecessor job closes its handle at once; its negative witness prevents a missing handle being reported empty to the updater. Spec F Ã‚Â§8.1 fail-before proof is the conformance negative control: engine-exit-ends-its-helpers and supervisor-killed-ends-engine-and-helpers fail on f7cd27765 by assertion; per-test fail-before run-evidence is not the proof for this New entry.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S13",
>           "quote": "If the app's shell crashes or is killed, the Engine and every helper end within seconds; the next launch starts normally.",
>           "label": "agent-drafted, owner-adopted"
>         },
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "S14",
>           "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
>           "label": "agent-drafted, owner-adopted"
>         },
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
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::a_dropped_timed_out_job_is_not_an_empty_updater_witness",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::forced_shell_stop_observes_supervisor_forced",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::uncontained_incarnation_is_published_as_shell_unavailable",
>         "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::observed_engine_exit_ends_the_helpers_it_left_running"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature"
>       ],
>       "whatMoved": "terminal_record supplies containment: None for the additive state-file field. All pre-existing test inputs, expected outcomes and test selection remain unchanged.",
>       "sources": [
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
>         "modules/shell/src-tauri/src/exit_ledger.rs#cfg(test)"
>       ],
>       "class": "New",
>       "behaviour": "Step 5: every shared summary and 30 shared writer records match the Java format and Node canonical bytes; unknown timestamps obey the 2001 rule; rotation, hard cap, torn tail repair, pending gaps and interleaved appenders preserve readable generations. I/O failures return false without blocking the owner; caller bugs are rejected. Spec F Ã‚Â§8.1 proof for this new module is shared fixture parity with Java/Node, not per-test fail-before run-evidence.",
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
>           "scenario": "S17",
>           "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ],
>       "evidence": "gates/test-intent/evidence/b6d536-shell-r1-new.json",
>       "newChecks": [
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t11_unknown_times_and_java_calendar_edges",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t12_rotation_replaces_old_generation_and_respects_hard_cap",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_fixture_has_the_java_and_node_summary",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::write_failure_is_nonfatal_and_gap_precedes_next_success",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::torn_tail_is_closed_and_rotation_failure_records_a_gap",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::t15_every_shared_record_writes_the_same_bytes_as_node_and_java",
>         "modules/shell/src-tauri/src/exit_ledger.rs#exit_ledger::tests::appender_follows_rotations_and_rejects_caller_bugs"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "New",
>       "behaviour": "Step 3 P5-U2: updater-nonempty-job-never-launches-installer drives the production coordinator with a nonempty-job fault edge backed by a live helper. The candidate never launches, waits twice, terminates once, attempts resume and records RepairRequired. Spec F section 8.1 shell conformance proof form, New without run-evidence before. Before f7cd27765 binary SHA-256 b85d866d95a01223ad79608775c6db93610de3a0f3ca334c7472004886c64123: UNVERIFIED; the binary exposes no updater/installer handoff, so its missing hook is not an assertion witness. The earlier fixture deadline is not a behavioural proof. See b14v-shell-r2/report.md.",
>       "sources": [
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
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs#updater::handoff_tests::p5_u2_nonempty_job_never_launches_the_installer"
>       ]
>     },
>     {
>       "items": [
>         "modules/shell/src-tauri/src/updater_handoff_tests.rs"
>       ],
>       "class": "Adaptation",
>       "moved": [
>         "signature"
>       ],
>       "whatMoved": "The handoff fixture implements await_contained_exit and terminate_contained through the real EngineHost. Only the new helpers-failure arm injects a timeout; every existing fake, assertion and scenario keeps its original outcome.",
>       "sources": [
>         {
>           "kind": "owner-adopted-scenario",
>           "task": "t-20261005-b6d536",
>           "scenario": "P5",
>           "quote": "Tray quit and update leave no chat server or helper behind, and the installer can replace files.",
>           "label": "agent-drafted, owner-adopted"
>         }
>       ]
>     }
>   ]
> }
> ```
