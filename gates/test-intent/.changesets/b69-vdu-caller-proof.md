---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b69-vdu-caller-proof
---

The observation caller check this changeset added is retired by b72-vdu-presence. The automatic path-label read answers malformed or invalid input like the
invocation it replaced. Legacy browser-local conversation titles are adopted only when a person
acts. Proof and live results: F:/agent-sandbox/design-b6d536/codex/b69-vdu-caller-proof/report.md.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java", "modules/ui/src/test/java/io/justsearch/ui/api/PathLabelErrorContractTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Malformed JSON to POST /api/library/path-label answers 400, as POST /api/operations/{id}/invoke does for the same input. Malformed, empty, non-object, extra-field, non-string and blank pathHash bodies all answer 400 with the invocation's error body fields (success false, message, structuredData, errorClass BAD_REQUEST) and never reach the resolver. VerifierSixHydrationTest is the verifier's class copied unchanged.",
      "sources": [{"kind": "owner-words", "quote": "the new routes return the same client-error status and body shape as the path they replaced for malformed or invalid input", "location": "task t-20261005-b6d536 request (b69 builder contract)"}, {"kind": "owner-words", "quote": "malformed JSON (\"{\") to POST /api/library/path-label returns 500 INTERNAL_ERROR; the operation invocation it replaced returns 400 BAD_REQUEST for the same input", "location": "task t-20261005-b6d536 request (b69 builder contract)"}],
      "evidence": "gates/test-intent/evidence/b69-vdu-repaired.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java#pathLabelMalformedJsonPreservesInvocationClientError", "modules/ui/src/test/java/io/justsearch/ui/api/PathLabelErrorContractTest.java#malformedAndInvalidInputMatchTheReplacedInvocationErrorShape"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java", "modules/ui-web/src/shell-v0/state/legacyTitleMount.test.ts"],
      "class": "New",
      "behaviour": "Preserved neighbours copied from the verifier unchanged: the facet and path-label reads reject smuggled query, filter, pipeline, document, operation and raw-path fields; facet malformed JSON keeps the search route's error; the invocation route keeps its 400; a fresh automatic list load sends only the observation GET.",
      "sources": [{"kind": "owner-words", "quote": "the malformed path-label case, and the legacy-title mount case (copy them in, keeping their assertions)", "location": "task t-20261005-b6d536 request (b69 builder contract)"}],
      "evidence": "gates/test-intent/evidence/b69-vdu-preservation.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java#collectionCannotSmuggleQueryFiltersPipelineOrDocumentFields", "modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java#pathLabelCannotInvokeAnotherOperationOrResolveByRawPath", "modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java#collectionMalformedJsonPreservesExistingSearchError", "modules/ui/src/test/java/io/justsearch/ui/api/VerifierSixHydrationTest.java#existingInvocationMalformedJsonRemainsClientError", "modules/ui-web/src/shell-v0/state/legacyTitleMount.test.ts#control: fresh automatic list hydration sends only the observation GET"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/state/legacyTitleMount.test.ts", "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "An automatic list load with a retained jf-conversation-titles entry for an untitled backend conversation sends no POST /title (a USER_ACTION route) and keeps the entry; an untrusted synthetic event sends none either. The first trusted input adopts the name once as the reader's (source user) and erases the entry. The verifier's mount case is copied with its assertions.",
      "sources": [{"kind": "owner-words", "quote": "automatic title adoption must not count as a person acting: choose between a bounded observation route for adoption, deferring adoption until a person acts, or another design", "location": "task t-20261005-b6d536 request (b69 builder contract)"}, {"kind": "owner-words", "quote": "a title a person sets stays USER_ACTION", "location": "task t-20261005-b6d536 request (b69 builder contract)"}],
      "evidence": "gates/test-intent/evidence/b69-vdu-legacy-repaired.json",
      "newChecks": ["modules/ui-web/src/shell-v0/state/legacyTitleMount.test.ts#automatic mount list hydration with retained legacy titles emits no user-action request", "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#adopts a legacy browser-local title, then ERASES the key — the migration completes"]
    },
    {
      "items": ["modules/ui-web/src/shell-v0/state/conversationListStore.test.ts"],
      "class": "New",
      "behaviour": "The two remaining tempdoc-838 migration cases now reach adoption through a trusted input instead of the list load, and keep their outcomes: a 423 keeps both legacy names and stops re-posting them for the session; a legacy entry the backend has outgrown is dropped with no write.",
      "sources": [{"kind": "owner-words", "quote": "deferring adoption until a person acts", "location": "task t-20261005-b6d536 request (b69 builder contract)"}],
      "evidence": "gates/test-intent/evidence/b69-vdu-preservation.json",
      "newChecks": ["modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#does NOT erase a legacy title the locked store refused, and stops re-POSTing it", "modules/ui-web/src/shell-v0/state/conversationListStore.test.ts#drops a legacy entry the backend has already outgrown, without a second write"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review. The builder does not provide its own acceptance.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu7-20261012",
  "verdict": "reject",
  "digest": "sha256:2fce950d318b76289310f6cb4c32bd48654b2f231784c8bdbc00ad89f1ff298f",
  "note": "Candidate 3d6c71cfd vs 469d8f10f. Rejected: (1) CALLER-MECHANICS: the observation caller check accepts 12 of 25 independent must-reject adversaries (reassigned target, barrel alias, shadowed const, shorthand and quoted POST method, local fetchFn and cache.fetch, click-fed subscription, two name collisions, click-mounted connectedCallback, route only in a query string); (2) LIVE-LEGACY-TITLE-DISPLAY: after an untouched reload, the person's first input (opening History) posts the adoption while the list reloads; the list answer predates the write and the local copy is already erased, so the row shows the first message instead of the retained title until a later reload (reproduced twice live; own unit L10 fails on the candidate). Passed: full JVM suites, web suite, typecheck, path-label 400/retry preservation, roster/failed-jobs/ledger/preview/chat/typing/rename interrupt with later completion, untouched reload not interrupting, idle Library automatic completion with all-observation traffic, GPU-on replay and raster search, admin GPU-off completing on CPU. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-vdu7/. Owned stacks stopped; no product/test edits, commits or pushes."
}
```
