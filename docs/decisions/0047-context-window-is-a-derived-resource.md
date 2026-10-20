---
title: "ADR-0047: Context window is a derived resource"
type: decision
status: stable
description: "The LLM context window is derived at activation from a ladder of explicit rungs (32k on GPU, 8k on CPU, stepped down on a launch abort) rather than configured as a user preference, and every prompt-side budget is a fraction of the live window rather than a constant."
date: 2026-09-02
probes:
  - adr-0047-fit-off-explicit
  - adr-0047-no-context-size-promotion
  - adr-0047-ladder-policy-test
  - adr-0047-budgets-are-window-fractions
  - adr-0047-no-window-blind-threshold
last_reviewed: 2026-09-02
---

# ADR-0047: Context window is a derived resource

## Status

Accepted (2026-09-02). Amended by
[ADR-0050](0050-gpu-co-residence-by-prevention.md): the per-card GPU profile may lower the
ladder's top rung.

Implemented across tempdoc 883 PR 1 (the window itself) and PR 2 (the budgets derived from it).
This record is written after the fact but before the lane closes, because the live measurements
that decided the numbers (below) only exist once.

## Context

The packaged chat model trains at 262,144 tokens. The app ran it at **4096**.

That number was not a decision. `UiSettings.contextLength` shipped as `4096`, and `modules/ui-web`
never had a control for it — so it was a "user preference" no user could express. Worse, the value
was copied into a JVM system property on every settings save and every AI install, which the
configuration resolver reads at its highest ordinal (`jvm_arg`, 500). A GUI default therefore
outranked the operator's own `-D` flag and the packaged YAML, and
`/api/debug/effective-config` reported it as an operator override. A second default of 8192 existed
in the resolver and had been unreachable since it was written (tempdoc 208, 2026-02-16) — it was
never propagated to `UiSettings`, so the raise it argued for never shipped.

Downstream, a dozen constants were sized against that frozen 4096 by eye: a 5000-token hierarchical
threshold, an 1800-token section target, a 1000-token conversation-history cap documented as "~25%
of a conservative 8K window", a 3000-char agent read page "well inside n_ctx 4096". Two of them were
`static final` resolved at class-initialisation, so no runtime window change could ever reach them.
The RAG default shape asked for roughly 5000 tokens against an honest input budget of ~2300, and
tempdoc 845's trimmer silently dropped a suffix of passages on essentially every ask.

Three separate problems, one root: **the window was treated as a setting instead of as a resource
the runtime has to fit.**

## Decision

**The context window is derived at activation from the backend, and every prompt-side budget is a
fraction of the window the server actually reports.**

### 1. A ladder of explicit rungs, not arithmetic

`ContextWindowPolicy` (`modules/app-inference`) produces a descending ladder — `32768, 16384, 8192,
4096` — truncated to the backend's top rung: **32768 with GPU layers, 8192 at `-ngl 0`** (CPU
prefill at 32k is minutes per RAG ask). The Head contributes the top rung at the existing
`ORDINAL_AUTO_DETECT` (150, source `auto_detected` / `hardware_probe`) *after* GPU detection, so
`/api/debug/effective-config` explains the window with the mechanism that already explains GPU
detection — no new provenance vocabulary.

If llama-server refuses a rung it exits immediately; `LlamaServerOps` steps down one rung and
relaunches on `PROCESS_EXITED` (the same seam `relaunchWithoutReasoningBudget` already used). A rung
that does not fit costs context, not inference.

**No VRAM arithmetic and no GGUF reader.** The packaged model is a Gated-Delta-Net hybrid: 8 of 32
layers carry KV, plus ~50 MiB per slot of recurrent state independent of `n_ctx`. Any
dense-attention formula is ~4x wrong, and `/props` on the bundled build does not expose
`n_ctx_train`. Free VRAM is recorded for diagnosis and is never an input to the choice.

### 2. The 32k top rung is a budget, not a fit

This is the part that must not be misread, because the obvious reading is measurably false. On the
dev card (RTX 4070, 12,281 MiB) the model's **entire 262,144-token training context loads**: 33/33
layers, KV 4352 MiB, 10,321 MiB total. 32768 is not what fits. It is what this app chooses to spend,
for three reasons:

- **(a) Latency.** Prefill scales with the prompt and the budget fractions below fill whatever
  window exists, so the rung is what bounds worst-case ask latency.
- **(b) Co-residency.** KV is reserved up front for the whole `n_ctx` whether used or not, and the
  same card holds the embedding / SPLADE / NER encoders, the reranker and VDU batches. 544 MiB at
  32k versus ~2.2 GB at 128k is headroom kept on purpose.
- **(c) The ladder's job is stepping DOWN on small cards, not maximizing on large ones.**

Consequently the recorded reason for an unstepped launch is `top-rung`, never `fit`: a wire value
called `fit` would assert exactly what the measurement disproves. llama-server's
`n_ctx_seq (32768) < n_ctx_train (262144) -- the full capacity of the model will not be utilized` is
expected output, not a defect.

### 3. `-fit off`, and the rest of the launch line

