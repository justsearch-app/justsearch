---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: uiux-library-actions-builder
---

# Test-intent entries

WP3.4, authored from the agreed scenarios and delegated decisions. No acceptance is authored here. The gate accepts only letter/digit IDs: S341–S349 and N341–N342 encode S34.1–S34.9 and N34.1–N34.2; agreedScenario preserves each original ID and the quote is verbatim.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "class": "New",
      "behaviour": "Add retains failed drafts and focuses the path for correction; busy submission is single-flight; picker cancellation preserves the form. Remove exposes per-row busy state, retryable failures, current-membership reconciliation after uncertain responses, focus after success, stale-response suppression after navigation, and LA1 confirmations. Reindex acceptance is separate from its recorded completion/failure and scopes empty/offline folders honestly. Preview explains inside/containing overlaps with the watched path and a live status before Add, blocks click and Enter, and accepts a corrected preview from older Engines without the optional overlap field.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S341",
          "quote": "A failed or refused Add (missing or non-folder path, inaccessible folder, Engine refusal, a folder that disappears after preview) keeps the form and the typed path, says why, and lets the user correct and retry. Cancelling the native picker changes nothing. Double submission adds the folder once.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S342",
          "quote": "Adding a folder inside, or containing, an already watched folder is explained before Add; whatever the user is allowed to add they can later remove (decision: refuse nested adds, or allow and make them removable).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S343",
          "quote": "An empty folder, or one whose files are all excluded, is added and shown as such, not as failed or still scanning.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.3"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S344",
          "quote": "After confirming Remove, the row shows that removal is in progress (exposed to assistive technology) and its actions cannot be repeated until the outcome is known; the rest of Library stays usable.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.4"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S345",
          "quote": "On success the row disappears and fresh searches in both windows no longer return its files; keyboard focus moves somewhere sensible, and the outcome is announced.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.5"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S346",
          "quote": "On failure or refusal the row stays, the message says the removal did not complete and that it can be retried, without claiming the folder is still being watched live (see S35.1), and retry works.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.6"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S347",
          "quote": "Reload, navigation away or a lost response during a removal never shows success for a removal that did not happen, nor an error for one that did; returning shows the current state and a second Remove of an already removed folder is harmless.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.7"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S348",
          "quote": "Reindex says it was accepted, then completion or failure, as separate facts; with no watched folders, or with some offline, it says so and does not claim a full reindex; failures leave a usable recovery action.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.8"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S349",
          "quote": "All of the above work by keyboard and screen reader, in both themes, High Contrast and narrow width; errors are announced and reachable.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.9"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N341",
          "quote": "The Remove confirmation's consequence statement; the preview-driven Add availability rules; \"Other sources\" collection removal; failed-files drawer Retry and Cancel (`FailedJobsDrawer.ts:395-399`).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "N34.1"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-repair-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.folderActions.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.removal.test.ts"
      ],
      "class": "New",
      "behaviour": "The declared folder-card renderer exposes aria-busy, disables and guards affected-row Remove and failed-file actions, and leaves other cards usable.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S344",
          "quote": "After confirming Remove, the row shows that removal is in progress (exposed to assistive technology) and its actions cannot be repeated until the outcome is known; the rest of Library stays usable.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.4"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S349",
          "quote": "All of the above work by keyboard and screen reader, in both themes, High Contrast and narrow width; errors are announced and reachable.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.9"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/renderers/controls/FolderCardRenderer.removal.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsFolderActionsTest.java"
      ],
      "class": "New",
      "behaviour": "Reject nested additions in both directions without side effects, retain exact re-add idempotency, reject a disappeared/non-directory folder, and durably remove a legacy child registration while retaining ancestor watchers/documents and harmless repeat removal.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S341",
          "quote": "A failed or refused Add (missing or non-folder path, inaccessible folder, Engine refusal, a folder that disappears after preview) keeps the form and the typed path, says why, and lets the user correct and retry. Cancelling the native picker changes nothing. Double submission adds the folder once.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S342",
          "quote": "Adding a folder inside, or containing, an already watched folder is explained before Add; whatever the user is allowed to add they can later remove (decision: refuse nested adds, or allow and make them removable).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S347",
          "quote": "Reload, navigation away or a lost response during a removal never shows success for a removal that did not happen, nor an error for one that did; returning shows the current state and a second Remove of an already removed folder is harmless.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.7"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N342",
          "quote": "Idempotent re-add of a watched folder (`RootLifecycleOps.java:418-430`).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "N34.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsFolderActionsTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/AddWatchedRootOverlapTest.java"
      ],
      "class": "New",
      "behaviour": "The operation handler carries the lifecycle refusal explanation with the existing stable INVALID_REQUEST code instead of an untyped generic Add failure.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S341",
          "quote": "A failed or refused Add (missing or non-folder path, inaccessible folder, Engine refusal, a folder that disappears after preview) keeps the form and the typed path, says why, and lets the user correct and retry. Cancelling the native picker changes nothing. Double submission adds the folder once.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.1"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S342",
          "quote": "Adding a folder inside, or containing, an already watched folder is explained before Add; whatever the user is allowed to add they can later remove (decision: refuse nested adds, or allow and make them removable).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/AddWatchedRootOverlapTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java"
      ],
      "class": "New",
      "behaviour": "LA1 replaces the retained-ancestor refusal expectation with successful child-registration removal, preserving ancestor-owned documents/watchers and durable membership. The scan-drain and watcher-initialization race checks retain their assertions and schedules, now seeding legacy pending nested registrations because decision 8 prohibits creating new overlaps; they still exercise actual initialization and removal through separate clients.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S342",
          "quote": "Adding a folder inside, or containing, an already watched folder is explained before Add; whatever the user is allowed to add they can later remove (decision: refuse nested adds, or allow and make them removable).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S345",
          "quote": "On success the row disappears and fresh searches in both windows no longer return its files; keyboard focus moves somewhere sensible, and the outcome is announced.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.5"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S347",
          "quote": "Reload, navigation away or a lost response during a removal never shows success for a removal that did not happen, nor an error for one that did; returning shows the current state and a second Remove of an already removed folder is harmless.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.7"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N342",
          "quote": "Idempotent re-add of a watched folder (`RootLifecycleOps.java:418-430`).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "N34.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/RootLifecycleOpsConcurrencyTest.java#retainedAncestorPreservesDocumentsWhenLegacyChildRegistrationIsRemoved"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.themeContrast.test.ts"
      ],
      "class": "New",
      "behaviour": "Follow OS changes refresh the existing derived accent-on-tint ink together with the fill so Library primary buttons remain legible through light/dark transitions. The unit check supplies resolved fills at the renderer boundary; rendered axe measurements cover Library Add Folder and Add in the browser fixture.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S349",
          "quote": "All of the above work by keyboard and screen reader, in both themes, High Contrast and narrow width; errors are announced and reachable.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.9"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-repair-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/LibrarySurface.themeContrast.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerRootPreviewTest.java"
      ],
      "class": "New",
      "behaviour": "The HTTP preview optionally names one overlapping watched path and the inside/containing relationship before Add. Existing fields remain intact; exact membership takes precedence over legacy overlaps, and siblings sharing a text prefix are not overlaps. Preview has no mutation effects.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S342",
          "quote": "Adding a folder inside, or containing, an already watched folder is explained before Add; whatever the user is allowed to add they can later remove (decision: refuse nested adds, or allow and make them removable).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.2"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "N342",
          "quote": "Idempotent re-add of a watched folder (`RootLifecycleOps.java:418-430`).",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "N34.2"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-repair-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerRootPreviewTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerSaturationTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The fixture registers the folder first because unregistered removal became a no-op (S34.7). Only the remove parameter seeds the existing watched-roots format before client construction and before saturating the same Worker executor. Admission, queue saturation, invocation, and every oracle remain unchanged: HTTP 429, Retry-After, preserved error code, retrySafe=false, and no ingest-service access.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "S347",
          "quote": "Reload, navigation away or a lost response during a removal never shows success for a removal that did not happen, nor an error for one that did; returning shows the current state and a second Remove of an already removed folder is harmless.",
          "label": "agent-drafted, owner-adopted",
          "agreedScenario": "S34.7"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "allright i agree. you dont need to wait for my opinion regarding the designs, i agree with you on them. you can proceed with implementation after the designs.",
          "delegationLocation": "task t-20261019-bd718f agreement decision 4",
          "delegationScope": "Remaining UI/UX designs and implementation, including WP3.4 Library folder actions.",
          "choice": "Decision 4 delegation: implement the agreed folder-action outcomes. Decision 8 refuses new nested adds at the Engine convergence point, while exact re-add stays idempotent. LA1 keeps legacy nested registrations until explicit removal: removing a child retains ancestor-covered documents; removing an enclosing folder removes the whole watched subtree. Legacy confirmations explain those consequences.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-library-actions-saturation-adaptation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/IndexingControllerSaturationTest.java"
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
  "session": "zeav5urd",
  "verdict": "accept",
  "digest": "sha256:63f5cfc5dcf18f45a9f9e56a82ca8546d139c6ec728ca925aa514072da0802ee",
  "note": "Expectations follow agreed WP3.4, decision 8 and corrected LA1; named checks pass on candidate and assert-fail before (UI rerun used hardlinked dependencies). Test-intent acceptance only; product S34.2 preview and S34.9 dark contrast remain findings."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "ktv9yz16",
  "verdict": "accept",
  "digest": "sha256:4f8b4b9f70fca7c40deeb4bba8bc0f110878f77ec91bef0bee0619b68564502e",
  "note": "Expectations follow agreed WP3.4, decision 8 and corrected LA1. Repaired checks pass on 3895b9660 and assertion-fail on 2d3faef2d. The saturation fixture adaptation preserves all nine refusal oracles and Worker saturation. Acceptance concerns test intent; full UI suite has the same missing-esbuild failure before and after."
}
```
