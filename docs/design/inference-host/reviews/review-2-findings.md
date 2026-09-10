# Independent review, round 2: inference host design v2

**Disposition: keep the direction; do not treat the H1/H2 contracts as closed. Lane F D1 should proceed without an inference-port prerequisite.**

V2 substantially improves the first draft: it adds explicit set routing, representation/execution identities, reservation-before-launch, device-wide selection, distinct shared ownership, attachment leases, and actual-exit terminology. Those changes answer much of round one's architectural criticism. The remaining problems are mostly at transitions between those mechanisms, where an invariant stated in one section has no implementing rule—or conflicts with a rule—in another.

This review records **eight High and eight Medium findings**. These are contract counterexamples and evidence defects, not observed production failures. The round-one resolution ledger is assessed individually below. A named acceptance test is not a passing test.

## Scope and citation conventions

Read-only review of the supplied archive: the v2 target, all fifteen response-ledger rows, the round-one review, the four audits, the relevant lane-F design/checklists/evidence, and the source files needed to check the findings. No builds, repository tests, dev stack, GPU experiments, or live probes were run. The scheduling and memory examples are constructed traces, not executed tests.

Paths below are relative to the extracted bundle. `design.md` is the v2 target; `src/` means `context/src/`; `audits/` means `context/audits/`; `stages/` means `context/design/stages/`; `evidence/` means `context/evidence/`; `docs/` means `context/docs/`; **lane-F design** means `context/design/design.md`. Revisions `4229f1091` and `1465acb7a` are the bundle's provenance labels, not independently verified repository history.

There are 118 files under `context/src/`, but the assertion that all previously missing implementations are now supplied is still too strong: see finding 16. In particular, conclusions about the install self-test remain audit-supported rather than directly implementation-verified.

## Ranked findings

### 1. High — The execution-growth ticket omits transient workspace already acknowledged by the footprint model

**Section:** §§5.3, 10.5; `design.md:294-322,527-530`.

**Contradicting lines:** First-load demand includes `weights + arena cap + workspace` at `design.md:300-305`, but tickets are reconciled after allocation at `:294-298`, and execution growth is only `cap - observed steady arena` at `:320-322`. The design itself identifies workspace as outside the arena at `:271-275`. The supplied implementation separately sets the arena limit and cuDNN workspace option at `src/SessionOptionsApplier.java:95-107`; shrinkage is a run option at `:119-123`.

**Failure scenario:** After warm-up, temporary workspace is gone and the load ticket has been converted to observed residency. Suppose two roles each have a 1,024 MiB arena cap, negligible retained arena, and the design's 256 MiB workspace allowance. The ledger has 2,048 MiB available **after** its margin. At `K=2`, both 1,024 MiB execution-growth tickets fit. Two runs can then demand 2,560 MiB under the design's own chosen footprint model. No unexpectedly large workspace or inaccurate learned maximum is needed: the stated 256 MiB allowances were simply not reserved for execution.

The other implementation choice—keeping each full cap reserved after warm-up and then also charging cap-minus-steady at seating—double-counts that arena entitlement. V2 does not define which ticket owns the entitlement through these transitions.

**Smallest fix:** specify a conservation rule per allocation class. Retain a session's unmaterialized entitlement, or transfer the required arena **and workspace** entitlement into a run ticket; never reserve the same entitlement twice and never drop it merely because warm-up ended. At run completion, transfer retained growth into resident accounting before releasing the remainder. Seat grant and growth-ticket grant must succeed atomically, with a defined refusal/fallback path. This asks only that the ledger account for its declared model, not that 256 MiB become a proven native upper bound.

### 2. High — Reserve-before-launch has no crash-safe launch intent, and attachment expiry can invalidate a still-live allocation owner

**Section:** §§5.3–5.4, 7.3, 8; `design.md:294-298,326-342,455-460,495-496`.

**Contradicting lines:** The current child is started before its identity is persisted: `src/LlamaServerOps.java:1255-1275`. `ManagedChild.fromProcess` creates the child ID only after spawn (`src/ManagedChild.java:48-71`), and its record has no reservation-ticket linkage (`:14-23`). Registration rollback deliberately retains a surviving unregistered process (`src/LlamaServerOps.java:1300-1323,1336-1345`). Lane F requires unreconciled ownership to survive another startup crash, not disappear with the owner: lane-F design `:878-888,901-907`.

V2 instead scopes tickets to attachments, expires that epoch's tickets, and says recovery reads “the child registry.” Section 8 identifies the supervisor's registry even though the Engine remains llama's lifecycle/registration owner (`design.md:108-111,443-446,495-496`). No cross-registry association or pre-launch record is defined.

**Failure scenario:** The host grants ticket T; Engine A starts llama; A crashes between `pb.start()` and `register`. A host restart cannot reconstruct T's child from the registered-child list. Treating absence as death frees capacity while llama still allocates; retaining every unknown ticket forever can permanently block single-chat admission after a launch that never occurred. A related race needs no crash: A is paused after receiving T, its attachment expires, and A later resumes the authorized-looking spawn. Revoking an epoch is not proof that a previously authorized launch cannot still occur.

A successfully registered llama also survives `RESTART`/`HANG`; its memory cannot be released just because the predecessor attachment expires or is fenced.

