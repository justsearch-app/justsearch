# Real-model long-document test budget correction (2026-09-14)

C2 queue verification exposed a pre-existing test-budget error at base9dfaf9ee4.
The unchanged OnnxEmbeddingEncoderLongDocForensicTest long method took54.863s in
integrated1665 and56.32s in isolated1666, exceeding the generic30s JUnit default.
Both runs completed three FP32 CPU inference passes over5,141 tokens; both long
cosine comparisons were1.000000. Neither run is recorded as passing.

## Root cause and correction

The test class deliberately selects real model.onnx with ExecutionProvider.CPU
and FP32 (class documentation and session construction). Its contract reproduces
numerical corruption for2049-8192 tokens, not an inference latency SLO. The30s
budget is inherited from build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt;
there was no method-local Timeout. The historical B-stage CPU-fallback and explicit
annotation interpretations were incorrect and are corrected in the owning notes.

Independent read-only review confirmed the source intent and the adjacent
OnnxEmbeddingEncoderBoundedTokenizeTest two-minute real-model method precedent.
The correction adds only TimeUnit/Timeout imports and a two-minute timeout to
longDocEmbedWithSpansMatchesBaseEmbed. The short control keeps the global default.
Token target, all three inference paths, FP32 CPU provider, model-presence gating
and all numerical assertions are unchanged. No production inference or global
budget changes are justified by this failure.

## Verification and limits

- 1665: indexer-worker executed598 cases/95 suites,15 skips, zero failures/errors.
  Worker-core executed342 cases/84 suites, six skips, one timeout failure.
- 1666: unchanged isolated real-model class executed2 cases, zero skips, one timeout.
- 1667: scoped correction executed both real-model cases, zero skips/failures/errors;
  Worker-core test PMD and whole Spotless passed. The input remains5,141 tokens and
  both long cosine comparisons remain1.000000.
- Full Worker-core1668 is pending at this checkpoint. A narrow rerun does not replace
  the failed integrated run; the production queue has not changed since1665.

Commands are in tmp/1665-counts.json through tmp/1667-counts.json and their complete
logs tmp/1665.txt through tmp/1667.txt. Copied JUnit XML is in the matching
*-xml directories in F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp.
Retain logs, counts and XML through final lane reconciliation plus30 days, at least
2026-10-14. Hosted proof for this correction remains required.
