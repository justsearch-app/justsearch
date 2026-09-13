# C2-3 HTTP keys and approval identity

POST /api/operations/{id}/invoke now consumes the existing idempotencyKey field.
POST /api/undo/{id} reads the same optional field and carries it to keyed undo.
The latter is the registered route; the draft's operations/{id}/undo spelling is
corrected in C2 section0.1, without adding an alias. Calls without a supplied key
retain the legacy SPI overload and receive the executor's minted response key.

Key errors keep their public class/code: invalid400 BAD_REQUEST/OPERATION_KEY_INVALID;
reused409 CONFLICT/OPERATION_KEY_REUSED; expired409 CONFLICT/OPERATION_KEY_EXPIRED.
Capacity is503 UNAVAILABLE/OPERATIONS_CAPACITY and retryable; storage failure is500
HANDLER_ERROR/OPERATION_STORAGE_FAILED and does not expose its native cause. The
existing OperationInvocationResponse owns this shared wire projection.

Approval must preserve the same identity. The existing in-memory pending record
now carries the key and an explicit undo flag. Its current lifetime/cap, provenance,
consent-capsule and admission rules remain. Server-side approval dispatches the
stored mode/key, never substitutes a key from the approving request and never
interprets a reversal as a forward call. No durable prepared-payload store is added
here; that remains C2-3b/c. Existing pending callers default to unkeyed invoke.

Negative914 executes4 cases and fails both forwarding witnesses against the original
controller. Preliminary915 passes232 cases before approval work. Negative916 is a
test-compilation failure (missing clock argument), not behavioral proof. Corrected
negative917 restores only the former raw-JSON approval dispatch:4 cases execute and
both approval-identity witnesses fail after the pending key/mode assertions pass.
The correct consumer is restored before final918.

Intermediate918 passes264 represented cases/50 suites, zero failures/errors/skips. Services
executes12 cases and UI48; app-api reuses204 unchanged passing915 cases. Affected
services/UI PMD and UI integration-test compilation execute successfully; app-api
PMD reuses unchanged input. Exact command/results: http-key-verification.json. The controller's stale confirmation javadoc is also corrected.
Canonical API reference, index generation/check, skill synchronization check and
canonical-link check pass919–922 (156 documents; no embedded skill section affected).

Final923 also projects operationKey/operationRecordId from server-side approved
results and carries typed executeErrorClass/executeErrorCode/executeRetryable on
approved-dispatch storage/key failure. It exposes neither private handler data nor
native causes. All265 represented cases/50 suites pass, zero failures/errors/skips;
UI49 executes, services12 and app-api204 reuse unchanged successful inputs. UI PMD
and integration-test compilation pass. No required failed check is waived.

Raw: `tmp/c2-3-http-negative{914,916,917}.txt`,
`tmp/c2-3-http-negative{914,917}-xml/`, `tmp/c2-3-http{915,918,923}.txt`,
`tmp/c2-3-http{915,918,923}-xml/`, `tmp/c2-3-http{915,918,923}-counts.json`,
`tmp/c2-3-http-docs{919,920}.txt`, `tmp/c2-3-http-skills921.txt` and
`tmp/c2-3-http-links922.txt`. Retain through acceptance plus30 days; export before
worktree release. Live API/model and hosted acceptance remain part of the coherent
C2-3/batch2 boundary, not claimed by these mocked-controller tests.

Next: MCP key delivery, then frozen prepared persistence and approval payloads.
Broader outcome-query integration belongs to C2-4. No prepared producer is activated yet.