**Smallest fix:** define a durable, preallocated launch intent/ticket ID and states such as reserved, launch-pending, bound-to-OS-identity, retiring, released. Persist the intent before authorizing spawn; link the resulting child record to it. Unknown launch outcome retains the reservation and blocks conflicting admission until reconciled, rather than becoming “dead.” Transfer a live child's reservation across attachment epochs. Specify the authoritative registry and the device supervisor's read/projection relationship to Engine-owned llama records. Retries and rung changes must settle the predecessor attempt before consuming a new launch authorization.

### 3. High — Three completion labels do not yet provide C1's per-call actual-exit protocol

**Section:** §§4.1, 4.4, 7.4, 11; `design.md:130-132,180-189,475-479,581`.

**Contradicting lines:** C1 expressly distinguishes a work ID from the lifetimes of individual calls and accepted children. A group releases its retained owner only after it is sealed and every accepted child actually exits; holding all interactive references until work completion was rejected because multiple sequential/concurrent calls can share a handle (`stages/C1.md:1376-1387`). `src/EngineFutures.java:95-109,112-129` implements the separation between result completion and actual exit. A native result also remains caller-owned after `run()` returns (`src/SessionHandle.java:191-207`).

V2 defines execution completion at `(attachment, workId)` scope, but does not define per-submission terminal records, sealing, retrieval after a lost response, or an acknowledgement that permits terminal-record retirement. `status()` only lists aggregate status/queues, not those records (`design.md:209-211`).

**Failure scenario:** Two encoder submissions S1 and S2 share one C1 work ID. S1 completes while S2 is still running; a work-ID-level completion cannot safely release both call lifetimes. Conversely, waiting for the work ID to be globally finished retains S1's per-call admission beyond the C1 contract. After a connection loss, the host can finish S1 without the Engine receiving anything. Releasing on timeout violates actual exit; retaining until host death leaks admission indefinitely on a healthy host.

Merely returning from the native `run()` is also too early when accepted host work still owns results, native leases, or postprocessing resources. Detachment, cancellation acceptance and execution termination are independent facts, not three mutually exclusive states in a required sequence; normal completion need not pass through either of the first two.

**Smallest fix:** use the already planned request UUID as a submission identity, scoped by host boot and attachment epoch. Register/retain a child lifetime before a send can become accepted or ambiguous. Give each submission an idempotently retrievable terminal state that means its accepted execution and native-resource ownership actually ended. Keep `cancel(workId)` as a group cancellation fence, not as a substitute for per-call completion. Specify how cancellation/status retrieves terminal outcomes after a lost acknowledgement and how acknowledgement retires them. Local task groups retain their existing seal/actual-exit rule. Add explicit request-level pre-admission refusal semantics rather than forcing capacity, epoch, or cancellation refusals into §4.4's “only unavailable or retired” whole-request failure rule.

### 4. High — Fencing an attachment does not prevent overlapping old/new-epoch execution

**Section:** §§4.4, 7.3, 11; `design.md:188-194,455-467,593`.

**Contradicting lines:** V2 promises that restart cannot leave two epochs' work live (`:192-194`) and explicitly gates “no work from two epochs runs concurrently” (`:593`). It also forbids preempting a running native call (`:188,260`). C1 requires that an issued lease retain its permit until actual exit (`stages/C1.md:1582-1589`; `src/NativeSessionHandle.java:298-324`).

`attach(clientIdentity, declaredConfigHash)` has no specified predecessor relationship or replacement barrier. Recording the new Engine's OS process identity distinguishes processes; it does not itself establish that the new attachment replaces a particular logical Engine's old epoch.

**Failure scenario:** Epoch e has a native call running. Its Engine dies or misses heartbeats. The host fences e and cancels queued work, but the native call continues. The successor attaches as e+1 and opens a new producer—the predecessor token must not block it according to §4.4. At `K=2`, or on a CPU lane, new work runs concurrently with e. All cancellation rules were obeyed; the epoch gate was not.

There is also a named renewal race: an expiry task reads a lease as due, a request renews it, and the expiry task later fences it using the stale observation. The stated attachment lock covers attach versus the final draining transition, not explicitly renewal, expiry validation, enqueue, and epoch replacement.

**Smallest fix:** identify a stable logical Engine plus its process incarnation and predecessor attachment. Under one attachment authority, linearize renewal, expiry/fencing, request admission, and replacement. A replacement may attach, but remains non-runnable until predecessor execution exits or host death is confirmed. Charge predecessor execution to host-wide bounds throughout that interval. Expiry callbacks must validate the observed epoch/renewal revision before expiring it. Live llama reservations follow finding 2, not the end of encoder execution.

### 5. High — A single destructive owner is named, but ownership recovery after that supervisor dies is not defined

**Section:** §§7.2–7.3, 8, 11; `design.md:443-467,491-496,591`.

**Contradicting lines:** Existing dev-runner admission combines an exclusive-create admission file, lease expiry, and owner-process liveness (`src/dev-runner.cjs:1703-1751`). Lane F's child contract separately requires persisted predecessor ownership and OS-identity checks before adoption or destruction (lane-F design `:878-907`). V2 gives the device supervisor a lease with today's semantics and says a second host/version is refused, but does not identify the lock holder or recovery authority that maintains that exclusion when the supervisor dies and the host survives.

**Failure scenario:** Supervisor S1 owns host H1, serving Engine B. S1 crashes; H1 remains alive. The supervisor lease expires and S2 starts, possibly from another host version. Reclaiming the dead supervisor is not permission to spawn H2 or kill H1. A lock held only by S1 has disappeared; a stale registration alone does not enforce exclusivity. Keeping S1's lease forever avoids split ownership but strands a healthy shared host without a recoverable owner.

