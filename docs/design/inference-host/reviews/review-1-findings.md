# Independent review: inference host design

**Review target:** `design.md` in the supplied `inference-host-review.zip`, prepared against `4229f1091`.  
**Disposition:** retain the operation-port direction, but revise the generation, reservation, scheduling and shared-ownership contracts before treating H1/H2 as implementable. Do not make the proposed H1 package a prerequisite for lane F D1.

## Scope and evidence boundary

This is a read-only source and document review. No Gradle, npm, dev stack, repository tests, GPU experiments or live probes were run. Failure scenarios below are counterexamples to the proposed contracts, not claims that those failures were observed in production. Historical measurements are identified as records in the bundle, not independently reproduced results.

Paths are relative to the extracted bundle. In citations below, `src/` means `context/src/`, `audits/` means `context/audits/`, `docs/` means `context/docs/`, and `stages/` means `context/design/stages/`. `lane-F design.md` means `context/design/design.md`; unqualified `design.md` is the review target.

The README's assertion that every code citation resolves to a verbatim source is not satisfied by this bundle. Twenty explicitly named code files in the target are absent, including `IndexFingerprint.java`, `ReadPathOps.java`, `RuntimeActivationService.java`, `ManagedChildReconciler.java`, `SpladeEncoder.java`, `backend.py`, and `lib.rs`. `SessionOptionsApplier.java`, important to the audits, is also absent. Where a relevant implementation is absent, the review cites the supplied audit or design document rather than claiming to have inspected that implementation. There is also citation drift: audit 2 line 10 cites `ModelSessionPolicy.java:215-218`, while the supplied file ends at line 144; the relevant fallback factory is at lines 83-98. Restoring the missing sources and validating citation ranges is necessary before calling the entire evidence package independently source-verified.

**Severity:** High means a contract permits incompatible index data, native-resource overcommit, another Engine's outage, or failure of a promised service/lifetime bound. Medium means a material availability, migration, performance or acceptance defect. Ranking is by impact, not implementation order.

## Ranked findings

### 1. High — A role-shaped request cannot address the two encoder generations that D1 requires, and result-only binding does not protect writes

**Design:** §§4.1, 6.2–6.3; `design.md:125-141,330-354,481-482`.

**Contradicting evidence:** `stages/D1.md:506-528` requires search on Blue to resolve serving encoder set A while Green's app services write using candidate set B. It requires per-runtime identity rather than process-wide fingerprint providers. `stages/D1.md:440-452` binds journal replay to `building_generation_id` and checks accepted source hashes at activation. In the actual write path, `src/CombinedEnrichmentBackfillOps.java:969-987` accepts any nonempty vector and marks embedding complete; `:1049-1061` does the corresponding sparse update; `:1200-1211` writes those accumulated updates without encoder identity.

The proposed requests carry urgency, work ID, budget and correlation, but no encoder-set selector. `reload(role, config)` is also role-global. Saying the host can hold A and B does not specify how either caller selects them. A post-result comparison is a rejection mechanism, not routing.

**Failure scenario:** Blue contains A vectors. The host loads B for a Green rebuild. A Green embedding finishes after a role reload, or an A embedding already in flight is delivered to a write path that now targets Green. Its dimension and source hash are valid, so the current write-shaped adapter can mark it complete. Green's generation metadata says B. A subsequent B query passes the proposed SHA comparison but searches a generation containing A vectors. Alternatively, choosing B globally merely makes all Blue semantic queries fail throughout the rebuild, violating beside-mode continuity.

**Smallest fix:** introduce an immutable encoder-set handle selected on every inference request. Acquire that handle together with the actual read/write generation, and retain both until the operation finishes. Carry the expected representation identity and destination generation through accepted derived writes; validate them at the write boundary, not only at search. Journal replay must resolve its fixed target generation and encode with that generation's set before acknowledging application. Journals that retain only source commands need not redundantly persist an encoder identity on every row, but any accepted/replayed derived output needs a checkable binding. Query comparison must use the pinned runtime/searcher, not a separately read mutable “active” pointer. Align this with D1-12 rather than creating a competing generation owner.

