---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: p2-intent-builder-20261110
---

# Test-intent entries

Owner agreement, 2026-11-02: "i agree with your recommendations." Only the adopted interrupted-import recommendation below is owner-adopted; all detailed I/N scenario choices are delegated-choice. No product or test source is changed by this record. Original case numbers refer to the 22-case disposition supplied with the assignment. Case 9 (cancellationCannotRecreateProgressAfterAnEnumerationHasStarted) keeps its original oracle and is exercised by the required suite; it has no changed expectation. New files are gate-exempt and remain subject to independent verification.

Execution evidence is written by run-evidence.mjs (fail-before with gates/test-intent/compat/p2-interrupted-imports-before.patch); acceptance is intentionally empty.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java"
      ],
      "class": "New",
      "behaviour": "N3: recorded non-import parents/children retain unknown origin and existing boundary permissions. I9: lookup errors propagate and a recorded child whose owner is missing is refused rather than reclassified as ordinary.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "N3: ordinary null/empty/noncanonical/versioned scan keys and recorded non-import parents/children retain unknown origin and existing boundary permissions. I9: lookup errors propagate and a recorded child whose owner is missing is refused rather than reclassified as ordinary.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "N3: ordinary null/empty/noncanonical/versioned scan keys and recorded non-import parents/children retain unknown origin and existing boundary permissions. I9: lookup errors propagate and a recorded child whose owner is missing is refused rather than reclassified as ordinary.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-ordinary-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java#nonImportRecordedParentsAndChildrenKeepTheirBoundaryRules"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java"
      ],
      "class": "New",
      "behaviour": "N3: ordinary null/empty/noncanonical/versioned scan keys retain unknown origin and existing boundary permissions. Preservation: passes on the before-state, so it is not a repaired check.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "N3: ordinary null/empty/noncanonical/versioned scan keys and recorded non-import parents/children retain unknown origin and existing boundary permissions. I9: lookup errors propagate and a recorded child whose owner is missing is refused rather than reclassified as ordinary.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "N3: ordinary null/empty/noncanonical/versioned scan keys and recorded non-import parents/children retain unknown origin and existing boundary permissions. I9: lookup errors propagate and a recorded child whose owner is missing is refused rather than reclassified as ordinary.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-ordinary-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java#nonImportScanKeysRetainUnknownOriginAndAllowEveryBoundary"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java"
      ],
      "class": "New",
      "behaviour": "The existing storage-error oracle also covers failure during the second import-approval lookup and publication boundary. Failure cannot invent authority or an unknown-origin success.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "The existing storage-error oracle also covers failure during the second import-approval lookup and publication boundary. Failure cannot invent authority or an unknown-origin success.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "The existing storage-error oracle also covers failure during the second import-approval lookup and publication boundary. Failure cannot invent authority or an unknown-origin success.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-ordinary-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/OrdinaryScanOriginTest.java#recordedOperationLookupFailureStillSurfaces"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Retained imports stop consuming work slots after draining; two retained owners remain listed while a fresh import or watched reindex dispatches with one active slot.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Retained imports stop consuming work slots after draining; two retained owners remain listed while a fresh import or watched reindex dispatches with one active slot.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Retained imports stop consuming work slots after draining; two retained owners remain listed while a fresh import or watched reindex dispatches with one active slot.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#twoInterruptedImportsDoNotBlockFreshDispatch"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Admission refusal leaves the original interruption revision, pending import and two existing active work slots unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Resume requires fresh approval of the original plan; rejection, dismissal or a direct unapproved call changes nothing. A persisted session label never authorizes.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I5 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Admission refusal leaves the original interruption revision, pending import and two existing active work slots unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Admission refusal leaves the original interruption revision, pending import and two existing active work slots unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Temporary unavailability (worker readiness, generation not ready): waits and continues with no new approval and no attempts consumed.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N1 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Admission refusal leaves the original interruption revision, pending import and two existing active work slots unchanged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#resumeRefusedAtFullCapacityKeepsTheImportVisible"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "An accepted renewal unable to obtain capacity relatches ENGINE_LIMIT visibly. A later fresh Resume continues the same parent, preparation and child; closed enumeration does not start a new producer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Duplicate, concurrent or stale Resume: one runner; an older request is refused.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I7 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An accepted renewal unable to obtain capacity relatches ENGINE_LIMIT visibly. A later fresh Resume continues the same parent, preparation and child; closed enumeration does not start a new producer.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An accepted renewal unable to obtain capacity relatches ENGINE_LIMIT visibly. A later fresh Resume continues the same parent, preparation and child; closed enumeration does not start a new producer.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Attempts exhausted or a recovery write fails: stays visible with an explanation, never disappears or reads as success.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I16 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An accepted renewal unable to obtain capacity relatches ENGINE_LIMIT visibly. A later fresh Resume continues the same parent, preparation and child; closed enumeration does not start a new producer.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#acceptedResumeWithoutWorkCapacityRelatchesTheSameImport"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Issued claims and a live producer retain their slot until actual exit; returned claims remain denied, then the drained interruption releases its slot and remains visible.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Issued claims and a live producer retain their slot until actual exit; returned claims remain denied, then the drained interruption releases its slot and remains visible.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Issued claims and a live producer retain their slot until actual exit; returned claims remain denied, then the drained interruption releases its slot and remains visible.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#interruptedImportKeepsCapacityUntilProducerAndIssuedClaimsDrain"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "An overlapping import cannot take a pending path owned by a live or interrupted recorded walk. Original pending work and open walk remain with their original owner.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Duplicate, concurrent or stale Resume: one runner; an older request is refused.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I7 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An overlapping import cannot take a pending path owned by a live or interrupted recorded walk. Original pending work and open walk remain with their original owner.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An overlapping import cannot take a pending path owned by a live or interrupted recorded walk. Original pending work and open walk remain with their original owner.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Interrupted work and everything needed to resume survive retention and cleanup while Resume is offered. An import with no indexed documents still appears.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I13 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "An overlapping import cannot take a pending path owned by a live or interrupted recorded walk. Original pending work and open walk remain with their original owner.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#overlappingImportCannotTakeRetainedWalkMembers"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 5: the positive family fixture installs the actual file-operations grant instead of treating indexing as authorization. Original partition, exclusion, origin, unspecified-collection and history-expiry expectations survive.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 5: the positive family fixture installs the actual file-operations grant instead of treating indexing as authorization. Original partition, exclusion, origin, unspecified-collection and history-expiry expectations survive.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Interrupted work and everything needed to resume survive retention and cleanup while Resume is offered. An import with no indexed documents still appears.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I13 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 5: the positive family fixture installs the actual file-operations grant instead of treating indexing as authorization. Original partition, exclusion, origin, unspecified-collection and history-expiry expectations survive.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#acceptedPartitionedImportsRetainOriginAndApprovalAfterHistoryExpiry"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original cases 1-2 and all six contradictory-child variants: retain nonterminal parent/child, no terminal receipt, visible unavailable reason, original identities and surviving queue evidence; no producer or claims.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 1-2 and all six contradictory-child variants: retain nonterminal parent/child, no terminal receipt, visible unavailable reason, original identities and surviving queue evidence; no producer or claims.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Attempts exhausted or a recovery write fails: stays visible with an explanation, never disappears or reads as success.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I16 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 1-2 and all six contradictory-child variants: retain nonterminal parent/child, no terminal receipt, visible unavailable reason, original identities and surviving queue evidence; no producer or claims.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#recoveryCannotRecreateOrEnumerateContradictoryChildEvidence"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 15: replacement invalidates session authority; completion remains unresolved and import is visibly interrupted with authorization refused. No producer restart or implicit durable approval.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Session-only approval, then restart: no reads or claims, the import is listed as interrupted with its folders and reason, and Resume is available.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I1 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 15: replacement invalidates session authority; completion remains unresolved and import is visibly interrupted with authorization refused. No producer restart or implicit durable approval.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "A renewed session approval, then another crash: interrupted again, Resume available; never upgraded to durable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I6 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 15: replacement invalidates session authority; completion remains unresolved and import is visibly interrupted with authorization refused. No producer restart or implicit durable approval.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#indexReplacementKeepsPendingWorkButDropsFreshCapsuleAuthority"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 4: persisted parent/child corruption leaves unresolved visible interruption; replacement cannot use cached authority, acknowledge the child or produce new effects.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 4: persisted parent/child corruption leaves unresolved visible interruption; replacement cannot use cached authority, acknowledge the child or produce new effects.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#replacementRevalidatesPersistedParentAndChildInsteadOfCachedAuthority"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original cases 11-12: missing/stale parent checkpoint catches up from the durable child receipt without a new execution attempt (one, formerly two). Failure receipt, counters, cursor and acknowledgement barrier stay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 11-12: missing/stale parent checkpoint catches up from the durable child receipt without a new execution attempt (one, formerly two). Failure receipt, counters, cursor and acknowledgement barrier stay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "All work finished before the crash: completes from durable records, with no approval needed to re-execute.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I12 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 11-12: missing/stale parent checkpoint catches up from the durable child receipt without a new execution attempt (one, formerly two). Failure receipt, counters, cursor and acknowledgement barrier stay.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#bootFailedChildRepairsMissingOrStaleParentCheckpointBeforeFailure"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 3: durable cancelled-child receipt catches up without a new execution attempt (one, formerly two); cancellation, checkpoint, counters, receipt acknowledgement and no-admission expectations stay.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 3: durable cancelled-child receipt catches up without a new execution attempt (one, formerly two); cancellation, checkpoint, counters, receipt acknowledgement and no-admission expectations stay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "All work finished before the crash: completes from durable records, with no approval needed to re-execute.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I12 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 3: durable cancelled-child receipt catches up without a new execution attempt (one, formerly two); cancellation, checkpoint, counters, receipt acknowledgement and no-admission expectations stay.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Cancel racing Resume, then restart: cancel wins and drains safely; no later automatic restart.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I14 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 3: durable cancelled-child receipt catches up without a new execution attempt (one, formerly two); cancellation, checkpoint, counters, receipt acknowledgement and no-admission expectations stay.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#bootCancelledChildRepairsParentAndPropagatesCancellationWithoutAdmission"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Additional changed expectation: contradictory confirmed parent counts retain a visible unavailable interruption, preserving cursor, counts and attempt budget rather than becoming terminal.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Additional changed expectation: contradictory confirmed parent counts retain a visible unavailable interruption, preserving cursor, counts and attempt budget rather than becoming terminal.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#bootParentWithMoreConfirmedUnitsThanChildFailsUnavailableAndPreservesCheckpoint"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 22: malformed parent stays visible with RECOVERY_BINDING_INVALID; issued sibling drains, hidden terminal child stays unacknowledged, original confirmed counts/cursor stay and claims remain denied.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 22: malformed parent stays visible with RECOVERY_BINDING_INVALID; issued sibling drains, hidden terminal child stays unacknowledged, original confirmed counts/cursor stay and claims remain denied.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#malformedParentCannotAcknowledgeHiddenTerminalChildOrOutrunIssuedSibling"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Real SQLite origin INSERT failure retains RUNNING parent and accepted child, starts no producer and lists RECOVERY_ORIGIN_UNAVAILABLE with Resume available.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Real SQLite origin INSERT failure retains RUNNING parent and accepted child, starts no producer and lists RECOVERY_ORIGIN_UNAVAILABLE with Resume available.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Attempts exhausted or a recovery write fails: stays visible with an explanation, never disappears or reads as success.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I16 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Real SQLite origin INSERT failure retains RUNNING parent and accepted child, starts no producer and lists RECOVERY_ORIGIN_UNAVAILABLE with Resume available.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#freshOriginWriteFailureRetainsTheImportAndAcceptedChild"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 6: generation-mismatched imports retain pending work, nonterminal child and exact parent/child preparations and identities with no producer. The watched-reindex refusal branch keeps its terminal assertions.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 6: generation-mismatched imports retain pending work, nonterminal child and exact parent/child preparations and identities with no producer. The watched-reindex refusal branch keeps its terminal assertions.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 6: generation-mismatched imports retain pending work, nonterminal child and exact parent/child preparations and identities with no producer. The watched-reindex refusal branch keeps its terminal assertions.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 6: generation-mismatched imports retain pending work, nonterminal child and exact parent/child preparations and identities with no producer. The watched-reindex refusal branch keeps its terminal assertions.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 6: generation-mismatched imports retain pending work, nonterminal child and exact parent/child preparations and identities with no producer. The watched-reindex refusal branch keeps its terminal assertions.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#bothStoresReopenUnderNewOwnersAndResumeTheOriginalPreparedOperation"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original cases 16-21: none, before-marker, after-marker, after-retirement, after-terminal and exhausted retain open/unsealed/unacknowledged child and pending denied work after repeated recovery. before-marker uses a real SQLite latch-write failure; obsolete retirement/terminal-child crash protocol is removed. Exhaustion lists its reason.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 16-21: none, before-marker, after-marker, after-retirement, after-terminal and exhausted retain open/unsealed/unacknowledged child and pending denied work after repeated recovery. before-marker uses a real SQLite latch-write failure; obsolete retirement/terminal-child crash protocol is removed. Exhaustion lists its reason.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Attempts exhausted or a recovery write fails: stays visible with an explanation, never disappears or reads as success.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I16 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 16-21: none, before-marker, after-marker, after-retirement, after-terminal and exhausted retain open/unsealed/unacknowledged child and pending denied work after repeated recovery. before-marker uses a real SQLite latch-write failure; obsolete retirement/terminal-child crash protocol is removed. Exhaustion lists its reason.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#refusedRecoveryRetainsUnsettledUnitsAfterEnumerationAlreadyCompleted"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original case 10: durable interruption replaces the terminal refusal cursor. Restoring authority never clears it. The drained issued effect is counted in the walk; unissued work remains pending, denied and unsealed.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original case 10: durable interruption replaces the terminal refusal cursor. Restoring authority never clears it. The drained issued effect is counted in the walk; unissued work remains pending, denied and unsealed.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#liveRefusalSurvivesAuthorityRestorationAndCountCheckpointsAfterAnIssuedClaimDrains"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original cases 7-8: unsupported v2 and unknown v1 refusal evidence retain RECOVERY_BINDING_INVALID without changing cursor or unresolved queue evidence. Added supported v1 generation-mismatch control remains readable and interrupted without present-day reauthorization.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 7-8: unsupported v2 and unknown v1 refusal evidence retain RECOVERY_BINDING_INVALID without changing cursor or unresolved queue evidence. Added supported v1 generation-mismatch control remains readable and interrupted without present-day reauthorization.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#malformedRefusalWitnessCannotFallBackToCurrentAuthority"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Original cases 13-14: stale-generation/revoked-authority import completion stays unresolved with its precise visible interruption reason, no replacement producer and unchanged preparation. Reindex keeps terminal failure checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 13-14: stale-generation/revoked-authority import completion stays unresolved with its precise visible interruption reason, no replacement producer and unchanged preparation. Reindex keeps terminal failure checks.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 13-14: stale-generation/revoked-authority import completion stays unresolved with its precise visible interruption reason, no replacement producer and unchanged preparation. Reindex keeps terminal failure checks.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Completed and cancelled imports do not restart. Fresh imports, watched-root reindex and bulk generation recovery keep their rules.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario N3 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Original cases 13-14: stale-generation/revoked-authority import completion stays unresolved with its precise visible interruption reason, no replacement producer and unchanged preparation. Reindex keeps terminal failure checks.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#registeredPreparedRecoveryRefusesStaleGenerationAndRevokedAuthorityWithoutEffect"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261102-redesign",
          "scenario": "P2",
          "location": "task t-20261102-redesign agreement",
          "adoptionQuote": "i agree with your recommendations.",
          "quote": "Import interrupted by a crash: resume automatically for exactly the folders originally approved; if that approval was session-only, show it as interrupted with a resume action; never silently drop.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Approval revoked or hard stop: claims and enumeration stop, the import stays visible as interrupted, indexed content stays.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I8 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Supersede terminal refusal/retirement and extra receipt-only attempt expectations only in the methods enumerated above. Remove cutRefusalRecovery and RefusalCrash, which injected crashes in that displaced terminal protocol. Cancellation evidence-loss assertions are preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Missing, malformed, contradictory or unsupported approval or recovery evidence, and older imports with no binding: visible as interrupted, no authority invented, Resume only where scope is provable.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I9 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Supersede terminal refusal/retirement and extra receipt-only attempt expectations only in the methods enumerated above. Remove cutRefusalRecovery and RefusalCrash, which injected crashes in that displaced terminal protocol. Cancellation evidence-loss assertions are preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Crash after acceptance before origin or child, after child before walk, during enumeration or admission, after enumeration closes with work pending: resumes without duplicate child, enlarged scope or recovery-refusal skips.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I10 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Supersede terminal refusal/retirement and extra receipt-only attempt expectations only in the methods enumerated above. Remove cutRefusalRecovery and RefusalCrash, which injected crashes in that displaced terminal protocol. Cancellation evidence-loss assertions are preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "All work finished before the crash: completes from durable records, with no approval needed to re-execute.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I12 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Supersede terminal refusal/retirement and extra receipt-only attempt expectations only in the methods enumerated above. Remove cutRefusalRecovery and RefusalCrash, which injected crashes in that displaced terminal protocol. Cancellation evidence-loss assertions are preserved.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Attempts exhausted or a recovery write fails: stays visible with an explanation, never disappears or reads as success.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I16 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "Supersede terminal refusal/retirement and extra receipt-only attempt expectations only in the methods enumerated above. Remove cutRefusalRecovery and RefusalCrash, which injected crashes in that displaced terminal protocol. Cancellation evidence-loss assertions are preserved.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none in docs/decisions for interrupted import/session approval/receipt-attempt authority; the settled scenarios and the 22-case disposition in the supplied assignment are the authority. No private decision records were read."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Imported assertNull/assertNotNull replace redundant fully qualified calls with identical arguments and predicates. assertRetainedImport centralizes the newly sourced nonterminal/no-receipt/reason observations listed above; those new expectations are classified separately, not claimed as oracle-preserving adaptation."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/OriginApprovalEvaluatorTest.java"
      ],
      "class": "New",
      "behaviour": "A live proof from the preceding approval binding cannot authorize its selected successor, even with the same parent and plan. The fixture uses the actual operation key as its initial binding identity so its existing positive session-owner assertion exercises the valid accepted binding.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Duplicate, concurrent or stale Resume: one runner; an older request is refused.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I7 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "A live proof from the preceding approval binding cannot authorize its selected successor, even with the same parent and plan. The fixture uses the actual operation key as its initial binding identity so its existing positive session-owner assertion exercises the valid accepted binding.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "After renewal, new work under an import not covered by a watched folder uses the active approval's origin; historical references stay. A file inside a watched folder takes the watched folder's origin, per the settled provenance rule W7 (design/provenance.md line 59).",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I15 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "A live proof from the preceding approval binding cannot authorize its selected successor, even with the same parent and plan. The fixture uses the actual operation key as its initial binding identity so its existing positive session-owner assertion exercises the valid accepted binding.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-origin-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/OriginApprovalEvaluatorTest.java#sessionRequiresExactLiveAcceptedOwnerFromThisAuthority"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "class": "New",
      "behaviour": "The exact core operation set includes core.resume-import for fresh approved continuation and core.cancel-import for explicit cancellation; existing operation identifiers remain expected.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Resume requires fresh approval of the original plan; rejection, dismissal or a direct unapproved call changes nothing. A persisted session label never authorizes.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I5 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "The exact core operation set includes core.resume-import for fresh approved continuation and core.cancel-import for explicit cancellation; existing operation identifiers remain expected.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Cancel racing Resume, then restart: cancel wins and drains safely; no later automatic restart.",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "Orchestrator-settled P2 scenario I14 supplied in this assignment; this is delegated intent, not a verbatim owner-authored scenario.",
          "choice": "The exact core operation set includes core.resume-import for fresh approved continuation and core.cancel-import for explicit cancellation; existing operation identifiers remain expected.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/p2-interrupted-imports.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java#definitionsContainTheExpectedSeedEntries"
      ]
    }
  ]
}
```

## Acceptance records