The two-Engine quit test does not exercise this failure: A detaching while a live supervisor remains is the easier case.

**Smallest fix:** give the device slot a version-neutral exclusion mechanism whose lifetime covers the live host, not just the supervisor—e.g. a host-held per-user/device lock—plus one persisted supervisor ownership record with boot/process identity. Successor startup must recover that record and adopt the same host before admitting launches. Unknown identity fails closed. Define owner transfer, stale-owner fencing, and terminal stop ordering. Add supervisor death before/after host registration and cross-version successor cases; ordinary Engine quit must still remain detach-only.

### 6. High — Config-mismatch fallback can silently introduce an unbudgeted second GPU runtime

**Section:** §§2, 7.2, 8; `design.md:83-86,448-451,500-502`.

**Contradicting lines:** The host is described as the only owner that sees every allocation (`:83-86`), while an incompatible Engine may fall back to its in-process adapter (`:448-449`). That adapter is not specified as CPU-only. H1 expressly includes the GPU pipeline, scheduler, ledger, and llama admission (`:602`); existing sessions perform lazy GPU acquisition/retry (`src/NativeSessionHandle.java:211-247`). H0 correctly recognizes that independent GPU processes are not made safe by cache fixes (`design.md:500-502`).

**Failure scenario:** Shared host version A has resident encoders and a llama tenant. Engine B expects version B and receives `HOST_CONFIG_MISMATCH`, then starts its ordinary in-process GPU adapter. The second-host spawn guard does not run because no second child is spawned. B's private ledger can regard A's memory as external, but it neither participates in A's reservations nor obeys the same atomic single-chat rule. Two allocators can act against the same remaining headroom. The intended fault-domain separation is also lost for B.

**Smallest fix:** make this fallback explicitly **CPU-only**, with GPU providers and lazy GPU retries disabled, or report `encoders: UNAVAILABLE`. Full GPU fallback would require registration with the existing device authority and its admission protocol; it is not the minimal fix. Apply the same rule to `bench`/adapter compositions that can coexist with a shared host.

### 7. High — The representation identity still does not define the complete representation-producing input

**Section:** §§4.3, 6.2, 13; `design.md:156-169,393-409,614`.

**Contradicting lines:** V2 says its identity is D1-12's identity plus a BGE-M3 digest and sparse vocabulary ID (`:156-160`). D1-12's tuple is three SHAs, BGE-M3 selection and dimension (`stages/D1.md:509-511`). Actual embedding also depends on separately supplied prefixes, tokenizer and pooling: `src/EmbeddingService.java:125-131,238-249,403-406`; `src/OnnxEmbeddingEncoder.java:174-199`; `src/ModelManifest.java:32-46,78-85`. The persisted fingerprint currently has only the three model keys (`src/IndexFingerprint.java:118-125,297-300`).

**Failure scenario:** An ONNX file and output dimension stay unchanged, but a document prefix or pooling/tokenizer sidecar changes. Under the enumerated identity inputs, the new set and query-cache key can still match the old generation while producing a different representation. Calling a value a “representation digest” would solve this only if its canonical inputs actually include those dependencies; the current “D1 plus two fields” definition does not establish that.

Separately, declaring BGE-M3 binding `partial` is honest but does not resolve round one's same-dimension, different-model counterexample. The supplied fingerprint does not establish a stored sparse-vocabulary ID either. D1 explicitly allows the digest migration to remain a follow-up (`stages/D1.md:688-689`).

**Smallest fix:** define a canonical, versioned representation descriptor covering model compatibility family, tokenizer/vocabulary artifacts, resolved preprocessing/pooling/prefixes/window semantics, dimensions and relevant labels. Specify how compatible execution variants refer to that descriptor; `cpu`/`gpu` filenames alone are not a compatibility proof. Preserve unknown versus known-absent identity. For generations lacking required metadata, declare an explicit checked legacy policy or refuse the affected operation/change until migration; do not mark the full A/B binding gate green on `binding: partial`. This remains H1/follow-up work, not an implicit new D1 prerequisite.

### 8. High — Model/generation write binding does not protect the source revision, partial aggregates, or the actual replay acknowledgement boundary

**Section:** §6.2 and H1 binding gate; `design.md:388-406,579`.

**Contradicting lines:** Combined backfill selects content before encoding and later performs a map-based RMW write (`src/CombinedEnrichmentBackfillOps.java:409-444,969-987,1200-1211`). Resumable embeddings retain cross-cycle state through `BackfillScheduler` (`src/BackfillScheduler.java:121-127`) and call `progress.nextWindow(docId, content)` / `record(docId, content, ...)` before producing the final vector (`src/CombinedEnrichmentBackfillOps.java:854-899`).

D1's activation gap check compares indexed source hashes with current accepted hashes (`stages/D1.md:447-452`). Replay can directly change content and its revision (`src/KnowledgeServerMigrationOps.java:626-641,670-680`), whereas an UPSERT is **not** synchronously encoded: it is durably enqueued and the buffer can then be cleared (`:806-838`).

**Failure scenario:** Backfill encodes content H0 for set B and generation G. A source update/replay applies H1 to the same destination before the derived RMW executes. The old result carries the correct B identity and G destination, so the proposed two-field check admits it and marks the representation complete on the H1 document. D1's source-hash activation check can see H1 matching the accepted H1 while the vector still describes H0. V2 specifies neither a source-revision compare at that write nor serialization excluding this interleaving. The single index-time encoder producer is not, by itself, such a write-boundary contract. D1-9's initial embedding-drain gate is a relevant guard: this is not a claim that ordinary single-loop backfill races itself, or that the initial replay ordering has been observed to fail. To exclude the constructed interleaving, the contract must carry that serialization through every applicable derived write and replay-induced update, not infer it from producer cardinality.

