# Independent review of the supplied inference-host draft

**Disposition: retain the operation-port direction and let lane F proceed independently. The supplied H1/H2 contract still has five High findings and one Medium finding. Do not mark it closed.**

## 1. Review target and evidence boundary

The requested review is of **v2**, but this attachment is a later bundle. `README.md:1-18` identifies a round-three bundle, and `design.md:1-4,17-45` identifies **v3**. The only other `design.md` in the archive is the lane-F design under `context/design/`; it is not the missing inference-host v2. The attachment preserves `review-2-findings.md`, an earlier review of v2, but not the complete v2 target.

Accordingly, the findings below are **independently checked against the supplied v3**, not retrospectively attributed to v2. The round-one ledger distinguishes the earlier review's recorded v2 disposition from my independent disposition of v3. A fresh, line-for-line v2 resolution certification is not possible from this attachment. The `as v2` operation/protocol back-references in v3 also limit review of details that are not restated (`design.md:113-117,210-211,420-424`).

The archive contains both the old 118-file flat `context/src/` and a 124-file path-preserving `context/src-tree/`. The README/round-two response's statement that the flat directory was removed does not match the archive. I used the path-preserving sources, notably the **1,996-line app-services implementation** of `RuntimeActivationService`, not the 35-line app-api interface. A source-path index appears at the end of this report. Revision identifiers are the bundle's provenance labels, not independently verified Git history.

This was a read-only inspection of the documents, audits, and relevant source paths. No builds, repository tests, development stack, GPU experiments, or runtime probes were run. All failure traces below are constructed counterexamples, not observed incidents. A design acceptance row is not a passing test.

Citation conventions: `design.md` is the supplied inference-host **v3**; `lane-F design.md` is `context/design/design.md`; `C1.md`, `D1.md`, and `D2.md` are under `context/design/stages/`; source basenames resolve through `context/src-tree/` and the source-path index. Audit and evidence paths are written explicitly.

## 2. Ranked findings

### F1. High — A persisted launch intent still cannot prove that an unbound launch is absent

**Section:** §5.3, ticket recovery; §5.4, reserving llama tenants. `design.md:265-278,311-321`.

**Contradicting evidence:** The recovery rule releases an unbound ticket after “confirmed absence by a fresh device read and a registry scan” (`design.md:272-276`). But the registered-child path executes `pb.start()` before constructing and registering the child identity (`LlamaServerOps.java:1255-1275`). `ManagedChild.fromProcess` obtains the PID/start/executable and creates the child ID only after spawn (`ManagedChild.java:48-71`). NVML supplies device-wide total/free/used bytes, not proof of a process's absence (`NvmlService.java:177-190`). The proposed persisted intent fields (`design.md:266-270`) do not specify how an unregistered live process is discoverably linked to that intent.

The now-supplied install self-test is a second independent launch path: it starts a process and then waits for health (`RuntimeActivationService` implementation:1217-1221), with its own `pb.start()` at `:1294-1330`. Instrumenting only `LlamaServerOps` would not cover it.

**Concrete failure:** Persist intent I and reserve T. Spawn llama, but pause the child before its first CUDA allocation. The Engine dies before registration. Recovery sees no child record and a fresh device reading with no new GPU usage. If that combination satisfies the stated release rule, T is released. Another allocation is admitted, then the old child resumes and allocates. Reserve-before-spawn was obeyed; the entitlement was subsequently revoked without establishing that the allocation could no longer occur.

This is not solved by tagging an observation epoch: the missing fact is process/launch identity, not the freshness of the memory reading.

**Smallest fix:** Make `LAUNCH_PENDING -> RELEASED` require proof that the launch **cannot still start or continue**. Persist a recoverable launch-to-process association, or use a launcher/containment mechanism whose identity and termination establish that fact across the spawn/registration gap. A registry miss or zero VRAM delta is only corroboration. Unknown outcome stays `RETAINED`, with an explicit fail-closed recovery path. Apply the same protocol to managed starts, self-tests, retries, and rung relaunches. After a host restart, reconstruct surviving llama entitlements before reopening admission.

The intended pre-spawn order and independent single-generative-tenant rule are sound; the release predicate is not yet sufficient.

### F2. High — Unshared adoption/draining can still create two live device authorities

**Section:** §7.2, unshared ownership and idle exit; §5.5, exclusive allocator promise; §13, deferred device exclusion. `design.md:333-340,403-416,528-535`.

