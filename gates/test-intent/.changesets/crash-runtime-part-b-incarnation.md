---
schema: test-intent.v1
task: t-20261019-91c3ab
author-role: builder
author-session: 01a187a2-833c-7a21-85d1-26b14d2c66aa
---

# Test-intent entries

The builder leaves the candidate uncommitted. The orchestrator generates execution evidence after committing and obtains independent acceptance. The existing three accepted changesets are unchanged. The existing Linux native-helper fixture is shared by the admission checks; no installed UI bundle is required. No options or production abstractions are added. The predecessor snapshot and wall-clock cutoff are removed; exact writer incarnation plus PID subsumes them.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "B10-1/B10-2/B10-3: shell manifest admission requires the exact supervised session and incarnation number plus PID. Another writer or an absent incarnation never supplies credentials; wall-clock rollback does not prevent the owned incarnation binding. Preserve predecessor evidence, same-PID rejection, and same-child republishing."
        }
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no leak across a requested scope",
        "reproduction": "On 11bcf0561c a competing incarnation with a reused PID, fresh instance ID and future startedAt binds port 42425 and competing-secret; an absent incarnation binds port 42427. An owned incarnation with startedAt before spawn fails to bind port 42426."
      },
      "reproduction": "The spawn closure deterministically replaces predecessor evidence after the predecessor snapshot. A separate closure publishes the owned incarnation with a pre-spawn timestamp, simulating clock rollback without changing the system clock. Both fail on 11bcf0561c by assertion; absent/malformed incarnation refusal also fails by assertion.",
      "evidence": "gates/test-intent/evidence/crash-runtime-part-b-incarnation.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::competing_incarnation_with_reused_pid_never_binds",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::own_incarnation_binds_after_wall_clock_steps_back",
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs#shell_actuator::commit_transport_tests::manifest_without_incarnation_never_binds"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_host.rs#cfg(test)",
        "modules/shell/src-tauri/src/engine_conn_test_support.rs",
        "modules/shell/src-tauri/src/session_credential_tests.rs",
        "modules/shell/src-tauri/src/shell_connection_tests.rs",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "signature"
      ],
      "whatMoved": "Direct manifest fixtures include the current host's session and incarnation number, as a real supervised Engine does. The engine_host manifest helper takes its host to encode that provenance. The test-only child removal follows the simplified discovery state without a predecessor snapshot. Existing PID, instance, credential, probe, event, shutdown, timeout and retry assertions are retained. The conformance fake publishes the same incarnation block from its existing JUSTSEARCH_ENGINE_INCARNATION environment.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18T21:15:41Z",
          "delegationScope": "the testing task and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "label": "agent choice",
          "choice": "B10-1/B10-2/B10-3: shell manifest admission requires the exact supervised session and incarnation number plus PID. Another writer or an absent incarnation never supplies credentials; wall-clock rollback does not prevent the owned incarnation binding. Preserve predecessor evidence, same-PID rejection, and same-child republishing."
        }
      ]
    }
  ]
}
```

## Acceptance records
