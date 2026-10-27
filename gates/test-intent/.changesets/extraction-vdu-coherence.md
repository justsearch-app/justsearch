---
schema: test-intent.v1
task: extraction-vdu-coherence
author-role: builder
author-session: uq-vdu-builder
---

# VDU replacement coherence test intent

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ],
      "class": "New",
      "behaviour": "VC1–VC5: real-index VDU replacements refresh parent and chunk token counts, remove only the pending-fallback reason from stored reads, preview metadata, exact filters and facets, complete unspecified text outcomes, refresh numeric query/sort and cached corpus/fusion inputs across refresh/reopen, and handle repeated, identical and legacy replacements. VC-N1–VC-N4 controls preserve nonreplacement descriptors/chunks, refuse stale source through WorkerIngestService, retain baseline routing/provenance and other reason codes, avoid rewriting untouched documents, preserve supplied status and retain existing content-dependent SPLADE/NER invalidation. BC2–BC3 controls preserve true/false truncation and OCR values through replacement and unrelated updates, project vdu_processed through real worker preview metadata, leave ingestion-history owners untouched through all public replacement forms, and retain the index fingerprint across reopen.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-vdu-coherence request",
          "delegationScope": "VDU text replacement refreshes token count, pending-fallback reason and unspecified-outcome status; stored booleans survive updates",
          "choice": "VC1–VC5: real-index VDU replacements refresh parent and chunk token counts, remove only the pending-fallback reason from stored reads, preview metadata, exact filters and facets, complete unspecified text outcomes, refresh numeric query/sort and cached corpus/fusion inputs across refresh/reopen, and handle repeated, identical and legacy replacements. VC-N1–VC-N4 controls preserve nonreplacement descriptors/chunks, refuse stale source through WorkerIngestService, retain baseline routing/provenance and other reason codes, avoid rewriting untouched documents, preserve supplied status and retain existing content-dependent SPLADE/NER invalidation. BC2–BC3 controls preserve true/false truncation and OCR values through replacement and unrelated updates, project vdu_processed through real worker preview metadata, leave ingestion-history owners untouched through all public replacement forms, and retain the index fingerprint across reopen.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-vdu-coherence-repair.json",
      "repaired": true,
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/StoredBooleanUpdateTest.java"
      ],
      "class": "New",
      "behaviour": "BC1, BC-N1–BC-N3: stored true, false and absent booleans survive unconditional, conditional, stale, batch, enrichment, status, fresh declaration, move and legacy migration paths across reopen; scalar types and expected retry comparisons retain their meaning; historical false changes only on re-extraction.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-vdu-coherence request",
          "delegationScope": "VDU text replacement refreshes token count, pending-fallback reason and unspecified-outcome status; stored booleans survive updates",
          "choice": "BC1, BC-N1–BC-N3: stored true, false and absent booleans survive unconditional, conditional, stale, batch, enrichment, status, fresh declaration, move and legacy migration paths across reopen; scalar types and expected retry comparisons retain their meaning; historical false changes only on re-extraction.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-vdu-coherence-repair.json",
      "repaired": true,
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/StoredBooleanUpdateTest.java"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java"
      ],
      "class": "New",
      "behaviour": "BC-N1: stored numeric boolean strings and incoming boolean/numeric representations retain their meaning, absent stays absent.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-vdu-coherence request",
          "delegationScope": "VDU text replacement refreshes token count, pending-fallback reason and unspecified-outcome status; stored booleans survive updates",
          "choice": "BC-N1: stored numeric boolean strings and incoming boolean/numeric representations retain their meaning, absent stays absent.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-vdu-coherence-repair.json",
      "repaired": true,
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java#storedNumericBooleanStringsKeepBooleanMeaningWithoutChangingOtherInputs"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewControllerTest.java"
      ],
      "class": "New",
      "behaviour": "BC3: the real HTTP preview endpoint reports vduProcessed true for the worker stored numeric form and the existing true string.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "if your done before 6 AM, try to find new work, even if substantial and proceed autonomously with research, investigation design and afterwards task register/ implementation",
          "delegationLocation": "task extraction-vdu-coherence request",
          "delegationScope": "VDU text replacement refreshes token count, pending-fallback reason and unspecified-outcome status; stored booleans survive updates",
          "choice": "BC3: the real HTTP preview endpoint reports vduProcessed true for the worker stored numeric form and the existing true string.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-vdu-coherence-repair.json",
      "repaired": true,
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PreviewControllerTest.java"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17d59-afbd-7b73-ba56-4fcf5711fa43",
  "verdict": "accept",
  "digest": "sha256:59343210284b0544795b20306b5b10554358b3edc9200df595c8bc454689f709"
}
```
