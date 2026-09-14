# C2-6 public settings producer — 2026-09-14

SettingsService now normalizes partial input with existing setters, looks up the
key before reading settings or validating paths, accepts through the existing
runner, and commits against the client's original full witness. Matching retries
return the original receipt; different canonical input under the same key refuses.
Empty/null nested sections normalize consistently. A transactional existing
acceptance never repeats mode bookkeeping or chat reconciliation.

The controller owns only transport, attribution and SettingsV2 projection. Its
raw save/config rebuild are retired. The real bootstrap injects the existing
service; missing composition permits reads and refuses writes. Error status,
classification and retryability remain typed. Accepted failures retain key/id;
unacknowledged storage transitions do not project terminal state or witness.

A nonblocking admission bit replaces the old store monitor. It retains the
existing64-entry mode LRU through fresh preparation, synchronous runner return and
COMPLETE-only bookkeeping, then releases before chat nudge. GET and known replay
remain available. Internal writes still compare full witnesses through the physical
owner. No new durable counter, queue or writer is introduced.

Race proof includes postcommit/pre-LRU admission, original replay during that gap,
postcommit ConfigStore listener failure, an intervening internal commit, and the
lookup-to-accept race. Real loopback HTTP proves CORS, witnessed UUIDv7 input,
strict first/replay DTO parsing, invalid/missing-key codes, failed-row identity and
restart readback. Real SQLite triggers refuse RUNNING and COMPLETE transitions;
HTTP retains the accepted identity without claiming an outcome, and the test
checks the actual retained row, settings witness and required restart.

Final1452 represents4,859 cases/743 suites,
4 existing skips, 0 failures/0 errors.
Applicable PMD/Spotless and UI integration compilation pass. The
[manifest](public-settings-producer.json) identifies exact sources, command,
executed/reused tasks and accessible artifacts.

Adverse evidence is retained:1441 exposed malformed worker test payloads and
expectations (double-ui wrapper, invalid mode name, wrong prior key and misplaced
spy assertion), corrected by root;1442 failed only a trailing blank line;1444
failed the old uncomposed/unkeyed HTTP fixture. Review found extra operation fields
on the strict wire, null/omitted identity drift, duplicate bookkeeping on an
acceptance race, broad error classification and missing accepted-storage identity.
1450's real SQL-failure regressions caught identity stored in errorDetails instead
of structuredData;1451 passes after using the actual result fields. No detector or
expected product behavior was weakened.

Frontend attempts/callers are next. This checkpoint is not shippable until those
consumers, all-writer retirement and required integrated/live/model/installed/
final-head hosted proofs are complete. C2 and D1-F remain open.
