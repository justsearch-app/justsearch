---
schema: test-intent.v1
task: t-20261020-712d3e
author-role: builder
author-session: reload-holders-producer-repair
---

# Runtime reload drains producers and preserves immediate admission

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The actual admin HTTP handler reloads a real Engine root, native runtime and producer. Producer close must succeed before its runtime drains; a refused producer close retains the runtime and owner until retry. Issued filesystem mutations drain under the existing mutation fence, late callbacks from the retired owner are refused, and a mutation-drain timeout restores serving admission before retry. Five HTTP reloads each permit immediate real-client search and accepted ingestion. A physical health poll deliberately overlaps every replacement without publishing index.failed; fresh health succeeds immediately afterwards. Replacement services retain the selected embedding and NER bindings, and producer shutdown cannot close generation-owned NER. The existing physical watcher, exact registration witnesses and queued force paths transfer to the replacement, so an already-watched root continues ingesting after reload. Models are stubs in these automated cases; native model/backfill/enrichment HTTP receipts are separate. The independent status sampler also defers capture across a planned replacement without publishing index.failed or refreshing an old sample timestamp. Retained unpublished producer work drains before its runtime can close. Shutdown that wins during the new producer drain prevents a replacement from being published.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261020-712d3e user turn 4",
          "quote": "Fix it so a reload stops and drains old producers (indexing loop, backfills, enrichment) before closing what they use, and so the Engine accepts work again as soon as the reload returns 200."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261020-712d3e user turn 4",
          "quote": "Add checks that fail on 18ea761cc for these reasons: producer-before-close ordering, and repeated reloads followed at once by search and ingestion."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261020-712d3e",
          "scenario": "R8",
          "quote": "a queued reload never resurrects serving;",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/runtime-reload-producers.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/app/engine/RuntimeReloadProducersHttpIntegrationTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/BootstrapLeaseFixtures.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The mocked bootstrap now provides the observation-only optional capture used by the status sampler. It delegates to the same existing captureClient stub, retaining every existing unavailable/failure injection, exact bound-client action, and test assertion. Ordinary request capture stubs are unchanged."
    }
  ]
}
```

## Acceptance records
