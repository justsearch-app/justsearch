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

Release v0.3.3 shipped extraction policy tika-default-v11 and help marker v5 without this change,
so the generated default and configured policy identities advance from v11 to v12 and the help
marker from v5 to v6: installs that already run v0.3.3 re-extract their files and bundled help once.
The identity entries follow the #1229 precedent (`New`, expected outcomes change; older identities
used as "old" fixtures keep their values; every non-policy expectation is unchanged). Their
repaired checks fail on origin/main by assertion (v11 or v5 where v12 or v6 is expected, or no
re-ingest for a v5 marker) and pass on the candidate (evidence lang-body-sample-bump.json).

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
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Advance generated null/blank fallback, default, null-worker and configured policy identity expectations from v11 to v12. Preserve all limits and explicit caller-owned identity assertions.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseCurrentGeneration"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java"
      ],
      "class": "New",
      "behaviour": "Advance generated default/configured identity expectations and current-generation comments from v11 to v12. Keep deterministic default-equivalent and nonpositive fallback identities, distinct configured chars/bytes, numeric limits, office cap, clamp and factory independence assertions.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#differingLimitsGetADistinctIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#nonPositiveLimitsFallBack",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultsRemainStable"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only generated default, null/blank fallback and configured policy identity expectations from v11 to v12. Preserve all UTF-16 text, metadata, status and truncation expectations.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsCurrentGeneration"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only expected default/configured artifact identities from v11 to v12 across real child reuse/replacement and Hebrew formats/lengths. Preserve exact text, status, child PID, spawn/restart and all non-policy expectations.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected child artifact identity from tika-default-v11 to tika-default-v12. Preserve every encoding attribution, exact structured/flat/policy/child text, SUCCESS_FULL and child spawn/restart expectation.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the in-process current policy expectation from tika-default-v11 to tika-default-v12. Preserve empty-child-command acceptance solely for IN_PROCESS and rejection for PROCESS/AUTO, with all routing expectations.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the real extraction-child artifact identity expectation from tika-default-v11 to tika-default-v12. Preserve the child content assertion and every protocol, security, lifecycle and request-budget expectation.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only current fresh/indexed/artifact/successful-ledger identities and the current-policy unchanged-family seed from v11 to v12. Historical v5 documents and ledger events keep v5. Preserve text, metadata, source hash, stable UID, chunk revisions, family reuse and restart expectations.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected newly extracted parent identity from tika-default-v11 to tika-default-v12. Preserve older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced/ordinary work and reopen expectations.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java"
      ],
      "class": "New",
      "behaviour": "Advance only the typed format-capability oracle policyId from tika-default-v11 to tika-default-v12. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v12; Java/JSON projections must still agree.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json"
      ],
      "class": "New",
      "behaviour": "Advance only format-capability JSON policyId expectations from tika-default-v11 to tika-default-v12. Preserve every recipe hash, literal annotated text, required/absent marker, MIME/parser identity, embedded/structure count and known-gap classification. Real production extraction must return v12; Java/JSON projections must still agree.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only current default/configured maintenance target policy expectations from v11 to v12. Historical v8 policies remain v8. Preserve case-distinct PDF edges, repeated-edge removal, source identity, successful-history provenance, queued repair, complete-family reuse and reopen assertions.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java#maintenanceRepairsSuccessfulOldPolicyAndReusesCompleteFamilyAfterReopen"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only expected default/configured child artifact identities from v11 to v12. Preserve six physical PDF pages, exact case-distinct headers/footers, removal of exact repeated edges, direct/child text equality, structured parser identity, SUCCESS_FULL, no truncation and actual child reuse without restart.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#caseDistinctPdfEdgesSurviveRealStructuredParserAndReusedChild"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "The v5 help marker released in v0.3.3 names built-in help stored under extraction policy v11, whose language labels came from the text's opening; the help folder is no watched root, so only the marker re-reads it. The new v5MarkerReingestsOnceAndWritesV6 check requires a v5 marker to trigger exactly one forced help re-ingest, advance the marker to v6, and suppress another re-ingest on the next start. The historical v2, v3 and v4 checks now expect the v6 marker and are renamed from ...WritesV5 to ...WritesV6; their once-only oracle (one re-ingest, then none) is retained. The stale, unreadable and current-marker checks are unchanged.",
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
          "choice": "v0.3.3 shipped v11 without this change; bump to v12 and help marker v6 so released installs re-extract once"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/lang-body-sample-bump.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v5MarkerReingestsOnceAndWritesV6",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v2MarkerReingestsOnceAndWritesV6",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v3MarkerReingestsOnceAndWritesV6",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v4MarkerReingestsOnceAndWritesV6"
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
  "session": "cb931fec-4aa3-4c83-8112-969f6334cf08:verifier-5",
  "verdict": "accept",
  "digest": "sha256:32abfead46d0e306cd6232666b7004f9bd41609d057571c3e4093e719493d737",
  "note": "Verified on 421a1dd9ec against base 5358c3952a. Before-state run of the candidate's three language test classes overlaid on 5358c3952a: every repaired check fails by assertion for the reason under test (opening language en, or the opening's language, instead of the body's: de/fr/nl/zh/ru/en/nl/cy; parent and passages en instead of de/nl); every preserved check passes on 5358c3952a (ContentLanguageBodySampleTest 40 preserved cases; VduResultWriterCoherenceTest 21 of 22, only vc1b fails) and all pass on the candidate. Adaptation entries keep inputs and expected labels; every input is under 4,096 characters, where contentLanguage applies the old single-window rule. The exempt ContentLanguageBodyWritePathsTest changes no global state, is read by no other test (full module suites pass), and its four checks fail by assertion before. Non-blocking: n2ShortMixedScriptFilesKeepTheirLabels pins two known short-text mislabels (Japanese kanji text as zh); its warrant is the delegated 'shorter than 4,096 characters keeps today's rule', not N2, which does not require keeping mistakes. The choice text says Markdown is prepared once and shared with the preview; the indexing writer still prepares it twice, as on main (measured: no added allocation)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "cb931fec-4aa3-4c83-8112-969f6334cf08:verifier-6",
  "verdict": "accept",
  "digest": "sha256:e4771d002fa0fad24f2bb7916246a5315297c9a2ec34b75283d7128dcbcb2d5a",
  "note": "Verified on aa40a3c38b against base 36738eb4d6 (merge-base with origin/main). Merge commit 12dff36276 equals the automatic merge tree; the six round-2 entries and their items are unchanged from the accepted 2d104e6486 copy and their before content is the same at the new base. Bump entries: the candidate's 14 bump-entry files overlaid on 36738eb4d6 fail 160 cases, every one an AssertionFailedError expecting tika-default-v12 / tika-config-v12-* / v6 (or one v5 re-ingest) and getting v11 / v5 (or none); the other 165 cases of those classes pass on the base, so only current-identity expectations moved; historical v3/v5/v7/v8/v9 fixtures are unchanged. All named checks pass on the candidate in the full module run. The help v4 check keeps its once-only oracle; v5MarkerReingestsOnceAndWritesV6 mirrors it. Real-system upgrade from a v0.3.3 dist confirms one v12 re-extraction per source, marker v6 and no repeat. Non-blocking: the bump entries' delegationScope text names only label estimation and write paths; the identity bump is within the agreed question, which stated every file is re-read once after the update. Entry 3's quoted round-2 choice still says 'No policy bump: policy v10 is unreleased', now superseded by the v12 choice; its expectations do not depend on it."
}
```
