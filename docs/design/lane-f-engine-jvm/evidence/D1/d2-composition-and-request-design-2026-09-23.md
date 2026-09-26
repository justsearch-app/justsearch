# D2 composition, fairness and durable request design

Selected 2026-09-23 against runtime code `22800c842`; documentation HEAD was
`09fdbe769`. This resolves implementation choices, not production acceptance.
The [publication](publication-and-lifetime-design-2026-09-23.md) and
[generation/cursor](generation-native-cursor-design-2026-09-23.md) contracts apply.

## 1. One real composition and explicit ownership

Do not rename the mocked EngineTestHarness into a production library. Its
OperationStore and AttemptRunner are Mockito objects (`EngineTestHarness.java:103`);
the real ownership currently lives in HeadlessApp's composition around lines1078–1101.
Extract that wiring into a production EngineComposition in app-engine. It creates
the real operations store/runner/settings coordinator and EngineRoot, using the
same process-resource/publication-lock owner in process and embedded entry points.
HeadlessApp remains the process host: it owns listeners, supervisor/exit authority,
instance lock and process termination. Embedded composition has none of that exit
authority. Do not copy a second settings or admission implementation into the facade.

Add the explicit non-transitive app-engine implementation dependency on
app-observability for its public SqliteOperationStore/OperationAttemptRunnerImpl
construction, rather than relying on app-services' transitive implementation
classpath. Public facade results remain app-api/core contracts. Check the actual
constructor collaborators during extraction and keep their implementations inside
the root; regenerate the module graph with this wiring change. No reverse edge
from a composed module to app-engine is authorized.

The default facade owns everything it creates. A typed resource input may borrow
an existing process-resource owner; borrowing never transfers close authority.
Keep ownership explicit in the construction API, not inferred from null values or
catching AlreadyClosedException. On partial construction failure close only owned,
successfully constructed dependencies in reverse dependency order; refusal retains
their owner for bounded recovery. Closing the embedded facade uses the same D1
quiescence rules and reports refusal to its caller; it must never halt the host JVM.
The process host alone chooses normal exit versus hard termination.

EngineRoot currently allocates DefaultEngineProcessResources in a field initializer
and derives its policy/admission/executors/components there (`EngineRoot.java:61–76`).
Replace that implicit allocation with a required constructor resource input;
derive registrations after that input is established. EngineComposition allocates
the owner before ConfigStore and passes it through every process/embedded factory.
An old convenience factory may delegate to this single allocation path, never
allocate a second hidden owner. Root's restartable index close still does not
close process resources. Test object identity across ConfigStore, registry, Head
and Worker and verify partial construction does not strand an unseen owner.

Borrowing process resources is the host handing its one Engine composition the
resources it will close after that composition quiesces; it is not permission to
create independently closing roots over the same admission/registry authority.
Do not publish an embedded profile into ConfigStore.GLOBAL or replace another
composition's static configuration. The facade's paths use its injected owners;
retire remaining global fallbacks on those paths and test two sequential profile
lifetimes for leaked globals, handles and operation state.

Profiles remain stores={real,ephemeral} × inference={full,encoders,none}. Named full
is real/full, bench is real/encoders, verification is ephemeral/none. Verification
has API/index READY and both encoders and generative ABSENT. It performs real
storage/operation work, with no ORT sessions or llama-server children. ABSENT is
intentional composition, not a failed component; readiness projections use that
same registry fact. The under-five-second reference-machine test remains required.

Use one owned temporary workspace for ephemeral composition, ordinary file-backed
SQLite stores within it, and the existing ephemeral Lucene construction. Include
operations/settings and any newly discovered path-owning dependency, not only the
four SQLite stores listed in the old stage. Resolve paths through composition,
never mutate global user configuration or fall back to the real data directory.
Delete only after all handles close; failed close retains the workspace and names
the reason. No competing in-memory store implementations or per-store temp-file
registry are needed. Test real mutations, failed construction, close refusal,
retry and absence of writes outside that workspace.

jseval bench and the CLI indexing consumer must compose through this production
facade. Tests may retain a narrowly named mock adapter where deliberate unit
isolation is useful; migrate real-composition tests to the facade and retire the
misleading old harness role. Do not turn mock success into library acceptance.

