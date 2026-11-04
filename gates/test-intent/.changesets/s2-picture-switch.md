---
schema: test-intent.v1
task: t-20261101-uiimpl
author-role: builder
author-session: builder-s2-picture-20261104
---

# Picture reading switch test intent

The brief's adopted scenarios are quoted below. Evidence is emitted only by run-evidence.mjs. The baseline and candidate evidence checkouts are shared clones inside the session temporary directory, with their own build folders and copied frontend dependencies; source-repository worktrees are not created. These are builder checks, not acceptance of the complete application. The independent verifier must cover the real-system scenarios, including actual GPU processing, full installer completion, deletion/exclusion publication races and process-death recovery. No acceptance record is written here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/PictureReadingOwnerRefusalTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/PictureReadingConfigurationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/PictureReadingPreferenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/PictureReadingCoordinatorTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/PictureReadingSettingsHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Bounded owner and HTTP checks: the nullable preference is persisted independently of answers and acquisition; legacy settings stay automatic; every coordinator entry is gated; deferral releases the owned claim and the next pass gets another attempt; a result already being saved finishes before Off settles; a server still entering its mode cannot claim a document after the fence; completed documents are not reread; runtime and prepared applies honor Off; streaming replies drain and abort restores the prior projector; override/external/missing-file refusals cannot mutate the runtime. The native server is a local fake process; no real GPU or real-model completion is claimed. If a result publication fails while Off is waiting, procedure exit is insufficient: the transition refuses and reopens its temporary fence, preserving the prior saved choice. An ordinary save echoing an unchanged picture choice does not prepare the generative owner or advance its preference revision; an explicit repeated switch does both. Off also withdraws an explicit procedure waiting on the real indexing hold without resuming or changing that policy.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V1",
          "quote": "After install, Text in scans and images has a live switch. Off: no new picture work starts (automatic or manual), unfinished pictures return to waiting without using up retries, the managed answer server runs without the picture file; files and already read text are kept; the page says \"Off - files and read text kept\". On: picture reading resumes waiting work only (no rereading of already processed files). Works whether answers are on or off.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V2",
          "quote": "Switching picture reading at every stage (Engine starting, between documents, during reading, while saving a result) ends consistent: no late result after Off is lost or half-written; completed text stays searchable. The choice survives restart, upgrade, a profile or model change, Repair and runtime activation; a second window converges; failures (server restart fails, file missing or mismatched, settings write fails, process dies midway) leave a truthful prior or recovered state. A user-run (external) answer server: the switch refuses with a reason and never stops that server.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V3",
          "quote": "While picture reading is off, new scans are indexed and wait for picture reading; a changed scan gets normal fresh processing later; a deleted or excluded scan never gets a late result; unchanged scans with a final empty or rejected outcome stay as they are; the manual or agent trigger of offline processing cannot bypass Off. Off during a multipage document followed at once by On: no old attempt publishes into the new one; an orderly Off returns retries, a crash keeps today's spent-count recovery.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V4",
          "quote": "Switching picture reading on or off while an answer, summary or answer with sources is streaming: the reply is not lost; the switch either waits for the reply and then applies, or says it is busy; it never shows applied before the answer server is reconfigured.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R5",
          "quote": "With an operator override (JUSTSEARCH_MMPROJ_MODEL), the switch refuses with a reason instead of showing Off while the override keeps the feature in use, live and after restart; custom paths are preserved.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (stored schema 8 today -> 9; public API v2): existing installs load with no explicit choice (today's behaviour: picture reading on when its file is present), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement the adopted picture-reading switch design, preserving files, acquisition choices and the existing fixed settings owner.",
          "choice": "Use the existing none-projector resolved setting, a temporary coordinator fence and the existing controller convergence hold; bound procedure cleanup to 180 seconds; preserve completed writes and use the existing owned-claim refund; verify the retained projector against the shipped registry before an explicit enable. Store a picture-preference revision so a newer explicit switch supersedes an older install consent.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; \"Pause answers while indexing\" and \"Best match first\" keep their behaviour; installs, consent, decline and Repair of parts not switched keep today's behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Use the existing generative settings owner to apply the picture switch while retaining answer intent, pacing and GPU-pressure behavior.",
          "choice": "A picture-only switch uses the existing restart-if-online preparation policy: reconfigure a serving server, or retain the configuration for the next normal controller start when offline. It does not request an otherwise stopped server to load merely to change the projector.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Keep publication coherent and failure states truthful while picture reading is stopped.",
          "choice": "A transition waits for both procedure cleanup and its acknowledged outcome. An unsuccessful in-flight publication refuses Off before settings preparation rather than treating cleanup alone as successful publication.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Apply the picture switch through the current settings owner and operation registry without disturbing ordinary settings saves or newer install-time choices.",
          "choice": "Reuse the existing reranker convention: pictureReadingSwitchIntent marks an explicit repeated switch; unmarked unchanged echoes remain ordinary saves. The marker is transient and omitted from reads, and requires pictureReadingEnabled when true.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Gate every picture-work entry at its existing owner and preserve indexing pause rules.",
          "choice": "Let the existing interruptible IndexingHold acquisition loop observe the caller's admission predicate. VDU withdraws its wait when fenced; the hold's own policy and eligibility rules stay in that one owner. Existing no-argument acquisition retains its behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-engine.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/PictureReadingOwnerRefusalTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/PictureReadingConfigurationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/PictureReadingPreferenceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/PictureReadingCoordinatorTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/PictureReadingSettingsHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts"
      ],
      "class": "New",
      "behaviour": "Both installed switches send the same witnessed single-field setting, stay busy until the receipt, preserve the prior state on refusal, show the operator-controlled effective state after restart, and use the whole selected download consent. Cancel does not save the picture setting or start transfer; accepting carries the picture-enable intent to installer completion. These checks fail with assertions on the baseline because no picture switches exist.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V1",
          "quote": "After install, Text in scans and images has a live switch. Off: no new picture work starts (automatic or manual), unfinished pictures return to waiting without using up retries, the managed answer server runs without the picture file; files and already read text are kept; the page says \"Off - files and read text kept\". On: picture reading resumes waiting work only (no rereading of already processed files). Works whether answers are on or off.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V2",
          "quote": "Switching picture reading at every stage (Engine starting, between documents, during reading, while saving a result) ends consistent: no late result after Off is lost or half-written; completed text stays searchable. The choice survives restart, upgrade, a profile or model change, Repair and runtime activation; a second window converges; failures (server restart fails, file missing or mismatched, settings write fails, process dies midway) leave a truthful prior or recovered state. A user-run (external) answer server: the switch refuses with a reason and never stops that server.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V4",
          "quote": "Switching picture reading on or off while an answer, summary or answer with sources is streaming: the reply is not lost; the switch either waits for the reply and then applies, or says it is busy; it never shows applied before the answer server is reconfigured.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "C1",
          "quote": "Switching on a part whose files are missing opens the existing consent listing everything the download will actually transfer (all missing selected parts, with sizes and licences), not only the switched part; closing or declining starts no transfer and leaves the switch as it was.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "R5",
          "quote": "With an operator override (JUSTSEARCH_MMPROJ_MODEL), the switch refuses with a reason instead of showing Off while the override keeps the feature in use, live and after restart; custom paths are preserved.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement the adopted picture-reading switch design, preserving files, acquisition choices and the existing fixed settings owner.",
          "choice": "Expose a read-only operator-enabled projection beside the saved nullable preference. A successful witnessed receipt takes precedence over an older runtime poll until a new observation arrives.",
          "label": "agent choice"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; \"Pause answers while indexing\" and \"Best match first\" keep their behaviour; installs, consent, decline and Repair of parts not switched keep today's behaviour.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-behaviour.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ],
      "class": "New",
      "behaviour": "Replace only the superseded expectation that installed picture reading has no control. Its path switch now remains visible and disabled while live observations are unknown; the existing runtime-derived sentence and other feature expectations stay in place.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V1",
          "quote": "After install, Text in scans and images has a live switch. Off: no new picture work starts (automatic or manual), unfinished pictures return to waiting without using up retries, the managed answer server runs without the picture file; files and already read text are kept; the page says \"Off - files and read text kept\". On: picture reading resumes waiting work only (no rereading of already processed files). Works whether answers are on or off.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V2",
          "quote": "Switching picture reading at every stage (Engine starting, between documents, during reading, while saving a result) ends consistent: no late result after Off is lost or half-written; completed text stays searchable. The choice survives restart, upgrade, a profile or model change, Repair and runtime activation; a second window converges; failures (server restart fails, file missing or mismatched, settings write fails, process dies midway) leave a truthful prior or recovered state. A user-run (external) answer server: the switch refuses with a reason and never stops that server.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-redesign.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.redesign.test.ts"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStorePersistenceModeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java",
        "modules/ui-web/src/api/__fixtures__/settings-v2-live.json",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LiteralExclusionSettingsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/RerankerPreferenceTest.java"
      ],
      "class": "New",
      "behaviour": "Pin the requested current stored envelope to schema 9 while preserving each existing round-trip, backup, migration and witness assertion. Extend the public v2 example with the nullable picture choice and read-only operator projection. The standalone current-envelope wrapper executes the nested persistence oracle.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (stored schema 8 today -> 9; public API v2): existing installs load with no explicit choice (today's behaviour: picture reading on when its file is present), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement the adopted picture-reading switch design, preserving files, acquisition choices and the existing fixed settings owner.",
          "choice": "The new preference and preference revision belong to stored schema 9. The public API remains v2; the new read-only override projection is nullable when no operator override is present.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-schema.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsCurrentEnvelopeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreContextLengthMigrationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/UiSettingsStoreRevisionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/LiteralExclusionSettingsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/RerankerPreferenceTest.java"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "New",
      "behaviour": "The updater compatibility check pins schema 9 as the successor that reads legacy v1 and preserves the frozen reconciliation-strategy identity. Its readable-source list now includes stored schema 8 as required for upgrades.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (stored schema 8 today -> 9; public API v2): existing installs load with no explicit choice (today's behaviour: picture reading on when its file is present), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-updater.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater.rs#updater::tests::ui_settings_v9_successor_reads_legacy_v1_without_changing_strategy_identity"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/resources/registry/emitter/ui-operation-wire.golden.json",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/PictureReadingInstallIntentTest.java"
      ],
      "class": "New",
      "behaviour": "Add only the boolean enablePictureReadingOnCompletion input to the start-install operation contract. This carries the consented picture-reading enable through the existing install owner; all other golden operation fields remain pinned. The real operation handler and Brain facade forward the consented picture-enable flag to the installer entry; an ordinary install retains its existing entry without that flag.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "C1",
          "quote": "Switching on a part whose files are missing opens the existing consent listing everything the download will actually transfer (all missing selected parts, with sizes and licences), not only the switched part; closing or declining starts no transfer and leaves the switch as it was.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement the existing-consent picture-switch design through the operation registry.",
          "choice": "Use an additive enablePictureReadingOnCompletion boolean on core.start-ai-install, parallel to its existing reranker completion intent; do not create a separate projector package.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-wire.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/emitter/UIOperationViewConformanceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/PictureReadingInstallIntentTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/PictureReadingCandidateCompatibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Unset picture preference and zero preference revision stay absent from stored UiSettings JSON, so the new fields do not extend an already accepted legacy installer candidate. Explicit false and a nonzero revision serialize and round-trip. Public v2 projection remains nullable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (stored schema 8 today -> 9; public API v2): existing installs load with no explicit choice (today's behaviour: picture reading on when its file is present), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; \"Pause answers while indexing\" and \"Best match first\" keep their behaviour; installs, consent, decline and Repair of parts not switched keep today's behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Preserve saved settings meaning and existing install behavior while adding the nullable preference.",
          "choice": "Omit only the newly introduced unset/null preference and zero revision from stored UiSettings serialization. Previously accepted canonical installer settings must not acquire new default fields merely by decoding and reserializing on the successor.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-candidate-compatibility.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/PictureReadingCandidateCompatibilityTest.java"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/PictureReadingSettingsWireTest.java"
      ],
      "class": "New",
      "behaviour": "The new preference stays nullable; explicit switch intent needs its preference, survives request serialization and is absent from ordinary read/default projections.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V1",
          "quote": "After install, Text in scans and images has a live switch. Off: no new picture work starts (automatic or manual), unfinished pictures return to waiting without using up retries, the managed answer server runs without the picture file; files and already read text are kept; the page says \"Off - files and read text kept\". On: picture reading resumes waiting work only (no rereading of already processed files). Works whether answers are on or off.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N2",
          "quote": "Saved settings keep their meaning across the stored settings schema change (stored schema 8 today -> 9; public API v2): existing installs load with no explicit choice (today's behaviour: picture reading on when its file is present), older settings versions migrate, corrupt settings are quarantined as today; declinedAiPackages stays acquisition preference only.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Apply the picture switch through the current settings owner and operation registry without disturbing ordinary settings saves or newer install-time choices.",
          "choice": "Reuse the existing reranker convention: pictureReadingSwitchIntent marks an explicit repeated switch; unmarked unchanged echoes remain ordinary saves. The marker is transient and omitted from reads, and requires pictureReadingEnabled when true.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-settings-wire.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/settings/PictureReadingSettingsWireTest.java"
      ]
    },
    {
      "items": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingHoldWithdrawnAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Withdrawing a caller admission returns no lease and leaves the manual indexing hold active. The predicate cannot fluctuate between two reads and grant a lease while the policy is held. Existing no-argument hold scenarios retain their behavior.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V1",
          "quote": "After install, Text in scans and images has a live switch. Off: no new picture work starts (automatic or manual), unfinished pictures return to waiting without using up retries, the managed answer server runs without the picture file; files and already read text are kept; the page says \"Off - files and read text kept\". On: picture reading resumes waiting work only (no rereading of already processed files). Works whether answers are on or off.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "N1",
          "quote": "Answers on/off (core.set-chat-enabled) keeps files and its meaning; search by exact words and by meaning, indexing and its pacing, energy and manual pause rules, GPU-pressure handling and picture-reading back-off work as today; \"Pause answers while indexing\" and \"Best match first\" keep their behaviour; installs, consent, decline and Repair of parts not switched keep today's behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Gate every picture-work entry at its existing owner and preserve indexing pause rules.",
          "choice": "Let the existing interruptible IndexingHold acquisition loop observe the caller's admission predicate. VDU withdraws its wait when fenced; the hold's own policy and eligibility rules stay in that one owner. Existing no-argument acquisition retains its behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-indexing-hold.json",
      "newChecks": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingHoldWithdrawnAdmissionTest.java",
        "modules/core/src/test/java/io/justsearch/core/scheduling/IndexingRulesHoldTest.java"
      ]
    }
  ]
}
```
