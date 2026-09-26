# C2-6 fixed reset owner and durable recovery clearing

2026-09-13, a4afee776 plus the nine Java files in
[the verification manifest](settings-reset-owner-verification.json). This implements the
fixed reset mechanism in C2-6-plan; production composition and public producers remain open.

OperationAttemptRunner.applySettingsReset requires this runner's live RUNNING settings body
capability. It loads the actual accepted preparation, lets the fixed owner validate and reserve
it, and arms the owner's expected numeric marker before building the reset candidate. The
ordinary typed settings path also checks that the owner marker agrees with its captured base.
The existing receipt, one-shot, fatal-error and terminal-store protocol remains shared.

The owner validates the entire normal witness pair or the frozen quarantine fingerprint.
Recovery bypasses only lost readable history with no armed dependency. It retains its block
through reservation and precommit failure, yet releases a durably failed attempt's fence so a
new confirmed reset can retry. Candidate preparation occurs only after arming; normal reset
uses a fresh matching snapshot and the original user-controlled reset subset. That setter list
now has one settings-owned helper used by the transitional controller too. Recovery starts from
canonical defaults because its previous history is unavailable.

After replacement, an exact next file witness proves commit. Only unchanged absent-file
quarantine proves recovery precommit; a new zero/null file cannot stand in for an absent prior.
The receipt is captured before configuration/notification failures. SQL COMPLETE precedes
recovery clearing and the one recovery restart request. Notification and restart callbacks run
outside the physical mutex; a clear RuntimeException is diagnostic, a clear Error still requests
restart, and a second restart failure is suppressed on the primary Error. Terminal SQL failure
keeps the condition and fence. Ordinary resets and already-committed boot recovery do not
request another recovery restart. Boot exact commitment wins before decoding even bad metadata;
otherwise recovery precommit needs the unchanged fingerprint and normal reset needs its full pair.

## Review and verification

Independent source review is clear against1334's nine source hashes. The reviewer read the
protocol, implementation and fault tests without running a separate build.

- Initial1329 exposed the canonical identity defect, fixed separately in a4afee776;1330 executed
  192 cases/26 suites green across app-observability, app-services and UI.
- Expanded1333 runs196 cases/26 suites with one fixture failure: its missing-preparation setup
  violated SQLite's all-three-fields-null CHECK. The fixture now clears nonce/sealed/payload
  together, preserving the schema and its intended missing-preparation assertion.
- Positive1334 passes196 cases/26 suites, zero failures/errors/skips:155 app-services cases
  execute,32 app-observability and9 UI cases are unchanged-input UP-TO-DATE reuse from1330.
  Main/test PMD and format pass for app-services; other changed modules' checks passed1332
  before any further changes to those sources. Register1334 passes all three gates, zero findings.
- Negative1335 disables four protections: retaining recovery precommit fences, clearing before
  SQL completion, accepting a synthetic zero prior, and numeric-only normal-reset boot evidence.
  Thirteen cases/5 suites run; exactly five intended assertions fail. Both precommit retries
  become FAILED instead of COMPLETE; synthetic-zero and changed-pair cases become FAILED
  instead of RUNNING; terminal SQL failure incorrectly loses the recovery condition. The normal
  reset control and changed-quarantine control pass. Zero errors/skips.
- Restored1336 passes196 cases/26 suites, all reused. All nine hashes match1334 byte-for-byte;
  PMD/format pass. This is restored-input proof reuse, not a fresh full-suite execution.

Exact commands are in counts JSON, and log/XML/zips/source manifests are retained in this lane
worktree's tmp directory through acceptance plus30 days and exported before worktree release.
No full/live/model/installed/hosted reset completion is claimed. Next: compose the owner using
the existing process restart action, migrate reset/all settings writers and public witness/key
flows, wire Health, then prove actual successor bootstrap and previously refused startup writers.

## Hosted main-policy overlap

Origin main advanced to b4d972b6b (#726). CI34779298814 at001f7e20f passed12 jobs and failed the
new introduced-commit identity guard in Secret scan. The current effective identity and latest
commits are approved;365 older commits in the379-commit lane range predate the guard. No private
identity values are copied here. Root is preparing a compliant PR1 publication lineage without
force-pushing or changing the guard; main integration and that candidate's full checks remain next.
The green ae46f6919 run predates the new policy in the hosted candidate and cannot waive it.
