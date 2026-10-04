# C2-6 runtime-intent producer checkpoint

2026-09-14, base `0ad7e19140ebb57749c2b87046346d69acc27964`, Windows/PowerShell,
worktree `F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify`.
[Exact commands, source hashes, counts and accessible artifacts](runtime-intent-producer.json).

RuntimeSpecStore now submits its captured candidate/full witness to the existing
accepted settings owner. Both operation handlers use frozen server preparation and
their existing dispatcher record. Direct mode requests retain context/key, return
the issued receipt even on accepted failure, and never prepare, nudge or resample
on replay. An incomplete row answers `accepted` without waiting. Boot seed compares
the same witness used for its no-op decision; typed refusal leaves bootstrap alive.
Activation receives the same composed intent writer. The REST response preserves
its error contract and existing public operation-key tokens.

Independent refute-first review is clear after wire/accepted-key corrections.
The root reproduced both findings before fixing them:1401 has two intended HTTP
shape/status failures;1402 catches the missing server-issued key.1403 catches the
public-token mismatch; the restored final run includes that correction.

Final1405 represents4,235 cases/650 suites, zero failures/errors and four existing
skips: app-services executes2,817 cases, UI1,214, app-api reuses204 cases from1400
without an intervening app-api change. PMD main/test, all three affected modules'
format checks and UI integration-test compilation pass. Pre-correction full1400
executes4,231 represented cases. Focused1398 passes88 cases;1396's fixture close
compile error and1397's handler-vs-catalog assertion failure are retained and fixed.
Existing compiler/dependency warnings remain visible in the logs; no validation
or warning policy was weakened. Documentation regeneration and link checks pass.

Negative1404 deliberately refreshes the witness after a captured boot-seed decision.
A single interleaving user choice then gets overwritten, so the expected refusal
does not occur. Source restoration is byte-exact. Negative1399 is retained but
rejected as weaker evidence: repeated mocked reads injected extra writes and made
the test fail for the wrong reason.1404 is the accepted negative proof.

This is an intermediate, non-shippable per-item checkpoint. Activation/deactivation
and rollback still call legacy raw save, which refuses after a recorded revision.
Their immediately following whole-document/compensation cut is required before
shipment/F; this is not a deferral or C2-6 completion. Installer/import, public
settings witness/key consumers, retirement, live API/model, installed successor and
final-head hosted proof remain required. Hosted success at0ad7e1914 covers only the
prerequisite. Raw artifacts stay accessible through lane acceptance plus30 days and
must be exported before worktree release.