**Contradicting evidence:** V3 says a draining host is refused and the Engine “spawns afresh” (`design.md:410-412`), without requiring the old host's confirmed exit before replacement GPU admission. It defers the host-lifetime device lock to H2b (`:528-531`). Yet the unshared design also promises that no second GPU allocator runs outside the ledger's authority (`:339-340`). D1-13 expressly permits retirement to time out while retaining live native sessions (`D1.md:536-544`). The cited llama adoption precedent rechecks process identity, not an atomic encoder-host client lease (`LlamaServerOps.java:1002-1026`). Its invalid-child path does require successful termination before moving on (`:1007-1012`).

**Concrete failure A — probe versus drain:** The Engine probes `SERVING`. Before it commits adoption/establishes its client lease, the idle transition acquires the client lock and changes the host to `DRAINING`. The PID, start time, executable, and config hash are unchanged, so the borrowed second identity check can still succeed. A status probe is not an atomic attach.

**Concrete failure B — drain versus replacement:** The old Engine is gone, its host has no renewed client lease, and the host enters `DRAINING` while an old native call or retirement still owns resources. The replacement Engine gets `HOST_DRAINING` and launches a fresh host. There are now two independently mutable ledgers. Counting the old host's presently materialized bytes as external usage does not import its outstanding, not-yet-materialized run entitlements.

This race does not require two developer Engines or the shared-host feature. An old and a new host version can appear in this trace as part of ordinary recovery/replacement.

**Smallest fix:** Successful adoption must atomically validate `SERVING` and establish/renew the client lease under the same authority as `SERVING -> DRAINING`. A refusal must not authorize replacement GPU allocation: retain the old ownership record, stop/drain it under identity-safe rules, and confirm exit before replacement admission, or require a host-held device-exclusive lock throughout both processes' lifetimes. Keep admission closed while recovering live external llama tickets. Only the multi-client supervisor protocol should be deferred to H2b; exclusion during unshared host replacement cannot be.

Engine death is also not evidence that its already-accepted **host execution** has ended; v3 itself says predecessor submissions terminate on their own (`design.md:412-415`). Their running capacity, leases, and tickets must survive client-incarnation changes until actual termination.

### F3. High — One fairness cursor shared by selection classes does not prevent aged-lane starvation

**Section:** §5.2, device selection. `design.md:224-240`.

**Contradicting evidence:** V3 specifies one persistent round-robin cursor applying to **every** selection class, then concludes that two continuously aged background lanes alternate exceptions (`design.md:233-237`). Foreground selections can change that shared cursor between aging exceptions, so the conclusion does not follow. D2 requires background progress under saturating foreground load, not just the foreground passed bound (`D2.md:201-209`).

**Concrete K=1 trace:** Put the lanes in circular order `B1, B2, F`. Both background heads are aged, and F has a pending foreground wait. Start the shared cursor at B1.

| Step | Selection | Shared cursor after selection | Consequence |
|---|---|---|---|
| 1 | Aging exception selects B1 | B2 | The pending F wait is marked passed. B2 remains aged. |
| 2 | Foreground selection scans to F | B1 | The marked wait is seated; aging suspension can end. |
| 3 | While F runs, B1's next bounded sub-batch ages; another foreground wait queues | B1 | Keep each run finite, with F's duration longer than the aging threshold. |
| 4 | Next aging exception again selects B1 | B2 | B2 is bypassed again. Repeat steps 2-4 indefinitely. |

All queues can remain bounded, all runs can finish, one producer per background role suffices, and every foreground **native wait** is passed at most once. Nevertheless B2 starves. The cursor is persistent; the problem is that another selection class repeatedly moves it back.

**Smallest fix:** Keep an aged-selection cursor/service history that foreground and ordinary-background selections cannot reset. For example, use separate persistent cursors per selection class, with FIFO within each producer/client queue. Define what happens to a marked wait that cannot obtain its growth entitlement or a viable CPU path: finite admission alone does not make an unseatable wait drain. Add the trace above to the deterministic scheduler obligations.

**What did survive the attack:** With atomic device grants and a passed set retained until the marked waits are seated/cancelled, I found no second aged overtake of the **same continuously pending native wait**. At K=2, an aged grant marks all then-pending foreground waits before a second grant is selected. A foreground request with two native waits can be passed once on each; v3 now explicitly permits and reports that total of two (`design.md:225-232`). That is no longer a contract violation. Fairness among background lanes is the independent failure.

### F4. High — Automatic group sealing is not C1's actual-exit rule

**Section:** §4.1 and §4.4, work identity and Engine retention. `design.md:103-108,154-175`.

**Contradicting evidence:** V3 says “A group seals when its submissions have all terminated” (`design.md:164-168`). C1 says the retained owner is released **after the group is sealed and all accepted children exit** (`C1.md:1376-1387`). Sealing is the producer/parent's declaration that no more children will be accepted; it is not inferred from temporarily having zero active children. C1 explicitly permits sequential/concurrent calls sharing a work handle. `EngineFutures.java:95-109,112-129` separately accounts for actual exit and result completion.

