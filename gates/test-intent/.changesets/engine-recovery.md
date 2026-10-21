---
schema: test-intent.v1
task: t-20261020-51b072
author-role: builder
author-session: 01a1563a-7f02-7081-9d04-646e3ac0b504
---

# Test-intent entries

Owner adopted the E1–E21 scenarios on 2026-10-20. Entries below quote the relevant
scenarios verbatim. Engine-side queue scenarios E8–E15 belong to a later assignment.
Coverage is against the merge-base with origin/main, 2ba7d810e. There are thirteen flagged items;
separate entries distinguish new assertions from unchanged test formatting in the same item.
No acceptance record is written by the builder. Governance tooling is unchanged. The current
correction refreshes Java execution evidence for the expanded fatal-header checks; all existing
Rust/UI evidence and every appended acceptance record are preserved.

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

## CI follow-up after merging main (2026-10-21)

Public-claims job 118947729682 correctly found the new production write site recovery.rs
unclassified. The register now gives its JVM crash artifacts their own SHELL-owned derived
identity: the shell prepares the directory, selects output names and retains heaps; the JVM
writes the plaintext fatal logs/HPROF bytes. Existing fatal logs are preserved even if unreadable;
heap retention keeps the oldest/newest files without parsing them. Missing historical evidence
cannot be reconstructed, and future crashes produce independent UUID-named artifacts. This differs
from the existing rotating telemetry/JSON authority, so its corruption vocabulary states preservation
and retention rather than falsely claiming every damaged diagnostic is rotated away. The register
gate passes with 56 authorities; the merge base passes with 55 and the unmodified branch failed
specifically for recovery.rs. Product storage behavior is unchanged by this classification.

Windows job 118947729530's recovery-delayed-retry reached RUNNING and had two TRANSIENT exits,
then its third Windows job root missed the 599.9854 ms startup budget. In the manual/update-hold
case, the third incarnation first suffered the same short discovery allowance, was closed as a
hang, and the following job root missed its 599.9847 ms allowance. These are not exhaustion after
four real crash exits, so those cases correctly reject the missing retry deadline. The initial
CLI admission policy cannot explain them: first readiness has already occurred, and the default
shared supervisor's delayed retry has no prior-health condition.

The branch's own recovery driver overrode START_DEADLINE_MS to 600 for every replacement.
Removing that override restores the common conformance harness's 4000 ms startup policy; neither
restart allowance, quit deadline, fault timing nor existing assertion changes. The new out-of-scope
Node regression recovery-startup-budget.test.mjs inserts a controlled 900 ms root-publication delay
at the real third-spawn actuator boundary and runs both existing real-process recovery cases.
Both assertions fail on the shared a2ab1d99c before clone and pass after. Applying PR #842
(da6cb8180) in that disposable before clone still fails both: its 60-second fixture allowance is
capped by min(policy.startDeadlineMs, initialPortMs), leaving this 600 ms override in force.

On Linux, both ordinary recovery cases pass before and after this harness fix. The merge base
predates these new recovery cases; with only their test scaffolding overlaid, both reach four
TRANSIENT exits and fail the new retry-deadline assertion because main has no delayed recovery.
No product implementation or policy was overlaid. quit-deadline-is-the-contract-value passes on
both merge-base 2ba7d810e and the candidate; lifecycle.mjs is byte-identical between them. Its
Windows failure is the separate post-quit elapsed-time assertion (which includes exit-record work),
also reported on unrelated PR #841. The RUNNING quit deadline/force-margin calculation is unchanged.
PR #842 only extends pre-trigger startup observation and does not change that post-quit assertion.
Windows cannot be executed here; no Windows pass or fix of that shared failure is claimed.

Execution transcript: gates/test-intent/evidence/engine-recovery-ci-followup.txt. All Engines use
throwaway data/home folders and the drivers stop their own children; shared clones are removed.
The official execution-evidence runner refreshed all six existing Rust checks. Invoking it for
the new Node script exits 2 (no runner for scripts/); these scripts are explicitly outside product
test-intent scope, so the Node checks have a supplemental transcript, not fabricated D4 JSON.
Existing Java/UI and fail-before evidence stays valid for unchanged check-file digests. No Gradle
or Java change is needed. Acceptance records below are preserved and require a new independent
acceptance on the changed digest.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/diagnostics/JvmCrashEvidenceTest.java",
        "modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/README.md",
        "modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/internal.txt",
        "modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/native-oom.txt",
        "modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/realtime-signal.txt",
        "modules/app-services/src/test/resources/io/justsearch/app/services/diagnostics/hotspot-fatal-headers/sigsegv.txt"
      ],
      "class": "New",
      "behaviour": "E2: the newest JVM fatal log displaces an older crash JSON and reaches the public summary for all HotSpot header families (OS signals, Windows EXCEPTION_ names, Internal Error, native Out of Memory Error). Four redacted, actually generated Linux report headers exercise internal errors, native mmap OOM, SIGSEGV and SIGRTMIN+1. Five source-derived numeric-location/POSIX variants cover the no-filename fallback and unknown/realtime signal names. A new control keeps the 64-line/64KiB bounds and rejects malformed lookalikes. All three pre-existing checks are unchanged: Windows exception summary and path exclusion; the line bound and heap exclusion; export of only the newest three bounded, redacted text logs with no heaps. The whole class now executes 13 cases. On fa8f1d8d52f1 the five new internal/OOM/realtime assertions fail while the other eight pass; all 13 pass after. The bounds and safe classification spelling are delegated implementation choices, not owner-mandated constants or display text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E2",
          "quote": "The diagnostics export and the latest-crash summary find those JVM fatal logs (not only crash-*.json), without exporting heap dumps automatically.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "Read only the first 64 lines and redact paths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "E2: find the diagnostics export and the latest-crash summary (they currently look only at crash-*.json); make them include the JVM fatal-error logs in `<dataDir>/crashes/` (bounded: newest few, text only, no heap dumps).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "Keep the existing exception-type identifier allowlist: classify multiword HotSpot headers as InternalError or OutOfMemoryError and encode SIGRTMIN+<offset> as SIGRTMIN_<offset>.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-fatal-headers.json",
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
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "the shared supervisor behaviour stays identical in both (delayed retry after exhaustion, whether or not the Engine was healthy before), so revert the `!s.firstReadyDone` condition.",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "Read only the first 64 lines and redact paths.",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261020-51b072 request",
          "delegationScope": "implementation choices inside the agreed recovery scenarios E1-E21",
          "choice": "Decision: at Engine spawn preparation keep at most two heap dumps in `crashes/`, the oldest (first failure, E1) and the newest, and delete the others; fatal-error logs are small and stay.",
          "label": "agent choice"
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

