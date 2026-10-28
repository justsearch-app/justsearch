---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: wp15b-A-continuation-3
---

SA15 compatibility implementation. D4 files and independent acceptance are left to the orchestrator as required; no run-evidence call or acceptance is fabricated.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/chrome/Shell.compatibilityRegistration.test.ts"
      ],
      "class": "New",
      "behaviour": "SA15: cold production Shell eagerly registers Search and all six surviving public elements, before Settings loads; all eight authoring tokens traverse generated vocabulary/schema/runtime without declaration rewriting. Old root constructs Search via surface/view/shape factories, ranked/override dispatch, hints and Stage fallback; identity, provenance, brand forgery rejection, cross-owner refusal and trust/admission stay intact.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15 compatibility, independent registration, cold plugin factory and unchanged trust/admission obligations in scenarios-wp1.5.md.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp15b-authoring-compat.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/chrome/Shell.compatibilityRegistration.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts"
      ],
      "class": "New",
      "behaviour": "SA15 replaces lazy Search loading with eager landing-window registration and removes only its lazy-loader entry. Other tests retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "delegationLocation": "task t-20261019-bd718f request (2026-10-19)",
          "delegationScope": "skip the lower tier for now. then proceed with designing and implementing the remaining work",
          "choice": "SA15: eager Search replaces the old eager root; retain public element registrations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/wp15b-eager-search.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.test.ts#registers eagerly as the landing window without a lazy loader"
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
  "session": "01a183bd-577c-7b03-a83c-3769053d2773",
  "verdict": "accept",
  "digest": "sha256:5db45bf5a9c11fff24843badf146b7beebd757d67e46e8af46b32bb8da6c7914",
  "note": "Codex verifier-2 (separate session, same family): SA15 New expectations and execution evidence fit delegation."
}
```
