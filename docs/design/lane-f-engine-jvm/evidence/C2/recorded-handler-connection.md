# C2 d.3b.3 — prepared handlers and public ingestion

Selected2026-09-14 at pushed0431bbe74. Producer .2b is locally verified; C2 remains open.
This mechanism completes the public path required by C2-8d-vertical-plan.md and
recorded-ingest-binding.md. It does not change the merge-at-F placement.

## Existing owners and selected mechanism

WatchedRootsState owns membership and labels. Its registration/removal/loading methods use
the state monitor; production aliases of rootsMap only read. Add a synchronized snapshot
returning immutable IngestCollectionPolicy.RootBinding entries from that same owner. Do not
call RootLifecycleOps.getWatchedRoots: its availability projection can schedule filesystem
work. No second map, revision, registry or durable snapshot is needed.

IngestTool and ReindexHandler receive four existing-domain collaborators: the stable
RecordedIngestionService, a root-binding snapshot supplier, a context-aware strict generation
supplier, and an effective exclusion-pattern supplier. This avoids a new preparation service,
mutable adapter or duplicate plan representation. Compose them in the existing factories;
generation resolves the currently connected IndexingService at preparation time, never a
client retained before Engine replacement. Root state and recorded service remain process-owned.

KnowledgeClient owns the resolved exclusion observation. Add a strict recorded accessor that
requires the ConfigStore, validates the JSON string-array shape and reuses ExcludeMatcher's
existing normalization. The ordinary maintenance fallback is unchanged. Copy roots, then
effective exclusions, then capture the strict serving generation once. These observations
do not claim a transaction across independent owners: the accepted immutable plan is the
chosen policy, and generation/live authorization is rechecked before effects. D1 owns leases
across index transitions. Concurrent later configuration cannot rewrite an accepted plan.

IngestTool retains JSON validation and the existing agent path-resolution algorithm, but reads
only the captured root snapshot. Initial preparation may read path attributes to classify the
input; it performs no write, scan, enqueue, availability refresh or document-content read.
Use non-following attributes consistently with the recorded Worker. Resolve root-relative
inputs through AgentToolPaths, including the existing existence-tested fallback; never use cwd
for an unresolved relative input. Refuse unresolved, missing, unreadable, link or unsupported
inputs before effects rather than silently completing a partial requested set. This intentionally
supersedes the old write handler's skipped-input/unknown-roots-as-empty behavior; read tools keep
their existing rules. Freeze singleFile and normalized absolute target in RecordedRootPlan.

Use IngestCollectionPolicy as the sole collection authority. With an explicit collection every
requested/nested root gets that collection, allowing same-policy partition collapse. Without an
explicit collection, freeze the deepest containing watched-root label for each requested/nested
root; include nested watched roots under requested directories so distinct labels retain subtree
ownership. Paired tests cover the same directory/nested roots with and without explicit collection.
Reindex snapshots
all watched directory roots with their original nullable labels and the requested force flag.
RecordedRootPlan.partition owns deduplication, same-policy collapse and nested exclusions.
Empty reindex membership is an explicit empty plan; invalid ingest is a typed preparation refusal.

Both handlers create METADATA preparation with the unchanged public arguments and existing
root-plan.v1 payload. Validation/approval preview use only the frozen preparation (public input
plus payload), never current roots/config/filesystem. Reindex validates directory shape and force
agreement; ingest validates non-force roots and explicit collection agreement. Tests reject those
schema/correlation mismatches before the service. Relative path resolution is the trusted initial
handler's responsibility and is proven there; the key/nonce/descriptor envelope already binds the
persisted value. Do not add a second relative-path witness to defend arbitrary forged server
metadata inside the local trust boundary. Prepared execution
requires the issued record and passes that exact handle/context to RecordedIngestionService;
its asynchronous completion is returned unchanged. Direct execute and missing-record prepared
execution refuse without effects. Retire old callback, unkeyed submitBatch/scanRoot and ordinary
reindex/flush routes from these handlers; periodic maintenance is a separate existing owner.

