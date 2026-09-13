# C2-3b: operations.db v3 prepared storage prerequisite

2026-09-13, based ondf4bedf07. The store portion is implemented and focused-proved;
runner key serialization, dispatcher/cipher wiring and approval nonce delivery are
next. Non-null replay preparation remains refused by the production dispatcher.

V3 adds bounded paired payload columns to the accepted row and one pending table in
the same operations.db. A pending preparation is not an accepted history entry. The
first unexpired key/public identity owns its nonce and payload; retries cannot update
the payload or refresh its five-minute TTL. A512-row cap evicts oldest pending entries.
A nonce must still identify that exact pending preparation to accept it. Missing,
expired or replaced preparations refuse with OPERATION_PREPARATION_UNAVAILABLE,
including raw acceptance that would bypass a live pending value. Accepted matching
rows still answer without another effect. Copying payload and deleting pending state
share the existing acceptance transaction; the payload is retained/pruned with its row.

V1 rebuilds into v2 before applying the append-only v3 migration. A frozen v2 SQL
fixture records df4bedf07's DDL. Existing row IDs, deleted-ID sequence high water and
history fence survive. Failed migration restores the old version, row and schema.
Future-version tests now use4 and still require byte-identical main/WAL/SHM refusal.
The recoverability register declares v3, readable1/2 and the frozen SQL fixtures.

## Verification

- Negative951 removes the acceptance transaction. Two represented cases execute,
  with one intended failure: after a delete trigger refuses transfer, the row remains
  accepted. With the transaction restored, acceptance rolls back and pending survives.
- Positive952 passes302 represented cases with all three test tasks executed.
- Negative953 removes only the nonce comparison. Two represented cases execute,
  with one intended failure: an old nonce accepts a replacement after the original
  preparation expires. Test preparations have distinct target bytes, not only nonces.
- Positive956 passes363 represented cases. Final958, after capturing one timestamp
  for created/expiry, passes363 cases across67 suites, zero failures/errors/skips:
  observability63, launcher35 and UI61 execute; app-api204 reuse952. Affected PMD and
  UI integration-test compilation pass. UI selection includes the new typed MCP
  preparation refusal as well as HTTP and approval regressions.
- Register954 self-test passes75 assertions; gate955 reports6 catalog stores and45
  durable authorities,27 corruption policies and zero unregistered authorities.
- Independent read-only review found no remaining store defect. A reported empty-v1
  sequence defect was refuted against the exact primitive and captured regression:
  zero-row INSERT SELECT creates a sequence row0, then the existing migration raises
  it to900 and the next ID is901. Reviewer withdrew the finding. Root evidence is
  `tmp/c2-3-preparation-sequence-refutation957.json`; Python SQLite3.50.4 agrees with
  the actually executed JDBC regression. No unnecessary sequence mechanism was added.

[prepared-store-verification.json](prepared-store-verification.json) records exact
command/counts/source hashes. Raw logs/counts/XML under lane `tmp`:
`c2-3-preparation-store-negative{951,953}{.txt,-counts.json,-xml/}`,
`c2-3-preparation-store{952,956,958}{.txt,-counts.json,-xml/}` and
`c2-3-preparation-store-register{954,955}.txt`. The capture labels a task emitted on
one line as FAILED when appropriate; this means executed-and-failed, not reused.
Retain until lane acceptance plus30days and export before worktree release.

This is actual SQLite reopen/transaction/migration proof. It is not application
restart with frozen approval, live model/API behavior, hosted acceptance, or power-loss
durability. The final C2-3 and batch2 gates remain required. The v3 register change
also requires fresh hosted/platform proof at the integrated boundary; b713's successful
hosted v2 run is historical and does not certify v3.
