---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b34-e7c-repair-codex-20261008
---

E7c repair checks follow the builder brief of 2026-10-08. The fresh-profile boot also runs as an installed-process assertion; the ordering guard is supplementary. Native provider evidence reads the created session's ORT kernel profile. The ingest check uses a real Worker, SQLite queue, recorded filesystem traversal and Lucene document lookup after 16k -> 32k and changed automatic/default arena caps. No existing oracle is removed or relaxed.

```json
{
  "entries": [
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ContextReplanIngestTest.java"],
      "class": "New", "repaired": true,
      "behaviour": "An approved recorded root walk after query-only context/memory replanning completes and its sentinel document appears in Lucene, preserving the exact running producer.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Item 1's check must cover a real ingest after a context change, not only the query guard.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b34-e7c-repairs.json",
      "newChecks": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ContextReplanIngestTest.java"]
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GpuOffNativeRerankerSessionTest.java","modules/configuration/src/test/java/io/justsearch/configuration/resolved/ResolvedConfigBuilderTest.java"],
      "class": "New", "repaired": true,
      "behaviour": "Effective GPU-off vetoes both reranker configurations even with explicit per-role GPU overrides. A real session is CPU, executes a native kernel through CPUExecutionProvider, and neither configures nor runs CUDA.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Every ONNX or native session must follow the effective policy, including the reranker.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b34-e7c-repairs.json",
      "newChecks": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/GpuOffNativeRerankerSessionTest.java","modules/configuration/src/test/java/io/justsearch/configuration/resolved/ResolvedConfigBuilderTest.java#gpuPolicyVetoIncludesBothRerankersEvenWithExplicitGpuOverrides"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/HeadlessAppAutostartOrderingTest.java"],
      "class": "New", "repaired": true,
      "behaviour": "Autostart seeding occurs after physical settings owners are sealed and settings recovery is reconciled; the earlier ServicePhase must not issue this settings transaction.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Make that boot path work.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b34-e7c-repairs.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/HeadlessAppAutostartOrderingTest.java"]
    },
    {
      "items": ["modules/app-inference/src/test/java/io/justsearch/app/inference/OwnedStreamCancellationTest.java"],
      "class": "New", "repaired": true,
      "behaviour": "A real HTTP SSE body containing 256 reasoning deltas survives a temporarily blocked consumer and delivers all deltas before completion. A finite network burst must not terminate chat with inference.callback QUEUE_LIMIT. Existing nonblocking-dispatch overflow, cancellation, ownership and urgency tests retain their oracles.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Find what fills that queue under the scripted load and repair it if it is a defect, so a scripted agent's chat does not fail under that load.", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}],
      "evidence": "gates/test-intent/evidence/b34-e7c-repairs.json",
      "newChecks": ["modules/app-inference/src/test/java/io/justsearch/app/inference/OwnedStreamCancellationTest.java#bufferedReasoningStreamSurvivesTemporarilySlowConsumer"]
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/reranker/SplitRerankUnderOneLeaseTest.java"],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The fake device identifier of the one-seat split-rerank/aged-bulk scenario moves from 174 to unused 23174. DeviceSeatDuringCpuStepTest also uses 174 and installs an incompatible process-lifetime gate policy; one JVM running both classes therefore fails before entering the split-rerank scenario. Both the reranker and bulk handle still share the same isolated device and the same one-seat/50-ms-aging policy. Query text, documents, timing, waiter/seat observations, expected event order, exceptions and every assertion are unchanged. The second scenario is unchanged.",
      "sources": [{"kind": "delegated-choice", "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself", "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z", "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.", "choice": "Keep the existing checks passing", "label": "agent choice", "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"}]
    },
    {
      "items": ["modules/app-inference/src/test/java/io/justsearch/app/inference/StreamCallbackBackpressureTest.java"],
      "class": "New",
      "behaviour": "An interrupted producer waiting for callback capacity exits without dispatching its delta or acknowledging a still-running consumer.",
      "sources": [{"kind": "owner-words", "quote": "Coordinate with the inference lane's host contract (priority and aging).", "location": "task t-20261005-b6d536 request (item 5)", "provenance": "quote corrected to the owner's exact words; it was paraphrased as \"coordination with the inference lane's priority and aging\""}],
      "evidence": "gates/test-intent/evidence/b34-e7c-backpressure.json",
      "newChecks": ["modules/app-inference/src/test/java/io/justsearch/app/inference/StreamCallbackBackpressureTest.java"]
    }
  ]
}
```

## Acceptance records

Independent verifier acceptance is pending.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-e7d-codex-20261009",
  "verdict": "accept",
  "digest": "sha256:cc11250c5e257d0da44588e81be0b9e6273a42d2f914eb1a42e24ba30ad1b7b6",
  "note": "Independent checks: candidate 129 selected pass; parent 123 pass/5 targeted assertion failures; interruption compatibility probe fails parent by assertion, exact new API unavailable there; split-rerank preservation passes both with unchanged assertions. Fresh autostart and post-context prepared ingest pass live. Acceptance concerns these test intents, not overall product acceptance: GPU-off llama placement and VDU remain findings; quiet long windows and memory repeat pending. Evidence F:/agent-sandbox/design-b6d536/codex/verify-e7d/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "accept",
  "digest": "sha256:e781d66d73e0eeae25e96ec149770ca31ebb37dd98c5fa6053bcc11af1e3dfff",
  "note": "Re-reviewed the source/provenance corrections, unchanged checks/evidence and current candidate reruns. The earlier verifier note supplies the true missing-API reason for candidate-only backpressure execution; selected current checks pass."
}
```
