---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp3.6-builder-e9cx5hlb
---

# Test-intent entries

WP3.6. Dotted agreed scenario IDs are preserved in agreedScenario; the gate IDs omit dots. New tests are durable. No acceptance record is authored by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingHoldTest.java"
      ],
      "class": "New",
      "behaviour": "Admission closes atomically and quiet requires every admitted lease to publish and close. Deadline expiry uses wall clock.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingHoldTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/IndexingPauseContractTest.java"
      ],
      "class": "New",
      "behaviour": "Pause policy round-trips; legacy AI-only input loads but the retired preference is absent from saved and canonical output.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3612",
          "agreedScenario": "S36.12",
          "quote": "The old \"pause indexing during AI\" setting is gone from API, schema and UI; settings files containing it still load. Using an older version afterwards may drop the pause; on return the actual state is shown (no promise that the older version keeps or clears it).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/IndexingPauseContractTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingPauseRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Corruption quarantine, including preserved quarantine after restart, starts paused; explicit reset clears pause.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3611",
          "agreedScenario": "S36.11",
          "quote": "A settings reset clears the pause and is visible as such; recovery from a corrupt settings file applies decision 7 (recommended: starts paused, with a reason that exclusions may have been lost).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingPauseRecoveryTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingPauseIntentTest.java"
      ],
      "class": "New",
      "behaviour": "UI-only pause/resume use accepted settings commits, preserve unrelated settings, publish the hold, and keyed replay retains the accepted deadline.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S369",
          "agreedScenario": "S36.9",
          "quote": "Overlapping Pause, Resume and unrelated settings saves (two windows, Settings open, exclusions being saved) end in an authoritative accepted order or an explicit conflict; unrelated preferences are preserved; a lost response or publication failure is reported as uncertain until reconciled, and retry does not apply a different change. A pause or resume causes no model reload and no \"restart required\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted). Design A3 section 2.A names core.indexing-pause/core.indexing-resume. Reuse cancel-indexing-job LOW/no-confirm policy, with no WorkerOnline precondition because persisted intent must work before activation. The optional untilEpochMs argument is an absolute deadline; resume has no arguments. Both are UI-only settings effects, with empty availability and lineage, standard core provenance, no agent executor and no new UI consumer in WP3.6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/IndexingPauseIntentTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIndexingPauseTest.java"
      ],
      "class": "New",
      "behaviour": "Operating status waits for acknowledgment, overrides idle with user pause, carries end time and reports queued work waiting for Resume.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S368",
          "agreedScenario": "S36.8",
          "quote": "With nothing queued, status still reports the user pause (never plain idle/Ready); a file added during the pause is reported as waiting.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIndexingPauseTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopUserPauseTest.java"
      ],
      "class": "New",
      "behaviour": "Paused startup does not poll work; cutover and upgrade preparation finish; upgrade resume preserves user pause until explicit Resume.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S367",
          "agreedScenario": "S36.7",
          "quote": "A rebuild cutover and an app upgrade's preparation complete while paused; after the upgrade the app is still paused (decision 6).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopUserPauseTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/IndexingPauseVduTest.java"
      ],
      "class": "New",
      "behaviour": "Accepted picture reading waits for Resume; active work retains pausing until its outcome is published.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/IndexingPauseVduTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/IndexingPauseGplTest.java"
      ],
      "class": "New",
      "behaviour": "GPL waits for Resume and publishes document progress before acknowledging quiet, including blank query generation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/IndexingPauseGplTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/api/generated/schema-types/settings-pause-retirement.test.ts"
      ],
      "class": "New",
      "behaviour": "The canonical settings validator rejects the retired AI-only pause preference.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3612",
          "agreedScenario": "S36.12",
          "quote": "The old \"pause indexing during AI\" setting is gone from API, schema and UI; settings files containing it still load. Using an older version afterwards may drop the pause; on return the actual state is shown (no promise that the older version keeps or clears it).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-retirement.json",
      "newChecks": [
        "modules/ui-web/src/api/generated/schema-types/settings-pause-retirement.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/OperatingStateViewTest.java"
      ],
      "class": "New",
      "behaviour": "Extend the operating-state vocabulary with PAUSING and user/recovery reasons; preserve automatic members and casing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/OperatingStateViewTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/BulkWorkVocabularyMirrorTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "The automatic bulk gate vocabulary still mirrors the automatic operating reasons, in the same precedence order.",
      "whySurvives": "User and corruption-recovery reasons come from the acknowledged processing hold, rather than the automatic GPU gate. The new status check independently pins those reasons.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N361",
          "agreedScenario": "N36.1",
          "quote": "Automatic duty-cycle slowdown, game/GPU holds, energy-saver deferral; the migration pause/resume routes; rebuild Cancel and Accept gaps.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/ReconfigureHandlerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/ExclusionLimitTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsControllerReconfigureDispatchTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Remove only the obsolete AI-pause constructor argument. Inputs for every retained field and all existing oracles remain unchanged. Candidate execution: gates/test-intent/evidence/uiux-pause-engine-preserved-java.json.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3612",
          "agreedScenario": "S36.12",
          "quote": "The old \"pause indexing during AI\" setting is gone from API, schema and UI; settings files containing it still load. Using an older version afterwards may drop the pause; on return the actual state is shown (no promise that the older version keeps or clears it).",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsV2ProjectionTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/SettingsV2ContractTest.java",
        "modules/ui-web/src/api/__fixtures__/settings-v2-live.json"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3612",
          "agreedScenario": "S36.12",
          "quote": "The old \"pause indexing during AI\" setting is gone from API, schema and UI; settings files containing it still load. Using an older version afterwards may drop the pause; on return the actual state is shown (no promise that the older version keeps or clears it).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none; K5 and S36.12 explicitly retire the old preference. Only that field and its input/assertion are removed; all remaining projection and round-trip expectations are preserved."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "class": "New",
      "behaviour": "The exact catalog includes the two design pause/resume operations without duplicates or missing existing entries.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S369",
          "agreedScenario": "S36.9",
          "quote": "Overlapping Pause, Resume and unrelated settings saves (two windows, Settings open, exclusions being saved) end in an authoritative accepted order or an explicit conflict; unrelated preferences are preserved; a lost response or publication failure is reported as uncertain until reconciled, and retry does not apply a different change. A pause or resume causes no model reload and no \"restart required\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted). Design A3 section 2.A names core.indexing-pause/core.indexing-resume. Reuse cancel-indexing-job LOW/no-confirm policy, with no WorkerOnline precondition because persisted intent must work before activation. The optional untilEpochMs argument is an absolute deadline; resume has no arguments. Both are UI-only settings effects, with empty availability and lineage, standard core provenance, no agent executor and no new UI consumer in WP3.6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/CoreOperationCatalogTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "class": "New",
      "behaviour": "The UI emitter exposes the two LOW/no-confirm UI-only settings operations, optional non-negative nullable epoch deadline for pause and no resume arguments; all pre-existing wire rows remain unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S369",
          "agreedScenario": "S36.9",
          "quote": "Overlapping Pause, Resume and unrelated settings saves (two windows, Settings open, exclusions being saved) end in an authoritative accepted order or an explicit conflict; unrelated preferences are preserved; a lost response or publication failure is reported as uncertain until reconciled, and retry does not apply a different change. A pause or resume causes no model reload and no \"restart required\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted). Design A3 section 2.A names core.indexing-pause/core.indexing-resume. Reuse cancel-indexing-job LOW/no-confirm policy, with no WorkerOnline precondition because persisted intent must work before activation. The optional untilEpochMs argument is an absolute deadline; resume has no arguments. Both are UI-only settings effects, with empty availability and lineage, standard core provenance, no agent executor and no new UI consumer in WP3.6.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-pause-engine-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Extend the shared unpaused status fixture with the neutral new wire members untilEpochMs=null and waitingForResume=false. Every existing field and oracle is retained. The generator and contract suites executed in tmp/wp3.6/contracts-and-processors-2.log; frontend contract execution is in uiux-pause-engine-preserved-ui.json.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S361",
          "agreedScenario": "S36.1",
          "quote": "Pause, requested during extraction, idle enrichment, interleaved enrichment, an active picture-reading pass and a GPL pass (decision 5), leads to: status reports \"pausing\" until every covered processor has finished its permitted work and acknowledged the hold, then \"paused\"; never \"paused\" while any of them still works, and never inferred from stable counts or loop state alone. Time from Pause to quiet is measured on the reference corpus and meets the agreed bound (decision 10).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S368",
          "agreedScenario": "S36.8",
          "quote": "With nothing queued, status still reports the user pause (never plain idle/Ready); a file added during the pause is reported as waiting.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/validator/ValidatorRunnerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Register the two new real pause/resume handlers in the declaration-only validator fixture. Every existing validator predicate, finding expectation and invocation context is unchanged; this fixture does not claim production boot coverage. Execution: gates/test-intent/evidence/uiux-pause-engine-preserved-java.json.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S365",
          "agreedScenario": "S36.5",
          "quote": "Work the user explicitly requests while paused (Add folder, Reindex, rebuild, Process pending enrichment, Retry a failed file, MCP ingest) is accepted and waits for Resume, and status says it is waiting; Remove folder and Apply exclusions still run (decision 5).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S366",
          "agreedScenario": "S36.6",
          "quote": "A pause survives stop, crash and restart: the app starts paused and processes no file between start and the paused state. \"Pause until <time>\" ends at that time, also when the machine slept or was off through it.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S369",
          "agreedScenario": "S36.9",
          "quote": "Overlapping Pause, Resume and unrelated settings saves (two windows, Settings open, exclusions being saved) end in an authoritative accepted order or an explicit conflict; unrelated preferences are preserved; a lost response or publication failure is reported as uncertain until reconciled, and retry does not apply a different change. A pause or resume causes no model reload and no \"restart required\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S3610",
          "agreedScenario": "S36.10",
          "quote": "Status (`/api/status` and MCP status, read-only) reports paused or pausing, its reason and end time, and distinguishes a user pause from automatic holds. Pause/resume are not offered to the agent or over MCP.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement",
          "delegationScope": "Remaining UI/UX designs and implementation, WP3.6 indexing pause Engine side.",
          "choice": "Apply K1-K5 (battery pause off by default; manual pause survives restart; processing paused while discovery and cleanup continue; one global schedule; retire pauseIndexingDuringAi). Apply decisions 4 (keep automatic hold semantics), 5 (extraction, all enrichment, picture reading and GPL stop; explicit work waits), 6 (cutover and upgrade preparation still complete), 7 (corrupt-settings recovery starts paused with exclusions warning), 10 (measure Pause-to-quiet against 2 minutes), 14 (Engine side has no new UI controls), and pass-2 finding 3 (after acknowledgment covered processors publish no further progress; deletion/cleanup/cutover count changes are permitted). Design A3 section 2.A names core.indexing-pause/core.indexing-resume. Reuse cancel-indexing-job LOW/no-confirm policy, with no WorkerOnline precondition because persisted intent must work before activation. The optional untilEpochMs argument is an absolute deadline; resume has no arguments. Both are UI-only settings effects, with empty availability and lineage, standard core provenance, no agent executor and no new UI consumer in WP3.6.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
