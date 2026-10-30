---
schema: test-intent.v1
task: t-20261027-4e4d6b
author-role: builder
author-session: codex-builder-w2b-compat-predecessors
---

# Test-intent entries

W2b adds coverage for descriptors produced from the published v0.3.0 register.
The existing current-register fixture and its assertions remain in place; the shared fixture
helper gains an explicit published-baseline mode. The published register fixture is retained
as independently verified. Acceptance belongs to the independent verifier.

The independent verifier reported that the full-current-register and earlier-current-subset
checks fail on cadeb18d53 for missing owner coverage and pass on 443c0752cf. The published-register
and unreadable-format preservation checks pass on both. The attached script-generated evidence
records execution of all four checks on 443c0752cf in a temporary shared clone with its own build
directory and temporary resources; it does not replace that independent before-state check.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs"
      ],
      "class": "New",
      "behaviour": "Compatibility must cover the published v0.3.0 register and current-era predecessors while refusing unknown or unreadable stores. For both windows-x86_64 and linux-x86_64, metadata-signed bytes generated against the published register must cover the full current register, including data-version-marker, and an earlier current-era subset containing only that owner. Those same bytes must remain compatible with the published register's 42 durable stores, including the retired worker-config-snapshot owner. Raising a predecessor store's current version above every readable source version must still fail with the cannot-read-durable-store error. Existing checks continue to reject missing owner coverage and incompatible store formats; their expectations are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b user turn 2026-10-27",
          "delegationScope": "implement the release plan W0-W8",
          "choice": "Compatibility must cover the published register and current-era predecessors, refusing unknown or unreadable stores.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/w2b-compat-predecessors.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_full_current_register",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_earlier_current_subset",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_published_register_including_retirement",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_refuse_unreadable_predecessor_format"
      ]
    }
  ]
}
```

## Acceptance records
