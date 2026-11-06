---
schema: test-intent.v1
task: t-20261105-secretfiles
author-role: builder
author-session: secretfiles-builder-4
---

# Test-intent entries

New security checks are isolated test sources. These entries account for superseded expectations
in existing tests; the independent verifier checks the new sources and fail-before evidence.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerIngestionAuthorityTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/AdmissionSourceHashTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Pass an explicit null collection to the remaining admission signature. The removed two-argument overload passed null to this same method; every existing input, assertion, fixture, resource and timing limit is unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceRequestEndTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Mark the existing successful document-fetch fixture found=true, matching the Worker's successful response. Its content, IDs, exception and wrapper matrix, caller scopes, cancellation/deadline checks and every assertion remain unchanged; the ownership-preserving fallback now honors the existing success flag."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceFetchEndpointsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Adapt the mocked field reader to the combined content-and-ownership batch read. The adapter answers from the existing content and individual-field stubs, so all existing payload-size, request-size, found/missing and cancellation assertions and read-count verifications remain unchanged. Production reads content and its ownership facts from one immutable index reader."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/ingest/IngestionSkipPolicyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "go with items 1 and 2",
          "delegationLocation": "task t-20261105-secretfiles request",
          "delegationScope": "Item 1 as offered: \"The security fix. It removes password and key files from the index, deletes those already indexed, and stops outside assistants from searching the built-in assistant's history.\" Scenarios, protected-file definition and implementation design are orchestrator choices within this delegation.",
          "choice": "S1: \"Under a watched root, protected files are never indexed: not findable by keyword or meaning search, not listed by folder browse, not shown as failed jobs.\" Delegated design: .env and case variants are protected even under operator skip-rule overrides, superseding their former exemption. P1: \"Ordinary files (not protected, not below a dot-directory) are indexed and searchable as before on every arm; `.gitignore` stays exempt.\" Ordinary exclusion rules and the .gitignore exemption remain. S6: \"The removal and later protected skips are visible: the log names the count removed and the skip reason distinguishes protected files from ordinary temp/system skips.\" Added reason-code and .gitignore assertions are isolated in WorkerProtectedAdmissionReasonTest; this Obsolete entry covers only the superseded .env exemption.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none; repeated search of docs/decisions for .env, .gitignore, protected files and hidden directories found no conflicting contract. Only the former .env exemption is superseded; ordinary exclusions and the .gitignore exemption remain. WorkerUserExclusionsTest now passes an explicit null collection to the remaining admission signature after deleting the unused two-argument wrapper; every input and assertion is preserved."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerCollectionScopeTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "go with items 1 and 2",
          "delegationLocation": "task t-20261105-secretfiles request",
          "delegationScope": "Item 1 as offered: \"The security fix. It removes password and key files from the index, deletes those already indexed, and stops outside assistants from searching the built-in assistant's history.\" Scenarios, protected-file definition and implementation design are orchestrator choices within this delegation.",
          "choice": "S7: \"Outside assistants (MCP) cannot reach the built-in assistant's history, in every runtime mode: search and answer with a collection scope that includes agent-history (alone or mixed) are refused with a clear error, not an empty result\". P5: \"MCP search and answer without a collection filter, or with the user's own collections, work as before\". Preserve personal-collection forwarding and independent history-refusal coverage.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none; collection forwarding is preserved for the user's personal collection and history refusal is checked independently"
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineCorruptionRebuildTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExclusionReplayPolicyTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineFrozenCollectionAdmissionTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineHintlessCollectionMigrationTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineMigrationCollectionOwnershipTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineMigrationLifecycleTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSwitchingFenceBufferingTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineVduMigrationReplayTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableQuitReplayTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecoverableChunkCompletenessTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Ordinary user corpus paths move from children of the app data directory to sibling paths owned by the same temporary fixture. Migration child-process path derivation moves identically. Every assertion, fault, timeout and lifecycle remains unchanged. S11 intentionally protects app data, while these fixtures exercise ordinary user content."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Folder and search response fixtures answer the same API by method name across the original and authority-carrying signatures. Every HTTP status, activity interruption, resumed unit, outcome and timing assertion is retained. The fixture compiles and runs on both APIs."
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSyncDirectoryTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "go with items 1 and 2",
          "delegationLocation": "task t-20261105-secretfiles request",
          "delegationScope": "Item 1 as offered: \"The security fix. It removes password and key files from the index, deletes those already indexed, and stops outside assistants from searching the built-in assistant's history.\" Scenarios, protected-file definition and implementation design are orchestrator choices within this delegation.",
          "choice": "S1: \"Under a watched root, protected files are never indexed: not findable by keyword or meaning search, not listed by folder browse, not shown as failed jobs.\" Protected definition (b): \"any file below a directory whose name starts with `.`, counted from the boundary\". The .obsidian/app.json expectation becomes protected absence; the six ordinary-file search positives remain.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: the old test explicitly admits .obsidian/app.json below an ordinary root; S1 now supersedes this expectation. Its six ordinary-file search assertions remain, and the seventh is an explicit absence assertion for the protected metadata."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/BootstrapPhysicalInitializationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Configure the same healthy/throw/recovered client response sequence before starting the bootstrap; asynchronous cleanup can otherwise race Mockito when/then stubbing on that same mock. Startup and every health, reinitialization, count and lifecycle assertion remain unchanged."
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/Release030DurableIntentCancellationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Remove one pre-existing extra blank line at end of the Java source to satisfy the unchanged Spotless endWithNewline rule. The executable tokens, assertions, fixtures, resources, tags, timing and test selection are byte-for-byte unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/DeletionCompletionOutcomeTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Select the remaining completion-receipt signatures: two arguments for prefix deletion and three for file deletion, supplying the fixture's existing deleteByIdFn callback to file deletion. The Apply proxy forwards its existing path and context through the same selector. Every assertion, request, response, callback, resource and timing limit is unchanged; unused production wrappers are removed rather than retained for reflection."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchControllerConfigStoreTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchControllerFeedbackObserverTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/RetrieveContextControllerFallbackTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Forward the server-supplied read-authority overload to each existing mock response. The same response, captured configuration and old-signature interactions are preserved. Every assertion, message, failure, timeout and selection remains unchanged."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/app/engine/Bm25OnlyCursorHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchDuringChatDrainTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/SpladeOnlyBoundaryFallbackTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Move the cursor fixture's observer onto the authority-carrying Worker search overload, and forward that overload to the existing chat-drain and SPLADE boundary mock responses. Every assertion, message, request/response capture, timing, failure input, resource, and test selection is unchanged. HTTP pagination already returned the expected pages; its old-signature observer received no calls, so getFirst failed on the empty observation list."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/RetrievalRequestEndProjectionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Run the authority-carrying DocumentService default overload on the existing mock so it forwards to the same old-signature failed future. All 21 REST request-end cases and the MCP cases retain their exact failures, wrappers, assertions, status codes, reason codes, interrupted-thread checks, timing and selection."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceMatchCitationsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/RagContextOpsQualityTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The existing mocked read views classify their synthetic ordinary IDs (source, a, b and their chunks) as public, matching the real ownership check for these non-file IDs. The same lookup then throws the same wrapped cancellation, and the same RAG passages reach the unchanged Unicode budget rejection. Every original assertion, cancellation branch, timing, input, response alignment and source inclusion check is unchanged. No production ownership check is bypassed or weakened."
    },
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/mcp/RetrievalRequestEndInterfacesIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Forward the new authority-carrying rich retrieval overload into the fixture's existing real-Engine deadline/cancellation/ordinary-failure interception. HTTP and MCP continue through the same real provider gate and saved-conversation fixtures. Every original HTTP status, reason code, deadline, interruption, next-turn persistence assertion, chosen-file fallback, restricted failure, query count, and old-signature interaction assertion remains unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1af86-7ec4-7012-8b51-c9c7e39339bd",
  "verdict": "accept",
  "digest": "sha256:d5bc71225613dc38a6331e6a852426e0c3e14d93a15ebc36bfe37d8aeb0fdf8f",
  "note": "Transcribed by the orchestrator from the verifier's result (Codex sandbox cannot run accept). All 16 entries accepted on digest sha256:d5bc7122...; delegated sources are agent choices, not owner-adopted scenarios; a queue-lock SQLITE_BUSY in EngineHintlessCollectionMigrationTest passed on focused repeats on both commits, kept as a separate finding."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1afa1-e850-7ec0-bc15-5e4f0c31ace8",
  "verdict": "accept",
  "digest": "sha256:6206238de2b641bcfd72f2e24163efcbda6d6b38e33a53f2a7b90cb36ab8caff",
  "note": "Transcribed by the orchestrator from the verifier's result (Codex sandbox cannot run accept). Accept all 15 entries on this digest. Sources are delegated choices, not owner-adopted scenarios; no conflicting decision source found. 438 baseline and 447 candidate tests passed, zero skips."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a1afa1-c13e-7e70-9e55-ef8c6e4567f7",
  "verdict": "accept",
  "digest": "sha256:6206238de2b641bcfd72f2e24163efcbda6d6b38e33a53f2a7b90cb36ab8caff",
  "note": "Transcribed by the orchestrator. Independent audit accepts all 15 entries on this digest; before-state tests and supplied sources reviewed before provisional classification and candidate diffs, then the class table. No must-fix findings. Static test-intent audit only; no builds or tests executed."
}
```