The component map projects D1's registry, executors and retained-state owners,
captured coherently. Build-time RegistrySnapshotExporter describes declared shape;
it cannot assert live counts without a running composition. Share the schema and
declaration authority, while runtime endpoints project actual live instances.
Coverage compares against actual wired registrations, including intentional
profile absence, rather than a duplicated hand-maintained list.

## 2. Enforce architecture instead of restoring a size ratchet

The canonical discipline-gate reference records removal of size/count ratchets
(frontmatter and lines18–23,72–76). Replace D2-3's new Java line-count baseline with
existing module dependency/LayeringEnforcement checks and a focused composition
boundary test: no product algorithms or reverse dependencies introduced into the
wiring root. Verify a forbidden dependency makes the relevant check fail. Record
the actual fan-in with the existing module-deps generator; consumers and tests
must follow allowed dependency direction, not a stale three-module hardcoded list.
This changes the mechanism, not the requirement to keep app-engine a wiring module.

## 3. Fairness belongs to each native handle

The two levels mean foreground/background queues at each NativeSessionHandle,
not another global GPU semaphore. Preserve the existing GPU memory/admission
arbiter independently. SessionHandle currently has no urgency argument
(`SessionHandle.java:51,78`); thread an immutable request scheduling input from the
admitted EngineContext through encoder calls to acquisition. Map urgency/deadline/
cancellation at the owning upper-layer seam, using a narrow ort-common value type
if necessary to preserve dependency direction. A worker thread name or ambient
ThreadLocal is not authority. Lifecycle releaseGpu/retire enters exclusive owner
maintenance after preventing new relevant acquisition, outside native calls and
accounting monitors; it does not masquerade as a foreground user request.

Maintain FIFO within each queue and one issued GPU execution lease per handle.
When the oldest background waiter has waited three foreground seatings or two
seconds, seat it if no previously passed foreground waiter remains. Snapshot the
current foreground waiters as passed. New foreground arrivals do not join that
cohort and cannot jump ahead of it. Cancellation/deadline removes a waiter from
both queue and cohort exactly once. Once the cohort drains, aging can resume.
Age belongs to the waiting background submission and resets on its seating, not
on every foreground arrival. Inject a monotonic clock in deterministic tests.
Retirement permanently rejects acquisition, including a waiter selected just
before retirement; selection is not proof of an issued native lease.

One background producer token is owned per encoder stage **per EncoderSet**.
Blue and Green have different handles; their tokens are distinct, while both
consume the existing process admission/memory envelope. Multiple workflows on
the same set feed its existing bounded serial producer rather than minting a
second token. A token permits one outstanding background batch, including a
waiting batch; do not release it at submission before native completion. Transfer
producer ownership only after the predecessor stops. This preserves the claimed
per-session bound without inventing a process-global single indexing loop.
The concrete background propagation owner is the EncoderSet's IndexingLoop/
BackfillScheduler: its stage contexts (`BackfillScheduler.java:633–818`) carry the
set-owned producer token plus scheduling input minted from the existing admitted
durable background context. Bind that input through embedding, SPLADE, NER and
other actual encoder adapters to SessionHandle; map foreground input at the query
entry. Do not infer background from a missing foreground context. Inventory all
parameterless acquire/acquireCpu callers, including OnnxEmbeddingEncoder and
SpladeEncoder, and remove their context-free execution escape path. Maintenance
calls use the explicit owner path. Test both foreground and background through
the real scheduler/encoder adapters in addition to fake-gate policy tests.
CPU execution keeps its existing concurrency policy but uses the exact-instance
lifetime accounting selected by D1-13; a GPU fairness gate must not accidentally
serialize all CPU calls or claim a new cross-device throughput guarantee.

Tests must prove the whole-wait one-overtake bound, cancelled cohort removal,
background progress under bounded foreground admission, token handover/refusal,
and generation-specific tokens. E measures latency/throughput before final tuning.

## 4. Durable writes bind to an actual writer lifetime

