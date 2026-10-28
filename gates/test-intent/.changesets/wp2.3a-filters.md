---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp2.3a-filters-builder
---

# Test-intent entries

Agent choices under the quoted delegation; not owner-agreed. Evidence and independent acceptance are pending the orchestrator. No run-evidence execution was performed by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: crossedCorpusAndSaturatedChunksUseParentMetadataBeforeBudget, parentBoundsIntersectSearchFacetsBeforeChunkCandidates, inclusiveOneSidedEqualAndLargeBoundsDistinguishUnknownAndKnownZero, knownEpochZeroMatchesAnUpperDateBoundWhileUnknownDoesNot, sourceBytesAndFilesystemMtimeStayDistinctFromTextAndPublicationTime, folderPrefixesAreLiteralDirectories, reopensLegacyFieldIndexThenParentUpdateChangesChunkEligibility",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#crossedCorpusAndSaturatedChunksUseParentMetadataBeforeBudget",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#parentBoundsIntersectSearchFacetsBeforeChunkCandidates",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#inclusiveOneSidedEqualAndLargeBoundsDistinguishUnknownAndKnownZero",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#knownEpochZeroMatchesAnUpperDateBoundWhileUnknownDoesNot",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#sourceBytesAndFilesystemMtimeStayDistinctFromTextAndPublicationTime",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#folderPrefixesAreLiteralDirectories",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#reopensLegacyFieldIndexThenParentUpdateChangesChunkEligibility"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/SearchRestrictionsTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: exactMaximumNullAndZeroSemantics, decimalParsingNeverRoundsAnInvalidBoundIntoTheDomain",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-domain-validation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/SearchRestrictionsTest.java#exactMaximumNullAndZeroSemantics",
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/SearchRestrictionsTest.java#decimalParsingNeverRoundsAnInvalidBoundIntoTheDomain"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: richPortFailureAndHeadWaitFailureNeverFetchChosenFiles, overflowSurvivesHeadAsTypedFailureWithoutModelInput, restrictedEmptyIsNoContentAndSuccessfulFulltextIsKept, reportedFallbackFailureIsNeverPresentedAsEmptyRestrictedContent, allRestrictionsReachTheRichPortForOpenAndExplicitIdRetrieval, actualConversationDispatchEndsOnTypedRestrictedFailureWithoutGeneration",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#richPortFailureAndHeadWaitFailureNeverFetchChosenFiles",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#overflowSurvivesHeadAsTypedFailureWithoutModelInput",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#restrictedEmptyIsNoContentAndSuccessfulFulltextIsKept",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#reportedFallbackFailureIsNeverPresentedAsEmptyRestrictedContent",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#allRestrictionsReachTheRichPortForOpenAndExplicitIdRetrieval",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#actualConversationDispatchEndsOnTypedRestrictedFailureWithoutGeneration"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteRestrictionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: preSearchAndDiscoveredIdCopyPreserveRestrictionsOnTheWorkerRequest, preSearchAndRichPortFailuresDoNotBroadenRestrictedRetrieval",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteRestrictionFailureTest.java#preSearchAndDiscoveredIdCopyPreserveRestrictionsOnTheWorkerRequest",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteRestrictionFailureTest.java#preSearchAndRichPortFailuresDoNotBroadenRestrictedRetrieval"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RestrictionValidationEntryTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: searchAndAskRejectTheExactValidationMatrixBeforeAdmission",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RestrictionValidationEntryTest.java#searchAndAskRejectTheExactValidationMatrixBeforeAdmission"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: chunksChunklessUnionAndFullDocumentPathsIntersectAllBounds, fulltextFallbackAndEmptyIntersectionDoNotBroaden, knownZeroMatchesAndUnknownSizeDoesNot, exactlyTenThousandSucceedsOverflowIsTypedAndExplicitIdsAvoidIt",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java#chunksChunklessUnionAndFullDocumentPathsIntersectAllBounds",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java#fulltextFallbackAndEmptyIntersectionDoNotBroaden",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java#knownZeroMatchesAndUnknownSizeDoesNot",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java#exactlyTenThousandSucceedsOverflowIsTypedAndExplicitIdsAvoidIt"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/input/EntityExpansionRestrictionTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: expandedEntityKeepsDocumentCollectionFolderDateAndSizeScopes",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/input/EntityExpansionRestrictionTest.java#expandedEntityKeepsDocumentCollectionFolderDateAndSizeScopes"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/api/domains/searchRestrictions.test.ts"
      ],
      "class": "New",
      "behaviour": "WP2.3a settled scenario checks: parses all bounds including zero through the generated response contract",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp2.3a-filters.json",
      "newChecks": [
        "modules/ui-web/src/api/domains/searchRestrictions.test.ts#parses all bounds including zero through the generated response contract"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilderTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Per builder brief: adapt the obsolete ignores-date expectation to parent-resolved modified_at eligibility (D4/F2). Other query-filter oracles are preserved. This is a deliberate changed expectation, not owner-agreed preservation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Remaining folder, modified date and file size filter design and implementation; WP2.3a Engine capability.",
          "choice": "Scenarios revision 3 D1–D12 and package a scenarios: exact inclusive intersecting parent restrictions, no broadening, typed overflow, preserved unrestricted recovery.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
