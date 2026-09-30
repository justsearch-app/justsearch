# C2-4 R6: undo outcome wire parity

Grounded at13b6bd680. The retained operation-history proto omitted UNDONE, which
OperationExecutorImpl already emits after undo. Add enum value3 and preserve
all existing field/enum numbers. VERSION moves2.0.0 to2.1.0 under the explicit
enum-value-added changeset. This does not change runtime operation states.

The existing field-name conformance test could not detect the missing enum value.
Its new companion compares all Java OperationOutcome values with the generated
proto descriptor. Negative1061 fails only on OPERATION_OUTCOME_UNDONE among8
represented cases; the field parity case still passes. Final1062 executes8 cases
in5 suites with zero failures/errors/skips, PMD and integration compilation pass,
and syncSsotSchemas is current. Focused schema1063 executes2 passing cases,
including OperationHistorySchemaTest. No schema payload field changed.

Commands and source/raw-artifact SHA-256 inventories are in
history-wire-verification.json. Raw evidence: tmp/c2-4-wire-negative1061.txt,
tmp/c2-4-wire-negative1061-xml/, tmp/c2-4-wire1062.txt, tmp/c2-4-wire1062-xml/,
tmp/c2-4-history-schema1063.txt, tmp/c2-4-history-schema1063-xml/.
Retain through lane acceptance plus30 days and export before worktree release.

The wire gate passed after this commit, as recorded below; this cut does not close C2-4. The durable
store swap, six-state HTTP/MCP query, completion projection/replay, SSE boundary
and registered history lineage remain in C2-4-plan.md. No hosted proof is inferred.

Post-commit wire gate1067 passes at eac25abb3:1 gate,0 failures/findings, buf1.69.0.
Evidence: tmp/c2-4-wire-gate1067.txt and tmp/c2-4-wire-gate1067.sarif. The wire
correction is locally proved; the remaining C2-4 cuts and hosted proof stay open.
