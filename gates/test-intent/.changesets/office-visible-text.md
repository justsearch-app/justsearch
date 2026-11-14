---
schema: test-intent.v1
task: t-20261114-wordtext
author-role: builder
author-session: f5154131-58b2-4c0e-8a50-dc0fb1bf7d4f
---

# Test-intent entries

The package preview picture of an office file is no longer parsed or counted as an embedded
resource (repaired). The format-capability oracle for the POI-generated PPTX, whose only embedded
part is that preview, changes from one embedded resource at depth one to none.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json",
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "Repaired: the package preview picture (docProps/thumbnail.*, Tika resource type THUMBNAIL) of an office package is neither parsed nor counted against the embedded-resource allowance, so its text cannot be stored a second time. For PPTX_WITH_NOTES, whose only embedded part is the preview that POI's template adds, the oracle's embeddedResourceCount and maxEmbeddedDepth change from 1 to 0 in both the JSON row and the typed Java oracle; every other field of that row, including the exact annotated text, and every other format row stay unchanged. The JSON-to-typed-oracle projection check passes before and after (preserved).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "For now pick any other issues you think are meaningful for a user and fix them",
          "delegationLocation": "task t-20261114-wordtext request",
          "delegationScope": "pick issues meaningful for a user and fix them",
          "choice": "Stop storing the text of an office file's package preview picture, which repeats page one (scenario S1), and do not spool or count a skipped preview as an embedded resource (scenario S10).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/office-visible-text-preview.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
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
  "session": "stfi7akg",
  "verdict": "accept",
  "digest": "sha256:2cb9c90a9d4ebe2dbb4fb28c1042cbabe83e84fff3a160245712874201cab477",
  "note": "The S1/S10 delegated choice requires skipping the package preview without counting it; changing only the PPTX preview count and depth from one to zero follows that source. Cited evidence records PPTX assertion failure before and all capability cases passing after; projection preservation passed in both."
}
```
