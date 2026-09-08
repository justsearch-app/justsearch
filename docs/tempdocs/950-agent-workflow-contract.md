---
title: "Agent workflow contract: continuity, bounded delegation, and evidenced completion"
status: implemented
created: 2026-09-08
updated: 2026-09-08
---

# Agent workflow contract

## Problem and decision

An implementation audit found checkpoint stops during authorized work, expanding
worker briefs, late scope review, and completion claims missing hosted evidence.
Technical verification was substantial; coverage of the acceptance contract was
incomplete. Private transcripts are not a dependency of this public record.

Use AGENTS.md as the complete shared policy authority and extend its existing
Claude projection. Keep tool-specific instructions outside the generated block.
This replaces the six-invariant-only projection and duplicate Claude discipline
prose. Prefer the existing generator to a new import mechanism because its CI
check can verify the exact delivered text and malformed boundaries.

Keep detailed execution and evidence procedures in canonical contributing docs;
skills route to that procedure and retain their own task-specific instructions.
Extend existing hook delivery tests to exercise the real subprocess envelope and
both harness branches. These prove repository output, not host installation or
model obedience. Document the separate live delivery probe and its limits.

Independent review exposed an adapter gap: SubagentStart matched tool names,
so selected agent types never reached the guidance handler. Use event-specific
matcher fields and test the complete adapter path with matching and nonmatching
roles. This fixes existing binding delivery without expanding the role matcher.

Do not create an evidence database or a prose-compliance classifier. The existing
task checklist and managed PR review record own requirements and evidence. A
review must reconcile them, including environment and tested revision. Hashes
identify artifacts but do not make unavailable evidence inspectable.

## Acceptance

- [x] Full shared policy projects into Claude; drift and malformed markers fail.
- [x] Checkpoint continuity, authorization persistence, bounded delegation,
      scope reassessment, migration applicability, and evidence reconciliation
      are explicit and consistent across the two harnesses.
- [x] Skills distinguish checkpoints from closeout and preserve active work
      during status questions; documentation has one current decision owner.
- [x] Obsolete hook/inheritance claims are corrected in active guidance and code.
- [x] Hook subprocess tests cover Claude/Codex delivery without claiming a live
      client probe; fresh Codex roles read the governing contract explicitly.
- [x] Canonical docs, generated outputs, budgets, and relevant Node checks pass.
- [x] Independent review resolves substantive findings; publication evidence
      distinguishes local, hosted, queue, and post-merge results.

## Verification evidence

| Requirement | Environment / revision | Result and evidence | Remaining limit |
| --- | --- | --- | --- |
| Projection and delivery | Windows `3739454ef2d5`; final evidence-only doc delta | `agent-instructions-projection.test.mjs`, `subagent-guide.test.mjs` (9 checks), `check-codex-agent-parity.mjs` (8 checks), analytics suite (55 test files): pass | Repository output tested; no live client injection or obedience claim |
| Documentation and script checks | Same local candidate | `lint:scripts`, `docs-validate`, `verify-canonical-doc-links`, canonical Markdown lint, prompt budgets, premerge-table, tempdoc-number, publication classification, PR-record and squash-message tests: pass. All 8 generated sets pass, including notices with the installed stable Rust toolchain | Hosted final-head checks recorded in PR |
| Integrated application checks | Same candidate; Windows local environment | `gradlew.bat build -x test` and `gradlew.bat test`: both successful | Local logs under ignored `tmp/agent-build.txt` and `tmp/agent-tests.txt`; workstation-only access/retention |
| Independent review | `0e16c0c5b` plus correction delta committed as `3739454ef2d5` | Two findings corrected: adapter selection bypassed by leaf tests; stale nested-hook denial. Adapter regression failed before the fix and all 17 adapter checks pass afterward. Follow-up reviewer independently checked routing and found no unresolved substantive issue | Reviewer inspected execution paths and ran a focused behavior probe; did not independently rerun full suites |
| Publication | Hosted candidate and landed revision | Managed review record on [PR 719](https://github.com/justsearch-app/justsearch/pull/719) owns current hosted, queue, and post-merge results | Pending at this commit-time snapshot; no premature publication claim |

## Next action

Implementation and independent review are complete. Finish publication checks
and update the managed PR record through queue and exact landed-revision CI;
that live record owns the publication outcome after this dated snapshot.
Keep unrelated main-checkout changes and active migration worktrees untouched.

## Verification qualifications

One overlapping analytics run failed while publication preflight reinstalled
Node dependencies: both errors were missing gray-matter files during replacement.
The subsequent preflight analytics run passed all 55 files. Keep dependency
installation and dependent tests sequential. The preflight later stopped at
Cargo metadata because no default toolchain was selected; the installed stable
toolchain was selected for the resumed command only. Neither failure justified
changing a test, removing a check, or changing the machine-wide default.