A related migration hazard is a partial-window accumulator carried across a runtime/set transition: checking only the final envelope does not prove all contributing windows have the same binding. The accumulator implementation is not supplied, so this is a missing required invariant, not a claim about its unseen internals.

**Smallest fix:** make each derived-field write conditional on `(destination generation, representation identity, selected source revision/hash)` at the same boundary that applies the field and completion marker, or explicitly retain and prove a serialization rule that excludes source changes across selection–encoding–write. Bind partial accumulators to those identities and the window layout, or discard them at an incompatible transition. Preserve per-field identities in the combined RMW rather than stamping one current set over mixed outputs. Distinguish **source-command replay accepted durably** from **derived representation applied**: keep D1-9's source journal schema/replay semantics and enforce the encoding binding at the eventual target-generation job/write. “Before acknowledging application” must name which of these two acknowledgements it means.

### 9. Medium — The steady-state formula is corrected, but materializing reservations can still be counted twice

**Section:** §5.3 and ledger gate; `design.md:278-298,577`.

**Contradicting lines:** `resident` is attributed from before/after observations, `external` is the remainder of NVML used, but tickets are reconciled only after allocation completes (`:288-298`). The critical section names read/decide/write-ticket, not materialization, reconciliation, release, or sample epochs. The supplied NVML path provides aggregate device totals only (`src/NvmlService.java:177-190`), not a per-session weights/arena breakdown.

**Failure scenario, illustrative GiB:** T=12, resident=4, external=0, and a 3 GiB load ticket is outstanding. Available is 5. The load allocates 2 GiB before finishing. A second admission reads used=6, while resident is still 4 and the unreconciled ticket is still 3. It computes external=2 and available=3, refusing a 4 GiB demand that fits the original reservations. The 2 GiB is counted as both materialized “external” usage and part of the still-full ticket. The free-memory cross-check passes: `external + resident = used` by construction.

Concurrent native activity also makes an unqualified device delta unsuitable for identifying weights versus arena. Locking two ticket grants against one reading does not serialize those observations.

**Smallest fix:** define one atomic accounting transition for reservation-to-residency and include reconciliation/release in the ledger authority. State which observations share an epoch and how in-flight materialization is excluded from external usage or subtracted from remaining reservation. For observations that cannot be attributed, retain a conservative unknown amount and label it unknown; do not claim an exact role breakdown from aggregate NVML. Add a grant **during** another load, not just two grants before either load, to the ledger trace.

### 10. Medium — The passed-set rule is sound for one native wait, but its claimed unit is ambiguous after sub-batching

**Section:** §§4.4, 5.2, 11; `design.md:173-175,242-265,580`.

**Contradicting lines:** §4.4 calls a batch one scheduling unit, while §5.2 seats native sub-batches and releases eligibility between them. It then claims a foreground “call” is passed once over its whole wait. The inherited statement is a waiting-call/acquire bound, not an end-to-end arbitrary multi-run RPC guarantee (lane-F design `:672-686`).

**Concrete two-pass trace:** At `K=1`, foreground request F has two native sub-batches, F1 and F2. An aged B1 is granted while F waits; F is marked passed. F1 is then seated, so F is no longer waiting. During F1, B2 becomes aged. F2 re-enters the queue; B2 is granted, marking F again. The same outer request was passed twice, without violating a once-per-contiguous-native-wait interpretation. At `K=2`, keep the second seat occupied by another foreground lane and the same trace remains possible.

**What did not refute:** For a fixed foreground candidate continuously waiting, an atomic selector and a sticky passed mark do prevent a second aging exception before it is served. With persistent round-robin cursors over all eligible equals, finite queues and eventual native exits, I did not find an equal-class starvation trace.

The selection of *aged* background heads should nevertheless be explicit. Rule 4's background example is qualified by “when no foreground is waiting.” If aging exceptions use a separate fixed-order picker, a continuously aged B lane can win every exception while a second B lane never runs under continuous foreground load. If “same class” already includes all aged candidates, that counterexample is excluded; say so rather than relying on the example's interpretation.

**Smallest fix:** choose the bound's unit. Either retain passed history through the outer request and specify how non-runnable intervals affect aging, or promise once per native wait and state the resulting multi-sub-batch request bound. Apply the persistent lane/attachment fairness cursor to aged-exception selection too. Add a multi-sub-batch F trace and **two** aged background lanes under saturated foreground; the proposed one-background-lane trace cannot test that fairness issue. Finite admission does not turn a stuck native call into a finite latency bound.

### 11. Medium — Process configuration identity includes mutable sets, without a corresponding shared-set lifecycle contract

**Section:** §§4.1, 6.3, 7.1–7.2; `design.md:126-129,411-418,428-451`.

**Contradicting lines:** The host's declared process hash includes per-set representations and variants (`:428-429`), while sets can be added/reconfigured after launch and A/B may coexist. Lane F intentionally separates declared process configuration from mutable diagnostics/adoption evidence (lane-F design `:891-898`). D1-12 gives each set its own identity/handles and retirement contract (`stages/D1.md:509-528`), not authority to mutate another Engine's set reference.