**Concrete failure:** A parent call submits S1, consumes its result, then plans S2 under the same call/fanout ownership. Between those submissions, all currently accepted submissions have terminated. V3's rule seals the group at that point. Either S2 is incorrectly refused because the group is sealed, or it is allowed after the group's retained owner was eligible for release. A parallel fanout can hit the same gap when S1 exits while the parent is still dispatching S2.

**Smallest fix:** Preserve C1's existing local per-call/fanout task-group lifetime unchanged. Register a retained child before a send can become accepted or ambiguous; release that child only on its own proven terminal condition. Seal only on the existing parent/group-close path, and release the group owner only when **sealed AND all accepted children have actually exited**. The work ID may name a cancellation group without becoming the identity or lifetime of one global `EngineTaskGroup`.

Per-submission IDs, retrievable terminal records, and treating transport detach/cancel acceptance/execution termination as independent facts are real improvements. The automatic-sealing sentence undermines their integration with C1.

### F5. High — The per-work watermark neither implements permanent group cancellation nor bounds fence history

**Section:** §4.4 cancellation and §4.5 bounds. `design.md:169-185`.

**Contradicting evidence:** `cancel(workId)` is promised to fence every current and future submission for that work ID (`design.md:169-171`). The described mechanism rejects only submissions with sequence **below** a per-work watermark (`:171-174`). No terminal cancelled-work state, no no-further-sends proof, and no retirement rule for distinct work-ID watermark entries is specified. The context/submission definitions also do not define the sequence's domain or its relationship to sealing (`:103-109`). C1 preserves real accepted work through cancellation (`C1.md:1582-1589`); rejecting stale linkage must not silently mint replacement work (`lane-F design.md:582-593`).

**Concrete correctness failure:** Work W is cancelled when the last known submission is 7, yielding watermark 8. A racing sender transmits sequence 8 for W. It is not below the watermark, even though the contract says all future W submissions are cancelled. Keeping a separate permanent `cancelled(W)` flag would fix this part, but that flag and its lifecycle are absent from the stated mechanism.

**Concrete retention failure:** Cancel W1 through WN, acknowledge all terminal submission records, and leave all queues empty. Preventing a delayed submission for any of those work IDs from executing still needs N independent watermarks/tombstones. A per-work watermark replaces per-submission history; it does not provide a work-count-independent bound. The queue/backlog caps and `ack(submissionId)` do not retire these records.

**Smallest fix:** Define the cancellation/fence domain and retirement proof. A cancelled work ID must reject **all later submissions**, not merely an older sequence prefix. Keep bounded cancelled-work records under an explicit admission budget until the producer is sealed and old messages can no longer be admitted; or use a client-incarnation-wide ordered namespace with a justified retirement watermark and bounded out-of-order exceptions. Fence an old incarnation before forgetting its history. Existing `RUNNING` submissions remain accepted work awaiting actual termination, never a new pre-admission `REFUSED` shortcut that releases their Engine retention.

This is a remaining instance of the original retained-state/cancellation obligations, not a request for an Engine-side second admission authority.

### F6. Medium — The admission equation still permits literal resident-ticket double charging

**Section:** §5.3 ticket states, conservation, and admission. `design.md:257-298`.

**Contradicting evidence:** The ticket lifecycle includes `RESIDENT` and `RETIRING` before `RELEASED` (`design.md:260-278`). The formula subtracts both `Σ resident` and `Σ reserved(all non-released tickets)` (`:291`). Read literally, a resident ticket is still non-released and its materialized bytes are counted twice. The conservation prose intends a disjoint transfer (`:280-289`), but the equation does not define the monetary/byte balance of a ticket after that transfer. NVML's used/free figures already reflect materialized allocations (`NvmlService.java:184-190`).

**Concrete failure:** A session has a 7 GiB entitlement: 2 GiB weights, 4 GiB arena allowance, and 1 GiB workspace allowance. After warm-up, 3 GiB is resident and 4 GiB remains unmaterialized. Its correct total ledger charge is 7 GiB. Retaining the original 7 GiB amount on the non-released load ticket and adding 3 GiB residency produces a 10 GiB charge, causing unnecessary refusal or eviction. This reading contradicts the intended conservation rule rather than proving that every conceivable implementation would double-charge.

**Smallest fix:** Define charged **balances**, not a sum over lifecycle states:

```
charge = residentBytes + unmaterializedEntitlementBytes
available = total - external - sum(charge) - margin
```

