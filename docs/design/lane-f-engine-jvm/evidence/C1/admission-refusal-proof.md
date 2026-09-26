# C1 admission refusal proof correction

The live capture now preserves retrySafe from the REST error body or the MCP error.data
envelope. Both the aggregate and fairness analyzers count a429 only when retrySafe is exactly
boolean true. False, absent and string-valued truth are refused as proof: a later executor
refusal may share ADMISSION_ENGINE_LIMIT but cannot establish front-door admission behavior.
No server authority or refusal mapping changes in this item. Existing real HTTP tests already
pin true for pre-dispatch admission and false for handler-thrown executor refusals.

The old responseCode helper was replaced by one response projection shared by failed chat
creation and JSON request capture. Bounded response decoding is unchanged. The projection
retains the actual field value rather than coercing it to boolean; the analyzer owns validation.

## Verification

Windows11, Node24.12, Temurin25.0.2; base26e5e86d6 plus this item.

- Self-test345 passes63 cases. Positive aggregate/fairness fixtures carry the explicit field.
  Negative inputs cover false, absent and string-valued flags for each analyzer. Twelve capture
  cases use real Response body decoding through failed chat, search and MCP paths with a bounded
  self-test fetch stub restored in finally; they prove the producer carries the wire field into
  each captured row. These are deterministic capture tests, not live model proof.
- Adverse346 separately removes the aggregate check, fairness check and response projection.
  The first two cause an unsafe proof to pass; the third drops true to undefined. All three
  self-test runs fail for those intended reasons. Source restoration is byte-for-byte in finally.
  Restored347 passes63 cases. Full build348 passes in4s with test Error Prone enabled.
- Re-analysis349 of the unmodified prior standard aggregate capture fails. Its429 rows have no
  captured retrySafe field and count as zero proven admission refusals. The earlier recorded
  pass remains historical; it does not satisfy this stronger final acceptance rule. Fresh live
  aggregate and fairness captures are mandatory after the remaining C1 corrections.

Logs: `tmp/c1-admission-safe-345.txt`, `tmp/c1-admission-safe-adverse-346-aggregate.txt`,
`tmp/c1-admission-safe-adverse-346-fairness.txt`, `tmp/c1-admission-safe-adverse-346-projection.txt`,
`tmp/c1-admission-safe-restored-347.txt`, `tmp/c1-admission-safe-build-348.txt`,
`tmp/c1-admission-safe-old-capture-349.txt`.
Historical raw capture (unchanged): `tmp/c1-final-standard-aggregate/`.

Root independently reread the production REST/MCP retrySafe authorities and all three adverse
outputs. Remaining governance, cancellation/shutdown proof, Windows rename, integrated/live/hosted
verification and final C1 review are still required.

## Final production review correction

The final review at0b4b13ad0 refuted the earlier statement that MCP retrySafe was
covered by real transport assertions: its branch omitted that assertion and production
omitted the field. The strict oracle itself remains correct. See
[MCP retry safety](mcp-retry-safety.md) for the reproduced defect and correction.
