---
schema: test-intent.v1
task: t-20261111-winliveness
author-role: builder
author-session: ce52d70e-890a-4092-9a94-1fcdd1ca4acb
---

# Test-intent entries

New Windows-only checks in `shell_actuator::windows_identity_tests` for the liveness of a recorded
Engine process that has ended while a handle to it is still held. Evidence is produced on Windows CI
by run-evidence.mjs; the repaired checks run against 9868861448 with
`gates/test-intent/evidence/winliveness-before-api.patch`, which adds only the pure exit step the
transplanted tests call and leaves the before-state's liveness check unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/shell_actuator.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "L1/L2/L4 (repaired): on Windows, a recorded Engine that has ended reads GONE although another process still holds a handle to it, for exit codes 0, 7 and 259 (STILL_ACTIVE). A successor shell started after such an Engine ended records engine-owner-died with osCheck GONE, exitClass OWNER_DIED and basis OPEN_START. A lock holder recovered from the ledger while alive, and one saved by record_exit, report lock_holder_gone once the process has ended. At 9868861448 each of these read ALIVE_MATCH, so the checks fail there by assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with investigating Windows start and restart failures. i dont want you to spend your tokens too much on implementation, but save that for opus models.\nEach product change is above the floor and gets its own task record, a separate builder, and verification on the real system. For L, and for the Windows half of M, the real system is Windows CI; this machine cannot run it.",
          "delegationLocation": "task t-20261111-winliveness request",
          "delegationScope": "Technical choices in the Windows liveness repair of task t-20261111-winliveness: the orchestrator's settled scenario list (L1-L5, P1-P8) and design for how the shell decides whether a recorded Engine process is still alive on Windows. The scenarios are orchestrator choices under the delegation, not owner words or owner adoption.",
          "choice": "Ended Engine processes are judged by a zero-timeout wait on one process handle opened with query-limited plus synchronize access; exit for any code means GONE, before identity is compared. Settled scenarios, verbatim: L1 (deliver): \"An Engine that has ended before the next shell starts, whose process can still be opened because another process holds a handle to it, is recorded GONE / OWNER_DIED before the successor's engine-start, and the successor reaches running. Checked with a deterministic exited-process fixture that retains a handle, and with the existing Windows lifecycle case. Fails before the change.\" L2 (deliver): \"Exit is recognised for any exit code, including 0, a nonzero code and 259.\" L4 (deliver): \"A predecessor first observed alive that then ends lets the waiting successor take the existing early retry (lock_holder_gone), both for a holder recovered from the ledger and for one saved by record_exit.\"",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/winliveness-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::ended_process_is_gone_for_every_exit_code_while_a_handle_is_held",
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::engine_ended_before_the_next_shell_is_recorded_owner_died",
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::ledger_lock_holder_is_gone_once_it_ends",
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::record_exit_lock_holder_is_gone_once_it_ends"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_actuator.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "L3/L5 (new) and P1/P2/P3 (preservation): an exit observed by the wait answers GONE without reading identity; a failed wait or a failed creation-time query is IDENTITY_UNKNOWN; a live child with matching creation time stays ALIVE_MATCH and is not ended by the check; a different saved creation time is GONE; a live child without saved birth is IDENTITY_UNKNOWN; a nonexistent PID is GONE.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with investigating Windows start and restart failures. i dont want you to spend your tokens too much on implementation, but save that for opus models.\nEach product change is above the floor and gets its own task record, a separate builder, and verification on the real system. For L, and for the Windows half of M, the real system is Windows CI; this machine cannot run it.",
          "delegationLocation": "task t-20261111-winliveness request",
          "delegationScope": "Technical choices in the Windows liveness repair of task t-20261111-winliveness: the orchestrator's settled scenario list (L1-L5, P1-P8) and design for how the shell decides whether a recorded Engine process is still alive on Windows. The scenarios are orchestrator choices under the delegation, not owner words or owner adoption.",
          "choice": "Exit is decided first on the opened handle; only a process that has not exited is compared by creation time read from that same handle; a failing open (other than a nonexistent PID), wait or query is IDENTITY_UNKNOWN. Settled scenarios, verbatim: L3 (deliver): \"Identity and liveness are read from the same opened process, and an exit that is already observable when liveness is checked cannot produce ALIVE_MATCH. (An exit after the check is not covered; L4 covers the later re-check.)\" L5 (deliver): \"The new liveness query itself failing (the wait or exit query returns an error) yields IDENTITY_UNKNOWN, neither GONE nor ALIVE_MATCH.\" P1 (must not break): \"A genuinely live predecessor with matching creation identity stays ALIVE_MATCH / UNKNOWN and is never killed by reconciliation.\" P2 (must not break): \"A PID that now belongs to a different creation identity means the recorded Engine is GONE; the other process survives.\" P3 (must not break): \"A nonexistent PID is GONE. Where birth or query information is unavailable and nothing independently establishes death or an identity mismatch (access denied; a live process whose saved birth is missing), the result stays IDENTITY_UNKNOWN.\"",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/winliveness-new.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::exit_is_decided_before_identity_and_a_failed_query_is_unknown",
        "modules/shell/src-tauri/src/shell_actuator.rs#shell_actuator::windows_identity_tests::live_and_nonexistent_processes_keep_their_verdicts"
      ]
    }
  ]
}
```

## Acceptance records