A ticket keeps its identity/history after materialization, but its materialized bytes leave the reserved balance. For a fixed enforced entitlement, assert across load, seating, run completion, shrinkage, and retirement:

```
resident + sessionRunEntitlement + seatEntitlement = entitlement
```

Use an analogous partition for a pending/partially materialized llama ticket. Return persistent growth to residency and reduce the remaining arena entitlement; reverse the classification when observed arena contraction is established. Only confirmed retirement removes the entitlement. Keep conservative unattributed usage separate from this internal partition.

The arena/workspace distinction is grounded in separate options (`SessionOptionsApplier.java:99-107,119-123`). V3 now retains workspace and atomically transfers the run entitlement with the seat; the old missing-workspace failure is fixed in principle. This finding is about an inconsistent accounting definition, not a demand that observations prove a hard native-memory upper bound.

## 3. Round-one response ledger: all fifteen entries

The v2 column below is **attributed to the preserved earlier review**, `review-2-findings.md:211-231`; it is not a new certification of the absent v2 text. The v3 column is this review's independent result. “Resolved” means the written contract addresses the defect, not implemented or tested.

| R1 finding / response row | Recorded v2 disposition | Independent disposition of supplied v3 |
|---|---|---|
| **1. Set routing and write binding** — `review-1-response.md:20` | Partial (`review-2-findings.md:217`) | **Resolved at the full-metadata contract level for H1/H2.** Requests carry set handles; serving/candidate/replay routing is explicit; reads bind to the pinned searcher; field writes and window accumulators bind to generation, descriptor, and source revision. See `design.md:101-102,113-122,359-382`. Shared operation/lifetime policy remains H2b's. |
| **2. Compatibility versus execution identity** — `:21` | Partial (`:218`) | **Resolved for fully described generations; explicitly partial for legacy ones.** Canonical inputs and actual execution identity are now distinct (`design.md:126-144`). Unknown metadata is not known-absent and does not pass the binding gate. The BGE-M3 migration is still a real deferred dependency, not a completed verification (`D1.md:688-689`). |
| **3. Claiming after llama activation** — `:22` | Partial (`:219`) | **Partial: F1/F2.** Reserve-before-spawn, retry settlement, live-ticket transfer, and single tenancy are specified; absence/replacement recovery still admits counterexamples (`design.md:265-278,311-321`). |
| **4. Learned footprint as lifetime maximum** — `:23` | Partial (`:220`) | **Epistemic overclaim resolved; accounting definition still open in F6.** Observations are advisory, profile keys include runtime/device/allocator shape, and tightening requires an enforced cap. Arena remainder plus workspace is retained/transferred (`design.md:280-307`). No hard native upper bound is claimed. |
| **5. Every Engine destructively owns the shared host** — `:24` | Partial (`:221`) | **Deferred, not resolved for sharing.** H2 is ordinary unshared ownership; H2b's supervisor ownership protocol remains open (`design.md:403-416,526-536`). CPU-only mismatch fallback is now correct. Unshared replacement still has F2. |
| **6. Local aging presented as a device guarantee** — `:25` | Single native wait resolved; broader claim open (`:222`) | **Partial: F3.** Atomic device scope and the per-native-wait passed bound are sound; v3 clarifies the n-sub-batch bound. The shared fairness cursor still permits background starvation (`design.md:224-246`). |
| **7. Producer count/K do not bound admitted or retained work** — `:26` | Partial (`:223`) | **Partial: F5.** Queue, byte, CPU, producer, frame, and terminal-record limits are named (`design.md:154-185`); per-work fence retention is not bounded by those limits. Shared epoch replacement is deferred to H2b. |
| **8. HTTP cancellation releases C1 ownership early** — `:27` | Partial (`:224`) | **Partial: F4/F5.** Per-submission completion and retrieval solve the work-ID-only ambiguity. Automatic sealing and the incomplete fence rule remain incompatible with the required lifetime semantics (`design.md:146-178`). |
| **9. Double-counted device allocations** — `:28` | Partial (`:225`) | **Partial: F6.** Total-based accounting and in-flight attribution are the right approach, but the sum over all non-released tickets must become a disjoint balance partition (`design.md:280-298`). |
| **10. Eviction sacrifices foreground retrieval** — `:29` | Partial (`:226`) | **Resolved as a policy contract.** Classification now uses the serving generation's search plan and native lease counts, not background producer tokens; the coverage row includes dense, sparse, and reranking effects (`design.md:323-331`). This permits measured degradation; it does not promise zero regression. |
| **11. Idle exit/adoption and attachment lifetime** — `:30` | Partial (`:227`) | **Partial for H2: F2; deferred for H2b.** A locked idle transition and heartbeats are not yet an atomic adoption claim plus replacement-exclusion protocol (`design.md:405-416`). Shared renewal/expiry/fencing requirements are listed but not designed (`:528-535`). |
| **12. Tokenizer-only window count** — `:31` | Resolved (`:228`) | **Resolved.** `WINDOWS(0,0)` returns per-text counts without a device seat and explicitly preserves prefix/window semantics (`design.md:113-116`; `EmbeddingService.java:530-554`). |
| **13. Port-first cost and D1 dependency** — `:32` | Partial (`:229`) | **Resolved in the supplied v3 wording.** The cost claim is withdrawn; D1/D2 require no new host-port types or prerequisite agreement, and H1 owns adaptation/admission changes (`design.md:69-73,387-391,482-485`). This should not be credited retroactively to v2. |
| **14. Blanket byte equality versus baseline noise** — `:33` | Resolved in principle; frame metadata open (`:230`) | **Resolved at contract level.** Captured payload exactness excludes variable timings/transport IDs; numeric and workflow checks are separate; masked hit lists remain outside the verdict (`design.md:490-497`). |
| **15. Unnamed instruments/isolation prerequisites** — `:34` | Resolved as work plan (`:231`) | **Resolved as a work plan only.** H0/H1 name isolation, cleanup, attribution, encoder-warm and query timing work (`design.md:431-437,499-509,520-522`). No speedup, memory saving, or coexistence run is established by this review. |

