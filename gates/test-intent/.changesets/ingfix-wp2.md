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
          "choice": "S-D90 (Legacy recovery certifies untouched old chunk vectors) Recover a rescue-eligible index with distinguishable old chunk vectors and require their replacement before certification.",
          "label": "agent choice"
        },
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/InPlaceEmbeddingRebuildRecoveryTest.java#rescueReplacesOldChunkVectorsAndTerminalFailureRemovesOldParentVector"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D90 (Legacy recovery certifies untouched old chunk vectors) Recover a rescue-eligible index with distinguishable old chunk vectors and require their replacement before certification.",
          "label": "agent choice"
        },
        "reproduction": "Seed distinguishable parent/chunk vectors, rescue, permanently fail a parent, certify and query/reopen. Baseline leaves the old chunk completed and searchable."
      },
      "reproduction": "Seed distinguishable parent/chunk vectors, rescue, permanently fail a parent, certify and query/reopen. Baseline leaves the old chunk completed and searchable.",
      "behaviour": "Rescue invalidates completed chunk vectors before certification, waits for enabled chunks, removes terminal parent vectors, and retains lexical/sparse state across reopen. Parameterized naming only changes evidence selection."
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
      "class": "Defect pin",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D90 (Legacy recovery certifies untouched old chunk vectors) Recover a rescue-eligible index with distinguishable old chunk vectors and require their replacement before certification.",
          "label": "agent choice"
        },
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
          "choice": "C4. D93 \u2014 preserve: Active and candidate generations must use their own exact model and companion assets despite changed desired settings; GenerationModelSelection supplies generation-specific fingerprints and directories; run generation A while selecting B and verify each independently.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp2.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerTest.java#onlyEnabledChunkStageBlocksEmbeddingCutover"
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D90 (Legacy recovery certifies untouched old chunk vectors) Recover a rescue-eligible index with distinguishable old chunk vectors and require their replacement before certification.",
          "label": "agent choice"
        },
        "reproduction": "Hold parent counts clear and chunk pending count at seven; enabled chunks must block cutover. Baseline ignores this stage; disabled is a preservation case within the same named parameterized check."
      },
      "reproduction": "Hold parent counts clear and chunk pending count at seven; enabled chunks must block cutover. Baseline ignores this stage; disabled is a preservation case within the same named parameterized check.",
      "behaviour": "Only an enabled pending chunk-vector stage blocks embedding cutover. Adaptation: the existing cutover fixture supplies a readable generation-owned ONNX graph/tokenizer and captured selection, preserving the same counter faults and attestation assertions."
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
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Input re-encoding: the independent eager geometry oracle now repeats framing tokens with content capacity reduced by two. The same bounded-heap lazy-vs-eager obligation and traversal checks survive. Supplementary preservation check (excluded from newChecks): TokenWindowsTest#threeMillionTokensFitWithEightLazyWindowsButEagerCopiesExhaust128MiB.",
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
      ]
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
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Input re-encoding/instrumentation: window thresholds reserve two framing tokens, and truncation telemetry reports zero dropped documents now that overlapping tokenizer windows cover the complete input. Histogram and persistence assertions still observe the same counters through the corrected window design.",
      "sources": [
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
          "choice": "C14. D72 \u2014 already broken: Token-dense input must reach NER and chunk SPLADE inference completely, including tails beyond estimated character overlap; ChunkSplitter estimates characters while both encoders truncate actual tokens; project retained token offsets back to source.",
          "label": "agent choice"
        }
      ]
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
      "behaviour": "Terminal parent and chunk failure updates explicitly remove prior vectors; retryable updates keep them. Adaptations: recovery mocks forward strict throwing enumeration to the existing readable fixture and account for pending coverage; enumeration-fault and missing-status inputs retain fail-closed coverage at those boundaries."
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
      "class": "Adaptation",
      "moved": [
        "rename",
        "instrumentation"
      ],
      "whatMoved": "Adapt recovery invalidation expectations: keep dense vectors during retry, remove them at terminal failure, and retain completed independent SPLADE postings across bookkeeping, rename and reopen. Lexical postings, positions, norms and chunk coordinates/proofs keep their prior obligations. The renamed test states the terminal-vector retirement contract.",
      "sources": [
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
          "choice": "C21. D90/D91 \u2014 preserve: Recovery must retain lexical content, chunk coordinates/revision witnesses and unrelated enrichment; chunk RMW reconstructs non-stored text from its parent; compare keyword/chunk search and stored metadata before and after recovery.",
          "label": "agent choice"
        }
      ]
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