`-np 2 -kvu -ctk q8_0 -ctv q8_0 -fa on -fit off`, with keys `justsearch.llm.slots` (default 2) and
`justsearch.llm.kv_type` (default `q8_0`).

- **`-fit off` is what makes the ladder mean anything.** The bundled build defaults `--fit on`
  ("adjust unset arguments to fit in device memory") and it *maximizes* rather than fits — with `-c`
  omitted it chose 242,944 tokens and 4 GB of KV. The step-down reads a hard abort as its signal; a
  memory-adjusting pass running beside it could absorb that signal instead.
- **`-kvu` is mandatory next to an explicit `-np`.** llama-server enables `kv_unified` only when the
  slot count is automatic, so `-c 32768 -np 2` alone gives `n_ctx_seq` 16384 while `/props` still
  reports `n_ctx` 32768. The argv order is asserted as an exact list, not by `contains`.
- **Two slots is a scheduling choice, not a memory one** — a background delegate must not evict the
  foreground turn's prompt-cache prefix (tempdoc 841).
- **`-fa on` is explicit** because a quantized V-cache aborts the launch without flash attention.

### 4. `contextLength: 0` means auto; an override is honoured or fails loud

`UiSettings.contextLength` `0` is the shipped default (settings schema bumped 1 → 2, migrating the
old 4096). A positive value is a real override contributed at ordinal 300 (`settings.json`), and
`JUSTSEARCH_CONTEXT_SIZE` / `-Djustsearch.context.size` still outrank it at 400 / 500. An explicit
value produces a **one-rung ladder**: it is honoured, or the launch fails with an ERROR naming the
override and the remedy. Silently serving a smaller window than an operator asked for would be this
lane's own precedence lie in a new place.

The settings→sysprop promotion and the `justsearch.context.size.source` marker are deleted, and
`/api/debug/effective-config` is sourced from the resolver's own provenance instead.

### 5. Intent and observation are different fields

`/api/inference/status.contextWindow` and the runtime manifest's `ai.contextWindow`
(`{rung, reason, freeVramBytes, slots, kvType}`, reason ∈ `top-rung` / `override` /
`stepped-from:<n>`) are the **intent**. `/props` `n_ctx` (published as `llmContextTokens`) and
`n_ctx_seq` in the llama-server log are the **observation**, and stay authoritative. Neither field
outlives the server it describes: `contextWindow` is absent before the first launch and again after
the engine stops.

`/props.n_ctx` reports the server's *total* context even when `kv_unified` is off, so a matching
`n_ctx` is not by itself evidence that a request gets the full window. The guarantee is the argv
plus `n_ctx_seq`.

### 6. Every prompt-side budget is a fraction of the live window

One immutable `ContextBudget` (`modules/core`) is built per request from the observed window and
that request's completion reserve, and threaded into the hierarchical runner, the injectors and the
agent tools. Every derived quantity is `min(fraction × inputBudget, cap)`, with the cap's reason
stated at the site:

| Quantity | Derivation | Why the cap |
|---|---|---|
| hierarchical threshold | `inputBudget` | no cap — it *is* the budget |
| section target | `min(inputBudget / 2, 4096)` | map-step latency: a section is one blocking call |
| external-context (history) | `min(inputBudget / 4, 2048)` | prior turns are low value per token |
| agent read-document page | `min(inputBudget / 2, 4096)` | a 12k page at 32k defeats the compressor |
| tool-result cap | `min(inputBudget / 4, 2048)` | one tool result must not own the prompt |
| agent completion reserve | `min(configured cap, window / 4)` | a reserve is not linear in the window |

`ContextBudget` is the **one** authority for prompt-side budgets. Anything that needs "how much room
does this turn have" asks it rather than walking the window itself.

## Consequences

- **`/api/debug/effective-config` stops lying about this key.** The window reports `auto_detected` at
  ordinal 150 on a fresh install and `settings.json` at 300 when a user sets one — measured live,
  never `jvm_arg`.
- **A deliberate user 4096 is discarded** by the schema 1 → 2 migration. There is no way to tell it
  from the shipped default and there was never a control for it; the alternative is pinning every
  existing install to the smallest rung forever. Release-note line required.
- **Two config defaults flipped from positive to `0 = derive`** (`justsearch.agent.max_completion_tokens`,
  `justsearch.agent.max_tool_result_chars`). An operator relying on the shipped 1024 / 4000 now gets
  a window-derived number instead. A positive value is still an explicit ceiling; where the window
  cannot afford it, the reduction is reported at INFO rather than applied silently.
- **The read page shrinks at small windows.** At the 4096 fallback it drops from a flat 3000 chars to
  1704, and at a forced 2048 to 320. That is the derivation working — those pages never fit the
  layer-two cut at those windows, they were simply clipped instead — but it is a real behaviour
  change on the CPU path: a delegate run at 4096 pages a document in more, smaller reads.
- **`q8_0` costs nothing.** Measured 69.66 vs 69.54 tok/s against `f16` at the same rung (3 runs
  each, 200 generated tokens), while halving the KV cache. The design's revisit trigger ("if q8_0
  exceeds 10%, make f16 the default at 16k and below") does not fire.