**Failure scenario:** Host H launches with set A and a hash covering A. Reconfigure successfully loads B without restarting H. The Engine restarts with applied B. Comparing its B hash with the original child declaration A rejects the warm host; updating the host-global hash instead can reject another Engine still serving A even though A remains resident. A process-level config mismatch has been manufactured by an ordinary set operation.

Also, `reload(set, config)` returns an identity and mode, not a specified replacement handle or shared-set ownership result. Mutating the object behind the old handle violates its immutable-set promise; retiring a shared handle on one Engine's reload/close can invalidate another Engine's future calls despite keeping the host process alive.

**Smallest fix:** separate immutable host/protocol/device-policy compatibility from individual set specifications. Define set acquisition/release and ownership: either attachment-private sets, or shared immutable sets with attachment references. Reload creates/returns a new handle; an old set retires only after its references and native leases permit it. Process mismatch must not be caused by a compatible client requesting another permitted set. The process's one destructive owner remains unchanged.

### 12. Medium — Queue caps do not bound cancellation fences, terminal records, or response retention

**Section:** §§4.4, 7.3–7.4; `design.md:180-199,455-458,473-479`.

**Contradicting lines:** V2 adds a durable-in-the-epoch cancel-before-enqueue fence (`:185-187`) and promises host-wide bounds, but the enumerated caps cover queued requests/bytes, attachments, CPU concurrency, and outstanding submissions (`:195-198`). It does not specify the retention or bound of cancellation/terminal records, nor completed outputs awaiting transport. A 64 MiB per-frame ceiling (`:476-477`) bounds an individual response, not the number retained.

**Failure scenario:** One long-lived attachment repeatedly cancels new work IDs before enqueue. Every later arrival must still be refused, so deleting a fence arbitrarily violates the cancellation contract; retaining all of them grows memory with history rather than concurrent work. Queue-depth and CPU-concurrency caps remain zero throughout. Similarly, releasing execution admission when native work finishes does not itself bound already-produced frames retained by slow response writers.

**Smallest fix:** define bounded fence/terminal-record ownership alongside finding 3's completion acknowledgement. A per-attachment sequencing/watermark or bounded admission-backed record scheme can reject stale submissions without retaining every historical UUID. Reserve cancellation capacity for already admitted work. Add byte/count bounds for admitted input, retained results and outstanding delivery, with their own release events; do not extend GPU-seat lifetime just to wait for network delivery. Epoch expiry may retire metadata only once that epoch cannot submit again and its execution has settled.

### 13. Medium — The eviction predicate incorrectly uses a background-producer concept to identify query-serving roles

**Section:** §§4.1, 4.4, 5.4; `design.md:133,190-194,344-352`.

**Contradicting lines:** Producer tokens are explicitly for background producers (`:133`). Yet SPLADE/BGE-M3 are put in the first eviction category when “no query-time caller holds a producer” (`:345-346`). Query-time BGE-M3 and SPLADE are real: `src/SearchInputCapture.java:151-184,321-339`.

**Failure scenario:** A serving generation relies on BGE-M3 for both dense and sparse query representations. No query caller ever holds the specified background producer token, so the session is classified as background-only even while it is a serving query capability. The promised serving-obligation order therefore cannot be implemented with that predicate. Measuring reranker candidate coverage alone does not expose loss of those retrieval legs.

**Smallest fix:** classify by serving-set/role obligation, independently of whether a call happens to be active. Use native leases to distinguish busy from idle, and producer tokens only for producer admission. Include dense/sparse query-leg availability and degradation in the chat-coexistence eviction measurement. The new obligation-first ordering itself is an improvement over largest-first.

### 14. Medium — Layering fixes the baseline contradiction, but whole-frame byte equality still includes nondeterministic metadata

**Section:** §§4.2, 7.4, 11; `design.md:147-150,473-476,554-565`.

**Contradicting lines:** Every result includes queued/run timing (`:150`); frames include timings and identity metadata (`:474-475`). Contract exactness nevertheless requires byte-identical frames through the two placements (`:558-559`). The supplied baseline explicitly says equality was achieved partly by withdrawing noisy fields, leaving five query hit lists outside the verdict (`evidence/README.md:35-58`).

**Failure scenario:** Replay the same captured numeric arrays through both placements, but run through the real scheduling/transport wrappers. Different queue/run timings produce different headers and therefore different frames. Reusing the workflow noise mask to hide such differences would conflate transport exactness with workflow noise and could hide an actual serialization error.

**Smallest fix:** define the exact comparison boundary: identical captured result payload plus fixed/canonical contract metadata must serialize and round-trip exactly; placement-dependent timing/transport IDs are excluded or supplied identically by the test fixture. Keep identities, shapes, outcomes, item order, numeric bytes and integer IDs inside that exact comparison. Specify each role's numeric/discrete/tie acceptance rule before capture, rather than deriving an epsilon from a failed cross-side diff. Keep the workflow mask's existing limited claim explicit: masked hit lists remain unverified, not proven equivalent. No return to blanket real-inference byte equality is warranted.

### 15. Medium — The dependency arrow is corrected, but “written against section 4” is not a minimal request to D1

**Section:** §§0, 6.3, 12–13; `design.md:44-46,413-418,602-614`.

