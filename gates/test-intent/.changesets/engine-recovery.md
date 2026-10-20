---
schema: test-intent.v1
task: t-20261020-51b072
author-role: builder
author-session: 01a1563a-7f02-7081-9d04-646e3ac0b504
---

# Test-intent entries

Owner adopted the E1–E21 scenarios on 2026-10-20. Entries below quote the relevant
scenarios verbatim. Engine-side queue scenarios E8–E15 belong to a later assignment.
Coverage is against the merge-base with origin/main, c00a0cb24. There are eight flagged items;
separate entries distinguish new assertions from unchanged test formatting in the same item.
No acceptance record is written by the builder. Governance tooling is unchanged by this correction;
no Java check source changed, so its existing execution evidence remains valid. No Gradle run was
needed for this correction; the evidence runner's refreshed invocation contains Rust checks only.

The owner's corrected decision after rejecting 89c6581d5 replaces the preceding prior-health rule:
"the shared supervisor behaviour stays identical in both (delayed retry after exhaustion, whether
or not the Engine was healthy before), so revert the `!s.firstReadyDone` condition."

The default shared JS supervisor now retains unconditional transient delayed retry, matching Rust.
The optional stopOnInitialExhaustion policy defaults to false and the production dev runner alone
passes true, preserving main's failed-start exit-1 contract. The conformance adapter does not disable
that CLI policy. A common conformance probe directly drives default JS supervision with the real
Engine actuator/fixture and the existing Rust harness; both exhaust at incarnation 4, publish a retry
deadline, start incarnation 5 with a fresh budget, and exhaust again at incarnation 8 without a
healthy incarnation. Its shared case is recovery-never-healthy-delayed-retry (E4/E17).

Script tests are outside this gate's product scope. The former default-loop startup regression now
explicitly selects the CLI admission policy; an additional default-loop check requires delayed retry
without prior readiness. That new check and the real-Engine parity probe fail by assertion on
89c6581d5. The default-loop check passes on known-good 876fec015 and the candidate. The existing
initial-exit process check passes five consecutive candidate runs. Evidence and commands for the
correction are in gates/test-intent/evidence/engine-recovery-supervisor-parity.txt.

