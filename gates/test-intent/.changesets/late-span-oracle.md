---
schema: test-intent.v1
task: t-20261025-665d69
author-role: builder
author-session: 01a172f4-0917-76e0-a8f9-2ee0b6aac879
---

# Correct the whole-content span oracle

Only the autonomy quote below is owner wording. The specific oracle and fixture are agent choices under the orchestrator's delegated design, supported by the established design/interface cited in each source.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoderLateChunkingTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical correction of the demonstrated invalid real-model late-chunking test oracle while preserving product vector semantics.",
          "choice": "Agent choice (orchestrator delegated design): replace full-span equality to embed() with an independent normalized mean from the direct ORT per-token hidden-state output, attention mask and tokenizer offsets; preserve document bit identity and all other real-model assertions. Add one hand-computed fixture distinguishing span mean from CLS and MEAN document pooling. Established design/interface: docs/tempdocs/691-corpus-build-throughput.md I-2 (line 887) explicitly distinguishes CLS document pool from per-span mean; line 1203 records VECTOR-only shipping. OnnxEmbeddingEncoder#poolSpan documents intersecting, nonzero offsets and masked-token exclusion. These are established design/interface references, not owner words.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "Decision records searched for late chunking, CLS, pooling and poolSpan: no conflicting vector-pooling decision found. Existing tempdoc 691 I-2 contradicts the retired whole-span == isolated-document assertion. The GTE manifest declares CLS for documents. No production behavior is changed.",
      "note": "Retires an invalid oracle, replacing it with a positive independent reference to the existing span contract. The reference does not call pool, poolSpan, runHidden, or production normalization. Original failure is established by the assignment and is not claimed as newly reproduced public evidence. No product fail-before is claimed. The rewritten test and one added pure fixture are durable; memory guards, baseline checkout and mutation are temporary scaffolding."
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/embed/onnx/OnnxEmbeddingEncoderPoolingTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "poolSpan returns the normalized mean of intersecting content tokens, excluding masked and zero-width special tokens, for either configured document pooling strategy. pool retains CLS and attention-mask MEAN document semantics.",
      "whySurvives": "All existing fixtures/assertions remain verbatim. One hand-computed, non-collinear fixture strengthens the same rule with CLS={10,0}, content mean={3,4}, special={0,20}; document CLS={10,0}, document MEAN={4,7}, and either span={0.6,0.8}. It specifically rules out treating a whole-content span as a MEAN-document vector.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "Work autonomously; don't ask me for verdicts on technical work.",
          "delegationLocation": "task t-20261025-665d69 request",
          "delegationScope": "Technical correction of the demonstrated invalid real-model late-chunking test oracle while preserving product vector semantics.",
          "choice": "Agent choice (orchestrator delegated design): replace full-span equality to embed() with an independent normalized mean from the direct ORT per-token hidden-state output, attention mask and tokenizer offsets; preserve document bit identity and all other real-model assertions. Add one hand-computed fixture distinguishing span mean from CLS and MEAN document pooling. Established design/interface: docs/tempdocs/691-corpus-build-throughput.md I-2 (line 887) explicitly distinguishes CLS document pool from per-span mean; line 1203 records VECTOR-only shipping. OnnxEmbeddingEncoder#poolSpan documents intersecting, nonzero offsets and masked-token exclusion. These are established design/interface references, not owner words.",
          "label": "agent choice"
        }
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
  "session": "01a1732f-b6b2-7b80-909e-f6a4081809d3",
  "verdict": "accept",
  "digest": "sha256:43e6c637a1552273a7c25ab5a18c61dbb414915e47fc04247dd9fed69d7aee62",
  "note": "Verified e8be57b44bbe234647a0ca63ad7bcdebe5025e54 against explicit baseline 58216932223d79d2660f2c4064a4618bbc44ef73. Established existing span/document contract before diff review. Baseline false equality reproduced; other four real-model checks and six pure pooling cases pass. Candidate selected real GTE checks: 12 passed, no skips, original deadlines; model-free worker-core: 494 passed, 19 skipped; compile and PMD passed. Corrected raw-state oracle rejects document-pooling mutant by the intended numerical assertion. Independent interior Unicode/output-shape and null/mask probes pass on both. All native test JVMs guarded at 6144 MiB RSS+swap. Product source and model metadata unchanged. Independence is sessions/checkouts only; same family. No production fail-before claimed for this oracle correction."
}
```
