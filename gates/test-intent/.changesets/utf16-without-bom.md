---
schema: test-intent.v1
task: utf16-without-bom
author-role: builder
author-session: 01a17e0e-1333-7042-9dd7-ebba7af01fa7
---

# BOM-less UTF-16 extraction test intent

The repair checks are the extraction safeguard for the Engine reproduction below. UTF-8 reference
files carry a BOM so the reference encoding is explicit; their extracted output supplies the
production-path oracle, including Tika's existing handling of supplementary characters. Preservation
expectations pin the unchanged baseline, including replacement characters for NULs and the short
CJK result; they do not extend the UTF-16 heuristic.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java"
      ],
      "class": "New",
      "behaviour": "Repair BOM-less UTF-16LE and UTF-16BE Russian, Greek, Ukrainian and Hebrew extraction, matching an explicit UTF-8 control through structured and flat production paths; handle a full detection window ending inside a surrogate pair and report correct text plus partial status under a small configured limit. Focused detector checks pin metadata, exact evidence thresholds, odd trailing bytes, allowed whitespace, a full-window final high surrogate and reset after a read exception.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no data loss",
          "reproduction": "A real Engine on main stored BOM-less UTF-16LE/BE Russian and Greek as windows-1252/ISO-8859-15 garbage with full success, not searchable. The repair checks below safeguard lossless extraction of that text."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "After the HTML-declaration step and before default detectors, only when no charset was decided: mark/read at most 8192 bytes/reset in finally; skip non-markable streams and leading UTF-16/UTF-8 BOMs; ignore an odd final byte. For units=window length/2 and even/odd NUL counts, choose LE iff odd>=4, odd*16>=units, even*8<=odd, or BE with even/odd swapped. Require strict decoding with REPORT, allowing a trailing unpaired high surrogate only in a full 8192-byte window; reject U+0000 and all C0 except TAB/LF/FF/CR. Accepted candidates set DETECTED_ENCODING and this class as ENCODING_DETECTOR; otherwise keep current decoding, including ISO-8859-1 to Windows-1252. Bump default, blank-id fallback and configured policy ids from v2 to v3 and update only the policy-id expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf16-without-bom-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16LeRussianMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16BeRussianMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16LeGreekMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16BeGreekMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16LeUkrainianMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16BeUkrainianMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16LeHebrewMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#bomlessUtf16BeHebrewMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#windowEndingInsideSurrogatePairMatchesUtf8",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredLimitTruncatesCorrectUtf16TextWithPartialStatus",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#acceptedByteOrdersSetMetadataAndRestoreStream",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#oddFinalByteIsIgnored",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#nulEvidenceThresholdsAreInclusive",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#allowedWhitespaceKeepsUtf16",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#fullWindowCanEndWithHighSurrogate",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#readFailureStillResetsStream"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java"
      ],
      "class": "New",
      "behaviour": "Preserve exact existing output for UTF-8 with/without BOM, UTF-16 with BOM, ASCII, Windows-1252, Shift_JIS, GB18030, EUC-KR, KOI8-R, HTML with/without declarations, declared XML, interior U+FEFF, one trailing NUL, sparse NULs, UTF-8/Windows-1252 NUL padding, UTF-32LE, NUL pairs, NULs only beyond byte 8192, and a 100 KB UTF-16 file including its final marker. Pin today's short CJK result (-N‡e). Focused rejection checks preserve fallback and stream bytes for weak NUL evidence, opposite-lane NULs, U+0000, disallowed C0, malformed surrogates, BOMs and unsupported mark.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "After the HTML-declaration step and before default detectors, only when no charset was decided: mark/read at most 8192 bytes/reset in finally; skip non-markable streams and leading UTF-16/UTF-8 BOMs; ignore an odd final byte. For units=window length/2 and even/odd NUL counts, choose LE iff odd>=4, odd*16>=units, even*8<=odd, or BE with even/odd swapped. Require strict decoding with REPORT, allowing a trailing unpaired high surrogate only in a full 8192-byte window; reject U+0000 and all C0 except TAB/LF/FF/CR. Accepted candidates set DETECTED_ENCODING and this class as ENCODING_DETECTOR; otherwise keep current decoding, including ISO-8859-1 to Windows-1252. Bump default, blank-id fallback and configured policy ids from v2 to v3 and update only the policy-id expectations. Preserve the existing extracted text in every listed preservation case; short CJK without enough NULs remains outside this narrow repair.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf16-without-bom-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#utf8WithoutBomKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#utf8WithBomKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#utf16LeWithBomKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#utf16BeWithBomKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#asciiKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#windows1252KeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#shiftJisKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#gb18030KeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#eucKrKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#koi8RKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#interiorByteOrderMarkKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#htmlWithDeclarationKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#htmlWithoutDeclarationKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#xmlWithDeclarationKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#largeUtf16KeepsFinalMarker",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#oneTrailingNulKeepsExistingReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#sparseNulsKeepExistingReplacements",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#nulPaddingAfterUtf8KeepsExistingReplacements",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#nulPaddingAfterWindows1252KeepsExistingReplacements",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#utf32LeKeepsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#nulPairsKeepExistingReplacements",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#nulsOnlyAfterWindowKeepExistingReplacements",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#shortCjkWithoutEnoughNulsKeepsExistingText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#sparseNulsDoNotOverrideDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#oppositeLaneNulsDoNotOverrideDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#nulUnitsDoNotOverrideDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#disallowedC0ControlsDoNotOverrideDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#malformedSurrogatesDoNotOverrideDefault",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#byteOrderMarksStayWithDefaultDetectors",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/OwnedTextEncodingDetectorTest.java#unsupportedMarkSkipsRuleWithoutReading"
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
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "Default and blank-id fallback read tika-default-v3; configured limits read tika-config-v3-<chars>-<bytes>. Existing policy expectations change only from v2 to v3, preserving content, limits, routing and sandbox assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261026-8f2d61 request",
          "delegationScope": "Investigate, design and implement text extraction decoding fixes and their regression safeguards.",
          "choice": "After the HTML-declaration step and before default detectors, only when no charset was decided: mark/read at most 8192 bytes/reset in finally; skip non-markable streams and leading UTF-16/UTF-8 BOMs; ignore an odd final byte. For units=window length/2 and even/odd NUL counts, choose LE iff odd>=4, odd*16>=units, even*8<=odd, or BE with even/odd swapped. Require strict decoding with REPORT, allowing a trailing unpaired high surrogate only in a full 8192-byte window; reject U+0000 and all C0 except TAB/LF/FF/CR. Accepted candidates set DETECTED_ENCODING and this class as ENCODING_DETECTOR; otherwise keep current decoding, including ISO-8859-1 to Windows-1252. Bump default, blank-id fallback and configured policy ids from v2 to v3 and update only the policy-id expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/utf16-without-bom-repair.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsV3",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankPolicyIdentityFallsBackToV3",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsV3"
      ],
      "repaired": true
    }
  ]
}
```
