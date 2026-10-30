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

The repaired full-current-register and earlier-current-subset checks have separate evidence
showing missing owner assertion failures on cadeb18d53 and passing execution on the candidate.
The before-state overlays contain only the published-mode fixture helper and the
published v0.3.0 register; the old producer remains under test. The published-register and
unreadable-format preservation checks have separate script-generated passing evidence.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs"
      ],
      "class": "New",
      "behaviour": "Compatibility must cover the published v0.3.0 register and current-era predecessors. For both windows-x86_64 and linux-x86_64, metadata-signed bytes generated against the published register must cover the full current register, including data-version-marker, and an earlier current-era subset containing only that owner.",
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
      "evidence": "gates/test-intent/evidence/w2b-compat-repaired.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_full_current_register",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_earlier_current_subset"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs"
      ],
      "class": "New",
      "behaviour": "For both windows-x86_64 and linux-x86_64, metadata-signed bytes generated against the published v0.3.0 register must remain compatible with that register's 42 durable stores, including the retired worker-config-snapshot owner. Raising a predecessor store's current version above every readable source version must still fail with the cannot-read-durable-store error. Existing checks continue to reject missing owner coverage and incompatible store formats; their expectations are unchanged.",
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
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_cover_published_register_including_retirement",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::published_baseline_producer_bytes_refuse_unreadable_predecessor_format"
      ]
    }
  ]
}
```

## Acceptance records