Place indexAndReturn and identity-based delete-and-acknowledge on KnowledgeClient,
through the existing Worker ingest ownership. IndexingService is the watched-root
management facade, not the write port. Carry EngineContext and existing accepted
operation identity; ordinary submitBatch continues to mean queue acceptance.
No HTTP/MCP endpoint is added by this item. Register actual port consumers.
Extend the existing in-process chain together: KnowledgeClient → IngestServiceCalls
(`app-services/worker`) → WorkerIngestCalls (`app-engine`) → WorkerIngestService
(`worker-services`), then the bound RuntimeSession mutation owner. Put typed request/
receipt values in the existing shared port-contract layer, with no Worker-native
objects leaked to Head. The proto is currently DTO-only; do not restore RPC services
or gRPC stubs. Both new calls enter the same D1 mutation-routing fence as ordinary
ingest/delete; a shortcut from Head directly to Lucene is not permitted.

A write ticket contains generation id, non-reused RuntimeSession/writer epoch,
Lucene sequence number and stable document identity/accepted projection revision.
An epoch is process-local and is never reused to acknowledge a recovered request;
C2's durable operation identity and committed result handle restart reconciliation.
Do not compare bare sequence numbers across writers or runtime reopenings.
WritePathOps returns the actual mutation sequence, including deletes. CommitOps
publishes a successful covering commit watermark from that same writer, alongside
elapsed time. It must use the sequence coverage established by the commit operation,
not sample a later max-completed sequence that may include uncommitted writes.
Use one RuntimeSession-owned read/write mutation/commit barrier to make this
coverage explicit. All IndexWriter mutation paths take its read side for the actual
mutation and sequence assignment; commit takes the write side, waits for those
mutations to finish, records the completed mutation watermark, and commits before
publishing that watermark as durable. No mutation can cross that exclusive interval.
The existing CommitOps-only monitor does not provide this boundary; route direct
writer updates/deletes and maintenance mutations through the shared owner as well.
Keep write operations concurrent under read leases; this is not a new durable log.
Accept the bounded scheduling plus actual fsync stall for new mutations and measure
it at E. Do not infer a wall-clock fsync bound or weaken coverage to improve a result.
Lock order is generation routing/lifetime acquisition, then mutation barrier, then
the short watermark/waiter monitor. Mutation bodies never acquire runtimeSwapLock
while holding the barrier. Commit releases the barrier before notification or any
wait for client work; activation finishes Green commit before publication locking.
Register waiters and observe the committed watermark atomically so completion
between write and waiter registration cannot be lost. A failed/ambiguous commit
never advances the successful watermark by inference from NRT visibility.

nrt awaits the corresponding reader visibility; durable awaits a covering commit.
For durable indexAndReturn also establish reader visibility before returning the
searchable document receipt. Coalesce concurrent waiters up to 250ms; this is the
commit scheduling deadline, not a promise that fsync completes in 250ms. Document
the generation/visibility in the receipt. An already-absent delete requires a
committed authoritative absence on the bound writer, not a cache or queue lookup.
Commit failures fail the wait; cancellation/deadline retains an observable C2
outcome for a potentially completed effect and never labels queue admission durable.

The D1 mutation-routing fence routes writes to their actual serving writer while
durably journalling accepted changes for the candidate. A durable ack during a
rebuild requires the active-generation covering commit and the durable replay
obligation, so restart cannot resurrect a delete. Activation cannot cross an
unsettled accepted write route; drain/transfer its existing ownership first. Do
not keep a caller waiting for an entire rebuild when the serving writer can
establish the requested durability. No second operation log or commit journal.

Required proof: controlled covering/non-covering commit races; two writer epochs
with equal numeric sequence; coalescing; commit failure; cancelled wait after
effect; no-file update/delete during rebuild; installed immediate kill/restart
showing durable visibility and authoritative deletion. The adverse nrt case must
control commit timing; it must not depend on a probabilistic kill winning a race.

## 5. Proof boundaries and refutation

D2-6 follows the linked generation/cursor record, including active-page holds,
capacity refusal and old encoder lifetime. D2-9 uses the real installed generic
MCP client recovery scenario and existing supervisor fixture, not a mocked client
or an in-process substitute. D2-8 adds timings to the existing SearchTrace owner.
E/F retain all installed, hosted, real-model, quality/performance, publication and
platform obligations. Measured numbers cannot be selected by a design document.

The independent D2 source pass identified facade, port/epoch, gate and cursor gaps
and the retired ratchet. Its claimed profile contradiction was rejected: “encoders
and generative absent” already means both absent. No runtime test was performed
by that source pass. Reopen only a bounded choice refuted by code or experiment;
missing execution proof alone does not call for a new architecture investigation.