## 4. Results of the requested mechanism attacks

### 4.1 Reservation ledger: critical section, double counting, failed launches, and K

A single authority for grant, entitlement transfer, reconciliation, observation epoch, and release is a sound target (`design.md:291-298`). The critical section must protect **ledger state**, not merely two reads of `free`; otherwise competing admissions still spend one availability observation twice. In-flight materialization must consume an existing entitlement rather than appear both as unowned external memory and as a full new reservation. F6 supplies the exact accounting definition missing from the current formula.

The declared load/run model now accounts for workspace at K=2. With one 1 GiB arena entitlement and 0.25 GiB workspace allowance per role, two running roles need both 1.25 GiB entitlements, not two 1 GiB arena-only tickets. Transfer is not a second charge. This closes the previous review's explicit omitted-workspace example (`review-2-findings.md:19-29`; `design.md:280-289`).

However, retaining every composed session's full arena/workspace entitlement is conservative even at K=1. Lower K limits concurrent runs; it does not automatically make the sum of retained per-session entitlements smaller. Do not attribute memory-admission improvements to K alone. The policy can tighten later reservations by lowering the enforced cap, as v3 already says (`design.md:300-307`).

I do **not** infer that device-wide NVML readings can identify exact per-session weights, arena, workspace, and unrelated external changes. The draft correctly calls attribution provisional and charges unknown usage conservatively (`design.md:250-255`). A lock does not freeze unrelated GPU processes. This is a boundary of the claimed model, not another claim that the draft promises a hard native-memory maximum.

Failed-launch recovery remains F1. Fresh memory observations cannot prove that an unregistered child will not allocate later. Host replacement also needs F2's admission barrier, particularly while independently owned llama remains alive.

### 4.2 Device grants: passed twice versus starvation

For one continuously pending native wait, the atomic passed-set rule is coherent. Two sub-batches in one outer request can be overtaken once each, and v3 expressly states that n-sub-batch bound. It no longer claims blanket once-per-outer-request service.

The separate fairness claim fails: F3 gives a finite-queue, finite-run starvation trace under K=1. An isolated test of one passed set or of foreground precedence will not expose it. The required trace must let foreground grants mutate the actual cursor between aging exceptions, with two background lanes remaining eligible. K=2 simultaneous grants should additionally verify that the first aging exception updates the host-wide passed set before the next grant is selected.

### 4.3 Ownership scenarios

| Scenario | Unshared H2 | Shared host requested in the original brief |
|---|---|---|
| **An Engine quits** | It owns its host and may stop it on QUIT/UPGRADE; RESTART/HANG preserve it for adoption (`design.md:405-409`). Actual identity-safe terminal cleanup remains the existing ownership contract. | Deferral avoids claiming a solution. H2b still needs Engine quit to detach without destructively owning another Engine's host. |
| **Supervisor crashes** | Ordinary manifest recovery remains useful, but no new host may bypass a live prior device authority or surviving llama entitlement. F2 identifies the necessary local exclusion/barrier. | §13 names host-lifetime exclusion, durable supervisor identity, recovery before launches, and fail-closed unknown identity. Those are requirements, not an implemented ownership protocol. |
| **Config mismatch** | Retire an identity-matched owned child only through confirmed-stop rules; otherwise CPU-only adapter or UNAVAILABLE (`design.md:333-340`). Never silently create another CUDA adapter. | CPU-only/UNAVAILABLE is an appropriate refusal path. A client must not retire the shared host merely because its desired configuration differs. |
| **Two host versions** | Version in the config hash detects mismatch; it does not establish exclusion. F2's drain/replacement trace can involve different versions. | A per-user/device exclusion key must span versions and model directories; version belongs in compatibility, not in a key that permits simultaneous independent GPU owners. §13's lock requirement points in the right direction. |

