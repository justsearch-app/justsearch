---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: wp2-builder-ti2-20261101
---

# Test-intent entries

Base: 09c246ae15a9611482b7bae712a326a5259d9a1d. The CLI enumerated 25 flagged files and four isolated new test sources exempt from entries. The entries below cover all 25 flagged files. Mixed files describe their fixture/signature adaptations explicitly beside their added defect checks.

Evidence is intentionally left for the orchestrator to generate using run-evidence.mjs; no evidence or acceptance is hand-authored. The compatibility patch adds only missing signatures with graph-only baseline behaviour or neutral defaults. No repaired implementation is overlaid.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/IndexFingerprintTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/IndexFingerprintTest.java#companionOutageKeepsKnownGraphAndGraphOutageKeepsKnownCompanions"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        "reproduction": "Construct graph-only and companions-only identities; baseline has no independent companion state and continues to compute graph-only identity."
      },
      "reproduction": "Construct graph-only and companions-only identities; baseline has no independent companion state and continues to compute graph-only identity.",
      "behaviour": "Keep readable graph and companion identity independently; unavailable companions prevent a complete attestation."
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommitOpsTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D93 (Model fingerprints omit output-determining companion assets) Change tokenizer vocabulary IDs without changing ONNX and require compatibility rejection.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/CommitOpsTest.java#lexicalCommitPreservesUnresolvedCompanionAndRejectsReplacementAfterReopen"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        "reproduction": "Stamp a role identity, make its companions unreadable, perform a lexical update, then replace graph and companions independently. Baseline never stores the separate companion key."
      },
      "reproduction": "Stamp a role identity, make its companions unreadable, perform a lexical update, then replace graph and companions independently. Baseline never stores the separate companion key.",
      "behaviour": "Across a lexical commit and reopen, unresolved companions preserve committed provenance, never certify it, and readable graph/companion replacements remain detectable."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/InPlaceEmbeddingRebuildRecoveryTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21. D90/D91 \u2014 preserve: Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. Recovery \u2014 preserve: All-failed recovery must remain uncertified, while mixed success/failure can finish once old vectors are inaccessible; the controller explicitly distinguishes these distributions and ignores unrelated queue depth; exercise both plus unreadable counters.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/InPlaceEmbeddingRebuildRecoveryTest.java#rescueTerminalFailureRemovesOldParentVector"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        "reproduction": "Recover two completed old-vector parents using main legacy recovery, successfully re-embed one and permanently fail the other, then certify and query/reopen. Baseline still returns the failed parent through dense search."
      },
      "reproduction": "Recover two completed old-vector parents using main legacy recovery, successfully re-embed one and permanently fail the other, then certify and query/reopen. Baseline still returns the failed parent through dense search.",
      "behaviour": "Terminal parent failure removes its old vector independently of legacy recovery preparation; certification and reopen expose only the successful parent and retain lexical content. S-D90 chunk re-marking and pre-certification invalidation are withdrawn."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/ServiceOutageBoundaryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/SystemicBatchDeferralTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/AcceptedProjectionBroadReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduReplayFailureRetentionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Adapt mock instrumentation from a void indexSingle to the acceptance-returning signature required to observe rejected inline vectors. doAnswer returns null in the same no-op lanes; injected failures, deferrals, retries and assertions stay unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D89 (Rejected inline vectors earn compatibility success) Submit the first inline non-finite vector and require no compatibility success evidence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D93 (Model fingerprints omit output-determining companion assets) Change tokenizer vocabulary IDs without changing ONNX and require compatibility rejection.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D93 \u2014 implied: Identity must cover consumed output-determining assets beyond the tokenizer: SPLADE vocabulary, NER label mapping, pooling, prefixes and context configuration; their loaders read these separately; mutate each effective input and require rejection of the old identity.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. D93 \u2014 preserve: Active and candidate generations must use their own exact model and companion assets despite changed desired settings; GenerationModelSelection supplies generation-specific fingerprints and directories; run generation A while selecting B and verify each independently.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C6. D93 \u2014 implied: Existing graph-only attestations cannot silently acquire stronger provenance after upgrade; compatibility and index-parity readers consume the saved identities; reopen an old-format generation and verify its explicit compatibility/recovery outcome.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Existing indexes whose model assets are unchanged are not forced into re-embedding or legacy recovery by the new identity (an index without the new identity fields adopts the current one).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java#companionChangesRejectCompatibilityWithoutChangingGraphIntegrity",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java#externalWeightsChangeRejectsCompatibilityAfterCommitAndReopen",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java#externalReferencesUseExactGenerationGraphRatherThanSiblingVariant",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java#graphOnlyGenerationAdoptsWithoutClaimingCommittedCompanionProof",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GenerationModelSelectionTest.java#companionReadFailureIsIndeterminateAndGenerationsKeepIndependentAssets"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D93 (Model fingerprints omit output-determining companion assets) Change tokenizer vocabulary IDs without changing ONNX and require compatibility rejection.",
          "label": "agent choice"
        },
        "reproduction": "Commit model metadata, mutate effective companions or selected external weights with graph bytes unchanged, then inspect parity after reopen; baseline ignores companions. Legacy adoption lacks its explicit provenance diagnostic."
      },
      "reproduction": "Commit model metadata, mutate effective companions or selected external weights with graph bytes unchanged, then inspect parity after reopen; baseline ignores companions. Legacy adoption lacks its explicit provenance diagnostic.",
      "behaviour": "Role-specific companion appearance/change/removal, unreadability, exact graph selection and external weights reject stale identity independently of graph integrity. Legacy adoption is explicit and never manufactures persisted proof. Adaptation: fixture SHA hashes exact graph bytes rather than decoding/re-encoding text; parameterized names expose method ids."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerTest.java"
      ],
      "class": "Adaptation",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. D93 \u2014 preserve: Active and candidate generations must use their own exact model and companion assets despite changed desired settings; GenerationModelSelection supplies generation-specific fingerprints and directories; run generation A while selecting B and verify each independently.",
          "label": "agent choice"
        }
      ],
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Supply the existing cutover fixture with a readable generation-owned ONNX graph/tokenizer and captured selection, preserving the same counter faults and attestation assertions. S-D90 chunk-stage cutover checks are withdrawn."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/Release030UpgradeCharacterizationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Input re-encoding: supply the existing model-identity-only path with a minimal valid ONNX graph so new companion inspection can read the fixture. Released-index adoption, analyzer mismatch and recovery expectations stay unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Existing indexes whose model assets are unchanged are not forced into re-embedding or legacy recovery by the new identity (an index without the new identity fields adopts the current one).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OnnxExternalDataTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D93 \u2014 implied: Identity must cover consumed output-determining assets beyond the tokenizer: SPLADE vocabulary, NER label mapping, pooling, prefixes and context configuration; their loaders read these separately; mutate each effective input and require rejection of the old identity.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/OnnxExternalDataTest.java#collectsLocationsAcrossEveryTensorBearingFieldAndSkipsRawData",
        "modules/ort-common/src/test/java/io/justsearch/ort/OnnxExternalDataTest.java#incompleteLocationsAreUninspectable"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D93 \u2014 implied: Identity must cover consumed output-determining assets beyond the tokenizer: SPLADE vocabulary, NER label mapping, pooling, prefixes and context configuration; their loaders read these separately; mutate each effective input and require rejection of the old identity.",
          "label": "agent choice"
        },
        "reproduction": "Use nested/training/function/sparse tensors with location w, and external tensors with missing locations. Baseline has no location collection API; compatibility adds only an empty-set signature, so positive collection and rejection assertions fail."
      },
      "reproduction": "Use nested/training/function/sparse tensors with location w, and external tensors with missing locations. Baseline has no location collection API; compatibility adds only an empty-set signature, so positive collection and rejection assertions fail.",
      "behaviour": "Collect external-data locations throughout tensor-bearing ONNX fields, skip raw payload, and reject incomplete references."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityControllerTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D92 (Preparing fingerprint metadata marks it stored before commit) Fail commit after supplier invocation and require absent stored state plus a retried stamp.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C26. D92 \u2014 implied/preserve: Successful ordinary commits, metadata-only stamps, rebuild finalization, shutdown finalization and reopen must agree on durable identity; these use different acknowledgement paths; check success and failure, retaining existing backoff and the ability of a later ordinary commit to repair persistence.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C22. Recovery \u2014 preserve: All-failed recovery must remain uncertified, while mixed success/failure can finish once old vectors are inaccessible; the controller explicitly distinguishes these distributions and ignores unrelated queue depth; exercise both plus unreadable counters.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityControllerTest.java#unreadableCompanionsDoNotAdoptOrEraseCommittedProvenance",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityControllerTest.java#successfulCommitReadbackClearsAnAbsentStampWithoutDiscardingRepairEvidence",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityControllerTest.java#preparingMetadataDoesNotClaimDurableFingerprint"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C3. D93 \u2014 implied: Missing, unreadable and newly appearing companion assets must remain distinguishable from an unconfigured model; ModelCapabilityResolver has fallback candidates whose later appearance changes behavior; check removal, read failure and fallback-file appearance.",
          "label": "agent choice"
        },
        "reproduction": "Prepare a fresh fingerprint without committing; baseline claims it stored. Remove persisted fingerprint before readback; baseline retains the old cached value. Make companions unavailable with a readable graph; baseline still resolves graph-only compatibility."
      },
      "reproduction": "Prepare a fresh fingerprint without committing; baseline claims it stored. Remove persisted fingerprint before readback; baseline retains the old cached value. Make companions unavailable with a readable graph; baseline still resolves graph-only compatibility.",
      "behaviour": "Companion outages do not adopt or erase provenance; metadata preparation and commit readback reflect durable fingerprint state. Supplementary preservation checks (excluded from newChecks): checkRebuildCompletionRefusesWhenEveryEmbeddingFailed and checkRebuildCompletionStillCertifiesThePoisonPillDistribution."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsHeapProbe.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D71 (Sliding embedding windows omit model framing) Capture every CLS-pooled window and require the proper CLS token at its start.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C11 Every long-document window, including first and final windows, needs the model\u2019s framing and aligned masks/type IDs within its capacity; `TokenWindows.get` returns raw slices; capture actual inference inputs at boundary lengths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12 Single-document, pooled batch, retained-chunk batch and resumable window calls must use consistent window geometry; these paths share `createChunks`, and callers also use `windowCount`; compare counts, ordered windows and pooled outputs.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13 Short-document behavior, mean pooling, model-specific special-token IDs and bounded lazy window allocation must remain valid; existing tests cover geometry and heap usage; check those contracts against the corrected framing rather than preserving the defective raw-slice oracle.",
          "label": "agent choice"
        }
      ],
      "reproduction": "Overlay the independent framed eager oracle on main and compare TokenWindows arrays/counts at small shapes, tails, nested sublists and integer-limit capacity three. Main uses raw-slice stride, endpoints and tiny-tail geometry, so the three geometry/count checks fail assertions.",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D71 (Sliding embedding windows omit model framing) Capture every CLS-pooled window and require the proper CLS token at its start.",
          "label": "agent choice"
        },
        "reproduction": "Overlay the independent framed eager oracle on main and compare TokenWindows arrays/counts at small shapes, tails, nested sublists and integer-limit capacity three. Main uses raw-slice stride, endpoints and tiny-tail geometry, so the three geometry/count checks fail assertions."
      },
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java#matchesFramedGeometryAcrossSmallShapesAndTailBoundaries",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java#sublistsPreserveOrderAndEachGetReturnsIndependentFieldCopies",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java#countHandlesIntegerLimitWithoutTokenArraysOrWindowMetadata"
      ],
      "behaviour": "The eager oracle reserves two framing positions, repeats model-specific framing across all fields, and uses content capacity for stride and final-window boundaries. These are changed outcomes under S-D71/C11-C13, not an unchanged oracle. Supplementary preservation check (excluded from newChecks): modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java#threeMillionTokensFitWithEightLazyWindowsButEagerCopiesExhaust128MiB, which retains bounded lazy allocation versus eager exhaustion."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D71 (Sliding embedding windows omit model framing) Capture every CLS-pooled window and require the proper CLS token at its start.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13. D71 \u2014 preserve: Short-document behavior, mean pooling, model-specific special-token IDs and bounded lazy window allocation must remain valid; existing tests cover geometry and heap usage; check those contracts against the corrected framing rather than preserving the defective raw-slice oracle.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/TokenWindowsTest.java#everyWindowRepeatsModelFramingAndCoversAllContent"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D71 (Sliding embedding windows omit model framing) Capture every CLS-pooled window and require the proper CLS token at its start.",
          "label": "agent choice"
        },
        "reproduction": "Create 1703 identifiable tokens and inspect every actual window, mask and type-id array. Baseline does not repeat the final framing token in the first window."
      },
      "reproduction": "Create 1703 identifiable tokens and inspect every actual window, mask and type-id array. Baseline does not repeat the final framing token in the first window.",
      "behaviour": "Every window repeats framing and covers all content. Adaptations: the former raw-slice geometry oracle becomes framed geometry; invalid capacities one/two become valid framing-aware capacity three; integer-limit count excludes two framing tokens. These follow C13 rather than preserving the defective raw-slice oracle."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/ner/BioTagDecoderTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D73 (NER rejects legitimate short entities) Decode high-confidence short person/location entities and require their survival.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 NER results for ordinary text (entities of three or more letters) are unchanged; empty results stay COMPLETED_EMPTY.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/ner/BioTagDecoderTest.java#highConfidenceShortNamesSurvive"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D73 (NER rejects legitimate short entities) Decode high-confidence short person/location entities and require their survival.",
          "label": "agent choice"
        },
        "reproduction": "Decode Li, Bo, An and CJK names with high-confidence PER/LOC logits. Baseline rejects the legitimate short entities."
      },
      "reproduction": "Decode Li, Bo, An and CJK names with high-confidence PER/LOC logits. Baseline rejects the legitimate short entities.",
      "behaviour": "High-confidence short PER/LOC and CJK names survive. Supplementary preservation check (excluded from newChecks): singleWordPerson, moved unchanged from BasicExtraction to the outer class, with its method DisplayName retained as a comment for evidence selection."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeTruncationEvidenceTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D71 (Sliding embedding windows omit model framing) Capture every CLS-pooled window and require the proper CLS token at its start.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D72 (Character-estimated chunks leave tokenizer coverage gaps) Project retained token offsets for punctuation-dense input and require complete source coverage.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C11 Every long-document window, including first and final windows, needs the model\u2019s framing and aligned masks/type IDs within its capacity; `TokenWindows.get` returns raw slices; capture actual inference inputs at boundary lengths.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C12 Single-document, pooled batch, retained-chunk batch and resumable window calls must use consistent window geometry; these paths share `createChunks`, and callers also use `windowCount`; compare counts, ordered windows and pooled outputs.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C13 Short-document behavior, mean pooling, model-specific special-token IDs and bounded lazy window allocation must remain valid; existing tests cover geometry and heap usage; check those contracts against the corrected framing rather than preserving the defective raw-slice oracle.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C14 Token-dense input must reach NER and chunk SPLADE inference completely, including tails beyond estimated character overlap; `ChunkSplitter` estimates characters while both encoders truncate actual tokens; project retained token offsets back to source.",
          "label": "agent choice"
        }
      ],
      "reproduction": "Record 120/300/700 tokens and flush 120/600 tokens through SPLADE coverage telemetry; main reports two/one truncated documents despite the required full-window coverage. At 447 tokens with capacity 256 and overlap 64, main reports two windows instead of three because it omits framing capacity.",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D72 (Character-estimated chunks leave tokenizer coverage gaps) Project retained token offsets for punctuation-dense input and require complete source coverage.",
          "label": "agent choice"
        },
        "reproduction": "Record 120/300/700 tokens and flush 120/600 tokens through SPLADE coverage telemetry; main reports two/one truncated documents despite the required full-window coverage. At 447 tokens with capacity 256 and overlap 64, main reports two windows instead of three because it omits framing capacity."
      },
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeTruncationEvidenceTest.java#deriveWindowCountUsesOverlapAwareStride",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeTruncationEvidenceTest.java#snapshotReportsTruncationAndWindowHistograms",
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/SpladeTruncationEvidenceTest.java#flushIfNeededWritesEvidenceIncrementally"
      ],
      "behaviour": "Full overlapping tokenizer coverage reports zero dropped documents, including persisted telemetry, and the window count reserves two framing tokens. Histogram totals and incremental file publication remain assertions in these repair checks."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D89 (Rejected inline vectors earn compatibility success) Submit the first inline non-finite vector and require no compatibility success evidence.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java#rejectedInlineVectorsNeverEarnFirstCompatibilityEvidence"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D89 (Rejected inline vectors earn compatibility success) Submit the first inline non-finite vector and require no compatibility success evidence.",
          "label": "agent choice"
        },
        "reproduction": "Write rejected non-finite vectors through the real JobBatchWriter/FieldMapper, inspect persisted failure and fresh-stamp evidence. Baseline latches success from the original input despite rejection."
      },
      "reproduction": "Write rejected non-finite vectors through the real JobBatchWriter/FieldMapper, inspect persisted failure and fresh-stamp evidence. Baseline latches success from the original input despite rejection.",
      "behaviour": "Rejected-only inline NaN/infinite writes never earn compatibility evidence; a later accepted vector does, and later rejection keeps that evidence."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EmbeddingProviderLifecycleTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D92 (Preparing fingerprint metadata marks it stored before commit) Fail commit after supplier invocation and require absent stored state plus a retried stamp.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C26. D92 \u2014 implied/preserve: Successful ordinary commits, metadata-only stamps, rebuild finalization, shutdown finalization and reopen must agree on durable identity; these use different acknowledgement paths; check success and failure, retaining existing backoff and the ability of a later ordinary commit to repair persistence.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EmbeddingProviderLifecycleTest.java#failedCommitAfterSupplierInvocationRetainsDedicatedStampRetry"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D92 (Preparing fingerprint metadata marks it stored before commit) Fail commit after supplier invocation and require absent stored state plus a retried stamp.",
          "label": "agent choice"
        },
        "reproduction": "Invoke fingerprint supplier in commit, throw before durability, then retry after backoff. Baseline records the prepared fingerprint as stored and suppresses the dedicated retry."
      },
      "reproduction": "Invoke fingerprint supplier in commit, throw before durability, then retry after backoff. Baseline records the prepared fingerprint as stored and suppresses the dedicated retry.",
      "behaviour": "Failing a fresh-stamp commit after supplier invocation preserves absent durable state, retry backoff and a dedicated retry."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Adapt signature instrumentation to indexSingle returning acceptance; explicitly return true for the valid precomputed inline vector so existing success-latch expectations still represent an accepted write. Retain injected AssertionError and second-write no-op behaviour.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D89 (Rejected inline vectors earn compatibility success) Submit the first inline non-finite vector and require no compatibility success evidence.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21. D90/D91 \u2014 preserve: Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P5 Recovery and certification on a healthy index still complete without extra work.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#embeddingBatchReadsSparseDictionaryOnce"
      ],
      "behaviour": "A populated eight-parent/64-term sparse fixture requires one dictionary preservation scan for each uniform outcome batch (success, retry, terminal, blank, failed fallback), with unchanged status progression and retained sparse terms. Adaptations: embedding-only/blank RMW expectations retain independent completed SPLADE postings under C21 instead of the old destructive reset. Parameterized names permit selection.",
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Adapt retry publication observation from updateDocument to conditional updateDocumentsBatchIf; the same chunk2 identity, retry count one and omitted terminal status are asserted. Batch publication preserves independent sparse enrichment without repeated per-document reads.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21. D90/D91 \u2014 preserve: Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P5 Recovery and certification on a healthy index still complete without extra work.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingRecoveryOpsTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C19. D91 \u2014 existing unsafe path; runtime trigger unverified: A permanently failed recovered parent or chunk must not expose its old-model vector once compatibility is granted; failure updates omit vectors, RMW preserves them, and dense reads lack status filtering; recover mixed success/failure documents and query both dense interfaces.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C20. D90/D91 \u2014 implied: Interrupted or partially failed recovery must remain safe and resumable; enumeration, RMW, commit and refresh are separate operations; interrupt each boundary, restart, and verify neither incomplete coverage nor exhausted retries permit stale-vector exposure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingRecoveryOpsTest.java#terminalParentAndChunkFailuresExplicitlyRemovePriorVectors"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        "reproduction": "Compute terminal parent/chunk update maps. Baseline lacks the explicit null vector fields needed to remove old vectors."
      },
      "reproduction": "Compute terminal parent/chunk update maps. Baseline lacks the explicit null vector fields needed to remove old vectors.",
      "behaviour": "Terminal parent and chunk failure updates explicitly remove prior vectors; retryable updates keep them. Recovery enumeration, coverage and mocks retain main behaviour; S-D90-only missing-status and chunk-enumeration cases are withdrawn."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentQuarantineProgressTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C19. D91 \u2014 existing unsafe path; runtime trigger unverified: A permanently failed recovered parent or chunk must not expose its old-model vector once compatibility is granted; failure updates omit vectors, RMW preserves them, and dense reads lack status filtering; recover mixed success/failure documents and query both dense interfaces.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentQuarantineProgressTest.java#terminalChunkFailureRemovesPriorVectorEvenWithoutParentProof"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        "reproduction": "Seed a wrong-revision chunk with a prior vector at its final retry, process real backfill, inspect Lucene vectors and reopen. Baseline retains the prior vector."
      },
      "reproduction": "Seed a wrong-revision chunk with a prior vector at its final retry, process real backfill, inspect Lucene vectors and reopen. Baseline retains the prior vector.",
      "behaviour": "A terminal damaged chunk loses its prior vector even without parent revision proof and stays failed after reopen. Existing damaged/healthy quarantine fixtures are unchanged via the delegating seed overload."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D91 (Failed re-embedding leaves old parent vectors certified and searchable) Recover two old-vector parents, fail one permanently and require no old vector exposed after certification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C19 A permanently failed recovered parent or chunk must not expose its old-model vector once compatibility is granted; failure updates omit vectors, RMW preserves them, and dense reads lack status filtering; recover mixed success/failure documents and query both dense interfaces.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21 Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        }
      ],
      "reproduction": "Seed a legacy chunk with a dense vector and completed independent SPLADE postings, then apply retry bookkeeping. Main resets independent SPLADE status from COMPLETED to PENDING on the first retry, failing the C21 preservation assertion before the later terminal-vector, rename and reopen assertions.",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C21 Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        },
        "reproduction": "Seed a legacy chunk with a dense vector and completed independent SPLADE postings, then apply retry bookkeeping. Main resets independent SPLADE status from COMPLETED to PENDING on the first retry, failing the C21 preservation assertion before the later terminal-vector, rename and reopen assertions."
      },
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#legacyMatchingProofRmwAndRenamePreservePostingsAndRetireFailedDenseVector"
      ],
      "behaviour": "Retryable failures retain the dense vector; terminal failure removes it through rename and reopen. Independent completed SPLADE status and postings survive every failure update under C21. Lexical postings, positions, norms, parent content and chunk revision/coordinate obligations remain asserted within the repaired check."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CandidateIndexTargetCaptureTest.java"
      ],
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D93 (Model fingerprints omit output-determining companion assets) Change tokenizer vocabulary IDs without changing ONNX and require compatibility rejection.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C2. D93 \u2014 implied: Identity must cover consumed output-determining assets beyond the tokenizer: SPLADE vocabulary, NER label mapping, pooling, prefixes and context configuration; their loaders read these separately; mutate each effective input and require rejection of the old identity.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C4. D93 \u2014 preserve: Active and candidate generations must use their own exact model and companion assets despite changed desired settings; GenerationModelSelection supplies generation-specific fingerprints and directories; run generation A while selecting B and verify each independently.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "C5. D93 \u2014 implied: Composite model identity must coexist correctly with raw file-integrity checks; ModelArtifact.sha256 currently feeds both generation fingerprinting and ONNX-file verification; check unchanged bundles still load and graph tampering still fails verification.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 Existing indexes whose model assets are unchanged are not forced into re-embedding or legacy recovery by the new identity (an index without the new identity fields adopts the current one).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CandidateIndexTargetCaptureTest.java#externalWeightReplacementChangesCandidateIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CandidateIndexTargetCaptureTest.java#externalWeightReplacementChangesStartupProviderIdentity",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/CandidateIndexTargetCaptureTest.java#candidateCommitPersistsOwnCompanionsAndRejectsChangedTokenizerOnReopen"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D93 (Model fingerprints omit output-determining companion assets) Change tokenizer vocabulary IDs without changing ONNX and require compatibility rejection.",
          "label": "agent choice"
        },
        "reproduction": "Capture each role, commit its own runtime inputs, replace weights with graph/tokenizer/conventional sidecar unchanged, recapture and inspect committed parity. Baseline ignores consumed external weights and tokenizer companions. Startup graph fingerprints remain equal while companions must differ."
      },
      "reproduction": "Capture each role, commit its own runtime inputs, replace weights with graph/tokenizer/conventional sidecar unchanged, recapture and inspect committed parity. Baseline ignores consumed external weights and tokenizer companions. Startup graph fingerprints remain equal while companions must differ.",
      "behaviour": "Commit candidate-captured runtime inputs, mutate tokenizer or referenced external weights, and reject the separate per-role model_assets_sha256 identity after reopen without changing raw selected-model SHA. Startup providers likewise distinguish external weights. Adaptations: model fixtures use minimal valid ONNX framing and hash those exact bytes; outer LuceneExecutorTestBase supplies isolated executors; parameterized names expose method ids. Supplementary preservation check (excluded from newChecks): candidateModelBytesChangeTargetWithoutProcessWideProviders."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a196d6-0c31-72b1-b7ca-f32fb0817413",
  "verdict": "accept",
  "digest": "sha256:211ebb0384ea79aafc8508a73426156e2073b95d0bdab15171224603e003b465",
  "note": "Focused re-verification of 5f8eb48c82 after the final repair: C3 missing/unreadable referenced external weights leave companion identity unresolved on capture and the serving commit route (fail before on a82a8a0594); D93 and P1 hold; exempt files keep global state; entries 1-22 accepted with reasons. Transcribed by the orchestrator from the verifier's report."
}
```
