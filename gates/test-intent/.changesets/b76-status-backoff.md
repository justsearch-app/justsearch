---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b76-status-backoff
---

A successful status read ends the fresh-read back-off; a fresh read answered before its lane reflects is followed up by bounded ordinary reads; the producer-classification check imports only the classes that name the status counter. Independent acceptance remains pending.

The evidence file's fail-before (`--before ff5bb2ae9`) was produced in a throwaway copy of this candidate. Its ui-web/node_modules was a private install that the script's worktree removal emptied, as expected. In that run, five cases fail by assertion and the five preservation cases pass.

The round-7 verifier case OWN7-REFUSED-ADVANCING (at most 6 fresh reads over 200 advancing responses while fresh reads are refused) is not copied here, and it records 10 on this candidate. Its bound of 6 assumed a single clock. The case advances a mocked `Date.now` by 3.6 s in total, but leaves the 10 s status poll on the real clock, and about 90 s of real time pass. Each successfully answered ordinary poll then ends the back-off, by the rule that any successful status read ends it, and is followed by exactly one refused fresh read. Two clock-consistent cases in statusBackoff.test.ts replace it as the evidence:
- the same 200 responses over 3.6 s on one clock give at most 6 fresh reads (3 observed);
- over 90 s of refused fresh reads, there is at most one fresh read per answered poll once the back-off exceeds the poll interval.

One refused fresh read per poll interval, while only fresh reads are refused, is the bounded behaviour the orchestrator accepted.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/api/statusBackoff.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "After refused fresh status reads, the first successful status read (ordinary poll or fresh read) ends the back-off, so a later change signalled by another client's response, or by the UI's own successful action, gets its fresh read at once and shows at the next render. When a fresh read is answered before every lane reflects its value, the next request after the lane reflects shows the change without another fresh read: later signals start ordinary reads, at most three per value, and status responses start none. Back-off still bounds refused reads and honours Retry-After while status reads keep failing; at most one fresh read is in flight and one per value. The round-7 verifier cases OWN7-BACKOFF-THEN-ACTION and OWN7-BACKOFF-REALISTIC are copied with their assertions kept; OWN7-LAGGING-FRESH is copied with an assertion added; OWN7-REFUSED-ADVANCING is repeated on one consistent clock.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.",
          "location": "task t-20261005-b6d536 request (task item 5 completion condition, quoted in the b76 builder brief)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.",
          "delegationLocation": "task t-20261005-b6d536 request (task item 5)",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20)",
          "choice": "Orchestrator decision in the b76 brief: any successful status read (ordinary poll or fresh read) that shows the server is answering again ends the back-off; an action or receipt signal made after recovery gets its fresh read at once; back-off still bounds refused or failed reads and Retry-After is honoured while it applies; the display shows a change at the next render once the server reflects it, with at most one fresh read in flight and a bounded total per value.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b76-status-backoff-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/statusBackoff.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusStateFieldsTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Every caller of StatusChangeCounter.changed in product code is a classified producer in StatusFieldClassification.PRODUCERS, and every classified producer still calls it (everyOwnerPublicationIsAClassifiedProducer).",
      "whySurvives": "The predicate and both assertions are unchanged; only the ArchUnit import is narrowed. Code that calls a StatusChangeCounter method has a Methodref to that class in its constant pool, so its class file contains the internal name io/justsearch/core/status/StatusChangeCounter. The new import option keeps exactly the class files that contain those bytes, plus the counter itself, so the set of call sites to the counter is the same as with the whole-product import. The second assertion would fail if the narrowing dropped a real producer. The whole-product import alone exhausted the 384m test heap: a heap dump of the full ui suite at ff5bb2ae9 shows the worker out of memory inside this test, with ArchUnit import records dominant and the heap flat across earlier tests. With the narrowed import the full ui suite completes at 384m."
    }
  ]
}
```
