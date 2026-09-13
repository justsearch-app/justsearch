---
title: Exposure prevention and historical incident closure
status: in-progress
---

# 956 — Exposure prevention and historical incident closure

## Scope and authorization

2026-09-13: investigate and remediate the reported signing-log, email metadata,
historical scan, and secret-scanner coverage issues. Local implementation,
verification, and account-side investigation are authorized. No publication,
push, PR, merge, or history rewrite. Keep sensitive evidence outside Git and
never reproduce real credential values or personal email addresses.

Worktree: `the dedicated source worktree`, branch
`codex/956-exposure-hardening`, base `3a3e8e4896edef04f0a160bcf646d24e336a5b96`.

## Investigation and risk assessment

GO. LITE-CLASS: no. The need is demonstrated: an isolated invocation of the
existing native-command wrapper preserved a fake credential marker in its log.
The historical PR records the original exposure; original run logs currently
return 404. Neither this response nor repository secret update timestamps prove
provider revocation. Existing attribution is not evidence of compromise.

Confidence: 8/10 for local prevention, unknown for provider-side closure until
account evidence is available. Balanced/strongest-capability implementation with
high reasoning is appropriate for output boundaries and scanner exceptions.

## Design

1. Native signer output is untrusted credential-bearing data. Do not persist or
   return it. Keep exit codes, operation context, and exception type/line only.
   Retire raw native output and exception-text diagnostics in the signing script.
   This is simpler and more reliable than parsing arbitrary vendor templates or
   building a second secret registry. Preserve signing verification and budgets.
2. Narrow secret-scanner exceptions to individually justified matches. Scan
   previously excluded first-party code, fixtures, datasets and reports. Add
   regressions proving those paths are covered. Run a separate redacted history
   audit and explicitly distinguish tree, history, and metadata scope.
3. Use the authenticated owner's GitHub no-reply identity in repository-local
   Git configuration. Extend the existing repository history publication policy
   with the owner's aliases and approved public no-reply address. Local hooks
   check effective identities and outgoing commits; preflight and CI check the
   introduced commit range. This is a privacy choice, not an authentication
   registry. Unrelated contributor identities and old public commits are outside
   that personal policy; no history rewriting is involved.
4. Verify what historical evidence is accessible. Remove confirmed exposed
   records only within authorized account access. Provider revocation, a working
   contact alias, and inaccessible historical scans remain explicit external
   dependencies, never inferred successes. Public attribution changes need a
   real replacement identity/certificate, not a misleading name edit.

Reach: credential-bearing subprocess output should be excluded at its owner,
before shared diagnostics. The present scope is signing; no generic logging
framework or new lifecycle mechanism is warranted. Evidence of value is a
fake-secret failure regression. Retire the special handling only if credentials
no longer reach the subprocess and its output contract is demonstrably safe.

## Acceptance and implementation plan

- [x] Signing stdout/stderr/launch failures/traps cannot disclose fake credential
  markers; command failure retains exit status and budget behavior. Run Windows
  PowerShell regression and existing signing rehearsal; prove negative control.
- [x] Broad first-party Gitleaks path exclusions replaced by reviewed narrow
  exceptions; tree scan and detector-positive fixtures pass with pinned scanner.
- [x] Redacted reachable-history audit completed; findings triaged with no claim
  that a clean tree scan clears old objects or metadata.
- [x] Local no-reply identity verified; metadata prevention integrated and tested
  at the existing publication boundary, including a negative control.
- [x] Canonical maintenance instructions describe changed behavior and limits;
  required documentation regeneration and governance checks pass.
- [x] Independent refute-first review reconciles acceptance with actual evidence.
- [x] Owned changes committed locally, with no publication.

## External closure items

- Provider credential rotation: owner confirmed on 2026-09-13 that the exposed
  components were rotated. Recorded as owner confirmation, not an independent
  provider audit. GitHub secret update time alone is not proof. Provider audit
  review for misuse remains unverified.
- Historical exposed logs/artifacts and build scans: incident run has zero
  retained artifacts; original log endpoint returns 404, not universal deletion
  proof. CI run 31755561331 yielded seven scan URLs; browser inspection could not
  attach and web access could not open the sampled scan. Provider scan cleanup
  remains unverified. Raw downloaded CI logs were deleted locally after URL
  extraction; this does not delete the original GitHub logs.
- Existing privacy contact: owner supplied `justsearch457@gmail.com` and
  authorized the replacement. A local releases-repository branch prepares the
  single contact-line edit; publication remains excluded. Delivery was not tested
  by sending mail, and no mailbox credentials were requested.
- Historical email metadata and deliberate publisher attribution: cannot promise
  global erasure; no history rewrite or certificate identity change authorized.
- GitHub web-email privacy: current browser requires sign-in; API lacks the user
  scope. No credential/scope change attempted. This remains an account-side
  follow-up before publication, distinct from the completed local Git setting.

