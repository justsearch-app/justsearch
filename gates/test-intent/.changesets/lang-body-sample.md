---
schema: test-intent.v1
task: t-20261110-langdetect
author-role: builder
author-session: cb931fec-4aa3-4c83-8112-969f6334cf08:builder-round2
---

# Test-intent entries

Round 2 of the content-language label: the label follows a file's main text, not an opening block
in another language. Repaired checks fail on 5358c3952a (origin/main at the start) with assertion
failures and pass on the candidate; preserved checks pass on both (the before-state run of the
preserved checks is not part of the evidence file; the verifier repeats it).

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The preview-based LanguageUtils.resolveLanguage(text) is removed with this change; the same inputs now go through its replacement LanguageUtils.contentLanguage(text, null, null) (no file kind, as before). Every input is shorter than 4,096 characters, where the rule is exactly today's single-window rule. Inputs, expected labels (null, zh, ja, ko, ru, ar, the eight Latin prose labels, the no-label cases), the locale child-JVM check and every other assertion are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Per-window plurality vote (orchestrator, 2026-11-12, after the combined four-window sample failed N3, R1 and R6b on real data, and after the plain vote tied on Regulation 170/2013): a prepared text shorter than 4,096 characters keeps today's rule; a longer one is read as min(8, ceil(length/4096)) windows of 4,096 characters evenly spaced from start to end, edges moved inward to a line break within 256 characters and never splitting a surrogate pair; each window gets today's rule on its own, except that in a text of several windows a window labelled by a non-Latin script rule votes only if that script holds a majority of its letters; the label is the language with strictly the most window votes, else none. Markdown is prepared once and shared with the preview where the writer already prepares it; the VDU writer hands its label to passage regeneration."
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java"
      ],
      "class": "New",
      "behaviour": "A file's label follows its main text, not an opening block: saved web pages extracted from HTML with more than 4,096 characters of English menus and a German, French or Dutch body get de/fr/nl (R1 constructed); a first quarter in another language, or the body concentrated toward the end, does not decide the label at 20,000 to 2,000,000 characters (R3); Chinese and Russian bodies after English menus get zh/ru (R6); Markdown with an English frontmatter description, heading and menus gets the German body's label.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Per-window plurality vote (orchestrator, 2026-11-12, after the combined four-window sample failed N3, R1 and R6b on real data, and after the plain vote tied on Regulation 170/2013): a prepared text shorter than 4,096 characters keeps today's rule; a longer one is read as min(8, ceil(length/4096)) windows of 4,096 characters evenly spaced from start to end, edges moved inward to a line break within 256 characters and never splitting a surrogate pair; each window gets today's rule on its own, except that in a text of several windows a window labelled by a non-Latin script rule votes only if that script holds a majority of its letters; the label is the language with strictly the most window votes, else none. Markdown is prepared once and shared with the preview where the writer already prepares it; the VDU writer hands its label to passage regeneration."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r1SavedWebPageWithEnglishMenusGetsTheBodyLanguage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r3AFirstQuarterInAnotherLanguageDoesNotDecideTheLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r3TheBodyLanguageWinsWhenItIsConcentratedTowardTheEnd",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r6NonLatinBodyWithEnglishMenusGetsTheBodyLanguage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#markdownWithAnEnglishFrontmatterAndHeadingGetsTheBodyLanguage"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java"
      ],
      "class": "New",
      "behaviour": "Preserved and neighbouring behaviour, passing on main and on the candidate: an even two-language split gets one of the two or none, never a third (R3); an English body with a short Chinese block under a tenth of its letters stays en at start, middle or end (R6); a German or English body with a Bulgarian, Greek or EU-style multilingual annex keeps its label; a Chinese technical text with many English terms gets zh or none, never a Latin label; short mixed-script files keep main's labels (en, ru, zh, en, en, zh, ja); long monolingual Latin prose in en/de/fr/es/it/nl/pt/cy and long zh/ru/ja (kana)/ko/ar/hi/el text keep their labels up to 500,000 characters (N1, N2); long whitespace, numeric, code-kind, CSV and data-like files stay unlabelled (N1 S2); single-line text dense with supplementary characters keeps its label.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Per-window plurality vote (orchestrator, 2026-11-12, after the combined four-window sample failed N3, R1 and R6b on real data, and after the plain vote tied on Regulation 170/2013): a prepared text shorter than 4,096 characters keeps today's rule; a longer one is read as min(8, ceil(length/4096)) windows of 4,096 characters evenly spaced from start to end, edges moved inward to a line break within 256 characters and never splitting a surrogate pair; each window gets today's rule on its own, except that in a text of several windows a window labelled by a non-Latin script rule votes only if that script holds a majority of its letters; the label is the language with strictly the most window votes, else none. Markdown is prepared once and shared with the preview where the writer already prepares it; the VDU writer hands its label to passage regeneration."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/lang-body-sample-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r3AnEvenSplitGetsOneOfTheTwoOrNone",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r6EnglishBodyWithAShortNonLatinBlockStaysEnglish",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r6AGreekOrBulgarianAnnexDoesNotOutvoteTheBody",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#r6ChineseTechnicalTextWithManyEnglishTermsGetsNoLatinLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#n2ShortMixedScriptFilesKeepTheirLabels",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#n1LongMonolingualLatinProseKeepsItsLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#n2LongMonolingualNonLatinTextKeepsItsScriptLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#n1LongFilesWithoutProseEvidenceStayUnlabelled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodySampleTest.java#denseSupplementaryCharactersAtWindowBoundariesKeepTheLabel"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodyWritePathsTest.java"
      ],
      "class": "New",
      "behaviour": "Every write path labels the finally stored content from its body when the opening is more than 4,096 characters of English menus: first indexing, file update, forced re-index and VDU replacement for plain text and Markdown, with passages carrying the parent's label and the stored preview still the text's opening; standalone passage regeneration labels passages from the parent's stored content and leaves the parent label; label transitions de, fr, nl, de, none hold across update, forced re-index, VDU and reopen with obsolete passages gone; an incomplete-coverage append labels the combined stored text and VDU empty, failed and rejected outcomes keep it (R4, N4).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Per-window plurality vote (orchestrator, 2026-11-12, after the combined four-window sample failed N3, R1 and R6b on real data, and after the plain vote tied on Regulation 170/2013): a prepared text shorter than 4,096 characters keeps today's rule; a longer one is read as min(8, ceil(length/4096)) windows of 4,096 characters evenly spaced from start to end, edges moved inward to a line break within 256 characters and never splitting a surrogate pair; each window gets today's rule on its own, except that in a text of several windows a window labelled by a non-Latin script rule votes only if that script holds a majority of its letters; the label is the language with strictly the most window votes, else none. Markdown is prepared once and shared with the preview where the writer already prepares it; the VDU writer hands its label to passage regeneration."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Every write path (first indexing, file update, forced re-index, VDU accepted replacement and incomplete-coverage append, standalone passage regeneration) labels the finally stored content with the same rule (LanguageUtils.contentLanguage); VDU empty, failed or rejected outcomes keep the label; standalone regeneration labels passages only and does not repair the parent label. No policy bump: policy v10 is unreleased. A background rebuild's serving index may keep the previous label until cutover (R7, eventual correction at cutover)."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodyWritePathsTest.java#r4EveryWriterLabelsTheBodyNotTheOpening",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodyWritePathsTest.java#r4StandaloneRegenerationLabelsPassagesFromStoredContentAndLeavesTheParentLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodyWritePathsTest.java#r4LabelTransitionsHoldAcrossUpdateForcedReindexVduAndReopen",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageBodyWritePathsTest.java#r4IncompleteCoverageLabelsTheCombinedTextAndNonReplacementKeepsIt"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ],
      "class": "New",
      "behaviour": "A VDU replacement whose English opening precedes a German body stores the label de on the parent, before and after reopen, while its preview stays the opening (R4, N4).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Per-window plurality vote (orchestrator, 2026-11-12, after the combined four-window sample failed N3, R1 and R6b on real data, and after the plain vote tied on Regulation 170/2013): a prepared text shorter than 4,096 characters keeps today's rule; a longer one is read as min(8, ceil(length/4096)) windows of 4,096 characters evenly spaced from start to end, edges moved inward to a line break within 256 characters and never splitting a surrogate pair; each window gets today's rule on its own, except that in a text of several windows a window labelled by a non-Latin script rule votes only if that script holds a majority of its letters; the label is the language with strictly the most window votes, else none. Markdown is prepared once and shared with the preview where the writer already prepares it; the VDU writer hands its label to passage regeneration."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Replace VduResultWriterCoherenceTest's preview-derived language expectation (it called the old resolveLanguage on the computed preview) with fixed expected labels: the existing English fixture LONG is en, SHORT and the smaller replacement have no label, and a new replacement whose 4,700-character English opening precedes a German body is de; the stored preview is asserted as the first 4,096 characters of the stripped plain text, independent of the implementation."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java#vc1bReplacementLabelsItsBodyNotItsOpeningAndKeepsTheOpeningAsPreview"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ],
      "class": "New",
      "behaviour": "The coherence assertions after every replacement no longer derive the expected label and preview by calling the implementation: the label is fixed per fixture (LONG en; SHORT and the smaller replacement none) and the preview is the first 4,096 characters of the stripped replacement text. Fixtures, counts, chunk, reason, ranking and reopen assertions are unchanged; the whole class passes on main and on the candidate except the new vc1b check.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "yes you should fix that.",
          "location": "task t-20261110-langdetect agreement"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "yes you should fix that.",
          "delegationLocation": "task t-20261110-langdetect agreement",
          "delegationScope": "The answer to the question \"should I fix the app so it judges a file's language from the main text rather than its opening menus?\": how the label is estimated and on which write paths, within the round-2 scenarios settled 2026-11-12 (language label only; stored preview, search and matching unchanged).",
          "label": "agent choice",
          "choice": "Replace VduResultWriterCoherenceTest's preview-derived language expectation (it called the old resolveLanguage on the computed preview) with fixed expected labels: the existing English fixture LONG is en, SHORT and the smaller replacement have no label, and a new replacement whose 4,700-character English opening precedes a German body is de; the stored preview is asserted as the first 4,096 characters of the stripped plain text, independent of the implementation."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/lang-body-sample-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduResultWriterCoherenceTest.java"
      ]
    }
  ]
}
```

## Acceptance records