The shared-host obligations are not “resolved by removing the code path.” They are deliberately deferred. Before H2b is designed, keep the original requirements alongside §13's recovery requirements: attachment-only Engine manifests; one destructive supervisor owner; quit/detach and shutdown/upgrade policy; version/config refusal; atomic attach versus drain; renewal/expiry fencing; shared-set references; client-count-independent caps; and transfer of live llama tickets across incarnations. Some of these are present elsewhere in v3, but §13 alone is not a complete shared-host protocol.

### 4.4 Attachment/draining state machine

For the supplied unshared draft, the named race is **SERVING probe -> idle DRAINING transition -> successful identity recheck/adoption**, followed alternatively by **HOST_DRAINING refusal -> replacement allocation before old exit**. F2 covers both.

For the deferred shared design, an expiry decision must validate the lease revision under the same authority as renewal, as §13 now requires. An earlier heartbeat read cannot authorize expiry after a later renewal. Replacement of an attachment cannot itself prove that predecessor native work has exited. Those points remain H2b design obligations; no shared-host acceptance claim can be made from their presence in a checklist.

### 4.5 The three completion facts against C1

The intended separation is correct: a transport detach changes delivery; cancel acceptance records intent; execution termination establishes the end of accepted native/postprocessing resource ownership (`design.md:148-175`). Normal success need not pass through either cancellation or detachment. Each submission requires an independent retained lifetime, and a lost response can be recovered from its terminal record.

That still must plug into C1's **explicit seal plus actual-exit conjunction**, not the automatic seal in F4, and into a cancellation fence that covers future/in-transit submissions as in F5. `REFUSED` must mean never admitted to execution, not merely cancelled after admission. Result transport records may retain copied payloads under separate bounds without keeping a native lease or device seat alive; native result ownership must actually have ended (`SessionHandle.java:191-228`).

The scheduler/retention integration must cover both native entry methods, `Lease.run` and SPLADE's `Lease.runPinned` (`SessionHandle.java:202-223`), rather than only instrumenting the former method name. This is a required implementation coverage point, not evidence that a supplied host implementation already bypasses the gate.

### 4.6 Writes, partial windows, and D1-9 replay

The supplied source shows why generation identity alone was inadequate. Backfill captures content first (`CombinedEnrichmentBackfillOps.java:408-444`), accumulates field updates over several encoder calls/cycles (`:854-899`), then performs a combined read/modify/write batch (`:1200-1223`). The current accumulator identifies content with length and Java hash, not a generation/model/window descriptor (`WindowedEmbedProgress.java:54-107`). V3 now explicitly requires stronger binding for that accumulator and per-field binding for mixed outputs (`design.md:365-376`). That is a substantive contract repair, not just a returned model tag.

The important implementation interpretation is that text is captured with **its own indexed source revision**. An old stored text must not be relabelled with a newer `jobs.content_hash` read after acceptance of an edit. The conditional field-and-marker write must compare the captured binding at the actual application boundary. A stale vector must not mark a newer source revision complete. No separate preflight-only check suffices.

The acknowledgement distinction also matches the source. UPSERT replay enqueues each source command (`KnowledgeServerMigrationOps.java:806-814`) and clears the switch buffer only if replay's `allApplied` remains true (`:834-838`). That is not proof that all eventual embeddings have been applied. D1-9 separately compares Green's indexed source hash with the current accepted hash at activation (`D1.md:444-454`). V3's two named acknowledgements preserve that distinction (`design.md:377-382`). I found no additional contradiction in the stated write contract, but no conditional-write implementation or gate result is established here.

### 4.7 Exactness versus the lane-F baseline

V3's exactness boundary now addresses the original contradiction. It requires byte-identical round-trip of a **captured payload with fixed metadata**, not byte-identical fresh execution or identical wall-clock metadata (`design.md:490-497`). Dense epsilon, sparse-ID/weight rules, and rerank tie rules must be declared before captures under pinned execution shape. Workflow comparisons keep their existing classification/noise mask, and policy changes get separate rows.

