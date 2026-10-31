---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-search-coord-e4fkw1rd
---

# Test-intent entries

Search commands and Forget coordination move to the existing search controller. Existing tests,
assertions and catalog bindings are unchanged. The added source is in the directory scanned by
SearchV3View.ask.test.ts; its single-issuance and cross-window import predicates remain unchanged.
No controller catalog observation is added.

The 20 added preservation cases pass against unchanged production at f88714bfaf in an independent
shared clone with its own dependencies, and against the candidate. They cover refused, stale and
unconfirmed recovery acknowledgement for Enter, typing and palette Search; failure of the next
results-place acknowledgement; refused Ask preparation; blank palette Search; and stale capability
reads over disconnect, reconnect and API-base change; whitespace-only file paths; mismatched Forget hit identity; and filesystem-root folder refusal. No known defect is asserted as intended.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts"],
      "class": "New",
      "behaviour": "Preserve refusal before Search issuance or scheduling when recovery acknowledgement fails, including the results-place acknowledgement; preserve Search text and preparation when chosen-file Ask cannot be saved; preserve blank palette mode changes without issuance; permit Forget only after the current connected API base has supplied a valid capability response; preserve refusal of whitespace-only Ask paths, mismatched Forget hit identity and filesystem-root folder targets.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed with step 4",
          "location": "task t-20261029-fs993c request, 2026-10-30"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed with step 4",
          "delegationLocation": "task t-20261029-fs993c request, 2026-10-30",
          "delegationScope": "Step 4 of the agreed frontend restructuring: move the Search view's areas out one at a time, nothing the user sees changes, under the same safety net of scenario checks",
          "choice": "Add mounted-window preservation checks for recovery acknowledgement refusal, chosen-file Ask preparation, blank palette Search, obsolete Forget capability reads, whitespace-only Ask paths and exact Forget targets around the extraction into the existing search controller.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-search-coord.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts"]
    }
  ]
}
```

## Acceptance records

Left to the independent verifier.
