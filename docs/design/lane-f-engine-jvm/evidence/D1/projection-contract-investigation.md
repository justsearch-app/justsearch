# Non-file projection contract investigation

2026-09-14. Read-only explorer and independent refutation against lane F
`0ad7e1914` and project-memory design `d69b39dcb`, followed by root source checks.
This record preserves findings for the required D1/D2 mechanism pass. It does not
adopt the explorer's proposed schema or claim implementation/proof. No owner input
is needed; the lane orchestrator resolves these contracts before implementing D1.

The955 extension is already owed: D1 must preserve non-file port writes and name
missing projections as gaps; D2 must provide a durable identity-delete receipt.
The current file path/hash completion predicate does not fully specify either.

| Finding | Primary evidence | Required design/proof consequence |
|---|---|---|
| Existing facts may predate reindex and never emit another mutation. | Project-memory design:217-240; D1.md:464-486 | Seed the authoritative live projection set as well as replaying concurrent port writes. Unreadable/locked source is a gap, never an empty source. |
| The switch-buffer revision is a replacement token, not source order. | SqliteQueueSwitchBufferOps:155-175,258-268; SwitchBufferVersionTest:20-33 | Keep source order distinct from conditional cleanup identity. A replayed delete followed by a delayed older upsert must not resurrect the document. |
| Current replay converts UPSERT into queued file work. | KnowledgeServerMigrationOps:439-469,750-789 | Non-file upserts/deletes must reach the building runtime and a covering commit before being cleared or reported durable. Queue admission is insufficient. |
| Source revision can move backwards during an explicit restore. | Project-memory design:140-157,192-196,240-251 | Define recovery ordering. Compare a quiesced restore that drains old work and abandons a running reindex with a durable source incarnation. Do not add an epoch authority until simpler existing lifecycle ownership is disproven. |
| A free-running snapshot leaves an undefined activation cut. | Lane F design:1307-1325; project-memory consumer:147-158 | Define the port-admission fence, candidate commit, exact identity/revision gap comparison and swap ordering. Distinguish accepted port writes from source mutations not yet projected. |
| IndexDocument is an untyped field map; current writes trust its id. | indexing/IndexDocument:4-5; WritePathOps:83-104,154-169 | Give the port explicit owner/identity/revision semantics; the adapter owns reserved identity fields. Bind sources through contract-module ports at EngineRoot, without reverse app-agent dependencies. |
| Existing write/delete wrappers discard Lucene sequence numbers. | WritePathOps:157-203; CommitOps:77-110; D2.md:214-243 | An already-absent delete still needs a covering commit. Journal before acknowledgement; failed commit, timeout or cancellation cannot return durable success. |
| Note un-designation can race its watcher. | Project-memory design:349-357; RootLifecycleOps:370-407 | Source owner must quiesce the watcher before the delete cut; a point-in-time durable receipt alone cannot stop a later writer. Fold this correction into the consumer contract. |

The rejected initial proposal combined a consistent snapshot, a source revision and
a tagged switch-buffer row without specifying restore, seed/journal ordering or
the activation cut. A count/hash manifest is not automatically required: exact
enumerated identity/revision gaps may suffice. Likewise, retaining the latest
per-identity mutation until activation may reuse the existing journal instead of
introducing a separate tombstone/version table. These alternatives need concrete
ownership decisions in D1/D2's current sections before implementation.

Required adversarial proofs include older seed versus newer journal, retained delete
versus delayed upsert, equal revision with different content, explicit restore,
port admission racing activation, direct replay crash before/after commit,
unreadable source, already-absent durable delete, commit failure, coalescing,
immediate forced restart and watcher events racing note un-designation. Existing
replacement-token tests do not discharge those requirements.