The supplied baseline write-up explicitly says five of twelve query hit lists are outside the verdict and a real regression in them could be masked (`context/evidence/README.md:48-58`). Thus workflow PASS is not proof of equality on those lists. Contract round-trip equality is not numerical equivalence between execution variants, either. V3 now keeps those propositions separate. This review did not rerun or certify the baseline gate.

## 5. Cross-lane judgment and the genuinely minimal D1 contract

**The supplied v3 now has the right dependency direction.** Decision 14 explicitly says D1 proceeds against its existing local contracts, without inference-port types, and H1 adapts after batch 4 (`design.md:482-485`). This corrects the v2 wording that the preserved earlier review criticized as requiring D1 to be “written against” section 4 (`review-2-findings.md:189-199`).

D1 already specifies local `EncoderSet` ownership, serving/candidate resolution, per-runtime identity and consumer suppliers (`D1.md:506-532`); native retirement and retained timeout behavior (`:534-553`); and a free/total supplier with beside/in-place selection and A recomposition on refusal (`:555-577`). Batch 4 contains exactly D1-12/13/14 (`:734-741`). These do not need a transport, remote set handle, `SubmissionId`, `ProducerToken`, host token, attachment lease, reservation RPC, or acknowledgement protocol.

The genuinely minimal new demand on D1 is therefore **zero new source types or gate prerequisites**. Preserve those already-documented local semantics. H1 owns the adapter and every change required by its new admission authority. Agreeing future operation shapes may be useful coordination, but it cannot be a dependency of D1 completion.

D2 likewise proceeds with its own profiles and gate (`D2.md:154-169,187-209`). H1 must coordinate replacement of the local scheduling implementation when both changes land; it must not turn that integration into a retroactive prerequisite for D2.

V3 also correctly says H1 **replaces** D1-14's admission implementation rather than merely feeding it another memory reading (`design.md:387-391`). Its outward contract must still preserve:

- beside only while the serving set remains a usable A during B composition;
- in-place RELOADING/text-search behavior and immediate A recomposition after B refusal;
- failure reasons and recovery if A recomposition also fails, plus the mode/footprint/device-ceiling observability D1 uses for its floor scenario.

Evicting/retiring the serving set must not be relabelled as a beside success merely because a ticket now fits. This is an integration obligation derived from the preserved outward contract, not a reason to add ledger work to D1. Keep the BGE-M3 fingerprint/rendering migration on its own declared follow-up (`D1.md:688-689`).

### Scope and sequencing judgment for the actual v3

Deferring multi-client sharing and making H2 a decision gate are reasonable. H0's isolated encoders-off Engines do not require a shared GPU host, while the audits explicitly do not establish encoder-warm latency, merged-Engine RSS, co-resident VRAM, or real CUDA fault behavior (`context/audits/audit-4-multi-instance-constraints.md:81-91`; `audit-2-session-gpu-lifecycle.md:132-145`). That supports measuring before committing, not claiming that most of the benefit has already been measured.

H1 has independent value in operation isolation, binding, local admission, and reserve-before-llama allocation. The in-process implementation can satisfy submission lifetimes with local C1 retention. Do not make persistent HTTP outcome delivery, wire-frame machinery, or a shared-client supervisor prerequisite to H1 just because H2 may eventually need them. Conversely, F2 shows that **device exclusion during replacement is not exclusively a shared-host concern** and cannot all be moved to H2b.

These six findings are materially fewer than the sixteen in the preserved round-two review. They call for targeted contract corrections and adversarial implementation proofs, not another broad architectural redesign.

## 6. What was verified sound

The operation-shaped port remains the right seam: raw ORT leases are not a portable process boundary (`SessionHandle.java:179-223`), while the consumer audit supports operations rather than exporting tensor/session objects (`context/audits/audit-1-consumer-surface.md:67-89,129-137`). D1's local encoder sets are a suitable substrate, not competing work to replace.

Explicit per-call set routing, pinned-reader comparison, source-bound conditional writes, and accumulator invalidation now express the important generation invariants. Representation and execution identity are distinct, and the legacy partial-binding limitation is acknowledged rather than counted as a pass (`design.md:126-144,359-383`).

The revised memory model retains arena remainder **and workspace** across warm-up and run transitions, reserves before allocation, treats unknown usage conservatively, and explicitly declines to claim a hard native upper bound. The accounting and recovery defects above do not invalidate those corrected principles (`design.md:250-307`).

Atomic passed-set suspension is coherent at one native wait. The defect is fairness across classes/lanes, not the existence of K>1 or sub-batching itself (`design.md:220-246`).

