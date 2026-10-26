---
schema: test-intent.v1
task: extraction-unicode-scalar
author-role: builder
author-session: uq-builder-scalar-1
---

# Extraction quality scalar test intent

All added tests and the two numeric-type expectation corrections are durable. The supplied brief permits New/Changed classes; the gate vocabulary supports New, so new Double expectations use New. No independent acceptance is recorded here.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java"
      ],
      "class": "New",
      "behaviour": "SC1/SC7/SC8: the real catalog maps finite double values and numeric representations to stored doubles, raw numeric docvalues and points; the ingestion health canary materializes the double.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC1/SC7/SC8: the real catalog maps finite double values and numeric representations to stored doubles, raw numeric docvalues and points; the ingestion health canary materializes the double.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java#realCatalogDoubleUsesStoredRawNumericDocValuesAndPoints",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java#ingestionCanaryExercisesRealCatalogDouble"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java"
      ],
      "class": "New",
      "behaviour": "SC7 preservation control: null, NaN, infinities and nonnumeric input remain absent while the rest of the document is retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC7 preservation control: null, NaN, infinities and nonnumeric input remain absent while the rest of the document is retained.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-preservation.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FieldMapperTest.java#invalidDoubleIsOmittedWithoutLosingOtherFields"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "SC1/SC2/SC3/SC6/SC7/SC8/SC-N1/SC-N2/SC-N3: real catalog/index projections preserve exact 0.1/0/1 and absence across segments and reopen; numeric exact lookup and adjacent-double sort; unrelated conditional, enrichment, batch and rename updates; docvalues-only double reconstruction; unchanged-catalog fingerprint golden and explicit-projection preservation controls.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC1/SC2/SC3/SC6/SC7/SC8/SC-N1/SC-N2/SC-N3: real catalog/index projections preserve exact 0.1/0/1 and absence across segments and reopen; numeric exact lookup and adjacent-double sort; unrelated conditional, enrichment, batch and rename updates; docvalues-only double reconstruction; unchanged-catalog fingerprint golden and explicit-projection preservation controls.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/DoubleFieldIntegrationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOpsTest.java"
      ],
      "class": "New",
      "behaviour": "SC1: the extraction producer retains the exact computed Double instead of rounding through Float.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC1: the extraction producer retains the exact computed Double instead of rounding through Float.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOpsTest.java#extractionQualityRetainsTheComputedDoubleWithoutFloatRounding"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "SC1/SC4/SC-N1: real file admission, extraction and writer preserve legacy absence on unchanged skipping, populate an unchanged legacy document on force, and replace the score after changed-file extraction, with reopen.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC1/SC4/SC-N1: real file admission, extraction and writer preserve legacy absence on unchanged skipping, populate an unchanged legacy document on force, and replace the score after changed-file extraction, with reopen.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingDocumentOpsScalarIntegrationTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterTest.java"
      ],
      "class": "New",
      "behaviour": "SC5: explicit success, legacy COMPLETED and unspecified nonblank text recompute score with content in a real index; empty, failed, rejected, blank, invalid and stale updates retain text/score across reopen. Durable replay retains text and score while requesting an ordinary refresh; the orchestrator confirmed replay does not replace text and is outside these replacement checks (SC4 covers refresh).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC5: explicit success, legacy COMPLETED and unspecified nonblank text recompute score with content in a real index; empty, failed, rejected, blank, invalid and stale updates retain text/score across reopen. Durable replay retains text and score while requesting an ordinary refresh; the orchestrator confirmed replay does not replace text and is outside these replacement checks (SC4 covers refresh).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/ExtractionDropoutMarkerTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java"
      ],
      "class": "New",
      "behaviour": "SC1/SC-N2: change only two existing score expectations from Float 1.0 to Double 1.0 to match the repaired producer; preserve every other assertion.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-scalar request",
          "delegationScope": "Repair extraction_quality_score double persistence and every existing read/update path, preserve the catalog/index fingerprint, and recompute quality on VDU text replacement.",
          "choice": "SC1/SC-N2: change only two existing score expectations from Float 1.0 to Double 1.0 to match the repaired producer; preserve every other assertion.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-scalar-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/ExtractionDropoutMarkerTest.java#utf8SupplementaryTextKeepsItsStructuredMethodAndNoDropoutMarker",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java#supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured"
      ],
      "repaired": true
    }
  ]
}
```
