---
schema: test-intent.v1
task: fu-5
author-role: builder
author-session: fu-5-builder
---

# FU-5 test intent

Gate ids FU51–FU54 refer to FU5-1–FU5-4 in the delegated task request.
The orchestrator's delegated revision in the task request supplies the scenarios. Policy identity
stays at v8; no automatic retry or new durable state is introduced. Retry uses the existing
failed-files action. Successful-file preservation reuses SyncExtractionPolicyTest and
RestoredMtimeQueuedRestartTest unchanged. The failed-files generic message remains a follow-up.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "FU5-1–FU5-4: extract a 268-entry valid deck and search its last-slide marker; exactly 1,000 parts pass, including nested/sibling parser callbacks and embedded-disabled admission; 1,001 refuses; package-member ratios scale to twice the policy ratio (100x to 200x default); existing failed-files retry indexes a terminal count-refused file. Each named repair check fails by assertion on the base.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "package parts get their own cumulative per-document budget of 1,000 entries (directories and duplicate occurrences counted, the two ZIP views not double-counted).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU53",
          "quote": "package members get twice the policy's compression ratio (default 100 -> 200x); generic ZIP members, packages and the whole document keep the policy ratio and the byte ceiling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU54",
          "quote": "a file refused on main for ARCHIVE_MEMBER_LIMIT, retried through the existing failed-job retry on the candidate, is indexed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU51",
          "quote": "the candidate extracts it and its last-slide marker is searchable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "exactly 1,000 package entries pass; nested and sibling packages share the allowance, with cumulative admission above 128 shown.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5.json",
      "repaired": true,
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#packagePartsHaveABoundEvenWhenEmbeddedResourcesAreDisabled",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#nestedAndSiblingPackagesShareOneThousandParts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#explicitRatioScalesOnlyPackageMembers"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java"
      ],
      "class": "New",
      "behaviour": "FU5-1–FU5-4: extract a 268-entry valid deck and search its last-slide marker; exactly 1,000 parts pass, including nested/sibling parser callbacks and embedded-disabled admission; 1,001 refuses; package-member ratios scale to twice the policy ratio (100x to 200x default); existing failed-files retry indexes a terminal count-refused file. Each named repair check fails by assertion on the base.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "package parts get their own cumulative per-document budget of 1,000 entries (directories and duplicate occurrences counted, the two ZIP views not double-counted).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU53",
          "quote": "package members get twice the policy's compression ratio (default 100 -> 200x); generic ZIP members, packages and the whole document keep the policy ratio and the byte ceiling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU54",
          "quote": "a file refused on main for ARCHIVE_MEMBER_LIMIT, retried through the existing failed-job retry on the candidate, is indexed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU51",
          "quote": "the candidate extracts it and its last-slide marker is searchable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "exactly 1,000 package entries pass; nested and sibling packages share the allowance, with cumulative admission above 128 shown.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5.json",
      "repaired": true,
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#parserConsumedPackageMemberBetweenOneAndTwoHundredRatioExtractsText",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#nestedSiblingOfficePackagesShareThePartAllowanceThroughParserCallbacks"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java"
      ],
      "class": "New",
      "behaviour": "FU5-1–FU5-4: extract a 268-entry valid deck and search its last-slide marker; exactly 1,000 parts pass, including nested/sibling parser callbacks and embedded-disabled admission; 1,001 refuses; package-member ratios scale to twice the policy ratio (100x to 200x default); existing failed-files retry indexes a terminal count-refused file. Each named repair check fails by assertion on the base.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "package parts get their own cumulative per-document budget of 1,000 entries (directories and duplicate occurrences counted, the two ZIP views not double-counted).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU53",
          "quote": "package members get twice the policy's compression ratio (default 100 -> 200x); generic ZIP members, packages and the whole document keep the policy ratio and the byte ceiling.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU54",
          "quote": "a file refused on main for ARCHIVE_MEMBER_LIMIT, retried through the existing failed-job retry on the candidate, is indexed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU51",
          "quote": "the candidate extracts it and its last-slide marker is searchable.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "exactly 1,000 package entries pass; nested and sibling packages share the allowance, with cumulative admission above 128 shown.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5.json",
      "repaired": true,
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#manyPartDeckExtractsAndLastSlideIsSearchable",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#failedFilesRetryIndexesPreviouslyCountRefusedDeck"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "FU52",
          "quote": "package parts get their own cumulative per-document budget of 1,000 entries (directories and duplicate occurrences counted, the two ZIP views not double-counted).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. The delegated revision explicitly replaces the metadata-entry package bound; only the old two-entry package oracle changes to the fixed 1,000-entry allowance."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P4 preservation: excess admission opens no member stream; spoofed packages, generic members, total expansion and effective byte ceilings remain bounded; malformed competing ZIP views cannot bypass inspection; depth eight works and depth nine and a hash-pinned self-reproducing ZIP refuse through the real child, which processes a normal file after each refusal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P4",
          "quote": "Pinned quine and the existing depth convention (callbacks incl. the leaf) refuse; a normal file succeeds afterwards; exact reasons survive the child into structured outcomes and the ledger; timeout stays timeout.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P3",
          "quote": "Hostile ZIPs: both views, duplicates, directory payloads, misleading sizes, overlaps, spoofed package markers (bounded as decided); ordinary preamble protection (the preamble/local-view combination is not claimed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P2",
          "quote": "Above-200x package members, above-100x generic members, package and document expansion overflow, metadata limits, embedded count/depth limits and deadlines stay enforced; byte ceilings are effective-policy limits (100 MiB default).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P1",
          "quote": "Entry 1,001 refused at central-directory admission before member streams open.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#packageExcessRefusesBeforeAnyMemberStreamOpens",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#spoofedPackagesAndGenericMembersKeepExpansionAndByteCeilings"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P4 preservation: excess admission opens no member stream; spoofed packages, generic members, total expansion and effective byte ceilings remain bounded; malformed competing ZIP views cannot bypass inspection; depth eight works and depth nine and a hash-pinned self-reproducing ZIP refuse through the real child, which processes a normal file after each refusal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P4",
          "quote": "Pinned quine and the existing depth convention (callbacks incl. the leaf) refuse; a normal file succeeds afterwards; exact reasons survive the child into structured outcomes and the ledger; timeout stays timeout.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P3",
          "quote": "Hostile ZIPs: both views, duplicates, directory payloads, misleading sizes, overlaps, spoofed package markers (bounded as decided); ordinary preamble protection (the preamble/local-view combination is not claimed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P2",
          "quote": "Above-200x package members, above-100x generic members, package and document expansion overflow, metadata limits, embedded count/depth limits and deadlines stay enforced; byte ceilings are effective-policy limits (100 MiB default).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P1",
          "quote": "Entry 1,001 refused at central-directory admission before member streams open.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#realChildKeepsDepthAndExpansionReasonsAndAcceptsNormalFileAfterwards"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P4 preservation: excess admission opens no member stream; spoofed packages, generic members, total expansion and effective byte ceilings remain bounded; malformed competing ZIP views cannot bypass inspection; depth eight works and depth nine and a hash-pinned self-reproducing ZIP refuse through the real child, which processes a normal file after each refusal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P4",
          "quote": "Pinned quine and the existing depth convention (callbacks incl. the leaf) refuse; a normal file succeeds afterwards; exact reasons survive the child into structured outcomes and the ledger; timeout stays timeout.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P3",
          "quote": "Hostile ZIPs: both views, duplicates, directory payloads, misleading sizes, overlaps, spoofed package markers (bounded as decided); ordinary preamble protection (the preamble/local-view combination is not claimed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P2",
          "quote": "Above-200x package members, above-100x generic members, package and document expansion overflow, metadata limits, embedded count/depth limits and deadlines stay enforced; byte ceilings are effective-policy limits (100 MiB default).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P1",
          "quote": "Entry 1,001 refused at central-directory admission before member streams open.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#localOnlyDuplicateDirectoryMisleadingAndOverlappingMembersCannotBypassInspection",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#packageAndDocumentTotalsAndEffectiveByteCeilingsRemainIndependentOfMemberRatio",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#genericMemberBetweenOneAndTwoHundredRatioStillRefusesWithPadding"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PinnedZipQuineTest.java"
      ],
      "class": "New",
      "behaviour": "P1–P4 preservation: excess admission opens no member stream; spoofed packages, generic members, total expansion and effective byte ceilings remain bounded; malformed competing ZIP views cannot bypass inspection; depth eight works and depth nine and a hash-pinned self-reproducing ZIP refuse through the real child, which processes a normal file after each refusal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P4",
          "quote": "Pinned quine and the existing depth convention (callbacks incl. the leaf) refuse; a normal file succeeds afterwards; exact reasons survive the child into structured outcomes and the ledger; timeout stays timeout.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P3",
          "quote": "Hostile ZIPs: both views, duplicates, directory payloads, misleading sizes, overlaps, spoofed package markers (bounded as decided); ordinary preamble protection (the preamble/local-view combination is not claimed).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P2",
          "quote": "Above-200x package members, above-100x generic members, package and document expansion overflow, metadata limits, embedded count/depth limits and deadlines stay enforced; byte ceilings are effective-policy limits (100 MiB default).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P1",
          "quote": "Entry 1,001 refused at central-directory admission before member streams open.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PinnedZipQuineTest.java#pinnedSelfReproducingZipRefusesThroughRealChildAndNextFileSucceeds"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "ZIP fixtures now use a fixed local DOS timestamp without changing their members, inflation, parser content, fault injections or expected outcomes. This makes both-tree fixtures deterministic without changing process time-zone state."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java"
      ],
      "class": "New",
      "behaviour": "P4: genuine package-count and ZIP-expansion refusals traverse the real extraction child and indexing pipeline into the SQLite ledger as BUDGET_EXCEEDED with their exact structured reason; subsequent normal ingestion succeeds and is searchable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "fu-5",
          "scenario": "P4",
          "quote": "exact reasons survive the child into structured outcomes and the ledger; timeout stays timeout.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/fu-5-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#childBudgetReasonsRemainExactInTheSqliteLedger"
      ]
    }
  ]
}
```

## Verification commands

Run each block in the background, one invocation at a time. The slot wrapper covers the evidence
runner's internal Gradle calls. The orchestrator generates these JSON files after committing;
the builder does not generate them. Every repair selector below must fail by assertion before.

```bash
js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --before 2665136517ede81aefaa348a70d1ccd7e55647e4 \
  --before-root "$TMPDIR" \
  --out gates/test-intent/evidence/fu-5.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#packagePartsHaveABoundEvenWhenEmbeddedResourcesAreDisabled \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#nestedAndSiblingPackagesShareOneThousandParts \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#explicitRatioScalesOnlyPackageMembers \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#parserConsumedPackageMemberBetweenOneAndTwoHundredRatioExtractsText \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#nestedSiblingOfficePackagesShareThePartAllowanceThroughParserCallbacks \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#manyPartDeckExtractsAndLastSlideIsSearchable \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#failedFilesRetryIndexesPreviouslyCountRefusedDeck
