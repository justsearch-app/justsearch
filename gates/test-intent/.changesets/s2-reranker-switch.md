---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: s2-reranker-builder-20261102
---

# Test-intent entries

The before-only patch adds an inert nullable wire property so installer preservation checks can
execute on 551357f754. It changes no installer, resolution or activation behavior. The independent
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
      "behaviour": "Enabling without a selectable reranker is refused by query-role selection rather than accepting a disabled role.",
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
    }
  ]
}
```

## Acceptance records
