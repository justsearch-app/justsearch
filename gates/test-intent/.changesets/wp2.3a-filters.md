---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp2.3a-filters-builder
---

# Test-intent entries

Agent choices under the quoted delegation; not owner-agreed. Execution evidence distinguishes assertion fail-before repairs, before/after preservation and after-only new-type validation. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: crossedCorpusAndSaturatedChunksUseParentMetadataBeforeBudget, parentBoundsIntersectSearchFacetsBeforeChunkCandidates, inclusiveOneSidedEqualAndLargeBoundsDistinguishUnknownAndKnownZero, knownEpochZeroMatchesAnUpperDateBoundWhileUnknownDoesNot, sourceBytesAndFilesystemMtimeStayDistinctFromTextAndPublicationTime, reopensLegacyFieldIndexThenParentUpdateChangesChunkEligibility.",
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
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#reopensLegacyFieldIndexThenParentUpdateChangesChunkEligibility"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java"
      ],
      "class": "New",
      "behaviour": "New preservation checks of existing behavior, passing before and after without skips: folderPrefixesAreLiteralDirectories. These checks do not establish repaired behavior.",
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
      "evidence": "gates/test-intent/evidence/wp2.3a-preservation.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ParentRestrictionQueryTest.java#folderPrefixesAreLiteralDirectories"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/knowledge/SearchRestrictionsTest.java"
      ],
      "class": "New",
      "behaviour": "After-only validation checks for the new SearchRestrictions utility: exactMaximumNullAndZeroSemantics, decimalParsingNeverRoundsAnInvalidBoundIntoTheDomain. Transplanting the exact candidate test file onto b744db87446b847edb306eb4d12c92781ac5b4dd fails compileTestJava because SearchRestrictions does not exist there. This is a compile failure, not assertion fail-before evidence; before behavior is unverified by these checks.",
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
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: richPortFailureAndHeadWaitFailureNeverFetchChosenFiles, overflowSurvivesHeadAsTypedFailureWithoutModelInput, restrictedEmptyIsNoContentAndSuccessfulFulltextIsKept, allRestrictionsReachTheRichPortForOpenAndExplicitIdRetrieval, actualConversationDispatchEndsOnTypedRestrictedFailureWithoutGeneration.",
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
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#allRestrictionsReachTheRichPortForOpenAndExplicitIdRetrieval",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#actualConversationDispatchEndsOnTypedRestrictedFailureWithoutGeneration"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "New preservation checks of existing behavior, passing before and after without skips: reportedFallbackFailureIsNeverPresentedAsEmptyRestrictedContent. These checks do not establish repaired behavior.",
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
      "evidence": "gates/test-intent/evidence/wp2.3a-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGRestrictionFailureTest.java#reportedFallbackFailureIsNeverPresentedAsEmptyRestrictedContent"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteRestrictionFailureTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: preSearchAndDiscoveredIdCopyPreserveRestrictionsOnTheWorkerRequest, preSearchAndRichPortFailuresDoNotBroadenRestrictedRetrieval.",
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
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RestrictionValidationEntryTest.java"
      ],
      "class": "New",
      "behaviour": "After-only entry-validation check: searchAndAskRejectTheExactValidationMatrixBeforeAdmission. The unchanged official runner classified the before result as exception (Mockito ArgumentsAreDifferent), not assertion: the baseline Search handler returned 500 INVALID_STATE, Search requires the shared publication owner, instead of the expected 400 INVALID_REQUEST. The candidate passes, but this before run does not establish the validation matrix failing for its intended reason; before validation is unverified by this check. Raw before diagnostics are retained in gates/test-intent/evidence/wp2.3a-filters/before-entry-validation.xml and before-entry-validation.log. No repaired/fail-before claim is made.",
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
      "evidence": "gates/test-intent/evidence/wp2.3a-entry-validation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RestrictionValidationEntryTest.java#searchAndAskRejectTheExactValidationMatrixBeforeAdmission"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerRestrictionRetrievalTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: chunksChunklessUnionAndFullDocumentPathsIntersectAllBounds, fulltextFallbackAndEmptyIntersectionDoNotBroaden, knownZeroMatchesAndUnknownSizeDoesNot, exactlyTenThousandSucceedsOverflowIsTypedAndExplicitIdsAvoidIt.",
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
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/input/EntityExpansionRestrictionTest.java"
      ],
      "class": "New",
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: expandedEntityKeepsDocumentCollectionFolderDateAndSizeScopes.",
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
      "behaviour": "WP2.3a repaired restrictions, asserted on the before revision and passing on the candidate: parses all bounds including zero through the generated response contract.",
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
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilderTest.java"
      ],
      "class": "New",
      "behaviour": "Changed chunk-date oracle: date restrictions resolve eligible parent metadata before chunk candidate budgets, rather than being ignored. buildChunkFilterQuery_resolvesDateRangeThroughParent fails by assertion before and passes after. This changes the expected outcome, so it is New/repaired, not an Adaptation retaining the whole oracle. Other QueryFilterBuilderTest expectations are unchanged.",
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
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/QueryFilterBuilderTest.java#buildChunkFilterQuery_resolvesDateRangeThroughParent"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
