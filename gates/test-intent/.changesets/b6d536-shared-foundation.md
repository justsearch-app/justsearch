---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, shared foundation (S-1) for GPU co-residence (item 5) and process ownership
and exit accounting (item 6). Every flagged item is a new check of behaviour this branch adds; none
changes or removes a pre-existing expectation. `StatusRecordSchemaTest` and
`LocalApiCorsPolicyTest` keep every existing check unchanged and gain new ones (see their entries).

ADR-0050 and ADR-0051 record these decisions but are added in this PR, so the gate rejects them as
sources (`source-written-in-pr`). The entries cite the owner-adopted scenarios, base ADRs that this
PR does not touch, and the owner's delegation for the agent's specific choices.

All new checks ran in one evidence run: `gates/test-intent/evidence/b6d536-shared-foundation.json`.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/OperatingStateViewTest.java"
      ],
      "class": "New",
      "behaviour": "One app-api vocabulary for the chat, indexing, GPU, degradation and freshness states that the status response, MCP and tools read. ChatVocabularyTest: every chat enum has exactly its members, in order, and serializes as the constant name. In ChatStatus each reason slot is legal only in its own state; UNLOADED needs a reason, and a pressure unload names gpu_busy or game_mode; generating is exactly inFlight > 0. Activation names its reason exactly when it is not effective. ChatRefusal and Demand reject negative durations. OperatingStateViewTest: the operating enums have exactly their members and their wire casing, and a chat detail serializes as its lower-case token. A chat reason must belong to the vocabulary of its state. Indexing reasons are distinct and ordered, and are required when PAUSED or SLOWED. An unknown gpu block carries no readings, and a profile source pairs only with a resolved profile. The block validates its envelope and omits absent chat and indexing blocks on the wire.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The member lists of the chat and operating enums, defined once in app-api. Wire casing is the constant name, and chat details are lower-case tokens. A reason is valid only in the state it explains (an unload names its reason, a pressure unload is gpu_busy or game_mode, PAUSED and SLOWED name why). Generating is derived from inFlight. Durations are non-negative. An unknown gpu block has no figures, and absent chat and indexing blocks are omitted on the wire rather than sent as nulls.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/OperatingStateViewTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlVocabularyTest.java"
      ],
      "class": "New",
      "behaviour": "The runtime.child vocabulary that owners use to stop helpers and record why. Every enum has exactly its members, in order. Names are UPPER, detail tokens are lower case, and both round-trip. Exactly QUIT, UPGRADE, ENGINE_RESTART and DEVICE_LOST end the Engine. Only REQUESTED is shared between the exit class and the supervision contract's restart class. ChildSpec copies its collections and rejects a path as a label or an empty argv. A cooperative stop of NONE has no grace, and grace is never negative. TerminateResult carries an error exactly when FAILED, with its ledger wire form. A REQUESTED ExitOutcome names its reason, and a terminate time pairs with the terminate call. ChildSurvivedException names the survivor.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S14",
          "quote": "If the Engine crashes, hangs or is restarted, every helper ends with it (including a chat server just started but not yet registered); the supervisor restarts it and chat returns without user action (D2). Unless a game or low GPU memory blocks it, in which case chat shows why.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The stop-reason, stop-mode, exit-class and child-kind members, and the rule that exactly QUIT, UPGRADE, ENGINE_RESTART and DEVICE_LOST end the Engine. The exit class is kept apart from the supervisor's restart class except for REQUESTED, so an intended stop and a crash are never conflated. ChildSpec labels are diagnostic, never paths, and argv is never empty. A failed terminate always carries its error. A requested exit always names its reason.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildControlVocabularyTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/EngineSelfIntentWriterTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerReaderTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerSchemaTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java"
      ],
      "class": "New",
      "behaviour": "The exit ledger in which owners record each process stop before acting on it. The ledger is not yet wired to any stop path.\nExitLedgerWriterTest: records round-trip with the v1 envelope, seq starts at 1, and files land under runtime/exit-ledger. A torn tail is closed with a newline at session start. A torn line another writer leaves mid-session is closed before the next record, so a record reported durable always parses. A write failure returns false, never throws, and leaves a ledger-gap record on the next write; every attempt spends a seq, so a lost record leaves a visible hole. A rotating writer rotates at session start over the session size and before an append would pass the hard cap, keeping one older file. A rotation failure keeps appending and records ROTATE_FAILED. An append-only writer never rotates. Caller bugs are refused: wrong family, envelope fields, oversized records.\nExitLedgerReaderTest: missing files are an empty ledger. A newer schemaVersion is refused line by line and counted, never fatal. A torn last line is ignored and reported. Malformed, oversized and envelope-less lines are corrupt, and unknown and foreign types are counted. An unknown timestamp reads as unknown without dropping the record. The older generation is read first.\nExitLedgerSchemaTest: every record the Java writers write, gap records included, conforms to governance/exit-ledger.v1.schema.json. The schema rejects what a writer must never write. Its enums are exactly the app-api enums.\nEngineSelfIntentWriterTest: the Engine's own intent to exit is written to engine.v1.jsonl with its identity, reason, detail and expected code. Only a reason that ends the Engine is accepted. An unwritable ledger never stops the exit, and the writer reports itself unhealthy. The identity refuses values a reader could not use.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S17",
          "quote": "Dev and app hosts give the same accounting and cleanup when the Engine dies during startup or later; an identity lookup failure reads as unknown, never dead.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The ledger format and its failure handling. Append-only JSON lines of at most 4 KiB, forced to disk per record, with a v1 envelope (schemaVersion, type, seq, at, writer). seq is spent per attempt, and a failure is written as a ledger-gap record rather than stopping the caller. A torn line is closed with a newline before the next record. Retention is by size: rotate over 1 MiB at session start and before an append past 2 MiB, keeping one older generation. Readers tolerate crash leftovers and newer versions line by line. A machine-readable schema has the same enums as the Java types. The Engine's self-intent record accepts only reasons that end the Engine.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/EngineSelfIntentWriterTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerReaderTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerSchemaTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/ExitLedgerWriterTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/gpu/GpuMemorySampleTest.java",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/WindowsGpuMemoryReaderTest.java",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/WindowsGpuMemoryReaderMachineTest.java"
      ],
      "class": "New",
      "behaviour": "Reading adapter and per-process graphics memory, with no GPU work, so external pressure can be observed.\nGpuMemorySampleTest: a LUID is formatted and normalized the way PDH names its instances. UNKNOWN carries a reason and no values, and frees nothing. A known sample names its device, total and used, and free is floored at zero. Per-process values come only from PDH and are never negative.\nWindowsGpuMemoryReaderTest (fake sources): PDH gives adapter use and per-PID dedicated and shared use, on the NVIDIA LUID only. A mapped compute device picks its adapter among several and keeps its CUDA ordinal. Several NVIDIA adapters without a map are UNMAPPED_MULTI_GPU, never a guess. NVML is used only as a fallback for a single NVIDIA adapter and a single NVML device, and gives totals without per-process memory. No NVIDIA hardware adapter is UNKNOWN, and the adapters are listed again next time. An adapter-list failure is retried. Every source failing, linkage errors included, is READ_FAILED and never throws. Another OS is UNSUPPORTED_OS and touches no native source. Concurrent samples are serialized. close releases every source once, and later samples are CLOSED.\nWindowsGpuMemoryReaderMachineTest (Windows only): the real PDH instance names carry the DXGI LUIDs. A real reading is PDH-known on one NVIDIA adapter, otherwise a named UNKNOWN, and never throws. The NVML fallback reads the single NVIDIA adapter. It reads counters only and creates no device.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S10",
          "quote": "On multi-GPU and hybrid laptops, chat and encoders use the same chosen GPU.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "PDH GPU adapter-memory and process-memory counters are the primary source, matched to the adapter by its DXGI LUID. NVML is a totals-only fallback, used only when the mapping is unambiguous (one NVIDIA adapter and one NVML device). An ambiguous multi-GPU machine reads UNKNOWN rather than guessing. Every failure is a named UNKNOWN and never an exception, so a machine without a usable NVIDIA GPU is unaffected. Native reads are serialized and close is idempotent.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/gpu/GpuMemorySampleTest.java",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/WindowsGpuMemoryReaderTest.java",
        "modules/gpu-bridge/src/test/java/io/justsearch/gpu/memory/WindowsGpuMemoryReaderMachineTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusOperatingStateTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java"
      ],
      "class": "New",
      "behaviour": "The operating block of /api/status, projected from the same status pass the rest of the response comes from, so the UI and every other reader see one state.\nOperatingStateAssemblerTest: chat state comes from the runtime ENGINE axis (Healthy READY; Starting and Recovering LOADING, with recovery as the cause; Down FAILED when the generative component failed, else OFF), otherwise from the generative component. Chat reasonCode and since are the generative component's own. Nothing unobservable is guessed (generating, inFlight, queued, blockedBy, load and reloadAllowed stay null). The chat block is absent while the Engine stops. Indexing is IDLE with nothing pending, RUNNING with work, and PAUSED for energy saver with work pending; since moves only on a state or reason change. Indexing is absent without a Worker view and listed stale when the sample is; staleAfterMs is three sampler periods. Without a reading, gpu is unknown and degradations are empty. A projected gpu reading passes through: byte figures do not move seq, the source does, and a stale reading is listed. seq starts at 1 and grows only on content changes, not sample times. Every projected view validates.\nStatusOperatingStateTest (the real status handler): chat projects the ENGINE axis and the same generative component the response shows. A healthy engine is READY, and repeated reads keep seq. A wired GPU reader fills operating.gpu after a background first sample. A status pass during that sample does not wait and reports unknown. Closing the reader returns gpu to unknown.\nOperatingGpuProjectionTest: PDH gives total, free, our dedicated and shared use (Engine plus its child processes) and external use (adapter used minus ours, floored at zero). A failed child-process listing counts the Engine alone. NVML gives adapter figures only. Any UNKNOWN reading is the unknown block. Before the first sample exists, a pass is unknown and never calls the reader. A pass does not wait for a first sample in flight. A reader that throws, or fails to link, is the unknown block, cached for the window. One sample per 2 s window, inside which only the age moves; concurrent passes after the window take exactly one new sample. A reading older than three windows is stale.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P7",
          "quote": "Energy-saver indexing pause and replacement admission keep their behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0034-fe-backend-owned-truth.md",
          "quote": "Backend-owned runtime truth must be declared through resources, operations, prompts, catalogs, or explicit APIs. Product surfaces must not maintain private readiness or capability state machines for backend concepts."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "This first producer projects only what the status pass already observes and leaves everything else null or unknown. The mapping is from ENGINE axis and component state to chat state; from pending jobs and the energy-saver poll to indexing (the condition the Queue card already shows). Sample times do not change seq. Staleness is three sampler periods, and a GPU reading is stale after three cache windows. The GPU reading is diagnostic only. It is read at most once per 2 s under the reader's serialization. The first (slow) sample is taken on a background thread when the reader opens, with gpu unknown until it exists. A failing reader is unknown and cached for the window. Our use is the Engine plus its descendant processes, and external use is adapter used minus our dedicated use, floored at zero.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusOperatingStateTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProjectionTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java",
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "New",
      "behaviour": "The new operating block is part of the status record's generated JSON schema and of the live fixture that the frontend contract tests read. StatusRecordSchemaTest's sample status response gains a populated operating block (sampleOperatingState): every block is present, and nullable fields are both set and null. The existing checks therefore now also cover it: the sample conforms to the generated schema, and the regenerated status-response-live.json matches it. The test's other edits only pass the new StatusResponse argument (null where a check is not about it); no existing check's inputs or expected outcomes change otherwise. The fixture gains the same operating block, written by the test's fixture writer (updateSchemas), not by hand. The frontend tests that read it (contract.test.ts, status-response.test.ts) pass against it. Evidence limit: run-evidence.mjs reads only TEST-<fqcn>.xml, and this class's checks sit in @Nested classes with their own XML files. The evidence therefore records one case for StatusRecordSchemaTest. The same Gradle invocation did run the nested classes (CrossLanguageContract 'StatusResponse fixture matches current Java serialization', ContractValidation, SchemaDrift, BackwardCompat, SsotFieldCompleteness; all passed), but the evidence file does not show them. The two frontend checks are recorded in it.",
      "sources": [
        {
          "kind": "decision-record",
          "location": "docs/decisions/0038-wire-contract-source-of-truth.md",
          "quote": "The FE↔backend wire contract is a first-class, versioned artifact; hand-written per-language mirrors are forbidden; every consumer-side type is mechanically generated."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java",
        "modules/ui-web/src/api/contract.test.ts",
        "modules/ui-web/src/api/generated/schema-types/status-response.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/ChatUnavailableErrorTest.java"
      ],
      "class": "New",
      "behaviour": "The typed refusal for chat that is installed but not serving this caller now. CHAT_UNAVAILABLE is TRANSIENT and retryable. Its HTTP body carries errorCode, errorClass and retryAfterMs, and conforms to the api-error-response schema. The MCP tool result carries the same code, retryable flag and retryAfterMs in structured content, plus a 'Retry after' line, and isError. A permanent code cannot carry a retry time, and a negative one is refused. An unknown retry time is absent, never zero.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "The refusal is one new ApiErrorCode, CHAT_UNAVAILABLE, of class TRANSIENT, beside the unchanged AI_STARTING and AI_OFFLINE. It carries an optional retryAfterMs on both HTTP and MCP. That field is omitted when the Engine does not know when to retry, and is allowed only on retryable codes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/ChatUnavailableErrorTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/clientKind.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiCorsPolicyTest.java"
      ],
      "class": "New",
      "behaviour": "The webview declares X-JustSearch-Client-Kind: WEBVIEW only on requests that are already CORS-preflighted, so no simple request gains a preflight.\nclientKind.test.ts: the header is never added to a GET or HEAD (any casing, Request input included). It is never added to a non-JSON write: multipart, a text body, or no body. It is added to POST, PUT, DELETE and PATCH with a JSON content type (parameters and casing ignored), keeping every other init field and the caller's header shape (record, Headers or tuple list). A kind the caller declared is never overridden. A Request input's method and headers are read and kept. The admission seam sends the header on the first attempt and on every retry of a JSON write. shell-v0 authorizedFetch sends no header on a GET and the header on a JSON write.\nLocalApiCorsPolicyTest gains one check: in prod, a preflight from the desktop origin for a POST that requests content-type and x-justsearch-client-kind is answered 200, advertises POST, and allows x-justsearch-client-kind. The file's existing checks are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S8",
          "quote": "When another app takes GPU memory and chat is idle, the chat server unloads with a visible reason; the next human chat shows loading and answers; MCP gets a typed refusal instead of a forced load (D8).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "decision-record",
          "location": "docs/decisions/0046-local-api-trust-boundary.md",
          "quote": "In prod only the desktop origins (`tauri://localhost`, `http(s)://tauri.localhost`) are granted."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "choice": "To tell the human webview from agents before chat demand distinguishes them, the webview declares its client kind. It does so only on non-GET, non-HEAD requests with Content-Type application/json, which are already preflighted. GETs, multipart uploads and SSE keep resolving to WEBVIEW by the Engine's existing default, so no simple request changes and no extra preflight appears. The established contract is unchanged: a missing header still resolves to WEBVIEW, and the CORS configuration is not modified.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-shared-foundation.json",
      "newChecks": [
        "modules/ui-web/src/api/clientKind.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/LocalApiCorsPolicyTest.java"
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
  "session": "58e59dda-7611-47f4-98b5-59be68ef30a4",
  "verdict": "accept",
  "digest": "sha256:b9a530b5bb5682a49a80da4d200ad29267ee39f16554eb8045554c11473828d8"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "0ae522aa-cda1-4c85-9e8f-65677d5cc3c0",
  "verdict": "accept",
  "digest": "sha256:33e5ce82a186f212e2b9ff15494e0f88cd8e8e98fecd00cdbd82c264fc2cc6fb",
  "note": "Verifier f12: refreshed evidence digest matches the current ChildControlVocabularyTest; the candidate test fails exactMembers on f75486245 and passes on ef6df5aec."
}
```
