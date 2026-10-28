---
schema: test-intent.v1
task: updater-v2-round2
author-role: builder
author-session: codex-builder-round2
---

# Test-intent entries

The independent verifier appends acceptance after reviewing this candidate. The component-only UI check was replaced by the boot-path check in updater-round3.md; it did not establish the running application presentation.

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
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Fix the reconciliation race for both platforms: the target-side confirmation waits for, or retries until, the Engine can answer (bounded, with a clear give-up that keeps today's repair-required outcome when the Engine never becomes ready). Never treat a 503 as success; a real reconciliation refusal (not \"not ready yet\") must still end in repair-required.",
          "label": "agent choice"
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
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Fix the reconciliation race for both platforms: the target-side confirmation waits for, or retries until, the Engine can answer (bounded, with a clear give-up that keeps today's repair-required outcome when the Engine never becomes ready). Never treat a 503 as success; a real reconciliation refusal (not \"not ready yet\") must still end in repair-required.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-readiness-refusal.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_reconciliation_tests.rs#updater::reconciliation_tests::reconciliation_refusal_requires_repair_without_retry"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "claude-verifier-60x_8huq",
  "verdict": "accept",
  "digest": "sha256:a48c7b3ed64cebdcaae3062400aade7e4f6970358f7f92de63063ea6983f6f46",
  "note": "Verifier of d0895c8587. New/repaired reconciliation checks: evidence shows assertion fail-before on the patched baseline (503 -> RepairRequired) and pass after; refusal check is new behaviour. On the VM the relaunched 0.4.1 reconciled to COMMITTED. Delegation quote not checked against the task record (not available to the verifier)."
}
```
