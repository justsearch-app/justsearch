---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-drafts-domain
---

# Draft owner extraction: saved preparation and readback preservation

No acceptance is recorded. The tests pin the existing saved payload, attachment write count and bytes during ordinary preparation changes and preparation readback; this extraction adds no production behavior. An independent verifier must record fresh acceptance for the updated evidence.

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
      "behaviour": "Preserve tier, schema, card scope and scope-clear mutations at synchronous attachment readback. A reentrant composer edit keeps the newer text, refuses the pending preparation change and shows the recovery warning. A conversation move from another surface is refused with the departure notice: the view keeps its original place and original A draft, and the pending preparation applies there (delegate tier, array schema, replacement.txt scope or empty scope). Neither path dispatches a task. Each readback case also retains companion C and asserts the complete saved payload: original A with the requested preparation, followed by C with its original Search preparation. One ordinary Delegate preparation change and all eight readback cases assert exactly one attachment write with the baseline bytes (only the opaque incarnation is fixed by the test). All nine cases pass on f9730e4010 and the extracted candidate. A duplicate saveSessionAttachment mutation fails all nine cases with two writes instead of one; replacing the acknowledged preparation with Search fails the saved-payload assertions. Removing the owner's post-readback freshness guards was previously shown to fail each edit case. The new file is also listed explicitly because the existing Ask source-walk test reads its folder.",
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

## Check durability

`SearchV3View.preparationAck.test.ts` is durable: it checks persisted preparations and companion drafts, exact attachment writes, and the existing live response to readback edits and refused departures through composer events and sessionStorage. The storage hooks and fixed incarnation are test-only fault injection and byte-comparison scaffolding.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18df4-f890-7b83-8101-992eca037b0e",
  "verdict": "accept",
  "digest": "sha256:a2cad3e73fbe507aeb3fb0db8ffd26c41defdf7090f20cf9376849b11ad08ec9"
}
```
