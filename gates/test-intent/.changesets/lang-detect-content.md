---
schema: test-intent.v1
task: t-20261110-langdetect
author-role: builder
author-session: cb931fec-4aa3-4c83-8112-969f6334cf08:builder-test-intent
---

# Test-intent entries

Content-based language detection. Language labels now come from each file's content instead of
the machine locale. Existing libraries are re-indexed once with corrected labels through the
extraction policy identity tika-default-v10, which #1209 introduced on main and which no release
has shipped yet (v0.3.2 carries v7); this change therefore does not bump the identity again and
only adds its line to the v10 comment. The owner's request (task t-20261110-langdetect) is the
answer "Well then become a production session and proceed accordingly" to the question "a yes
means a product session fixes the language labels, which changes what the app stores and means
re-indexing". The specific choices (the shared v10 re-index, full re-index accepted, the
Tika-backed detector confined to the extraction package, no label when the evidence is too little)
are the orchestrator's, recorded as delegated choices. A repair round added: no label for code and
structured-data kinds, stronger prose evidence for every other kind, the built-in help documents
re-indexed through a help marker bump, and one detector shared per JVM. The two new test files
ContentLanguageWritePathsTest and ContentLanguageMaintenanceTest are exempt (isolated new tests).

```json
{
  "entries": [
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
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "The released v4 help marker (v0.3.2, extraction policy v7) names built-in help stored with the machine locale as language label; the help folder is no watched root, so the maintenance scan never re-reads it. The new v4MarkerReingestsOnceAndWritesV5 check requires a v4 marker to trigger exactly one forced help re-ingest, advance the marker to v5, and suppress another re-ingest on the next start. The historical v2 and v3 checks now expect the v5 marker and are renamed from ...WritesV4 to ...WritesV5; their once-only oracle (one re-ingest, then none) is retained. The eval-mode, stale, unreadable and current-marker checks are unchanged.",
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "cb931fec-4aa3-4c83-8112-969f6334cf08:verifier-3",
  "verdict": "accept",
  "digest": "sha256:140999fcae78aaf6ed6ab24ff45eabefed7d04642e79ac9d331058e28bbd8225",
  "note": "Rebased onto 07769e24e2: the remaining entries hold against the new base. Before-state run on 07769e24e2 with the candidate's checks overlaid: every repaired check fails by assertion for the reason under test (locale tag en-US/de-DE instead of a content label or none; help marker stays v4, or a v4 marker triggers no re-ingest); retainsExistingScriptLabels and the passage-label check pass on both. Non-blocking wording: the prose says three new test files are exempt (two are; ContentLanguageTest is covered by entries), and the help entry says the released v4 marker names help under policy v9 (v0.3.2 shipped v4 with v7); neither changes an oracle."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "cb931fec-4aa3-4c83-8112-969f6334cf08:verifier-4",
  "verdict": "accept",
  "digest": "sha256:9450f908c89fee28557ca1c59ac13eb337cc4d9cc7b9a1f4d2ebb52c589e0961",
  "note": "Rebased onto 8c726a279c (#1213): every entry holds against the new base. Before-state run on 8c726a279c with the candidate's checks overlaid: every repaired check fails by assertion for the reason under test (locale tag en-US instead of a content label or none; help marker stays v4, or a v4 marker triggers no re-ingest); retainsExistingScriptLabels and the passage-label check pass on both. The exempt ContentLanguageWritePathsTest now publishes first indexing through buildDeclaredChunks + replaceTextFamily as JobBatchWriter does and fails on the base by assertion (en-US). The labels evidence names 8c726a279c as its fail-before revision. The corrected wording holds: two exempt files; v0.3.2 shipped help marker v4 with extraction policy tika-default-v7."
}
```
