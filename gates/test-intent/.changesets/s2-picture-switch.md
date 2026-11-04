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
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/PictureReadingProfileRepairTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 1: production ConfigStoreRebuilder profile activation and retained-profile core.reconfigure preparation keep saved Off, keep coordinator admission closed and strip a constructed runtime projector. Internal runtime_activation permits switches both ways after activation and retained preparation; enable still verifies the retained local projector against a fixture registry hash and size. Genuine JVM and JUSTSEARCH_MMPROJ_MODEL environment provenance refuses switching regardless of source ordinal; profile preparation preserves the custom operator path through the generative owner. Each named check asserts a violated obligation on 7273c984c2.",
      "sources": [
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
          "scenario": "R5",
          "quote": "With an operator override (JUSTSEARCH_MMPROJ_MODEL), the switch refuses with a reason instead of showing Off while the override keeps the feature in use, live and after restart; custom paths are preserved.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-profile-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/PictureReadingProfileRepairTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Repair round 1: retain the original witnessed attempt after uncertain delivery or receipt. Reconnect replays exactly the same body and operation key; a COMPLETE receipt resolves application with either an unchanged or changed GET witness, without spending another revision for a replay. A terminal refusal resolves non-application with an unchanged witness and keeps the prior switch. Uncertainty clears only on a reconciled outcome, not merely a changed GET witness. All three checks fail with assertions against 7273c984c2.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261101-uiimpl",
          "scenario": "V2",
          "quote": "Switching picture reading at every stage (Engine starting, between documents, during reading, while saving a result) ends consistent: no late result after Off is lost or half-written; completed text stays searchable. The choice survives restart, upgrade, a profile or model change, Repair and runtime activation; a second window converges; failures (server restart fails, file missing or mismatched, settings write fails, process dies midway) leave a truthful prior or recovered state. A user-run (external) answer server: the switch refuses with a reason and never stops that server.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-ui-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts#reconciles an interrupted delivery with an unchanged witness using the original attempt",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts#reconciles an interrupted receipt with a changed witness by replaying the original attempt",
        "modules/ui-web/src/shell-v0/views/BrainSurface.picture-switch.test.ts#clears interrupted uncertainty when the original attempt is refused with an unchanged witness"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PictureReadingInstallCompletionTest.java"
      ],
      "class": "New",
      "behaviour": "Exercise the real asynchronous installer completion after a missing projector supporting file is downloaded over private loopback HTTP: consent enables picture reading durably; a newer explicit Off, including false-to-false, wins over the earlier consent; an unrelated public theme save echoing false neither fences enable nor gets reverted. Assert completion, retained downloaded bytes, unchanged answer intent and settings reopened from disk. Native preparation is supplied by RuntimeIntentTestFixture, so this does not claim real-model or GPU validation. Existing behaviour at 222f968617 is covered; isolated production mutations demonstrate assertion failures rather than an artificial fail-before against an already-correct baseline.",
      "sources": [
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
          "scenario": "C1",
          "quote": "Switching on a part whose files are missing opens the existing consent listing everything the download will actually transfer (all missing selected parts, with sizes and licences), not only the switched part; closing or declining starts no transfer and leaves the switch as it was.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261101-uiimpl user turn proceed accordingly",
          "delegationScope": "Implement the adopted picture-reading switch design, preserving newer explicit user choices and existing settings-owner semantics during installer completion.",
          "choice": "Cover the existing picture-preference revision guard with a repeated explicit Off and an unrelated theme save that echoes the unchanged picture preference. Isolate native preparation while driving consent through the operation handler, real acquisition, completion and durable settings owner.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/s2-picture-switch-install-completion.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PictureReadingInstallCompletionTest.java"
      ]
    }
  ]
}
```


## Repair round 1 route sweep

Paths below are relative to `modules/`. The repaired production configuration preparation is the shared owner for profile activation and retained-profile refresh. JVM/environment sources remain genuine operator overrides. The generic base-source contributor can supply the projector only from the registered environment/JVM key; YAML and CI profile contributors contain no projector mapping. `HeadAssembly.generativeAppliedVersion` records an identity; it does not contribute configuration.

| Picture entry, resume or publication route | Owner, file:line | How Off applies | Covering check |
| --- | --- | --- | --- |
| Idle automatic pass, including a loaded answer server | app-services/src/main/java/io/justsearch/app/services/vdu/VduOfflineTriggerSampler.java:172 | Both branches enter the coordinator; its bound admission reads the configured resolved projector choice | PictureReadingCoordinatorTest.offBlocksAllProcedureEntriesBeforeRecoveryOrClaims; new PictureReadingProfileRepairTest |
| Manual UI / HTTP operation and agents/MCP | app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/TriggerOfflineProcessingHandler.java:36; app-services/src/main/java/io/justsearch/app/services/brainruntime/BrainRuntimeServiceImpl.java:54 | Registry handler and facade share the coordinator callback; no separate eligibility rules | PictureReadingSettingsHttpTest; PictureReadingCoordinatorTest.offBlocksAllProcedureEntriesBeforeRecoveryOrClaims |
| Startup, retained-owner recovery and retry passes | app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java:140,318,322 | Admission checked before recovery or claims; Off itself does not rewrite pending or terminal states | PictureReadingCoordinatorTest.offBlocksAllProcedureEntriesBeforeRecoveryOrClaims; onResumesPendingOnlyWithoutRereadingCompletedPictures |
| Waiting for an indexing hold / entering picture server mode | app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java:311; app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java:233 | Fence withdraws the hold wait; batch rechecks admission before each claim after server entry | PictureReadingCoordinatorTest.offWithdrawsAnExplicitWaitWithoutResumingTheIndexingPolicy; offDuringServerEntryDoesNotClaimAnyPicture |
| Between documents and multipage checkpoints | app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java:233,270 | Bound admission closes; existing deferral releases the owned claim and refunds retry; serialized fence waits for old pass exit | PictureReadingCoordinatorTest.offDuringReadingReturnsTheClaimAndOnUsesANewAttempt |
| Late result / result write already in progress | app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java:380; worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java:2604 | Fence waits for acknowledged publication and cleanup; existing worker claim/revision/exclusion checks reject stale results | PictureReadingCoordinatorTest.offWaitsForAResultAlreadyBeingSavedAndKeepsIt; failedPublicationDuringOffRefusesTheFenceAndKeepsThePriorChoice. Real deletion/exclusion and process-death races remain for independent verification |
| Ingest / changed scans / terminal empty results | worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java:3566 | Ingest queues pending work; it does not run the picture processor. Pending-only selection uses the same coordinator gate | PictureReadingCoordinatorTest.onResumesPendingOnlyWithoutRereadingCompletedPictures; real changed/deleted scan end-to-end coverage remains unverified here |

| Projector configuration route | Owner, file:line | How Off / operator provenance applies | Covering check |
| --- | --- | --- | --- |
| Initial settings contribution / pre-inference boot refresh | app-services/src/main/java/io/justsearch/app/services/config/ConfigStoreRebuilder.java:275; app-services/src/main/java/io/justsearch/app/services/bootstrap/BootstrapInferenceFactory.java:145 | Saved false contributes none; inference resolves no projector; env/JVM overrides retain their precedence | PictureReadingPreferenceTest; PictureReadingProfileRepairTest.operatorProjectorKeepsItsPathAndRefusesSwitchesAfterProfilePreparation |
| Named profile activation | app-services/src/main/java/io/justsearch/app/services/config/ConfigStoreRebuilder.java:108 | Repaired: activation contributes its projector only without a resolved per-file choice; saved none and genuine operator paths survive | PictureReadingProfileRepairTest.profileActivationCannotReopenSavedOff |
| core.reconfigure, ordinary settings refresh, retained profile | app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:174; app-services/src/main/java/io/justsearch/app/services/config/ConfigStoreRebuilder.java:94 | Retained profile uses the same repaired preparation; its internal source is not operator authority | PictureReadingProfileRepairTest.reconfigureRetainingAnActivatedProfileCannotReopenSavedOff; switchesBothWaysAfterActivationAndRetainedProfilePreparation |
| Explicit switch / profile-bearing prepared generative apply | app-services/src/main/java/io/justsearch/app/services/GenerativeSettingsComponentOwner.java:80,143,148 | Repaired: refuse by source identity, retain the resolved projector path, then apply withPictureReading; verification remains required when enabling | PictureReadingProfileRepairTest.switchesBothWaysAfterActivationAndRetainedProfilePreparation; operatorProjectorKeepsItsPathAndRefusesSwitchesAfterProfilePreparation; environmentProjectorSourceRefusesSwitchesRegardlessOfItsOrdinal |
| Install completion / Repair / runtime activation | app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:2566; app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java:974 | Existing settings owner and candidate context lead to the same builder and generative owner | PictureReadingInstallIntentTest; PictureReadingProfileRepairTest. Full installation and Repair remain independent real-system checks |
| Consented missing-file picture enable completion | app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java:1476,2328 | Completion reads the latest settings witness; the picture-choice revision preserves a newer explicit Off while unrelated saves remain eligible for enable | PictureReadingInstallCompletionTest: all three cases exercise real HTTP acquisition and durable completion; native preparation remains a fixture |
| Prepared lifecycle transaction, crash recovery | app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java:2385; app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:1512 | Prepared candidates apply withPictureReading; recovery uses the same context and prepared owner | PictureReadingConfigurationTest.preparedPictureChangeWaitsForRepliesAndAbortRestoresTheProjector; real crash recovery remains unverified here |
| Runtime apply, residency reload and VDU configuration / restore | app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java:2759,2077,2125 | Common apply strips projector from resolved none; transition fence waits for the VDU hold/restore before publishing new settings | PictureReadingConfigurationTest.runtimeAndProfileAppliesCannotReattachAProjectorWhileOff; PictureReadingCoordinatorTest.offDuringReadingReturnsTheClaimAndOnUsesANewAttempt |
| Admission predicate readers | app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java:1103; app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OfflineCoordinatorBuilder.java:90 | The manager reads its configured resolved snapshot; coordinator and batch share that supplier plus a temporary fence | PictureReadingProfileRepairTest.assertOff via activation and refresh checks; PictureReadingCoordinatorTest |

No additional production projector contributors or withPictureReading/admission readers were found beyond the listed owners and the generic registered base sources. Existing checks in the tables are bounded checks; the table does not claim new real-GPU or installation evidence. Repair evidence uses the unmodified run-evidence.mjs in a temporary shared candidate clone. A temporary Git command adapter implements its before-checkout allocation/removal with shared clones, so no source-repository worktree metadata is written. Every build has a separate checkout/build folder and frontend dependencies are copied. No owner application, dev stack or GPU is used; the shared Gradle cache, npm dependencies/cache, build slot/admission queue and loopback ports for session-owned fake servers are the shared resources touched.


## Repair builder check results

All Gradle invocations used `env -u DISPLAY -u WAYLAND_DISPLAY`, `js-gradle-slot` without `-w`, and `-PskipWebBuild=true`. `.agents` contains no preflight executable.

| Command | Observed result |
| --- | --- |
| Gradle `:modules:app-services:test :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:installDist --continue` | BUILD SUCCESSFUL in 10m 36s; 5,085 passed, 11 skipped, zero failures. Skips are three explicitly deferred composition rules, platform/native fixture conditions, the pinned GPU-device test and Windows ACL availability. This broad run preceded the last operator-path preservation edit; the final focused run below covers that edit. |
| Gradle `:modules:app-services:test --tests '*PictureReading*' --tests '*RuntimeActivationServiceChatProfileTest' :modules:app-services:pmdMain :modules:app-services:pmdTest :modules:ui:installDist --continue` | BUILD FAILED in 31s: all 24 picture checks passed; all 22 existing RuntimeActivationServiceChatProfileTest cases failed with SQLite UnsatisfiedLinkError. PMD main/test and installDist completed successfully. The fixture at lines 73–74 sets os.name to Windows 11 before SQLite's first load in this fresh focused fork; SQLite reports NativeLibraryNotFoundException for os.name=Windows on Linux. These cases passed in the earlier full run. Returned to orchestrator under the builder's non-implementation-check-failure rule; no workaround, test weakening or unrelated fixture edit. |
| `npm --prefix modules/ui-web run lint` | Passed. |
| `npm --prefix modules/ui-web run typecheck` | Passed. |
| `npm --prefix modules/ui-web run test:unit:run -- --maxWorkers=4` | 697 files / 8,871 tests passed. |
| `node scripts/ci/run-ui-web-gates.mjs` | 31/31 passed. |
| `npm --prefix modules/ui-web run knip:report` | Report written; underlying knip exit 1, as reported by the wrapper. |
| `node scripts/governance/run.mjs --gate dead-code --mode gate` | Pass; six findings, zero failed gates. |
| `run-evidence.mjs --before 7273c984c2` for the profile repair class and three UI repair checks | Candidate passed; every Java case and all three UI checks failed before with assertions. The initial expanded Java test had an ambiguous Mockito overload; its matcher was typed explicitly and the regenerated evidence compiled and passed. |
| `node scripts/governance/gates/test-intent/cli.mjs` | Only test-intent/acceptance-missing; builder writes no acceptance record. |
| `git diff --check`; `git ls-remote origin refs/heads/main` | Clean whitespace; remote main remains bcc5ece3c64961dbd224dffbae599675e265ac85, so no rebase required. |

Repairs and evidence are complete and uncommitted. Open: orchestrator must resolve or classify the existing focused runtime-profile fixture failure and obtain independent verification/acceptance. No check that was skipped is claimed as passing.


## Installer completion coverage addition

Only tests and test-intent records changed. The existing VDU entry table is unchanged; the projector-route table now names the real completion coverage at its existing owner. No VDU or projector configuration route was added. The new tests inspect installer outcomes, downloaded bytes and persisted settings reopened from disk; no assertion relies on a local fixture value that production cannot change.

All three cases pass at the unmodified `222f968617` baseline in the temporary shared clone as well as in the candidate. These are new checks of existing behaviour, so `--before 222f968617` would correctly pass rather than demonstrate a repaired defect; it was not used as artificial fail-before evidence. Instead, two isolated mutations demonstrate each assertion's sensitivity. Only the clone's production file was mutated, and it was restored byte-for-byte from that baseline after the runs (`git diff --exit-code -- AiInstallService.java` passed). No mutation entered the candidate.

| Check | Observed result |
| --- | --- |
| `env -u DISPLAY js-gradle-slot ./gradlew :modules:app-services:test --tests io.justsearch.app.services.ai.install.PictureReadingInstallCompletionTest :modules:app-services:pmdTest -PskipWebBuild=true --console=plain` | BUILD SUCCESSFUL; all three new tests passed; PMD passed. |
| `env -u DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs --out gates/test-intent/evidence/s2-picture-switch-install-completion.json --check modules/app-services/src/test/java/io/justsearch/app/services/ai/install/PictureReadingInstallCompletionTest.java` | Exit 0; three executed, passed, none skipped. Evidence is script-generated. |
| Same evidence runner on unmodified shared-clone baseline, output `build/picture-completion-baseline.json` | Exit 0; all three passed. Each tree used its own build folder. |
| Clone mutation: remove only the `getPictureReadingPreferenceRevision() > picturePreferenceAtInstallStart.witness().acceptedRevision()` guard term; same evidence runner, output `build/picture-completion-without-revision-guard.json` | Exit 1; `explicitOffAgainDuringDownloadWinsOverEarlierConsent` failed with AssertionFailedError at line 59 (expected false, actual true); both enable cases passed. |
| Clone mutation: restore the guard and change completion's `candidate.setPictureReadingEnabled(true)` to false; same evidence runner, output `build/picture-completion-enable-suppressed.json` | Exit 1; `consentedEnableTurnsPictureReadingOnAfterDownload` and `unrelatedSaveDuringDownloadPreservesTheSaveAndAllowsEnable` failed with AssertionFailedError at lines 44 and 77 (expected true, actual false); newer-Off case passed. |
| `node scripts/governance/gates/test-intent/cli.mjs` | Exit 1; only `test-intent/acceptance-missing`. No acceptance record written. |
| `git diff --check`; `git ls-remote origin refs/heads/main` | Passed; main remains `bcc5ece3c64961dbd224dffbae599675e265ac85`, so no rebase required. |

`.agents/preflight` is absent. No unrelated suites were run. Shared resources touched: Gradle cache, CPU/build slot and admission queue; loopback servers and temporary settings/operations databases belonged to these tests. No running owner application, dev stack or GPU was used. Native preparation and actual GPU/model activation remain outside these bounded completion checks. Changes are uncommitted and unpushed.
