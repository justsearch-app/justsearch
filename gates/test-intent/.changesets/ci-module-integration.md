---
schema: test-intent.v1
task: t-20261020-5d7302
author-role: builder
author-session: ci-module-integration-builder-20261022
---

# Test-intent entries

No lane previously ran these five modules' integrationTest suites. The ui
RuntimeReloadHttpIntegrationTest failed on every main commit from #841 until #895
unnoticed. The added job executes existing checks without introducing or changing
their expectations. A local run took 139 s; app-api and reranker self-skip without
model files.

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml"
      ],
      "change": "modified",
      "kind": "watched-build-config",
      "class": "Structural rule kept",
      "rule": "The existing app-api, app-services, indexing, reranker and ui integrationTest suites retain their test selection, prerequisites and assertions; CI coverage is strengthened by executing them on main pushes and manual dispatch.",
      "whySurvives": "One Linux job runs the five existing integrationTest tasks with --continue and -PskipWebBuild=true, retains model-file self-skips, and uploads their results even on failure. Its 20-minute job timeout accommodates the observed 139 s local run. The job is main/dispatch-only, advisory and not required; existing PR lanes remain selected as before. Wall-clock attribution waits for the added job. No existing test oracle or execution lane is removed or weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes",
          "delegationLocation": "task t-20261020-5d7302 agreement",
          "delegationScope": "Approved direction: slow suites and the combined-tree check on main after merging",
          "choice": "Run the five modules' existing integrationTest suites (app-api, app-services, indexing, reranker, ui) in one main/dispatch-only Linux job, not on PRs and not required.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "orchestrator",
  "session": "justsearch-03",
  "verdict": "accept",
  "digest": "sha256:5832328859aefa738341eb96f9acb2428b180372eddef93e0c1cc184c9108d87",
  "note": "Checked against the diff: the job only adds execution of five existing integrationTest suites on main and dispatch, fails on any test failure, and changes no test or PR lane. The delegated choice lies within the owner's approved 'slow suites on main after merging'. Verifier passed M1-M5."
}
```