## E2 HotSpot fatal-header correction (2026-10-21)

The independent acceptor's real AbortVMOnException report revealed that accepting only SIG/EXCEPTION_
headers omits HotSpot's Internal Error. Inspection of OpenJDK 25u VMError::report, POSIX signal naming,
and the Windows exception table establishes the complete header families. Internal Error and native
Out of Memory Error also have a hexadecimal-ID fallback when no source filename is available.
POSIX includes SIG<number> and SIGRTMIN+<offset>. There is no additional generic Error header family.
Source links and generation procedures accompany the redacted test-owned report excerpts.

Four fresh standalone JVMs generated the actual internal, native-OOM, SIGSEGV and real-time signal
reports; none was an Engine, each had java.io.tmpdir under TMPDIR, exited 1, and was reaped. Native
OOM was induced by a process-local 384 MiB address-space limit, without exhausting host memory.
Only headers before SUMMARY are retained; their absolute paths are redacted. Windows cannot run
here; the existing Windows exception fixture is unchanged. Numeric fallback variants are explicitly
source-derived, not claimed as real generated reports.

Entry 1 retains E2 as its primary source. Its added delegated detail is: "Keep the existing
exception-type identifier allowlist: classify multiword HotSpot headers as InternalError or
OutOfMemoryError and encode SIGRTMIN+<offset> as SIGRTMIN_<offset>." No arbitrary exception message,
source location or problematic frame enters the public summary. The parser still reads at most
64 KiB and 64 lines; public summary/value limits and diagnostics export are unchanged. The three
existing test methods and assertions are unchanged. The new test-owned excerpts/provenance are
covered by the same entry and named whole-class check.

The manually built before state is a shared detached clone of fa8f1d8d52f1 under TMPDIR, with only
the candidate checks and their redacted resources overlaid: 13 cases run, five fail by assertion
(two missing internal/OOM real headers, one realtime public classification, two numeric fallback
headers), eight pass. Candidate diagnostics selection: 34 cases pass, none skipped. Official D4
execution evidence is refreshed with the unmodified evidence runner in a disposable shared clone;
its temporary before checkout writes no registration into the source repository. The runner is
held under js-gradle-slot for its complete Gradle execution. All older evidence files and every
acceptance/rejection record are preserved; a new independent acceptance is required for this digest.

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1587a-97ec-7c50-97f4-8cf4a4aa7694",
  "verdict": "reject",
  "digest": "sha256:03df668be031c302c7d9ea36f84d1a900837e34ab68a2c8a177222aa0945a24b",
  "note": "Behavioral checks pass, but the brief omits independently verifiable sources for E1/E2/E3/E5/E6/E16/E19/E20/E21 and the redaction and heap-retention decisions."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15887-dba4-7ca2-8a4e-922ca5ba9319",
  "verdict": "accept",
  "digest": "sha256:6cf6736e3dd607a3232f92536c5b510be1687172ae1dad6f7910a682a6002832",
  "note": "The entries match agreed intent and scoped delegation, with valid evidence and independently reproduced retry failures before and passes after."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15c58-a359-71e1-98cd-6f2186363ddd",
  "verdict": "reject",
  "digest": "sha256:1325779400c554492817ac2ecbd357bea6b39303a77ba7c41ee6f2ded86576a9",
  "note": "E2 remains incomplete: a real JVM Internal Error fatal report is omitted by the public diagnostic summary on the exact candidate, and the new diagnostics tests do not cover it."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15c6f-883e-7583-b133-70db91551f7b",
  "verdict": "accept",
  "digest": "sha256:f01ee97d68037165fc6e86198518ea9f1f2244066d72148029668b07ad250bd8",
  "note": "All twelve entry classifications and delegated choices are supported; independent JVM parent/candidate checks, real SIGBUS summary, Linux Engine artifacts, browser recovery and both supervisor conformance suites confirm the cited behavior within this PR's scope."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15d55-b897-75b2-84c9-64f376624c97",
  "verdict": "accept",
  "digest": "sha256:161cbfb992aa4a10286deba1544f2c7b66491befc71f8232e72a0c88b9839e90",
  "note": "All twelve entries follow scoped owner intent and preserve existing oracles; JVM before failures are assertions, candidate and HEAD~1 pass 13 cases, real SIGBUS summary passes, retention fails before and passes after, both supervisor suites pass, and register semantics match the code."
}
```
