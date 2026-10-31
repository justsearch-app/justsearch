---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: fe-sessions-domain
---

# Session navigation preservation

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessionNavigation.test.ts"
      ],
      "class": "New",
      "behaviour": "Preserve the shown conversation, reload pointer, draft and absence of read/send requests when row selection, version opening outside the sidebar or address following cannot acknowledge draft storage (unavailable, unacknowledged, stale ownership and capacity). Address restoration may move the shared pointer while the refused view keeps its identity. Unknown rows leave the per-tab reload pointer unchanged; selecting the active row refreshes both records without reordering or closing its evidence pane. Successful A-to-B row opening observes the shared active conversation and per-tab reload pointer at the start of each thread/history fetch: both must already be B. The retained-preparation A-to-B-to-A case gives A and B distinct Structured schemas, checks both after navigation, and preserves the original draft, tier, scope, no-review and no-send assertions. A second A-to-B-to-A case uses raw draft restoration, the recovery-copy UI and fresh handoffs to park conversations with null preparations while receiveHandoff records their fallback tier and file scope. It checks Ask/A.txt and Structured/B.txt through repeated row switches. All 16 cases pass on unchanged c678e421e9 in an isolated shared clone and on the extraction; no moved-member adaptation was needed. No assertion was weakened or removed and no production code changed in this test-quality round. The file is named because SearchV3View.ask.test.ts walks views/search-v3. No catalog observation is added: these checks do not establish every clause of a catalog entry.",
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
          "choice": "Extract sessions and navigation into one browser-free owner and add preservation checks for refused draft acknowledgements across opening paths and row-selection identity without changing established outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-sessions-domain.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessionNavigation.test.ts"
      ]
    }
  ]
}
```

Independent acceptance is left to the verifier. The refreshed evidence supersedes the previous 14-case evidence; any earlier acceptance must be renewed for this test content.

Tests added or rewritten: SearchV3View.sessionNavigation.test.ts — refused departures, unknown-row pointer preservation, per-conversation schemas, raw-draft fallback restores and claim-before-fetch order — durable. HTTP responses, fetch-start observations, raw recovery setup, storage fault injection and rendering drains are local scaffolding. No test-only helper file was added.

## Test-quality round: executed preservation and mutation checks

Baseline setup: `git clone --quiet --shared --no-checkout . "$TMPDIR/before" && git -C "$TMPDIR/before" checkout --quiet --detach c678e421e9`. The changed test was copied unchanged into that checkout; root and ui-web dependencies were copied into checkout-local directories so their caches were not shared. No compatibility or production patches were applied. `npm run test:unit:run --prefix "$TMPDIR/before/modules/ui-web" -- src/shell-v0/views/search-v3/SearchV3View.sessionNavigation.test.ts --maxWorkers=4` exited 0: `Test Files 1 passed (1); Tests 16 passed (16)`.

The same command against the candidate's ui-web directory exited 0: `Test Files 1 passed (1); Tests 16 passed (16)`. Thus all strengthened scenarios pass before and after extraction.

Faults were introduced one at a time in a separate shared candidate clone under `$TMPDIR/sessions-mutations`, using its own dependency copies, and restored after each run. Each targeted Vitest run used the same test path, `--maxWorkers=4` and `-t` with the scenario fragment below. Every listed fault exited 1 with one assertion failure (the other 15 cases were deliberately filtered out, not counted as passes).

| Fault | Targeted scenario | Actual failing output |
| --- | --- | --- |
| Write `this.transport.setLastViewed(id)` before returning for an unknown row | `unknown rows` | `AssertionError: expected 'unknown' to be 'a'` at the raw sessionStorage pointer assertion |
| Omit `if (savedTier !== undefined && preparation === null) this.tier = savedTier;` | `restores fallback tiers` | `AssertionError: expected 'ask' to be 'structured'` on returning to B |
| Omit `this.search.prepare(this.placeScopes.get(to) ?? null)` while retaining `clearScope()` | `restores fallback tiers` | `AssertionError: expected '' to contain 'a.txt'` on returning to A |
| In `applyPreparation`, clear the schema when the outgoing active conversation is A (opening B), instead of applying B's saved schema | `restores each conversation` | `AssertionError: expected '' to be '{"type":"object","properties":{"b":{"…'`; expected B's full schema, received empty text |
| Make row selection async and await both record reads before `claimConversation(id)`, removing the later duplicate reads | `opening B from A` | `AssertionError: expected [ …(2) ] to deeply equal [ …(2) ]`; both `/api/thread/b` and `/api/chat/conversations/b/history` observed `activeId: 'a', pointer: 'a'`, expected `'b'` for each |

Moving just the two read-method calls before the synchronous claim survived: their authorization path defers the actual fetch until after the claim. The last fault above waits for the reads before claiming, and therefore exercises the required wrong fetch-start ordering on the real fetch seam. Scratch production files were restored; production files in the candidate were never modified.
