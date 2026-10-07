---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b22-abandon-status
---

# Abandoned proxy response test intent

The A10 half-close ruling counts client FIN as abandonment. These real HTTP/1 socket
checks use Javalin, the production proxy and shared lane, and a loopback fake upstream.
The receiving client remains open. Repair assertions fail on 6ad3abfbb and pass after
the repair. Preservation cases pass on both. Earlier changesets and verdicts remain
history; independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A queued write-half-closed proxy client receives no HTTP status or completion, leaves without upstream dispatch while another exchange holds the lane, and a fresh exchange succeeds afterwards. A write-half-closed client whose stream has started receives no completion or DONE; the connection ends as an error and the lane serves the next exchange.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b22 builder role contract",
          "quote": "when the OpenAI-compatible proxy treats a queued (or dispatched) exchange as abandoned because its client closed, it must never write a success."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b22 builder role contract",
          "quote": "If a status is already committed (a stream that has started), end the stream as an error the way the proxy already ends failed streams, never as a normal completion."
        }
      ],
      "evidence": "gates/test-intent/evidence/b22-abandon-status-repairs.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java#halfClosedQueuedClientReceivesNoStatusOrCompletionAndFreesLane",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java#halfClosedStartedStreamHasNoNormalCompletionAndFreesLane"
      ]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java"],
      "class": "New",
      "behaviour": "A client keeps its sending side open, waits fifteen seconds while the holder runs without receiving an early response, then receives its requested completion. A queued TCP RST leaves before release without upstream dispatch. Both cases leave the lane able to serve a fresh request. They pass before and after; the evidence producer's before-state pass is expected for preservation.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b22 builder role contract",
          "quote": "Everything else stays as it is: abandonment detection, lane release, no dispatch for a detected close, the RST case, the patient-client case, and unchanged upstream statuses and bodies."
        }
      ],
      "evidence": "gates/test-intent/evidence/b22-abandon-status-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java#patientClientWaitsLongOpenThenReceivesCompletion",
        "modules/ui/src/test/java/io/justsearch/ui/api/ProxyAbandonmentSocketTest.java#resetQueuedClientNeverDispatchesAndFreesLane"
      ]
    }
  ]
}
```