Fresh Rust execution evidence covers the existing manual restart check unchanged, including the
never-ready initial generation and delayed fifth spawn. Earlier Java/UI and repaired-behaviour
fail-before evidence remains tied to the unchanged check-file content digests. The temporary shared
before/known-good clones live under TMPDIR and are removed after the checks. Compatibility patches
are evidence-only fixtures, never production patches or substituted passing implementations.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/JvmCrashEvidenceTest.java"
      ],
      "class": "New",
      "behaviour": "All three new checks: newest fatal JVM log is an engine crash with its timestamp and exception type and no private paths in the summary (E2); the 64-line summary bound and heap exclusion (E2 preservation controls); export includes only the newest three fatal logs with bounded, redacted text and excludes heap dumps (E2). The whole class is the named execution check; two assertions fail before and all three methods pass after. The formerly crash-JSON-only summary/export omission is repaired. The three/64-line/1MiB limits are bounded text-only implementation choices, not claims that those constants are owner-mandated.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E2",
          "quote": "The diagnostics export and the latest-crash summary find those JVM fatal logs (not only crash-*.json), without exporting heap dumps automatically.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-51b072 request, design D-d",
          "quote": "Read only the first 64 lines and redact paths."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-51b072 request, additional design E2",
          "quote": "E2: find the diagnostics export and the latest-crash summary (they currently look only at crash-*.json); make them include the JVM fatal-error logs in `<dataDir>/crashes/` (bounded: newest few, text only, no heap dumps)."
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/JvmCrashEvidenceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Two new host checks: after the exhausted actor has actually finished and its delayed replacement spawn failed, manual restart installs one fresh actor, reaches running with a new port/token/instance, and resets its budget (E3); restart refuses quit/update holds and admits no child (E5). This is the newly introduced manual-restart capability. The four initial incarnations in the first check never became ready: E4 still admits the delayed fifth spawn before its failure, so this test intentionally retains that prerequisite. The new default-JS conformance probe checks the same no-prior-health rule under E17; the dev CLI alone opts into terminating unsuccessful admission.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E3",
          "quote": "Desktop, restart budget spent: the recovery screen shows the cause (exit reason, count, problematic frame when known) and a restart action; pressing it brings the Engine back to READY without quitting the app, and the UI reconnects to the new instance's port and token, ignoring stale manifests and late probe results.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E5",
          "quote": "Repeated restart clicks racing the cooldown, quit or update preparation leave exactly one supervisor and one Engine, and no spawn after quit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E4",
          "quote": "Desktop, budget spent, nobody present: after 30 minutes a new Engine generation starts with a fresh budget; quitting during the wait ends cleanly; an update hold blocks the retry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E17",
          "quote": "The Rust shell supervisor and the JS supervisor (dev runner, conformance) behave the same for exhaustion, delayed retry, manual restart, hang detection, stability-budget reset and shutdown (conformance cases in both).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-51b072 user turn: independent rejection of 89c6581d5 and corrected decision",
          "quote": "the shared supervisor behaviour stays identical in both (delayed retry after exhaustion, whether or not the Engine was healthy before), so revert the `!s.firstReadyDone` condition."
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::manual_restart_after_finished_exhaustion_reaches_ready_with_a_new_generation",
        "modules/shell/src-tauri/src/engine_host.rs#engine_host::tests::manual_restart_refuses_quit_and_update_holds_without_admitting_a_child"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Existing terminal_record test fixture supplies None for the four added StateRecord fields (retry_at, not_ready, recent_exits, crash_file). Other existing test edits are rustfmt layout. Inputs, injected process faults, existing assertions, timeouts and platform conditions remain identical; the two new test functions are covered separately.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E17",
          "quote": "The Rust shell supervisor and the JS supervisor (dev runner, conformance) behave the same for exhaustion, delayed retry, manual restart, hang detection, stability-budget reset and shutdown (conformance cases in both).",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_probe.rs#cfg(test)"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Apply rustfmt to the edited Rust file; preserve every existing test oracle. Only rustfmt array/assertion layout changes in essential_readiness_requires_the_engine_ready_epoch; the READY epoch, six non-READY states, six malformed timestamps and expected outcomes are byte-for-byte the same values.",
        "callersAndStateOwners": "Existing shell unit tests continue to own their temporary profiles and children; formatting changes no caller, saved state or ownership.",
        "indirectBehaviours": "No input, precondition, observation, assertion, tag, conditional disable or fixture lifetime changes.",
        "evidence": "The complete extracted cfg(test) items at c00a0cb24 and candidate compare byte-identical after rustfmt, preserving literal contents and every assertion; proof: gates/test-intent/evidence/engine-recovery-formatting.txt. Both real supervisor conformance adapters pass, including health/readiness, exit-2, quit and child-ownership checks; see gates/test-intent/evidence/engine-recovery-supervisor-parity.txt. The independent prior checks at head 876fec015 also covered these unchanged tests.",
        "unresolvedRisks": "No unresolved semantic difference in these test-only formatting hunks. Linux evidence does not claim a new Windows execution."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E21",
          "quote": "Readiness that flaps, a changed READY epoch, failed status probes or a Windows suspend/resume do not refill the restart budget; only sustained readiness does.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/lib.rs#cfg(test)"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Apply rustfmt to the edited Rust file; preserve every existing test oracle. Only rustfmt layout in existing tests: manifest records, terminal outcome arguments, malformed and accepted schema fixtures. No test selection, timeout, fault, expected value or cleanup ownership changed.",
        "callersAndStateOwners": "Existing shell unit tests continue to own their temporary profiles and children; formatting changes no caller, saved state or ownership.",
        "indirectBehaviours": "No input, precondition, observation, assertion, tag, conditional disable or fixture lifetime changes.",
        "evidence": "The complete extracted cfg(test) items at c00a0cb24 and candidate compare byte-identical after rustfmt, preserving literal contents and every assertion; proof: gates/test-intent/evidence/engine-recovery-formatting.txt. Both real supervisor conformance adapters pass, including health/readiness, exit-2, quit and child-ownership checks; see gates/test-intent/evidence/engine-recovery-supervisor-parity.txt. The independent prior checks at head 876fec015 also covered these unchanged tests.",
        "unresolvedRisks": "No unresolved semantic difference in these test-only formatting hunks. Linux evidence does not claim a new Windows execution."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E7",
          "quote": "Exhaustion by exit 2 (data folder locked by another process): no automatic retry; the screen says why.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_actuator.rs#cfg(test)"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Apply rustfmt to the edited Rust file; preserve every existing test oracle. Only rustfmt layout in existing Linux identity and signalled-exit tests. Same real child signals, matching birth identity, deleted executable, REQUESTED/UNEXPECTED classification and no fabricated exit code. Platform conditions, timeouts and cleanup remain.",
        "callersAndStateOwners": "Existing shell unit tests continue to own their temporary profiles and children; formatting changes no caller, saved state or ownership.",
        "indirectBehaviours": "No input, precondition, observation, assertion, tag, conditional disable or fixture lifetime changes.",
        "evidence": "The complete extracted cfg(test) items at c00a0cb24 and candidate compare byte-identical after rustfmt, preserving literal contents and every assertion; proof: gates/test-intent/evidence/engine-recovery-formatting.txt. Both real supervisor conformance adapters pass, including health/readiness, exit-2, quit and child-ownership checks; see gates/test-intent/evidence/engine-recovery-supervisor-parity.txt. The independent prior checks at head 876fec015 also covered these unchanged tests.",
        "unresolvedRisks": "No unresolved semantic difference in these test-only formatting hunks. Linux evidence does not claim a new Windows execution."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E17",
          "quote": "The Rust shell supervisor and the JS supervisor (dev runner, conformance) behave the same for exhaustion, delayed retry, manual restart, hang detection, stability-budget reset and shutdown (conformance cases in both).",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/recovery.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The fresh profile test pins the repaired missing crash-directory preparation, JVM option paths containing spaces, unique fatal-log/heap-dump names across replacements and relaunch, and retained first evidence. The before patch only exposes the original inline JVM flags, preserving absent mkdir and fixed names. Other summary APIs are nonexecuted stubs to type-check the transplanted module; no recovery fix is imported.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E1",
          "quote": "Fresh profile with no crashes folder (including a Windows path with spaces): a native crash of the Engine leaves its JVM fatal-error log in `<dataDir>/crashes/`, and a Java heap OOM leaves the artifact the Engine's JVM flags promise for OOM (named in the change), on Windows and Linux. After repeated crashes, exhaustion and an app relaunch, the first failure's evidence is still there and attributable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-jvm-folder.json",
      "newChecks": [
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::fresh_crash_folder_with_spaces_keeps_artifacts_across_boots"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/recovery.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The three new recovery-data checks cover the new spawn-obstacle reporting interface (E6), a bounded fatal-log header and Windows-path redaction in the new shell-side summary (E1/E3), and a ledger-based 24-hour count surviving stability-budget reset and shell relaunch including an exhausting hang (E19). These are new shell recovery-data capabilities, not replacements for old assertions.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E1",
          "quote": "Fresh profile with no crashes folder (including a Windows path with spaces): a native crash of the Engine leaves its JVM fatal-error log in `<dataDir>/crashes/`, and a Java heap OOM leaves the artifact the Engine's JVM flags promise for OOM (named in the change), on Windows and Linux. After repeated crashes, exhaustion and an app relaunch, the first failure's evidence is still there and attributable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E3",
          "quote": "Desktop, restart budget spent: the recovery screen shows the cause (exit reason, count, problematic frame when known) and a restart action; pressing it brings the Engine back to READY without quitting the app, and the UI reconnects to the new instance's port and token, ignoring stale manifests and late probe results.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E6",
          "quote": "A spawn that fails (Java missing, data folder unavailable or locked) shows the actual obstacle on the recovery screen, for the first spawn and for a replacement.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E19",
          "quote": "A long crash loop that never exhausts the budget (more than 300 s READY between crashes) is visible as a restart count in the recovery data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-51b072 request, design D-d",
          "quote": "Read only the first 64 lines and redact paths."
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::unavailable_crash_folder_reports_the_real_obstacle",
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::fatal_summary_is_bounded_and_redacts_windows_paths_with_spaces",
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::the_daily_count_survives_budget_reset_and_shell_relaunch"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/shell/src-tauri/src/recovery.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The heap-retention repair leaves the oldest and newest regular .hprof files across repeated spawn preparation, preserves fatal logs and similarly named directories, and removes the intermediate dumps. Evidence fails with five dumps on the first recovery implementation, 347c0f4c7, and passes with two on the candidate. Main predates this helper; the preceding implementation is the relevant before-state for this follow-up defect.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E1",
          "quote": "Fresh profile with no crashes folder (including a Windows path with spaces): a native crash of the Engine leaves its JVM fatal-error log in `<dataDir>/crashes/`, and a Java heap OOM leaves the artifact the Engine's JVM flags promise for OOM (named in the change), on Windows and Linux. After repeated crashes, exhaustion and an app relaunch, the first failure's evidence is still there and attributable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-51b072 user turn: verifier follow-up on 347c0f4c7, item 4",
          "quote": "Decision: at Engine spawn preparation keep at most two heap dumps in `crashes/`, the oldest (first failure, E1) and the newest, and delete the others; fatal-error logs are small and stay."
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-heap-retention.json",
      "newChecks": [
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::spawn_preparation_keeps_only_the_first_and_latest_heap_dumps"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "New parameterized check of transport/signature failure and digest/size/executable rejection: update state becomes error with the actual obstacle, byte progress clears, the pending update stays available, and installation/recovery controls are released. Before scaffolding only extracts the old inline byte-acceptance error path without adding the missing coordinator transition; the named assertion fails in downloading before and passes in error after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E16",
          "quote": "A failed or rejected update download returns to an error state that leaves update and recovery actions usable.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-updater.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::download_failures_and_rejections_return_to_actionable_error"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "rustfmt on pre-existing updater tests; the new E16 test is covered separately.",
        "callersAndStateOwners": "Existing updater test helpers, temporary profiles and UpdateCoordinator owners remain the same.",
        "indirectBehaviours": "Existing source-version lists, compatibility fixtures, action conditions, transport metadata and all assertions keep the same inputs and expected values.",
        "evidence": "Inspect the diff outside download_failures_and_rejections_return_to_actionable_error: only whitespace/layout. New E16 execution evidence exercises the candidate coordinator and the extracted unchanged baseline error path; both conformance adapters pass update preparation, failed preparation and held-replacement cases.",
        "unresolvedRisks": "No unresolved oracle change in pre-existing tests; known unrelated updater_probe handoff fixture failures remain outside this assignment."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E16",
          "quote": "A failed or rejected update download returns to an error state that leaves update and recovery actions usable.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "class": "New",
      "behaviour": "Four added tests cover restart_engine invocation and refused-update reason with usable controls (E3/E5); Java spawn obstacle, retained native frame, crash-folder reveal with spaces and daily restart count (E1/E3/E6/E19); running-but-not-ready cause even when the API responds (E20); and locked-profile explanation with no automatic retry (E7). The existing terminal-recovery assertion changes from \"Try connecting again\" to \"Restart JustSearch\", as E3 requires. Other existing expectations stay. Whole-file execution covers all eleven tests; baseline assertions fail and candidate passes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E1",
          "quote": "Fresh profile with no crashes folder (including a Windows path with spaces): a native crash of the Engine leaves its JVM fatal-error log in `<dataDir>/crashes/`, and a Java heap OOM leaves the artifact the Engine's JVM flags promise for OOM (named in the change), on Windows and Linux. After repeated crashes, exhaustion and an app relaunch, the first failure's evidence is still there and attributable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E3",
          "quote": "Desktop, restart budget spent: the recovery screen shows the cause (exit reason, count, problematic frame when known) and a restart action; pressing it brings the Engine back to READY without quitting the app, and the UI reconnects to the new instance's port and token, ignoring stale manifests and late probe results.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E5",
          "quote": "Repeated restart clicks racing the cooldown, quit or update preparation leave exactly one supervisor and one Engine, and no spawn after quit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E6",
          "quote": "A spawn that fails (Java missing, data folder unavailable or locked) shows the actual obstacle on the recovery screen, for the first spawn and for a replacement.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E7",
          "quote": "Exhaustion by exit 2 (data folder locked by another process): no automatic retry; the screen says why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E19",
          "quote": "A long crash loop that never exhausts the budget (more than 300 s READY between crashes) is visible as a restart count in the recovery data.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E20",
          "quote": "The Engine process answers but the index never becomes READY (health 503 indefinitely, #739's \"worker not READY\"): within a bounded time the app shows that the Engine is up but not ready and why, rather than \"connecting\" forever or claiming recovery.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-ui.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15866-a331-7452-b8b0-5d508a095b3d",
  "verdict": "reject",
  "digest": "sha256:74a3d93e3c85456c4e3bf1b0c3f02369ff9f5a314a24a8dd15556a66e88f96bf",
  "note": "E17 fails: Rust retries a never-healthy Engine while JS exits, and the new Rust manual-restart test requires that forbidden delayed fifth spawn."
}
```
