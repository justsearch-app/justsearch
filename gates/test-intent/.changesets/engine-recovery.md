---
schema: test-intent.v1
task: t-20261020-51b072
author-role: builder
author-session: 01a1563a-7f02-7081-9d04-646e3ac0b504
---

# Test-intent entries

Owner adopted the E1–E21 scenarios on 2026-10-20. Entries below quote the relevant
scenarios verbatim. Engine-side queue scenarios E8–E15 belong to a later assignment.
Coverage is against the merge-base with origin/main, 738e6a443. There are thirteen flagged items;
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
      "evidence": "gates/test-intent/evidence/engine-recovery-jvm-folder-headers.json",
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
      "behaviour": "The three original recovery-data checks preserve spawn-obstacle reporting (E6), the bounded header/Windows-path redaction summary (E1/E3), and the ledger-based 24-hour count across budget reset/relaunch (E19). Their assertions are unchanged. Native-OOM recognition and newest-report selection are now repaired in both readers and covered by the separate repaired-header entry, shared Java real-report fixtures and a common published-state conformance case (E2/E3/E17).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E2",
          "quote": "The diagnostics export and the latest-crash summary find those JVM fatal logs (not only crash-*.json), without exporting heap dumps automatically.",
          "label": "agent-drafted, owner-adopted"
        },
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
      "evidence": "gates/test-intent/evidence/engine-recovery-summary-preserved.json",
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
      "evidence": "gates/test-intent/evidence/engine-recovery-heap-retention-headers.json",
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
      "evidence": "gates/test-intent/evidence/engine-recovery-merge-839-updater.json",
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
      "behaviour": "Four added tests cover restart_engine invocation and refused-update reason with usable controls (E3/E5); Java spawn obstacle, retained native frame, crash-folder reveal with spaces and daily restart count (E1/E3/E6/E19); running-but-not-ready cause even when the API responds (E20); and locked-profile explanation with no automatic retry (E7). The existing terminal-recovery assertion changes from \"Try connecting again\" to \"Restart JustSearch\", as E3 requires. Other existing expectations stay. Whole-file execution covers all thirteen cases after the #821/#851 merge. The two administrator-refusal cases from main and every existing branch test are retained byte-for-byte; these are preservation checks. Recovery assertions fail on current main 4f4e20514 and all thirteen cases pass on the merged candidate.",
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
      "evidence": "gates/test-intent/evidence/engine-recovery-merge-821-851.json",
      "newChecks": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/desktopRecovery.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Main added a factory_reset_status IPC stub that deliberately returns idle without consuming the command spy. Its fallback forwarded the command name but dropped all other arguments, hiding the existing crash-folder reveal payload from the spy. The fallback now forwards the original argument list. No input, observation assertion, condition, timeout or expected value changes. The old and new assertion are identical: expect(mocks.invoke).toHaveBeenCalledWith('reveal_in_explorer', { path: 'C:/Users/Name With Spaces/data/crashes' }); The unmodified merge fails that assertion; the transparent proxy passes it. This restores instrumentation rather than weakening the assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E1",
          "quote": "Fresh profile with no crashes folder (including a Windows path with spaces): a native crash of the Engine leaves its JVM fatal-error log in `<dataDir>/crashes/`, and a Java heap OOM leaves the artifact the Engine's JVM flags promise for OOM (named in the change), on Windows and Linux. After repeated crashes, exhaustion and an app relaunch, the first failure's evidence is still there and attributable.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/recovery.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "Three repaired-header checks: all four real HotSpot report fixtures already shared with Java participate in recovery data (Internal Error, native Out of Memory Error, SIGSEGV and SIGRTMIN+1); a newer native-OOM report without a problematic frame displaces an older SIGILL while newer bounded-out text and heap dumps are ignored; native-OOM source paths with spaces are redacted and the 64KiB bound remains. The 64-line/256-character controls are kept. All three fail by assertion on ad2b22ae5 and pass after. JS exercises the same shared fixtures with equivalent direct tests, and recovery-newest-fatal-report drives the real published supervisor state in both hosts: both show the older SIGILL before and the newer OOM after. Script checks are outside the product gate by design and have actual before/after transcripts rather than fabricated D4 records.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E2",
          "quote": "The diagnostics export and the latest-crash summary find those JVM fatal logs (not only crash-*.json), without exporting heap dumps automatically.",
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
          "scenario": "E17",
          "quote": "The Rust shell supervisor and the JS supervisor (dev runner, conformance) behave the same for exhaustion, delayed retry, manual restart, hang detection, stability-budget reset and shutdown (conformance cases in both).",
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
      "evidence": "gates/test-intent/evidence/engine-recovery-fatal-headers-rust.json",
      "newChecks": [
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::fatal_summary_recognizes_shared_real_hotspot_headers",
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::newest_fatal_summary_prefers_native_oom_over_older_signal",
        "modules/shell/src-tauri/src/recovery.rs#recovery::tests::native_oom_summary_redacts_source_paths_and_keeps_the_byte_bound"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Reconcile the real Chromium/SES boot checks with E3: the old RN3 test used an exhausted supervisor and expected Try connecting again to reload. Its reload and both event-reload assertions stay, with the nonterminal starting precondition where that action still applies. A new terminal check requires the visible Restart JustSearch action to invoke restart_engine exactly once without navigation, retain recovery until the host announces running, and then reload into the mounted shell with the new API binding. This catches a real callback defect: the composed button retained the initial nonterminal reload closure after its label became Restart JustSearch. The callback now reads the current supervisor at activation. On merged parent 2a64b161f the new test fails by assertion (zero restart_engine calls and a navigation); with the fix all five boot checks pass. No existing timeout or assertion is weakened or deleted. Factory-reset gating and ordinary browser/desktop boot remain covered.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-51b072",
          "scenario": "E3",
          "quote": "Desktop, restart budget spent: the recovery screen shows the cause (exit reason, count, problematic frame when known) and a restart action; pressing it brings the Engine back to READY without quitting the app, and the UI reconnects to the new instance's port and token, ignoring stale manifests and late probe results.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/engine-recovery-lockdown-restart.json",
      "newChecks": [
        "modules/ui-web/src/boot/boot-lockdown.test.ts"
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

## Merge of main's app-update switch (2026-10-21)

Merge commit e333bcdf5 has parents 4e918cce5 and origin/main f6d92d768, without rebasing.
The updater conflict keeps both main authorization checks (including reauthorization just before
artifact download), enters downloading only after authorization, and retains the recovery branch's
checked_installer_bytes path that publishes actionable error state on download/validation failure.
The boot-test conflict is split into independent test groups: main's two administrator-refusal
cases are copied verbatim; removing that copied group leaves the first parent's whole test file
byte-identical. Every pre-existing recovery expectation and crash-artifact implementation is kept.
No oracle is weakened or deleted and no new test case is introduced by this merge resolution.

Entries 10 and 12 now use the unmodified execution-evidence runner's fresh combined artifact,
engine-recovery-merge-856.json. The updater recovery check and all 13 boot cases pass on the merged
candidate. Against f6d92d768, the updater assertion observes downloading instead of error, and the
recovery UI assertions fail for the missing restart/crash/not-ready behavior. Main's administrator
refusal cases stay green before and after. The unchanged updater compatibility patch only extracts
the predecessor's inline download acceptance into a callable helper; it adds no error transition.
The runner executes in a disposable shared clone under TMPDIR and writes no worktree registration
into the source repository. No Gradle invocation is needed for these Rust/Vitest checks.

The full shell suite reproduces exactly the seven user-excluded updater_probe::handoff_tests
failures; they are baseline failure, unchanged. Those fixture files are byte-identical to main.
Filtering only the seven named failures leaves the remaining library and binary suite green.
The six related Vitest files pass all 29 cases; store-recoverability's test gate passes 85
assertions and its register gate recognizes 56 durable authorities. Clippy succeeds with warnings.
Conformance passes the self-test 35/35, dev-runner adapter 50/50 and freshly built Tauri adapter
60/60, including exit-2 exhaustion, delayed/manual recovery, update holds and quit. The final gate
covers all 13 flagged items and requires only fresh independent acceptance; its nonblocking audit
warning reports third-reviewer tie-breaks on 1 of 8 audited PRs (12.5%).
Every older evidence artifact and every existing acceptance/rejection record is preserved.
The merge is the only commit; this changeset and its new execution evidence remain uncommitted.

## Revalidation after #821 and #851 (2026-10-21)

The owner-provided merge is 79b120f72; current main is 4f4e20514. Both mod recovery and
mod search_shortcut are present. The shell builds without a production change. Factory reset,
search shortcut and quit modules are byte-identical to main. The production reset gate precedes
shortcut/updater initialization and Engine supervision; retry/cancel/storage completion use
AppHandle::request_restart, whose installed Tauri implementation delivers RunEvent::Exit. That
handler releases the native shortcut before the existing permanent quit/owned-child cleanup path.
Normal crash retries do not arm a reset, and the boot reset gate precedes Engine recovery UI.

One actual integration-test failure occurred on the unmodified merge: the factory-reset idle
stub discarded reveal_in_explorer's path argument. The fallback now forwards all original IPC
arguments, while keeping reset-status handling isolated from the spy. Entry 13 records this
instrumentation adaptation. Every existing test assertion is byte-identical; no test is disabled,
weakened or deleted. Before: 64/65 cases pass across 11 files, with the crash-folder reveal assertion
failing. After: 127/127 pass across 16 related recovery/reset/shortcut/update/indexing-readiness files.

The full candidate Rust run passes all 221 library tests, including both private-Xvfb native
shortcut probes and reset/startup integration tests. Current main passes 213 library tests. Both
then fail the exact same seven updater_probe::handoff_tests; after normalizing only libtest thread
PIDs, each panic body is identical. They are baseline failure, unchanged. The candidate passes its
complete remaining suite when only those seven named failures are filtered. The first main-clone
attempt lacked the ignored installed bound-exec fixture and is not treated as product evidence;
copying the unchanged installed helper supplies the same fixture as the candidate, then the real
comparison above is rerun. No before-state production code is replaced.

Conformance passes self-test 35/35, dev-runner 50/50 and Tauri 60/60, including exhaustion, delayed
and manual restart, update holds, manifest replacement and quit. Entries 10 and 12 use the official
runner's new engine-recovery-merge-821-851.json: the updater error-state check and all 13 boot cases
pass after, with assertion failures on current main before. Its compatibility patch still only
extracts the predecessor's inline byte acceptance. All older evidence and every acceptance record
are retained. Supplemental execution/comparison details are in engine-recovery-merge-821-851.txt.
A temporary cross-feature probe also passes against the real filesystem and production functions:
normal JVM spawn preparation keeps existing fatal/heap artifacts; explicit reset deletes them and
the shortcut choice; awaiting window-storage cleanup blocks spawn preparation; completed reset
allows a fresh empty crashes folder while retaining models and native-bin/llama-server. Its first
fixture mistakenly put a retained sentinel at an arbitrary native-bin path (correctly deleted by
main); the corrected fixture uses the documented runtime location. It is scaffolding only in the
shared clone, with source/result recorded in the supplemental transcript; no product test is added.
No Java code changes or Gradle calls are needed; only the shell and related UI tests are run.

## Rust/JS native-OOM summary repair after the R2l rejection (2026-10-21)

The appended, uncommitted rejection of entry 8 is retained verbatim with every earlier record.
Rust/JS already recognized signals, Windows EXCEPTION_ headers and Internal Error; both omitted
Out of Memory Error. A native-OOM report commonly has no problematic frame, so it was skipped and
an older signal could be selected. Both readers now include that fourth HotSpot header family.
Native-OOM source locations are redacted exactly like Internal Error, including Windows paths
with spaces. Existing 64KiB/64-line reads and 256-character displays are unchanged; heaps are still
excluded. Existing mtime ordering is unchanged; accepting the valid newest report fixes selection.

Both unit suites read the existing Java real-report fixtures directly; no fixture is copied into
a second source tree. Three new Rust checks fail by assertion on ad2b22ae5 and pass after. The JS
suite fails three native-OOM assertions before and passes 19/19 after. The new common conformance
case recovery-newest-fatal-report seeds older SIGILL/newer real native-OOM evidence before starting
a real fixture Engine and checks crashFile in the published supervisor state. It fails on both
baseline hosts for choosing the older file and passes in both corrected hosts. Full conformance
passes self-test 35/35, dev-runner 51/51 and Tauri 61/61; related Vitest passes 127/127 in 16 files.

The full candidate Rust suite passes 224 library tests and then reproduces the same seven known
updater_probe::handoff_tests failures as current main 947be754d. Filtering only those seven leaves
the complete remaining suite green. Main is a shared detached clone with the unchanged installed
bound-exec prerequisite copied into its ignored distribution path. No main production code is
patched for that comparison. The exact failure comparison and Node/boundary outputs are in
engine-recovery-fatal-headers.txt. Only Rust, Node and related UI checks run; no Gradle is needed.

The unmodified evidence runner writes the new repaired-header artifact against ad2b22ae5. Since
its source digest covers the whole recovery.rs file, it also refreshes entry 8's three unchanged
checks and the existing folder/heap repair proofs against their original before states. Those
pre-existing test bodies/assertions and every older artifact are retained. Entry 14 records the
new repaired-header assertions; entry 8 keeps its original API checks and adds E2 as a source.
No acceptance record is written by the builder, and no commit is made.

## CI run 39601040562: merge and real-lockdown restart repair (2026-10-21)

Merged origin/main 738e6a443 into 3b0c4a161 as 2a64b161f, the only commit in this follow-up.
Main's TLS-provider initialization and its updater tests remain intact. Entry 10 has fresh
execution evidence against that main revision; the unchanged predecessor-byte acceptance is
exposed by the existing compatibility patch, and fails its transport-error state assertion.
Entry 12 and all other valid evidence artifacts are retained.

The CI Frontend lockdown tests failure was RN3 Engine recovery Retry and both desktop event
reloads mount under real lockdown (30-second timeout looking for Try connecting again).
The original suite passes 14/14 on main and fails 1/14 on the merged branch. E3 deliberately
changes the exhausted action to native restart. Entry 15 records the split between nonterminal
reload preservation and terminal native restart, and the callback repair its real-browser check
exposed. The unmodified evidence runner records that new assertion failing on the merged parent
and the complete candidate lockdown file passing. Supplemental command outcomes, including the
main comparison and Windows startup-budget analysis, are in
`gates/test-intent/evidence/engine-recovery-ci-39601040562.txt`.

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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15dac-78fd-7103-96c0-ad9d503e7759",
  "verdict": "accept",
  "digest": "sha256:413750e658b6796888da17581208078eff59cc21d7e99f1121849bf7b3d2e0e0",
  "note": "All twelve test-intent entries follow the agreed scenarios and scoped delegation; independent before/after checks, real JVM evidence, merge preservation and register review found no defect."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15e12-6c62-7e33-b41b-66220c7a78e8",
  "verdict": "reject",
  "digest": "sha256:494ea3d72213106743679e87dc4fe8b950bb0a3cfbc3f26ef14ebedada025b49",
  "note": "Entry 8 leaves fatal-crash recovery evidence incomplete: the running Rust shell and JS recovery summaries omit a real native-OOM fatal report and can present an older crash instead."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a15e65-043d-7571-a951-0ccf9f3d6f17",
  "verdict": "accept",
  "digest": "sha256:a7486e4e311284b656d585e7a88b67dd9906a4a31297e218efef2f5579527d37",
  "note": "All fourteen entries follow scoped owner intent; independent JVM and real fatal-report supervisor checks pass, with seven identical main harness failures and explicit platform limits."
}
```
