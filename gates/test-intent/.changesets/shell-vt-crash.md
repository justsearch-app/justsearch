---
schema: test-intent.v1
task: shell-vt-crash
author-role: builder
author-session: 01a15744-a402-7e41-af4b-4a92b5010015
---

# Linux desktop shell view-transition exclusion

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "turn off view transitions in the Linux desktop shell",
          "label": "agent choice"
        }
      ],
      "behaviour": "On first use in a Linux Tauri shell, surface switching and the Search v3 composer never call startViewTransition, even with the API available and motion allowed. Composer state is applied exactly once and neither morph attribute remains.",
      "repaired": true,
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts#gates surface switching in a Linux Tauri shell on first use",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a Linux Tauri shell"
      ],
      "evidence": "gates/test-intent/evidence/shell-vt-crash.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i agree, i will be going to sleep now though, so i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.\nyou can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261018-6108b0 request; task t-20261018-6108b0, owner turns, orchestrator session cc6d91b2-5314-42fd-867b-dbc2d8a68699, 2026-10-18 and 2026-10-19",
          "delegationScope": "the testing task t-20261018-6108b0 and the fix tasks routed from it, including choosing fix-or-defer and the scenarios for each fix",
          "choice": "turn off view transitions in the Linux desktop shell",
          "label": "agent choice"
        }
      ],
      "behaviour": "Preservation coverage: Windows and macOS Tauri shells and Linux browsers still start transitions with the API present and motion allowed. API-absent and reduced-motion composer paths still apply state exactly once without transitions or remaining morph attributes. Existing API-absent, reduced-motion and rejection-adoption checks remain unchanged.",
      "repaired": false,
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts#gates surface switching in a Windows Tauri shell on first use",
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts#gates surface switching in a macOS Tauri shell on first use",
        "modules/ui-web/src/shell-v0/chrome/viewTransition.test.ts#gates surface switching in a Linux browser on first use",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a Windows Tauri shell",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a macOS Tauri shell",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a Linux browser",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a API absent",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#applies composer state once and clears morph flags in a reduced motion"
      ],
      "evidence": "gates/test-intent/evidence/shell-vt-crash-preservation.json"
    }
  ]
}
```

Baseline shared clone: 39 existing focused tests pass. With the candidate tests overlaid,
47 pass and the two Linux-shell exclusion cases fail assertions. After the product change,
all 49 focused cases pass. Removing only the Linux check in a separate shared clone again
fails those two cases, with the other 47 passing. Logs are in tmp/shell-vt-crash/.

The evidence producer ran from a separate shared clone; its internal before-worktree
belongs to that clone, leaving the source repository worktree metadata untouched.

No acceptance record is authored by the builder.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-a382b6a881bf89c5e",
  "verdict": "accept",
  "digest": "sha256:7c73be4c406b54006278bca017ca6326b57ddf116403542ac0f61b94edb20401",
  "note": "Real built Linux shell (own Engine) under Xvfb and headless kwin_wayland, 3 configs x 4 triggers x 3 launches: base SIGSEGV after the trigger in every config, candidate 72/72 alive with state changed and zero startViewTransition calls; unit tests fail on base for the reason under test and four gate mutations are caught."
}
```
