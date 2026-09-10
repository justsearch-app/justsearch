# Response to independent review 1 (2026-09-10)

The review is `review-1-findings.md` (copied from `I:\Downloads\inference-host-review-findings.md`).
Every finding was accepted. This ledger records, per finding, what v2 changed and where; a second
reviewer can check each row against `design.md` v2 rather than re-deriving it.

## Evidence-bundle corrections

| issue raised | action |
|---|---|
| Twenty named source files absent (`IndexFingerprint`, `ReadPathOps`, `RuntimeActivationService`, `ManagedChildReconciler`, `SpladeEncoder`, `SessionOptionsApplier`, `backend.py`, `lib.rs` and others) | 82 further files copied verbatim into `context/src/` (118 total), including every file the audits and v2 cite plus the Rust supervisor and updater, the dev MCP server, the jseval backend and register, and the governance registers |
| Audit 2 cited `ModelSessionPolicy.java:215-218`, `:237-238`, `:164-165`, `:258-259` in a 144-line file | Corrected in place in `context/audits/audit-2-session-gpu-lifecycle.md` with a dated note; the records are `Gpu :111-112`, `Cpu :121`, `Lifecycle :132`, `RunOptions :143`, `forFallback :83-98`. Audit 1's `:111-112` was already right |
| "Six `NativeSessionHandle` instances" overstated | Corrected in `context/theorisation.md` 1.3 and in v2 section 5.2: five handles in the SPLADE configuration (four GPU-capable), four with BGE-M3 (three GPU-capable) |
| Docs revision | The lane-F docs in `context/design/` and `context/evidence/` are re-synced at `1465acb7a` (the 2026-09-10 D1 re-grounding and C2 store design). Code is unchanged since `4229f1091` (`git diff --stat 4229f1091..1465acb7a -- modules scripts governance` is empty), so every code `file:line` remains at `4229f1091` |

## Findings