**Contradicting lines:** D1-12 is specified as a local `EncoderSet` and `Supplier<EncoderSet>` consumer migration (`stages/D1.md:509-522`); D1-13 defines local lease retirement (`:536-553`); D1-14 uses a free/total supplier and cap-based decision, explicitly without a new port type (`:557-569`). V2 says D1 proceeds unchanged, but asks for section 4's operation shapes “so that D1's mechanisms are written against them” (`design.md:610-612`). Section 4 includes host-issued handles, attachment context, per-item outcomes and remote completion semantics, none of which is necessary to finish those D1 mechanisms.

V2 also says D1-14's contract is unchanged while replacing a read-only free-byte decision with an atomic, potentially evicting reservation transaction (`:413-418`). Its externally visible beside/in-place result can stay unchanged; its admission policy is not merely receiving a different measurement.

**Failure scenario:** D1 implementation waits for `core.inference` request/result types or rewrites consumers around attachment-aware operations before H1 exists. The dependency cycle returns despite the “after batch 4” label. Alternatively, H1 preserves the old free-byte comparison and only swaps its supplier, failing to acquire the atomic ticket that the new policy requires.

**Smallest fix:** say explicitly that D1 requires **no new host-port types or source changes** for this lane. Agree only on preservation of set-local identity, runtime binding, resolvable serving/candidate ownership, and retirement semantics; H1 wraps those completed mechanisms. H1 owns the operation API and the replacement of D1-14's admission implementation, preserving its outcomes and rollback behavior. Keep the BGE-M3 migration as a named follow-up/feature gate, not work silently added to D1 batch 4. Coordinate D2's gate/profile adaptations when they land; do not make H0/H1 a prerequisite for D2 either.

### 16. Medium — The evidence-bundle repair still contains the wrong implementation and unresolved source paths

**Section:** evidence provenance; `README.md:8-18`, `review-1-response.md:7-14`, and the self-test/H0 claims in §§5.4, 8.

**Contradicting lines:** The supplied `src/RuntimeActivationService.java:1-14` declares an **app-api interface**, expressly stating that the concrete implementation has the same simple name in `modules/ui/.../ai/runtime/`. The entire supplied file is 35 lines. It is not the implementation from which audit 2's self-test memory observations or audit 3's self-test stop path were read. `design.md:498-500` also references `_paths.py` and `fixture-pair.sh`; neither is in the supplied source directory. `WindowedEmbedProgress`, needed to inspect the full partial-window state owner, is not supplied either.

**Failure scenario:** A reviewer considers every llama activation path directly checked because the basename exists, although the install self-test's launch/registration/stop implementation was never present. A flattened same-name interface has silently stood in for the concrete class. This matters directly to the requirement that **every** GPU-capable activation reserve before allocation.

**Smallest fix:** supply the concrete implementation with its repository-relative path preserved, and add the referenced fixture/path helpers and accumulator implementation. Generate an inventory mapping repository paths to bundled files, with line counts or hashes, rather than asserting completeness from basenames. Until then, keep those conclusions attributed to the audits or caller-visible APIs. The corrected `ModelSessionPolicy` ranges and the 118-file count were verified; this finding does not invalidate the directly checked source evidence elsewhere.

## Round-one response ledger: individual disposition

“Resolved” here means the v2 design addresses the original contract problem. It does not mean implemented, tested, or green.

| Round-one finding | Disposition | What v2 actually fixes; what remains open |
|---|---|---|
| **1 — set routing and write binding** | **Partial** | Every request now selects a set; serving/candidate/replay routing and pinned-searcher comparison are specified (`design.md:126-129,388-406`). That closes the role-global routing defect. Actual write protection still needs source-revision/aggregate binding and an acknowledgement boundary consistent with asynchronous source replay: finding 8. Shared reload/handle lifetime also needs finding 11. |
| **2 — compatibility and actual execution identity** | **Partial** | The representation/execution split, actual fallback identity and identity-keyed cache are appropriate (`:156-169,408-409`). Canonical representation inputs and a safe policy for missing BGE-M3/vocabulary metadata remain open: finding 7. Naming D1's deferred migration does not make the missing check available. |
| **3 — claim after llama activation** | **Partial** | The order is now correct: reserve, evict/drain if needed, spawn, reconcile; single-chat tenancy is independent of bytes (`:326-342`). Crash-safe pending launch, child-ticket association, late spawn, and ownership transfer of a live tenant remain open: finding 2. |
| **4 — learned footprint as a lifetime maximum** | **Partial** | V2 no longer treats a sampled maximum as a hard bound; it adds useful profile keys and tightens only through an enforced cap (`:307-318`). That is a real resolution of the epistemic overclaim. Its execution-growth accounting still drops workspace and lacks conservation across load/run/residency: finding 1. Aggregate observation attribution is finding 9. |
| **5 — every Engine terminally owns the shared host** | **Partial** | The direct A-quit-kills-B defect is removed by owned-child versus attachment records (`:439-451`). Supervisor-crash recovery, fallback bypass and mutable shared-set control remain unclosed: findings 5, 6 and 11. |
| **6 — per-lane aging presented as a device bound** | **Resolved for a single native wait; broader claim open** | One atomic device selector and one passed set eliminate the original independent-lane exception problem (`:238-250`). A continuously waiting marked candidate cannot be passed again under that rule. The new sub-batch interpretation and aged-candidate fairness need the precise statement and traces in finding 10. |
| **7 — producer count/K do not bound admitted work** | **Partial** | Explicit host-wide caps and epoch-scoped producer ownership improve admission (`:190-199`). Epoch replacement still needs an actual-exit barrier, and retained fences/results need their own bound: findings 4 and 12. |
| **8 — HTTP cancellation releases C1 lifetime early** | **Partial** | The three distinctions and “not on detach/cancel acknowledgement” rule are correct (`:180-189`). A per-submission completion/recovery protocol is still missing; work ID alone does not reproduce C1's per-call accounting: finding 3. |
| **9 — double-counted resident allocations** | **Partial** | The new algebra avoids subtracting resident bytes from already-free bytes in a consistent steady-state snapshot. It does not yet avoid counting a materializing ticket both in NVML used and in full reserved demand: finding 9. |
| **10 — largest-first sacrifices foreground reranking** | **Partial** | Serving-obligation-first ordering and a rerank-coverage row are improvements (`:344-352,578`). The background-producer predicate cannot classify query-serving SPLADE/BGE-M3, and those retrieval legs need coverage too: finding 13. |
| **11 — timer/adoption race and undefined attachments** | **Partial** | Leases, expiry and locked attach-versus-drain remove the original idle-timer race (`:455-467`). Renewal-versus-expiry and replacement while native work runs are not specified by that lock: finding 4. Supervisor recovery is finding 5. |
| **12 — missing tokenizer-only window count** | **Resolved** | `WINDOWS(0,0)` explicitly returns per-text window counts without a device seat (`:141,203-207`). The adapter must preserve document-prefix/window semantics, which existing `EmbeddingService` uses for counting and embedding alike (`src/EmbeddingService.java:530-554`). |
| **13 — unproved port-first cost and inverted dependency** | **Partial** | The cost claim is withdrawn and H1 is placed after D1 batch 4 (`:72-81,602`). The remaining requirement that D1 be “written against” section 4 is broader than necessary; finding 15 gives the minimal replacement. |
| **14 — byte-identical workflow despite same-build noise** | **Resolved in principle; exact-frame oracle needs correction** | Separating serialization, numeric behavior, workflow noise and policy changes addresses the original contradiction (`:554-565`). Whole-frame timing metadata still needs the explicit exact boundary in finding 14. The existing workflow mask's blind spots remain acknowledged limitations. |
| **15 — unnamed instruments/isolation prerequisites** | **Resolved as a work plan, not as proof** | H0 now names per-run paths/manifests/ports, cleanup ownership, process attribution, query-embed timing, encoder-ready instants and encoders-off coexistence (`:497-502,572-576,601`). No measurement or isolation run has been established here. Some fixture sources still cannot be directly checked: finding 16. |

