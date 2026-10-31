---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-search-coord-e4fkw1rd
---

# Test-intent entries

Search commands and Forget coordination move to the existing search controller. Existing assertions
and catalog bindings are preserved. The added source is in the directory scanned by
SearchV3View.ask.test.ts; its single-issuance and cross-window import predicates remain unchanged.
No controller catalog observation is added.

The 25 preservation cases pass against unchanged production at f88714bfaf in an independent
shared clone with its own dependencies, and against the candidate. They cover refused, stale and
unconfirmed recovery acknowledgement for Enter, typing and palette Search; failure of the next
results-place acknowledgement; refused Ask preparation; blank palette Search; and stale capability
reads over disconnect, reconnect and API-base change; whitespace-only file paths; mismatched Forget hit identity; and filesystem-root folder refusal. No known defect is asserted as intended.

The refusal helper requires exactly one interception of the named storage fault (a rejected write,
the stale-owner read, or the unconfirmed readback). The second-acknowledgement cases retain their
exactly-two-write assertion. Paired writable-storage controls issue Search for Enter, typing and
palette, and prepare scoped Ask for card-file and chosen-file actions. Returning before storage
in draftMayIssue makes all nine first-acknowledgement cases fail with zero interceptions; returning
before storage in acknowledgePreparation makes both Ask refusal cases fail the same assertion.

The focused composer-port case distinguishes a pending pre-render seed from the mounted draft,
including seed flush and a later reader edit. All four port cases pass on the candidate. The new
readMounted method does not exist at f88714bfaf: its equivalent was the palette handler's inline
composer?.draft read. A baseline-only scaffold exercises that actual handler through the palette
event with the composer removed and a pending seed, then with the composer mounted; it passes,
along with the 25 preservation and three existing port cases. Returning read() from readMounted
in an isolated candidate clone fails the new case: expected empty, received pending seed. Faults
and the baseline scaffold are temporary checks; production files in this candidate are unchanged.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts", "modules/ui-web/src/shell-v0/views/search-v3/Sv3ComposerPort.test.ts"],
      "class": "New",
      "behaviour": "Preserve refusal before Search issuance or scheduling when recovery acknowledgement fails, including the results-place acknowledgement; require the named storage fault to be intercepted exactly once and the second acknowledgement to attempt exactly two writes; pair refusal with writable-storage Search and scoped Ask preparation controls; preserve Search text and preparation when chosen-file Ask cannot be saved; preserve blank palette mode changes without issuance; permit Forget only after the current connected API base has supplied a valid capability response; preserve refusal of whitespace-only Ask paths, mismatched Forget hit identity and filesystem-root folder targets; readMounted ignores a pending pre-render seed and follows the mounted composer's draft, seed flush and subsequent edits while read still exposes the unmounted seed.",
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
          "choice": "Add mounted-window preservation checks for recovery acknowledgement refusal, chosen-file Ask preparation, blank palette Search, obsolete Forget capability reads, whitespace-only Ask paths and exact Forget targets around the extraction into the existing search controller; strengthen storage-refusal causality with exact fault interception counts and paired writable controls; add the composer port's pending-seed versus mounted-draft read boundary.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-search-coord.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.searchPreservation.test.ts", "modules/ui-web/src/shell-v0/views/search-v3/Sv3ComposerPort.test.ts"]
    }
  ]
}
```

## Acceptance records

Left to the independent verifier.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1913d-6317-7612-b650-0828dd0592f6",
  "verdict": "accept",
  "digest": "sha256:8e74f89fe4edd536d9342bd60ada559ba8ba1a2a0568e01f021334e1c72779b8"
}
```
