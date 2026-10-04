# C2-4 keyed operation-history convergence

2026-09-13, tested source based on f3c07654b. Operation history declares its existing
`operationKey` field through Resource.primaryKey. The actual EVENT_STREAM strategy
and the generic HISTORY strategy now merge snapshot/update overlap by that declared
identity. Latest values replace in place; the window remains capped at 200 rows.
Different invocation keys survive even when the operation reference is the same.
Blank declarations and absent/null/blank/non-scalar row keys append independently.
Scalar identities retain their types; numeric 1 and string "1" do not collapse.
There is no new resource field, schema version, inferred Id field or resource-id branch.

The worker implemented only the two strategy files. Root review simplified helper
signatures and required non-coercing identities; the corrected 19 focused cases and
typecheck pass. Root's catalog test proves the EVENT_STREAM construction and serialized
primaryKey opt-in. Negative1211 disables key routing in strategyFor and fails the two
intended EVENT_STREAM/HISTORY overlap assertions. Byte-identical restoration passes
all 19 focused cases in1212. Full UI1209 passes all 6,516 tests in484 files.

Java final1210 represents71 passing cases (56 execute,15 reused), with PMD/format and
UI integration compilation green. Compatibility1213 executes572 passing cases across
the full observability module and existing SSE/run writers. No failures/errors/skips
occur in those Java runs. Full Vitest fixture connection-refused/Happy DOM teardown
diagnostics remain visible; the suite exits0. No warning is suppressed.

The first ui-shot affected-file query correctly refuses an editable jseval import from
a different worktree. Repeating with this lane's scripts/jseval on PYTHONPATH succeeds
and reports no affected visual steps. This is state-reducer proof, not screenshot proof.
Canonical indexes, embedded skills and canonical links pass their checks.

Independent review finds no substantive defect, verifies the source/artifact hashes,
and traces the declared key through resource emission, HTTP catalog serialization,
the frontend catalog client and ResourceView's actual strategy selection. Live or
installed evidence of a catalog-delivered key collapsing a rendered snapshot/update
overlap remains part of the atomic-SSE campaign; source and unit proof do not claim it.

[Commands, counts, source hashes, mutation restoration and accessible raw logs](history-keyed-merge-verification.json)
are retained through lane acceptance plus30 days; export before worktree release.
Atomic snapshot attachment, safe checkpoints, incarnation tokens and live SSE restart
remain in the [owning SSE plan](C2-4-sse-plan.md). C2-4 and the lane remain open.