## Disposition of the new attacks

| Mechanism attacked | Result of the attack |
|---|---|
| Ledger: double counting and critical section | Steady-state formula is sound with a consistent partition. Allocation-in-progress counterexample survives; reconciliation/release/sample epochs need the same authority. Finding 9. |
| Failed launch, retry, host/Engine restart | Reserve-before-spawn is correct, but pre-registration launches and attachment-scoped ticket expiry are not recoverable from the named registry alone. Finding 2. |
| Growth reservations at `K>1` | Two arena-only growth tickets can consume headroom needed by the stated workspace allowances. Finding 1. |
| Device-scope passed rule | No double pass for one continuously queued marked candidate. The same multi-run outer call can be passed again after re-entry. Equal-class starvation is excluded only with the persistent cursor interpretation stated in finding 10. |
| Engine quit | Shared attachment-only ownership fixes direct terminal killing of another Engine's host. Set-reference retirement must also obey that ownership split. Findings 5 and 11. |
| Supervisor crash / two host versions | Expired supervisor ownership must not admit a second host over the live first one. A host-lifetime device exclusion and recovery protocol are missing. Finding 5. |
| Config mismatch | Refusal without retirement is right. Unqualified in-process fallback bypasses the authority; a hash over mutable sets also manufactures mismatches. Findings 6 and 11. |
| Attach versus drain | The locked transition is sound for this specific race. Lease renewal versus stale expiry and replacement versus old execution are different races. Finding 4. |
| Three completion distinctions versus C1 | Correct distinctions, incomplete per-call state/acknowledgement protocol. Finding 3. |
| Backfill writes and D1-9 replay | Model/destination checks need source-revision and aggregate provenance; durable source replay acceptance is not native execution completion. Finding 8. |
| Exactness versus same-build baseline | Correct layered direction; deterministic payload equality must not include live timing fields, and masked workflow fields remain outside proof. Finding 14. |

## What was verified sound

**The operation boundary remains the right seam.** `SessionHandle.Lease` exposes raw sessions/run options and native results (`src/SessionHandle.java:179-207`); those cannot be an Engine-to-child interface. V2 keeps native leases, tokenizers, fallback and postprocessing together while exposing application operations. The source of `OrtRunChokePointTest` defines the intended `run`/`runPinned` funnel (`src/OrtRunChokePointTest.java:78-106`); this is a verified architectural test definition, not evidence it ran green.

**D1-12 is the correct generation substrate.** It already owns local composition, identities, per-set readiness and serving/candidate selection (`stages/D1.md:506-531`). V2 now uses that substrate rather than inventing a competing role-global reload owner. Comparing against the pinned searcher's generation rather than a separately read active pointer is the correct safety rule (`design.md:388-406`).

**Representation and execution identity should remain separate.** The supplied GPU fallback can load the CPU model file on CUDA and records the realized path only locally (`src/NativeSessionHandle.java:655-680`). Reporting that actual execution while preserving explicitly declared representation compatibility is materially better than comparing one selected-file SHA. Finding 7 completes the identity definition; it does not argue against this split.

