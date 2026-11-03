---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: s2-reranker-builder-20261102
---

# Test-intent entries

The before-only patches add inert scaffolding so checks can execute on their before-states: `s2-reranker-settings-api.patch` adds a nullable wire property on 7f3be184b6, and `s2-reranker-preference-revision.patch` adds an always-zero, unserialized choice revision accessor on 9a81be19f4. The CUDA before-only patch `s2-reranker-candidate-plan-api.patch` adds a preparation overload that delegates to the historical method without invoking its new callback, plus an inert stage method, on 8613d76e02. These patches change no historical installer, resolution, activation, settings or device-plan behavior. The independent
verifier supplies acceptance after reviewing the final candidate. No acceptance is recorded here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallOnnxSettingsProducerTest.java"
      ],
      "class": "New",
      "behaviour": "An additions/Repair activation candidate preserves explicit Off or a legacy disabled query witness, the custom path and installed files; the stored witness is untouched during preparation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-install.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallOnnxSettingsProducerTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/IndexingRulesPersistenceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The future-envelope fixture uses schema 99 instead of 7 because 7 becomes readable. The assertion that an unreadable future envelope is refused before duplicate rule validation, unchanged bytes and no quarantine are retained.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java"
      ],
      "class": "New",
      "behaviour": "Prepared replacement emits schema 7 while retaining the existing migration, values and reader compatibility assertions. Only the expected current envelope version changes from 6 to 7.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStorePersistenceModeTest.java"
      ],
      "class": "New",
      "behaviour": "Prepared replacement emits schema 7 while retaining the existing migration, values and reader compatibility assertions. Only the expected current envelope version changes from 6 to 7. The evidence runner cannot select nested JUnit classes by a source-file check ID; the evidence instead checks the same real store replacement/schema-7 emission and reload in RerankerPreferenceTest. The nested original check also executes in the full app-services test run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/RerankerPreferenceTest.java#currentEnvelopeReopensOffAndExactSelectionAfterRestart"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java"
      ],
      "class": "New",
      "behaviour": "Prepared replacement emits schema 7 while retaining the existing migration, values and reader compatibility assertions. Only the expected current envelope version changes from 6 to 7.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java#legacyTwoStartsAtZeroAndOldReaderRefusesThree"
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
      "whatMoved": "The Java SettingsV2 wire fixture adds the new nullable rerankerEnabled property as null. Existing fields retain their values and the same strict generated-schema validation remains.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "class": "New",
      "behaviour": "The installed reranker retains its path switch while runtime status is unavailable; it is disabled until the current saved choice and runtime can be observed. Existing picture observation assertions stay. The earlier no-reranker-switch assumption is superseded by R1.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LiteralExclusionSettingsTest.java"
      ],
      "class": "New",
      "behaviour": "Prepared replacement emits schema 7; the exact exclusion tokens, legacy patterns, unrelated settings, witness and old-reader refusal assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-exclusions.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LiteralExclusionSettingsTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallRerankerCompletionTest.java"
      ],
      "class": "New",
      "behaviour": "The consented switch-on is committed with the installed reranker path by installer completion using its current witness, preserving an intervening settings write; cancelled and failed runs keep Off. The completion keeps an explicit custom path or a discovery-only retained exact selection.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair1.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallRerankerCompletionTest.java#consentedEnableCommitsWithInstalledPathAtTheInstallerCurrentRevision"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeRerankerPreferenceTest.java"
      ],
      "class": "New",
      "behaviour": "After first quarantine and the next restart, runtime status remains readable, other model observations survive and the reranker observation is unknown.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair1.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeRerankerPreferenceTest.java#quarantineAndNextRestartKeepOtherStatusAndRerankerUnknown"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchTest.java"
      ],
      "class": "New",
      "behaviour": "Enabling without a selectable reranker is refused by query-role selection rather than accepting a disabled role. Switching on is the switch, not a model-path change: the exact-identity restore check passes pathChanged=false and restores the retained custom identity.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preflight-fail-before.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchTest.java#enablingWithoutFilesRefusesInsteadOfSelectingDisabled"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts"
      ],
      "class": "New",
      "behaviour": "Consent carries enable-on-completion to the installer; its intervening revision cannot cause a stale follow-up save. A completed settings save without a serving observation reads unavailable until an active observation arrives. Existing switch checks wait for that observation before asserting On.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair1.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts#accepts and completes a consented enable across installer revisions without a follow-up save",
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts#keeps enabled but unavailable when a completed save has no serving reranker observation"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json"
      ],
      "class": "New",
      "behaviour": "The start-install operation advertises the optional enableRerankerOnCompletion boolean used by the consented Best match first switch. All other operation wire fields retain their exact golden assertions.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair1-wire.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java"
      ],
      "class": "New",
      "behaviour": "Install completion preserves an Off saved through the settings coordinator during a real local HTTP download, including an unchanged Off and Off/On/Off. It reports that the newer choice was kept, persists Off and the revision that accepted it across reload, and keeps the downloaded bytes.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair2.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java#concurrentOffDuringDownloadKeepsTheNewerChoice"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java"
      ],
      "class": "New",
      "behaviour": "A repeated true marked as the switch's explicit intent, through the settings HTTP controller and real coordinator, goes through reranker preparation when the saved preference is already true and the durable role is disabled. Unavailable files refuse without replacing settings bytes or their witness; a prepared selection commits COMPLETE.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair2.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java#repeatedEnablePreparesDisabledRoleOrRefusesWithoutCommitting"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java"
      ],
      "class": "New",
      "behaviour": "A public settings save from another window that echoes the saved Best match first value without the switch intent is not a new choice: the persisted choice revision stays unchanged and the consented switch-on still completes with the downloaded model after the download.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java#publicSaveEchoingTheUnchangedChoiceDuringDownloadStillAllowsConsentedEnable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through the settings HTTP controller and real coordinator, a theme save that echoes the saved true while the reranker files are unavailable runs no reranker preparation, commits COMPLETE with the theme, and leaves the disabled role, the saved true and the choice revision as they were.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair3.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java#themeSaveEchoingTrueDoesNotPrepareUnavailableReranker"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts"
      ],
      "class": "New",
      "behaviour": "Both requests the page's Best match first switch sends, Off and then On, carry the explicit switch intent marker with the chosen value; the remaining switch assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-repair3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.reranker-switch.test.ts#shares the sentence and path choice, waits for completion, and switches back on"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java"
      ],
      "class": "New",
      "behaviour": "After a consented install whose switch-on was fenced by a newer Off, a Repair or an install of additions keeps Off, the disabled role and the persisted choice revision (the addition's own path is applied); a second consent takes its own snapshot and enables. A consented install interrupted before completion and followed by an Engine restart does not enable on the later Repair: the install-start snapshot is memory-only (named limit). An internal chat write during the download still allows the consented enable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-lifecycle.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java#followUpInstallAfterAFencedEnable",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java#interruptedConsentedInstallDoesNotEnableAfterRestart",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/RerankerInstallConcurrentChoiceTest.java#unrelatedSettingsWriteDuringDownloadStillAllowsConsentedEnable"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java"
      ],
      "class": "New",
      "behaviour": "A plain settings save without the switch intent that changes the saved false to true still goes through reranker preparation: a prepared selection commits COMPLETE, unavailable files refuse without committing and the saved false stays.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-lifecycle.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerRepeatedEnableHttpTest.java#plainEnableChangeStillPreparesOrRefuses"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/RerankerSettingsWireTest.java"
      ],
      "class": "New",
      "behaviour": "The switch intent marker is accepted only with a Best match first value (a marker alone is refused as malformed), round-trips with its value, and is never written by a settings projection that carries no intent.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-wire.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/RerankerSettingsWireTest.java#switchIntentNeedsAValueAndIsOmittedWhenAbsent"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchOnCudaTest.java"
      ],
      "class": "New",
      "behaviour": "Switching on with a CUDA reranker from a published device-memory plan without a reranker budget: once memory admission passed, the preparation warms under the candidate configuration's staged plan and realizes CUDA with the retained files; an abandoned or refused switch-on puts the published plan back. This mock-session check exercises the real query-role preparation and device-plan state; it does not run a native CUDA session. The before-only compatibility patch leaves historical preparation unchanged and supplies inert API scaffolding.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-cuda-repair.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchOnCudaTest.java#switchingOnRealizesCudaUnderTheStagedCandidatePlan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchOnCudaTest.java#aRefusedSwitchOnPutsThePublishedPlanBack"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerSwitchRouteHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Through the settings HTTP route, the Best match first switch reaches the encoder owner as the switch (rerankerEnabled), never as a reranker model-path change, and keeps the saved path. The Off case of the same file passes on 6f770ee77d too and runs in the module suite.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round2-repairs.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/RerankerSwitchRouteHttpTest.java#onIsTheSwitchNotAModelPathChange"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageFusionOrderTest.java"
      ],
      "class": "New",
      "behaviour": "A hybrid search without a cross-encoder returns, for limits 1, 5, 9, 10 and 50, the prefix of the long page's fused order: leg-top candidates are spliced only into a cross-encoder's pool, and short pages rank the same candidate pools as the default page.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-redesign2.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageFusionOrderTest.java#everyLimitWithoutCrossEncoderIsAPrefixOfTheLongPage"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java"
      ],
      "class": "New",
      "behaviour": "Without a cross-encoder, a chunked corpus's hybrid and keyword pages for limits 1, 5 and 9 are the prefix of the ten-result page and a 50-result page keeps every ten-page result; the chunk-side recall-complete splice changes no page without a cross-encoder but still feeds a cross-encoder's window; with a cross-encoder, the round-1 and chunked corpora return the pages they returned on 8613d76e02 (limits 1, 5, 9).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; installs, consent, decline and Repair of parts not switched keep today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3-depth.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java#chunkedShortPagesWithoutCrossEncoderArePrefixesOfTheTenResultPage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java#chunkRecallCompleteLeavesPagesWithoutCrossEncoderAlone",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java#pagesWithCrossEncoderKeepTheirRankingBeforeTheSwitch"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchRetainedSelectionTest.java"
      ],
      "class": "New",
      "behaviour": "Switching on through the settings owner into the Worker's query-role preparation, with a blank saved path and custom retained files while a different installed model sits where discovery looks, selects the exact retained model and tokenizer and keeps the saved path blank.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round2-repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchRetainedSelectionTest.java#switchingOnSelectsTheRetainedCustomFilesNotTheDiscoveredModel"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchRetainedSelectionTest.java"
      ],
      "class": "New",
      "behaviour": "Switching on when the retained reranker files changed refuses with a reason naming the kept files instead of selecting other files.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round2-new.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchRetainedSelectionTest.java#switchingOnRefusesWhenTheRetainedFilesChanged"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerOffDevicePlanTest.java"
      ],
      "class": "New",
      "behaviour": "The device-memory plan weights of the configuration saved with Best match first off have no reranker entry (no budget reserved while off); with it on and the same files, the reranker is planned.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round2-repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerOffDevicePlanTest.java#offReleasesTheRerankerBudget"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RerankerSwitchOnDevicePlanTest.java"
      ],
      "class": "New",
      "behaviour": "From a published plan without a reranker budget, the Engine's query-role owner runs the switch-on preparation under the plan of the candidate configuration with the encoder weights the Worker passes for the files it selected (with a reranker budget), and a refused preparation leaves the published plan in effect.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3-plan.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RerankerSwitchOnDevicePlanTest.java#switchOnPreparesUnderTheCandidatePlanAndARefusalKeepsThePublishedPlan"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java"
      ],
      "class": "Obsolete",
      "behaviour": "Retire the old precondition and expectation: with no cross-encoder, turning index.hybrid.chunk_leg_recall_complete_enabled on rescued the collapse-dropped parent p-d into results. Adding setCrossEncoderEnabled(true) changes that precondition; it is not input re-encoding. ShortPageRankingDepthTest#chunkRecallCompleteLeavesPagesWithoutCrossEncoderAlone now asserts that the flag leaves pages without a cross-encoder alone. The doc-side leg recall splice (index.hybrid.leg_recall_complete_enabled, default true) now also runs only with a cross-encoder, which slightly changes ranking for every install without a reranker. This is the recorded agent choice under R1, not preservation licensed by N1.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "The Best match first work under the owner-adopted scenarios, including R1: results without the reranker stay usable.",
          "choice": "Task t-20261101-uiimpl, round-decision sequence 2 decision B: \"the splice applies only when a cross-encoder reranks\". This is the recorded agent choice for the usable fused ordering without a reranker.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Searched docs/decisions with rg -n -i 'rerank|cross.encoder|recall.complete|leg.recall|splice'. No decision found requiring the doc-side or chunk-side recall splice without a cross-encoder; no conflicting decision found. R1 and the recorded agent choice supply the reason for retiring the old expectation."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java"
      ],
      "class": "New",
      "behaviour": "With cross_encoder_enabled=true, the chunk-side recall-complete check still compares the same corpus, collapse multiplier and limit: p-d is absent with the flag off and rescued into the cross-encoder judge window with it on. Without a cross-encoder the splice no longer runs, as checked separately by ShortPageRankingDepthTest#chunkRecallCompleteLeavesPagesWithoutCrossEncoderAlone (s2-reranker-round3-depth.json). The doc-side leg recall splice (index.hybrid.leg_recall_complete_enabled, default true) likewise now requires a cross-encoder; this slightly changes ranking for every install without a reranker.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "The Best match first work under the owner-adopted scenarios, including R1: results without the reranker stay usable.",
          "choice": "Task t-20261101-uiimpl, round-decision sequence 2 decision B: \"the splice applies only when a cross-encoder reranks\". This is the recorded agent choice for the usable fused ordering without a reranker.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round2-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceReasonCodeContractTest.java#chunkSideRecallComplete_rescuesLegTopNParentDroppedByCollapse"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchTest.java"
      ],
      "class": "New",
      "behaviour": "After Off retained a custom selection and a new process reopens the saved candidate, switching on (the switch, not a model-path change) restores that exact selection even though another available variant now sits in its directory.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-preflight-adaptation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchTest.java#onRestoresExactCustomIdentityInsteadOfProbingAnotherVariant"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java"
      ],
      "class": "New",
      "behaviour": "Without a cross-encoder, keyword+SPLADE and meaning+SPLADE searches rank to the ten-result depth: a document second on both legs wins the ten-result fusion and is the one-result page, and the two-result page is the ten-result page's prefix; with a cross-encoder the one-result pages are those of 8613d76e02.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; installs, consent, decline and Repair of parts not switched keep today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java#keywordAndSpladeWithoutCrossEncoderRankShortPagesToTheTenResultDepth",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/ShortPageRankingDepthTest.java#meaningAndSpladeWithoutCrossEncoderRankShortPagesToTheTenResultDepth"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchOnSelectedPlanTest.java"
      ],
      "class": "New",
      "behaviour": "Switching on through the Engine's query owner and the Worker's preparation, with real device planning, a retained 3 MiB custom CUDA reranker and a 1 MiB installed one where discovery looks: the reranker warms under a plan budgeting the 3 MiB selected file, and the commit publishes that same plan.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R1",
          "quote": "After install, Best match first has a live switch (sentence and path). Off: searches and the answer context stop using the reranker within one change, no Engine restart, results stay usable (earlier ordering); files kept; the page says \"Off - files kept\". On: reranking returns with the same model files.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchOnSelectedPlanTest.java#switchOnWarmsUnderAndPublishesThePlanOfTheRetainedCustomFiles"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchAfterRepairTest.java"
      ],
      "class": "New",
      "behaviour": "With Best match first off and its installed reranker kept, an upgrade or Repair that installs new verified bytes for the same model file and variant leaves Off saved, and switching on through the Worker's selection restores the upgraded model and tokenizer instead of refusing them as changed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R2",
          "quote": "The Best match first choice survives Engine restart, app upgrade and a legacy install whose reranker was found by discovery; an explicit off survives Repair, installing additions and runtime activation (none silently turns it back on). Absent choice keeps today’s behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchAfterRepairTest.java#anUpgradedInstalledRerankerStaysOffAndSwitchesOnWithItsNewFiles"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchAfterRepairTest.java"
      ],
      "class": "New",
      "behaviour": "Replaced reranker bytes that no registry or install contract names keep the old kept identities after the installer's activation, and switching on refuses them as changed files; a retained custom model is not replaced by the installed one and switching on restores it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R3",
          "quote": "Switching Best match first while searches and answers run: in-flight work completes, new work uses the new setting; a second window shows the change; a stale save cannot overwrite a newer one (settings witness). Failures (missing or corrupt files, warm-up failure, low memory, crash mid-change) leave a truthful state, never a false On/Off. Missing files: the existing consent with size and licence, no transfer if declined.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R4",
          "quote": "Off then restart then On restores the exact earlier reranker selection (same model and variant, a custom model too) unless the user changed it; an install whose query role was already explicitly disabled stays disabled when no new choice exists.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-reranker-round3-new.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchAfterRepairTest.java#anUnexplainedReplacementIsStillRefused",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchAfterRepairTest.java#aRetainedCustomModelIsNotReplacedByTheInstalledOne"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/FanoutRuntimeLifetimeTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/HybridSearchIntegrationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The removed executeHybrid and searchHybrid overloads passed rerankPool=true. Their callers now pass true explicitly to the remaining overloads; every assertion, other input, precondition, observation, fault injection, tag and condition is kept.",
      "evidence": "gates/test-intent/evidence/s2-reranker-preflight-adaptation.json"
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/RerankerSwitchTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The composition mock is declared immediately before a nested try-with-resources and used as its resource. Mock creation, its active lifetime and reverse close order (issued view, mock, fixture) are preserved; every assertion and test input is kept.",
      "evidence": "gates/test-intent/evidence/s2-reranker-preflight-adaptation.json"
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
      "whatMoved": "The ui-settings successor test is renamed from v6 to v7 and its asserted current/format versions become 7, matching the register's schema bump; the successor's readable source versions extend from 0-6 to 0-7. Every assertion is kept: the installed legacy v1 store is accepted with the unchanged frozen READ_V0_OR_V1_AND_WRITE_V1 reconciliation token, and the changed token is rejected. Legacy inputs, fault injection, observations and conditions are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (envelope schema 6 today; public API v2): existing installs load with no explicit choice (today’s behaviour), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
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
  "session": "verifier-s2r-acc2",
  "verdict": "accept",
  "digest": "sha256:cd3505403c6034bb6799113771b73d15c9c412b80bb8a4680454673128ad09aa",
  "note": "Acceptance by independent verifier session verifier-s2r-acc2 (Codex): accept, all entries. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-39",
  "verdict": "reject",
  "digest": "sha256:cd3505403c6034bb6799113771b73d15c9c412b80bb8a4680454673128ad09aa",
  "note": "Audit by independent reviewer session reviewer-39 (Codex): reject entry 13 only (wire evidence recorded before the rebase). Transcribed by the orchestrator."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-s2r-acc3",
  "verdict": "accept",
  "digest": "sha256:4075ed90d86f2c93d4a847cde0dbf7cf134d4901cb04effa4a0d13a102f4e629",
  "note": "Acceptance by independent verifier session verifier-s2r-acc3 (Codex): accept, all entries. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "reviewer-40",
  "verdict": "accept",
  "digest": "sha256:4075ed90d86f2c93d4a847cde0dbf7cf134d4901cb04effa4a0d13a102f4e629",
  "note": "Audit by independent reviewer session reviewer-40 (Codex): accept, no must-fix. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-s2r-acc4",
  "verdict": "accept",
  "digest": "sha256:4b2a962e46fc9f94587177340a1451c5242ffc0813feaa2c79abd5f6bd98b0e4",
  "note": "Acceptance by independent verifier session verifier-s2r-acc4 (Codex): accept, all entries. Transcribed by the orchestrator."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-s2r-acc5",
  "verdict": "accept",
  "digest": "sha256:79588da221fac3d125a799da7e94006ec183ea0a705dfe567020199c1167b7ac",
  "note": "Acceptance by independent verifier session verifier-s2r-acc5 (Codex): accept, all entries. Transcribed by the orchestrator."
}
```
