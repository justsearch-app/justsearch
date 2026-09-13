# C2-6 shared settings response projection

2026-09-13. `SettingsV2Projection` owns the existing DTO mapping in app-services,
so the durable owner can construct receipts without depending on an HTTP controller.
This is a projection of UiSettings, not another settings representation or writer.
HTTP default-index injection and per-client UI-mode ordering stay in the controller.
Exclude patterns and index paths are immutable snapshots; all nullable, sentinel,
persistence-mode and LLM-path mappings are preserved. Stored index paths are already
trimmed by UiSettings; the projection adds no trimming. The Java contract assertion
now includes llamaLibPath.

Root inspected the final diff against the bounded independent design review.
Focused1368 passes 18 cases/8 suites (9 executed, 9 UP-TO-DATE), no skips/failures/errors,
plus UI integration compilation, affected PMD and format checks. Focused1365 executed
the app-services cases; UI cases were reused there. Frontend contract1366 passes 12 tests.
The initial1364 run had two erroneous new expectations about index-path whitespace;
correcting those to the existing setter contract produced1365. Negative1366 removes
only the exclude-pattern snapshot and fails exactly the intended list-alias assertion.
Restore1367 proves byte-exact restoration before the final controller comment/spacing
cleanup;1368 verifies that final cleanup. An existing LocalApiServer variable-name
compiler warning remains visible in1368; no warning was suppressed.

[Commands, final source hashes and retained output](settings-response-projection.json).
This is local helper proof. The later GET contract must project an atomic settings
snapshot with both witness fields. Production reset wiring, all writers, public wire,
Health, named A/B/C retry acceptance and installed successor-bootstrap proof remain owed.