### 2. High — One model SHA is not the complete compatibility predicate, particularly for BGE-M3 and fallback execution

**Design:** §§4.1, 6.2; `design.md:138-141,332-340`.

**Contradicting evidence:** the bundled, re-grounded D1 explicitly identifies the missing BGE-M3 digest in the fingerprint: `stages/D1.md:61-65`. `src/SearchInputCapture.java:155-175,293-298,326-329` uses BGE-M3 for both dense and sparse query representations. `src/NativeSessionHandle.java:655-680` can load a different model file on the GPU after preferred-model failure; the realized file is held in local `modelPathUsed`, while the execution provider remains CUDA. The target includes `dimension` in the result but specifies comparing only the SHA.

**Failure scenario:** two BGE-M3 models with the same vector dimension cannot be distinguished by the three fingerprint keys the design claims are sufficient. A preferred FP16 file can also fail and be replaced by another file while the wrapper stamps the originally selected identity. Conversely, equating every execution-file change with representation incompatibility can disable the CPU fallback the design promises to preserve. A matching SHA does not itself perform the promised dimension validation.

**Smallest fix:** define a versioned **representation compatibility identity** separately from **realized execution identity**. Populate the former for every indexed representation, including BGE-M3, with explicit handling of old generations that lack it. Validate dimensions and the relevant dense/sparse identities against the pinned generation. Report actual file/precision/provider for each item or homogeneous result group when fallback changes execution. Declare which execution variants are representation-compatible instead of guessing from one file SHA. Cache query vectors by representation identity plus normalized input, not just text with an invalidation side effect. The BGE-M3 metadata migration needs an explicit cross-lane decision because D1 currently records that gap rather than solving it.

### 3. High — Claiming llama capacity after activation cannot prevent activation-time overcommit

**Design:** §§5.4, 8, decision 6; `design.md:273-290,439-441,476-478`.

**Contradicting evidence:** `src/InferenceLifecycleManager.java:425-444` checks total VRAM, not free capacity reserved against encoders. It starts the server and waits for health at `:466-467`. The supplied audit identifies the activation delta as a post-hoc self-test observation, not admission (`audits/audit-2-session-gpu-lifecycle.md:81-83`). `src/RuntimeGpuLease.java:44-53` supplies no existing byte reservation.

**Failure scenario:** encoder sessions leave less free memory than llama needs. Llama allocates during startup and fails before the Engine can execute the new post-activation `claim`. The host never gets an opportunity to evict the encoders that would have made startup possible. Two Engines can likewise launch concurrently before either claims. Moreover, a bytes-only budget cannot guarantee the unconditional “second chat refused” rule on a card large enough for two chat allocations.

**Smallest fix:** reserve before any GPU-capable activation, including self-tests, retries and context-rung changes. Use an atomic ticket scoped to the device, attachment and actual child instance: reserve, drain/evict and confirm capacity, launch, then reconcile the reservation with observed usage. Release a failed launch's ticket only after its allocations are confirmed gone. Keep a distinct single-chat ownership rule if the product requires one chat tenant regardless of available bytes. Recover/reconcile tickets after host or Engine restart; do not identify every claimant merely as `tenant=llama`.

### 4. High — Three snapshots and one warm-up do not produce a safe lifetime footprint

**Design:** §§5.3–5.4, decision 5; `design.md:253-264,278-287,473-475`.

**Contradicting evidence:** `src/ModelSessionPolicyResolver.java:219-221` enables shrinkage by default; `src/ModelSessionPolicy.java:140-143` describes its run option; `src/NativeSessionHandle.java:684-692` constructs those GPU run options. The bundled allocator investigation explicitly rejects “hold the peak forever” and records grow/shrink behavior between calls (`docs/394-encoder-call-path-batching.md:106-114`). It also records progressive fragmentation after initially successful calls under a larger-batch experiment (`:24-35`). These are historical results, not measurements of this proposed host.

