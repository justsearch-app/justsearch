---
schema: test-intent.v1
task: extraction-unicode-pdf-pages
author-role: builder
author-session: 01a17a07-0aaf-7bd3-afc4-646d3d17121a
---

# PDF physical-page inference test intent

All listed checks are durable. Existing expectation replacements are classified New under the current gate schema (which has no Changed class). Independent acceptance remains pending.

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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#similarClassNamesDoNotStartPages",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#exactWhitespaceSeparatedClassTokenStartsPage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#preambleBelongsToFirstPageWithoutInventingABoundary",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#everyStructuredElementUsesPhysicalPageBeforeEnrichment",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#emptyWrappersCountAndBlankFirstPageKeepsItsBoundary",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPageWrapperTest.java#truncationStillIgnoresLaterPageWrappers"
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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPdfPageInferenceTest.java#physicalPagesAndMissingIndicesAgreeBeforeEnrichment"
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
      "evidence": "gates/test-intent/evidence/extraction-unicode-pdf-pages-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredPdfPageInferenceTest.java#wrapperFreePlainTextAndHtmlKeepTextAndUnknownPageCount"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredContentHandlerTest.java"
      ],
      "class": "New",
      "behaviour": "PD1/PD5: changed wrapper-as-delimiter expectations in PageBreakParsing.divWithPageClass and MixedContent.fullDocumentFlow: first wrapper starts page zero without a separator; preamble and subsequent elements share the first page.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "btw you can reconsider the agents previous decisions, proceed autonomously with the work.",
          "delegationLocation": "task extraction-unicode-pdf-pages request",
          "delegationScope": "Repair physical PDF page inference only in StructuredContentHandler, with named old expectations and durable SAX/real-PDF regressions; preserve thresholds, routing policies, PDFBox merging and stored documents until re-extraction.",
          "choice": "PD1/PD5: changed wrapper-as-delimiter expectations in PageBreakParsing.divWithPageClass and MixedContent.fullDocumentFlow: first wrapper starts page zero without a separator; preamble and subsequent elements share the first page.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/extraction-unicode-pdf-pages-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredContentHandlerTest.java#divWithPageClass",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredContentHandlerTest.java#fullDocumentFlow"
      ],
      "repaired": true
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
