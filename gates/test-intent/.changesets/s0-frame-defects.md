---
schema: test-intent.v1
task: t-20261103-shell
author-role: orchestrator
author-session: a446007c-8bf5-4a65-8fe9-bb7018e32a58
---

# Frame defects: queued security prompts, demo emitter, silent failures

The tests were written by a Codex builder from the orchestrator's contract; the orchestrator writes these entries from the builder's report and the generated evidence. Shell.agentEmitter.test.ts is a new isolated file and is exempt. An independent verifier (Codex, session results/s0/verifier-1) confirmed every case in real Chromium and a production build: before fails, after passes.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts"
      ],
      "class": "New",
      "behaviour": "Each queued prompt is presented with the modality its own kind requires: typed operation approvals and plugin capability consent are modal in the top layer, lightweight approvals are non-modal, in every order of queued prompts; scroll lock and focus return stay as before. (repaired cases)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "The frame-build plan the user approved, whose first step was: 'S0 · Known defects first (small, independent): the possible security gap when approval requests queue; the demo tool reachable in the real app; the silent failures'.",
          "label": "agent choice",
          "choice": "When AuthorizationHost advances its queue it ends the previous ceremony before presenting the next, so a security prompt that follows a lightweight one keeps its top-layer protection and a lightweight one that follows a modal one is not blocking."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/s0-frame-defects-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued lightweight → typed applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued lightweight → capability applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued typed → lightweight applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued capability → lightweight applies each prompt’s own modality"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts"
      ],
      "class": "New",
      "behaviour": "Each queued prompt is presented with the modality its own kind requires: typed operation approvals and plugin capability consent are modal in the top layer, lightweight approvals are non-modal, in every order of queued prompts; scroll lock and focus return stay as before. (cases that already held and are now guarded)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "The frame-build plan the user approved, whose first step was: 'S0 · Known defects first (small, independent): the possible security gap when approval requests queue; the demo tool reachable in the real app; the silent failures'.",
          "label": "agent choice",
          "choice": "When AuthorizationHost advances its queue it ends the previous ceremony before presenting the next, so a security prompt that follows a lightweight one keeps its top-layer protection and a lightweight one that follows a modal one is not blocking."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/s0-frame-defects-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued lightweight → lightweight applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued typed → typed applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued typed → capability applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued capability → typed applies each prompt’s own modality",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.toplayer.test.ts#Move 4 — trusted ceremony on the Top Layer queued capability → capability applies each prompt’s own modality"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts"
      ],
      "class": "New",
      "behaviour": "A failed 'Retry all' (refused, network, unknown) shows an error notice and leaves each row with its original error; a late failure after switching folder, closing or remounting does not affect the new target. (repaired cases)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "The frame-build plan the user approved, whose first step was: 'S0 · Known defects first (small, independent): the possible security gap when approval requests queue; the demo tool reachable in the real app; the silent failures'.",
          "label": "agent choice",
          "choice": "Report a failed retry in the failed-files drawer through the existing local ephemeral notice instead of ignoring it."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/s0-frame-defects-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback reports a refused retry failure and keeps the row error",
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback reports a network retry failure and keeps the row error",
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback reports a unknown retry failure and keeps the row error"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts"
      ],
      "class": "New",
      "behaviour": "A failed 'Retry all' (refused, network, unknown) shows an error notice and leaves each row with its original error; a late failure after switching folder, closing or remounting does not affect the new target. (cases that already held and are now guarded)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "The frame-build plan the user approved, whose first step was: 'S0 · Known defects first (small, independent): the possible security gap when approval requests queue; the demo tool reachable in the real app; the silent failures'.",
          "label": "agent choice",
          "choice": "Report a failed retry in the failed-files drawer through the existing local ephemeral notice instead of ignoring it."
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/s0-frame-defects-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback a late retry failure after switch folder does not affect the new target",
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback a late retry failure after close does not affect the new target",
        "modules/ui-web/src/shell-v0/components/FailedJobsDrawer.test.ts#FailedJobsDrawer — retry failure feedback a late retry failure after remount does not affect the new target"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts"
      ],
      "class": "New",
      "behaviour": "A failed or refused 'Go online' request (refused, network, unknown) shows an error notice and re-enables the action; a late failure after teardown still reports locally without leaving a new overlay busy. (repaired cases)",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly",
          "delegationLocation": "task t-20261103-shell user turn 2026-11-03 ~16:20Z (reply to the orchestrator's plan)",
          "delegationScope": "The frame-build plan the user approved, whose first step was: 'S0 · Known defects first (small, independent): the possible security gap when approval requests queue; the demo tool reachable in the real app; the silent failures'.",
          "label": "agent choice",
          "choice": "Report a failed 'Go online' request from the indexing overlay through the existing local ephemeral notice instead of ignoring it."
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/s0-frame-defects-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#IndexingOverlayHost — Go online failures reports a refused failure and enables the CTA again",
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#IndexingOverlayHost — Go online failures reports a network failure and enables the CTA again",
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#IndexingOverlayHost — Go online failures reports a unknown failure and enables the CTA again",
        "modules/ui-web/src/shell-v0/components/IndexingOverlay.test.ts#IndexingOverlayHost — Go online failures a late failure after teardown reports locally without leaving a new overlay busy"
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
  "session": "01a1a215-af07-7f01-bd2d-922d218963d1",
  "verdict": "accept",
  "digest": "sha256:90b67413b630d522a423a4dcb3024d0ace7a092767a030aeae9113f4b336ce73"
}
```
