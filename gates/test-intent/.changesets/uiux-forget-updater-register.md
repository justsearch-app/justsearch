---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: orchestrator
author-session: 8c330fb4-0ab8-439d-8de2-6a42f0669e2b
---

# Forget: updater compatibility test follows the ui-settings v6 register

Forget moves the ui-settings store to format v6 (reads v0-v5 unchanged) in
`governance/store-recoverability.v1.json`. The updater test added on main by #993 reads that
register and asserted the v5 it held then. The source quotes the task request; the independent
acceptor verifies it.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": ["rename", "input re-encoding"],
      "whatMoved": "Only ui_settings_v5_successor_reads_legacy_v1_without_changing_strategy_identity changes, renamed to ui_settings_v6_successor_reads_legacy_v1_without_changing_strategy_identity. Its input is the checked-in store register (LOCAL_STORE_REGISTER, include_str of governance/store-recoverability.v1.json), whose ui-settings currentVersion moves 5 -> 6 in this branch. The two fixture precondition asserts follow it (current_version 6, successor format_version 6), the successor's readable_source_versions gains the new current version (0..6, still including the installed v1), and the deliberately wrong strategy token is spelled for the new version (READ_V0_TO_V6_AND_WRITE_V6). Kept: the installed register is set to v1, the frozen reconciliation token READ_V0_OR_V1_AND_WRITE_V1 is asserted unchanged, the matching successor is accepted by validate_store_compatibility_against, and the renamed strategy is rejected with 'ownership or recovery strategy'. No production code changes.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "location": "task t-20261019-bd718f request (2026-10-19)"
        }
      ]
    }
  ]
}
```

## Acceptance records
