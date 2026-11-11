---
schema: test-intent.v1
task: t-20261110-langdetect
author-role: builder
author-session: cb931fec-4aa3-4c83-8112-969f6334cf08:builder-test-intent
---

# Test-intent entries

Content-based language detection. Language labels now come from each file's content instead of
the machine locale, and the extraction policy identity moves from v9 to v10 so existing libraries
are re-indexed once with corrected labels. The owner's request (task t-20261110-langdetect) is the
answer "Well then become a production session and proceed accordingly" to the question "a yes
means a product session fixes the language labels, which changes what the app stores and means
re-indexing". The specific choices (v10 as the re-index mechanism, full re-index accepted, the
Tika-backed detector confined to the extraction package, no label when the evidence is too little)
are the orchestrator's, recorded as delegated choices. A repair round added: no label for code and
structured-data kinds, stronger prose evidence for every other kind, the built-in help documents
re-indexed on the policy change, and one detector shared per JVM.

Every v9-to-v10 entry advances only literal current-generation identities; historical seeds
(v5, v8) and all other assertions stay. Each such check must fail on the base (origin/main) because
the base still produces the v9 identity. The three new test files are exempt (isolated new tests).

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the current-generation policy identity from tika-default-v9 to tika-default-v10: the seed of the already-current document (old=false) and its recorded SUCCESS_FULL ledger event, the expected stored identity of that seed, the expected identity after ordinary or forced re-extraction and after another restart (restartScenario and assertCorrect), and the policy of the expected SUCCESS_FULL ledger event. The historical v5 mojibake seed and event stay v5; text, metadata, source hash, stable UID, chunk revisions and restart/preservation assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/AsciiFirstUtf8QueuedRestartTest.java#newlyIndexedAsciiFirstUtf8SurvivesRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected current identity (currentPolicy) from tika-default-v9 / tika-config-v9-100000-1000000 to tika-default-v10 / tika-config-v10-100000-1000000, which the fresh artifact, the repaired document family and the SUCCESS_FULL ledger event must carry. The historical v8 seed (oldPolicy), page-edge text, parser identity, document UID and family-reuse-after-reopen assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/CaseDistinctPageEdgesQueuedRestartTest.java#maintenanceRepairsSuccessfulOldPolicyAndReusesCompleteFamilyAfterReopen"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected policy of the newly extracted parent from tika-default-v9 to tika-default-v10. The older source/provenance, literal text, source/content revisions, preview, chunk replacement, forced and ordinary work and reopen assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#forcedReindexRepairsHebrewAndSurvivesAnotherRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/Utf8QueuedRestartTest.java#ordinaryQueuedWorkRepairsOlderPolicyAndSurvivesAnotherRestart"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/system-tests/gradle.lockfile"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The Tika-backed detector (org.apache.tika:tika-langdetect-optimaize at the project's Tika version, 3.2.3) is a dependency of worker-services and used only inside its extraction package.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Record, as the project's dependency locking writes it, what worker-services' new tika-langdetect-optimaize dependency adds to the system-tests test classpaths, which include worker-services and indexer-worker.",
        "callersAndStateOwners": "Gradle reads the lockfile to enforce resolution of the named configurations; the changed lines name testRuntimeClasspath, integrationTestRuntimeClasspath and systemTestRuntimeClasspath. No test source, oracle, task, filter or build script of system-tests changes. No saved state is involved.",
        "indirectBehaviours": "Three coordinates join those runtime classpaths (org.apache.tika:tika-langdetect-optimaize:3.2.3, com.optimaize.languagedetector:language-detector:0.6, net.arnx:jsonic:1.2.11), and org.jetbrains:annotations moves from 13.0 to 26.0.2 on them: dependencyInsight shows tika-langdetect-optimaize 3.2.3 requests 26.0.2 and conflict resolution lifts kotlin-stdlib's 13.0 request to it. These jars carry the language profiles and annotation types; no line selects or configures tests.",
        "evidence": "On this candidate, ./gradlew :modules:system-tests:dependencies --configuration <c> -PskipWebBuild=true for c = testRuntimeClasspath, integrationTestRuntimeClasspath and systemTestRuntimeClasspath each ended BUILD SUCCESSFUL with no FAILED or lock-state line, and each tree shows the three added modules and annotations 13.0 -> 26.0.2. Dependency locking fails resolution when a configuration resolves differently from its lockfile lines; an --offline dependencyInsight run before the jar was cached reported \"Dependency lock state out of date\", so the check can fail. The same four coordinate changes appear in the worker-services and indexer-worker lockfiles of this change. git diff origin/main -- modules/system-tests/gradle.lockfile shows exactly these four lines.",
        "unresolvedRisks": "The system-tests suites themselves were not run locally for this record; the hosted system-tests tier runs them on the PR head. A class-path change of annotation and language-profile jars is not expected to change their outcome."
      }
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected child extraction policy from tika-default-v9 to tika-default-v10. Charset detection and attribution, exact structured/flat/policy/child text, SUCCESS_FULL and the single-spawn/no-restart assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8ExtractionTest.java#asciiFirstTextAcrossFormatsAndRoutes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java"
      ],
      "class": "New",
      "behaviour": "Advance the null/blank-identity fallback, defaults(), fromWorkerLimits(null) and the configured identity from v9 to v10 (tika-default-v10, tika-config-v10-1000-2000); the limit values and every other assertion are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#blankOrNullIdentityUsesCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AsciiFirstUtf8PolicyTest.java#defaultAndConfiguredIdentitiesUseCurrentGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected in-process extraction policy from tika-default-v9 to tika-default-v10. All routing assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java#modeSwitchSelectsTheSandbox"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected policy of the real extraction child's artifact from tika-default-v9 to tika-default-v10. The file-content, protocol, security, lifecycle and request-budget assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#realChildExtractsFileContent"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected artifact policy under the default and configured built-in policies from tika-default-v9 / tika-config-v9-100000-1000000 to tika-default-v10 / tika-config-v10-100000-1000000. Page count, case-edge text, parser identity, status, truncation, direct/child agreement and child-reuse assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/StructuredExtractionIntegrationTest.java#caseDistinctPdfEdgesSurviveRealStructuredParserAndReusedChild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the literal current identities (tika-default-v10, tika-config-v10-1000000-5000000) and the two explanatory comments naming the default identity from v9 to v10. Default-equivalent, non-positive and differing limits keep their identity rules; every numeric expectation, the office cap, the clamp and factory independence are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#differingLimitsGetADistinctIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#nonPositiveLimitsFallBack",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultsRemainStable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the default, null/blank-fallback and configured identity assertions from v9 to v10 (tika-default-v10, tika-config-v10-48-1024). All decoding, fallback, exact text and limit assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#defaultPolicyIdentityIsCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#blankOrNullPolicyIdentityFallsBackToCurrentGeneration",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf16WithoutBomExtractionTest.java#configuredPolicyIdentityIsCurrentGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java"
      ],
      "class": "New",
      "behaviour": "Advance only the expected artifact policy from v9 to v10: the default/configured expectedPolicy across persistent-child reuse and replacement, and the default policy across every Hebrew format and length. Literal Hebrew text, status, format/length and child PID reuse/replacement assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childReuseAndReplacementKeepUtf8TextAndPolicyIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/Utf8WithoutBomExtractionTest.java#childExtractsAllHebrewFormatsAndLengths"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "New",
      "behaviour": "DetectLanguageTests now observes the label the indexer stores, LanguageUtils.resolveLanguage, instead of IndexingDocumentOps.detectLanguage, which this change removes (the indexer's parent metadata derivation, deriveParentMetadataWithTokenCounter, now calls LanguageUtils.resolveLanguage). At the base, resolveLanguage returned the machine locale tag for null, empty and Latin-only text. nullReturnsNull, emptyReturnsNull and latinReturnsNull now expect no label (null) from it; latinReturnsNull's display name changes from \"Latin-only text returns null (uses default)\" to \"Short Latin text has insufficient evidence\" (its input has fewer than 50 Latin letters). The five script cases (zh, ja, ko, ru, ar) keep their inputs and expected codes; only their callee changes. Nothing else in the file changes. Gradle's IndexingLoopTest.nullReturnsNull filter also selects the unchanged NormalizeMimeBaseTests.nullReturnsNull (same method name in another nested class), so that check records two cases with the display name \"null returns null\"; the unchanged one passes on both arms.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "Language labels come from content: the existing non-Latin script rules stay; Latin prose is classified by the Tika-backed detector, confined to the extraction package; when the evidence is too little (no content, or fewer than 50 Latin letters) there is no label; the machine locale is never consulted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content-labels.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#nullReturnsNull",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#emptyReturnsNull",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java#latinReturnsNull"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/testFixtures/java/io/justsearch/indexerworker/fixtures/FormatCapabilityExpectedState.java",
        "modules/worker-services/src/test/resources/fixtures/format-capability/expected-state.v1.json"
      ],
      "class": "New",
      "behaviour": "The typed oracle's DEFAULT_POLICY and the ten policyId values of its checked JSON projection advance together from tika-default-v9 to tika-default-v10. Every recipe hash, annotated text, marker, MIME/parser identity, embedded and structure count and known-gap classification is unchanged. productionExtractorMatchesVersionedCapabilityOracle must observe the v10 artifact; the fail-before run overlays both fixture files onto the before-state. checkedJsonProjectionMatchesEveryFieldOfTheTypedJavaOracle (Java/JSON agreement) and the app-engine EngineFormatCapabilityMatrixTest also read policyId from this oracle; they were not run for this evidence.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The extraction policy identity moves from tika-default-v9 to tika-default-v10 (and the configured form from tika-config-v9-<chars>-<bytes> to tika-config-v10-<chars>-<bytes>, limits unchanged) as the re-index mechanism: the existing maintenance path re-extracts every item stored under an older identity once, so existing libraries get content-derived labels. The full re-index this costs is accepted.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenFormatCapabilityTest.java#productionExtractorMatchesVersionedCapabilityOracle"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "The released v4 help marker names built-in help stored under extraction policy v9, with the machine locale as language label; the help folder is no watched root, so the maintenance scan never re-reads it. The new v4MarkerReingestsOnceAndWritesV5 check requires a v4 marker to trigger exactly one forced help re-ingest, advance the marker to v5, and suppress another re-ingest on the next start. The historical v2 and v3 checks now expect the v5 marker and are renamed from ...WritesV4 to ...WritesV5; their once-only oracle (one re-ingest, then none) is retained. The eval-mode, stale, unreadable and current-marker checks are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The built-in help documents are re-indexed on the policy change: the help version marker moves from v4 to v5, so an upgraded install re-reads its bundled help once under extraction policy v10 and never again after that succeeds.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content-help.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v4MarkerReingestsOnceAndWritesV5",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v2MarkerReingestsOnceAndWritesV5",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v3MarkerReingestsOnceAndWritesV5"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java",
        "modules/worker-services/src/test/resources/language-probes/config.json",
        "modules/worker-services/src/test/resources/language-probes/config.yaml",
        "modules/worker-services/src/test/resources/language-probes/main.go",
        "modules/worker-services/src/test/resources/language-probes/people.csv",
        "modules/worker-services/src/test/resources/language-probes/query.sql",
        "modules/worker-services/src/test/resources/language-probes/script.py",
        "modules/worker-services/src/test/resources/language-probes/script.sh",
        "modules/worker-services/src/test/resources/language-probes/test-summary.json"
      ],
      "class": "New",
      "behaviour": "Latin prose is labelled by content (en, de, fr, es, it, nl, pt, cy) with the same labels in separate JVM starts under an English and a German default locale; empty, blank, very short, numeric, code-only and unsupported-script text has no label, never the locale. The eight probe resources are code and data files that an n-gram model labelled as confident prose on the running app (Python, shell, SQL, Go, CSV, YAML, JSON configuration and a JSON test summary labelled et): indexed under their own file kind and MIME type they have no label, and the same text indexed as plain text (a prose kind) has no label either. English prose with a few configuration lines between its paragraphs keeps en.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "No language label when the evidence is too little; no label for code and structured-data kinds (file kind code, or a CSV, TSV, JSON, YAML, TOML or SQL MIME type); every other kind needs prose evidence before the detector is asked: lines of at least four tokens, three quarters of them words, at least twelve such words holding at least a quarter of the sample's letters, and a detector probability of at least 0.95. No per-language rules.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content-codedata.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#labelsLatinProseInSeparateJvmStarts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#insufficientOrNonProseEvidenceHasNoLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#codeAndStructuredDataKindsHaveNoLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#dataLikeTextInAProseKindHasNoLabel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#proseKeepsItsLabelBesideAFewDataLines"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java"
      ],
      "class": "New",
      "behaviour": "Representative Chinese, Russian, Japanese (kana), Korean, Arabic, Hindi and Greek text keeps the label the script rules give today (zh, ru, ja, ko, ar, hi, el). This preservation check passes before and after the change.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "Non-Latin scripts keep today's script-based labels; only Latin-script text is newly labelled by a statistical detector.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content-preserved.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ContentLanguageTest.java#retainsExistingScriptLabels"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceChunkRegenerationTest.java"
      ],
      "class": "New",
      "behaviour": "After a VDU text replacement regenerates the passages of a PDF, every passage carries the language label stored on its parent, including no label, instead of any non-null label. The replacement text (repeated 'lorem ipsum' lines with an emoji) is not prose, so parent and passages now have no label; the earlier non-null oracle only held because the machine locale filled every label. Every other expectation of the test (offsets, ordinals, UIDs, content slices, MIME, file kind, switch-buffer replay) is unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "A file's passages carry the same label as the file on every write path, also after VDU replacement; too little prose evidence gives no label, never the locale.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/lang-detect-content-preserved.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceChunkRegenerationTest.java#updateVduResultRegeneratesChunksWithOffsetsAndMetadata"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/build.gradle.kts",
        "modules/indexer-worker/build.gradle.kts"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Well then become a production session and proceed accordingly",
          "location": "task t-20261110-langdetect request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Well then become a production session and proceed accordingly",
          "delegationLocation": "task t-20261110-langdetect request",
          "delegationScope": "The owner's answer to the question \"a yes means a product session fixes the language labels, which changes what the app stores and means re-indexing\": language labels derived from file content instead of the machine locale, the stored labels changing accordingly, and existing libraries re-indexed.",
          "choice": "The Tika-backed detector is shared per JVM and keeps all bundled language profiles; the test forks of the modules whose suites load it get more heap rather than fewer profiles.",
          "label": "agent choice"
        }
      ],
      "assessment": {
        "purpose": "Give the unit-test forks of worker-services (512m) and indexer-worker (640m) room for the content language detector, whose Tika language models stay about 96 MiB of heap for the life of the JVM once any test indexes Latin text. On the convention's 384m both suites' forks ran out of memory on the earlier candidate.",
        "callersAndStateOwners": "Only the `test` and `ciUnitTest*` Test tasks of these two modules read the setting (the existing matching block). No other module, task, tag, filter, fork count, timeout or test source changes; the JvmBaseConventionsPlugin default stays 384m.",
        "indirectBehaviours": "No test is selected, skipped or ordered differently. Each module still runs two forks, so peak test heap per module rises from about 768 MB to about 1 GB (worker-services) and 1.25 GB (indexer-worker) on a developer machine and CI runner. The worker-services comment on fork memory now states the new total.",
        "evidence": "Retained heap of one detector measured in a standalone JVM with all profiles: 95.5 MiB after the first detection (5 GCs before and after). Full suites with --rerun --no-build-cache through js-gradle-slot and GC logging: worker-services at 512m, 4428 tests, 0 failed, 18 skipped, no OutOfMemoryError, at most 297 MB after its one full GC; indexer-worker at 512m, 2728 tests, 0 failed, 31 skipped, but one fork needed seven full GCs late in the suite and kept up to 426 MB after them; indexer-worker at 640m, 2728 tests, 0 failed, 31 skipped, no full GC, at most 476 MB after any collection. The earlier candidate at 384m: indexer-worker forks died with OutOfMemoryError in 2 of 2 runs and worker-services in 1 of 2 (independent verifier).",
        "unresolvedRisks": "Headroom was measured on Linux only, with this machine's test concurrency. Windows and CI runners were not run. Most of the indexer-worker fork's late live heap accumulates across tests independently of this change (the baseline suite passed at 384m without the detector); it was not attributed further."
      }
    }
  ]
}
```

## Acceptance records
