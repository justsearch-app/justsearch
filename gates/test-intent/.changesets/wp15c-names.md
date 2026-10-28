---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp15c-names-builder-20261028
---

# Test-intent entries

W7/M1 naming expectations are agent choices under the quoted delegation.
The orchestrator will generate execution evidence and obtain independent acceptance.
The cutover script self-tests and e2e replay label adaptation are outside product-test gate scope.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename"
      ],
      "whatMoved": "The existing mounted-window title expectation changes from Search v3 to Search under W7; the controls, structure, inputs and all other outcomes remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window promotion work; W7/M1 names and completion marker.",
          "choice": "W7: call the window Search in the rendered title band, rail and catalog, preserving all stable ids; describe searching files and asking questions about them. M1: report completion when USER audience and the explicit marker both exist.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts",
        "modules/app-api/src/test/java/io/justsearch/app/api/SearchWindowNameTest.java"
      ],
      "class": "New",
      "behaviour": "W7: the real rendered title band and expanded rail text, tooltip and accessible name say Search. The actual shipped message bundle resolves the catalog label to Search and describes searching files and asking questions about them; Java loads the packaged properties through Properties. Stable surface id remains core.search-v3-surface.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "Designing and implementing the remaining Search window promotion work; W7/M1 names and completion marker.",
          "choice": "W7: call the window Search in the rendered title band, rail and catalog, preserving all stable ids; describe searching files and asking questions about them. M1: report completion when USER audience and the explicit marker both exist.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp15c-names.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#W7 renders Search in the title band and rail using the real catalog message bundle",
        "modules/app-api/src/test/java/io/justsearch/app/api/SearchWindowNameTest.java#searchLabelResolvesFromTheShippedMessageBundle"
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
  "session": "01a183d3-0fd4-7a61-a8d0-73ceba0e387b",
  "verdict": "accept",
  "digest": "sha256:4c25a0ac9673f21ba3327103f26c9be067741153f929ed8da87143b0d742dc9d",
  "note": "Codex verifier PASS on WP1.5c (delegated-choice sources checked against owner-request.md; W7 and SearchWindowNameTest fail before by assertion, pass after)"
}
```

```json
{
  "kind": "audit",
  "role": "verifier",
  "session": "01a184b9-f209-7de1-a977-bdc41f590b2c",
  "verdict": "accept",
  "digest": "sha256:4c25a0ac9673f21ba3327103f26c9be067741153f929ed8da87143b0d742dc9d",
  "note": "Adaptation and New expectations follow delegated W7 intent; current-source evidence independently reproduced."
}
```
