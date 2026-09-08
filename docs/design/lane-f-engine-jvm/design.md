---
title: "Lane F: one Engine JVM, with process boundaries that follow runtime and failure domain"
type: design
status: "LOCKED (2026-09-07): design through six independent reviews and four owner conversations (section 0); all open items decided (section 15); sequencing decided by the owner and written (section 17): no transport flag, one branch with checkpoints, two merges to main; per-stage checklists are written at each stage's start; stages B to D2 derisked against the code and the design corrected in place (17.9); review seven's six contract counterexamples amended in place (section 0); every owner value recorded (15, 17.7, 18) and the supervisor budget re-cut for the Engine (7.1). LOCKED 2026-09-07: changes follow 17.6. No code."
created: 2026-09-06
updated: 2026-09-07
lane: F (decision re-examination programme, wave 4)
model: fable (orchestration)
category: engine / process-boundary
related:
  - 917-lane-f-derisk-and-consumer-audit           # consumer audit, eight brief corrections, four derisk results
  - 917-evidence/lane-F-brief-v2.md                # the brief this tempdoc is the contract for
  - 931-wave3-lane-d-hardening-lane-e-landing-and-record-repair   # section E item 3: why F was no-go and what cleared
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation    # ForegroundLoad, IndexingPacing, extraction pool
  - 627-process-supervision-crash-recovery         # SupervisionPolicy: the restart budget this lane must re-home
  - 903-non-nvidia-acceleration-reread             # runtime-neutral inference host: research input for the inference lane
  - docs/decisions/0001-three-process-architecture.md
  - docs/decisions/0002-grpc-mmf-hybrid-ipc.md
  - docs/decisions/0048-extraction-isolation-and-indexing-pacing.md
---

# Lane F: one Engine JVM, with process boundaries that follow runtime and failure domain

Worktree `.claude/worktrees/lane-F`, branch `worktree-lane-F`, base `b96cd999` (#687). This
document is the lane's contract: the design and the considerations that shaped it. The PR cut
is in 17; the per-stage implementation checklist is written at each stage's start.

## 0. Provenance

Written 2026-09-06 in place (owner waived append-only while the design is unlocked). Review one
overturned five premises of the first draft (the pact is directional and the Head supervises
the Worker; Tauri supervises nothing; encoders in-process protect less than the split; "one GPU
owner" was overstated; shared lifecycle is not atomicity). Three owner conversations added the
agents-as-developers input, eight later ideas and the one-process resource and lifecycle
questions. Reviews two and three closed mechanisms asserted without a trigger, owner or
producer, and four the code contradicted (`ForegroundLoad`'s producer, pacing as a duty cycle,
in-process text extraction, the existing shutdown owner). Review four checked properties
against mechanisms and found a dozen credited beyond what they establish (the one-batch bound,
admission as a work budget, reconfigure and resume semantics, stage 1 on credit from the host,
the memory metric, the updater with no Engine, privacy scope); each is resolved in its section.
Review five checked the design's promises against each other and found four that conflicted or
outran their basis: the supervisor role bound to a token every local process can read
(ADR-0046 says so in terms), compose-before-close promised unconditionally where headroom and a
native fault can both break it, a foreground-first gate that can starve indexing (the ADR-0048
failure) while the latency bound was stated as if absolute, and one word "generation" covering
three lifetimes with a resume condition (`config hash`) broader than any operation's real
dependencies. It also asked that recovery be defined as a usable workflow restored, not a port
answering, and that the merge's own account separate what preserves current behaviour from what
is capability investment. Each is resolved in its section; 3.4, 4, 7.4, 7.5, 7.6 and 13 changed.
Review six read the review-five text against itself and found two literal contradictions the
fold had written (an aged batch "seated next regardless" beside "no batch overtakes a waiting
foreground call"; "rejection leaves the incumbent serving" on a path that closes the incumbent
before composing), one categorical rule a deadline cannot justify ("never a process restart"
for a stuck essential component), an operation id delivered "in the first bytes" that MCP
gives no meaning to, an `unknown` outcome that meant both "no record" and "no effect", a
per-context admission with no aggregate bound, and two gaps: the generation cutover was
promised but its invariants unstated, and one `work kind` field conflated survival across
restart with scheduling urgency. It separated three guarantees a reconfigure had been treating
as one (configuration commitment, semantic coherence, capability continuity) and asked the
owner to name the supported operating shape and the tolerated work loss. Each is resolved in
its section; 3.4, 4, 6, 7.2, 7.4, 7.5, 7.6, 8, 13, 16 and 18 changed.
A fourth owner conversation (2026-09-07) decided the sequencing. It rejected the incremental
shape (a transport flag on `main`, every PR keeping both modes green, ten to twelve merges) on
cost: the rule "main is coherent after every merge" protects consumers of `main`, and this lane
runs alone with every other development halted, so the rule's expensive form (both-modes
suites, the dual-mode prohibition, per-merge ceremony) buys nothing here. The design was
amended in place: no transport flag, one branch whose checkpoints carry the coherence
requirement instead of `main`, two merges. Sections 15, 16 and 17 changed; the three
sequencing uncertainties were resolved against the code and recorded in 17. The same day's
autonomous pass added the checkpoint protocol, the owner-set parameter register and the re-cut
triggers (17.6 to 17.8), swept the flag-era wording (1, 17.3) and pointed 18 at 17.7.

A derisk pass the same day, three read-only audits of the stage B, C and D mechanisms against
the code with their load-bearing citations re-read by the orchestrator, found sixteen places
where the design described the code as it is not, and one place where 17.4 did. Each is
corrected in place, marked *(corrected 2026-09-07, 17.9)*, and 17.9 lists them with the facts
that replaced them; `verified-facts.md` carries the citations. No decision in 15 and no gate
row in 16 changed. Three values the corrections surfaced are added to 17.7 for the owner.

**Review seven (2026-09-07, after the derisk pass)** backed the process placement and refused
to lock the contract around it, with six counterexamples where two of the design's own
promises could not both be true: ingestion during an in-place generation transition (7.4),
a reindex under the captured-inputs rule (7.5), applied configuration against the active
generation after an interruption (7.4), the aged-batch bound (4), retained state outside the
executor envelope (8) and same-key retry after an intervening change (7.6). Each is amended in
place, marked *(amended 2026-09-07, review seven)*, with the amended contract restated in 13,
15 and 16. The review's four judgment items (the global applied version, semantic
availability during maintenance as a product criterion, the tested client set against the
advertised one, and which owner values must exist before stage A) are written as owner
items in 15, 16, 17.7 and 18 with the orchestrator's recommendation, not decided.

**Lock (2026-09-07, fifth owner conversation).** The owner took the orchestrator's
recommendation on every open value and locked the design. Recorded: the operating shape and
the work-loss tolerance (18, 17.7); dependency-scoped coherence with the global revision kept
as the accepted-settings record (7.4, 13, 15); a generic MCP-client recovery harness added to
the tested client set (7.6, 16); gap acceptance by a user only (7.4); the failed-unit default,
the readiness authority, retention, deadlines, caps, the semantic-availability bound, the gate
bounds and the disclosure contract (17.7, 18). The owner also asked that the supervisor budget
be recalibrated rather than ported: the Engine's cooldown is the outage, its stability window
must run from `ready`, its supervisor now meets boot failures the Head never budgeted, and its
hang detection shares a heap with indexing; 7.1's budget row carries the re-cut, marked
*(re-cut 2026-09-07, lock)*. From here, a change inside a decided line proceeds with a dated
line here; a change to 15, 16 or 17.3 waits for the owner's word (17.6).

**PR 0 (2026-09-07, implementation orchestrator).** Two mechanism-level corrections found while
starting PR 0, inside decided lines (17.6). (a) `docs-validate` does cover `docs/design/`: it walks
`docs/**` and exempts only `docs/tempdocs/`, so the H1 here was aligned with the front-matter title
and `handoff.md` corrected. (b) The stage-start re-verification of `verified-facts.md` against
`main` at `76871d924` found five citations moved and two claims wrong at the original base:
`core.worker-log` is not a registered diagnostic channel (only `core.head-log` is; the name
appears in a Javadoc example and FE test fixtures), and 91 Java files under `modules` import
`io.grpc`, not 102. `verified-facts.md` carries the corrections with citations. Neither changes
15, 16 or 17.3; the section 6 row "the two channels collapse" reads as one registered channel
renamed plus the worker log stream folded into it. The 917 Derisk 1 procedure is now
`scripts/jseval/lane-f/head-flag-run.sh` (analysed by `analyze-head-run.cjs` beside it), and PR 0's
before/after record lives under `evidence/pr0/`.
The stage A checklist was drafted at the same time (`stages/A.md`, DRAFT until stage start
re-verifies it) and corrected two more citations inside decided lines: `adr-0002-grpc-present`
is `.kts`-scoped, so 17.4's "red the moment the wire is deleted" holds once the grpc dependency
declarations leave the build files (its item A14), and `check-readiness-reason-codes` has a
producer direction that the deleted Worker classes satisfy today, so stage A carries a holding
allowlist for the orphaned `WORKER_*` codes until D1 re-cuts the vocabulary (its item A17).

**Findings from the first live fixture captures (2026-09-07, PR 0), decided.** Four captures on one
build (`evidence/baseline/fixture/`): two on one index, then two on two fresh ingests of the same
91 documents. Every capture agreed on the cancelled turn's outcome and on the rank order of the
hits both sides returned; they disagreed on three things 16 calls deterministic, none of them
caused by the merge and all of them present in split mode today. (a) Scores jitter (GPU float
nondeterminism in the dense and cross-encoder legs; max delta 0.0094 over 118 identity-matched
hits), so the fixture instantiates "equal-score" as within a declared epsilon (0.01) and does
not diff the score: a mechanism detail inside the class. (b) Evidence selection at the top-10
margin differs between two fresh index builds of the same documents: `totalHits` moved on four
of twelve queries and one tie-group member was replaced on two. *(corrected 2026-09-07, PR 0b
investigation)*: the first reading blamed the dense leg's HNSW; the code says otherwise. The
chunk BM25 and SPLADE legs call `searcher.search(q, n)` with no `Sort`
(`ChunkSearchOps.java:266-267, 318-319` and three more sites), so equal scores break on Lucene's
internal docId, which a different segment layout renumbers; the whole-document leg already sorts
by score then the stable id (`LuceneRuntimeUtils.java:354-355`). Downstream the parent collapse
is first-seen-wins and `totalHits` is the fused candidate-union size, so one swapped chunk moves
both. HNSW is a second, smaller source, and Lucene 10.4 has no unfiltered exact path at any `k`
(`AbstractKnnVectorQuery.getLeafResults`), so raising `index.vector.ef_search` (the real key;
there is no `hnsw.ef_search`) cannot pin it; a `k >= maxDoc` plus a match-all filter takes the
filtered exact branch. A wall-clock cross-encoder deadline (`CrossEncoderReranker.java:244-252`,
200 ms) can also skip the rerank on a slow call. (c) The chat trajectory is not pinned: the
same narrow question completed in 3 iterations in one capture and hit the iteration cap in the
next. *(corrected, same investigation)*: `/api/chat/agent` is shape-driven and never reaches
`ConversationEngine.parseSamplingParams`; the agent's sampling is the constant
`SamplingParams.AGENT` (0.7 / 0.8) returned by `AgentLlmCaller.resolveAgentSampling`
(`AgentLlmCaller.java:277-288`) and built inline at `AgentStepRunner.java:595,635`, read from no
request field; `SamplingParams` has no `seed`, and the streaming body sent to llama-server
carries none (`OnlineModeOps.java:726-762`). Citation targets, sources, tool counts and
disposition all follow from it. 16 lists evidence selection and citation targets among the
byte-equal fields and 17.7 names "generative text under a fixed seed" as a class, on the premise
that both are deterministic across two runs. Neither is.