2026-09-13 follow-up decision: keep automatic build-scan publication disabled.
The owner chose to skip scans; historical scan inspection/cleanup is deferred,
with the seven recovered links retained in the external evidence directory.
This accepted deferral does not establish that old scans were deleted or clean,
and does not block further local development.

## Evidence and current state

Investigation report (redacted, outside Git):
`redacted-exposure-investigation.md` in the task-local evidence directory (local-only access).
Evidence directory: same external directory as that report. Retain redacted
reports and test logs for 30 days or through the next explicit handoff; raw
downloaded CI logs were removed. Do not commit scanner JSON: even --redact leaves
commit email metadata, which was separately scrubbed from retained reports.

- `signing-diagnostics.txt`: Windows PowerShell 5.1 certificate-free regression,
  all nine assertions PASS against the modified script.
- `signing-negative-control.txt`: same regression against base script, expected
  failure on stdout/stderr/failure-log credential disclosure.
- `signing-rehearsal.txt`: existing Windows signing rehearsal, all 12 cases PASS,
  including actual throwaway-certificate signing, timestamp, shim cleanup, and
  failed-attempt/verified-ledger behavior.
- `signing-resolver.txt`: existing resolver test PASS.
- Unfiltered Gitleaks 8.30.1 tree/history reports: 82 current matches, 1,227 across
  public-main history (676 commits). Candidate matches were classified as
  hashes, encoded corpus collision digests, cursors and fixtures; no new live
  credential was established. The former configured clean baseline did not
  prove coverage. The hardened unbaselined history scan reports 1,030 matches:
  990 encoded revision matches, 39 paging cursors, and one fixed cohort digest.
  These are 304 unique immutable commit/path/rule/line locations recorded in
  `scripts/ci/gitleaks-history.ignore`, used only for the full-history local
  preflight. This retains the old history check without future path exclusions.
- A separate unfiltered scan of the 553 additional commits reachable from the
  locally recorded origin/tag refs but not origin/main produced zero matches
  (`gitleaks-other-recorded-public-refs.json`). This describes the recorded ref
  set, not every inaccessible or deleted remote copy.
- Repository-local no-reply configuration verified through `git var` for author
  and committer across all 29 registered worktrees, with zero overrides found.
- Documentation index, skill-sync, canonical links, schema validation, module
  graph, runtime configuration matrix, Markdown lint and PowerShell warning
  checks passed using Node 24.19.0. Initial fresh-worktree missing dependencies
  were resolved by locked npm installation; default Node 24.12 emitted an engine
  advisory, so subsequent Node checks use the available compatible 24.19 runtime.

Local implementation verified against the stated base plus the owned diff.
No hosted proof claimed; changes remain unpublished by explicit user instruction.
The signing test evidence above applies unchanged; final commit identity and
history checks are recorded below after the local commit.

## Independent review and corrections

The independently briefed read-only explorer reproduced the nine signing
diagnostic assertions and re-read the existing rehearsal/negative-control
evidence. No substantive signing defect found; production provider signing and
hosted CI remain unperformed. A separate reviewer spawn was unavailable because
the task's agent-thread limit was reached, so the existing independent reader
was reused rather than claiming a reviewer-role run.

Parent refutation found that the first metadata guard scanned all old history
when pushing a new branch. Correction requires excluding the destination's
already-published history while still checking genuinely outgoing commits, plus
real hook/stdin replay tests. Scanner review requires detector positives under
the same exempted rule on the same line; a different rule firing does not prove
that an exception is sufficiently narrow.

The final independent read-only review found no concrete metadata/scanner bypass.
It classified the remaining fixed cohort digest at historical
`scripts/jseval/_leak_free_judged_recompose.py:58` by equality with calibration
digests; the UI report token at historical `reports/phase13/ui/translator-degrade.json:77`
is PIT cursor evidence. Current exceptions trust the named generated digest
fields as non-secret schema fields; this is accidental-exposure detection, not
protection against deliberately disguising secrets as public hashes.

The parent retained Gitleaks's existing fingerprint mechanism instead of adding
a baseline schema or allowing arbitrary encoded blobs/paging tokens. The history
exception list contains no secret values or personal email metadata. It is
explicitly passed only to the full-history preflight, not staged or tree scans.
The runnable regression proves an old fingerprint is suppressed and a new
synthetic secret in the same path/rule/line still fails.

## Final local verification and delivery

- `gitleaks-regressions.txt`: PASS with Gitleaks 8.30.1, including same-rule,
  same-line detector positives, Java/TS/TSX tests, reports, datasets, jseval,
  and exact historical-fingerprint boundary controls.
- `gitleaks-hardened-tree.json`: zero findings in the final candidate tree.
- `gitleaks-hardened-history.json`: the 1,030 reviewed matches with location
  metadata only; report contents were parsed and reduced to safe fields.
