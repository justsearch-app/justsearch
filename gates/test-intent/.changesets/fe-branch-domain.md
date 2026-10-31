---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-branch-domain
---

# Branch and version owner preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "branchInto moved from SearchV3View to branchDomain. The two structural casts and the two calls follow the member into the new owner; all assertions, inputs and Ask accesses are unchanged. The parameterized checks remain XN1 branch inherits only the accepted output after %s and D-T43 failed saved output contributes nothing after branching (%s).",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move branch and version derivation and orchestration into a browser-free owner, adapt only moved member accesses, and preserve reservation and claim/read-before-resend ordering with HTTP-boundary checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-branch-domain.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ],
      "class": "New",
      "behaviour": "Retain all 52 existing branch tests and add five preservation cases (four declarations, one parameterized twice). At HTTP fetch-start observe the acknowledged rerun task before branch POST; hold child history and observe child claim before both record reads, no dispatch during the wait, and resend only after release. Refuse task-bearing recovery writes by exception or missing acknowledgement and retain the inline rewrite without branch POST or dispatch. Exclude a concurrent Retry while the first reserved fork is pending, then issue exactly one dispatch. Leave the screen and reload during a held branch POST, then resolve the old response and observe no child claim, child history read or resend. These checks pass on unchanged production 5aff3acd28 in a shared clone and on the extraction. This existing test file stays gated; SearchV3View.ask.test.ts also walks views/search-v3. No catalog observations are added.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261029-fs993c request, 2026-10-30",
          "quote": "proceed with step 4"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Move branch and version derivation and orchestration into a browser-free owner, adapt only moved member accesses, and preserve reservation and claim/read-before-resend ordering with HTTP-boundary checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-branch-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.branch.test.ts"
      ]
    }
  ]
}
```

Independent acceptance is left to the verifier. No catalog observations or test-only frontend helper files were added.

Tests added or rewritten: SearchV3View.branch.test.ts — reservation acknowledgement, claim and read ordering before resend, concurrent Retry, abandoned fork after leaving and reload — durable. SearchV3View.acceptedOutputCompatibility.test.ts — moved branchInto member access only — durable. HTTP snapshots, response holds, storage fault injection and rendering drains are local scaffolding.
