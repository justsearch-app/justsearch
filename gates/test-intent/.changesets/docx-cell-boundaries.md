---
schema: test-intent.v1
task: t-20261113-docxcells
author-role: builder
author-session: f5154131-58b2-4c0e-8a50-dc0fb1bf7d4f
---

# Test-intent entries

Words inside one table cell or list item no longer run together across paragraphs, line breaks and
nested-table cells (repaired), while body paragraphs, headings, PDF line handling and content limits
stay as they were (preserved).

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfWordBoundaryTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired: in a table cell (td or th, including the first row reused as labels and a single-row table) or a list item, a paragraph end, a line break or a nested-table cell/row end leaves one space between words, so 'repercussive'/'All' and 'Jason Adams'/'Avneet Nahal' are stored apart; a list inside a cell keeps all its text without a duplicate standalone list; empty or whitespace-only paragraphs add no extra separator; in PDF mode a hyphen at a paragraph end inside a cell is not joined with the next paragraph. PdfWordBoundaryTest: for td and li, the non-PDF case now expects 'nur unzureichend Rech-\\nnung' (boundary kept, no hyphen joining outside PDF); its old expectation 'nurunzureichend ...' for td and li pinned the run-together defect and is replaced, while p and h2 keep the old expectation unchanged. Preserved (pass before and after): single-paragraph cells with inline runs and the row/column/value layout; empty paragraphs make no content; body paragraphs, headings and text after a table; PDF physical-line hyphen joining; the content limit counts separators and never splits a surrogate pair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "For now pick any other issues you think are meaningful for a user and fix them",
          "delegationLocation": "task t-20261113-docxcells request",
          "delegationScope": "pick issues meaningful for a user and fix them",
          "choice": "Fix the confirmed Word defect that paragraphs inside one table cell are stored run together ('repercussiveAll', 'Jason AdamsAvneet Nahal') so neither word is findable, with the same boundary for line breaks, nested-table cells and list items (scenarios S1-S5), keeping body text, headings, PDF line handling and content limits unchanged (P1-P3).",
          "label": "agent choice"
        }
      ],
      "evidence": [
        "gates/test-intent/evidence/docx-cell-boundaries-repair.json",
        "gates/test-intent/evidence/docx-cell-boundaries-preservation.json"
      ],
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s1ParagraphsInCellAndReusedLabels",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s1SingleRowTable",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s2BreakInCell",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s3NestedCellsRowsAndSurroundingText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s4ListParagraphs",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s4ListBreaks",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#s4ListInCellHasAllTextAndNoStandaloneDuplicate",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#extraEmptyParagraphsBetweenWordsDoNotDoubleSeparators",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TableCellBoundaryContractTest.java#extraCellHyphenIsNotJoinedAcrossParagraphsInPdfMode",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PdfWordBoundaryTest.java#otherFormatsSeparateCellAndListBoundariesWithoutJoiningHyphens"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