**Failure scenario:** the post-warm-up snapshot sees the shrunken resident baseline, missing temporary workspace used inside the run. The store treats that value as the whole footprint. Two lanes admitted at K > 1 later expand simultaneously and exceed headroom. Even a sampled maximum from one maximum-shaped batch need not cover later allocation history. Another tenant allocating or freeing between snapshots also contaminates the attributed delta.

There are additional weaknesses in the same mechanism: the key omits ORT/runtime version, device identity and allocator options; a session arena cap is not a declaration of total device usage; and using that cap as the initial estimate can prevent a small, otherwise viable model from ever being admitted for observation.

**Smallest fix:** distinguish resident allocation from incremental execution/growth reservation and unmaterialized load reservations. Enforce reservations before session creation, warm-up, inference and lazy reacquisition, not only at initial load. Make observed profiles advisory, versioned, attributable to a controlled measurement window, and invalidated when runtime/device/allocator policy changes. Use a conservative unknown-profile path and retain reported fallback/refusal on underestimation. To claim a hard budget, the first cut must retain a defensible conservative reservation policy; a learned maximum is not a proof of a memory upper bound. K must participate in execution-memory accounting.

### 5. High — A shared host cannot remain an ordinary terminally owned child of each Engine

**Design:** §§7.2, 8 and H2 gates; `design.md:375-392,429-441,515-519`.

**Contradicting evidence:** `lane-F design.md:878-884` makes each Engine's manifest the sole child-ownership authority. `:911-916` requires terminal ownership evidence to survive until children are confirmed gone. `src/ManagedChild.java:12-23` represents an Engine-owned process; `src/ManagedChildRegistry.java:7-13` persists mutations through that authority. `src/dev-runner.cjs:1005-1035` kills every identity-matching registered child on terminal cleanup, with no shared-client exception, and `:3118` invokes that cleanup on stop.

**Failure scenario:** Engine A launches the host and Engine B attaches. A quits; either its graceful child policy or its unchanged terminal supervisor kills the host while B indexes and searches. Copying the host into both manifests gives both supervisors destructive ownership; leaving it only in A makes A's lifecycle decisive for B. A configuration mismatch can also retire a host still serving another Engine. Two host versions sharing the same device are not made safe merely by using different `(modelsDir, hostVersion)` keys.

**Smallest fix:** select one durable destructive owner for a shared host, such as the per-user/device GPU supervisor. Engine manifests record attachments, not duplicate owned-child records. An Engine quit detaches; explicit shared-host shutdown/upgrade belongs to that one owner under a defined client policy. Preserve ordinary child ownership for the unshared packaged case. Key exclusive device ownership by user/device, and make incompatible host configurations refuse or coordinate instead of automatically retiring another client's host. This explicitly changes the claim that supervisor behavior needs no change in the shared case.

### 6. High — Per-lane aging is not a device-level service bound

**Design:** §5.2; `design.md:219-234`.

**Contradicting evidence:** `lane-F design.md:672-688` ties the one-aged-batch claim to the full wait of a foreground call and derives background progress from a finite admitted queue. D2 requires deterministic proofs of both sides of the guarantee (`stages/D2.md:189-209`). The new design supplies local passed sets, global foreground precedence and an additional device-slot wait, but no global fair-selection or aging rule.

**Failure scenario:** let K = 2 with three runnable lanes. Embedding and reranker each maintain bounded but continuously replenished foreground queues. NER has one aged background batch. At each slot release a foreground candidate exists in one of the first two lanes, so the global foreground-precedence rule can leave NER waiting forever. Each Engine and each lane remains within its local bounds. Among foreground lanes, the unspecified device selector can also repeatedly choose the same lanes over another waiting lane.

**Exact limit of the criticism:** K > 1 does **not** break the local passed-set invariant by itself. If K is at least the number of runnable lanes, there is no additional slot contention under the one-running-batch-per-lane rule. The gap appears when K is below the runnable lane count, including 1 < K < lane count; it can also appear at K = 1. If aged lane heads are instead allowed to override global foreground precedence independently, there is no global rule preventing multiple aged grants during one foreground call's wait. The design currently chooses neither complete policy.

