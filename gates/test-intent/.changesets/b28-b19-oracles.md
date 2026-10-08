---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b28-b19-oracles-20261008
---

# b28: strengthen b19 false-pass oracles

Follow-up to b19 entries 1, 2 and 10. Historical b19 evidence and rejection/acceptance/tie-break records are retained unchanged. Production behaviour is unchanged; no entry claims a production repair or a fail-before on unmodified production.

Mutation evidence (old passes, new assertion fails) and exact sources/logs/XML: F:/agent-sandbox/design-b6d536/codex/b28-b19-oracles/report.md. The mutations are temporary uncommitted production edits in a detached scratch worktree. Fresh evidence below is produced by the repository tool on unchanged candidate production. No acceptance records are added.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "An optional internal exchange expires as INFERENCE_BUSY without cancelling its parent, including after lane recovery. Verification rejects any public parent.cancel invocation; the previous constant empty cancellation reason was insufficient. This corrects an oracle, not production behaviour. The scratch expiry mutation passes the old check and fails the strengthened check by Mockito assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b28 builder oracle repair contract",
          "quote": "Make the oracle fail if production cancels the parent."
        }
      ],
      "evidence": "gates/test-intent/evidence/b28-b19-oracles.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/GenerationAdmissionTest.java#expiredExchangeNeverDispatchesAndDoesNotCancelParent"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "behaviour": "The shared Work fixture makes public handle.cancel observable through first-wins cancellation reason and callbacks; explicit cancellation uses that same public handle. Expiry and durable detachment preserve parent work and forbid public cancellation; explicit queued/body cancellation remains observable and releases ownership. Scratch expiry and post-body durable cancellation mutations pass the old checks and fail the strengthened checks by assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b28 builder oracle repair contract",
          "quote": "Wire the mock so a handle cancellation is observable, and assert it as the contract requires (cancelled where the contract says so, untouched where it says not)."
        }
      ],
      "evidence": "gates/test-intent/evidence/b28-b19-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#expiredProxyWaiterIsBusyNeverDispatchedAndDoesNotCancelParent",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#durableProxyDisconnectBecomesBackgroundWhileWaiting",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancelledProxyWaiterNeverDispatches",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancellingHandedOffProxyBodyFreesLaneBeforeDownstreamClose",
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#cancelledInternalStreamWaiterNeverDispatchesOrAnswers"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "behaviour": "The proxy forwards unchanged HTTP500, native HTTP400, SSE400 and the retained SSE500 context overflow bodies and statuses. The legacy SSE500 case is additional to the native SSE400 case, using the same legacy HTTP500 error envelope.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b28 builder oracle repair contract",
          "quote": "Also restore the SSE500 overflow case as an additional case beside the SSE400 one, if the contract still covers it."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "V7",
          "quote": "The proxy forwards the upstream status and body unchanged, so its clients keep OpenAI-compatible semantics.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b28-b19-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#twoProxiesNeverOverlapTheirUpstreamExchanges"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java"
      ],
      "class": "New",
      "behaviour": "A real loopback Jetty/Javalin connection retains live pipelining and keep-alive while queued. Both responses have HTTP200 and their distinct exact choices/message/content body, in request order. HTTP framing is parsed by content length or chunks, complete bodies are required, and no third response is allowed. Upstream order and the independent queued internal cancellation check remain. The scratch body-discard mutation passes the old check and fails the strengthened check by assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b28 builder oracle repair contract",
          "quote": "Assert each response's body (the expected completion content for each request, in order)."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn b28 builder oracle repair contract",
          "quote": "The queued-stream cancellation check is sound: keep it."
        }
      ],
      "evidence": "gates/test-intent/evidence/b28-b19-oracles.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/ManagedGenerationProxyTest.java#socketProbePreservesPipelinedRequestsAndKeepAlive"
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
  "session": "verify-b28-20261008",
  "verdict": "accept",
  "digest": "sha256:7a904e55e43521fba80c77932067e963a521698acbc56a2f9e92ff2833a4197d"
}
```