The dispatcher keeps key lookup before preparation. Same public input returns recorded state
without reading roots/config/filesystem or scheduling; different input returns OPERATION_KEY_REUSED.
Boot recovery uses the coordinator's existing production resolver and winning Resume body;
it decodes the accepted envelope and never calls fresh handler preparation.
The handler cannot mint child identities, rows, permissions or queue receipts.

REST /api/knowledge/ingest must dispatch core.ingest-files through the same operation owner and
carry the existing operation-key/confirmation contract. Remove its direct adapter/filesystem
loop. Preserve the local mutation trust boundary and use the existing dispatcher HTTP response
mapping; accepted asynchronous work is represented by its operation identity, never a guessed
accepted-file count. Audit and update concrete callers and canonical contracts in that same cut.

## Per-item batches and verification

Review clarification, 2026-09-20: retire the agent-only scan-observation forwarding
through HeadAssembly and AgentToolFactory with .3a. Prepared ingestion no longer uses
that adapter; its durable operation progress is the recorded coordinator's projection.
Keep the REST adapter's existing scan observation until its .3b dispatcher retirement.
Preserve factory registration and journal-reuse tests while replacing obsolete scan-binding
assertions with recorded-owner composition coverage. Explicit collection length validation
uses the existing RecordedRootPlan bound and returns BAD_REQUEST before owner reads.

1. .3a: atomic root/policy observation, both prepared handlers, eager/late/replacement composition,
   removal of their old effect paths, focused regressions and relevant docs. Test snapshot coherence,
   relative/absolute/file/directory/nested policies, unavailable state, strict generation, frozen
   replay, exact handle/context, delayed completion and no legacy effects. Migrate superseded
   handler tests to these contracts while retaining path/collection regression coverage.
2. .3b: REST dispatcher connection, request/response/key/confirmation coverage, consumer and
   canonical contract updates; prove REST and MCP reach the same registered prepared handler.
3. .3c: real production resolver -> runner -> SQLite -> coordinator -> Java producer composition
   for both operation families. Prove acceptance before effects, mutation after acceptance cannot
   retarget, key-first retry, stale generation/authorization refusal, receipt/ack barrier, restart
   and replacement. Reconcile full affected tests, architecture/PMD/format, governance and hosted
   results. Final live/model/installed and remaining C2 items stay required, not waived.

Commit and push each batch; checkpoint owned WIP at least hourly. Root owns shared lifecycle,
composition, source integration and all builds. Bounded test/handler drafts may be delegated
only with these constraints; ambiguity returns to root. Independent review and discriminating
negative controls precede each local verification claim. Design and proof discoveries amend
this owner before implementation grows beyond its declared batch.

## Design review and initial implementation (2026-09-15)

Independent design review at0431bbe74 clarified explicit collection precedence and frozen
preparation correlation. Both corrections are in the owning mechanism above. A proposed second
relative-path witness was retracted after checking the trusted preparation/envelope boundary;
no new representation is introduced. This review supplies design evidence only.

Root added atomic state/policy accessors, ReindexHandler and eager/late/replacement composition.
The initial snapshot tests covered detached values but did not prove concurrent registration;
root added a held membership-before-label regression that must discriminate loss of the shared
monitor. Root also added actual factory/service forwarding and live-generation-supplier tests,
and frozen force/kind/content/schema mismatch refusals. Handler-specific tests are being migrated
in bounded owned files. No build/test result is credited until the first integrated compile/run.

User-requested pause:1837 production compilation and1841 focused91 cases/15 suites pass
(no failures/errors/skips, both test tasks executed).1842 docs checks,1843 format application
and1844 three governance gates pass.1839 compile misses and1840 Windows JSON fixture failures
are preserved; root corrections and all remaining proof are in
[the pause record](session-2026-09-15-closeout.md). This is a WIP checkpoint, not .3a completion.