**Smallest fix:** define device grants atomically, with foreground ordering and the aging exception at the same scope. If promising one aged overtake across the host, maintain a global passed set and global aging suspension across simultaneous slot grants. Otherwise explicitly promise only the local bound and add a separately bounded fair device allocator. Define seating at a bounded native sub-batch, releasing eligibility between sub-batches; do not silently hold a slot for an arbitrarily long outer request. Add deterministic K = 1 and K = 2 traces with more runnable lanes than slots, saturating foreground load and cancellation. Report queueing and run-time interference separately.

### 7. High — Producer cardinality and K do not bound a shared host's admitted or retained work

**Design:** §§4.4, 5.2, 8; `design.md:184-187,212-239,429-441`.

**Contradicting evidence:** `lane-F design.md:1511-1527` explicitly says that per-client quotas do not form a client-count-independent aggregate cap and assigns a shared host its own cap. `:1529-1546` separately bounds retained state. D2's producer token is issued once per stage (`stages/D2.md:195-199`); audit 1 identifies the single synchronous indexing producer (`audits/audit-1-consumer-surface.md:14-16,93-109`). K bounds running GPU batches, not queued request bodies, attachments, CPU work or outstanding producer submissions.

**Failure scenario:** two Engines each have a legal token and legal local admission. They enqueue large requests faster than the host drains them; further attachments multiply the offered load. The host's small heap fills while K remains perfectly satisfied. A producer can also submit multiple asynchronous requests using one token unless the contract prohibits it. After an Engine restart, either a stale producer token prevents the successor from indexing or a fresh client ID leaves both epochs' work live.

**Smallest fix:** the Engine's indexing-lifetime owner holds producer tokens, not the shared models directory. Per `(attachment epoch, indexing stage/role)` is appropriate for separate indexes; a host-global single producer would wrongly exclude the second Engine. Limit outstanding submissions per producer, add host-wide count/byte/attachment/CPU limits independent of Engine count, and specify fair cross-client service. Fence old epochs on reconnect and release running-work capacity only after actual execution ends. Bind producer, work and tenant IDs to the host-issued attachment context rather than accepting unrelated client ID fields.

### 8. High — HTTP completion and cancellation do not yet preserve C1's actual-exit lifetime

**Design:** §§4.2, 7.3; `design.md:156-161,396-410`.

**Contradicting evidence:** `stages/C1.md:1377-1383` retains the owner until all accepted children actually exit. `:1582-1589` distinguishes an interrupted waiter from an already-issued native lease. `src/SessionHandle.java:202-208,226-228` releases the lease only after the synchronous run's lifetime. `lane-F design.md:582-593` refuses stale/unknown work linkage rather than creating fresh uncancelled work.

**Failure scenario:** an Engine times out and cancels its HTTP future. The local task exits, but the host remains inside non-preemptible `run()`. Treating transport completion as child completion releases Engine admission and foreground accounting while its actual child work still executes. A separate `/cancel` request can also arrive before the inference request reaches the host queue; cancelling only currently queued entries then misses the later arrival.

**Smallest fix:** distinguish transport detachment, cancellation acceptance and execution completion. Make host enqueue/cancel transitions linearizable for an attachment-scoped work ID, with bounded cancellation fencing for in-transit submissions. Keep the Engine's remote-work retention until terminal acknowledgement or confirmed host death, even if the caller has gone away. Maintain the independent host aggregate cap from finding 7. Test cancel-before-enqueue, disconnect-during-run, host death and acknowledgement loss. This needs remote execution state, but not a second Engine-side admission authority.

### 9. Medium — The admission formula double-counts resident allocations

**Design:** §5.3; `design.md:260-262`.

**Contradicting evidence:** `src/NvmlService.java:184-190` reads current total, free and used device bytes. Current free memory already excludes resident tenants. The design then defines `committed` as every resident tenant's footprint and subtracts it again.

