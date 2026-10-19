---
schema: test-intent.v1
task: ci-critical-path
author-role: builder
author-session: 01a1520d-5a3b-7373-9834-7dd1b5fb7221
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml",
        "scripts/ci/unit-test-shard-policy.v1.json"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "The existing app-ui module tasks and Windows-native Node, conformance, JVM and release checks move to parallel jobs. Inputs, assertions, per-test budgets, retries, platform selection and conformance repetitions are unchanged within this assignment. Required check names become aggregates requiring success from both expected parts, including failure/skip/cancellation propagation. Evidence gains part and execution-attempt attribution. CP2 raw and stable-identity comparison of reference run 39182583158 and hosted candidate run 39193826064 is recorded below; its sole physical-count discrepancy is a recovered reference retry and remains unaccepted.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Nothing may be weakened to save time.",
          "location": "task ci-critical-path request"
        }
      ]
    }
  ]
}
```

CP2 execution evidence: [reference run 39182583158](https://github.com/justsearch-app/justsearch/actions/runs/39182583158) versus [uncached candidate run 39193826064](https://github.com/justsearch-app/justsearch/actions/runs/39193826064), both attempt 1.

The generated comparison is saved in `tmp/cp/identity-cold.json`; its executable is `tmp/cp/compare-execution-identities.py`. The before/after artifacts and logs are retained in `tmp/cp/before`, `tmp/cp/before-logs`, and `tmp/cp/cold`. The comparison covers module/class/testcase identities, every occurrence, skipped cases, Windows Node discovery and probe output, conformance repetitions, ancillary script invocations, and Rust case names. Governance-script regression tests are checked separately.

Both runs contain 2,429 JUnit XML files and 164 skips. The comparator retains 16,094 reference occurrences and 16,093 candidate occurrences, exiting 1. The only stable-identity count difference is `EngineSupervisedRecoveryE2ETest.initialBootstrapSurvivesHostileLocks()`: the reference artifact records a recovered retry (two attempts); the candidate passed once. No counts are discarded to produce a pass. `tmp/cp/retry-difference-evidence.json` records the original retry report.

Raw parameterized display names are retained alongside stable invocation keys for two providers whose Object identity hashes / Map iteration text vary. Their test sources are unchanged from the reference; `tmp/cp/identity-normalization-proof.json` records their hashes. All other stable identities, multiplicities, skips and script invocations match. CP2's treatment of physical retry variation is reserved for the orchestrator and independent verifier.

Comparison SHA-256: `aeaab9926a0a96eb148401697bccf23c091793d16dcb3ca5c8703976f1ea8959`. Full scenario/run ledger: `tmp/cp/final-evidence.json`.

No acceptance record is authored here.

## Acceptance records
