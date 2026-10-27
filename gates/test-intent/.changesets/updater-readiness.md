---
schema: test-intent.v1
task: updater-v2-round2
author-role: builder
author-session: codex-builder-round2
---

# Test-intent entries

The independent verifier appends acceptance after reviewing this candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An authenticated Engine returning two startup 503 responses and then a valid reconciliation attestation commits the durable intent; startup retries do not accept an invalid attempt attestation; persistent 503 responses give up after 60 seconds and persist repair-required. Tests use a contained child HTTP server with the production socket ownership/session proof, response validation, outcome transition, durable write and status projection.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix the reconciliation race for both platforms: the target-side confirmation waits for, or retries until, the Engine can answer (bounded, with a clear give-up that keeps today's repair-required outcome when the Engine never becomes ready). Never treat a 503 as success; a real reconciliation refusal (not \"not ready yet\") must still end in repair-required.",
          "location": "task updater-v2-round2 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-readiness-repair.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::reconciliation_retries_not_ready_then_commits_verified_response",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::reconciliation_retry_still_rejects_invalid_attestation",
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::reconciliation_never_ready_times_out_into_repair"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs"
      ],
      "class": "New",
      "behaviour": "A genuine HTTP 409 reconciliation refusal immediately persists repair-required, without retrying or committing.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix the reconciliation race for both platforms: the target-side confirmation waits for, or retries until, the Engine can answer (bounded, with a clear give-up that keeps today's repair-required outcome when the Engine never becomes ready). Never treat a 503 as success; a real reconciliation refusal (not \"not ready yet\") must still end in repair-required.",
          "location": "task updater-v2-round2 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-readiness-refusal.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::reconciliation_refusal_requires_repair_without_retry"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/EngineRecovery.update.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "During a pending install, poll host status without overlapping polls so the existing recovery component shows Updating JustSearch while install_launching persists. Stop polling and refresh final status on success and cancellation; preserve the cancellation error.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "If the UI already has an installing/updating state, show it while the Linux install step runs (including the lock wait); do not design new UI.",
          "location": "task updater-v2-round2 request"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-readiness-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/EngineRecovery.update.test.ts"
      ]
    }
  ]
}
```

## Acceptance records