**Failure scenario:** on a hypothetical 12 GiB device, existing tenants actually use 4 GiB, current free is 8 GiB, the candidate needs 4 GiB, and the margin is 0.5 GiB. The formula computes `8 - 0.5 - 4 = 3.5 GiB` and rejects a candidate that would leave 4 GiB physically free. It needlessly evicts sessions or sends workloads to CPU. Calling committed “future growth” would be a reasonable different model, but is not the present definition.

**Smallest fix:** use a consistent ledger. With current free as the starting point, subtract only outstanding allocations not yet reflected in that observation and explicitly reserved incremental growth, then compare the new incremental demand. Alternatively start from total bytes and account for resident commitments and external usage exactly once. Define the snapshot/reservation critical section and test two competing admissions against one free-memory reading.

### 10. Medium — Largest-first eviction can sacrifice foreground reranking while keeping dispensable background models

**Design:** §§5.4, 11; `design.md:278-287,506`.

**Contradicting evidence:** `src/RagContextOps.java:1263-1275` changes the rerank candidate count according to GPU availability, and `:1289-1302` can skip reranking. `src/WorkerSearchService.java:499-500` uses the request budget or a 200 ms default. Audit 1's role table identifies NER as index-only and reranker as request-time (`audits/audit-1-consumer-surface.md:100-107`). The current code already releases reranker on a chat claim (`src/RagContextOps.java:198-202`), so eviction itself is not a newly discovered regression.

**Failure scenario:** a foreground reranker is the largest reclaimable session, but an idle NER session would free enough capacity. Largest-first releases the reranker anyway. Its next request runs under CPU candidate limits or misses its deadline while NER remains warm. The proposed gate checks only that a semantic query answers on CPU, so it can pass despite reduced reranking coverage.

**Smallest fix:** classify eviction candidates by serving obligation and active leases before sorting by measured reclaimable bytes. Prefer background-only idle sessions when sufficient; drain issued leases before any release. If foreground eviction remains an intentional policy, state the permitted rerank degradation and gate candidate coverage, skipped results and deadline success during chat—not merely dense-query availability. Do not assert that a particular card size necessarily releases nothing without measurements for its actual tenants.

### 11. Medium — Idle exit is not synchronized with adoption, and “attached client” has no defined lifetime

**Design:** §7.2; `design.md:375-390`.

**Contradicting evidence:** the copied llama adoption pattern rechecks process identity and liveness (`src/LlamaServerOps.java:1002-1026`), but cannot reserve a host against an independently firing idle timer. The proposed endpoints list attach but no detach/heartbeat lifecycle (`design.md:408-411`). The lane-F ownership contract preserves a record across restart, not perpetual process liveness (`lane-F design.md:903-916`).

**Failure scenario:** an Engine probes and identity-checks the host just before the ten-minute timer expires. The host commits to exit, and the Engine then reports adoption or sends its first semantic request. Conversely, counting attachments without crash expiry can leave a host permanently non-idle after its Engine dies. HTTP connection pooling is not a durable Engine attachment lifetime.

**Smallest fix:** atomically acquire an attachment lease against the same state transition that enters `DRAINING`. A successful attach cancels the idle deadline; a host already draining refuses the attach so the Engine can retry discovery. Define detach, crash expiry/heartbeat or process-identity tracking, a monotonic idle deadline, and the restart grace interval. Gate adoption at the idle boundary and after abrupt client death. For a single Engine, the underlying RESTART/HANG manifest handoff is otherwise compatible with warm adoption.

### 12. Medium — Capabilities do not replace the tokenizer-only document-window-count operation

**Design:** §§4.1, 4.3; `design.md:127,166-171`.

**Contradicting evidence:** `src/EmbeddingProvider.java:50-65` defines a text-dependent count and explains why whole-document embedding can repeatedly restart long work without making resumable progress. `src/CombinedEnrichmentBackfillOps.java:800-817` uses that count to partition single-window and multi-window work before inference. The target's `supportsWindows` is a model property, not the count for a particular text. Returning `totalWindows` after an inference operation does not specify the existing tokenizer-only probe.

**Failure scenario:** an adapter uses the old default count of one because only capabilities are available. Long documents go through the non-resumable batch path again. Alternatively it performs an actual embedding merely to obtain the count, adding GPU work before pacing and scheduling decisions that previously used tokenization alone.

