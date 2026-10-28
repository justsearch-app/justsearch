---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp15b-A-continuation-3
---

D4 evidence paths are handover inputs for the orchestrator. Builder has behavioral baseline/mutation logs, but deliberately does not invoke run-evidence.mjs or create acceptance records.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts"
      ],
      "class": "New",
      "behaviour": "D-T43: Structured done without a nonempty string accepted finalResponse fails honestly live and from saved record; no JSON/empty-response success and no failed-turn follow-up context. Ask/model behavior unchanged. Live and reload checks are independent so baseline failure cannot short-circuit the other arm.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "Decision 6/D-T43 under S2: accepted-terminal-only Structured output; missing/empty/non-string final output settles failed, no accepted JSON or context, same after saved-record reload.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp15b-structured-terminal.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 missing terminal output fails honestly live",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 missing terminal output fails honestly reloaded",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 explicitly empty terminal output fails honestly live",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 explicitly empty terminal output fails honestly reloaded",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 non-string terminal output fails honestly live",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 non-string terminal output fails honestly reloaded",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 failed output contributes nothing to follow-up context (undefined)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 failed output contributes nothing to follow-up context ()",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 failed saved output contributes nothing after branching (undefined)",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.acceptedOutputCompatibility.test.ts#D-T43 failed saved output contributes nothing after branching ()"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts"
      ],
      "class": "New",
      "behaviour": "D-T44: live/reloaded FULLTEXT_FALLBACK discloses document too small for chunking through the existing retrieval-mode tail.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "Decision 8/D-T44 under S2 authorizes the small-document fallback disclosure, live and saved.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp15b-small-document.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts#D-T44 live document-too-small fallback explains its retrieval mode",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts#S2 terminal reload renders document fallback with its own wording and content"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.imports.test.ts"
      ],
      "class": "New",
      "behaviour": "D-T46: Sources-card hovered breadcrumb/detail maintain AA with margin and remain below primary ink; the bridge is local to the citations panel. Light is the repaired before failure; dark is the preservation control.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "Decision 9/D-T46 under S3 requires a local citations token-bridge repair and both-theme hovered-card pairs without shared/global token changes or axe debt.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/wp15b-sources-hover.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3Main.imports.test.ts#D-T46 Sources hover breadcrumb and preview detail clear AA with margin in light"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/responsiveState.test.ts"
      ],
      "class": "New",
      "evidence": "gates/test-intent/evidence/wp15b-shell-viewport.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/responsiveState.test.ts"
      ],
      "behaviour": "SA15 keeps the shared Shell viewport authority after deleting the old reporter/container arbitration. New focused checks call the real isWideLayout/subscribeWide API: immediate value, one media listener, live width changes, subscriber removal and wide fallback when matchMedia is absent. Old surface-width arbitration is obsolete with its only producer.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15/S1 preserve Shell viewport subscription while retiring reportLayoutWidth and old grid policies.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