**The handle-count correction is valid for the composed configurations.** The normal separate embedding/SPLADE configuration has five composed handles; BGE-M3 replaces both, leaving four. Citation is distinct from the search reranker. This follows directly from `src/InferenceCompositionRoot.java:128-161`. CPU leases do not consume the existing GPU semaphore (`src/NativeSessionHandle.java:310-338`). The stale six-handle wording still present in audit 1 should not override the corrected v2 interpretation.

**Reservation before allocation and release after confirmed stop are the right directions.** The existing llama stop path preserves ownership when termination fails (`src/LlamaServerOps.java:577-613`). V2 correctly requires eviction/admission before launch and makes single-generative tenancy an explicit device rule. Findings 1, 2 and 9 concern the accounting and lifecycle details needed to make those directions enforceable.

**The device-wide passed set fixes the original cross-lane defect at the native-wait level.** Once a waiting F is marked, aging is suspended while F remains waiting; another aged grant cannot overtake it. A second device seat does not invalidate that argument when the selector and marks are atomic. The remaining question is the larger unit to which the promise is attached, not whether independent per-lane exceptions are still present.

**D1-13 remains the native-retirement owner.** The supplied unconditional `close()` lacks the GPU-release semaphore (`src/NativeSessionHandle.java:362-398,552-577`). D1-13's refuse-new-leases, wait-for-all-CPU/GPU-leases, retain-on-timeout contract directly addresses that gap (`stages/D1.md:536-553`). Nothing in a transport cancellation should bypass it.

**The two process-ownership models are a genuine correction.** Ordinary unshared ownership matches the existing quit/upgrade versus restart/hang distinction (`src/ShutdownRequest.java:68-84`). Shared Engines recording attachments rather than owned children removes the specific reason an Engine's terminal cleanup would kill the common host. The missing successor-supervisor/set-lifetime protocols are separable additions, not reasons to return to per-Engine destructive ownership.

**Attach-versus-drain is correctly linearized as written.** If the attach wins the attachment lock, it cancels idle exit; if the transition wins, attach receives `HOST_DRAINING`. That excludes an adoption completing against a host already draining (`design.md:462-467`). It does not automatically cover every lease/epoch race, as finding 4 explains.

**The shared token matches the stated trust boundary.** ADR-0046 explicitly trusts same-user native processes and acknowledges that they can read the manifest token (`docs/0046-local-api-trust-boundary.md:36-53,69-88`). V2 need not invent per-Engine security principals to implement attachment accounting. Existing `RuntimeManifest.publicProjection()` removes private children (`src/RuntimeManifest.java:324-347`); new token/attachment fields still need equivalent projection tests. This is not a complete verification of the proposed HTTP filter implementation, which does not yet exist here.

**H0 and the exactness plan are more honest about evidence.** Encoders-off coexistence before a shared budget is sensible; separate cache/temp ownership addresses the hazards recorded by audits 2 and 4. Query-embed timing and encoder-ready instants are now named as new measurements rather than treated as already observed. The baseline does contain same-build hit-list noise and an explicit warning about the mask's blind spots (`evidence/README.md:35-58`). A killed-host test remains a process-loss test, not a reproduced CUDA illegal-memory-access fault (`design.md:354-360`).

## Cross-lane judgment and the genuinely minimal contract

**The dependency direction is now right:** D1's generation ownership, identity injection and native retirement land first; H1 wraps and relocates them afterward. The phrase “after D1 batch 4” accurately identifies the necessary substrate (`stages/D1.md:736-746`). It does not imply those tests have passed, that D1's BGE-M3 follow-up has landed, or that D2's later session-gate/profile changes need to wait for H1.

**The minimum contract is still overstated.** D1 need only preserve an identifiable set with immutable representation intent, explicit serving/candidate ownership tied to a runtime generation, a way for consumers to resolve that set, and retirement that refuses new leases and retains live native ownership on timeout. D1 can implement these with its documented local types and existing operation methods. An early discussion of future operation coverage is useful; requiring D1 to code against the host's new request/result/lifecycle API is not.

H1 owns translating those local methods into the operation port, host-issued handles, expanded representation descriptors, per-submission outcomes/completion, and reservation transactions. H2 owns attachment epochs, transport recovery and shared-supervisor authority. The BGE-M3 metadata migration needs an explicit integration/feature gate that respects D1's allowed follow-up, rather than an undocumented extension to D1 batch 4.

The smallest cross-lane wording change is:

> D1 proceeds against its documented local EncoderSet, runtime identity and retirement contracts, without new inference-port types. H1 adapts those completed mechanisms after batch 4 and owns any operation-port or device-admission changes. D1 is not blocked by agreement or implementation of the H1/H2 protocol.

## Acceptance consequences

The proposed tables are a useful starting point, but the next contract revision needs explicit traces for reservation materialization and workspace transfer; reserve–spawn–register crash points and late spawn after lease expiry; per-submission terminal recovery with multiple calls sharing a work ID; replacement while predecessor native work is still running; supervisor loss with a live host and a different-version successor; mutable-set reconfiguration followed by warm Engine adoption; two aged background lanes and multi-sub-batch foreground re-entry; and source-update/derived-write interleaving with replay acknowledgement separated from representation completion.

Those are **required future proofs**, not claims of tests performed by this review. The audits' empirical boundaries remain: practical cross-role concurrency and resident footprints, encoder-warm latency and merged memory, live migration-window behavior, actual CUDA-fault behavior, and platform-specific multi-device behavior have not been established by this source review.