**Smallest fix:** preserve a cheap count operation, or explicitly define `WINDOWS(from=0, max=0)` as tokenizer-only, returning the total without an ORT run or GPU seat. Preserve per-text cardinality, character-span conventions and resumption metadata. This is an operation-contract omission, not a reason to prefer a process boundary.

### 13. Medium — Port-first is reasonable, but its claimed cost advantage and D1 dependency are not established

**Design:** §§2, 10, 13–14; `design.md:63-69,467-468,538-543,562-564`.

**Contradicting evidence:** leaks 1–6 in `audits/audit-1-consumer-surface.md:81-87` are inside encoders, native leases and their lifecycle; those components can remain together inside either placement. `src/SessionHandle.java:179-228` confirms the ORT-typed internal seam. The current D1 document already rejects a new catalogue footprint field (`stages/D1.md:88-93`), specifies cap-based candidate sizing (`:557-569`), and locates lease accounting directly in the handle (`:534-547`). It permits Flow B to move to D1b if it grows too large (`:698-702`). Lane F explicitly calls the inference host an improvement rather than a prerequisite (`lane-F design.md:719-745`).

**Failure scenario:** lane F waits for a “thin port” whose real completion requires generation addressing, moving modules, consumer adapters, status/cancellation semantics and the host budget. D1 then inherits several unsettled H1 mechanisms despite having a bounded current plan. Moving handle-internal retirement or aging later would have been relocation, not necessarily a second implementation.

**Smallest fix:** compare two honest alternatives: operation port plus minimal in-process adapter versus the same operation port plus child client—not “child first, contract later.” Keep port-first as a risk-reduction preference, not a proved cost result. Make the D1 agreement only the minimum reusable contract: encoder-set identity, ownership, retirement and operation shapes. Let D1's required mechanisms proceed behind their current owner unless a separately scoped port-only change is ready without blocking them. Keep scheduling/budget policy changes and MMR batching out of the placement-only comparison. Update §13 to the bundled, re-grounded D1 instead of its superseded footprint plan.

### 14. Medium — The bit-identical workflow oracle contradicts the supplied same-build baseline

**Design:** §11; `design.md:496-507`, particularly `:503`.

**Contradicting evidence:** `context/evidence/README.md:35-58` records differences between repeated captures on the same build, including raw cross-side differences withdrawn by the noise mask and upstream GPU embedding jitter. The record explicitly limits what its PASS certifies. `src/RagContextOps.java:1451-1458` shows the single-document calls H1 proposes to batch; H1 also changes scheduling and CPU fallback behavior, so “same sessions, same options” does not establish identical execution shape.

**Failure scenario:** H1 behaves acceptably but fails the blanket byte-equality row because repeated identical-build runs already differ. Relaxing the row informally then risks hiding genuine ranking or representation regressions. A transport cut, a batching optimization and a device-policy change cannot be causally attributed by one purportedly bit-exact end-to-end comparison.

**Smallest fix:** separate exact contract/serialization tests from numeric and workflow tests. Use exact bytes for the same captured arrays passing through the two placements and for demonstrably deterministic fixtures. Use pinned execution shape and an explicit numeric criterion for encoder outputs; retain the existing same-build noise characterization plus quality/degradation gates for end-to-end behavior. Test MMR batching and scheduling/budget changes as separate policy changes. Never treat the baseline's noise withdrawal as proof that all result fields are equal.

### 15. Medium — H0 and the gate table require additional instruments and isolation work that the sequence does not name

**Design:** §§8, 11–12; `design.md:443-448,499-519,528-530`.

**Contradicting evidence:** audit 4 identifies the fixed eval data directory, whole-directory `--clean`, hard-coded ports and global cleanup behavior (`audits/audit-4-multi-instance-constraints.md:59-63`). The raw dev-runner uses a shared lease root (`src/dev-runner.cjs:53-62`). The current request-time evidence lacks query-embedding timing (`context/evidence/README.md:82-89`), and the C1 memory record is an initial host-memory contract, not a measured VRAM or merged-process baseline (`context/evidence/memory-budget.md:3-28`). Stage E already lists mandatory instrument repairs (`stages/E.md:120-140`).

