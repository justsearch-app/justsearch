---
schema: test-intent.v1
task: t-20261025-665d69
author-role: builder
author-session: 01a17476-fd07-7c11-a471-87158f9bef59
---

# UTF-16 span repair checks

The three new test files are isolated additions; existing tests, expectations, tolerances,
selection rules and prior changesets remain unchanged. Under the isolated-new-test exemption,
there are no gated entries to accept here. Independent verification must still inspect these
expectations and reproduce the repaired failures on the declared baseline.

Owner wording: "Work autonomously; don't ask me for verdicts on technical work." The technical
scenarios R1–R8, the UTF-16 boundary design, token-ID oracles and validation choices are agent
choices under the orchestrator's delegation, not owner-adopted scenarios. This builder does not
claim acceptance or independent verification.

- `OnnxEmbeddingEncoderSpanValidationTest`: R2/R3. Empty half-open intervals select no token;
  malformed, unordered, out-of-bounds and surrogate-splitting rows fail explicitly before
  tokenization/inference. The existing `poolSpan` intersection contract and Java substring
  coordinates are the established interface; the direct exception policy is the delegated choice.
- `EmbeddingServiceSpanTest`: R2/R4/R5/R8. Validate original bounds before prefixing, shift valid
  UTF-16 bounds once without mutation, validate the joined input too, preserve valid empty arrays,
  optional-null behavior and inference-error propagation. Under the resumed delegated M1–M4
  decision, requested coordinates require well-formed original and effective text. Malformed
  original content is refused even when concatenation would form a valid surrogate pair; the
  direct encoder validates only the effective text supplied to it. Empty outer arrays preserve
  the existing VECTOR-only input policy. These are delegated API choices, not owner scenarios.
- `OnnxEmbeddingEncoderUnicodeSpanTest`: R1/R3/R4/R7/R8. Independently identify blue/green token
  IDs from the named fixture vocabularies; read raw ONNX hidden states directly and compare their
  normalized mean at the existing 1e-4 tolerance. Cover supplementary characters, combining and
  compatibility-normalization inputs, partial tokens, repeated words, overlapping/repeated rows,
  actual service prefixes, empty/whitespace substring fallback and a whitespace-heavy control.
  ASCII/BMP controls (including literal U+FFFD) and document bit identity preserve established
  behavior. High/low surrogate and malformed-prefix controls enforce the explicit resumed
  M1–M4 rejection policy for requested coordinates; VECTOR-only controls compare unmodified
  baseline results with candidate results and emit SHA-256 digests of complete float bit patterns.
  No replacement normalization or sanitization policy is inferred from native IDs. The original
  failed malformed-GTE oracle and source remain retained in scratch as superseded evidence,
  rather than being claimed to pass.

Baseline: `f8e16e326bf30acc885eebcca0064c0e0d91aaff`. Check bytes are overlaid on an owned shared
clone; production sources remain the baseline there. No known-good product revision is established.
Initial builder sources, the original malformed-GTE failure, raw XML/logs and pure probes remain
in `tmp/span-builder-1/`. Final policy checks, baseline/candidate XML, guards, commands, complete
vector-digest comparisons and handoff report are in `tmp/span-builder-2/`. Both are retained for
the independent verifier. This changeset is intent/provenance, not generated execution evidence.

```json
{ "entries": [] }
```

## Acceptance records
