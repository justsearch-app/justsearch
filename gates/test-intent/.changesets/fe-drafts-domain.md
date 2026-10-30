---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-drafts-domain
---

# Draft owner extraction: preparation readback preservation

No acceptance is recorded. The tests pin the existing behavior during preparation readback; this extraction adds no production behavior.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.forgetRetention.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "split"
      ],
      "whatMoved": "The preparedHandoff, placeDrafts and schemaDraft members moved from SearchV3View to its draftDomain. Only member paths and their corresponding structural casts changed; all input, fault injection, identity checks, assertions and expected values remain unchanged.",
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
          "choice": "Move retained human work, preparations, recovery and handoff intake into a browser-free owner, adapt only relocated member accesses, and check preparation readback reentrancy without changing existing behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-drafts-domain.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.preparationAck.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve tier, schema, card scope and scope-clear mutations at synchronous attachment readback. A reentrant composer edit keeps the newer text, refuses the pending preparation change and shows the recovery warning. A conversation move from another surface is refused with the departure notice: the view keeps its original place and original A draft, and the pending preparation applies there (delegate tier, array schema, replacement.txt scope or empty scope). Neither path dispatches a task. All eight cases pass on f9730e4010 and the extracted candidate; removing the owner's post-readback freshness guards makes each edit case fail. The new file is also listed explicitly because the existing Ask source-walk test reads its folder.",
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
          "choice": "Move retained human work, preparations, recovery and handoff intake into a browser-free owner, adapt only relocated member accesses, and check preparation readback reentrancy without changing existing behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-drafts-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.preparationAck.test.ts"
      ]
    }
  ]
}
```

## Acceptance records