```

```bash
js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/fu-5-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#packageExcessRefusesBeforeAnyMemberStreamOpens \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#spoofedPackagesAndGenericMembersKeepExpansionAndByteCeilings \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#manyTinyMembersRefuseAtFirstExcessBeforeAnyInflation \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#oversizedNameRefusesBeforeEarlierAdmittedMemberInflates \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#aggregateMetadataRefusesBeforeInflation \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#exactMemberBoundaryStillInflatesBothViews \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ContainerExpansionBudgetTest.java#nestedInspectionsShareTheDocumentMemberBudget \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#strictRatioRejectsNonOutputOdtAndOoxmlPartsWithoutEmbeddedCallbacks \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#embeddedOoxmlUsesTheSameInternalExpansionPolicy \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#standaloneFlatParsingRejectsNonOutputOdtExpansion \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#preparedInputCannotBeReplacedBetweenInspectionAndParsing \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#realArchiveReportsObservedResourcesAndNestedDepth \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#rejectsThreeHundredMembersWithoutFlatFallbackBypass \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#rejectsDeepRealArchive \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#rejectsExpansionBelowInputAndTextCaps \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#flatFallbackRetainsConfiguredPolicy \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ArchiveExtractionLimitsTest.java#realChildKeepsDepthAndExpansionReasonsAndAcceptsNormalFileAfterwards \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#localOnlyDuplicateDirectoryMisleadingAndOverlappingMembersCannotBypassInspection \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#packageAndDocumentTotalsAndEffectiveByteCeilingsRemainIndependentOfMemberRatio \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/HostilePackageAdmissionTest.java#genericMemberBetweenOneAndTwoHundredRatioStillRefusesWithPadding \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PinnedZipQuineTest.java#pinnedSelfReproducingZipRefusesThroughRealChildAndNextFileSucceeds \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SyncExtractionPolicyTest.java#currentEffectivePolicyKeepsUnchangedFilesSkipped \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TikaExtractionPolicyWorkerLimitsTest.java#defaultEquivalentLimitsKeepDefaultIdentity \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PreparedExtractionInputTest.java#disguisedZipAndPreambleAcrossBufferBoundaryRetainExpansionBudget \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java#hangingChildIsKilledAtTheDeadlineAndTheNextRequestSucceeds \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#unchangedQueuedFileSkipsExtractionAcrossRestarts \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/OfficePackageRetryTest.java#childBudgetReasonsRemainExactInTheSqliteLedger \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceTest.java#cancelThroughServiceRecordsSkippedOutcomeAndStopsPendingWork \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/RestoredMtimeQueuedRestartTest.java#acceptedOrdinaryForceRunsOnceAfterUnforcedRecordedWalkSeals \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java#pythonBytecodeFileIsSkippedByExtension
```

The preservation selectors also run on the shared before clone with candidate tests overlaid;
no `--before` in the preservation evidence command, because that flag requires assertion failure.
The real-system corpus/upgrade run and configuration/hash pinning remain independent verification.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "820469ae-b06e-4b51-a0b8-23d7cf754311",
  "verdict": "accept",
  "digest": "sha256:c2bbe2ff1997167945db380e98136e45e1a3eb03937547ea16a11d1e3bc11f56",
  "note": "Claude verifier (CLAUDE_CODE_SESSION_ID) on 1745171ffe after a Codex verifier was stopped by its provider's content filter: all entries accept; FU5-1..4 fail before by assertion and pass after; 45 corpus files extract incl. the 610-part deck's last slide; P1-P5 hold; pythonBytecodeFileIsSkippedByExtension runs and passes on both trees. Pre-existing on main: a forged compressed size widens a member's own ratio limit (package 100x and 100 MiB ceiling still bound it); recorded as a follow-up."
}
```
