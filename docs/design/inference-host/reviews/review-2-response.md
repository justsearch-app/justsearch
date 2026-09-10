# Response to independent review 2 (2026-09-10)

Review 2 is `review-2-findings.md`. All sixteen findings accepted. The structural response is to
descope the shared host to a separate stage H2b (design v3 section 0 and 13); five Highs and
three Mediums existed only because of sharing. Per finding:

| # | sev | finding | v3 answer | where |
|---|---|---|---|---|
| 1 | High | growth tickets drop workspace; entitlement ownership across transitions undefined | allocation classes; a session's `weights + arena + workspace` entitlement; after warm-up the run entitlement (arena remainder **and** workspace) is retained, transferred atomically into a per-seat growth ticket with the seat, returned at run end, never charged twice or dropped | 5.3 conservation rules; H1 ledger traces |
| 2 | High | no crash-safe launch intent; attachment expiry invalidates a live owner | ticket state machine with a **persisted launch intent** before any spawn (Engine manifest private projection, linked from the child record); `RETAINED` for unknown outcomes, released only by reconciliation; tickets survive Engine restart with the adopted child; retries settle the predecessor attempt first; attachments are H2b's | 5.3, 5.4, 7.1, 13 |
| 3 | High | completion is work-id-shaped, not per call | `SubmissionId` (the request UUID) is the per-call identity; per-submission terminal records; idempotent `outcome` retrieval; `ack` retires; Engine retention per submission per C1 1376-1387; `cancel(workId)` is a group fence; pre-admission refusals first-class | 4.1, 4.4 |
| 4 | High | epoch fencing does not prevent overlapping execution | H2 is unshared: the predecessor is dead by construction when the successor attaches; the replacement barrier (attach admitted, non-runnable until predecessor exit) is specified as H2b's requirement | 7.2, 13 |
| 5 | High | no ownership recovery after the supervisor dies | H2b requirement: host-held device lock covering the host's lifetime; persisted supervisor ownership record; successor adoption before launches; unknown identity fails closed | 13 |
| 6 | High | mismatch fallback bypasses the device authority | fallback is CPU-only (GPU providers and lazy retry disabled) or `UNAVAILABLE`; same rule for `bench` and `verification` beside an owned device; H2 gate row checks no Engine GPU allocation | 5.5, 11 |
| 7 | High | representation identity lacks canonical inputs | `RepresentationDescriptor`: model family digest, manifest and tokenizer digest, resolved prefixes, pooling, window geometry, max sequence, dimension, sparse vocabulary id, label set, version; `unknown` vs `known-absent`; legacy policy (serve on matching recorded fields, report `partial`, refuse representation-bound changes); `partial` never counts as gate pass | 4.3, 6.2, 11 |
| 8 | High | write binding ignores source revision, accumulators, and the replay acknowledgement boundary | derived writes conditional on `(generation, descriptor, source revision)` at the field-and-marker boundary; per-field descriptors in the RMW; accumulators bound to the same plus window geometry and discarded on transition; the two acknowledgements named apart, with D1-9's replay semantics unchanged (verified: enqueue then `clearSwitchBuffer` in `KnowledgeServerMigrationOps`) | 6.2, H1 binding rows |
| 9 | Medium | materialising reservations counted twice | one authority covering grant, materialisation, reconciliation, observation epoch and release; in-flight usage attributed to its ticket, not `external`; epoch-tagged readings; `unknown` charged conservatively; a grant-during-load trace | 5.3 admission |
| 10 | Medium | passed-rule unit ambiguous after sub-batching; aged-candidate fairness | unit fixed to one native wait; per-request figure (`n` sub-batches, at most `n` passes) reported, not promised tighter; one fairness cursor over every selection class including aged candidates; two-aged-lane and two-sub-batch traces | 5.2 |
| 11 | Medium | process hash includes mutable sets; no shared-set lifecycle | process hash covers host, protocol, models dir, device policy, flags only; sets are immutable, reference-counted, acquired and released; `reload` returns a new handle | 4.6, 7.1 |
| 12 | Medium | fences, terminal records and retained responses unbounded | fence watermark per work id (no per-UUID history); records retired by `ack` under per-client count and byte bounds with `SUBMISSION_BACKLOG` refusal; retained frames bounded and released on delivery or boot change, never extending a seat | 4.4, 4.5 |
| 13 | Medium | eviction predicate misclassifies query-serving roles | classification by serving-set obligation (the serving generation's search plan), busy/idle from D1-13 lease counts; producer tokens only for producer admission; a retrieval-coverage row covering dense, sparse and rerank legs | 5.4, 11 |
| 14 | Medium | whole-frame byte equality includes timings | exact boundary: captured payload plus fixed contract metadata; timings and transport ids excluded or fixture-supplied; per-role numeric rules declared before capture; masked workflow fields stay outside the verdict | 4.2, 11 |
| 15 | Medium | "written against section 4" not minimal | round 2's wording adopted verbatim as decision 14; D1-14's outward contract preserved and its admission implementation replaced by H1; D2 not blocked; BGE-M3 migration a named follow-up with its own gate | 6.3, 10.14 |
| 16 | Medium | bundle contained the `app-api` interface, not the implementation; missing helpers; flattened names | `context/src-tree/` preserves repo-relative paths; `context/inventory.md` lists path and line count; the 1,996-line `app-services` `RuntimeActivationService` implementation, `_paths.py`, `fixture-pair.sh`, `WindowedEmbedProgress.java` added; the flat `context/src/` removed | README, inventory |

## Round-1 ledger rows the reviewer left partial, now closed or moved

1 (write binding → finding 8, closed; shared handles → 4.6), 2 (→ 7, closed), 3 (→ 2, closed
for the unshared case), 4 (→ 1 and 9, closed), 5 (→ 5, 6, 11: 6 and 11 closed, 5 moved to H2b),
7 (→ 4 and 12: 12 closed, 4 moved to H2b), 8 (→ 3, closed), 9 (→ 9, closed), 10 (→ 13, closed),
11 (→ 4, moved to H2b), 13 (→ 15, closed), 14 (→ 14, closed).

## Cross-lane

Round 2's wording is adopted unchanged (decision 14). D1 batch 4 and D2 proceed without any
type from this lane; H1 wraps them afterwards and owns the admission replacement in D1-14.
