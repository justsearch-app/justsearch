---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b69-vdu-caller-proof
---

The observation caller check proves method, request target and an unattended trigger for every
consumer claim. The automatic path-label read answers malformed or invalid input like the
invocation it replaced. Legacy browser-local conversation titles are adopted only when a person
acts. Proof and live results: F:/agent-sandbox/design-b6d536/codex/b69-vdu-caller-proof/report.md.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/activity/observationCallers.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "A consumer claim passes only when the named file makes a request with the route's HTTP method, read from the request's own init argument, to the route, read from the request's target argument only, and that request is reached from an unattended trigger: module evaluation, a mount or connected lifecycle callback, a timer, a subscription or status delivery, or a stream event. A request reached only from a person's event handler, a POST offered for a GET route, and a route that appears only in an options payload are rejected. The verifier's three adversaries and two controls are copied with their assertions. Every table entry is checked this way. On 469d8f10f the table's GET plugin-allowlist claim names a file that only POSTs it, and cross-file claims carry no traceable chain, so the file fails by assertion.",
      "sources": [{"kind": "owner-words", "quote": "the check must match the HTTP method and the actual request target argument, and must prove that the request is reached from an unattended trigger (timer, mount or connected callback, subscription or status delivery, stream), not only from event handlers a person fires; when it cannot prove that, it must reject", "location": "task t-20261005-b6d536 request (b69 builder contract)"}, {"kind": "owner-words", "quote": "the verifier's three caller-check adversaries and two controls (copy them in, keeping their assertions)", "location": "task t-20261005-b6d536 request (b69 builder contract)"}],
      "evidence": "gates/test-intent/evidence/b69-vdu-repaired.json",
      "newChecks": ["modules/ui-web/src/activity/observationCallers.test.ts"]
    },
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
