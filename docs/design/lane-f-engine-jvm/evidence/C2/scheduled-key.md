# C2-8a scheduled-run key delivery

2026-09-14, tested as a working diff over 5a4d1dde3 in lane-f-pr1-verify.
[Plan and scope](C2-8-scheduled-key-plan.md); [source hashes, commands and retained output](scheduled-key.json).

The presence producer passes the caller's UUIDv7 key into the existing scheduled-run
acceptance. Matching pending and terminal retries return the row before timer mutation;
changed prompt or normalized delay refuses OPERATION_KEY_REUSED. Concurrent callers
install one timer. A closed scheduler can still answer an already recorded outcome.
Internal null-key callers retain fresh-attempt behavior. The response reports key, row
state and replay status; invalid keys and storage refusal cannot look like scheduled work.

The frontend captures prompt, conversation and key before the request. Existing authorized
transport retries reuse those bytes. A distinct action generates another key. The settings
and background callers share the extracted UUIDv7 implementation. Refused or inconsistent
responses show a confirmation-failure toast and do not start polling for success.

## Verification

- Focused1516: 30 Java cases, five suites, no failures/errors/skips. The app-agent task
  reuses its successful execution from1515; that first build failed only because the new
  controller fixture called package-private AgentRunStore methods. The controller fixture
  now uses a mocked agent outcome with the real runner and SQLite store.
- Negative1517: both tasks executed, 17 cases, five expected failures after deliberately
  removing timer deduplication and dropping the public key. The failures assert duplicate
  timer/closed replay behavior and retained/invalid public keys. Both files were restored
  from exact backups before integrated verification.
- Integrated1518: both affected module suites executed, 1,935 cases across243 suites,
  one existing skip and no failures/errors. PMD rejected two redundant qualifiers after
  new imports. Final1519 removes those qualifiers and passes PMD, whole-tree Spotless and
  UI integration compilation. Its test tasks reuse1518's unchanged compiled behavior.
- Independent source/evidence review found no actionable defect. Root added the two
  narrow coverage gaps as one real-controller test: omitted public key is generated and
  echoed, and hostile authority fields cannot choose client, transport or survival.
  Final1520 passes 1,936 Java cases, 243 suites, one existing skip and all required checks;
  UI tests execute, app-agent reuses1518.
- Frontend focused1515: 176 cases. Full1517: 6,577 tests across489 files, all pass.
  Typecheck1516 and affected-file ESLint1518 pass. Existing happy-dom teardown AbortError
  diagnostics remain in full output; the test runner reports no failed tests.
- Canonical API request/response documentation updated; llms and skill regeneration,
  canonical-link and runtime-config-matrix verification pass without generated drift.

Logs and copied Java XML are under the workspace tmp directory named in the JSON;
retain through2026-10-14. This is local proof of key delivery and scheduling ownership.
It does not certify ingestion recovery, live model behavior, installed forced-kill
scenarios or this batch's hosted CI. Those remain in C2's owning checklist.

C2-7 hosted CI34804384959 and CLA34804383862 succeeded at5a4d1dde3.