- **Not every drop reaches the user.** Only the RAG trim is on the wire
  (`rag.meta.context_truncated`); the history drop and the selection cut are backend INFO logs.
  Putting them on the wire needs a contract surface and an FE consumer, and is open work.
- **One new module edge**, `modules/app-agent → modules/core`, so the agent tools can share the
  budget type rather than fork the formula. `core` is a leaf, so no cycle is possible.

## Alternatives considered

### Keep the window a user setting, and just raise the default
The status quo with a bigger number — the change tempdoc 208 argued for in February and that never
shipped. **Rejected.** It fixes the value and not the shape: the promotion still reports a GUI value
as `jvm_arg`, the constants downstream are still sized against whatever the new number is, and the
next model with a different training context re-opens the same argument. A number chosen once by a
human is exactly what proved unmaintainable.

### Compute the window from free VRAM
The intuitive derivation: read NVML free VRAM, subtract the weights, divide by bytes-per-token.
**Rejected on measurement.** The packaged model's KV footprint is not predictable from a
dense-attention formula (8 of 32 layers carry KV; the recurrent state does not scale with `n_ctx` at
all), and the bundled server does not expose `n_ctx_train`, so the clamp that would make the
arithmetic safe cannot be computed. A ladder of four explicit rungs plus a detectable abort needs no
model-specific knowledge and fails legibly. Free VRAM is still recorded — as a diagnostic, not an
input.

### Let `--fit` choose the window
llama-server ships a memory-fitting pass and it is on by default, so leaving `-c` unset looks like
the zero-code answer. **Rejected on measurement.** It maximizes rather than fits: with `-c` omitted
the 9B chose 242,944 tokens and 4 GB of KV, and with `-c 0` it dropped layers off the GPU instead of
reducing context. It also competes with the step-down for the same failure signal, which is why the
launch now passes `-fit off` rather than relying on the inference that `--fit` only touches unset
arguments.

### Scale every downstream constant by hand against the new window
Multiply the 5000/1800/1000/3000 literals by 8 and move on. **Rejected** — it re-creates the defect
one generation later, and it cannot work at all for the two constants resolved at class-init, which
no runtime window change reaches. The fractions are what make a window change propagate.

### Mirror the derived window into a system property so a config rebuild keeps it
The mechanism that already existed for GPU flags, and the reason a rebuild never dropped them.
**Rejected** — that mirror *is* the promotion pattern this ADR deletes; it resolves at 500 and
reports as `jvm_arg`. `ConfigStoreRebuilder.rememberAutoDetected` re-contributes the probe at its own
ordinal 150 instead.

## Reassess When

- **A model ships whose `n_ctx_train` is below 32768.** The ladder's top rung would then exceed the
  model's own context and the launch either aborts or is silently degraded by the server; the rung
  needs a `min(rung, n_ctx_train)` clause, which in turn needs a source for `n_ctx_train` that does
  not exist today.
- **A supported card cannot fit the top rung**, i.e. the step-down starts firing on the auto path in
  the field. The step-down is designed for exactly this, but if it becomes the common case then 32768
  is the wrong *default* rather than the wrong *ceiling*.
- **VRAM co-residency changes** — the reranker or the VDU batch budget moves, a second arbiter
  appears, or co-residency is actually measured under load. Reason (b) above is the whole
  justification for 32768 rather than 131072; a measurement could raise the rung, and lane F's memory
  plan is where window / `gpuLayers` / slots / KV type / encoder budgets should become one decision
  instead of several.
- **`/props` (or a successor) starts exposing `n_ctx_train`,** or a GGUF metadata reader lands for
  another reason — the first alternative above becomes cheap enough to revisit.
- **A prompt-side budget appears that is not a `ContextBudget` fraction.** That is the drift this ADR
  exists to prevent; the fix is to route it through the budget, not to add a second authority.

## Cross-references

- `docs/explanation/05-ai-architecture.md` §"The context window (`-c`) is derived, not configured"
  and §"Token budgets" — the explainer this ADR decides.
- `docs/reference/inference-runtime-register.md` entry **D-010** — the runtime decision record with
  the full measurement set (17.0 KiB/token at q8_0, the three standalone probes, the tok/s table).
- `docs/reference/configuration/environment-variables.md` — `JUSTSEARCH_CONTEXT_SIZE`,
  `JUSTSEARCH_LLM_SLOTS`, `JUSTSEARCH_LLM_KV_TYPE` and the two agent budget knobs.
- [ADR-0046](0046-local-api-trust-boundary.md) — the sibling record written in the same review wave;
  its frontmatter and `Reassess When` shape are the template followed here.

Working history, by number rather than by link (canonical docs do not reference tempdocs):
**883** is this lane's contract, its independent-review folds and its three live-verification
windows. **881** is where `n_ctx 4096` was observed live and correctly *not* blamed for the failure
it was chasing — which is what left the window under observation but un-re-decided. **845** is the
context trimmer whose firing on essentially every ask was the symptom that made the window's size
legible. **208** is the 2026-02 argument for 4096 → 8192 that was written into the resolver and
never into `UiSettings`, so it never shipped.