**Failure scenario:** adding `--port` does not make two eval runs against the same checkout safe: they can still share the data directory, contend on its lock, or have one run clean another's data. A campaign script can stop the wrong active run. A merged process sampler can count a shared host twice. Existing latency traces cannot directly prove the new queue/transport/embedding numbers, and the C1 baseline cannot be said to have the same explicit K without an instrumented legacy-policy comparison. H0's cache fixes also do not, by themselves, make arbitrary simultaneous GPU allocation safe.

**Smallest fix:** name per-run data directories, manifests, ports and identity-scoped cleanup, plus a read-only shared-model policy, as prerequisites. Specify CPU/encoders-off or otherwise explicitly reserved GPU profiles for H0 coexistence until host arbitration exists. Add per-role ready timestamps, host/Engine process identity and private-byte attribution, device free/reserved/resident observations, per-client queue/seating counters, and paired client/host timing. Count a shared host once in the machine sum. Assign each gate a producer and failing test for missing data. The source scripts themselves are not included in this bundle; their limitations here are audit-supported, not independently inspected.

## What was verified sound

### The operation boundary is the correct seam, and none of the eight leaks requires a child in order to be closed

The raw lease is ORT-shaped (`src/SessionHandle.java:179-228`), while the composition boundary already groups the native handles (`src/InferenceSurface.java:41-60`, `src/InferenceCompositionRoot.java:117-166`). Keeping tensors, CPU fallback control, pinned buffers and lifecycle callbacks inside the implementation closes the resource leaks in either placement. Operation-level status and identity are appropriate outward projections. A process adds fault isolation and warm survival; it is not required to make the Java interface runtime-neutral. This supports port-first as a useful engineering sequence, not its unmeasured cost claim.

### The audit's per-role correction is real, with a further cardinality correction

`src/NativeSessionHandle.java:289-338,511-514` confirms GPU serialization and CPU bypass. `src/InferenceCompositionRoot.java:128-161` confirms that BGE-M3 replaces separate embedding and SPLADE. There are six role kinds, not six simultaneously composed handles in the normal configurations: the SPLADE configuration has at most five total handles, four GPU-capable; successful BGE-M3 composition has four total, three GPU-capable, because citation is CPU-only. The design's literal “six instances today” is overstated, but its core criticism of independent role semaphores is valid.

### There is no implemented aggregate device-byte budget in the supplied session path

The resolver does not use hardware to size roles (`src/ModelSessionPolicyResolver.java:56-64,207-216`); the fallback hardware profile can have zero reported VRAM (`src/KnowledgeServer.java:1408-1411`); the existing lease ignores requested bytes (`src/RuntimeGpuLease.java:44-53`). Centralizing admission is warranted. The absence of a catalogue footprint field supports using observations as evidence, but not the assertion that observations alone are a hard bound.

### Release-after-confirmed-stop is the right direction for the falling edge

The actual stop/flush ordering is explicit in `src/InferenceLifecycleManager.java:549-553`; GPU acquisition is lazy (`src/NativeSessionHandle.java:211-245`). Making capacity unavailable until confirmed exit addresses the audited early-clear failure mode. It must be paired with reserve-before-start and restart reconciliation, as finding 3 explains.

### The native retirement defects are genuine and are correctly assigned to D1

`src/NativeSessionHandle.java:552-577` closes sessions without acquiring the GPU permit; `:587-615` can close/recreate CPU sessions without accounting for outstanding CPU leases. D1-13 specifies the appropriate retirement-and-lease-count mechanism. These defects do not justify making a transport or module relocation prerequisite to fixing them.

### Single-Engine warm adoption fits lane F's manifest handoff

Lane F deliberately preserves surviving ownership on RESTART/HANG and deletes terminal ownership only after confirmed cleanup (`lane-F design.md:911-916`). The raw llama owner checks configuration, health and OS identity and performs a second liveness/identity check (`src/LlamaServerOps.java:987-1026`). A new kind and per-kind probe can reuse that model for an unshared host. The failures are shared destructive ownership and the uncoordinated idle timer, not an inherent incompatibility between restart handoff and adoption.