| # | sev | finding (short) | v2 answer | where |
|---|---|---|---|---|
| 1 | High | role-shaped requests cannot address two encoder generations; result-only binding does not protect writes | `EncoderSetHandle` on every request, resolved by the Engine from D1-12's serving or candidate set; replay resolves its fixed generation's set; write boundary and replay validate representation identity and destination generation; read binding compares against the pinned searcher's identity; `reload` is per set | 4.1, 4.2, 6.2, 6.3 |
| 2 | High | one SHA is not the compatibility predicate; BGE-M3 digest missing; fallback changes the realised file | `RepresentationIdentity` (versioned; digests, dimension, sparse vocabulary id, label set, rendering version) separated from `ExecutionIdentity` (realised file, precision, EP); dimension and dense/sparse validated per generation; declared compatible execution variants keep CPU fallback legal; the BGE-M3 digest migration is D1's declared gap and a stated dependency; query cache keyed by representation identity plus text | 4.3, 6.2, 13 |
| 3 | High | claiming after activation cannot prevent activation-time overcommit | reserve-before-launch tickets for every GPU-capable llama activation including self-test, retries and rung relaunches; evict or refuse before spawn; reconcile on `/health`; release a failed launch only after confirmation; tickets reconciled from the child registry after restart; single generative tenancy as an explicit rule independent of bytes | 5.4, 11 (H2 reserve-before-launch row) |
| 4 | High | three snapshots and one warm-up do not give a lifetime footprint; key omits ORT/device/allocator; cap as estimate blocks small models | ledger of reservations; footprint = weights + enforced arena cap + workspace; observations are advisory and become bounds only by re-applying `gpu_mem_limit`; profile key gains ORT version, device id, allocator policy and is invalidated on change; conservative unknown path; per-seat growth reservations so `K` participates; the design claims a conservative reservation discipline, not an upper bound | 5.3, 9 (loss row), 10.5 |
| 5 | High | a shared host cannot be an ordinary terminally owned child of each Engine | two ownership models: unshared hosts are ordinary owned children; shared hosts have one destructive owner (the device supervisor), Engines hold attachments in the private projection which the terminal path ignores; config mismatch refuses rather than retires; exclusive ownership keyed by (user, device) | 7.2, 8, 11 (shared-ownership row) |
| 6 | High | per-lane aging is not a device-level service bound | grants atomic at device scope; per-sub-batch seating; foreground precedence and one passed set host-wide with aging suspension; round-robin fairness among equals across lanes and attachments; deterministic `K = 1` and `K = 2` traces with three runnable lanes | 5.2, 11 (scheduler-traces row) |
| 7 | High | producer cardinality and `K` do not bound admitted or retained work | tokens per `(attachment, role)` bound to the attachment epoch; one outstanding submission per producer; host-wide caps on queued requests, bytes, attachments and CPU concurrency with reason and retry-after; epoch fencing on reconnect; running-work capacity released only at execution complete | 4.4, 7.3, 11 (epochs row) |
| 8 | High | HTTP completion and cancellation do not preserve C1's actual-exit lifetime | three completion states (transport detached, cancel accepted, execution complete); Engine retains remote work until execution complete or confirmed host death; linearizable enqueue and cancel per `(attachment, workId)` with cancel-before-enqueue as a fence; tests named | 4.4, 11 (cancellation row) |
| 9 | Medium | the admission formula double-counts resident allocations | ledger `total - external - Σ resident - Σ reserved - margin`, each counted once; `free` used only as a cross-check; one critical section; two competing admissions against one reading is a required test | 5.3, 11 (ledger row) |
| 10 | Medium | largest-first eviction can sacrifice foreground reranking | eviction by serving obligation first (idle background-only, then idle foreground-capable, then leased sessions drained via D1-13), size second; foreground eviction is a stated degradation gated by a rerank-coverage row; today's four-site release behaviour neither preserved nor assumed | 5.4, 11 (rerank-coverage row) |
| 11 | Medium | idle exit not synchronized with adoption; attachment lifetime undefined | attachments are leases with heartbeats and recorded process identity; explicit detach; crash expiry; idle exit is a `SERVING -> DRAINING` transition under the attachment lock; attach in `DRAINING` is refused; adoption never completes against a draining host; `--hold` for campaigns | 7.3, 11 (adoption-and-draining row) |
| 12 | Medium | capabilities do not replace the tokenizer-only window count | `embed(WINDOWS(0,0))` is tokenizer-only, returns the per-text count, takes no device seat; capabilities keep only model properties | 4.2, 4.5 |
| 13 | Medium | port-first cost advantage and D1 dependency not established | cost claim withdrawn; port-first kept as a risk-reduction order; the honest comparison (port plus adapter versus port plus client) stated; D1-12/13/14 are the substrate H1 relocates; H1 lands after D1 batch 4; the cross-lane ask is the minimum contract only | 2, 12, 13 |
| 14 | Medium | the bit-identical oracle contradicts the same-build baseline | exactness layered: contract exactness on captured arrays through both placements; numeric criterion under pinned execution shape; workflow fixture under existing classes and noise mask; MMR, `K` and eviction measured as separate policy rows | 11 |
| 15 | Medium | H0 and the gate need instruments and isolation the sequence did not name | per-run data dirs, manifests and ports for jseval; identity-scoped cleanup in `cmdStop` and `fixture-pair.sh`; read-only shared models; encoders-off coexistence profile until the host exists; per-call query-embed timing; per-set ready timestamps; host counted once in the machine sum; process attribution | 8, 11, 12 (H0 row) |

## Verified-sound items carried into v2 unchanged

The operation boundary as the seam; the per-role semaphore correction (with the cardinality
fix); no aggregate device budget today; release-after-confirmed-stop; native retirement
assigned to D1-13; single-Engine warm adoption within lane F's handoff; a shared token within
ADR-0046's boundary; the H0 cache hazards; the unproved motivation claims kept unproved (the
H2 host-crash row is a process-loss test, not a CUDA-fault reproduction).

## Recommended cross-lane decision (adopted)

Lane F D1 proceeds with its encoder-set ownership, per-runtime identity, native retirement and
device-line work. The operation contract and the set handle are agreed early; the port-only
adapter is introduced after D1 batch 4, never as its prerequisite. Before H2: passing contract
tests for reserve-before-launch, A/B read-and-write binding, atomic device grants, attachment-
scoped cancellation, and one destructive owner across Engine restart, quit and upgrade.
