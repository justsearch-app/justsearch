---
schema: test-intent.v1
task: lossy-decoding-reported
author-role: builder
author-session: 01a17e9c-3dab-7122-8af8-54af50a2917b
---

# Decoding loss reporting test intent

The settled builder brief supplies L1–L7, P1–P9 and I1. Metadata checks use the owned production parser graph and pin the actual charset and parser. The content-limit checks pin fixed expected text, including replacement characters and the exact truncation boundary, rather than comparing two calls through the changed extractor. Real SQLite and Lucene checks use private temporary stores; no Engine or shared stack is used. Performance P9 is measured separately, as requested. Evidence files and independent acceptance are left to the orchestrator/verifier; this builder does not run the evidence generator.

The UTF-8 validator optimization adds fixed loss checks for invalid leads, overlong forms, encoded surrogates and code points above U+10FFFF in entry 1. Entry 1 also has a fixed-seed differential check against the strict JDK decoder over 4,224 samples, including valid Unicode range edges, all invalid classes, and read-boundary splits in memory and spilled snapshots; it fails before because main never detected loss in the lossy samples.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/DecodingLossJournalTest.java",
        "modules/ui-web/src/shell-v0/components/IngestionSummary.test.ts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LossyDecodingStoredDocumentTest.java",
        "governance/logic-seams.v1.json#law-targetTests"
      ],
      "class": "New",
      "behaviour": "L1–L7: report lossy decoding using the parser-reported charset through structured, flat fallback, sandbox reuse/replacement, document storage/reopen/search/slice metadata, mixed-batch journal, SQLite retry/recovery and the rendered ingestion summary. Preserve extracted text and independent truncation facts. Late HTML declarations pin the actual Windows-1252 choice, including an undefined byte in that charset. Review repairs use Tika 3.2.3's insertion-ordered parse-context record, which document metadata cannot change: the first non-dispatcher identifies the outer document's parser, so embedded image parsers cannot suppress HTML loss-only or combined loss/truncation reporting on either the in-process or child-process route; a forged X-TIKA:Parsed-By tag cannot suppress the audit, and HTML encoding meta tags cannot replace the parser-reported charset to hide loss, including across child reuse/replacement. Flat fallback must retain the handler's write-limit signal for lossy and clean oversized files. Content-limit checks now assert fixed expected text, replacement characters and the exact boundary. IndexingLoopTest#mixedPartialReasonsSurviveBatchFallbackAndRetry (batch fallback and individual retry with mixed partial reasons) sits in a nested class the evidence runner cannot select by method; the same reasons through individual retry and recovery are evidenced by DecodingLossJournalTest. The fail-before run applies gates/test-intent/compatibility/lossy-decoding-before.patch, which only adds the new ExtractionArtifact.partialReasonCode() accessor returning the old meaning of every partial (truncation) and a DecodingLossAudit.isLossy(...) that never reports loss, as main never did. Audit checks that expect loss (malformed or incomplete sequences across the audit buffer, invalid bytes after long ASCII runs, in memory and spilled) therefore fail before by assertion. Direct audit checks also require generic UTF-16 to retain its byte-order-selecting BOM before an incomplete surrogate, and ISO-2022-JP to retain its JIS X 0208 shift state across reads ending immediately after the escape sequence, in memory and spilled. P6a follows the parser selected by existing routing: valid PDF bytes with a long ASCII prefix and a late undefined Windows-1252 byte, named .txt, are decoded by TextAndCSVParser in windows-1252 and report TEXT_LOST_WHILE_DECODING; the routing rule is unchanged.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "On main, a UTF-8 file with BOM containing invalid byte sequences is stored with replacement characters as a full success, and the app ingestion summary reports it as indexed. These checks safeguard visibility of that lost text while preserving searchable extracted content. Review at 034135a766 also reproduced declared-UTF-8 HTML with an invalid body byte becoming full success when a meta tag adds X-TIKA:Parsed-By=other, and flat fallback discarding its write-limit signal for both lossy and clean oversized files."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Audit the complete prepared snapshot strictly with fixed-size decoder buffers and the content encoding reported by the actual whole-file text parser (explicit allowlist), after structured or flat extraction. Carry one decoding-loss warning in the existing artifact/sandbox warnings transport; classify nonblank lossy or truncated content as partial, keep blank output empty, derive independent loss-only/combined/truncation-only reasons for documents and ledger groups while preserving document dropout overrides, retry/recovery and historical data. Add the two readable summary labels. Move all extraction policy id forms from v6 to v7 without automatic re-extraction.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lossy-decoding-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8InvalidLeadBytesAreLossy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8OverlongFormsAreLossy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8EncodedSurrogatesAreLossy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8CodePointsAboveUnicodeMaximumAreLossy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#inlineImageCannotDisableHtmlLossAudit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#inlineImageCannotDisableCombinedHtmlLossAudit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#sandboxInlineImageCannotDisableHtmlLossAudit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#sandboxInlineImageCannotDisableCombinedHtmlLossAudit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#htmlParserMetaCannotDisableLossAudit",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8AuditMatchesJdkStrictDecoderOnGeneratedBytes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#malformedUtf8AcrossAuditBufferInMemory",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#malformedUtf8AcrossAuditBufferSpilled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#incompleteUtf8AfterFullAuditBufferInMemory",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#incompleteUtf8AfterFullAuditBufferSpilled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#longAsciiRunBeforeInvalidUtf8ByteInMemory",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#longAsciiRunBeforeInvalidUtf8ByteSpilled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#longAsciiRunBeforeInvalidAsciiByteInMemory",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#longAsciiRunBeforeInvalidAsciiByteSpilled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#genericUtf16BomSelectsByteOrderForIncompleteSurrogate",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#iso2022JpShiftStateSurvivesAuditReads",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#htmlEncodingMetaCannotHideLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#flatFallbackPreservesLossAndTruncation",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#invalidUtf8SequenceMidFileIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#invalidUtf8ByteIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#truncatedUtf8SequenceMidFileIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#incompleteUtf8AtRealEofIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#undefinedWindows1252ByteIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#loneUtf16LowSurrogateIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossAfterDetectorWindowIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#undeclaredUtf8LossAfterDetectorWindowIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#declaredHtmlLossIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#htmlBomWinsConflictingDeclaration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lateHtmlDeclarationReportsLossInActualWindows1252",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#xhtmlThroughHtmlParserReportsLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossBeforeContentLimitIsPartialAndTruncated",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossAfterContentLimitIsPartialAndTruncated",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#markupHeavyHtmlLossAfterLimitIsPartialAndTruncated",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#structuredFailureFlatFallbackStillReportsLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#sandboxRoutesReuseAndReplacementKeepLossIsolated",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#sandboxPreservesCombinedLossAndTruncationForTextAndHtml",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossyMarkdownIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossyJsonIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossySourceCodeIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossyCsvIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lossyTsvIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#replacementOnlyArtifactIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#oneAlphanumericArtifactIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#twoAlphanumericArtifactIsPartial",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java#realUtf8LossIsPartialOnDocumentAndLedger",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java#dropoutOverridesOnlyDocumentReasonWhenDecodingLosesCharacters",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LossyDecodingStoredDocumentTest.java#lossSurvivesReopenAndFileRepairClearsReason",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/LossyDecodingStoredDocumentTest.java#combinedSurvivesReopenAndFileRepairKeepsTruncationReason",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/DecodingLossJournalTest.java#mixedPartialReasonsSurviveIndividualRetryAndReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/DecodingLossJournalTest.java#mixedPartialReasonsSurviveAbandonedClaimRecovery",
        "modules/ui-web/src/shell-v0/components/IngestionSummary.test.ts#distinguishes decoding loss from truncation in the rendered summary",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#pdfNamedTextIsAuditedByTextParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#malformedUtf8AfterLongAsciiStartKeepsLegacyAudit"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionOutcomeClassifierTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P8: keep clean charsets and parser routing, intentional replacement characters, decoded controls, clean truncation, blank output, input refusal, XML/mail/PDF/Office outcomes and historical ledger data unchanged. The late-declared HTML FF byte is valid in the actual Windows-1252 charset and stays full success. HTML name/http-equiv/property tags cannot replace the parser-reported charset to invent loss. Forged parser metadata inside an HTML mail body cannot enable whole-file auditing for the mail container. The outer container remains excluded even when an embedded text parser runs. A failed structured attempt now leaves a stale container parser entry and write-limit flag in the fallback checks; a clean short flat fallback must discard both, stay untruncated and remain full success. Plain text explicitly pins TextAndCSVParser as the first non-dispatcher in the parse record, and HTML with an inline data-URI PNG pins JSoupParser before the embedded ImageParser, with UTF-8 reported for both documents. Container exclusion is exercised with MboxParser reporting an outer windows-1252 charset and later RFC822Parser/TextAndCSVParser parsing a base64 CSV part containing invalid UTF-8; an undefined byte in the outer Windows-1252 bytes makes an incorrectly enabled whole-file audit observable. Its existing full-success status and lack of decoding-loss reason remain unchanged. The identical PDF bytes named .pdf are handled by PDFParser, retain the expected extracted text and full-success status, and carry no decoding-loss reason.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "On main, a UTF-8 file with BOM containing invalid byte sequences is stored with replacement characters as a full success, and the app ingestion summary reports it as indexed. These checks safeguard visibility of that lost text while preserving searchable extracted content."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Audit the complete prepared snapshot strictly with fixed-size decoder buffers and the content encoding reported by the actual whole-file text parser (explicit allowlist), after structured or flat extraction. Carry one decoding-loss warning in the existing artifact/sandbox warnings transport; classify nonblank lossy or truncated content as partial, keep blank output empty, derive independent loss-only/combined/truncation-only reasons for documents and ledger groups while preserving document dropout overrides, retry/recovery and historical data. Add the two readable summary labels. Move all extraction policy id forms from v6 to v7 without automatic re-extraction.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lossy-decoding-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#flatFallbackDiscardsFailedAttemptState",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#htmlEncodingMetaCannotInventLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#htmlParserMetaCannotEnableAuditForMail",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#lateHtmlDeclarationUsesReportedCharset",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanWindows1252IsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanShiftJisIsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanGb18030IsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanEucKrIsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanKoi8RIsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#undeclaredHtmlIsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#intentionalReplacementCharacterIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8WithBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8WithoutBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#asciiIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf16LeBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf16BeBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf16LeWithoutBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf16BeWithoutBomIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#markdownUsesTextParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#jsonUsesTextParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#sourceUsesSourceCodeParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#csvUsesTextParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#tsvUsesTextParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanHtmlIsUnchanged",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanTruncationRemainsPartialWithoutLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#blankRemainsEmpty",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#inputBudgetStillRefusesBeforeParsing",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#validDecodedControlsDoNotImplyLoss",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#misdeclaredMailPartKeepsExistingOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#invalidXmlStillFailsThroughXmlParser",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#pdfKeepsExistingOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#officeKeepsExistingOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionOutcomeClassifierTest.java#intentionalReplacementAndControlsDoNotImplyLoss",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/JobQueueTest.java#historicalPartialLedgerReasonRemainsUnchangedAfterReopen",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#plainTextParserOwnsOuterDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#inlineImageHtmlParserOwnsOuterDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#containerWithOuterCharsetAndLossyTextPartIsNotAudited",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#samePdfNamedPdfIsNotAudited",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#cleanAsciiAuditIsNotLossy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#validUtf8AcrossAuditBufferInMemory",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#validUtf8AcrossAuditBufferSpilled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#utf8WithoutBomAfterLongAsciiStartIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#asciiFirstFlatFallbackRetainsSnapshotFactAndIsClean",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/LossyDecodingExtractionTest.java#flatFallbackPreservesCleanTruncation"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "I1: default and blank fallback ids are tika-default-v7, configured limits are tika-config-v7-<chars>-<bytes>. Existing expectations change only those id strings; all extraction, limits, routing, sandbox and format fixture oracles remain unchanged. Main's existing identity checks pin all forms; no migration or re-extraction is introduced. The classifier seam law now includes decoding loss.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "On main, a UTF-8 file with BOM containing invalid byte sequences is stored with replacement characters as a full success, and the app ingestion summary reports it as indexed. These checks safeguard visibility of that lost text while preserving searchable extracted content."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "Audit the complete prepared snapshot strictly with fixed-size decoder buffers and the content encoding reported by the actual whole-file text parser (explicit allowlist), after structured or flat extraction. Carry one decoding-loss warning in the existing artifact/sandbox warnings transport; classify nonblank lossy or truncated content as partial, keep blank output empty, derive independent loss-only/combined/truncation-only reasons for documents and ledger groups while preserving document dropout overrides, retry/recovery and historical data. Add the two readable summary labels. Move all extraction policy id forms from v6 to v7 without automatic re-extraction.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lossy-decoding-policy-id.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
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
  "session": "01a17f7e-d0d8-7e41-84cb-1bce43f86c62",
  "verdict": "accept",
  "digest": "sha256:680c357dcdf42498649e8b0401400e135212de49e53c2789bf6ee6c3b5e439cb"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17fbe-70d2-7620-b69a-3ef23e124d6c",
  "verdict": "accept",
  "digest": "sha256:527d8245cb378bf7d79a55b2b9dfe6ecbe4f4a4ecc03d09e87d5256ce49f27c6"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18006-730e-7911-ac00-0587771c9bdc",
  "verdict": "accept",
  "digest": "sha256:5604f4fa8a00ea4cce471b8ecf55c18ed3fc8a79827e4242f932c6cc65fe12a9"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a18269-09c0-7122-a276-e9d5c0c5d914",
  "verdict": "accept",
  "digest": "sha256:97d31b719319b57651e71a2042432cef716e892e1e07657d520322c987c3a8ee"
}
```
