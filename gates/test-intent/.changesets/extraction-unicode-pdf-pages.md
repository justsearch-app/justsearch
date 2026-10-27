---
schema: test-intent.v1
task: extraction-unicode-pdf-pages
author-role: builder
author-session: 01a17a07-0aaf-7bd3-afc4-646d3d17121a
---

# PDF physical-page inference test intent

All listed checks are durable. Existing expectation replacements in StructuredContentHandlerTest are classified Obsolete under D4/PD5. Independent acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java"
      ],
      "class": "New",
      "behaviour": "PD2/PD3/PD4/PD5/PD-N3: exact whitespace-separated page class token, first-page preamble, zero-based indices for headings, paragraphs, tables and lists, empty wrappers, and unchanged truncation ignoring later wrappers.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-pdf-pages request",
          "delegationScope": "Repair physical PDF page inference only in StructuredContentHandler, with named old expectations and durable SAX/real-PDF regressions; preserve thresholds, routing policies, PDFBox merging and stored documents until re-extraction.",
          "choice": "PD2/PD3/PD4/PD5/PD-N3: exact whitespace-separated page class token, first-page preamble, zero-based indices for headings, paragraphs, tables and lists, empty wrappers, and unchanged truncation ignoring later wrappers.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-pdf-pages-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPdfPageInferenceTest.java"
      ],
      "class": "New",
      "behaviour": "PD1/PD2/PD3/PD6/PD-N2: real Tika/PDFBox physical page counts, no invented separator, blank/image-only first/middle/last/consecutive/all pages, zero-based missing pages before and after enrichment, structured extraction, OCR skip and VDU demand; wrapper-free text and HTML remain unknown-page documents.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-pdf-pages request",
          "delegationScope": "Repair physical PDF page inference only in StructuredContentHandler, with named old expectations and durable SAX/real-PDF regressions; preserve thresholds, routing policies, PDFBox merging and stored documents until re-extraction.",
          "choice": "PD1/PD2/PD3/PD6/PD-N2: real Tika/PDFBox physical page counts, no invented separator, blank/image-only first/middle/last/consecutive/all pages, zero-based missing pages before and after enrichment, structured extraction, OCR skip and VDU demand; wrapper-free text and HTML remain unknown-page documents.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-pdf-pages-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPdfPageInferenceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredContentHandlerTest.java"
      ],
      "class": "Obsolete",
      "behaviour": "PD1/PD5: changed wrapper-as-delimiter expectations in PageBreakParsing.divWithPageClass and MixedContent.fullDocumentFlow: first wrapper starts page zero without a separator; preamble and subsequent elements share the first page.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-pdf-pages request",
          "delegationScope": "Repair physical PDF page inference only in StructuredContentHandler, with named old expectations and durable SAX/real-PDF regressions; preserve thresholds, routing policies, PDFBox merging and stored documents until re-extraction.",
          "choice": "Text before the first page wrapper belongs to the first page and never creates an extra page or a separator",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions/ and docs/reference/ for leading page breaks/separators and page wrappers/breaks (leading.{0,50}(page|separator)|page.{0,30}(wrapper|break), case-insensitive): no matching record and no requirement for a leading page break found."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java"
      ],
      "class": "New",
      "behaviour": "PD1: changed phantom-page expectations in supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured: no leading separator; one page, zero missing; textual OCR skip; 240 UTF-16 text units, 240 chars/page, ratio/quality 1; no VDU demand or dropout marker. Real ingestion/reopen and parent/chunk revisions remain for independent system verification.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-pdf-pages request",
          "delegationScope": "Repair physical PDF page inference only in StructuredContentHandler, with named old expectations and durable SAX/real-PDF regressions; preserve thresholds, routing policies, PDFBox merging and stored documents until re-extraction.",
          "choice": "PD1: changed phantom-page expectations in supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured: no leading separator; one page, zero missing; textual OCR skip; 240 UTF-16 text units, 240 chars/page, ratio/quality 1; no VDU demand or dropout marker. Real ingestion/reopen and parent/chunk revisions remain for independent system verification.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-pdf-pages-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ExtractionDropoutPdfFixturesTest.java#supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured"
      ],
      "repaired": true
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
  "digest": "sha256:8e3e0693565263a3727cc7993c0fe5ef2d366d173236d9e9eba5f516e21cb4b6"
}
```