Per-submission completion and retrieval are the right repair for work-ID-shaped completion. Transport loss must not release native execution ownership. D1-13, not this lane, owns session retirement and outstanding CPU/GPU lease accounting (`design.md:146-175`; `D1.md:534-553`).

Ordinary unshared child ownership fits the lane-F manifest handoff; shared attachment ownership is rightly distinguished from destructive ownership. CPU-only mismatch fallback now prevents the specific second-GPU-adapter bypass, provided it disables lazy GPU retry as specified (`design.md:333-340,403-427`).

`WINDOWS(0,0)`, query-serving eviction classification, separate retrieval-coverage measurement, and the three exactness layers are all substantive corrections (`design.md:113-116,323-331,490-497`). The request-time timing/encoder-ready/isolation work is named rather than assumed (`:499-522`).

Finally, the current explicit **no new D1/D2 prerequisites** wording is correct. None of the unresolved H1/H2 mechanisms should delay lane F.

## Appendix A. Disposition of the additional round-two response ledger in this later attachment

This appendix prevents the supplied v3 response's “closed” labels from being mistaken for this review's endorsement. It is additional to the fifteen-entry round-one ledger the user requested.

| R2 finding | Independent current disposition |
|---|---|
| 1 — Workspace/conservation | Workspace omission fixed; precise charged-balance definition still F6. |
| 2 — Crash-safe intent/expiry | Pending intent added, but unbound-process absence and replacement admission remain F1/F2; shared epoch transfer remains deferred. |
| 3 — Per-call completion | Per-submission records/retrieval fixed; C1 sealing integration remains F4. |
| 4 — Old/new execution overlap | Shared replacement barrier deferred. In H2, predecessor Engine death must not release host execution resources; see F2 and §4.5 above. |
| 5 — Supervisor recovery | H2b requirements acknowledged but not implemented; the exclusion subset needed for unshared replacement remains F2. |
| 6 — Mismatch GPU fallback | Resolved in the written policy: CPU-only with lazy retry disabled or UNAVAILABLE. |
| 7 — Canonical representation inputs | Resolved for full descriptors; legacy binding deliberately partial, migration unproved. |
| 8 — Source revision/accumulators/replay ack | Resolved as a contract; application-boundary conditional-write proof still required. |
| 9 — Materializing reservations | In-flight attribution principle fixed; disjoint charged balances must be explicit, F6. |
| 10 — Bound unit and aged fairness | Unit fixed; common-cursor aged fairness fails F3. |
| 11 — Mutable process hash/set lifecycle | Process hash excludes sets; immutable acquire/release lifecycle is now stated. Retirement must still honor D1-13, including explicit candidate retirement. |
| 12 — Retained fences/results | Terminal-record/frame bounds and acknowledgement added; per-work fence retention and cancellation semantics remain F5. |
| 13 — Eviction classification | Resolved at policy level: query-serving obligation, actual leases, retrieval-coverage gate. |
| 14 — Exact timing metadata | Resolved: captured payload boundary excludes variable timings/transport IDs. |
| 15 — Minimal D1 dependency | Resolved in current decision 14 and §6.3; do not credit this wording to absent v2. |
| 16 — Wrong/missing sources | Correct implementation/helpers now supplied under path-preserving paths. The stale flat directory remains, and the requested v2 target itself is absent. |

## Appendix B. Source-path index for this report

All paths below are relative to `context/src-tree/`.

| Citation basename | Repository-relative source |
|---|---|
| `ManagedChild.java` | `modules/app-api/src/main/java/io/justsearch/app/api/runtime/ManagedChild.java` |
| `LlamaServerOps.java` | `modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java` |
| `RuntimeActivationService.java` implementation | `modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java` |
| `EngineFutures.java` | `modules/core/src/main/java/io/justsearch/core/execution/EngineFutures.java` |
| `NvmlService.java` | `modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java` |
| `SessionHandle.java` | `modules/ort-common/src/main/java/io/justsearch/ort/SessionHandle.java` |
| `SessionOptionsApplier.java` | `modules/ort-common/src/main/java/io/justsearch/ort/SessionOptionsApplier.java` |
| `EmbeddingService.java` | `modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingService.java` |
| `CombinedEnrichmentBackfillOps.java` | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java` |
| `WindowedEmbedProgress.java` | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/WindowedEmbedProgress.java` |
| `KnowledgeServerMigrationOps.java` | `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/KnowledgeServerMigrationOps.java` |

The source index is a navigation aid, not a claim that all behavior in these files was executed or that every relevant repository file is present. The audits' “Could not establish” sections remain the boundary for unobserved behavior and omitted runtime evidence.
