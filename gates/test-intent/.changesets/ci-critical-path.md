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
      "whatMoved": "The existing app-ui module tasks and Windows-native Node, conformance, JVM and release checks move to parallel jobs. Inputs, assertions, per-test budgets, retries, platform selection and conformance repetitions are unchanged within this assignment. Required check names become aggregates requiring success from both expected parts, including failure/skip/cancellation propagation. Evidence gains part and execution-attempt attribution. After rebasing onto main including #766 and #773, the moved Windows Node step retains the ui-web npm install and its Vite-loopback rationale. The later rebase keeps #775's engine-discovery probe once in Linux public-claims (before the still-blocking test-intent gate) and once in Windows-native-contracts after its existing root dependency install, so SDK-dependent cases execute; main's actions versions are retained. Header-only Windows transcripts and their artifact paths are removed; Node output remains in the Actions job log and the contracts artifact retains real JUnit XML and HTML reports. CP2 raw and stable-identity comparison of reference run 39182583158 and hosted candidate run 39193826064 is recorded below; its sole physical-count discrepancy is a recovered reference retry, counted as one execution under the orchestrator decision and reported separately.",
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

Both runs contain 2,429 JUnit XML files and 164 skips. Under the orchestrator’s retry rule, both contain 16,093 logical execution occurrences; the comparator exits 0 with no identity, multiplicity, skip or script-selection differences. The reference records two physical attempts of `EngineSupervisedRecoveryE2ETest.initialBootstrapSurvivesHostileLocks()`, classified separately as `passed after retry`; the candidate passed once. Classification requires the failed then passing JUnit entries and matching ordered Gradle FAILED → PASSED outcome markers scoped to the same module/task/test path (reference log lines 2922 and 2959), not duplicate counts alone. The report retains all physical attempts (15,515 reference JUnit entries versus 15,514 candidate entries) and raw display differences. Duplicate passing entries, parameterized/inherited/repeated invocations, and conformance repetitions retain their multiplicities.

Raw parameterized display names are retained alongside stable invocation keys for two providers whose Object identity hashes / Map iteration text vary. Their test sources are unchanged from the reference; `tmp/cp/identity-normalization-proof.json` records their hashes. All stable identities, multiplicities, skips and script invocations match. A synthetic after dataset removes the non-retried `PassageRetrievalIntegrationTest.chunkAwareDoesNotDegradeRecall()` testcase: the same CLI exits 1 with exactly that identity missing (`tmp/cp/identity-synthetic-loss.json`, `tmp/cp/synthetic-loss-proof.json`). `tmp/cp/comparator-checks.log` also verifies that passing repetitions and uncorroborated duplicates are preserved. Independent verification and acceptance remain outstanding.

Comparison SHA-256: `745b1a45476e3759bb00c1351834c8b420ee289e48202e230b2016f188be528f`. Full scenario/run ledger: `tmp/cp/final-evidence.json`.

No acceptance record is authored here.

## Acceptance records
