# Remaining Lane F design resolution — Sol continuation

Scope: settle decisions solvable against current code before handing implementation
to Sol. Inspected documentation HEAD `09fdbe769`; runtime code remains `22800c842`.
No Java/proto/runtime implementation, build, dev stack or model query ran in this
design pass. C2 remains accepted; D1/D2/E/F remain open. Nothing below waives their
acceptance. The [continuation brief](../continuation-brief.md) is the task entry point.

## Selected decisions

| Scope | Decision and owning record | First dependent work |
| --- | --- | --- |
| D1-4 and teardown | [Publication/lifetime](D1/publication-and-lifetime-design-2026-09-23.md): shared short capture/publication lock, actual resource leases, all ordinary fallible preparation before commitment, runner-owned cancellation/receipt, monotonic shutdown admission and dependency-aware closure | After candidate-context verification, connected reconfigure path |
| D1-8–14 | [Generation/native](D1/generation-native-cursor-design-2026-09-23.md): prepare before strict promotion, final mutation routing/replay fence, forward recovery after commit, gap-approval binding, safe abandonment, exact native-instance leases and serialized CPU replacement | Live activation and model-bound generations |
| Installer generation, 2026-09-23 amendment | [Generation candidate](D1/generation-native-cursor-design-2026-09-23.md#7-installer-produced-generation-candidate-2026-09-23-amendment): download then distinct approved durable REINDEX activation; frozen settings/model target; pointer-committed settings roll-forward under the existing composite owner | Integrate with Flow A and D1-12, then replace direct ONNX settings producer |
| D2-6 | Same generation record: cursor pages retain the exact reader/runtime/encoder; fixed maximum lifetime, active-page retention, capacity released only after actual close/deletion | Cursor producer implementation |
| D2 composition | [Composition/request design](D1/d2-composition-and-request-design-2026-09-23.md): real facade extracted from production wiring; explicit owned/borrowed resources, one ephemeral workspace, no embedded exit authority | Profiles/library batch |
| D2 gate and durability | Same D2 record: per-handle FIFO/aging with explicit context propagation, producer token per stage/set; KnowledgeClient write port and writer-epoch-bound covering commit | Session gate and durable write batches |
| E/F | Existing runbooks remain binding. E values derive from the paired reference before candidate results; tuning and platform claims need measurements. F reconciles shipped canonical prose and publishes only after acceptance | Gate run and final publication |

The important amendments are deliberate: do not construct services after durable
generation promotion and then try to roll back its pointer; do not evict capacity
merely by dropping a reference; do not expose the mock harness as a real library;
do not restore the retired size ratchet. A retained old cursor may prevent the next
rebuild under the unchanged two-generation cap. Five minutes is a maximum cursor
lifetime, not an indefinitely refreshable idle timeout. CPU replacement waits for
its exact old instance to close. An unquiesced native process uses controlled hard
termination because ordinary JVM exit can invoke ORT's shutdown hook during a call;
embedded composition instead returns close refusal to its owner.

## Evidence and review limits

Two bounded read-only source investigations checked generation/native constraints
and D2 owners; they did not run builds or native experiments. Root rejected the D2
claim that “encoders and generative absent” contradicted inference=none: it already
means both are absent. Their concrete facade/ratchet/port/epoch/lease concerns are
addressed in the selected records. Independent refutation of the selected records
then identified these corrections/dispositions:

- Generation STATE_CONTROL must precede publication; the prepared strict promotion
  seam now records that order. No unbound promotion fallback.
- A proposed extra durable “view installed” receipt was rejected: boot always
  reconstructs from the committed pointer plus existing strict C2 binding, with
  readiness gated until construction and exact successor reconciliation. Live
  postcommit failure closes affected captures/routing and recovers forward.
- The review's current mixed service fields, stateless cursors and System::exit
  injection are real implementation gaps already targeted by the design. Added
  explicit CPU deadline/cancellation input and typed shutdown disposition/strategies.
- D2 now explicitly replaces EngineRoot's implicit resource initializer with injected
  ownership, names its concrete compile dependency and the background scheduler-to-
  encoder context/token propagation chain, and names the in-process ingest binder.
- Durable coverage now has a RuntimeSession mutation/commit barrier: concurrent
  mutations hold read; commit exclusively drains, captures and commits their watermark.
  Waiter publication uses a separate short monitor. A controlled specification
  example demonstrates why sampling a later completed watermark is unsafe.

The reviews ran no production tests; implementation negatives remain required.
The bounded D2 correction reread found no remaining concrete contradiction and
returned GO for implementation planning. This is a design verdict, not acceptance
of an implemented facade, fair gate or durable write path.
The generation/publication correction reread also found the lock order, pointer-
based recovery without a second receipt, bounded CPU acquisition, exit-strategy
selection and two-slot cursor choice coherent at design level. Its conditions
(strict binding, fail-closed recovery and actual lifetime proof) remain explicit
implementation acceptance, with no new native or crash-test result claimed.

[Specification script](D1/publication-model-2026-09-23.py) and its
[output](D1/publication-model-2026-09-23.json) enumerate six ordered read/write
interleavings: independent fields admit mixed pairs; an abstract atomic capture
does not. The additional lifetime/closing/commit examples assert the proposed
rules, including a later-write false-ack counterexample and writer-epoch mismatch.
They are deliberately small specification illustrations, not tests of the
production lock, Java memory model, fairness or ORT. Actual composed-reader and
native fault tests remain mandatory.

## Implementation proof still required

| Boundary | Required evidence; existing acceptance remains cumulative |
| --- | --- |
| Candidate-context slice | Corrected full integrated suite, installed standard-profile real-model query and hosted checks; focused2464 is not a substitute |
| Publication | Real paired-reader pause tests, in-place held leases, second-component failure, ambiguous file replacement, cancellation/commit arbitration, throwing observers and outcome-write recovery |
| Shutdown | Old freeze release cannot reopen; durable body ignores interrupt; recovery cannot self-join; finally cannot bypass close refusal; isolated native-unquiescent child exit selection |
| Generation | Accepted edits/deletes during final replay, changed approval gap set, abandon preserves accepted mutations/C2 evidence/ACK, all durable promotion crash cuts, actual old reader deletion and next-rebuild cap refusal |
| Native and cursor | Held CPU/GPU lease, failed CPU recreation/retirement race, native stress, cursor page versus expiry/eviction, differing models across cutover, Windows handle proof |
| D2 | Real profile/library consumers and cleanup, full-wait fairness negative controls, producer ownership, exact writer commit coverage, installed durable write/delete restart and generic MCP recovery |
| E/F | Paired workload/quality/latency/memory/soak/floor proof, existing publication and hosted/platform checks, canonical sweep and stage-F merge conditions |

## Successor operating boundary

Sol resumes the whole remaining lane in the retained worktree. First close the
candidate-context proof at runtime22800, then implement the selected connected
publication path and continue D1/D2/E/F. Resolve ordinary choices autonomously.
There is no pending initial Astra design assignment and no standing Astra reviewer.
Reopen a decision on a concrete contradiction, changed requirement or experimental
counterexample, naming the smallest blocked choice. A difficult test or concurrency
file alone is not an escalation trigger. This guidance lives in the task brief;
no global model configuration, role pins or enforced dispatch budget were changed.

## Documentation verification and closeout

On the documentation working tree based on09fdbe769, 2026-09-23:

- `node scripts/docs/llmstxt-generate.mjs --check`: PASS,116 indexed docs.
- `node scripts/docs/skills-sync.mjs --check`: PASS,5 generated skills/9 sources.
- `node scripts/docs/verify-canonical-doc-links.mjs`: PASS,157 files.
- `node scripts/architecture/module-deps.mjs --check-canonical`: PASS.
- `node scripts/docs/verify-runtime-config-matrix.mjs`: PASS,102 YAML keys/236 pairs/289 rows.
- `node scripts/docs/prompt-surface-inventory.mjs`:138 surfaces, zero suspicious tokens;
  this inventory is not proof of agent quality or model routing enforcement.
- The specification output reproduces from its checked-in Python script; relative
  file targets resolve in the nine selected/current design documents. An initial
  ad-hoc comparison used a misspelled Python encoding alias; the corrected comparison
  passed. `git diff --check` reports no whitespace defects.
- Both inference-runtime skill projections carry the same explicitly unimplemented
  coordination note. Regeneration changed no unrelated tracked content.
- Closeout sweep reaped nothing, retained two stale ui-shot records whose PIDs are
  absent, and reported the persistent OTLP singleton. No process was force-killed.
  The retained lane worktree remains HELD for continuation; main was not edited.

These checks cover documentation and the small specification illustration only.
They provide no new runtime/integrated/installed/hosted acceptance evidence.