- `gitleaks-history-reviewed.json` and `.log`: zero unreviewed findings across
  all 676 reachable base commits using the 304 exact historical fingerprints.
- `final-tooling-checks.txt`: metadata/hook regressions, effective identity,
  workflow trigger and npm-audit policy, repository history policy, preflight
  inventory tests/check, PowerShell comments, docs generation/checks, links,
  schema validation, Markdown lint, ESLint, and diff whitespace all PASS.
- Our worktree activates its checked-out `.githooks` using worktree-local
  `core.hooksPath`; the main checkout's hook selection was preserved.
- No runtime code changed; Gradle, live model queries, or dev-stack leases are
  outside these tooling-only acceptance checks. Hosted runner and production
  provider signing remain unverified; publication is expressly excluded.

Publication dependency: user explicitly excluded publish. Retain this worktree
and local commit for review. Account-side follow-ups are isolated in External
closure items; they do not negate the completed local protections.

Final tempdoc-fit review: local implementation matches the design and acceptance;
external erasure/account operations remain unresolved as stated, not substituted
with local source edits. Capability-realization review traced signing regression
and rehearsal to the CLI/CI invocation, metadata guard to real Git hooks and
preflight, scanner tests to actual pinned Gitleaks, and fresh-agent discovery to
`docs/llms.txt` and the canonical activation instructions. Local paths are proven;
hosted checks remain unverified until publication is separately authorized.

Implementation commit: `16af9cb330f6ead264134f37cfe834ea8a751eaa`. Real pre-commit
hook accepted the approved identity and scanned 119,911 staged bytes with zero
findings (`local-commit.txt`). The committed `origin/main..HEAD` identity guard
passed, and the same introduced commit range had zero Gitleaks findings without
historical exceptions (`gitleaks-introduced-commits.json`). The parent history
result remains applicable because those old commit objects are unchanged.

Closeout world-state lists this clean branch one commit ahead at that observation
(`closeout-world-state.txt`). Main remains on `main`; unrelated files preserved.
Worktree lifecycle registration/hold records publication exclusion, session owner,
and review date 2026-10-13. No worktree removal or push was performed.
The session-closeout spawn sweep reaped nothing: 11 other-session ui-shot records
were left as contention and one ownerless OTLP singleton was reported. This
session started no dev stack or registered helper. The final documentation-only
closeout commit follows the implementation commit and is checked by the hooks.

## Contact follow-up delivery

The separate release-documentation repository is prepared in the isolated clone
`the isolated release-documentation clone`, branch
`codex/956-privacy-contact`, based on remote main
`40bd50e9d11595435f86728fc3bd280f78d61590`.
Commit `c7a2c06a994a84466218ab69d322b5f2a86fde99` changes only the
`PRIVACY.md` Questions contact to the owner-supplied project Gmail address.
An exact before/after comparison with the contact line normalized proved all
other bytes unchanged; `git diff --check` passed. Its repository-local identity
uses the same approved no-reply email. No tests are needed for this one-line
documentation replacement; mailbox delivery and hosted publication are untested.
The clone and source worktree remain local and must be preserved until a separate
publication decision. No PR, push, release, or support message was sent.

## Publication authorization

2026-09-13: the owner explicitly authorized publishing the completed changes.
This supersedes the earlier publication hold for the source hardening and the
release privacy-contact edit. Branch pushes, pull requests, ordinary merges and
post-merge checks are in scope; no binary release or history rewrite is requested.
Historical scan cleanup remains deferred, and GitHub web-email privacy remains
an account-side follow-up. Local evidence files are not public CI artifacts.

Publication preflight on `2c515e68246671719856dd2c23541deaedb7775e` passed:
61/61 agent-tooling files, governance/public-claim checks, license/notices,
assembly, PMD, all three unit-test groups, metadata/scanner regressions, and
the reachable-history scan. Python: 3,637 passed, 16 skipped, 81 warnings;
`build -x test` also passed. Logs: `publish-preflight.txt`,
`publish-preflight-resumed.txt`, and `publish-build.txt` (task-local evidence).
Cargo initially failed because the shell had no default toolchain; selecting
the already installed stable toolchain for the process resolved it. Existing
Node engine and Gradle deprecation advisories remain disclosed, not hidden.
The local checkpoints are consolidated before first push to avoid publishing
superseded machine-local notes; implementation content is unchanged.

Release contact PR https://github.com/eliasjustus/justsearch-releases/pull/2
merged as `58ad2e074b2ab2c4ea3e9c31e4511342eb436a13`. Hosted PRIVACY.md
matches the candidate and uses the project mailbox. That repository has no
CI workflows or protected-branch checks. The source PR's hosted verification
and merge-queue result remain pending.
