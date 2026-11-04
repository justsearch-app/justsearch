---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: builder-s2-pause-20261104
---

# Test-intent entries

The user's answer-policy decision is quoted below. P1/P2 here identify the deliver scenarios, not the separately numbered folder-preservation scenarios. Main already writes schema 7; this change adds schema 8 and keeps schema 7 readable. New expectations are component and public-interface checks, not acceptance of the complete running application. Repair-round before-state checks use worktrees inside a temporary shared clone, with no source-repository worktree or object-store writes. Before-state checks use a shared clone inside the session temporary directory, with its own build folders and copied frontend dependencies; no source worktree or another session's service is used. Execution evidence is written only by run-evidence.mjs. The builder writes no acceptance record. The before-state evidence is a direct run in the shared baseline clone: only the existing settings-HTTP interface and Brain switch checks demonstrate assertion fail-before. Tests needing new Engine/generation APIs have candidate evidence only; no missing-helper compilation error is claimed as fail-before. UiSettingsStorePersistenceModeTest uses nested JUnit suites, which run-evidence.mjs cannot resolve from the outer source-file check ID; it is excluded from the evidence selector rather than reported as executed by that runner. The full app-services test task is part of the separate acceptance run; its result is reported in the builder handback.


The file-only narrowing entries below supersede all earlier backfill classification descriptions and delegated choices. Historical scenario quotations stay verbatim. Only this narrowing's changed checks receive fresh evidence; earlier route/settings evidence remains unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/IndexingAnswerAdmissionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/AnswerPausePreferenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationIndexingPauseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/AnswerPauseSettingsHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseWireTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/IndexingPauseMcpTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentIndexingPauseTest.java",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ],
      "class": "New",
      "behaviour": "The new pause policy persists independently of answer intent; file-only backlog classification is covered by the narrowing entries below. Only new GENERATE exchanges are refused; admitted leases finish and ASSIST/AUX/VDU remain available. The controller keeps its running server, HTTP/proxy/MCP/SSE identify the pause, agents do not retry a paused admission, and Advanced settings defaults off, saves a witnessed single-field patch, preserves answer intent and converges another window. Legacy inputs remain valid; the narrowing entries cover the current threshold and restart expectations. These are bounded component/interface checks; the independent verifier still checks the complete running application.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Design and implement the adopted indexing pause outcome using the owner's Wait, model stays choice; narrow the heavy-indexing signal after exhausted repairs.",
          "choice": "Heavy indexing = file indexing only. ON when indexing is running and not held, using exactly the holds the file indexing loop itself obeys (manual pause, schedule hold, battery hold, cutover pause, service outage; energy saver alone is not a hold) AND pending + processing indexing files >= 50; OFF when < 10 or held. Hysteresis 50/10, serialized sampling and publication as now. Meaning/embedding backfill, including chunk vectors, never pauses answers. Picture reading never counts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/IndexingAnswerAdmissionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/AnswerPausePreferenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationIndexingPauseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java#indexingPauseRefusesAnswersWithoutUnloadingAndPolicyOffReopensImmediately",
        "modules/ui/src/test/java/io/justsearch/ui/api/AnswerPauseSettingsHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseWireTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/IndexingPauseMcpTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentIndexingPauseTest.java",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LiteralExclusionSettingsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/RerankerPreferenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStorePersistenceModeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The expected current settings envelope encoding changes from schema 7 to schema 8. All original assertions, inputs other than the asserted current output version, fault injection, observations and conditions remain. Reranker choice, context migration, read-only/recovery and settings-witness behavior stay the same.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-preserved.json"
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java"
      ],
      "class": "New",
      "behaviour": "The exhaustive refusal mapping now covers INDEXING as CHAT_UNAVAILABLE, retaining reason and forceAllowed and conforming to the shared error schema; every previous reason keeps its mapping. The dedicated pause-wire check separately requires the new reason and exact words.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-preserved.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatRefusalWireTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/chat/ChatVocabularyTest.java"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "Rename the ui-settings successor test from v7 to v8, assert current/format version 8, and extend its successor-readable input from versions 0-7 to 0-8. Keep both acceptance of the legacy v1 store under READ_V0_OR_V1_AND_WRITE_V1 and rejection of a changed strategy identity. No assertion is removed. The schema-8 current register declares 0-7 readable. This Rust check is exercised by CI's Windows Rust lane, not claimed as a local pass.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/settings-v2-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The existing SettingsV2ContractTest generator adds the nullable pauseAnswersWhileIndexing field to its Java-to-TypeScript fixture. Its value is null, preserving the legacy absent-choice meaning; all prior fixture values and assertions remain. The fixture is generated from the same existing compatibility constructor.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-preserved.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseWireTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseGenerationHttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 1: an INDEXING-refused live page question waits visibly and resends once after a fresh Engine resume observation, including repeated polls, concurrent windows and a status flap while its resumed request is in flight. Editing, Stop waiting, discarding, New and switching answers off cancel continuation; restored copies remain review-only. The served readiness union accepts INDEXING. Real HTTP title and compaction entry points retain CHAT_UNAVAILABLE, INDEXING and the exact pause wording for synchronous and asynchronous generation refusals without changing saved history, title or summary. The HTTP fixtures refuse at generation, after initial admission. Each repaired expectation has assertion fail-before evidence at b4b0d60ac5. The page fixture now uses the production chunk SSE event; assertions observe the markdown element text property across its shadow boundary.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#automatically answers an indexing-paused question once after Engine resume across repeated polls and windows",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseWireTest.java#readinessUnionAcceptsTheTypedIndexingRefusal",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseGenerationHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationIndexingContinuationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 2: a typed INDEXING refusal inside an HTTP 200 SSE stream enters the same live automatic waiting path as a pre-stream HTTP 503 refusal, for grounded answers and free chat. The browser reads the already-saved unanswered question through the page record-check interface while waiting. Resume retains that visible turn and its turn id, sends once, and preserves a newer draft. The Engine reuses the already-persisted unanswered question across repeated refusals and injects it once, with prior history preserved for persistent shapes. Current file-count sampling and manual-pause/reset checks are covered by the narrowing entries below. Fail-before checks target 69812a2711 in the temporary shared clone.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#automatically continues a 200 SSE indexing refusal for core.rag-ask with the same turn and preserves a newer draft",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.residency.test.ts#automatically continues a 200 SSE indexing refusal for core.free-chat with the same turn and preserves a newer draft",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/ConversationIndexingContinuationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/HierarchicalShapeRunnerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 3: hierarchical sections, final synthesis and single-pass synthesis retain CHAT_UNAVAILABLE, INDEXING, forceAllowed=false and the exact pause wording for synchronous and callback refusals after initial admission, and never synthesize placeholders from a paused section. Baseline c93a9c0f49 supplies assertion fail-before for callback section/final refusals; cases already correct on the baseline remain preservation coverage. Worker signal checks previously grouped in this evidence are superseded by the file-only narrowing entries below.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-repair3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/HierarchicalShapeRunnerTest.java#generationPauseAfterAdmissionRemainsTypedAndStopsThePipeline"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/IndexingPauseGenerationRoutesTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/WorkflowIndexingPauseTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 3 route sweep: a legacy stream queue refusal calls the terminal error handler with the typed pause instead of leaving the caller waiting; unified streaming and completion retain the typed pause, make no upstream request and leak no generation tickets. A workflow LLM step preserves a thrown or SSE-encoded pause and aborts before subsequent nodes, node output or success. Fail-before targets c93a9c0f49 in a temporary shared clone; known-correct route variants remain preservation cases.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-repair3-sweep.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/IndexingPauseGenerationRoutesTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/WorkflowIndexingPauseTest.java"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseDispatchTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 3 route sweep: agent step-ceiling finalization, handoff escalation and synchronous wrapped refusal retain the explicit pause message with the existing UNAVAILABLE, TRANSIENT, ABORT agent outcome, terminate without a fabricated answer and never retry. Chat dispatch recognizes a wrapped typed pause instead of translating it to BAD_REQUEST. Controlled refusals happen at generation after earlier agent turns or initial chat admission; c93a9c0f49 supplies assertion fail-before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-repair3-agent-dispatch.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java#indexingPauseInEveryAgentGenerationPhaseStopsWithUnavailable",
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingPauseDispatchTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/IndexingSmokeProbeAdmissionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallIndexingPauseTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Remaining repair round 3: the Engine-internal post-install smoke probe is exempt from the answer pause at queueing and lease admission. It still honours draining. The real install validation calls OnlineAiServiceImpl and InferenceLifecycleManager against an isolated loopback answer-server fixture while the heavy-indexing signal and policy are on, receives an answer and reaches completed/installedFully without SMOKE_TEST_FAILED. Ordinary questions before and after the probe remain refused and never reach that server; when the heavy-indexing signal clears, an ordinary question answers again. Page, MCP, HTTP and agent attribution, a matching client label without internal transport/trust, and unrelated Engine-internal work cannot take the exemption. Assertion fail-before uses cc6257b9d6 inside a temporary shared clone; the allowed-probe cases fail before, while denied-provenance and drain cases preserve existing behaviour. This is component validation and completion coverage, not a full download/install journey.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; installs, consent, decline and Repair of parts not switched keep today's behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        },
        {
          "kind": "owner-words",
          "quote": "the post-install smoke test is an Engine-internal probe, not an answer; it is exempt from the answer pause (like picture reading), so install and Repair behave as today (scenario N1).",
          "location": "task t-20261101-uiimpl request"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-smoke-probe.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/IndexingSmokeProbeAdmissionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallIndexingPauseTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopHeavyIndexingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Narrowing: file indexing alone owns the pause. Parent/chunk embedding backlogs of 50 without files do not pause answers; 25 ready + 25 processing files under energy saver and GPU bulk hold do. Actual battery hold clears the pause and releasing it restores the file pause. Fail-before at 121f35e7cb rejects parent-only/combined backlog and energy saver by assertion; the chunk-only case and serialized race/hold/failure/recovery cases pass before as preservation. Deleted backfill-provider classification checks described the superseded scope. Stale-sample interleaving now stalls the file count read, with the same hold/reset oracle. Queue failure replaces the removed embedding-count read failure. All before builds run under the session temporary shared clone, with separate build folders and no source repository worktree writes. The parameterized backlog cases use the class-level selector because JUnit exposes only invocation display names in its XML; a method selector matched no cases and is not claimed as executed. The class captures each invocation and its assertion results.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Design and implement the adopted indexing pause outcome using the owner's Wait, model stays choice; narrow the heavy-indexing signal after exhausted repairs.",
          "choice": "Heavy indexing = file indexing only. ON when indexing is running and not held, using exactly the holds the file indexing loop itself obeys (manual pause, schedule hold, battery hold, cutover pause, service outage; energy saver alone is not a hold) AND pending + processing indexing files >= 50; OFF when < 10 or held. Hysteresis 50/10, serialized sampling and publication as now. Meaning/embedding backfill, including chunk vectors, never pauses answers. Picture reading never counts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-files-only.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopHeavyIndexingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopHeavyIndexingTest.java#energySaverAndGpuHoldKeepFilesHeavyButBatteryHoldReleases"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/HeavyIndexingStateTest.java",
        "modules/ui-web/src/shell-v0/views/BrainSurface.answer-pause.test.ts"
      ],
      "class": "New",
      "behaviour": "Narrowing supersedes the former overlapping-backfill expectations. File-only hysteresis retains fixed 50/10 edges, hold reset, restart recomputation and single-file preservation through the reduced signature. Brain Advanced settings describes indexing files (bulk import, reindex), and its paused sentence says answers resume when bulk file indexing ends while the model stays loaded. Existing witnessed-save and second-window expectations remain. These checks have candidate execution evidence; assertion fail-before for the changed signal is separately supplied by IndexingLoopHeavyIndexingTest, without treating the removed helper parameter as fail-before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P1",
          "quote": "(user Q1: \"Wait, model stays\") Advanced settings has a \"Pause answers while indexing\" switch, default off (existing installs unchanged). On: while the Engine runs heavy indexing (bulk import, reindex, meaning backfill; not picture reading), new questions from the page, agents and HTTP get an explicit \"Answers paused while indexing\" outcome instead of an answer, an answer already streaming finishes, drafts and conversations are kept, search stays available; when heavy indexing ends (finish, fail, cancel, manual pause) answers resume by themselves with the model still loaded. The saved answers on/off choice is never changed by the pause.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "P2",
          "quote": "Pause and user intent: switching answers off during a pause keeps them off after indexing; turning the pause switch off mid-indexing resumes answers at once; overlapping indexing work ending in any order releases the pause only when none is left; the choice survives restart and upgrade; a second window converges; Engine recovery mid-pause settles to the right state; a stale window or queue poll cannot hold answers paused. (challenger-4 #11)",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today's behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Wait, model stays (Recommended)",
          "location": "task t-20261101-uiimpl user turn Q1"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Design and implement the adopted indexing pause outcome using the owner's Wait, model stays choice; narrow the heavy-indexing signal after exhausted repairs.",
          "choice": "Heavy indexing = file indexing only. ON when indexing is running and not held, using exactly the holds the file indexing loop itself obeys (manual pause, schedule hold, battery hold, cutover pause, service outage; energy saver alone is not a hold) AND pending + processing indexing files >= 50; OFF when < 10 or held. Hysteresis 50/10, serialized sampling and publication as now. Meaning/embedding backfill, including chunk vectors, never pauses answers. Picture reading never counts.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-pause-answers-files-wording.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/HeavyIndexingStateTest.java",
        "modules/ui-web/src/shell-v0/views/BrainSurface.answer-pause.test.ts"
      ]
    }
  ]
}
```

## Acceptance records