### A shared host token is sufficient for the existing same-user authentication boundary

ADR-0046 expressly trusts same-user native processes and accepts their ability to read the manifest token (`docs/0046-local-api-trust-boundary.md:44-53,104-106`). A second Engine does not require a distinct security credential merely to enter that boundary. Separate attachment handles/epochs are still needed for lifecycle, cancellation, producer and tenant accounting; those are not a new trust boundary. An independently minted secret per Engine would not isolate mutually untrusted same-user processes if each could read the other's credential. The missing host-wide ownership protocol must not be misdiagnosed as a token-strength problem.

### Shared model files are not the producer owner, and the H0 cache hazards are real

Separate indexes legitimately have separate indexing producers. The raw cache writes next to shared model files (`src/OnnxSessionCache.java:41-43,263-269,332-339`); the native helper explicitly selects a temp directory by a single-JVM timestamp heuristic (`src/OrtCudaHelper.java:453-489`). Isolating those writes before multi-process use is sound. Preserve the existing CPU/CUDA optimized-graph distinction when relocating the cache (`src/OnnxSessionCache.java:29-39,54-56`); “model SHA plus ORT version” must not erase its EP namespace.

### The weakened motivation claims are appropriately treated as unproved

The supplied baseline distinguishes HTTP/index readiness from encoder warmth and does not establish a 40-second encoder reload (`audits/audit-4-multi-instance-constraints.md:65-71`). The native-fault audit explicitly does not establish how real CUDA illegal-memory-access faults surface (`audits/audit-2-session-gpu-lifecycle.md:132-145`). The design appropriately labels H1 as retaining the Engine's fault domain and H2's forced kill as a test of process loss, not a reproduction of a CUDA fault (`design.md:294-301,594-596`). Those limits should remain in the final ADR and acceptance record.

## Disposition on the nine requested attacks

| Requested attack | Result |
|---|---|
| Port-first versus child-first | No leak requires a child; cost superiority is unproved. Separate port-only relocation from policy changes. Finding 13. |
| Aging with K > 1 | Local invariant survives; global device-slot fairness/background service is missing when runnable lanes exceed K. Finding 6. |
| Observed footprint under growth/shrinkage | Not a lifetime bound; account for runtime peaks and reservations separately, and fix double-counting. Findings 4 and 9. |
| Largest-first evicts foreground reranker | Yes, it can; existing code also evicts it, so the issue is the new policy's promise and gate, not a falsely claimed novel regression. Finding 10. |
| One SHA for generation binding | Insufficient: routing, pinned generation, write/replay binding, BGE-M3 identity and realized fallback need specification. Findings 1 and 2. |
| Adoption, manifest handoff and idle timer | Handoff works for an unshared host; shared ownership and the timer do not yet have a safe contract. Findings 5 and 11. |
| Shared token versus second Engine credential | Shared token fits ADR-0046; attachment identity and scoped ownership are still required. Finding 7 and verified-sound section. |
| Producer token with two Engines | Engine indexing-lifetime owners hold separate attachment-scoped tokens; host owns aggregate scheduling and bounds. Finding 7. |
| Port at the start of D1; measurable gates | Not a demonstrated dependency; current D1 already re-cuts the cited work. Instruments/isolation and the exactness oracle also need revision. Findings 13–15. |

## Recommended cross-lane decision

Let lane F D1 proceed with its encoder-set ownership, per-runtime identity, native retirement and bounded device-line work. Agree the operation contract and generation handle early, but introduce a port-only adapter ahead of D1 only when that independently bounded change is ready. Do not make D1 wait for observed budgeting, multi-Engine attachment, HTTP transport or shared-host supervision. Before H2, require passing contract tests for reserve-before-launch, A/B read-and-write binding, fair device grants, attachment-scoped cancellation, and one destructive owner across Engine restart, quit and upgrade.