**Decided (orchestrator, 2026-09-07, with the owner's agreement): PR 0b**, one small follow-up
to PR 0 on `main`, before stage A merges, so the split side of every paired row is captured
under it and 16's relation and the three-class list stay unchanged. Contents: (1) the chunk
legs sort by score then stable id like the document leg, unconditionally, a correctness fix
that rides on both sides; (2) `index.vector.exhaustive_search` (boot-time, env-settable) makes
every kNN query exact through the filtered branch and, in that mode, the dense leg's
`totalHits` no longer counts as saturation so only the approximation changes, not the
pipeline's branches; (3) an optional `sampling` field on the chat request (temperature, top-p,
seed) threaded like `effort` through `AgentRequest` to `AgentLlmCaller` and the two inline
sites, with `seed` added to `SamplingParams` and written to the llama-server body; absent by
default, nothing else changes; (4) the fixture declares `sampling` (temperature 0, fixed seed)
and records it in provenance, and its capture runs set the exhaustive switch, one llama-server
slot and a high reranker deadline. Acceptance: two fresh-corpus captures on one build diff
clean on every `exact` field; the baseline capture is retaken under the pins, and
`totalHits` / `stageCardinality` are expected to move against the pre-pin capture. Until PR 0b
lands the capture in `evidence/baseline/` stands as taken (compact profile; the standard model's
11 GB resident set tripped the dev machine's memory guard), with its stability diff beside it.

**PR 0b outcome and the gating reference (orchestrator, 2026-09-07 evening).** Six paired
runs on one build (`evidence/baseline/fixture/` after PR 0b lands) took the fixture from 37
cross-build regressions to a residual of 2 to 5, and established what is and is not
deterministic in split mode: the chat trajectory is byte-stable once sampling is pinned and its
retrieved context is identical (both ordinary turns identical in four consecutive pairs); the
cross-encoder score is bit-stable across index builds (117 identity-matched hits, delta 0.0000);
the residual is index-time GPU embedding jitter moving fusion candidates at the rerank-window
boundary, which no budget pin removes (the window was widened to 4K; the per-leg budgets, the
Jaccard arbitration and the recall-complete splice were pinned; one pin, `rerank.top_k=100`,
silently dropped the reranker with an ORT arena failure, now refused by the capture-health check).
CPU encoders would make the vectors bit-stable but measured 38 percent of document embeddings in
15 minutes at four threads on the 91-document corpus, outside the run budget; the keys stay as a
documented instrument. **Decided:** 16's "byte-equal for deterministic fields" is instantiated with
determinism measured, not assumed: each side of the paired diff captures twice on two fresh
ingests of the corpus (GPU encoders, the pins on); a field that differs within a side's own pair
is noise on that side and cannot count as a regression across sides; the declared relation applies
to every field stable within both sides' pairs; a side whose noise pair exceeds a declared
fraction of noisy fields (0.05, about ten fields on the shipped fixture) is refused as too noisy to gate; the raw counts are reported
beside the verdict. No allowed-difference class is added; the three classes and the byte-equal
rule are unchanged for stable fields. Captures taken before PR 0b are not comparable to captures
after it (the request breadth and the pins changed) and the split-side baseline capture is
retaken under PR 0b.

**Stage A checkpoint (orchestrator, 2026-09-08).** Items A1 to A20 landed on `worktree-lane-F-A`
(cut from the PR 0 head; PR 0 and PR 0b are open, green and unmerged): the Engine composes the
index half in-process from `HeadlessApp` through `EngineRoot`; the ports are direct calls behind a
`KnowledgeClient` facade (an abstract class in `app-services`, the lowest module both `ui` and
`app-engine` see, catalogued in `governance/engine-ports.v1.json` with an enforcing gate; a mechanism
detail inside 3.3, since `SearchPort` and `IndexingService` cover one of its ~89 members); the two
streams are bounded in-process hand-offs (scan producer block-never-drop, change-feed producer
fail-fast-and-close because it runs under the job-queue lock); the wire, the MMF bus, the spawner,
the supervision policy, the config-snapshot tier, the second distribution, the second log and the
chaos process manager are gone (105 files deleted, 35 renamed, 49 RPCs and 3 service blocks, 9 MMF
fields, 2 argv builders, 1 ordinal, 1 log file); ADR-0049 supersedes ADR-0001 and ADR-0002 with
three probes (rule 6b as a test, `libs.grpc` absent from build files, `import io.grpc` absent from
Java); one `engine.log`, `core.engine-log`; one flag set at both spawn sites pinned by an exact-set
test in CI (`-Xmx2g`, SerialGC, `MetaspaceSize=128m`, `UseCompactObjectHeaders` as the first cut
17.7 leaves to the gate run, `file.encoding=UTF-8`). Proof: full suite 9,301 tests green including
the dead-code ratchet, 30 governance gates green, live search and ingest on one JVM, the file-trigger
reload verified live, evidence under `evidence/A/`. Five independent reviews (A1-A2, A3, A4-A5,
A6-A9, A10-A13, the checkpoint and its re-review), every finding fixed or named. Named reds
carried to stage B (`stages/A.md` section 10): supervision (crash detection and restart under a
budget); restart-as-reload answers `restart_required` on the HTTP, operation and migration paths
(a Blue/Green cutover promotes durably and the promoted generation is served after a restart, an
honest loss until D1's live swap; and the status fields named for the served generation report the
`state.json` pointer, so D1 must source one from the open runtime); `WORKER_RESTART_EXHAUSTED` has
no producer (the readiness-code gate now requires an emission and carries it as `awaitingProducer`,
owner B); `WorkerBootRecoveryE2ETest` red (its injector fired on the deleted PID validation, owner
B); the packaged installer unverified because `build-installer.yml`'s `release-signing` environment
rejects branch refs (a settings action). Two design facts corrected in the checklist: the
foreground gauge had no producer from A6 to the fix pass (the gate matched RPC names against ops
labels: the `wrong-gate` case, caught by an assertion across a real search, and there are ten
operations, not nine); `awaitingRecut` was never created because the health monitor's boot-recovery
arm survived.

**17.8 bullet 1 fired (orchestrator, 2026-09-08).** `origin/main` moved 11 commits (81 files: 941
sandbox rounds, 948, 949, 859, 935, the WinGet retirement) while the branch was open, so the
handoff's halt premise no longer holds. Decision: keep the single-branch shape; merge `origin/main`
into the stage branch at every stage boundary (done at this checkpoint: conflict-free, no changed
path in the lane's blast radius) and re-count; revert to the incremental shape only if a merge
conflicts inside the blast radius. **Stage B decisions (orchestrator, 2026-09-08, from `stages/B.md`'s
questions):** the dead-Engine sandbox proof is a host-level exercise on the branch plus a dated
deferral to the first post-merge installer run; the Tauri actuator half is tested by one extra step
in the `shell-rust-tests` lane; the supervisor policy record lives in the supervision register as
authority with a thin Java mirror for the drift check; `WORKER_RESTART_EXHAUSTED` is renamed
`ENGINE_RESTART_EXHAUSTED` when B lands its producer; the supervisor state file lives at 7.1's
location with a manifest-history mirror the updater can read after death; extraction children are
registered, never adopted; an absent supervisor file means the unsupervised mode of 3.1.

**Stage C1 and C2 checklists drafted during B; their questions decided (orchestrator, 2026-09-08).**
Both drafts (`stages/C1.md`, `stages/C2.md`, base `e692b86ef`) were written while B7 to B17 were
open and are re-verified at each stage start (17.6). Decisions, one per question, so the briefs
need no re-research. **C1:** the engine context is a sibling of `InvocationProvenance` with one
tested one-way mapping at the single site where both are in scope, not a projection or a
replacement (`core` carries no project dependency); the context reaches the indexing port through a
`withContext` bound view, not 33 signature changes and never a thread-local; VDU's in-Engine PDF
render keeps a dated, reasoned ArchUnit exception at C1 with the move behind the child protocol as an
in-lane follow-up (17.5); the operator's explicit `in_process` sandbox mode survives and only the
silent probe fallback is deleted; executor caps by kind and the retained-state caps live in one
`governance/retained-state.v1.json` with a drift check that reads the register and compares (the
supervision-register shape); all five retained-state kinds are declared, the three without a C1
producer marked `awaitingProducer` with their stage; the MCP surface is admitted on the same caps and
codes as HTTP, with the rejection mapped into the MCP error shape and asserted. **C2:** the
acceptance, effect and completion order is stamped from the operations table's own autoincrement
key, because no journal commit sequence number exists (`CommitOps.commit()` discards Lucene's
`long`; the NRT watermark is generation-scoped) — 17.3 row C2 and `verified-facts.md` are corrected
to say so; `jobs.db` becomes `MIXED` (a third `StoreRecoverability` value) with the operations table
inside it, **and every lane-F change to a durable store's `owner`, `role` or `reconciliation` (the
`WORKER` owner rename included) is batched into that one register change**, because the installed
updater refuses a release whose durable-store identities or row count differ (`updater.rs`, the
"closed set" and "changes ownership or recovery strategy" branches) and the cost is paid per
boundary, not per field; the same C2 commit relaxes the rule's successor so a later release may add
a durable store without refusing (the currently installed builds still pay the boundary once — a
product decision on in-place upgrade, recorded here as such); the operation record sits beside
`OperationLeaseService` with the boundary written into both javadocs; `version conflict` lands at
C2 on the global accepted-settings revision only (7.4's line) and D1 adds the per-component
versions; ingestion's unit key gains a persisted content-hash column in the same migration;
acceptance durability is stated as process-crash-durable under `synchronous = NORMAL`, not raised to
`FULL` without a paired run; the outcome query is the existing `OperationHistoryStore` surface made
durable, not a second endpoint.

**Decision authority (owner, 2026-09-07).** After PR 0 the owner delegated every remaining
decision in this lane to the implementation orchestrator: "owner item" is retired as a category,
and 17.6's clause that a change to 15, 16 or 17.3 waits for the owner's word now reads that the
orchestrator decides it and records the decision, dated, in this section with its reasoning.
The merge go-aheads (17.6, handoff rule 1) are unchanged.

**Stage B takeover and shutdown-request observation (orchestrator, 2026-09-08).**
The inherited long-document timeout explanation is withdrawn: the test deliberately selects
CPU FP32, and fresh serialized controls pass on clean main and the lane in about 17 seconds;
`evidence/B/takeover-verification.md` records the exact runs. No timeout is widened and no
environment red is declared from the unreproduced timing failure. The full-suite inventory
is captured before any targeted rerun can overwrite a module's results.
The B7-B10 review also exposed a protocol race: the Engine deleted an accepted shutdown
request before either supervisor was guaranteed to read its reason, and both supervisors
ignored an externally written request's deadline. **Decided:** retain an accepted request
through the terminating incarnation; the watcher's existing one-shot guard prevents repeat
dispatch, and boot must clear the predecessor's request before starting the watcher. Expired
and refused requests must still be cleared. Both supervisors must read the request before classifying
an exit and enforce its original deadline, including when the Engine consumed it before
the supervisor's next poll. **Acceptance distinction (same-day refute-first correction):**
presence is not acceptance. Before dispatch, the Engine must atomically mark the existing request
with `acceptedByInstanceId` from its `RuntimeManifestPublisher.instanceId()`; externally
written requests influence a supervisor only when that marker matches the terminating
incarnation. A refused request must never arm a supervisor deadline or change exit
classification. A supervisor's own request will remain authoritative in its in-memory state
without an Engine acknowledgement, since a hung Engine cannot acknowledge. A failed marker
write must not dispatch or consume the watcher's one-shot guard. The supervisor must read the
accepted record before clearing predecessor discovery state or spawning the replacement.
This uses the existing request artifact, not a second receipt or authority. The old
consume-before-callback test will become a stronger acceptance-plus-retention-plus-one-shot
test, paired with the stale-at-boot test; this changes the protocol, not its protection
against shutting down the next incarnation. The Java retention change and supervisor
observation/deadline tests will belong to one reviewed follow-up batch.

**B7-B10 review disposition (orchestrator, 2026-09-08).**
The independent source review at `1ffd6cc2d` is recorded in
`evidence/B/b7-b10-independent-review.md`. R1-R3 and R5-R9 are accepted for fixes;
R4 is the cross-cutting proof obligation for the production bindings, not another
claim of product failure. The fixes will preserve predecessor identity while clearing
its port/token, will make host closing monotonic and serialize it with spawn admission,
will publish terminal spawn failure, will install the supervisor-state consumer in the real
UI boot path, will end supervision before deliberate runner teardown, and will bound the
manifest watcher's lifetime. API responsiveness still permits `running`; the
300-second budget reset instead requires continuous readiness of both essential
components (`api`, `index`) and loses that clock when either ceases to be ready.
R3 follows the accepted-instance request protocol above. Production binding tests
must fail for the reported scenarios; the conformance binary's distinct actuator
cannot stand in for those tests. These fixes follow the inherited shutdown fix
batch, before B11-B17. No merge or stage-B checkpoint is implied by their acceptance.

**Upgrade request persistence and acknowledgement (orchestrator, 2026-09-08).**
The B6 production-wiring review found that commit acknowledged success before its
asynchronous file write, then swallowed write failure, leaving a committed barrier
with no shutdown. It also found that a direct file with a live preparation ID and
wrong nonce passed the new acceptance predicate. The HTTP nonce test did not cover
that direct-file path. Both findings are accepted for fixes.

`UpgradeController` remains the sole preparation/nonce/commit authority. The existing
`UpgradeShutdownBridge` will forward a verifier callback installed by the controller
before route registration; it must not keep a second nonce. The writer is installed
after the instance lock and strict predecessor clear, before API startup, because it
requires only the already-known runtime directory. Late watcher startup must preserve
a current request written in the intervening boot window.

Commit will use short synchronized OPEN/PERSISTING/ACKNOWLEDGED transitions, with
synchronous request persistence before writing/flushing the success response and
ACKNOWLEDGED only after successful flush. No servlet or filesystem I/O runs while
holding the controller monitor: a stalled response must not block the dedicated
watcher from observing a supervisor hang request. The watcher acceptance contract
becomes ACCEPT/DEFER/REFUSE. Matching preparation and nonce while PERSISTING returns
DEFER, leaving the file and one-shot guard untouched; ACKNOWLEDGED plus the current
frozen lease preparation permits ACCEPT; wrong identifiers or OPEN returns REFUSE.
Unprepared supervisor requests retain the plain path. Persistence failure restores
OPEN, returns a non-success response, and leaves the same capability retryable or
cancellable. Response-write failure restores OPEN and must not dispatch; any retained
unacknowledged file is refused. An unavailable bridge verifier fails closed.

The commit tests must traverse production factories and cover persistence failure,
response-write failure, a direct wrong-nonce file, deferred acceptance during flush,
and a valid request written before watcher startup. Death before the Engine's accepted
instance marker remains fail-closed to supervisors and cannot fabricate an upgrade
receipt. Single-slot writer admission and conditional cleanup/marking are a separate
coupled obligation of the accepted-request protocol batch; tri-state verification alone
does not make read-then-delete or read-then-replace safe against another writer.

**Shutdown request writer admission (orchestrator, 2026-09-08).**
The accepted-instance protocol above needs first-claim-wins admission in all three
languages. Unconditional replacement lets a refused request's cleanup delete a newer
one or an old acceptance marker overwrite it. The fixed request path will therefore
be claimed with atomic create-new; an occupied slot returns BUSY without changing it.
The winner writes the fixed `shutdown-request.v1.json.tmp` staging file in the same
directory (the existing Java name, shared by all three writers) and atomically
replaces its own empty claim. Other writers never replace an occupied slot. Readers
defer empty or malformed in-flight content without consuming the one-shot guard.
Failure removes the winner's staging and any still-empty claim and propagates;
a complete published request is preserved if publication's outcome is uncertain.
Atomic replacement is
required for this channel, with explicit failure on unsupported filesystems instead
of a non-atomic fallback. This avoids adding a hard-link filesystem requirement or a
cross-process lock service. The occupied-slot invariant permits one fixed staging
name: only its claimant publishes, then only the single watcher may mark it. A successful
rename consumes that writer's staging file; success must not subsequently delete the
staging path because the watcher may already be using it. Failure cleanup remains
within the writer's ownership interval. Strict predecessor boot/death cleanup removes
both the request and its fixed staging residue, with the existing staging artifact's
closure/recoverability treatment asserted. Staging is write mechanics, not a second
request or receipt authority, and unique staging files must not accumulate after crashes.

Only the Engine's single watcher clears an expired/refused complete request or
replaces it with the accepted-instance marker. The occupied slot excludes another
writer during that read/clear/mark interval. Accepted requests remain through death;
supervisors read their accepted reason and original deadline before classifying the
exit, then clear only after the owned Engine is dead and before a successor starts.
Strict Engine boot cleanup precedes its own writers and API exposure. A supervisor
whose own request finds BUSY still owns its in-memory reason and original deadline
and must force-stop at that deadline if cooperation cannot happen. Controller BUSY
is a failed commit, so the preparation remains retryable/cancellable. The first
claim wins the slot; the sequence's first accepted reason still owns
resource shutdown.

Java, Rust, Node and their conformance fixtures will change in one reviewed protocol
batch. Tests will pause publication after claim, cleanup after reading A, and marker
write after reading A; B must receive BUSY and leave A untouched in each interval,
then may publish after legitimate removal. They also cover an upgrade response
stalled in PERSISTING while a supervisor hang request is BUSY but its force deadline
still fires, marker-write failure leaving the watcher unfired, retained acceptance
read at exit, and empty claim residue cleared at the predecessor boundary. The
request gains required `schemaVersion: 1` with unknown/missing versions refused and
the field shape pinned across writers. Publication and marker rewrites use real JSON
string encoding, including optional strings read from the request, so quoting cannot
turn an accepted request into malformed retained evidence. An accepted record whose
deadline has elapsed still supplies the terminating incarnation's reason; its deadline
means force-stop now if still alive, not forget the accepted reason after death.
The same batch will replace sequence exit
literals with `EngineExit.OK` and new `REQUESTED_UNCLEAN = 4`, classified REQUESTED
in the supervision register and both supervisors; exact drift tests must reject a
Java constant/table mismatch. The existing shutdown-request recoverability row is
asserted, without making a second durable-store ownership change before C2.

**Production supervisor ownership fixes (orchestrator, 2026-09-08).**
R1, R2, R4, R5 and R9 will be implemented through a focused, Tauri-free production
host core, not by moving the whole shell or updater. One mutex will own the real
Engine child and the monotonic host-closing flag. The final close check, prepared
command spawn, pipe extraction and child installation occur under that mutex:
close wins and no child starts, or spawn wins and close observes and reaps that
child. Incarnation reset must never clear host closing. Discovery will distinguish
awaiting a successor from awaiting the installed child's manifest and a bound
incarnation. Reset retains the predecessor instance ID and clears its port/token;
no manifest is accepted while no successor is installed, and the successor's
manifest PID must match the installed child and its instance ID must differ from
the retained predecessor (including PID reuse). The real observation and event path
must emit exactly one restart event when the new identity arrives. One host-owned
manifest watcher survives incarnation changes, has explicit cancellation and join,
and cannot be started twice or after host close. A watcher holding the host alive
must not depend on the host's destructor to cancel itself.

Asynchronous stdout-close diagnostics also belong to their spawning incarnation.
The existing drain captures shared state and can set a spawn error after reset;
the production core must reject that callback once its child has been replaced,
using a monotonic spawn generation rather than a reusable PID.
A regression delays the old child's EOF notification until a successor is installed
and verifies that the successor's discovery state is unchanged.

Initial and replacement launch failures will both publish a terminal supervisor
transition through the same production state writer and a narrow injectable event
sink. Resolve the data directory before launch; if that resolution itself fails,
report the terminal state in memory and to the UI because no file destination is
available. The existing conformance binary proves the shared loop with a distinct
actuator and real child timing; it does not prove the shell's bindings. Tests will
exercise the production host operations, including stale discovery, both sides of
the close/spawn race, both launch failures, and watcher ownership. A later updater
replacement hold is releasable and must remain distinct from permanent host close.

**Managed child ownership and manifest handoff (orchestrator, 2026-09-08).**
B11/B12 will put the child record and narrow registration contract in `app-api`,
with one mutable registry at the composition root and `RuntimeManifestPublisher`
as its sole persisted writer. No second child file or history-based adoption
authority is introduced. The manifest becomes schema version 2; its public
projection also moves to a v2 schema and excludes child identifiers and private
shutdown handoff data. The root SSOT schema remains authoritative and its existing
sync/generation paths update consumers. This breaking constituent change raises
the runtime contract from 0.2.0 to 0.3.0, rather than maintaining a separate public
v1 representation while the private manifest moves to v2.

The canonical manifest must survive an intentional warm handoff. The publisher
will capture the predecessor before its first publication. The existing early
manifest shutdown step will mark shutdown pending, while child updates remain
writable through resource teardown. A narrow sequence completion callback runs inside
the memoized sequence, including the JVM-hook path, after the ordinary steps. It
receives their aggregate result; the publisher reads the current registry itself.
Only an error-free result with a GRACEFUL index outcome can mark handoff complete.
Persistence failure enters the sequence's error accounting, makes its final result
unclean, and leaves pending evidence. A
RESTART/HANG keeps surviving ownership in the same canonical manifest; QUIT/UPGRADE
deletes it only when registered children are confirmed gone and the preliminary
aggregate is clean with a GRACEFUL index outcome. Failed cleanup retains
ownership evidence. The publisher's ordinary/finally close must obey this disposition
and cannot erase it. An abrupt JVM death never calls the completion callback, so
pending or ordinary crash residue remains unclean. The per-instance snapshots,
NDJSON and start log stay diagnostic history.

Capturing predecessor records only in memory is insufficient: the first successor
publication must carry every unreconciled record forward, so another crash during
startup cannot erase the sole ownership record. Removal requires confirmed death
or proved identity mismatch; failed termination of an identity-matched child keeps
its record. Registration persistence failure after spawn must stop and reap the
just-spawned child and fail activation, never return an unregistered managed child
as healthy. PID, process start time and executable identity must all match before
acting on a live process. Reused PIDs or mismatched executables are left untouched.
The first v2 carry-forward publication must precede any child-capable asynchronous
bootstrap, including the existing early worker future. Publishing only once the API
binds is too late. An explicit pre-bind ownership seed is published after the instance
lock, then enriched when the API binds; the current single-use `publishHead` cannot
serve both moments unchanged. A race test must hold that seed publication and show that child startup
cannot escape registration or erase predecessor records. No origin-instance field
is required for process safety; the recorded OS and configuration identities suffice.

Managed llama adoption compares a `declaredConfigHash` of applied launch inputs;
`realizedArgvHash` records the actual final launch command for diagnosis. They are
different projections of the same launch: context step-down and reasoning-budget
fallback can change realized argv without changing applied configuration. Current
free-VRAM observation is diagnostic and does not choose the automatic context rung;
the earlier suspected VRAM-driven comparison mismatch was refuted. The declared
hash covers normalized executable/model/mmproj paths, port, effective GPU policy,
VDU mode, automatic or explicit context setting, thinking/reasoning inputs, slots,
KV type and a fixed-launch-flags version. No global settings revision is invented
before C2. Identity-matched configuration mismatch stops the old managed child and
starts from applied A. Extraction children are registered and never adopted.

The documented external llama path remains unmanaged: existing health and `/props`
validation and the disallow-external policy remain in force, but an unregistered
external process is never claimed or terminated by managed-child reconciliation.
Managed reconciliation runs first. This is an explicit exception for the existing
BYO contract, not permission to adopt an unregistered process as owned. Both
supervisors must separate killing the Engine alone on recoverable death/hang from
identity-checked child cleanup on terminal exit; process-tree killing cannot remain
on the recovery path because it would defeat warm adoption.

**Dead-Engine update path and recovery UI (orchestrator, 2026-09-08).**
B13's draft assertion that no API port means nothing needs stopping is false. A
booting Engine can own handles before binding. The updater must acquire an
exclusive, releasable replacement hold, block further spawns, end the current
supervision generation, and stop/reap the owned Engine before reconciling registered
children and launching the installer. The hold must not reuse permanent host close.
If installer launch fails, one resume operation starts both a child and exactly one
new supervision generation; starting an unsupervised replacement is insufficient.
Every failure after taking the hold and before confirmed installer launch must
either resume once when safe or remain terminal with the held/owned state explicit;
no failure silently releases ordinary supervision into child reconciliation.

An ENGINE_UNRECOVERABLE phase alone loses its evidence when later phases replace
it. The existing upgrade intent will retain a tagged, mutually exclusive stop
witness through install launch, reconciliation and commit. The normal prepared
path keeps all existing preparation, nonce, receipt and PID checks. The dead-Engine
path requires that normal evidence be absent and must never invent a nonce or
HEAD_STOPPED receipt. Reconciliation carries the evidence kind and attempt identity,
requiring a shutdown nonce echo only for the prepared path; release, durable-store
ownership and per-boot mutation-token checks remain. Mixed evidence is refused.
The host proof must traverse the production coordinator after staging/authentication
with injected launch/backend edges, not a copied state-machine implementation.

The current UI returns a static alert before mounting Settings when the first Engine
never binds. The supervisor bridge must therefore be installed before API resolution,
with subscribe-then-snapshot initialization from a read-only shell command and a guard
against stale snapshot overwrite. Packaged boot with no API mounts a local recovery
surface using existing host update status/check/install commands and update state;
it must not fabricate an API base URL or start the normal shell's API work against
an empty binding. Tests cover a terminal state published before UI subscription,
later events, snapshot ordering, and installation from that recovery surface. The
browser-only unresolved-API behavior is unaffected by this packaged recovery path.

**Upgrade acknowledgement deadline correction (orchestrator, 2026-09-08).** A request's
force deadline is computed when the request is created, before synchronous persistence and before
the HTTP response is flushed. The watcher must therefore consult the controller verifier before
generic expiry handling. An exact request observed while its reservation is PERSISTING remains
DEFERred even after its deadline, so a blocked flush cannot delete it. After the flush succeeds,
the same exact request maps to the watcher-only ACCEPT_COMMITTED outcome while its preparation
remains the live frozen lease; this outcome dispatches even if the deadline has elapsed. Ordinary
ACCEPT requests remain subject to expiry and REFUSE remains fail-closed. Dispatch still follows
the user-visible acknowledgement, and it carries the unchanged original deadline so the
supervisor's later accepted-instance protocol can interpret an elapsed deadline as force now.
This adds no nonce, phase or deadline authority. Accepted-instance retention and cross-language
supervisor observation remain in the separate protocol batch above.

**Upgrade preparation reservation correction (orchestrator, 2026-09-08).** The controller's
sole-authority rule also covers the complete prepare transaction. A short PREPARING reservation
serializes admission freeze, cancellation request, nonce publication, Worker prepare and response
publication against another prepare, cancel or commit. Competing calls receive a retryable
conflict. The reservation is acquired and released under the controller monitor, including release
in `finally`; lease, Worker and servlet work runs outside it. Repeating prepare after completion
preserves the live preparation and nonce. This prevents a delayed repeated prepare from publishing
an old nonce after cancel and a new freeze, and prevents delayed Worker prepare from recreating an
orphan Worker barrier after Head admission was released.

**B14/R7 supervision and readiness correction (orchestrator, 2026-09-08, from independent
`review_b7_b10`).** B14 must retire the obsolete Java whole-Worker supervision veto chain
`SUPERVISION_ENGAGED`/`RESTART_EXHAUSTED`, including the phantom
`worker.restart_exhausted` producer and its exemption. It must preserve the fatal index/schema
veto, operator override, bounded local four-attempt/backoff policy and
`worker.spawn_recovery_exhausted`. Host restart exhaustion must project the frontend-derived
`engine.restart_exhausted` row from the current host's retained in-memory `StateRecord`: the Tauri
bridge subscribes early, then snapshots, and guards a current event against a stale snapshot. It
must never seed this state from an old supervisor file or inject a predecessor failure into Java
readiness. The uppercase external contract `ENGINE_RESTART_EXHAUSTED` remains unchanged, and the
existing no-API recovery UI requirement applies. This contract needs no new owner or launch
identity.

R7 treats the current-child binding plus any bounded valid HTTP response, including 503, as
startup/running liveness; only lack of a response charges start or hang. Essential API and index
readiness alone own the continuous 300-second stability clock, and loss of either resets it.
Production tests must cover 503 during startup and running, timeout, an old supervisor file unable
to veto local recovery, preservation of the fatal veto, a real host event/snapshot reaching the
real no-API UI, and the readiness gate's frontend-derived row without an awaiting producer. D1's
later essential-recovery escalation uses a counted fault reason and must not route failure
escalation through the uncounted requested-restart path. B14 must also correct the canonical
index-migration documentation and the `supervisorState` Vite comment when implemented. All B14/R7
work remains open.

**Host discovery projection correction (orchestrator, 2026-09-08).** The production
ownership review also applies manifest admission to every UI projection. A refused
manifest must neither replace the binding nor update the tray tooltip. An admitted
unchanged observation is distinct from refusal; repeated identical tooltip text
does not cause repeated native mutations. Initial launch failure, replacement
failure, manifest events and tooltip updates use the same production methods in
tests and in the shell. The tests cover both serialized close/spawn orderings,
child-absent discovery, delayed stdout EOF and watcher cleanup from its own thread.
Tauri setup call sites are source-reviewed; these tests do not claim packaged UI
execution or a current AppHandle-linking failure.

**Stage B scope stop and ownership re-cut (orchestrator, 2026-09-08).** Applying
17.8 after the review of amendment growth stops the unimplemented shared
first-claim/accepted-instance batch. Its three-language acceptance and retention
protocol pulls C2-shaped guarantees into an ephemeral shutdown trigger. The
preferred replacement gives the supervisor sole file-writer ownership and keeps
Engine-local shutdown dispatch local; it is not yet implementation-approved.
Existing manifest lifecycle evidence may bound a responsive Engine stalled during
local shutdown, but that production path must be proved before the replacement
lands. Exact cross-process request-deadline recovery is not silently retained as a
promise. The earlier accepted-marker and writer-admission paragraphs are suspended
by this decision, including their proposed race fixtures. Reviewed local preparation
and response-ordering protections remain necessary; their file transport may be
removed. [The scope review](evidence/B/scope-recut.md) records alternatives, costs,
retirements and the required proof. The previous main-development stop-rule
disposition remains in force. No stage checkpoint or merge is authorized here.

**Integrated stress verification finding (orchestrator, 2026-09-08).** The fresh
9,364-test run exposes a closed Lucene writer after file-lock contention. This is
a blocking recovery finding, not an allowed timing red: B14's no-client boot retry
does not repair an already-bound client with an unusable writer. Preserve the
assertion and investigate detection, recovery ownership and durable replay before
choosing a fix. If the remedy needs D1's activation mechanism, 17.8 applies again;
this finding does not itself approve moving live replacement into B. The separate
drain-retry fixture defect is repaired without production changes. Exact evidence
and the still-red suite inventory are in
[the integrated verification record](evidence/B/integrated-verification.md).

**Merge authority delegated (owner, 2026-09-08).** The owner explicitly authorizes
the lane orchestrator to make merge decisions autonomously. This supersedes the
earlier per-PR approval requirement, including PRs #708 and #717. The orchestrator
still owns verification, independent review, the repository merge queue and
post-merge checks. The open stage-B recovery finding remains blocking for that
stage; it does not make the separately scoped split-mode PRs dependent on another
owner reply.

**Writer recovery scope (orchestrator, 2026-09-08).** Prove whole-Engine fault
recovery before importing D1's component reopening. The existing transient-exit
budget and startup queue replay are a smaller ownership cut; the cost is a full
application outage. The existing runtime swap has unresolved failed-open and
loop-handoff semantics. This chooses the next proof, not an implementation or a
green checkpoint. [The investigation](evidence/B/writer-recovery-investigation.md)
records the evidence and the detection, supervised restart and replay still to
demonstrate. No new file protocol or generic fault framework is approved.

**Split baseline publication (orchestrator, 2026-09-08).** PRs #708 and #717
have landed through the merge queue under delegated authority. Each landed tree
equals its reviewed candidate. [The publication record](evidence/publication.md)
contains the fresh suite inventories, review and hosted results. This does not
advance the Stage B checkpoint: PR 0b still enters the primary lane at its recorded
main-integration checkpoint, and the writer-recovery and shutdown proofs remain
open. Publication of the split baseline is not integration into the Engine branch.

**Terminal writer fault ownership (orchestrator, 2026-09-08).** Implement the
whole-Engine recovery direction through a narrow notification from the active
Lucene runtime to the composition root. Observe the writer after mutations and
commits; an ordinary IO failure with a usable writer remains recoverable.
`RuntimeSession` atomically arbitrates one notification against retirement;
initial and post-bulk CRTRT threads route only a Lucene `AlreadyClosedException`,
so intentional retirement consumes its close-induced observation while unrelated
errors retain the JVM uncaught owner. `EngineRoot` accepts only the current
`KnowledgeServer` and starts a dedicated fault thread after all mutation or commit
locks are released.

The fault thread waits for HeadlessApp's complete `EngineShutdownSequence`
binding, runs the existing ordered close with `RESTART` policy, and selects fatal
exit code 1 through that sequence's one exit authority. Direct `System.exit`
before the ordered close is rejected: ORT owns an independent JVM hook, and the
live failure showed it tearing down the native environment while Engine resources
were still initializing/closing. Calling `EngineRoot.close()` first is also
rejected because it bypasses the composition-owned order and races boot
publication. A concurrent cooperative exit already selected remains final; a
fatal request admitted by the sequence before selection upgrades the one exit to
1 and cannot produce a clean upgrade receipt. A main-thread boot failure that
prevents sequence binding retains the existing fatal-startup fallback. Close now
waits for the already-published deferred-model initializer to finish before model
fields are released; a stuck initializer therefore leaves an unsupervised close
pending, while the existing external supervisor hang/force budget owns death
after the API has stopped. This avoids importing D1's runtime replacement and
replay coordination, at the cost of an application-wide outage. No request
artifact, polling watchdog or generic fault framework is introduced.

The in-process contention fixture cannot prove supervised recovery: retain its
original red and assertions while establishing a real child-process proof with
durable-job replay. Test relocation or a stage-proof change requires an explicit
decision after that proof, not a silent harness restart. This authorizes the
bounded repair, not a green checkpoint or relaxed survival promise.

**Recovery verification tier (orchestrator, 2026-09-08).** B17 explicitly includes
`system-tests:integrationTest` for the installed-Engine writer regression. That
task builds the production distribution and exercises the dev-runner's real child
binding. Default unit tests cannot prove an actual exit and automatic restart.
The existing Windows integration CI job runs it and retains diagnostics, but
remains advisory; this decision does not turn it into a required hosted gate or
clear the original file-lock stress test. The deterministic segment collision
proves a terminal writer recovery path; hostile-lock survival and both durable
queue outcomes remain separate acceptance evidence.

**B17-R1 placement and scope limit (orchestrator, 2026-09-08).** Section 17.8's
later-mechanism trigger applies to the writer repair: terminal-writer detection
and bounded whole-Engine escalation are moved from D1 into the named B17-R1 item.
The demonstrated alternative is an Engine that stays alive with a permanently
closed writer, so leaving this connection absent cannot meet B's survival proof.
Only detection, ordered fatal exit and existing supervisor/startup replay move;
D1 retains live component replacement and general local recovery. B17-R1's proof
is the focused negative tests, installed-process collision/replay test, native
initialization overlap arm, full default suite and build/static checks. Finish
and push that batch without adding hostile-lock fixture relocation or another
shutdown protocol. Those remain enumerated B17 and shutdown re-cut work.

Remaining implementation runs in fixed batches: B11-B12 (registry/reconciliation),
B13-B14 (dead-Engine updater and supervision/readiness), then B15-B17 (requested
restart, residue and stage proof, including the existing shutdown re-cut).
Each has one implementer and independent review. New findings are separate
items; after two worker review rounds the orchestrator takes the diff. New raw
logs remain outside Git; committed evidence carries summaries and hashes.

**B14 Java retirement cut (orchestrator, 2026-09-08).** The already-decided local
recovery veto retirement is implemented separately from B11-B12 in
`codex/lane-f-b14-local-recovery`, with root as sole implementer. This lets bounded
Java work proceed without changing the child-registry worker's brief or sharing its
worktree/build slot. It removes the old host-to-Java veto path; it does not add a
supervisor-file reader. The orphan reason enum, UI row and producer exemption are
removed in the same cut, as the readiness gate requires. The current-host UI
projection and R7 host probes remain in B13-B14; this cut does not close B14.

**B16 residue disposition (orchestrator, 2026-09-08).** Delete the unconsumed
`KnowledgeServer.isRunning()` API and its state-only tests; boot tests assert the
already-existing service surface instead. Retain `shutdownLatch`: stage A's final
checkpoint gave it a real `EngineRoot.close()` consumer through `awaitClosed`, so
deleting it would remove completion evidence. The worker-config snapshot entries
in the named four files are already labelled retirement history, with no writer,
reader or allowlist entry; keep that history. Remove the phantom method name from
the retired supervision row and re-home its contract test into the supervision
package within the same module. The whole-program dead-code rule ratchets classes,
not methods: require no baseline growth, and accept an unchanged baseline when no
class is removed. This closes residue without adding a replacement lifecycle API.

## 0.1 Forces that shaped the design

One line per force and the section it bent; section 2 holds the rule, section 13 the losses.

**Where the lane sits.** F runs last and alone, rewriting the boundary every earlier lane
touched; it waited for A to E and an owner go-ahead (931 §E item 3, cleared 2026-09-06) and the
programme rules apply (stops between steps, no merge without a go-ahead, independent review, a
sweep, a Report-back). 917 corrected the brief eight times and #664 added a 49th RPC after it;
lane C's constructs are inherited (`ForegroundLoad` and `IndexingPacing` stay, the interceptor
and MMF activity slot go, the ADR-0048 pool stays and matters more): 3.1, 4, 6, 8. The owner
asked for the design first and the sequencing after the lock: 1, 17.

**What a process boundary is for.** A boundary is earned by a differing runtime, failure domain
or scarce-resource owner, never by rate of change; between the two JVMs none differs, and what
does differ (recovery, partition) is a priced loss: 2, 13. The split never isolated retrieval
from indexing; its real property, API availability during a Worker restart, is judged against
the runtime contract and not worth a permanent internal distributed-system contract: 6. The
pact is directional (the Head supervises the Worker under 627's budget; Tauri supervises
nothing), so the merge deletes the only supervised restart and supervision gates the flip; the
Worker restart is also the reload path and in one process a restart drops every session: 7.
Encoders in-process protect less than the split, so stage 1 must stand on its own terms, not on
credit from a host that may be late (the risk register records a lifecycle trigger that fired
without follow-through): 5. The merge removes the wire, not the encapsulation; operation
contracts are re-homed, not deleted: 3.3, 6. Lucene is single-writer, so remote use is one
Engine and N clients over the whole-engine API: 6. Gains are expected until a paired run
measures them; the default flips on a joint envelope and a failed envelope is answered by the
decision rule, not by re-splitting: 4, 16.

**One process, shared resources.** Two JVMs partitioned memory and threads by construction; one
address space holds heap, direct memory, ORT arenas, Lucene mmap and Tika, one OOM takes the
API, a wedged batch starves it through a shared pool, and the Worker's deadlines that bounded a
runaway agent loop from outside are replaced by admission plus per-operation budgets: 8. The
Engine owns children that outlive a crash holding VRAM and ports: 7.2. Both launchers start the
Head with `UseSerialGC` and `TieredStopAtLevel=1` (85 % empty heap, 917 Derisk 1); a JVM that
indexes needs its own set: 8. Windows has no graceful signal for a JVM and a hung engine answers
no HTTP, so the shutdown trigger is out of band; the updater's receipt protocol, the hang path
and a normal quit need one ordered shutdown, and a release that cannot boot must still be
replaceable: 7.3. Readiness must stay staged by component: 7.6. The HTTP listener now has Tika,
ORT and every root in reach, so contract changes are enumerated, not assumed absent: 6. Hot
reload must cover the whole engine (12); GPU scheduling is one policy, never one owner (4). A
split-mode fallback release would make four mechanisms dual-mode; an alpha with an updater
ships a fix release instead: 15. "Head never touches Lucene" lives in `CLAUDE.md`, `AGENTS.md`,
the baseline brief, skills and postmortems; the sweep reaches `.claude/`: 17.

**Agents are the developers.** Every line is written by agents whose scarcest resource is
context; one process is a chance to make the Engine cheap to inspect and a risk of one wall of
text, and the measure is how much unrelated knowledge an ordinary change needs: 10. Each agent
may run its own instance: the verification profile removes the 40 s stack and the shared lease
now; the inference host separates GPU ownership later: 10, 12.

**Later ideas are pinned, not designed.** The eight owner ideas reduce to an open port set, an
engine context, three edges, durable operations that resume and encoders at request time, each
a parameter today and a migration later: 3.3 to 11. Idea 8 is tier 1 here, tier 2 on the
inference lane, tier 3 deferred (9). Trusted-network sharing and any remote model change the
non-goals and need an ADR first (6, 9); optionality is never authorization.

## 1. What this tempdoc adds to the brief

Brief v2 says *what* to merge (in-process transport behind a flag, flip the default, delete
split mode and sweep; the flag and that order were withdrawn on 2026-09-07, 15 and 17), not
where the product's process boundaries belong nor what shape the one process must have so
later ideas and the agents building them are not fighting it. This tempdoc
fixes the end state, names the seams that survive and why, gives the Engine a structure and a
resource model, records the decisions, and changes what must exist before the flip (7, 8), what
the sweep deletes and pins, and the flip's gate (16).

## 2. The principle

A **process boundary** earns its cost when at least one of three things differs across it: the
runtime and its toolchain churn (CUDA, llama.cpp); the failure domain (native faults, untrusted
input); ownership of a scarce resource (the GPU). A **module boundary** (an ArchUnit-pinned
interface in a contract module) is enough when only the *rate of change* differs.

**Decision rule, for this lane and after it:** the modular Engine is the default placement. A new
process boundary must be justified by a persistent resource, recovery, security or deployment
requirement that outweighs its coordination cost. A boundary that fails that test is removed; a
requirement that passes it gets a boundary, even if that means adding one later.

| component | runtime | failure domain | owns | boundary that fits |
|---|---|---|---|---|
| HTTP + MCP API, agent loop, conversation, RAG assembly | JVM | Java exceptions | nothing scarce | module |
| Lucene index, SQLite job queue, indexing loop, pacing, durable stores | JVM (Lucene 10 is pure Java over Panama) | Java exceptions | disk | module |
| ONNX encoders (embedding, NER, SPLADE, reranker, citation, BGE-M3) | ORT + CUDA (native) | native faults | GPU (VRAM) | in-process in this lane, on its own terms (5); a process boundary is the inference lane's decision under this rule |
| generative LLM | llama.cpp (native binary) | native faults | GPU (VRAM) | process, already |
| document parsing (Tika, PDF, Office, archives, OCR) | JVM + native parsers | untrusted input | CPU | process, already (lane C pool) |

The Head/Worker split runs between rows 1 and 2. Nothing in the three criteria differs there;
two things do, and the argument must not pretend otherwise: recovery (a Worker OOM or wedge
leaves the API answering today) and resource partition (two heaps and pools by construction).
The Worker serves both search and indexing, so the split never isolated retrieval from
indexing; it isolates the API from the process that owns the index. That property (6) is bought
with a full IPC substrate (MMF bus, port handoff, config-snapshot tier, three argv builders,
deadline categories, 49 RPCs, `RemoteKnowledgeClient`; 917 §1 to §9) whose lasting cost is that
ordinary changes to retrieval, orchestration and state cross an internal distributed-system
contract. The case is the decision rule, not the table: recovery and partition are real, are
listed as losses (13) with what replaces them (7, 8), and are judged not worth that
coordination cost for this product's envelope. A paired run (16) can overturn that judgment.

## 3. Target architecture

### 3.1 Processes

```text
Tauri shell (Rust)  supervises ->  Engine JVM  --(HTTP/MCP)-->  webview, MCP clients, later: paired devices
                                      |
                                      |-- inference contract ---> [stage 1] ORT sessions in-process
                                      |                            [target]  one or more inference hosts
                                      |-- generative backend ----> llama-server.exe (native, unchanged); remote: needs an ADR (9)
                                      |-- ExtractionSandbox -----> extraction child pool (lane C, unchanged)
```

- **Tauri shell.** UI host and, in production, the supervisor of the Engine (section 7); in
  development the dev-runner plays the same role under the same contract. Each launch shape has
  its own recovery promise, stated rather than implied: Tauri and the dev-runner restart under
  the budget and reach the terminal state; a bare CLI or `app-launcher` run is an explicitly
  **unsupervised mode**: no restart, sessions lost, children reconciled only at the next start.
  Every general statement in 7.6 ("API restored", "children never leak") is scoped to the
  supervised shapes.
- **Engine JVM.** Everything in rows 1 and 2 of the table. One heap, one AOT cache, one log
  stream, one JDWP target, one `ResolvedConfig`. Entry point `HeadlessApp` (renamed later).
- **`llama-server.exe`.** Unchanged; ADR-0001's argument holds: a large foreign API and a VRAM
  lifecycle that must not share a fault domain with the index.
- **Extraction child pool.** Kept, and worth more after the merge because a parser crash would
  now take the API with it. Its routing table becomes a pin and the in-process fallback for the
  routed families is deleted (6); ADR-0048's probes survive. Its children are JVMs spawned by
  the Engine: ownership, not supervision (7.1).
- **Inference hosts (5).** Encoders behind a contract the Engine consumes; the host owns native
  sessions, model lifetime and execution resources.

### 3.2 Inside the Engine: three rings

The merge makes it possible, for the first time, to say what the one process looks like inside.
Three rings, each a module set with an ArchUnit-pinned direction of dependency (outer depends on
inner, never the reverse):

```text
API front  bind, Host/Origin checks, auth, request identity, admission, HTTP, SSE, MCP (today: ui)
  Core     ports (3.3), engine context (3.4), orchestration (agent loop, conversation, RAG,
           operations), index runtime, durable stores, pacing, executors
           (today: app-services, app-agent, worker-*, adapters)
    Edges  platform | egress factory | generative backend | inference contract | extraction sandbox
```

- **API front** is the only ring that knows there is an inbound network, a webview or a loopback
  bind (outbound connections are an edge, 9). *Who* is calling is resolved here into an engine
  context (3.4) and handed inward; the front enforces admission per context (8). The core never
  assumes the client is the local webview: bind, pairing, device tokens and grants are front
  work, which keeps trusted-network sharing (idea 7) possible without a rewrite.
- **Core** is where the merge pays: retrieval, orchestration and state in one address space,
  reached through ports. No Lucene type crosses a port (6).
- **Edges** are the contracts through which the Engine touches what is not Java-in-this-process:
  the operating system, the network, a model, a parser. The ring holds the interfaces;
  implementations live in their own modules, bound by the root (9). A second GPU runtime or a
  macOS binary layout is a new implementation of an existing edge, not a new hole.
- **The composition root** (`app-engine`, `io.justsearch.app.engine`) sits outside the rings,
  binds every implementation and owns the two sequences that span rings, startup (7.6) and
  shutdown (7.3). Core calls outward only through an interface core defines and the root
  implements (the recomposable registry, 7.4, is the pattern).

### 3.3 Ports: an open, catalogued set

The engine API is a **set of ports**, each an interface in a contract module (`core` or
`app-api`), catalogued with its owner and consumers, and ArchUnit-pinned so only the composition
root binds an implementation. Ports on day one:

| port | exists today as | notes |
|---|---|---|
| search | `SearchPort` (`core`) | unchanged contract; `SearchTrace` stays the one authority for "what the pipeline did" |
| indexing | `IndexingService` (`app-api`) | gains the provenance parameter (3.4) and an immediate index-and-return call (section 4) |
| operations | operation surfaces (`governance/operation-surfaces.v1.json`) | interactive versus durable split; reconfigure as an operation (section 7) |
| memory | the memory API and `remember` operation (Head-side today) | moves under the core's durable stores; no new behaviour in lane F |
| health and self-description | `/api/health`, `/api/debug/state`, live witness | becomes the engine's component map (section 10) |

Adding a port is a catalogue entry, an interface and a composition-root binding. Rule 6b (917
Derisk 4) is the outer pin: only `io.justsearch.app.engine..`, `io.justsearch.indexerworker..`
and `io.justsearch.adapters..` may depend on `io.justsearch.indexerworker.{server,services,loop}..`.
**Sharing a JVM does not license application code to reach past a port into Lucene.**

### 3.4 Engine context: identity and provenance

Every call into the core carries an **engine context**: client kind (webview, MCP client, CLI,
paired device, supervisor), client id, session id, grant, source tier, and two work axes that
the design keeps separate because they answer different questions: **survival** (`interactive`
or `durable`: does the work outlive an Engine restart, 7.5) and **urgency** (`foreground` or
`background`: does the work's resource demand deserve immediate service, 4, 8). The axes are
independent: a chat turn is interactive and foreground; bulk ingestion is durable and
background; a user-requested extraction is durable *and* foreground while the user waits for
it; an idle-time self-benchmark is interactive in survival terms and background in urgency.
Restart policy reads survival only, scheduling and `ForegroundLoad` read urgency only, and no
code infers one from the other; the default mapping (interactive to foreground, durable to
background) is a convenience at the front, not a rule in the type. Every write derives its
**provenance** from that context plus what it was derived from (source documents, model,
timestamp). The context is the unit of admission accounting and the producer of
`ForegroundLoad` (8). The session id is **attribution only**: MCP session semantics are a
transport property that the protocol is already revising (the 2026-07-28 revision moves to
per-request metadata), so nothing durable in the core (an operation, a grant lifetime, a
conversation's persisted state) is keyed on it; the API front absorbs that evolution.

**Client kind is a declaration, not a verified role.** ADR-0046's boundary is the same-user
native process: the per-boot token is handed to every legitimate local client through the
bootstrap route and is readable by any same-user process, so possession of it distinguishes
nothing inside the boundary. The design therefore claims no privileged role. The kind is a
cooperative label used for attribution, admission accounting and logs, and is a reliable
isolation unit only once a client identity with a defined basis and lifetime exists (idea 7's
pairing and device tokens, behind their ADR). Nothing safety-relevant hangs on the label: the
liveness route the supervisor probes is exempt from admission *for every caller* because it is
cheap and read-only (7.1, 8), not because the caller claimed `supervisor`; and a client
claiming a kind it is not gets that kind's accounting bucket, which inside a same-user
boundary is a self-inflicted mislabel, not an escalation. Per-client admission is therefore
fairness among cooperating local clients until idea 7 gives identity a basis.

One record, several lifetimes: the request ends, a grant can be revoked, provenance is
history; a durable operation stores the grant *reference* and re-resolves it on resume (7.5),
never reviving authority captured in an old request. Lane F introduces the type, threads it
through the ports, and records provenance where a slot exists: `DurableGrantStore` has one
today, and `ActionEvent.originator` and `.transport` are the seed for the ingestion ledger and
request logging, which get their column and attribute in C1 *(corrected 2026-09-07, 17.9: the
grant store is the only slot today)*; it adds no index field, which is lane D's register (11).

### 3.5 Location transparency

Only where a process boundary is plausible: the inference contract and the generative backend
accept in-process, local-process and remote implementations interchangeably. The ports do *not*:
a transport-neutral index facade would cost the in-process retrieval that is the point.

## 4. What the merge is for

The case the lane makes is **lower coordination cost**: the IPC substrate of section 2 deleted,
one process to attribute, and the section 12 workflow deltas. Latency and footprint are an
envelope the flip must stay inside (16), not the claim; a measured gain is not attributed to
the merge without a paired run, since 917 Derisk 1 found the Head's launch flags wrong
independently of any merge and post-enrichment latency dominated by GPU contention. 917
measured only the Head (RSS 377 MB idle, 436 MB loaded, live heap 13 to 68 MB); there has been
no paired single-versus-split run. The gains below are expected, not measured.

- **Agentic retrieval at in-process cost.** An agent turn issuing dozens of small searches,
  fetches and rerank calls today pays serialization and a retry policy on each; in one JVM they
  are port calls. Cheaper calls are not more useful answers: the agent-utility evidence shows
  adoption, not accuracy, so call count is never a product objective (16 proves semantics held).
- **Streaming through contracts.** Results, slices and RAG context flow as bounded pages behind
  the search port; the core acquires a searcher per page and releases it before the page
  returns, and a cursor carries a generation token it re-acquires for the next page. An evicted
  generation is a `cursor expired` reason code, never a silent switch to the latest index.
  `SubscribeIndexingJobs` and `ScanRoot` become in-process flows.
- **Immediate index-and-return.** A small document or a fact is indexed and its identity
  returned in one call, port-level only in lane F (no HTTP or MCP exposure, 6). The call returns
  at NRT visibility; durability follows the commit cadence unless the caller asks for `durable`,
  which waits for the next group commit (coalesced within a bounded deadline, never one fsync per
  call). The response says which it got; a durability test is its in-lane consumer (16). It is
  the capability agents-write-back and batch extraction stand on (11).
- **Encoders as request-time services.** Query-side NER, embedding of facts and reranking inside
  the conversation engine reach the same sessions indexing uses (stage 1) or the same host
  (target). A shared ORT session is serial, so a foreground call queues behind the batch already
  on the GPU. The bound of **one batch** is a scheduling property, not a pacing one: pacing
  lowers the *rate* of submissions and removes nothing already queued. It holds today because
  exactly one thread produces batches per stage (the `indexing-loop` thread, the sequential
  `BackfillScheduler` cycle) and `NativeSessionHandle` gates `session.run()` with a one-permit
  semaphore; lane F pins that with a test (16), since a second producer would break it silently.
  The semaphore is unfair, so a waiting foreground call can lose the race to the next background
  submit; stage 1 therefore honours the contract's `foreground`/`background` priority with a
  **two-level session gate with aging**: a foreground acquire goes ahead of any waiting
  background submit, background batch sizes stay bounded, and a background submit that has
  waited past an aging threshold (a count of foreground seatings or a wall-clock bound, the 16
  run picks the value) is seated next regardless. The aging term is the background side's
  service guarantee: without it a continuously non-empty foreground queue would starve indexing
  indefinitely, which is the failure ADR-0048's duty cycle was introduced to end, and pacing
  cannot prevent it because pacing bounds what indexing submits, not whether the gate ever
  serves it. The two promises are stated so they can both be true, because strict foreground
  precedence and bounded background service cannot: the gate establishes **foreground
  precedence with one bounded exception**. A queued background batch never overtakes a waiting
  foreground call *except* the one batch the aging threshold has matured, which is seated once
  and then re-queues behind every foreground call waiting at that moment. *(amended
  2026-09-07, review seven)* That sentence alone does not give the bound the design claims:
  one aged batch per aging period is not one aged batch per waiting foreground call, since a
  call deep in the queue can wait across several periods and be passed in each. The rule is
  therefore stated at the lifetime of a waiting call, not per period: **when an aged batch is
  seated, every foreground call waiting at that moment is marked `passed`, and aging is
  suspended while any `passed` call is still waiting.** A foreground call is overtaken by at
  most one aged batch in its whole wait, whatever the queue depth or the threshold. The
  background side's guarantee follows from admission (8), not from the gate alone: the
  foreground calls a session can have waiting are bounded by the per-context and aggregate
  admission caps, so the `passed` set is finite and drains within that cap's worth of
  foreground calls, after which aging resumes; under a continuously non-empty foreground
  queue, background progress is one batch per aging period plus the drain of one admission
  cap. A foreground call's wait at the gate is bounded by the running batch plus at most one
  aged batch plus the foreground calls admitted ahead of it. That is the delay the owner
  accepts so the corpus keeps becoming searchable, and 13 prices it as such. The gate
  establishes nothing beyond it: a foreground call still waits behind the running batch (never
  preempted), behind earlier foreground calls on the same session (admission bounds their
  number, 8), and behind device-level interference from llama-server, which the gate does not
  see. The 16 row is therefore stated relative to the admitted workload and under the corrected
  contract, and the target host honours the same precedence-with-exception in its queue (5).
- **One scheduling policy for the GPU.** `main_gpu_active` and `energy_reduced` become an
  in-process gauge; the MMF slots go with the wire. One *policy* across workloads, not one
  owner: llama-server stays a separate consumer and ORT's arena limit bounds only its arena.
- **One lifecycle, one recovery story.** Index, job queue, memory and conversation state start,
  stop and crash together; durable stores stay an open set under
  `governance/store-recoverability.v1.json`. Not atomicity: Lucene commits and SQLite
  transactions keep separate scopes and reconcile-on-start still applies.
- **An embeddable engine, and one AOT cache.** The `app-engine` root is a library through named
  profiles (10): eval, CLI indexing, test fixtures, the self-benchmark. No port handoff, no
  `lib/worker` jar duplication.

## 5. The inference seam: transitional stage, then the target

**Ownership rule (durable):** the Engine consumes inference capabilities and results; the
inference implementation owns native sessions, model lifetime and execution resources. This rule
outlives the present shape and name of `InferenceSurface`.

**Stage 1 (this lane), acceptable on its own terms.** Encoders stay in the Engine JVM behind
`InferenceSurface` (`indexer-worker/.../server/InferenceSurface.java:41`, a record of
`Optional<...Assembly>` values from `InferenceCompositionRoot.compose(...)`). A native ORT or
CUDA fault kills the engine and the supervisor restarts it (7); the encoders reload (about
40 s). The counterfactual is answered: **if the inference lane never ships, stage 1 is still the
product's architecture.** Under the split the same fault kills the Worker and its index, so
search is down until the Worker restarts under the 627 budget; in one process the delta is the
sessions and in-flight turns lost plus the API restart, the recovery row of 16, with today's
Worker restart rate in `worker.log` as the evidence. Stage 1 protects strictly less (13) and is
credited with none of the host's properties. It must also support **in-process recomposition**:
closing the surface and composing a new one from changed config without a process restart
(7.4), the encoder component reporting `reloading` while text search keeps working.

**Target (the inference lane, an improvement, not a precondition).** Encoders run in one or
more **inference hosts** behind the same contract:

- an Engine talks to a host through a narrow, versioned protocol that serves both index-time
  batches and request-time small calls (query NER, a single embedding, a rerank of twenty
  passages) under the contract's priority and aging (4): a foreground call goes ahead of *waiting* batches,
  never ahead of the one running (GPU stream priority does not preempt), so the bound stays one
  batch and the host earns its latency by sizing background batches small; engine-side edits do
  not restart the host and models stay warm across engine restarts; reconfiguring encoders
  becomes a host restart with the API untouched;
- hosts are shared within a compatible **user, runtime and model environment**; a shared host
  is a correlated outage for its clients, so sharing is allowed, not mandated;
- the contract is **runtime-neutral**: CUDA is one implementation; CoreML, DirectML, Vulkan or
  CPU are others, and GPU probing and status (about 140 references) sit behind the host (idea 8
  tier 2); an in-process provider remains a valid implementation (CPU fallback, CPU eval).

**What lane F owes the target.** The assemblies are resource-shaped (`EmbeddingAssembly` is
`SessionHandle` + shape + tokenizer + capabilities), so promoting them to an operation-shaped
interface (embed, rerank, tag, expand) is work lane F does not do and must not make harder:
`InferenceSurface` and the `*Assembly` types gain no Lucene, proto or API-layer dependencies
(ArchUnit-pinned); only `app-engine` and `indexerworker..` compose or hold `InferenceSurface`
(rule 6b); index-time throughput and request-time latency enter the 16 baseline. Placement
freedom is not semantics, so the **semantic obligations** the host contract must carry are
named now: model identity and capability set per call, cancellation, partial failure, and the
binding of a query embedding to the index generation it may search (7.4 keeps model identity as
generation metadata). Triggers that pull the lane forward: field data showing encoder faults; a
second GPU runtime or OS; concurrent verification stacks (12); stage 1 reconfigure cost.

## 6. The wire: what is deleted, what is kept, and what the split really gave

**gRPC and the MMF bus are deleted, cleanly.** Keeping the proto contract "in case a remote
worker is wanted" preserves the wrong seam; deleting the MMF bus removes the largest
Windows-specific substrate in the product (9). **Operation contracts are kept.** Cancellation,
deadlines, bounded work, per-batch memory limits and backpressure are requirements of the work,
not of the network; today they hang off the wire (deadline categories, the `FetchDocuments`
byte budget, streaming flow control) and the sweep re-homes each as a port property. None may
vanish with the channel; a collector bounds neither queues nor CPU nor disk contention.

**The merge is contract-invisible except for an enumerated list.** External MCP clients are
outside the blast radius for everything but one item they must rely on after a crash, the
operation outcome contract (7.6), which is listed below as a contract change and not hidden in
a recovery section; new port calls (4) get no HTTP or MCP surface in this lane. The changes
visible at the HTTP, MCP, operation, Tauri-event or registered-surface level are these, each
with the gate that actually detects it (a gate that passes in either state is not the gate); a
contract diff per step catches any other:

| change | gate |
|---|---|
| readiness reason codes re-cut by component (7.6); `readinessNotice.ts` follows | `check-readiness-reason-codes` for the vocabulary **plus** a test on the emitted vector's component keys |
| admission rejections and `cursor expired`: new response classes with reason codes, reaching the webview (8, 4) | contract diff; the webview's client handles both |
| `core.restart-worker` retired with the Worker; its `structuredData.port` consumers re-pointed at reconfigure (7.4) | `operation-surface` |
| runtime manifest gains a child registry (7.2) | manifest schema test (versioned schema); the closure check does not read fields |
| `supervisor.v1.json` beside the manifest (7.1); a new `justsearch://supervisor-state` event, `backend-restart` untouched | closure check's sibling allowlist, and `lib.rs` and `dev-runner.cjs` taken out of its `SKIP_PATHS` for this file so the new writers are checked rather than exempt *(corrected 2026-09-07, 17.9: both are exempt today, so the check alone would pass vacuously)*; shell event tests |
| `/api/lifecycle/shutdown` kept as the cooperative trigger of the one shutdown; `commit-shutdown` becomes its updater-nonced front half (7.3) | updater state-machine tests |
| `core.head-log` and `core.worker-log` diagnostic channels collapse to `core.engine-log`; the surface altitude derived from them is recomputed (10) | `operation-surface`, `surface-altitude` |
| debug and state shapes registered (10); `diagnostic-channel` producer enum loses the gRPC stream | contract-surfaces register |
| the operation outcome contract (7.6): every write and reconfigure accepts a **client-supplied operation key**, the outcome query answers by that key, and the supported client set for recovery is named (the webview, the MCPB bridge, the CLI, and a generic MCP-client recovery harness that exercises the protocol itself, *decided 2026-09-07, lock*); other MCP hosts get a tested protocol, not a tested host, and the README names no host | contract diff; the 16 workflow-recovery row exercises each named client and the harness |

**The merged classpath is a larger attack surface.** The HTTP listener now has Tika, ORT and
every indexed root in reach. Two properties become pins. First, the extraction sandbox's routing
table is the authority on what parses in the Engine: the families whose parsers can wedge or
exhaust a heap (PDF, Office, archives, images, unrecognised binaries) parse only in the child
pool, their in-process fallback is deleted (a broken pool fails those families visibly), and an
ArchUnit rule confines those parsers to the child's module; text, markdown, code and CSV/JSON
decoders stay in the Engine by the same table. Second, an **authorization invariant**: identity
is resolved at the front, authorization is effective wherever protected or derived data is
selected. Today that is the grant model on every file access from an API path; the ports carry
the engine context so derived data (snippets, counts, entities, memories, cached results) can
be checked at the same depth when idea 7's per-root ACL arrives, and no port may make a grant
checked once at entry the only check. The OS-level privilege-separation option is lost (13).

**Proto DTOs as internal parameter types are transitional**: without a wire they carry its cost
and none of its benefit; a named follow-up replaces them at the ports with the `app-api` records
(ADR-0025's dual-type layering collapses to one type). **The remote seam is the whole-engine
API**: an engine on a NAS or shared machine (idea 7) is the Engine JVM running there with
Tauri and MCP clients on the versioned HTTP and MCP contract, one Engine and N authenticated
clients, never N engines on one single-writer index. Invariant 2 binds loopback, so remote
deployment is separate work behind an ADR; lane F contributes 3.2. The one future the proto
deletion closes is a non-Java index engine.

**What the split really gave.** The Head survives Worker death and hang and restarts it under a
budget (`SupervisionPolicy`, 627: 3 restarts, 1 s to 30 s cooldown, 300 s stability window, 3
unhealthy polls count as a hang) while the API answers: genuine independent API availability,
judged against the runtime contract, not the desktop window. JustSearch is a local knowledge
runtime whose first client is the shell, so an MCP client losing its session while the window
stays open is a runtime cost (13). It is judged not worth a permanent internal
distributed-system contract because the runtime contract promises no uninterrupted availability
and the supervisor's cooldown is the outage; a headless multi-client requirement re-opens it
under the section 2 rule (14).

## 7. Lifecycle: supervision, shutdown, reconfiguration, readiness, operations

**What exists today.** The Worker is supervised by `WorkerSpawner` on death and hang paths
under one `SupervisionPolicy` budget (terminal `WORKER_RESTART_EXHAUSTED`), and `restart()`
doubles as the reload path for config-apply, AI install and pack import (five call sites). The
Head is **not supervised**: `lib.rs` spawns it once, follows the port manifest, emits
`justsearch://backend-restart` on an instance-id change and restarts only for the updater
handoff; the dev-runner observes exit (730 B2) and has no restart. `HeadShutdownCoordinator`
already owns the Head's idempotent ordered close, shared by the JVM hook, normal quit
(`POST /api/lifecycle/shutdown`, which the shell calls) and the updater's two-process protocol
over `POST /api/upgrade/commit-shutdown` ending in the nonce-bound receipt; neither trigger
reaches a hung Head. Readiness is staged by process (HTTP about 3 s, Worker about 8 s, encoders
about 40 s) with Worker-shaped codes. Ingestion jobs live in `SqliteJobQueue`; agent turns are
in-memory; the manifest records the Head's PID, not its children.

**Consequence.** The flip deletes the Worker's supervision and restart-as-reload and inherits the
Head's absence of supervision: *less* recovery and no reload path unless both are built first.
Everything in this section therefore **gates the default flip**.

### 7.1 The supervisor contract (one, two implementations)

Tauri in production and the dev-runner in development implement the same thing; there is no
third implementation. The `SupervisionPolicy` semantics are ported, not reinvented:

| element | Engine supervisor |
|---|---|
| states | `starting` (no hang detection until `api` readiness or a start deadline), `running`, `stopping` (a request file is present or a requested restart or upgrade is in flight; hang detection suspended; the request's deadline then forced kill), `restarting` (attempt, next try), `exhausted` |
| death path | child exit observed; wait for the process handle to close (Windows keeps file handles until then); restart after cooldown; the restarted Engine reconciles its children (7.2) |
| hang path | in `running` only: liveness probe (`/api/health` on the manifest port), a route admission never gates for any caller because it is cheap and read-only (3.4, 8), so throttling can never read as a hang and no role claim is involved; N consecutive failures with the process alive count as a hang; a shutdown request over the out-of-band channel (7.3) with a deadline, then forced kill, then the death path |
| requested restart | the same request with reason `restart`; wait for exit; start; not counted against the crash budget. Used by restart-required settings (7.4) and by the dev-runner's restart; the updater uses reason `upgrade` and launches the installer instead of restarting |
| budget | max restarts, exponential cooldown with a ceiling, stability window that resets the count. *(re-cut 2026-09-07, lock)* The 627 values seed the numbers; their meaning is re-cut for the Engine because the Worker's budget assumed a live API in front of it and transient faults behind it. **Exit reasons have three classes**: *requested* (`restart`, `upgrade`; not counted); *transient* (native crash, the out-of-memory exit, a hang; counted, retried under cooldown); *non-transient* (a boot failure whose exit code says the same input fails again: invalid configuration, port in use, a store at a schema the release cannot open; `exhausted` at once with that reason, no retry, since three retries of a port conflict are forty seconds of flapping before the same answer). **Cooldown is the outage**: the floor is the process handle closing, which is a fact the death path already waits for, not a number; the ceiling is a few seconds above it, because the restart count bounds a loop and a long cooldown only lengthens dead-API time. **The stability window runs from `ready`** (the essential components), not from spawn, since the Engine's boot carries the encoder load and a window counted from spawn is mostly consumed by it. **Hang parameters are set with the collector at stage E**: the health endpoint and indexing allocation now share a heap, so the poll interval times the miss count must exceed the worst safepoint pause the soak observes; ported values would read a long pause as a hang |
| visible state | `supervisor.v1.json` next to the port manifest carrying the state; the shell forwards it as a new `justsearch://supervisor-state` event (`backend-restart` keeps its instance-id trigger), and `quick_health`, jseval and the dev MCP read the file. No engine API carries it, since the engine is down when it matters |
| terminal | `ENGINE_RESTART_EXHAUSTED` with the last exit reason; on this path only, the supervisor kills the children listed in the manifest so nothing holds VRAM behind a dead product |
| never | the Engine's supervisor is itself a JVM, or is on the Engine's classpath. (The Engine spawning extraction JVMs is ownership, not supervision.) |

**Conformance.** A harness with a fake engine (exits with a code, hangs, or answers health)
drives both implementations under their own test runners; "one contract" is true only while
both pass it.

### 7.2 Children: adopt or kill

The Engine owns children that outlive its crash: llama-server (VRAM, a port) and the extraction
pool; an orphaned llama-server means the restarted Engine cannot load models. Two mechanisms: a
**child registry in the runtime manifest** (each child recorded at spawn with PID, start time,
executable identity and its port or pipe, removed at exit; a versioned schema bump under a
schema test, 6), and **reconciliation on Engine start** (a listed child whose identity checks
out, whose health answers **and whose recorded configuration matches the applied config
version the Engine boots from** is adopted; `app-inference` has an adopt-by-port path today
that checks the HTTP shape only, holding no process handle and comparing no identity or
config, so the identity and config match are new *(corrected 2026-09-07, 17.9)*;
identity and health say the child is alive and ours, not that it is running the model and
arguments version A names, so the registry entry carries the child's config identity (model
path, argument hash) and a mismatch, which a crash mid-reconfigure can leave behind (7.4), is
stopped and respawned from A rather than adopted; anything
else listed is killed by PID with identity evidence, never by port alone; extraction children
keep a parent-liveness watch and exit on their own). The supervisor does not kill children on
the death path, or adoption could never fire (7.1).

### 7.3 One ordered shutdown

One sequence, one owner, two triggers. The owner is the composition root (3.2), seeded from
`HeadShutdownCoordinator`, since the sequence spans all three rings. The cooperative trigger
stays `POST /api/lifecycle/shutdown`; the supervisor's is a **shutdown request file** in the
runtime directory (reason `quit`, `restart`, `upgrade` or `hang`; deadline; nonce when the
updater wrote it), watched by a dedicated thread on its own executor, never the API pool, since
a hung engine answers no HTTP and Windows has no graceful signal for a JVM. A JVM wedged at a
safepoint ignores the file too; the deadline and forced kill cover that. The sequence:

1. close mutating admission at the API front (reads keep answering);
2. cancel interactive turns with a reason code;
3. checkpoint durable operations (7.5) and stop taking new ones;
4. stop ingest and drain in-flight extraction; the extraction children exit;
5. close the inference surface (stage 1) or detach from the host (target);
6. generative backend by reason: `quit` and `upgrade` stop llama-server (the installer must
   overwrite its binary and nothing may hold VRAM behind a closed product); `restart` and
   `hang` leave it running for adoption (7.2) *(corrected 2026-09-07, 17.9: today
   `InferenceLifecycleManager.close()` stops it unconditionally, so the by-reason branch is
   new work in B)*;
7. commit and close the index; checkpoint SQLite *(corrected 2026-09-07, 17.9: today only
   the upgrade barrier checkpoints the WAL; the ordinary close gains it in B)*;
8. write the shutdown receipt (nonce-bound when the request carried one); exit.

The updater keeps its receipt contract and state machine. Its `commit-shutdown` endpoint becomes
the front half of this sequence (it validates the nonce, closes admission and reports blockers,
then writes the request file) and no longer waits for a second process. The Worker-quiescence
class becomes step 4.

**Upgrade with no Engine (a precondition of no fallback release, 15).** Today every apply path
needs a running Head: `prepare` fails on a missing port or token, a missing child witness ends
in `Cancelled` or `RepairRequired`, and the installer launches only after both receipts, so a
release that cannot boot could not be fixed by a release. The updater therefore gains a
**dead-Engine path**: when the supervisor is `exhausted` or no Engine has bound a port, the
shell skips prepare and commit, reconciles the manifest's child registry (7.2) so nothing holds
the llama-server binary or VRAM, records a distinct no-receipt witness (never a forged
`HEAD_STOPPED`) and launches the installer. The Settings action is already reachable with the
backend down, since update state is shell-owned. A sandbox round exercises the path (16).

### 7.4 Reconfiguration without restart

Config-apply, AI install and pack import today restart the Worker so a new environment takes
effect; in one process that would drop every API, SSE and MCP session. The Engine therefore
exposes **reconfigure** as an operation on the operations port. The ring direction (3.2) forbids
core calling the composition root, so the root registers each recomposable component (inference
surface, index runtime, generative backend) in a **recomposable registry** whose interface lives
in core.

**The invariant is a coherent applied state**, and it is defined at the level of the whole
config, not per component: the applied state is one **config version** (a hash over the
declared settings) that every recomposable component is known to be running; a reconfigure
moves the Engine from applied version A to applied version B or leaves it at A, never at a mix.
Requests observe A until the swap of the last affected component completes, then B; during the
transition the component map (10) shows desired B against applied A and readiness reports the
affected components as `reloading`. **Only success persists**: B is written as applied after
every affected component composed; a compose that fails at any component closes the candidates
already composed, keeps A applied, persists nothing **as applied** and fails the operation with
a reason code naming the component, so no boot inherits a version that could not compose. That
rule is what survives every failure mode below, including the fatal one, because A is on disk
as applied and B never is. Two records, not one: beside the applied version the store keeps an
**attempted version** (desired B, the component it failed at, the reason, the time), written
before the first compose and cleared on success, which is how a restarted Engine can report
"B was attempted and failed with a crash" (below) without B ever having been committed;
persisting an attempt is not persisting an application, and the component map shows the two
under different names.

**Three guarantees, named separately, because one can hold while another fails.** A
reconfigure has been described above as if "A stays applied" carried every promise; it
carries one:

- **configuration commitment**: which settings are durably accepted for subsequent starts.
  Invariant: the applied version is always one that composed in full, A or B, never a mix.
  This always holds, on every path below, because only success writes it.
- **semantic coherence**: which capabilities are allowed to serve together. Invariant: **no
  operation observes an incompatible combination** (a query embedded by one model against
  vectors of another; a SPLADE query against the wrong vocabulary). This is the essential
  correctness property, and it holds in degraded states too, provided a request never reaches
  a component whose applied version differs from the version its inputs were made against.
- **capability continuity**: whether the incumbent remains usable throughout the transition
  and after a rejection. This is the guarantee that is **conditional**, per component and per
  apply, and the design says exactly when it does not hold.

"One applied version for the whole config" is stronger than semantic coherence needs; the
essential property is per dependency, as 7.5's identity rule already is. The first draft kept
a global version as a chosen simplification. *(decided 2026-09-07, lock)* The design now keeps
**two things apart**: the **applied configuration revision**, one global number that is the
accepted-settings record the user reasons about ("revision 42 is applied") and the value a
reconfigure names in its `version conflict` check (7.6); and **coherence, which is
dependency-scoped**: each component declares in the register (7.5) which settings it depends
on, and a component's applied version moves only when a declared setting moves. A settings
change that touches no declared dependency of a component leaves that component untouched,
so a new optional capability never joins a transaction it has no dependency on (13's
organising principle), and the user-facing story does not change. Generation-bound settings
are outside both: the active generation is their applied value (above). "One applied version
throughout" in 16's reconfigure row therefore reads per component: a request never meets a
component whose applied version differs from the one its inputs were made against.

**Continuity is conditional, and the condition is stated per component.** Two things break an
unconditional compose-before-close, and the design claims neither away:

- *Headroom.* Composing a replacement while the incumbent serves needs room for both. For the
  index runtime and the generative backend that is usually true (the index runtime composes
  beside only as a second generation directory, never as a second reader over the same
  directory, which is a documented handle leak and the reason `swapRuntime` is close-then-open
  today *(corrected 2026-09-07, 17.9)*; llama-server is restarted, not duplicated). For the
  inference surface it is a
  VRAM question, and ORT's arena limit bounds only the arena, not the provider's whole device
  footprint, so a nominal budget does not prove transitional fit. The recomposable registry
  therefore records for each component whether it composes **beside** the incumbent
  (compose-before-close: the old component serves throughout) or **in place** (close, then
  compose: the capability is `reloading` and absent for the duration). The inference surface
  chooses per apply from measured free device memory (NVML, which only `gpu-bridge` reads
  today and the merged JVM has in-process) against the candidate's declared footprint, a
  field no model catalog entry carries today and D1 adds *(corrected 2026-09-07, 17.9)*;
  the choice and the reason are in the operation's result and the component map. In-place is
  a coherent outcome: text search keeps working, semantic legs report `reloading`, and
  configuration commitment and semantic coherence hold in both modes. **Capability continuity
  does not hold on the in-place path, and the design does not pretend it does.** If in-place
  compose of B is rejected, A's configuration is still the applied one and A's component is
  gone: the honest state is *applied A, component `unavailable`* with the rejection as its
  reason, a legal degraded state the component map shows as such. Recovery is a **recompose of
  A**, attempted immediately and automatically as the last step of the failed operation; it is
  a second operation that can itself fail (the memory A needs may now be held elsewhere), and
  if it does, the component stays `unavailable` with both reasons recorded and the stuck-
  component policy (7.6) owns it from there. The in-place path therefore trades "the old
  component serves throughout" for "text search serves throughout and the semantic leg comes
  back as A or B, or reports why neither": that is what is promised, and the 16 row tests the
  rejection branch on this path, not only on the beside path.
- *Native faults.* Stage 1 composes ORT sessions inside the Engine's failure domain, so a fatal
  native fault during compose kills the incumbent with the candidate. "The old component keeps
  serving" is therefore a promise about *rejected* composition (a model file missing, a shape
  mismatch, an allocation refused), not about process death. Process death during a
  reconfigure is a crash: the supervisor restarts the Engine (7.1), it boots from applied A
  because B was never persisted as applied, and the component map shows B as the attempted
  version that failed with the crash as its reason (the attempted-version record above is what
  survives the crash to say so). A surviving llama-server child is adopted only if its recorded
  config identity matches A (7.2). The inference target (5) turns this native fault into
  a host restart with the Engine untouched; stage 1 does not pretend to.

An **embedding model change is not a reconfigure**: model identity is index-generation metadata
(7.5), so it is a durable reindex operation producing a new generation, queries keeping the old
one until it lands; a hot swap leaving index and query encoder on different models is the
failure this rule makes impossible. The same rule covers **every change that alters the
compatibility or interpretation of persisted derived data** (a SPLADE vocabulary, a NER label
set the entity layer will store, a chunking parameter, a citation model whose output is
persisted): the config register marks such settings `generation-bound`, and a change to one is
a reindex or rebuild of the affected derived store, never an in-place apply. Settings that need
a process restart (JVM launch flags, 8) are declared `restart required` and applied by the
requested-restart path (7.1).

**A generation transition has a cutover contract, not only a compatibility rule.** "Queries
keep the old generation until the new one lands" says what is true during the rebuild; it
does not say when the new generation is allowed to land, and without that the new generation
can be internally compatible yet stale against what the product accepted meanwhile. The
invariants, held by the reindex operation as a converging operation over the accepted corpus
(7.5; *amended 2026-09-07, review seven*: it was "a finite operation over captured inputs",
which made every source change under a captured unit a failure):

- *Capture and replay.* The reindex captures the document set (ids with content hashes) at
  start. Ongoing ingestion keeps writing the old generation during the rebuild, and every
  accepted change in that window (an edit, a removal, a new document, a write-back) is also
  journalled against the reindex operation; before activation the reindex **replays the
  journal** into the new generation, so the generation that activates covers every document
  accepted up to its activation point, not up to its start. Removal is a journal entry like
  any other: a document removed during the rebuild is absent from the new generation.
- *Activation is atomic and conditional.* The new generation becomes the active one in one
  metadata swap, after replay, and only if its **gap count is zero** (a gap being a document
  whose latest accepted version is absent from the candidate, 7.5; processing history never
  counts) or a **user** accepted the named gaps through the webview, shown the list; a client
  under a grant may report gaps and never accept them, since accepting is a data-completeness
  decision and an agent accepting silently is the incident class this rule exists to prevent
  *(decided 2026-09-07, lock)*. Today's
  Blue/Green cutover
  already swaps `state.json` atomically but then restarts the Worker; activation with the
  Engine up is the new part, 17.4 *(corrected 2026-09-07, 17.9)*. The default refuses *(corrected
  2026-09-07, 17.9: today `index.migration.cutover.max_failed_jobs` defaults to -1, unlimited;
  D1 flips it, 17.7)*: a
  generation missing documents the old one had is a regression the product would silently
  present as "no results", so a rebuild with failed units stays `complete with gaps`, not
  active, and names the units, until re-run or accepted. There is no partial activation.
- *Cursors.* A search cursor holds a snapshot of the old generation; activation retires the
  generation but does not tear down readers a live cursor holds (no reader outlives a request
  today: `SearcherManager` acquire and release per call, and `searchAfter` is stateless, so
  the pinning is new work in D2 *(corrected 2026-09-07, 17.9)*). A cursor whose generation
  has been retired is served to its normal expiry if the old encoder is still loaded, and
  fails with `cursor expired` if it is not; it is never silently served from the new
  generation, which would change its evidence mid-page.
- *Resource envelope.* Building generation N+1 while serving N needs the new encoder beside
  the old one. The reindex declares its device footprint like any reconfigure and the same
  beside-or-in-place choice applies: beside when headroom allows; otherwise the transition
  runs **in-place for the semantic legs** (old encoder unloaded, the new one loaded, queries
  answer text-only with `reloading` on the semantic legs until activation, the old generation's
  vectors still on disk but unqueryable without their encoder). That degraded mode is stated
  here because it is the one the supported floor will actually hit; 16 measures the transition
  on the floor machine in that mode.
- *Ingestion during the transition, by mode* *(amended 2026-09-07, review seven)*. "Ongoing
  ingestion keeps writing the old generation" said too little: in the in-place mode encoder A
  is gone, so a document accepted then cannot receive A-compatible vectors, and "accepted",
  "indexed" and "searchable" have to be promised separately. **Accepted** means the acceptance
  row is durable (7.5). **Indexed** means the document is in the active generation's lexical
  index and text-searchable, which holds in both modes for every accepted write. **Semantically
  searchable** is per generation: in the beside mode A's encoder is loaded and the write is
  fully indexed into A; in the in-place mode the write is indexed into A **lexically, with
  semantic enrichment deferred**, its journal entry carries it into B with B's vectors at
  replay, and the write's response says which it got (`visibility: text-only, semantic at
  activation`, the same field index-and-return uses, 4). Nothing is accepted into a backlog
  without text visibility, and no accepted write is ever embedded by A into B or by B into A.
  Since every semantic query is text-only in that mode anyway, the deferred write is not a
  second-class document among first-class ones; the whole generation is text-only until
  activation.
- *A refused or abandoned transition is not a resting state* *(amended 2026-09-07, review
  seven)*. The in-place mode makes "semantic legs `reloading` until activation" an indefinite
  outage if activation is refused (`complete with gaps`, awaiting the operator) or the rebuild
  is cancelled or fails its attempts budget. The rule is the same as the in-place reconfigure
  rejection above: **whenever the candidate stops progressing, encoder A is recomposed
  immediately and automatically**, B's encoder unloaded (the waiting candidate needs none),
  and A's semantic legs return; the documents written lexical-only meanwhile are enriched by
  A's ordinary backfill, since a deferred-enrichment entry is a backfill item against whichever
  generation is active. The journal keeps recording while the candidate waits. When the
  operator accepts the gaps or re-runs, B's encoder is composed in place again, replay resumes
  from the journal, then activation. On cancel or exhaustion the candidate directory and its
  journal are deleted. If A's recompose fails, the state is `unavailable` with both reasons and
  the stuck-component policy owns it (7.6), exactly as for a rejected reconfigure. So the
  semantic outage on the in-place path is bounded by build plus replay, never by a human
  decision, and 16's generation row exercises the refusal branch on the floor machine.
- *Scope.* The rebuild implementation (chunking, batching, the journal's storage) stays
  outside this lane; the six invariants are the contract lane F owes because it introduced
  the generation vocabulary, and the resume conditions in 7.5 apply to the reindex operation
  under the convergence rule stated there.

**Applied configuration and active generation have one recovery meaning** *(amended
2026-09-07, review seven)*. Two authorities each with a sensible meaning (the applied config
version, the active generation) had no stated joint meaning after an interruption: an
ordinary reconfigure boots from A, an activation selects B's representation, and a crash
between the `state.json` swap and the operation's completion row could read either way. The
rule: **for a `generation-bound` setting, the active generation's metadata is the applied
value**; the settings store holds the desired value, and the `state.json` swap is the effect
that makes it applied (7.5's order: effect before completion). The applied config version is
therefore computed, not stored as one record: a hash over the apply-able settings' applied
version plus the active generation's recorded generation-bound settings (`IndexFingerprint`,
17.4), so the two can never disagree. What is allowed to serve is the triple *(applied
apply-able version, active generation id, journal high-water mark)*, and the encoders
composed at boot are chosen from the active generation's metadata, never from the desired
settings: the index tells the encoders what to be. Recovery then follows 7.5 unchanged: a
crash before the swap leaves A active with the candidate on disk and the operation `running`,
and it resumes replay; a crash after the swap leaves B active with the row `running` and the
effect present, and the row is advanced to `complete` on start. There is no state in which
the settings say B and the encoders serve A's vectors.

### 7.5 Two kinds of work, two promises

The operations port (3.3) distinguishes them and the recovery guarantees (7.6) depend on the
distinction:

| kind | examples | on engine restart |
|---|---|---|
| interactive turn | a chat answer, a single search, an agent loop iteration, a reconfigure (its effect is persisted config, 7.4) | on the cooperative path fails with a reason code the client can show; on an abrupt death gets no response and is queryable by its client-supplied key (7.6); not resumed |
| durable operation | ingestion of a root, a reindex bundle, later: a batch extraction over 400 documents, a self-benchmark run | row-backed; **resumes** from its last checkpoint after restart under an attempts budget |

The job store has no row for an operation today: `jobs` is one row per file path with a per-path
attempts counter, and a reindex bundle or batch extraction has no row at all. Lane F therefore
adds an **operations table** to the same SQLite store (operation id, the client-supplied
operation key it answers to (7.6), kind, survival and urgency (3.4), state, checkpoint
cursor, attempts, timestamps), registered in `store-recoverability.v1.json`; the per-file
`jobs` rows remain ingestion's unit-level substrate beneath it. A durable operation
**checkpoints on a bounded cadence**, per completed unit (a file batch, an extraction page) and
at most every 30 s; shutdown step 3 is one more checkpoint, not the only one. Resume is the
stronger promise, chosen deliberately: a batch extraction merely "not lost" is a feature nobody
ships. A checkpoint says where to continue, not whether continuing is still the same task, so
**resume has four conditions**, each a column of the row: *identity* (the operation's declared
dependency set, below; a changed dependency fails with `inputs changed`, never finishing a
different task under the same id); *authority* (the grant reference is re-resolved at resume,
3.4; a revoked grant fails the operation); *effects* (units are at-least-once under an
idempotent unit key, path plus content hash for ingestion, page id for extraction, so the
window between a unit's effect and its checkpoint repeats a unit, never an effect); *lifetime*
(search cursors, 4, are ephemeral and snapshot-bound; a checkpoint never stores or inherits one).

**Three lifetimes, three words.** "Generation" has been doing three jobs and they are not
interchangeable; the design names them and says how they relate:

| word | identifies | lifetime | who holds it |
|---|---|---|---|
| **search snapshot** | the reader view a read or a page sequence is consistent against | one request or one cursor; evicted on the reader's schedule | a cursor (4); never a checkpoint |
| **representation generation** | the set of persisted derived representations compatible with a given query-side behaviour: embedding model, sparse vocabulary, chunking, any `generation-bound` setting (7.4) | from the reindex that produced it until the next; the index carries it as metadata | a query, which must run against a generation its encoders match; a reindex operation, which produces the next one |
| **operation input identity** | the dependencies that make a resumed durable operation the same task | the operation's life | the operations row |

A query embedding binds to a representation generation, not to a snapshot: snapshots change on
every commit and must not invalidate anything durable; generations change on reindex and must
invalidate every semantic consumer of the old one. A durable operation binds to neither by
default. Its **identity is the dependency set it declares at start**, not a global config
hash, because a global hash is wrong in both directions: an unrelated setting would fail
coherent work, and an unchanged root set says nothing about whether the documents that define
the task still say what they said. The three kinds of durable work declare different sets:

- a **finite operation over captured inputs** (a batch extraction, a self-benchmark run)
  captures the input identities themselves at start (document ids with content hashes, or the
  representation generation it reads) and resumes only against those; a source changed under
  it fails that unit with `inputs changed` while the rest resumes, and the operation's result
  names the units it could not finish;
- a **converging operation over the accepted corpus** *(amended 2026-09-07, review seven)*: a
  reindex is finite and captures its plan (the document set with content hashes at start), but
  its correctness criterion is not "each captured version processed"; it is **the candidate
  holds the latest accepted version of every document the active generation holds, at
  activation**. So a captured unit whose source changed under it is not a failure: it is
  `superseded`, and the journal entry for the newer version is the unit that counts. The
  operation's result keeps two lists, **processing history** (units that failed or were
  superseded, reported, never blocking) and **gaps** (documents whose latest accepted version
  is absent from the candidate at activation), and only gaps refuse activation (7.4). The
  resume conditions apply to the plan (identity is the captured set plus the target
  generation; a changed target fails with `inputs changed`); the journal is the correction
  that makes the plan converge. A reindex earlier classified under the first bullet is
  reclassified here; a document captured at hash H1, changed to H2 and replayed at H2 is
  current, not a gap, and 16's generation row asserts it does not block activation;
- an **ongoing operation over a changing set** (ingestion of a root) declares the root and the
  representation generation it writes into; source change is its normal input, not a
  violation, and a generation change (a reindex landed) ends it in favour of the successor
  operation the reindex started.

**Acceptance, effect and completion are three records, in a fixed order.** Lucene and SQLite
are not one transaction (4), so an operation's rows and its units' durable effects can be
separated by a crash; the design fixes their order so each record has one meaning:

1. **acceptance** is written durably *before the first effect*: the operations row exists
   (state `accepted`, the client's operation key, 7.6) before any unit runs. This is the
   invariant that gives "no row" a meaning: no acceptance record implies no effect, because
   nothing may act before the row is durable;
2. an **effect** is written before the checkpoint that covers it, and "written" means
   *durable in the sense the checkpoint claims*: a checkpoint that says a unit's index effect
   is done follows a Lucene commit covering it, not an NRT-visible buffer, since Lucene's
   buffered changes are lost with the process;
3. **completion** is written after the final checkpoint.

On start, a row `accepted` with no checkpoint is resumed from its start (the acceptance
promise is the only thing owed); a row `running` is resumed from its checkpoint; a row
`complete` is trusted; a row whose last checkpoint precedes an effect the unit key shows
present is advanced past it without redoing it. A client reading a result therefore sees
`complete` only when every effect is durable; `running` with a checkpoint means partial
results are visible and attributed to the operation id, never presented as final; `failed`
carries the units completed before the failure, since a failed operation can have durable
partial effects and a client deciding whether to retry needs that count.

### 7.6 Readiness stays staged, by component

Readiness becomes a vector of component states, not a process state: `api` (HTTP answering),
`index` (open and searchable; text search works from here), `encoders` (semantic legs), `generative`
(LLM), with the reason-code vocabulary re-cut from Worker-shaped to component-shaped codes and
walked through `check-readiness-reason-codes` plus a test on the emitted keys (6). The UI
behaviour is unchanged: text search before encoders, the same notices, no 40 s blank. Two
readiness representations exist today, the three-slot `LifecycleSnapshotV1` and the
ten-dimension `ReadinessEnvelopeView`; D1 makes the component vector the one authority and
the other a projection of it or retired (which name survives is the owner's, 17.7), and no
per-component start deadline exists today, so those are new *(corrected 2026-09-07, 17.9)*.

Three questions stay distinct: **liveness** (the probe answers; the hang path reads only this),
**readiness** (the component vector; an intentionally absent encoder is `absent`, not `failed`,
never a hang) and **restart-worthiness** (the supervisor's budget); an answering endpoint with
`index` not ready is alive and not ready, never restarted for that.

**A stuck essential component has a defined outcome.** "The probe answers" and "a required
component never becomes ready" can both be true, and the supervisor's liveness-only view must
not leave that state undefined. Every component declares a **start deadline**; a component
still `starting` at its deadline becomes `failed` with a reason code (the last error, the
resource it waited on), readiness reports the degraded vector, the UI shows the component's
notice, and the component map (10) carries the evidence pointer. Recovery is **local first,
escalated when local recovery cannot restore the floor**, and the two are bounded separately:

- *Local recovery* is a **reconfigure of that component** (7.4, a retry with the same config
  is the degenerate case), which a user or a client can trigger and which the shell offers on
  the notice. It is preferred because the other components are serving and a restart would
  trade a degraded Engine for an outage. It is attempted automatically up to a small budget
  (the register's per-component attempt count) before the state is left to the user.
- *Escalation.* A deadline changes what the Engine knows, not what the stuck attempt holds: an
  index open wedged on a lock or a native handle keeps that resource, and a second in-process
  attempt runs into the same lock. So "never a process restart" was a rule a deadline cannot
  justify. For an **essential** component (`api`, `index`: text search is the product's
  floor), when local recovery has failed its budget *or* the failed attempt reports a resource
  it cannot release (the reason code says which), the Engine requests its own restart through
  the supervisor's requested-restart path (7.1), which counts against the same restart budget
  a crash would. That keeps readiness out of *indiscriminate* restart policy (an optional
  component never escalates; an essential one escalates only after bounded local recovery)
  while refusing to preserve an indefinitely healthy HTTP endpoint over a product that cannot
  search. The balance (attempt budget, which reason codes escalate immediately) is the
  owner's; the design fixes only that both paths exist and the order between them.

`encoders` and `generative` failing is the same degraded mode the product already shows
before AI is installed and never escalates. The supervisor's terminal path is reached through
crashes, hangs and this one bounded escalation, never through readiness alone.

**Recovery is a usable workflow restored, not a port answering.** The supervisor's promise is
that the process comes back; the product's promise, which the gate (16) measures, is that a
supported client can do useful work again, and that has four parts the design states
separately because they fail separately:

- *readiness by component*, since API-answering, text-search-ready, semantic-ready and
  generative-ready are four different user-visible outages (16 reports each);
- *client re-entry*: the per-boot token changes on every Engine start (ADR-0046), so every
  external MCP client re-runs the bootstrap route, and an MCP session terminated by the crash
  re-initialises under the protocol version the MCP reference documents. The supervisor state
  file tells the shell and local tooling why; it is not a client contract. The contract for
  clients is the two existing steps, unchanged by the merge, plus the reason code the
  `justsearch://supervisor-state` event carries for the webview;
- *operation outcome after an abrupt death*: a disconnect says nothing about effect, so a
  client must be able to distinguish **known success**, **known failure** and **outcome
  unknown**, and the mechanism must work when the client never received anything back. The
  identity is therefore **client-supplied**: every write and every reconfigure carries an
  operation key the client chose before sending (a UUID; the webview, the MCPB bridge and the
  CLI generate one per call), the Engine's acceptance row stores it (7.5), and the outcome
  query is by that key. A server-assigned id that the client might never receive cannot be a
  recovery identity, and MCP's JSON-RPC gives no application meaning to a partial response,
  so "the id is the first bytes" is not a mechanism and is withdrawn. After reconnecting, a
  client asks the operations port for its key and gets one of: `accepted` (row exists, no
  effect yet), `running` with the units completed so far (partial effects are durable and
  attributed, 7.5), `complete`, `failed` with reason and the units completed before the
  failure, or `unknown`. **`unknown` means exactly "no acceptance record for that key"**, and
  because acceptance is durable before any effect (7.5, record 1), it *also* means no effect
  happened; the two readings coincide only because of that ordering invariant, which is why
  the invariant is a rule and not a description. *(amended 2026-09-07, review seven)* A
  retry under the same key is **the same historical operation, never a re-application of its
  desired state**: for any answer other than `unknown` the Engine returns the recorded outcome
  and performs nothing, so a reconfigure that took A to B, whose response was lost, and is
  retried after a later operation took B to C, answers `complete` and leaves C applied; a
  completed write retried after its document was edited or removed answers `complete` and
  touches nothing. Only `unknown` leads to execution, and that execution is the first. A
  `failed` operation retried under its key answers `failed`; continuing it is a new operation
  under a new key, safe because units carry idempotent unit keys (7.5) and the Engine skips
  units already present. The earlier sentence "retrying a reconfigure is safe because
  applied-version semantics make it idempotent" is withdrawn: idempotence of the state
  transition says nothing about intervening history, and the operation key is what does. A
  reconfigure additionally carries the applied version it was issued against and is refused
  with `version conflict` when the applied version has moved, so even a retry under a *new*
  key cannot silently undo a later change. **The history has a stated lifetime**: the
  operations table retains rows for an owner-set period (17.7; the design's default is 30
  days), after which a key answers `expired`, which claims nothing about effect and is
  distinct from `unknown`; every outcome answer carries `history since`, the earliest instant
  the table is authoritative for, which a restore from backup moves forward to the restore
  point, so "unknown means no effect" is asserted only inside a window the answer names. A retry
  under a *new* key is a new operation and the client owns any duplication. "Fails with a
  reason code" in 7.5 is the cooperative path; the abrupt path fails with *no* response, and
  this query is what makes that honest. The **supported client set** for this contract is
  named (6): the webview, the MCPB bridge, the CLI, and *(decided 2026-09-07, lock)* a generic
  MCP-client recovery harness (initialise, search, forced kill, re-initialise, outcome query)
  that tests the protocol rather than any host; other MCP hosts get that tested protocol and
  the same query, not a per-host promise, and the README names none;
- *durable operations resumed*, under 7.5's four conditions.

**Guarantees, kept separate** (supervised shapes, 3.1). Window stays open (Tauri). API is
restored (supervisor). Sessions are lost, not resumed; clients re-enter as above. Committed
data is preserved (store-recoverability register). Durable operations resume under 7.5's four
conditions; interactive turns fail visibly on the cooperative path and are queryable by id on
the abrupt one; children never leak. **Dev-runner restart (new)** must keep the run id, lease
and log continuity its death-observability tests assert on, and must not read as
`TAKEOVER_ABANDONED` to another session.

## 8. One process, shared resources: memory, threads, admission

Two JVMs partitioned these by construction; one JVM partitions them deliberately, and the
composition root publishes the partition (10) so it can be checked.

**Memory model.** One explicit budget with a line per consumer: heap (sized from the Worker's,
per brief v2), Metaspace, direct memory (`MaxDirectMemorySize` set, not inherited), ORT arenas
(the per-session arena cap in `ModelSessionPolicy` is set from GPU VRAM and bounds device
memory, not commit charge; host-side ORT allocation is unbounded today, so this line is new
*(corrected 2026-09-07, 17.9)*), Lucene mmap (page cache,
accounted separately), and extraction children (their own flags, outside the budget). The
budget's metric is **commit charge (private bytes)**, which is what the flags shape and what an
OOM answers to; working set is reported beside it, never conflated. This is a component budget:
the machine-wide envelope the user experiences (Engine plus llama-server plus children plus
page cache) is the product's hardware requirement, measured in 16 as a sum, not promised here.
OOM policy: `HeapDumpOnOutOfMemoryError` stays and `ExitOnOutOfMemoryError` is added, so an OOM
is a supervised restart rather than a zombie answering health. The section 16 soak checks it.

**Launch flags are one decided set, in both launchers.** Tauri launches the Head today with
`UseSerialGC` and `TieredStopAtLevel=1` (right for a small idle heap, wrong for a JVM that
indexes); the dev-runner drops `TieredStopAtLevel` when an AOT cache is present, and
`WorkerSpawner` is a third spawn site that adds `UseCompactObjectHeaders` to the Worker, which
stage A deletes *(corrected 2026-09-07, 17.9; whether compact headers join the Engine's set is
the owner's, 17.7)*. The Engine's set is decided once and applied at both spawn sites: a
low-pause collector (G1 or ZGC, chosen by the 16 run), full tiered compilation with the AOT
cache, `MaxDirectMemorySize`, the two OOM flags, the heap from the budget; the `restart
required` settings of 7.4.

**Executor ownership.** Every subsystem owns a named, bounded executor registered with the
composition root; foreground (API front, interactive turns) and background (indexing, embedding
batches, durable operations) executors are disjoint; no core code uses `ForkJoinPool.commonPool()`
or parallel streams (ten bare `CompletableFuture.supplyAsync` and `runAsync` sites use it
today and dozens of executors (58 construction sites by grep) are constructed with no registry; C1 migrates them and an
ArchUnit rule keeps them out *(corrected 2026-09-07, 17.9)*); virtual threads are allowed at
the front. A wedged parser
or runaway batch cannot starve the API through a shared pool. This bounds CPU threads only: GC
pauses stop every thread (bounded by collector choice and per-batch allocation limits), and GPU
time on a shared session is serial (bounded to one batch by the session gate, 4).

**Admission at the front, budget on the operation.** Two bounds, not one. The API front enforces
**admission** per engine context (concurrent in-flight requests first; rate limits later) for
every client including the webview, with defaults only an agent loop will hit; rejections carry
a reason code and a retry-after (a contract change, 6). Admission bounds how many requests
enter, not the work each creates: one conversation runs many searches and reranks inside a
concurrency limit of one, and a library consumer (10) never passes the front. So every
operation carries its own **work budget** at the ports (deadline, unit count, bytes), inherited
by the work it generates internally; the agent loop's iteration and token limits stay as
separate protections. Neither the old gRPC deadline nor the new pair is a hard execution bound;
together they bound entry and total work, which the Worker's deadline categories bounded from
outside. The liveness route is exempt for every caller, so throttling never reads as a hang
(7.1); no client kind is exempt, since kinds are declarations (3.4). Admission is the new **producer of `ForegroundLoad`**: today only a gRPC interceptor
feeds it, and that goes with the wire; after the merge every admitted call whose urgency is
`foreground` (3.4: search family, conversation, the request-time encoder calls inside them,
and a durable operation a user is waiting on) increments the gauge on entry and decrements on
exit; `background` urgency never counts, whatever its survival class. *(amended 2026-09-07,
review seven)* **Urgency belongs to the work, not to the transport request that started it.**
The increment is held by the work item: an interactive call releases it on return; a durable
operation with `foreground` urgency holds it until it completes or until its waiting client
is gone, at which point its urgency flips to `background` (an operation nobody waits on is
background by definition, and the flip is recorded on its row); a library consumer's work
enters the same admission at the port and counts the same way, since the producer is the
composition root's admission of work, of which the HTTP front is one entry. This is what makes
the 16 envelope hold under a scripted agent rather than merely measure it.

**The aggregate envelope is owned by the Engine, not summed from clients.** Per-context
admission is fairness among cooperating clients; it is not a load bound, because N contexts
each inside their own limit offer N times that limit. The bound that does not depend on the
client count is the one the Engine already has and the design now names as such: **the
executor registry and the session gate are the aggregate envelope**. Every named executor has
a fixed thread count and a bounded queue (none does today; C1 builds the registry and bounds
them, and the coverage test of 10 asserts every executor is registered *(corrected
2026-09-07, 17.9)*); the encoder session gate admits one batch per
session; the generative backend has its slot count. Those caps are set from the machine's
budget (8, memory) and hardware, never from the number of clients, sessions, grants or
embedded consumers, and they are what a library consumer meets when it bypasses the front
(10). When an aggregate cap is reached, the front rejects with the same reason code and
retry-after it uses for a per-context rejection, regardless of the context's own headroom, so
"every client within quota" can never mean "the machine is overcommitted". The two schemes
solve different problems and the design keeps their names apart: per-context admission
decides *who waits*; the executor and gate caps decide *how much runs*. If several Engines
ever share a model host (5), the host owns its own cap the same way.

**Retained state is bounded separately from execution** *(amended 2026-09-07, review seven)*.
Executor and gate caps bound what runs; they do not bound what a finished request leaves
behind, and the design introduces state that outlives its request: a cursor pins a reader
(4, 7.4), a candidate generation holds a directory and, in the beside mode, a second encoder,
an attempted config holds a candidate component. A client opening one first page after
another against changing snapshots, each request finished before the next, keeps concurrency
at one and the queues empty while readers accumulate, and Lucene's own documentation prices a
retained searcher in RAM and open files. So the aggregate envelope has a second half: a
**retained-state register** beside the executor registry, with a cap per kind, set from the
budget like the executor caps and never from the client count: search cursors per context
and in aggregate (a new cursor beyond the cap evicts that context's oldest, which then fails
`cursor expired`; expiry remains the lifetime bound and the cap is the count bound), pinned
readers (at most the cursor cap plus one per generation), representation generations on disk
(two: the active one and one candidate), co-resident encoders (by footprint against the device
line), attempted configurations (one). The component map (10) lists each kind's count against
its cap, the coverage test asserts every retained kind is registered, and 16's aggregate row
gains the repeated-short-request case. The claim is now stated at its true strength: within
quota can never mean overcommitted, for execution and for retained state both.

## 9. Edges: the Engine's contact with everything that is not Java-in-this-process

Each edge is one interface, one composition site, one ArchUnit rule. Three are described here.

**Platform module (idea 8, tier 1).** The Engine has no operating-system knowledge outside one
module. About 44 Java files carry OS-conditional code today: binary names (`HeadlessApp`,
`InferenceConfig`, `CudaRuntimeDetection`, `WorkerSpawner`), process control (`taskkill` in
`LlamaServerOps`, the reconciliation in 7.2), path semantics (mostly through `PlatformPaths`
already) and Windows-only features with fallbacks (BITS, registry policy, diagnostics, pack
staging). The rule: `os.name` is read in the platform module only; binary names and process
control resolve through it; ArchUnit allowlists the readers. Tier 2 is the inference lane's
(5); tier 3 (DMG, per-OS jlink, notarization, CI runners, the dev-runner's roughly 50
Windows-isms) is a lane of its own.

**Egress factory (ideas 3 and 6).** No module opens an outbound connection except through one
engine-owned factory that records destination, class (`loopback` or `external`), byte count and
reason. The seed is small (`ArchUnitEgressTest` covers two sub-packages of `ai-backend`); lane
F writes the rule for the whole Engine with an allowlist of the factory, the front's inbound
server and the telemetry module's OTLP exporter (SDK-owned, so not routed; its class is
recorded from the endpoint resolved at startup, since a static `loopback` declaration for an
operator-configurable endpoint would be false authority). The llama-server client and the
AI-install downloader go through the factory; child-process downloads (BITS, curl) are recorded
at the platform module's spawn site. The factory does nothing new in lane F; it exists so an
egress log, a "nothing left" report and a per-folder policy are consumers of one record
stream. It is **observation, not enforcement**: its exceptions (the SDK exporter,
child-process downloads, the shell's updater and release feed) and any MCP client that receives
content bound its evidence, so a future report is scoped to the processes, channels, interval
and data classes it covers, never whole-machine non-disclosure. Two product statements need
that scope today and do not have it, and the design records them rather than inheriting them:
the README says documents never leave the machine and only the agent's answer does, while the
MCP reference's primary tool returns assembled passages with source attribution to an external
agent; and the OTLP endpoint is operator-configurable while the threat model promises no
telemetry. Both are true statements about *local retrieval and inference* and false as
statements about *content released to an authorised recipient*. The coherent contract is two
properties: retrieval and inference are local (the Engine's property, which the egress record
evidences), and disclosure to an authorised external agent is an authorisation decision made
at the handoff, whose consequences the product cannot govern and does not claim to.
"Authorised" needs a defined meaning *at that handoff*: a cooperative client kind (3.4) is an
attribution label inside the same-user boundary, not recipient-specific consent, and the
current trust model is deliberately broader than consent, so "the MCP client asked" is not by
itself the authorisation the second property refers to; what is (a per-client grant, a
per-folder policy, a one-time confirmation) is the owner's product decision, and the egress
record can evidence only what the Engine handed to whom, never what the recipient did next.
The same coverage limit applies to the model-call interceptors below: they see the Engine's
generative backend, not an external agent's own model call over passages it was handed. Which
exporter behaviour is development-only, which may exist in production and which is prohibited
is a decision the docs must make in one place. Lane F does not rewrite the README; it names the
inconsistency to the owner (18), since the headline claim is the owner's to change.

**Generative backend with interceptors (ideas 3 and 6).** The conversation engine and the agent
loop call one generative-backend interface; llama-server behind `app-inference` is its first
implementation. The interface carries an interceptor slot on the request and response path, so
redaction, the model-call audit and a per-folder policy are interceptors, not branches in the
conversation engine. Lane F names the interface and adds no interceptors. **Optionality is not
authorization**: a remote or cloud implementation is expressible and is not an approved mode
until an ADR amends the non-goals and threat model (local documents and queries, no cloud
processing), exactly as idea 7 needs an ADR for invariant 2.

## 10. Built for the agents that build it

Every line of this product is written by agents whose scarcest resource is context. One process
can make the Engine cheap to inspect, or one wall of text. The measure is not process or file
count but **how much unrelated system knowledge an ordinary change requires**: a retrieval
change must not need supervision; a new component reuses lifecycle, executor and readiness
semantics; the verification profile runs the same control logic with capabilities absent. A
registry proves a component declared an executor, not that it is responsive: the gates below
are structural, 16 carries the behavioural proof. Design requirements of the root, not conventions:

- **The Engine describes itself.** Every component the composition root wires is listed at
  runtime with lifecycle state, desired versus applied config and winning tier, last error,
  executor and memory line (8) and an evidence pointer, in one paginated, field-filterable
  endpoint built new: `/api/debug/state`, `/infra/capabilities` and
  `RegistrySnapshotExporter` each carry a slice today and none a component list, so they
  become projections of it *(corrected 2026-09-07, 17.9)*; a coverage test asserts it lists
  every wired component.
- **Structured, correlated, subsystem-tagged output.** One `engine.log` replaces
  `headless-backend.log` and `worker.log`; `core.head-log` and `core.worker-log` collapse to
  `core.engine-log` (6), and the diagnostics bundle and the `scripts/agent-analytics` parsers
  move with it. Every line carries a subsystem tag and a request or operation id; a trace spans
  front, retrieval and inference without cross-process propagation; OTel `service.name` becomes
  the engine. The privacy-safe logging discipline (hashed paths) now covers a file that also
  holds request logs. Debug and state JSON shapes are registered like API shapes.
- **Composition profiles.** Two orthogonal parameters, stores (`real` or `ephemeral`) and
  inference (`full`, `encoders`, `none`), with three named combinations: `full` (production),
  `bench` (real stores, encoders, no LLM: idea 5's self-benchmark and in-process eval),
  `verification` (ephemeral, none: boots in seconds). The verification profile is a deliverable
  of this lane: plumbing checks stop needing the 40 s stack and the shared lease. The dev-runner
  runs more than one instance only once the inference host exists.
- **Engine as a library.** The composition root is callable from tests, CLI tools and jseval
  without a process boundary; the 20 `WorkerProcessManager` consumers become JVM-level tests
  plus supervisor-contract tests. A size ratchet on `app-engine` and the module fan-in report
  keep the root a wiring module, not the next `app-services`.

## 11. Capabilities the design must not preclude

The owner named eight later ideas. None is designed here; each reduces to capabilities cheap to
pin while the one process is assembled and expensive to retrofit.

| idea | capability needed | pinned where in this design | left to a later lane |
|---|---|---|---|
| 1 Agents write back (remember; file a document) | provenance on every write; immediate index-and-return with a durability choice; notes root as a normal indexed root with a source tier; memory under the core's durable stores; **two authority pins**: a generated source tier ranks, cites and is revised as generated, never as independent source evidence (source files stay the authority, the index derived); and an index rebuild never destroys the sole authoritative copy of an accepted write (notes are files, memory is a registered durable store: true by construction, kept so); **an accepted write is a responsibility**, not a side effect of an indexing call: persistence, provenance, discoverability and removal semantics for it are owed from the first write, and the non-goals ("not a note-taking application, not a system of record") are amended before the first tool ships, as idea 7 and a remote backend need their ADRs | 3.4, 4, 3.3 | the two MCP tools; the grant-model wiring; the fact embedding path; fields via lane D's register; the non-goals amendment |
| 2 Entity and timeline layer | an open port set so an entity-graph port is additive; NER as a request-time service; a second durable store under the recoverability register; a content-date field | 3.3, 4, 5 | entity resolution, entity pages, date extraction, the neighbourhood tool, its eval |
| 3 Local retrieval, redacted context, any model | one generative-backend interface with interceptors; NER at request time; egress recorded | 9, 5 | the placeholder session store, rehydration, the disclosure panel, per-folder policy |
| 4 Batch extraction to structured output | the operations table and checkpoint cadence; streaming document access through the ports; the file-operations tool | 7.5, 4 | the schema prompt, the extractor, citation per cell, the CSV writer |
| 5 Quality moat visible | `SearchTrace` stays canonical; engine as a library; the `bench` profile | 3.3, 10 | the self-benchmark runner, the "why this matched" panel |
| 6 Inspectable privacy | the egress factory; the model-call interceptor; provenance on writes; privacy-safe single log | 9, 3.4, 10 | the egress log UI, the model-call audit store, the signed report |
| 7 Small trusted-network sharing | the API front owns bind, identity, admission and grants; the core never assumes a local client; one Engine, N clients | 3.2, 8, 6 | the ADR changing invariant 2; pairing, device tokens, per-root ACL |
| 8 Platform contradiction | tier 1: no OS knowledge outside the platform module (rule, in lane F); tier 2: runtime-neutral inference host (inference lane); MMF deletion | 9, 5, 6 | tier 3: packaging, signing, CI, dev tooling for a second OS |

## 12. Consequences for the development workflow

**After lane F (stage 1):** one process to attribute, one log filtered by tag and id, hot reload
covers Head code, the verification profile replaces the dev stack for plumbing checks, no
three-channel config forwarding, reconfigure replaces restart-to-apply. Full-profile restart cost
is unchanged (the encoder reload dominates) and the one-stack-at-a-time lease remains.
**After the inference target:** an engine restart is a JVM start (about 3 s) with models warm in
the host, and N worktree engines share one host, so GPU ownership becomes a separate,
longer-lived lease: the concurrent-agent unlock, a benefit of the target, not a daemon default.

## 13. What is lost, honestly

| loss | permanent? | mitigation |
|---|---|---|
| API isolated from encoder native faults | until the inference target | supervised restart with visible state (7.1); crash reports by role |
| independent API availability during a Worker restart (real today, section 6) | yes, by choice | supervisor cooldown is the outage; reconfigure (7.4) removes the most common restart cause |
| API isolated from indexing GC pauses | yes, by construction | the decided collector and per-batch allocation bounds + `IndexingPacing` (8); gate (16) |
| hard memory cap on the Head half | yes | explicit budget and `ExitOnOutOfMemoryError` (8); soak check |
| free thread partition | yes | executor ownership rule (8) |
| process-level chaos tests of the boundary | yes | supervisor conformance harness (7.1) for the supervisor; the recovery and hang rows of the gate (16) for the real engine |
| OS-level privilege separation option | yes | parser pin and grant checks (6) |
| request-time encoder latency independent of indexing batches | yes, even on a host (a running batch is never preempted) | foreground precedence with one bounded exception (4): a foreground call is passed by at most one aged background batch in its whole wait, since a passed call suspends aging until it is served; that aged batch is the delay accepted so indexing cannot starve; absolute latency stays workload-conditional |
| encoder reconfigure without capability loss | conditional on device headroom (7.4) | in-place mode keeps the API and text search up with the semantic legs `reloading`; the beside mode when memory allows; on in-place rejection the component is `unavailable` until A recomposes, a named degraded state, not a promise of continuity |
| an essential component recovers without an outage | bounded (7.6) | local reconfigure first under an attempt budget; a wedged `index` that cannot release its resource escalates to a supervised restart against the same restart budget, because a healthy endpoint over a product that cannot search is not the floor |
| a crash never interrupts a write mid-flight without telling the client | yes (the API dies with the work) | client-supplied operation key, acceptance durable before effect, outcome query after reconnect, same-key retry answering the recorded outcome and never re-applying, inside a named history window (7.6) |
| a settings change touches only the components it concerns | no *(decided 2026-09-07, lock)* | coherence is dependency-scoped through the register's declarations, so a component's applied version moves only when a setting it declares moves; the global revision survives only as the accepted-settings record; generation-bound settings are outside both, since the active generation is their applied value (7.4) |
| semantic retrieval through ordinary maintenance | partly: the in-place transition on the floor machine is text-only for build plus replay (7.4) | bounded by build plus replay, never by a human decision (encoder A recomposes on refusal); the 16 row for semantic availability during maintenance is the owner's product bound |
| in-process extraction fallback for the routed families | yes, by choice | those families fail visibly when the pool is broken; text decoders unaffected (6) |
| head/worker label in Task Manager and crash reports; independent JVM tuning per half | yes | subsystem tag; none needed (Head heap was 85 % empty) |

Not lost, although often assumed: parser crash isolation (lane C pool), Lucene lock safety on
Windows (the supervisor waits for handle release), VRAM arbitration with llama-server.

**Complexity ledger.** "Delete a distributed-system contract" is not the whole account. Three
kinds of complexity move in different directions, and inside the third the design separates
the architectural commitment (what the merge needs) from the capability commitment (what the
owner is investing in through the same lane), because "retrofitting is dearer" justifies a
cheap constraint and does not by itself justify an execution model:

| kind | what | judged as |
|---|---|---|
| eliminated | the wire, the MMF bus, port handoff, config forwarding, argv builders, `RemoteKnowledgeClient`, restart-as-reload | the structural saving; counted by the sweep (16) |
| compensation for a property the split gave free | supervisor, shutdown owner, executor partition, memory budget, admission, component readiness, child reconciliation, the stuck-component policy | required to preserve current behaviour; each must justify itself against the loss it replaces |
| required by the merge's own consequences | a usable in-process reload path (the Worker restart was the reload path; 7.4), the operation outcome query (a crash now takes the API with the work; 7.6), ingestion's per-file recovery kept working under the new lifecycle (its resume today rides the `jobs` table and the Worker restart) | required: without them the merged Engine is worse than the split at something the split did for free. Stated at the strength the merge needs: a reload path, not a global atomic version; per-file recovery, not a general resume contract |
| cheap constraint that prevents a foreseeable mistake | engine context as a type with its two work axes (3.4), provenance where a slot exists, the port catalogue, `generation-bound` settings, the three lifetimes (7.5), acceptance-before-effect (7.5), the authority pins (11), the platform module rule, the egress factory as a record stream, the generative interface with an empty interceptor slot | pinned: an interface, a column or a rule. "A page of code" is the declaration cost, not the whole cost: each is an invariant every later change must preserve, and 16's representative-change row is where that cost is observed rather than assumed |
| independent capability investment | the applied configuration revision with dependency-scoped coherence through the register (7.4), the operations table with its general resume contract for finite operations (batch extraction, self-benchmark; 7.5), the generation cutover contract (7.4), immediate index-and-return with its durability choice, the `bench` profile | a separate value proposition (11), decided by the owner to ship in this lane (15); credited as capability work, never as a benefit of merging, and measured in 16's positive-benefit row only through what the merge itself delivers. Authorisation to build these and proof that the merge needs them are different propositions, and the ledger keeps them in different rows |

The measure in 10 (unrelated knowledge per ordinary change) cuts across all five rows: an
agent no longer needs gRPC, and now meets the registry, applied versions, checkpoints,
generations, admission context and component readiness. Whether a retrieval or orchestration
change became more local or merely acquired a different set of obligations is the question 16
answers with representative changes, not with a deletion count. The organising principle for
the final contract is that one: a retrieval change must not reopen lifecycle reasoning, and a
new optional capability must not force unrelated components into a new global transaction;
where a row above violates it, the row says so and 16 measures the price.

**Work loss is a product number, not an SLA question.** The absence of an availability promise
does not make a restart cheap: a client's reconnection can succeed mechanically while an
expensive multi-step agent task is interrupted, session state is lost, and a write's outcome
has to be queried. The quantity the larger failure domain must be judged against is *how much
completed or in-progress user work a fault invalidates*, and 16's workflow-recovery row
measures it in those units (turns lost, operations resumed versus restarted, writes needing a
query). The tolerated value is the owner's call (18), not implied by the lack of a formal
promise; set at the lock at what the mechanisms guarantee and no looser (17.7), and the
measurement is what shows the mechanisms deliver it.

## 14. The alternative considered and not chosen

**Thin control process + Engine + isolated models.** A small, independently available
API/control process over an Engine, with models and parsing isolated. Not chosen: it keeps
exactly the boundary lane F removes, and nothing in scope requires API control to survive an
index-runtime failure. If that requirement arrives (headless multi-client deployment, a hard
availability target), the section 2 rule adds the boundary then, around the whole-engine API.

## 15. Decisions

All decided 2026-09-06 (owner conversations, then the orchestrator on the owner's instruction
to decide the remaining items and fold reviews four and five), and locked by the owner on
2026-09-07 (last bullet). Each decision's mechanism and rationale live in the section cited.

- **Boundaries.** The modular Engine is the default placement under the section 2 rule; the
  recovery and partition the split gave are priced losses (13), judged against the runtime
  contract (6). Stage 1 stands on its own: the inference lane is a separate lane after F, an
  improvement carrying only the section 5 obligations, and no host property is credited to
  stage 1. Idea 8: tier 1 as a rule with a test here, tier 2 on the inference lane, tier 3
  deferred (9). The later ideas are pinned as capabilities (11), not designed; a remote
  generative backend and trusted-network sharing each need their own ADR first (9, 6).
- **Lifecycle.** One supervisor contract, two implementations (Tauri, dev-runner; jseval uses
  the dev-runner), with `starting` and `stopping` states and a conformance harness (7.1).
  Children: manifest registry, reconciled on start, adopt or kill by identity, killed by the
  supervisor only on its terminal path (7.2). One ordered shutdown owned by the composition
  root, seeded from `HeadShutdownCoordinator`, HTTP route and request file as its two triggers,
  receipt contract kept, llama-server stopped on `quit` and `upgrade` and left for adoption on
  `restart` and `hang`; the updater gains a dead-Engine path (7.3). Reconfigure is in-process;
  its invariant is one applied config version (A or B, never a mix) with nothing persisted as
  applied on failure; continuity is per component, beside the incumbent when headroom allows and in place
  (capability `reloading`) when not, chosen per apply and reported; "old component keeps
  serving" covers rejected composition only, a native fault during compose is a crash that
  boots from A; three guarantees are named apart (configuration commitment always holds,
  semantic coherence always holds, capability continuity is conditional): on in-place
  rejection the state is applied A with the component `unavailable` and an automatic recompose
  of A that can itself fail; an attempted-version record beside the applied one is what
  survives a crash to name the failed B; coherence is dependency-scoped through the register
  and the global revision is the accepted-settings record (*decided 2026-09-07, lock*, 7.4);
  every `generation-bound` setting (embedding model first) is a reindex
  with a cutover contract (journal replay to activation, atomic activation refused with gaps
  unless they are accepted, cursors expire rather than migrate, beside-or-in-place for the
  transition; *amended 2026-09-07, review seven*: in the in-place mode accepted writes are
  indexed lexically with semantic enrichment deferred to replay and the response says so, a
  refused or abandoned candidate recomposes encoder A immediately, and the active generation's
  metadata is the applied value of every generation-bound setting so the applied config
  version is computed from it and boot composes encoders from it), never an apply; launch
  flags are restart-required (7.4). A surviving
  llama-server is adopted only if its recorded config identity matches A (7.2). Durable
  operations resume from an operations table checkpointed per unit and at most every 30 s
  under four resume conditions held as columns, identity being the operation's declared
  dependency set (captured inputs for finite work, root plus generation for ongoing work, the
  captured plan plus target generation for a reindex, whose completion criterion is
  convergence on the accepted corpus with gaps distinct from processing history; *amended
  2026-09-07, review seven*), never a global config hash; search snapshot, representation generation and operation
  identity are three lifetimes; acceptance is durable before the first effect, effect precedes
  the checkpoint that covers it and means durable in the sense the checkpoint claims,
  completion follows the final checkpoint, and start reconciles them (7.5). Readiness stays
  staged by component; liveness, readiness and restart-worthiness are distinct; an essential
  component stuck past its start deadline is `failed` and recovered locally by reconfigure
  under an attempt budget, escalating to a supervised restart against the crash budget when
  local recovery fails or the resource cannot be released, optional components never
  escalating; recovery is defined as a usable workflow restored (readiness by component,
  client re-entry through the existing bootstrap and MCP re-initialisation, an operation
  outcome query by a client-supplied key answering `accepted`, `running`, `complete`, `failed`
  or `unknown` where `unknown` means no acceptance record and therefore no effect, durable
  resume) for a named supported client set (7.6). *Amended 2026-09-07, review seven*: a
  same-key retry is the same historical operation and answers the recorded outcome without
  re-applying anything; a reconfigure carries the applied version it was issued against and
  is refused with `version conflict` when that has moved; the operations history has an
  owner-set retention after which a key answers `expired`, and every answer names the
  `history since` instant inside which "unknown means no effect" holds (7.6).
- **Resources.** An explicit per-consumer budget in commit charge; one launch-flag set for both
  launchers; `ExitOnOutOfMemoryError` added; the machine-wide envelope measured as a sum (8,
  16). Executors owned, disjoint, registered; no common pool in core (8). Admission per engine
  context at the front bounds entry; every operation carries its own work budget at the ports;
  the agent loop's limits stay; admission produces `ForegroundLoad`; the liveness route is
  exempt for every caller and no client kind is exempt (3.4, 8). The aggregate envelope is the
  executor registry plus the session gate, capped from the machine's budget and never from the
  client count; per-context admission decides who waits, the caps decide how much runs (8).
  *Amended 2026-09-07, review seven*: the aggregate envelope has a second half, a
  retained-state register with a cap per kind (cursors, pinned readers, generations on disk,
  co-resident encoders, attempted configurations), set from the budget and never from the
  client count, and urgency belongs to the work item, not to the transport request (8).
  The session gate on request-time encoders is foreground precedence with one bounded
  exception: a background batch overtakes a waiting foreground call only when its aging
  threshold has matured, and a seated aged batch marks the calls then waiting as `passed`,
  suspending aging until they are served, so a foreground call is passed at most once in its
  whole wait and its wait is bounded by the running batch plus at most one aged batch plus
  earlier foreground calls; background service follows from admission bounding the passed set
  (*amended 2026-09-07, review seven*); it establishes no absolute latency (llama-server is
  outside it) and the gate row is relative to the admitted workload under that contract; one
  producer per stage is pinned by a test; pacing is a rate, not a bound (4, 13).
- **Contracts.** One engine context record (client kind, client id, session id, grant ref,
  source tier, and two independent work axes: survival `interactive`/`durable` and urgency
  `foreground`/`background`), the three existing provenance consumers, no new index field; a
  durable operation re-resolves its grant; the session id is attribution only and nothing
  durable is keyed on it (3.4). Client kind is a declaration inside
  ADR-0046's same-user boundary, never a verified role; nothing safety-relevant hangs on it
  until idea 7 gives identity a basis (3.4). Authorization holds wherever data is selected (6).
  The complexity ledger's five rows separate what the merge requires, stated at the strength
  the merge needs, from what the owner is investing in through the lane, the applied
  revision and the general resume contract now in the investment row (13). Work loss is
  measured in user-work units and its tolerated value is the owner's (13, 18). An accepted
  write (idea 1) needs the non-goals amended before its first tool ships (11). Contract-visible changes are the enumerated list in 6, each with a gate that
  distinguishes the two states. The extraction routing table is the parser pin; the routed
  families' in-process fallback is deleted (6). Proto DTOs go with the wire, replaced at the
  ports in a named follow-up; the `FetchDocuments` byte-budget pager stays as a per-batch bound
  (6). Index-and-return returns at NRT visibility with an opt-in `durable` group commit and a
  durability test as its consumer; an evicted cursor generation fails with `cursor expired`
  (4, 16). The OTLP egress class is recorded from the resolved endpoint; the egress factory is
  observation with a stated scope (9). One log with tags and ids; the two channels collapse;
  profiles are two parameters with three named combinations, `verification` in scope (10).
- **No split-mode fallback release, and no transport flag (owner, 2026-09-07).** Brief v2's
  flag-then-flip-then-delete order is withdrawn. There is no both-modes state on `main`: the
  Engine is built on one branch (17) and `main` keeps split mode untouched until that branch
  merges, so nothing is ever dual-mode and no PR keeps two modes green. The paired measurement
  (16) is the branch against `main` on the same corpus and machine. The merge and the deletion
  of split mode are one PR; a regression the envelope missed is answered by a fix release
  through the in-app updater, which an alpha can afford and which holds only with the
  dead-Engine upgrade path (7.3), therefore a precondition. Throughout this document "the
  flip" and "the default flips" name the merge of that PR, and "split" names `main` before it.
- **Locked (owner, 2026-09-07).** Review seven's two reconsiderations, decided on the
  orchestrator's recommendation. (a) **The global applied version** becomes two things: the
  applied configuration revision as the accepted-settings record, and dependency-scoped
  coherence through the register, because a settings revision and a runtime compatibility
  epoch are different things and a global epoch would make every future optional capability
  join a transaction it has no dependency on (7.4, 13). (b) **The tested client set** gains a
  generic MCP-client recovery harness so the protocol is tested rather than each host; the
  README names no host (7.6, 16, 18). With them, every value 17.7 lists as the owner's is
  recorded there: one operating shape (a running desktop application that also serves agents);
  the work-loss tolerance set at what the mechanisms guarantee and no looser (18); the
  supervisor budget re-cut rather than ported (7.1); gap acceptance by a user only (7.4); the
  failed-unit default zero and the envelope as the readiness authority; thirty days' retention
  with a row cap; the webview not privileged over agent contexts; local recovery before
  escalation with the non-transient reasons named; the semantic-availability bound paired
  first and absolute second; every gate bound derived from the PR 0 baseline with a declared
  margin and exactly three allowed-difference classes (16); the two-property disclosure
  contract with telemetry export off in packaged builds (18). The design is locked: 17.6's
  amendment path governs every change from here.

## 16. What must be measured, and the gate for flipping the default

Brief v2's list stands (startup cold/warm, RSS idle and indexing, search p95 during bulk indexing,
API p95 for the agent loop, crash-to-recovered, installer size). Added: index-time embedding
throughput and request-time single-call latency (one query NER, one embedding, one rerank of
twenty); verification-profile boot time; reconfigure duration with the API up throughout.

**Gate for the default flip: a joint envelope**, single versus split (the lane branch at
stage E against `main` after PR 0, 17), paired runs on the same corpus and machine, bounds set
by the owner before the run:

| metric | condition |
|---|---|
| foreground search p95 and agent-loop API p95 | bulk indexing running; measured with the agent idle and with a scripted agent active; p95 over admitted requests, **and** the admission rejection rate under an owner-set ceiling in the same row, so the row cannot pass by shedding load; rejections carry the admission reason code, never a timeout |
| indexing progress | chunks/s not below an owner-set fraction of the split baseline while foreground queries run (885 recorded continuous queries starving indexing before pacing changed; the merge must not reopen that) |
| memory | commit charge (private bytes) within the section 8 budget through the run, working set reported beside it; the machine-wide sum (Engine, llama-server, children) not above the split's; no heap growth and **zero crashes** across a soak that runs indexing, a scripted agent and reconfigures together, so steady-state numbers cannot stand in for failure frequency |
| recovery, process | crash-to-API-restored within first cooldown plus an owner-set warm-start budget (today's index-ready time, about 8 s, is the reference); `restarting` visible in `supervisor.v1.json`; a durable operation in flight at the crash resumes from its last checkpoint, not its start; no orphaned child after the restart, and a healthy llama-server adopted rather than reloaded (crash and `restart` paths; `quit` and `upgrade` leave no llama-server) |
| recovery, workflow | reported per component: time to `api`, to `index` (text search answers), to `encoders` (a semantic query answers), to `generative` (a chat turn answers), since these are four different outages; each client in the named supported set (webview, MCPB bridge, CLI, and the generic MCP-client harness; 6) re-bootstraps the token, re-initialises its session where it has one and completes a search without operator action, the result stated per client and never generalised to hosts not run; a write and a reconfigure interrupted by a forced kill at each of three points (before the acceptance row, after acceptance before the first effect, after an effect before its checkpoint) are queried by their client-supplied key after the restart and answer `unknown`, `accepted` and `running`/`failed` with the right unit count respectively, against what the stores show; retrying under the same key produces no duplicate effect in any of the three; **and** work loss is reported in user-work units: agent turns interrupted, durable operations resumed versus restarted, writes that needed a query, since that is the number the owner sets the tolerance on (13, 18) |
| stuck component | an `index` open made to block past its start deadline turns `failed` with its reason, `api` stays alive, a reconfigure of the component recovers it when the block is released, and the supervisor does not restart on that path; **and** the same block made unreleasable (a held lock the retry re-meets) exhausts the local attempt budget and escalates to one supervised restart that counts against the crash budget and comes back with `index` ready, so both the local path and the escalation are exercised, and an optional component in the same state never escalates (7.6) |
| generation transition | a reindex started with an edit, a removal and a new document accepted during the rebuild activates with all three reflected and the removed one absent; a reindex with one gap does not activate and reports the document, while a document captured at one hash, changed under its unit and replayed at the newer hash is reported as `superseded` and does not block (7.5); a cursor opened on the old generation before activation either pages to its normal expiry or fails `cursor expired`, never returns new-generation evidence; the transition on the floor machine runs in-place with semantic legs `reloading` and text search answering throughout, a write accepted in that window is text-searchable at once with its response saying `semantic at activation` and is semantically searchable after activation with the new generation's vectors, **and** the refusal branch on that machine (one gap, activation refused) has encoder A recomposed and the old generation's semantic legs answering again within the reconfigure budget, with the deferred writes enriched by A's backfill (7.4) |
| semantic availability during maintenance | on the floor machine and the reference corpus, the fraction of a full generation transition (build plus replay) during which a semantic query is refused with `reloading` is reported, together with the wall-clock length of that window, and both are under an owner-set bound (17.7); text-search survival is the crash floor, this row is the product floor for ordinary maintenance (13, review seven) |
| combined: low-memory reindex, changing inputs, interruption | on the floor machine, in-place mode, a reindex with documents edited under their captured units and a forced kill after the `state.json` swap and before the completion row: on restart the new generation is active, the encoders composed match its metadata, the operation row reads `complete`, the edited documents are current at their latest accepted version, and the same run with the kill before the swap resumes replay from the journal with the old generation active and its encoders (7.4, 7.5) |
| combined: delayed retry after a later change | a reconfigure A to B whose response is dropped, then a reconfigure B to C completed, then the first retried under its key: the answer is `complete`, C stays applied, the component map shows one apply of C and none of B after it; the same retry under a new key is refused with `version conflict`; a completed write retried under its key after its document was removed answers `complete` and the document stays removed; a key older than the retention period answers `expired`; after a restore from backup an outcome answer carries a `history since` at the restore point (7.6) |
| aggregate bound | N contexts each inside their per-context admission limit, with N times that limit above an executor's cap, see rejections carrying the aggregate reason code and retry-after while the executor queue never exceeds its bound and the machine-wide memory row holds; the same run with one context and the same total offered load rejects the same number, so the bound is shown independent of client count; **and** a client opening first pages one after another against a changing index, never more than one request in flight, sees its oldest cursor evicted with `cursor expired` once the per-context cursor cap is reached, the pinned-reader count in the component map never exceeds its cap, and the memory row holds through the run, so retained state is shown bounded independently of execution (8) |
| durability | a `durable` index-and-return write is searchable after an immediate forced kill and restart; an NRT write is searchable before the next commit; both asserted by a test that is the call's in-lane consumer |
| request-time encoders | with the agent idle, one query NER, one embedding and one rerank of twenty during bulk embedding stay within the running batch plus one aged batch of their idle latency (the corrected relative bound, 4: precedence with one bounded exception, so the bound is two batches' GPU time in the worst phase and one otherwise, and the row states which phase it measured); with the scripted agent active the same calls are reported against the admitted concurrency, not against idle; a unit test pins one background batch in flight per session, foreground-first seating between aging periods, exactly one aged batch seated per period under a saturating foreground load, and, with a foreground queue deeper than twice the aging threshold, that no foreground call is passed by more than one aged batch over its whole wait and that aging resumes once the passed calls are served (the `passed` rule, 4), with the indexing-progress row above measured under that same saturating load so the two promises are shown compatible under one contract, not asserted separately |
| semantic non-regression | two layers: the search-quality baselines (jseval, same corpus) within noise and the same `SearchTrace` shape per query, single versus split; **and** a workflow fixture, a fixed set of queries and chat turns whose returned evidence (passage ids, source identity, truncation points), citations and cancellation behaviour are diffed across the two modes, since ranking metrics say nothing about what a client was handed; the equality relation is declared before the run: byte-equal for deterministic fields (evidence selection, truncation points, citation targets, cancellation outcome), and for fields an enumerated contract change (6) or scheduling legitimately moves (reason codes that did not exist, timing-dependent ordering of equal-score hits, generative text under a fixed seed), an **allowed-difference class** named per field with the fixture asserting the field differs only within that class, so a changed citation is a regression unless its class was declared, and no class is added after a diff is seen; the migration moves deadlines, streaming and lifecycle, so both layers are proven unchanged, not assumed |
| resume conditions | a durable operation resumed after a crash with a changed declared dependency fails with `inputs changed` while an unrelated setting changed does not; a finite operation with one source changed under it fails that unit and completes the rest; with a revoked grant fails; with a unit completed but not checkpointed repeats the unit without a duplicate effect; a row left `running` with the last effect present is advanced past it (7.5) |
| dead-Engine upgrade | a sandbox round applies a release over an Engine that cannot boot (supervisor `exhausted`), with the registered children reconciled first (7.3); **and** the repair release opens every store the broken release left, at the broken release's schema, and answers a search over the index it inherited, because replaceability of the executable is not recoverability of the state, and the stores holding authoritative user data (memory, notes) are the ones this row exists for |
| positive benefit | the sweep's deletion count (RPCs, `RemoteKnowledgeClient`, MMF, argv builders, config tier) and the verification profile booting in seconds are shown, not assumed; **and** the claim the lane actually makes (4, lower coordination cost) is measured on its own terms: three representative changes named before the flip (one retrieval, one orchestration, one lifecycle) are made on both modes and compared on modules touched, cross-subsystem facts an agent had to establish, verification time, **and failures and correction effort** (a review finding, a red test, a missed invariant such as an unpreserved applied-version or acceptance-order rule, counted with the turns spent correcting it), because a design can look cheap when the agent overlooked a cross-cutting obligation and the cost shows up as rework, not as modules touched; a design that passes the envelope and delivers none of these is not flipped. Credit is separated: the launch-flag correction is applied to the split baseline too, so the merge is never credited with a fix the split would have had; capability work (13, row 5) is credited as capability, never as merge benefit |
| hang, graceful | a wedge that leaves the watcher thread runnable (API pool exhausted) is recovered through the request channel within the request deadline plus the same budget |
| hang, forced | a whole-JVM wedge (safepoint stall) ignores the channel and is recovered by the forced kill within deadline plus budget; the two rows are separate so the kill branch is exercised |
| reconfigure | an encoder config change applies with zero API connection drops **and** one applied version per component throughout (never a mix: no request meets a component at a version other than the one its inputs were made against), while a component with no declared dependency on the changed setting keeps its version and is never recomposed (7.4, dependency-scoped coherence); in the beside mode the old component serves throughout and a compose made to fail by rejection leaves the incumbent serving, nothing persisted as applied, the next boot unchanged; in the in-place mode (forced by capping free device memory below the candidate's footprint) text search answers throughout and the semantic legs report `reloading`, and a compose made to fail by rejection **on this path** leaves the component `unavailable` with the rejection recorded, then recomposes A automatically and reports it, with the variant where A's recompose is also made to fail leaving `unavailable` with both reasons and the stuck-component row taking over; a compose made to fail by a native crash is recovered by the supervisor with the Engine booting from the last applied version, the attempted-version record naming the failed B and its reason, and a surviving llama-server adopted only when its config identity matches A, respawned otherwise (7.2, 7.4) |

If the envelope fails, the remedy is found under the section 2 rule: collector and heap sizing,
pacing tuning, a missing executor or admission bound, an operation contract that lost its bound,
and only then a process boundary a persistent requirement justifies. The default does not flip
on a failed envelope.

**What a passing envelope establishes, and no more.** A paired run on one machine is causal
evidence for that machine, corpus and workload; the published retrieval campaign is tied to one
GPU class and the lower-memory guidance carries unbenchmarked assumptions, so the conclusion
"the merged Engine is the better default" is bounded by the tested hardware and holds near the
supported floor only once a run there says so (a second machine at the floor is part of the
run, or the floor is re-stated). A zero-crash soak is an acceptance requirement, not a
rare-failure rate: the recorded conclusion names the duration, workload and failure scenarios
it covered. Neither bound reopens the decision rule; both keep the record honest.

## 17. Sequencing: two merges, one checkpointed branch

Decided by the owner 2026-09-07 (0). The per-stage checklist is written at the start of each
stage, not here, and inherits `verified-facts.md` (beside this file, `file:line` citations for
every code fact the design depends on, including the three added for this section).

### 17.1 Why this shape

The incremental shape (a transport flag on `main`, both modes green on every PR, ten to twelve
merges) rests on one rule: `main` is coherent after every merge. That rule protects consumers
of `main`: other agents building on it and releases cut from it. This lane runs alone and last,
the owner halts all other development for its duration, and no release is cut mid-lane, so
`main` has no consumer between the two merges. The coherence requirement therefore moves from
"`main` after every merge" to "the branch at every checkpoint", which the next stage's agent
needs regardless, and its expensive forms fall away: the flag itself, the both-modes suite on
every change, the dual-mode prohibition as a live constraint, and the per-merge ceremony
(review record, squash message, CI, merge queue, post-merge build, go-ahead round trip).
What does not fall away: every stage is verified before the next starts, every stage gets an
independent review of its diff range recorded in the managed review record, and every stage
ends with an explicit "continue" go-ahead from the owner. A stop between steps is a checkpoint,
not a merge; nothing in the programme rules says otherwise. The paired measurement is the
branch against `main`; rollback is one revert; a failed gate leaves the code on a branch, which
is cleaner than a dead flag on `main`. The one cost accepted and recorded: while the branch is
open there is no hotfix path for split-mode code on `main`, which an alpha with a quiet `main`
can afford. Drift is held by a weekly `git merge` from `main` (never a rebase of a pushed
branch, `agent-lessons.md`).

### 17.2 PR 0: launch flags on split

Small and first. Drop `TieredStopAtLevel=1`, add `MetaspaceSize=128m`, at both spawn sites
(`lib.rs`, `dev-runner.cjs`); the Head keeps `UseSerialGC` because the Engine's collector is
the gate run's choice (8), not this PR's. Measured before and after on the 917 Derisk 1
procedure. This is the credit rule of 16 made concrete: the split baseline the gate compares
against carries the fix, so the merge is never credited with it. Ordinary PR, ordinary review,
ordinary go-ahead.

**PR 0 also lands the baseline instruments, and the baseline is captured before stage A.**
Regressions happen at stage A, not at stage E, so the things stage E diffs against must exist
on `main` first: the workflow fixture of 16 (the fixed query and chat-turn set whose evidence,
citations and cancellation behaviour are diffed, with the allowed-difference classes declared
per field) lands in PR 0 because its runner is mode-agnostic; then, on `main` after PR 0
merges, one record is captured on the gate corpus and machine: the jseval search-quality
baselines, the fixture's output, the brief v2 performance list (startup, RSS, p95s), the
request-time encoder latencies, and the Worker restart rate from `worker.log` (5). That record
is the split side of every paired row in 16 and is stored under
`docs/design/lane-f-engine-jvm/evidence/baseline/`. Stage A does not begin until it exists.

### 17.3 PR 1: the Engine, one branch, seven checkpoints

Stage order is risk-first: the spine is the change most likely to surface an unknown, so it
precedes the compensation work that assumes one process. (Sequencing stages are lettered A to
F; "stage 1" and "the target" elsewhere in this document name the inference seam's two shapes,
5, and are unrelated.) Each stage names what it contains,
the state the branch is in afterwards (so the next agent knows what is deliberately broken),
and what the checkpoint review must see.

| stage | contents | branch state after | checkpoint proof |
|---|---|---|---|
| **A. Spine and unplug** | `app-engine` root (3.2) composing worker services in-process from `HeadlessApp`; ports as direct calls, `SubscribeIndexingJobs` and `ScanRoot` as in-process flows; rule 6b and the ring direction pinned; proto DTOs kept at the ports (6, transitional); operation contracts re-homed as port properties (work budget, per-batch byte bound, flow control); the wire's code deleted in the same change (gRPC services and server, `RemoteKnowledgeClient`, MMF bus, `WorkerSpawner` and `SupervisionPolicy`, argv builders, config-snapshot tier, `WorkerProcessManager` and its 20 tests replaced by JVM-level tests); ADR-0001 and ADR-0002 superseded by one ADR and their probes retargeted in the same change; one spawn path in `lib.rs` and the dev-runner with a first-cut Engine flag set, one AOT cache instead of two; one JDWP target and hot reload re-homed; the dev MCP `start`, `stop` and `reload` follow (`check-dev-mcp-doc-sync`); one `engine.log` and the channel collapse (10); `ForegroundLoad` fed at the search entry points until C1; `main_gpu_active` and `energy_reduced` re-homed as one in-process gauge (4) | one JVM boots; search, ingestion and the dev stack work. Deliberately lost until B: supervision, and restart-as-reload (config-apply, AI install and pack import exit with a `restart required` code; the dev-runner observes the exit and does not restart) | full suite green including the dead-code ratchet; a live search and an ingest on the stack; the deletion count recorded for 16's positive-benefit row |
| **B. Lifecycle** | supervisor contract with the re-cut budget (exit-reason classes, cooldown over handle release, stability window from `ready`; the hang parameters are placeholders until E), conformance harness with a fake engine that exits under each class, Tauri and dev-runner implementations, `supervisor.v1.json` and the `justsearch://supervisor-state` event (7.1); child registry in the manifest under a versioned schema bump with its schema test, and reconciliation on start (7.2); the ordered shutdown owned by the root, request file as second trigger, `commit-shutdown` as its front half, llama-server stopped by reason instead of unconditionally, the WAL checkpoint on the ordinary close (7.3); dead-Engine updater path (7.3); the closure check's `SKIP_PATHS` narrowed so the supervisor file's writers are checked (7.1); the dev-runner's death-observability test wired into a runner and given lease and run-id assertions, which it lacks today; `ExitOnOutOfMemoryError` (8) | the three restart paths work through requested restart; a crash comes back under the budget; children are adopted or killed | harness green on both implementations; a forced kill on the live stack recovers; the dead-Engine path exercised in a sandbox round; the death-observability test runs in CI and passes |
| **C1. Context and resources** | engine context with its two axes threaded through the ports (the `IndexingService` port's 33 methods carry no context argument today, and `Query.context` is an occupied map, so the type is a new parameter, not a slot), provenance at the grant store's slot plus the ledger column and request attribute seeded from `ActionEvent` (3.4); admission per context at the front, aggregate caps, reason codes and retry-after, admission as the `ForegroundLoad` producer (8); executor registry with bounds, disjoint pools, the ten common-pool sites migrated and the ArchUnit no-common-pool rule; the retained-state register with its caps, and urgency held by the work item rather than the request (8, review seven); memory budget lines and `MaxDirectMemorySize`; the attack-surface pins of 6: the extraction routing table as parser authority, the routed families' in-process fallback deleted, the ArchUnit rule confining those parsers to the child's module | the API is bounded; the webview handles rejections | contract diff shows only the enumerated changes (6); a scripted agent loop hits admission and gets reason codes, never timeouts; the aggregate-bound row of 16 |
| **C2. Operations** | operations table under the recoverability register, and `jobs.db` re-classified there (it is `DERIVED` today, which the operations table makes wrong); acceptance, effect, completion order stamped from the commit sequence number the journal already carries (`commit`, then `drainPending()` is the precedent); client-supplied key, for which the inert `OperationInvocationRequest.idempotencyKey` wire field gets its first consumer, and outcome query answering the recorded outcome on a same-key retry, with `expired`, `history since` and the retention period, and `version conflict` on a reconfigure issued against a moved applied version (7.6, review seven); resume under the four conditions for ingestion and reindex bundles; ingestion's per-file recovery re-based on it (7.5, 7.6) | a killed ingest resumes from its checkpoint; the outcome query answers all five states | the three-point forced-kill row and the resume-conditions row of 16 for a write |
| **D1. Reconfigure, generations, readiness** | recomposable registry, applied and attempted versions, beside-or-in-place, `generation-bound` and `restart required` in the config register (7.4); `core.restart-worker` retired and its `structuredData.port` consumers re-pointed at reconfigure (6); the generation cutover at the scope 17.4 fixes, on top of `IndexGenerationManager` (live runtime swap in place of the post-cutover restart, the journal and replay, the failed-unit default flipped, 17.7), under the review-seven contract: the reindex as a converging operation with gaps distinct from processing history (7.5), lexical-only ingestion with deferred enrichment in the in-place mode and the response field that says so, encoder A recomposed on refusal or abandonment, and the applied config version computed from the active generation's metadata with boot composing encoders from it (7.4); the candidate footprint declared per model and free device memory read in-process (7.4); readiness as a component vector with re-cut codes and `readinessNotice.ts`, the second readiness representation made a projection or retired (17.7), start deadlines, local recovery and bounded escalation (7.6) | an encoder config change applies with the API up; requested restart serves restart-required settings only | the reconfigure, stuck-component and generation-transition rows of 16 |
| **D2. Self-description and request-time paths** | component map endpoint and its coverage test, profiles (`full`, `bench`, `verification`), engine-as-library (10); session gate with aging under the `passed` rule and the one-producer test (4, review seven); index-and-return at the port with its durability test (the port's `submit` discards the sequence number today, so the durable variant is a new port method, not a flag); cursor generation token, reader pinning for a live cursor (Lucene's `SearcherLifetimeManager` is the obvious primitive) under the cursor and reader caps of the retained-state register, and `cursor expired` (4, 8); the ephemeral store axis for the `verification` profile completed (the Lucene side exists: `IndexSchema.ephemeral()` builds at an auto-temp path that `RuntimeSession` deletes on close; the three SQLite stores take a file path only, so they gain the temp-and-delete variant) | the verification profile boots in seconds; every 16 row is exercisable | the request-time-encoder and durability rows of 16 |
| **E. Gate run** | no code beyond the fixes the run forces; collector and heap chosen, and the supervisor's hang poll interval and count set with them from the soak's worst safepoint pause (7.1); the owner's bounds and the three allowed-difference classes (17.7) instantiated from the PR 0 baseline before the run; paired against `main` after PR 0 on the same corpus and machine, plus a run at the supported floor | the measurement record exists | the 16 table row by row, with the section's decision rule applied to any failed row |
| **F. Prose sweep and report** | `CLAUDE.md` invariant 1, `AGENTS.md`, the subagent baseline brief, skills, postmortems, `19-module-architecture.md` rewritten on the rings, jseval and `scripts/agent-analytics` parsers, governance registers and contract-surface registrations, the docs listed in `verified-facts.md`; the report-back (19) | nothing on the branch names the Worker as a process | the 917 §8 residue grep returns only labelled hits; `docs-validate` and the regen set green |

Then one merge, one go-ahead. Between checkpoints the branch may be red only in the way the
"branch state after" column names; any other red is a defect of the stage.

### 17.4 Three questions, resolved against the code

- **Stage A is one stage, not compose-then-delete.** `WholeProgramDeadCodeTest`
  (`modules/dead-code-audit`) is a closed-world ratchet against a committed baseline: a wire
  left compiled but unreferenced after the composition swap is a set of new dead classes and
  the checkpoint is red. `adr-0002-grpc-present` (`governance/adr-probes.v1.json`) is a
  grep-present probe on `io.grpc` across `modules`, so it is red the moment the wire is deleted
  unless the ADR supersession retires it in the same change. Both gates put compose, delete and
  probe retirement in one checkpoint. The review is structured instead of split: additions
  (root, ports, re-homed contracts, spawn path) are reviewed closely; deletions are verified by
  the compiler and the ratchet, and the reviewer checks only that nothing was deleted beyond
  the 917 consumer audit's list.
- **No parallel work inside the branch.** The one candidate was stage B's Rust half beside
  C1 in a second worktree. It is coupled to the JVM half at protocol level: the harness fixes
  the contract, the request file is written by Rust and read by Java, the dead-Engine path
  reads the child registry Java writes. Once the harness exists the Rust implementation is
  small, and a second worktree's spawn, brief and integration cost exceeds it. Sequential, one
  agent, one stage at a time; the multi-agent economics paragraph in `CLAUDE.md` says the same
  for coding lanes.
- **The generation cutover's scope in D1** *(corrected 2026-09-07, 17.9: the first draft of
  this bullet said no generation directory or activation swap exists; both do)*. Today a
  model change is a parity mismatch: `IndexMetadataParityGuard` refuses to open the index and
  the user runs `core.bulk-reindex`, an in-place rescan through the normal pipeline
  (`IndexingService.reindexWatchedRoots(force)`). Beside that path, `IndexGenerationManager`
  already keeps generational index directories under a `state.json` with promote and
  rollback, and the Blue/Green migration (the default `index.migration.strategy`) builds the
  candidate in a second generation and swaps `state.json` atomically at cutover. What it does
  not do is what 16's generation-transition row exercises: the cutover restarts the Worker
  rather than activating live, no journal records writes accepted during the build and
  nothing replays them, the failed-unit gate defaults to unlimited, and no reader is pinned
  for a cursor. D1 therefore owes exactly those on top of the existing manager, not a second
  generation mechanism: `IndexFingerprint` (already carrying embedding, SPLADE and NER model
  identity plus chunking) becomes the representation-generation identity; the candidate
  builds in a generation directory through the existing per-document pipeline, driven as a
  durable operation (C2); a journal table beside the operations table records every write
  accepted during the build; replay, then the existing atomic `state.json` swap followed by a
  live runtime swap instead of a restart, refused while any unit failed unless the gaps are
  accepted; cursors expire rather than migrate. Outside the lane, as 7.4 says: chunking and
  batching strategy, and retention beyond the two generations the transition needs.

### 17.5 After the merge: in-lane follow-up PRs

Each is a named cheap constraint (13), none gates the merge, and each touches many files, so
they land after the sweep rather than under it: proto DTOs replaced by `app-api` records at
the ports (6); the platform module, tier 1, with its ArchUnit allowlist (9); the egress rule
for the whole Engine (9); the generative backend interface with its empty interceptor slot (9).
Ordinary PRs, each with its own go-ahead. The lane closes when the report-back (19) covers
them.

### 17.6 Checkpoint protocol

What a stage's start and end require, so every stage is run the same way by whichever agent
picks it up:

- **Stage start.** The implementing agent writes the stage checklist in
  `docs/design/lane-f-engine-jvm/stages/<letter>.md` from three inputs: the stage's row in
  17.3, `verified-facts.md`, and a fresh read of the code the stage touches. The base moves as
  stages land, so a fact verified at `b96cd999` is re-checked against the branch head; one that
  no longer holds is corrected in `verified-facts.md` with its new citation before the checklist
  relies on it (`verify-dont-guess`). The checklist lists items with acceptance criteria, names
  the section 6 contract changes the stage makes and the gate for each, names the section 16
  rows the stage must leave exercisable, and names exactly what the "branch state after"
  column allows to be red.
- **Stage end, in order.** Compile and the full suite green except the named reds, plus the
  ui-web gate set where the stage touched `modules/ui-web`; the checkpoint proof from 17.3 run
  live and recorded under `docs/design/lane-f-engine-jvm/evidence/<letter>/`; an independent
  review of the stage's commit range by an agent other than the implementer
  (`independent-review-required`), recorded in the managed review record with the range, its
  findings fixed before a go-ahead is requested; then the owner's "continue" go-ahead, which
  authorises the next stage and nothing else. The critical-analysis pass (`CLAUDE.md`) runs
  before the review is requested, not instead of it.
- **Design errors found mid-stage.** The design is amended in place with a dated line in
  section 0 naming the stage that found the error and what changed. An amendment inside a
  decided line (a mechanism detail, a citation) proceeds; a change to a decision (15), a gate
  row (16) or the stage table (17.3) is decided by the implementation orchestrator and recorded
  in section 0 with its reasoning (owner delegation, 2026-09-07; it previously waited for the
  owner's word). Merge go-aheads are not delegated.
- **Drift.** `git merge origin/main` weekly and once more before stage E; never a rebase of the
  pushed branch (`agent-lessons.md`). With development halted, PR 0 is the only expected source
  of conflict.
- **Commits on the branch** are per checklist item so a review range is legible. ADR-0045
  squashes at the merge, so the branch history is working history; the PR body written at
  stage F carries the public narrative.
- **Stage E runs, it does not build.** It runs under `/jseval` and `/dev-stack` with the
  dev-runner's supervisor and a campaign lease, the 17.7 values fixed before the first run. A
  failed row is answered by the section 16 decision rule; a fix re-runs the rows it can affect,
  and a fix to a shared mechanism (collector, executors, admission, the session gate) re-runs
  the whole table.

### 17.7 Owner-set parameters

Every value the design leaves to the owner or to the gate run, collected so none is discovered
missing at stage E. "By" is the stage before which the value must exist. *(locked 2026-09-07)*
The owner's values are recorded in the "value" column; a starting value is a number the
PR 0 baseline or the gate run confirms, not a design fact, and the "by" column says which.

| parameter | section | value (owner, 2026-09-07) | by |
|---|---|---|---|
| primary supported operating shape | 18, 3.1 | **one shape: a running desktop application that also serves agents.** Agent access is a mode of the running application, not a deployment; recovery during an agent loop is automatic and prompt-free, and an exhausted supervisor leaves a visible state, never a dialog. Unattended headless and trusted-network sharing stay unsupported behind their own ADRs; a Tauri-less Engine is a development shape | A (the supervision scope reads it) |
| tolerated work loss per fault, in 16's units; what must stay usable through a fault and through ordinary maintenance; which interrupted work is unacceptable | 13, 18 | **set at what the mechanisms guarantee, no looser and no tighter**: at most the one agent turn in flight is interrupted; durable operations resume, never restart; only writes in flight at the crash need a query. Unacceptable: an accepted write lost silently, a half-applied configuration surviving a restart, a reindex starting over. Floor through a fault: API and text search within cooldown plus warm start. Floor through maintenance: text search always, semantic bounded by the row below (*moved from E, review seven*: tolerances constrain the investment; the numeric thresholds derived from them stay at E) | A |
| semantic-availability bound during a generation transition on the floor machine (fraction and wall-clock) | 16 | **paired first, absolute second**: on the floor machine the in-place transition unloads encoder A, so the refused fraction approaches the whole build plus replay and wall-clock is the number that matters; the bound is "not longer than the split's semantic outage for the same reindex on the same machine" from the PR 0 baseline, plus an absolute ceiling the owner names once that baseline number exists | E, before the first run |
| operations-history retention, after which a key answers `expired` | 7.6 | **30 days, plus a row cap** so a scripted agent cannot grow the history without bound | C2 |
| retained-state caps: cursors per context and aggregate, pinned readers, co-resident encoders | 8 | first cut by the implementer from the budget; **the webview is not privileged over agent contexts** (urgency lives on the work item, so identical per-context caps); overruled only if a gate row fails | C1; confirmed at E |
| supervisor budget: max restarts, cooldown floor and ceiling, stability window, hang poll interval and count; exit-reason classes | 7.1 | **seeded from 627 (3 restarts; 300 s window), semantics re-cut** (7.1, *re-cut 2026-09-07, lock*): requested, transient and non-transient exit classes, with non-transient exhausting at once; cooldown floor is handle release and the ceiling a few seconds above it; the window runs from `ready`; hang interval and count set with the collector | B for the classes, cooldown and window; E for the hang parameters |
| start deadline per component; local-recovery attempt budget; reason codes that escalate immediately | 7.6 | index deadline generous against today's index-ready time and the encoder deadline against the observed load time (both from the PR 0 baseline); **two local attempts before escalation**; immediate escalation only for what a retry cannot fix: corrupt store, native crash, out-of-memory | D1 |
| who may accept gaps at generation activation | 7.4 | **a user through the webview, shown the gap list; agents report, never accept** | D1 |
| admission defaults per context; aggregate caps per executor; retry-after | 8 | first cut by the implementer from the machine budget; identical per context (row above); confirmed by the owner | C1; confirmed at E |
| memory budget lines (heap, Metaspace, direct, ORT arenas) | 8 | first cut at A; final by the gate run | A, E |
| collector (G1 or ZGC) and heap | 8 | gate run | E |
| session-gate aging threshold and background batch size bound | 4 | first cut at D2; final by the gate run | D2, E |
| group-commit coalescing deadline for `durable` index-and-return; cursor expiry | 4 | implementer | D2 |
| checkpoint cadence | 7.5 | fixed: per unit, at most every 30 s | C2 |
| gate bounds: p95 ceilings, admission rejection ceiling, indexing-progress fraction, warm-start budget, soak duration and workload, floor machine, corpus | 16 | **every bound derived from the PR 0 baseline with a declared margin.** Starting values: p95s at the split baseline plus ten percent (a noise margin, not a permitted regression: a consistent loss under it is still investigated under the section 2 rule before the flip); rejections zero with the agent idle and one percent under the scripted agent; indexing progress ninety percent of split; warm start at baseline index-ready plus five seconds; soak two hours of indexing, a scripted agent and a reconfigure every fifteen minutes; floor machine the lowest GPU class the lower-memory guidance names; corpus the jseval reference corpus | E, before the first run |
| allowed-difference class per non-deterministic fixture field | 16 | **exactly three classes**: reason codes that did not exist in split, ordering of equal-score hits, generative text under a fixed seed; nothing else, and none added after a diff is seen | E, before the first run |
| disclosure contract and exporter policy; non-goals amendment for idea 1 | 18, 11 | **two properties written apart** (retrieval and inference are local; disclosure happens at the handoff to a client as an authorisation decision, where "authorised" is a grant scoped to a folder or a session, not a client label); **OTLP export permitted in development, off in packaged builds unless a visible setting turns it on, egress class recorded either way**; the non-goals amendment waits for a write-back tool to be proposed | not gating: before the first write-back tool, and before stage F touches the README's neighbours |
| failed-unit default at activation: `index.migration.cutover.max_failed_jobs` moves from -1 (unlimited, today) to 0, or the owner names another value | 7.4, 17.9 | **0**: today's value activates with documents missing and nobody told; with gaps reported and replay cheap, refusing is right and the row above is the escape hatch | D1 |
| which readiness representation survives as the authority's name (`LifecycleSnapshotV1` or `ReadinessEnvelopeView`) and which becomes its projection | 7.6, 17.9 | **the envelope**, whose ten dimensions are already component-shaped; the lifecycle snapshot becomes its projection | D1 |
| `UseCompactObjectHeaders` in the Engine's flag set (the Worker carries it today, the Head does not) | 8, 17.9 | gate run, with the collector | E |
| deterministic capture for the workflow fixture | 16, 0 | **decided 2026-09-07**: PR 0b lands the chunk-leg stable tie-break (unconditional), an exhaustive-kNN switch, a `sampling` request override with seed, candidate-budget pins and an applied-sampling echo; the gating reference is a same-build noise pair per side (section 0); CPU encoders measured too slow and stay an instrument | PR 0b; the baseline is retaken under it |

### 17.8 What would re-cut this sequencing

The cut holds under stated conditions. If one fails, the cut is redone, not bent:

- **Development resumes on `main` while the branch is open.** The coherence rule regains a
  consumer. Either the resumed work waits, or the sequencing reverts to the incremental shape
  from the next checkpoint, which reopens 15's flag decision. The owner's call, made then.
- **A release must be cut mid-lane.** A fix to split code lands on `main` as its own PR and is
  merged into the branch; the "no hotfix path" cost in 17.1 is paid, not avoided.
- **Stage A's checklist outgrows the 917 consumer audit.** Time-to-complete is an architecture
  signal (`agent-lessons.md`): the stage stops and the design is re-read at 3 and 6 before more
  code is written.
- **A gate row fails and its remedy under the section 2 rule is a process boundary.** The
  branch does not merge; the design reopens at 2 and 14, not at 17.
- **A stage needs a mechanism the table places later** (for instance the shutdown's step 3
  needing the operations table before C2). The item moves earlier with a note in section 0; the
  checkpoint order itself does not change.

### 17.9 Derisk pass on stages B to D2 (2026-09-07)

Three read-only audits, one per stage group, took each mechanism the stage table names and
asked the code two questions: what exists today, and what the design says exists. The
orchestrator re-read every citation the verdicts rest on. Stage A was not audited here; the
917 consumer audit and the 17.4 resolution already cover its two gates. Citations are in
`verified-facts.md`, section "Derisk B to D2".

**Verdicts.** No mechanism is blocked, and no decision in 15 or gate row in 16 changed. The
pass changed the design in three ways: it corrected sentences that described the code as it
is not, it added the work those corrections reveal to the stage rows in 17.3, and it added
three owner values to 17.7.

| stage | mechanisms with a seed in the code | mechanisms built new |
|---|---|---|
| B | ordered shutdown (`performOrderedShutdown`, 8 steps); request-file trigger has a sibling in the persisted `UpgradeIntent`; child identity by PID plus start instant (the `AppInstanceLock` and `RuntimeManifestPublisher` pattern); extraction children already watch the parent; llama-server adoption by port | supervisor contract and both implementations (Tauri today signals death only before the port is bound; its `restart_headless_backend` is the requested restart, not supervision); child registry and reconciliation with identity and config match; llama-server stop by reason; WAL checkpoint on ordinary close; dead-Engine updater branch |
| C1 | `ActionEvent.originator` and `.transport` as the provenance seed; `ForegroundLoad` as the pacing consumer; `ModelSessionPolicy` as the device-side cap | the context type as a port parameter (no slot exists on the indexing port or in `Query`); executor registry and bounds (none today); the common-pool migration; host-side memory lines; extraction routing as the parser authority |
| C2 | the journal's commit sequence number and the commit-then-`drainPending()` order; the versioned SQLite schema ladder; the inert `idempotencyKey` wire field | the operations table and its recoverability row (and `jobs.db` re-classified); acceptance, effect and completion stamps; outcome query; checkpointed resume |
| D1 | `IndexGenerationManager` with generational directories, `state.json`, promote and rollback; Blue/Green migration with an atomic swap; `IndexFingerprint` as the generation identity; `InferenceLifecycleManager.applyConfig` restart-with-rollback as the in-place shape; `ReadinessEnvelopeView` as a component-shaped readiness | live activation without the Worker restart; the journal and replay; the failed-unit refusal (default is unlimited today); applied versus attempted config versions; beside-mode compose (a same-directory second reader is a leak; beside means a second generation); footprint declaration per model and in-process free-memory read; start deadlines; the second readiness representation folded |
| D2 | `AutoCloseable` inference surface and `releaseGpu`; stateless `searchAfter`; Lucene ephemeral runtime | component map (the three named endpoints carry slices, not a component list); reader pinning for a live cursor; the durable index-and-return port method (today's `submit` discards the sequence number); SQLite ephemeral variant |

**Sentences the code contradicted**, each corrected in place and marked *(corrected
2026-09-07, 17.9)*; the section number is where the correction sits:

1. 3.4: provenance "where a slot exists (the ingestion ledger, request logging,
   `DurableGrantStore`)". Only the grant store has a slot; the other two are new columns.
2. 7.1: the supervisor file "under" the closure check. The check exempts both writers.
3. 7.2: `app-inference` "already having the adopt path". Adoption is by port and HTTP shape
   only; identity and config match are new.
4. 7.3 step 6: llama-server left running on `restart` and `hang`. `close()` stops it
   unconditionally today.
5. 7.3 step 7: SQLite checkpoint at close. Today only the upgrade barrier checkpoints.
6. 7.4: beside-compose as "a second `IndexReader` over the same directory". A documented leak;
   `swapRuntime` is close-then-open for that reason. Beside means a second generation.
7. 7.4: free device memory "measured" against a "declared footprint". NVML is read only in
   `gpu-bridge`; no model entry declares a footprint.
8. 7.4: activation as "one metadata swap". The swap exists; today's cutover then restarts the
   Worker.
9. 7.4: "the default refuses". Today's cutover default is -1, unlimited.
10. 7.4: readers "a live cursor holds". No reader outlives a request today.
11. 7.6: one readiness vector. Two representations exist today.
12. 8: ORT arenas "bounded per session" as a commit-charge line. The cap is VRAM-sized and
    device-side; host allocation is unbounded.
13. 8: "Tauri and the dev-runner launch the Head today with `UseSerialGC` and
    `TieredStopAtLevel=1`". The dev-runner drops the tier flag with an AOT cache; a third spawn
    site (`WorkerSpawner`) adds `UseCompactObjectHeaders`.
14. 8: "no core code uses `ForkJoinPool.commonPool()` ... (zero uses today)". Ten bare
    `supplyAsync`/`runAsync` sites; dozens of executors (58 construction sites by grep) with no registry.
15. 8: "every named executor has a fixed thread count and a bounded queue". None does.
16. 10: the component map "seeded from" three endpoints. None carries a component list.
17. 17.4: "no generation, no journal and no second index" (the orchestrator's own error, one
    day old). Generations and the atomic swap exist; the journal, live activation and pinning
    do not.

**What the pass did not do.** It did not audit stage A beyond the two gates already resolved,
did not run anything, and did not review the Rust supervisor implementation's cost (the
harness-first order in 17.4 stands on the audit's confirmation that Tauri has no supervised
restart today). Readiness for the lock, as of the derisk pass: the design described the code
correctly at every point the three stages touch. Review seven, the same day, then found six
places where the contract's own promises could not all hold (section 0); those are amended in
4, 7.4, 7.5, 7.6 and 8. The owner then recorded every value in 17.7, re-cut the supervisor
budget (7.1) and locked the design the same day (section 0, 15). What remains before stage A
is PR 0 merged, the baseline captured (17.2), and the implementer's first cut of the memory
budget.

## 18. Cross-lane requests

The owner items below, with every other value the design leaves to the owner or the gate run,
are collected with their values and deadlines in 17.7. *(locked 2026-09-07)* The two owner
items are decided; what remains of each is an edit outside this lane.

- **Owner (product docs), decided.** The README's "documents never leave the machine, only the
  answer does" and the threat model's no-telemetry promise were not scoped to what the MCP
  tools hand an external agent or to the configurable OTLP endpoint (9). Decided: two
  properties written apart, local retrieval and inference on one side and disclosure at the
  handoff as an authorisation decision on the other, where "authorised" is a grant scoped to a
  folder or a session and a cooperative client label is not consent; OTLP export is permitted
  in development, off in packaged builds unless a visible setting turns it on, and the egress
  class is recorded either way (17.7). The README and threat-model edits that state this are
  the owner's, not lane F's, due before stage F touches the README's neighbours. The README
  also stops naming MCP hosts: recovery is promised for the protocol (the harness, 7.6) and
  the three named clients, never per host (15).
- **Owner (operating shape and work-loss tolerance), decided.** One shape: a running desktop
  application that also serves agents; unattended headless operation and trusted-network
  sharing stay unsupported behind their own ADRs (3.1, 7.1, 14). Work loss is tolerated at
  what the mechanisms guarantee and no looser, in 16's units; the values, the usable floors
  and the unacceptable losses are in 17.7, with the semantic-availability bound for ordinary
  maintenance (16).
- **Idea 1 (agents write back).** The non-goals ("not a note-taking application, not a system
  of record") are amended before the first write-back tool ships (11). An accepted write makes
  the product the authoritative holder of that fact or note, with the lifecycle obligations 11
  lists, whatever the headline says about not being a PKM application.
- Expected: the inference lane (host contract with priority and aging, 5); lane D's field
  register (11).
## 19. Report-back

Not started. Filled per `00-program-overview.md` when the lane closes.
