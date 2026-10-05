---
title: "966 — Test reset: every expected outcome needs a source of intent"
status: design (draft 9, after research U1–U7, experiments E1–E6, challenger round 4, design review 3, verification round 1 and agent trials; drafts 1–4 superseded, review trail in the private task record)
created: 2026-10-05
updated: 2026-10-05
---

# 966 — Test reset: every expected outcome needs a source of intent

## Problem

The owner wants tests that can be trusted and that do not protect slop (2026-10-05). The owner
chose to keep the existing suite (the "legacy approach") over deleting it. The owner does not
read code and runs several agents in parallel, so nothing here may depend on the owner reading
PRs or noticing a violation.

Drafts 1–4 assumed the main hazard was a cleanup veto: tests that fail on behaviour-preserving
changes, which agents then rewrite to match. Seven research passes and six experiments (reports
kept in the private task record; findings and their sources summarised here) found a different
picture:

- **Tests that assert defects are the measured hazard.** In 42 classified expectation edits over
  the 3.5 months of public history, 7 were tests that had asserted a bug as correct and were
  flipped when it was fixed; 4 more are documented in tempdocs (819, 798, 804, a scan baseline).
  In 804 "every existing reconciler/spec test pins READ_WRITE, which is exactly why the suite
  stayed green while the shipped app wedged" (`804-…md:271`).
- **The risk sits in tests written alongside the code.** Across 1,349 agent sessions, none of 23
  sampled edits to tests already on main was a dangerous rewrite; the risky in-session edits all
  hit tests written in the same branch, and one (an expectation dropped to pass, unattended)
  reached main. A PR-level view sees such tests only as "new".
- **"Intended" mostly means agent-intended.** Of 26 historical expectation changes judged
  intended, 4 trace to explicit owner words, 6 to a broader owner goal, 16 to agents (6 bug fixes,
  10 choices, many under written owner delegation). Four agent documents label delegated or
  untraceable choices "owner decision".
- **Written intent is mostly agent-written with the code.** For app-inference, 14 of 19 commits to
  its runtime register also change code. In a blind replay of 8 historical defect pins, writers
  without the code avoided the defect in 4–5 of 8; every miss followed an agent-written source of
  the day that stated the defect as intended.
- **Tests keep dead code alive:** about 400 production methods (range 280–600) and 21 classes are
  reachable only from tests; about 40% are unwired features. The method-level dead-code check
  exempts test-only methods (48 of its 59 listed exemptions cite a test).
- **Expectations can already leave CI without touching an assertion:** reusing an existing
  excluded tag (`JvmBaseConventionsPlugin.kt:100-111`), conditional disables, assumptions, and
  hand-raised ratchet baselines; the suppression ratchet states such weakenings are "not reliably
  detectable" (`check-suppression-ratchet.mjs:15-16`).
- **The cleanup veto is real but cheap so far:** 9 of 42 edits were implementation pins removed in
  the same PR; no reverted or abandoned refactor was found.
- **Prose rules are followed about a third of the time** in agent-system's own census (34%); work
  the tool performs, every time (16 of 16).

Research: models shown buggy code write tests that assert the bug (ISSTA 2026, arXiv 2607.22883);
LLM oracles capture actual rather than expected behaviour (arXiv 2410.21136); mutation score
measures regression protection, not bug detection (arXiv 2607.22880).

## Goal and non-goals

Goal: no expected outcome enters, changes or leaves the suite, and no production code survives
only through tests, without a named source of intent and a recorded acceptance by someone other
than the author, bound to the exact change; and, in a pilot, measure which way of writing oracles
finds tests that assert defects.

What the design can guarantee mechanically: every detected change is accounted for, cites a source
that resolves and predates the PR, and carries an acceptance record bound to the candidate. What
it cannot guarantee mechanically: that the acceptance judgment was right, or that the acceptor was
in fact independent (all agents commit as one git identity). Those rest on the agent workflow and
on automated audit sampling (Measurement).

Non-goals: standings or tagging of existing tests; a protected test set; deleting tests at scale;
making existing tests non-blocking; running fewer or faster tests (an earlier wish of the owner,
not part of this change); test-value scores; core agent-system changes.

## Principle

A test's expected outcome has authority only through a source of intent (D3). Test greenness, a
test's existence, a register entry, a snapshot of current output or an agent-written document is
not a source. Existing tests keep running and blocking; what changes is that their expectations
can no longer move silently, and new ones cannot enter by copying what the code does.

## Design

### D1. The test-intent gate (mechanical, every PR)

A new script in the required "Public claims" job (no new required check name), using the
discipline-gate kernel (`scripts/governance/lib/`) for changeset loading and git access. The kernel
supplies infrastructure, not the analysis; the analysis is new work.

**Scope.** Product test code under `modules/`: all Java test source sets (test, integrationTest,
systemTest, testFixtures), `modules/test-support` (which lives under `src/main`), the app-api TCK;
frontend `*.{test,spec}.{js,ts,tsx}` including the `*-lockdown.test.*` files the default run
excludes, `src/mocks/**`, `src/__test-setup__/**`, `__fixtures__/**`; Rust `#[cfg(test)]` modules
in `modules/shell/src-tauri`; all of `modules/system-tests` (its oracles live under `src/main`); and
frontend files under `src/` that only tests import, kept in a committed list
(`gates/test-intent/test-support-paths.v1.json`) that a self-test recomputes from an import scan.
Out of scope: script and gate self-tests under `scripts/` and the
jseval suite (governance tooling, not product), stated in the gate's output.

**First increment: conservative, file-level.** Flag every added, deleted, renamed or modified file
in scope (the diff includes deletions and renames; the kernel's `diffAddedModifiedFiles` does not,
so the gate uses its own diff), every changed test-owned data file (test resources, golden and
truth files, fixtures and mocks inside test source sets), and these watched baselines:
the suppression-ratchet baseline, `gates/test-efficacy/strength-baseline.v1.json`, ArchUnit
stores, `gates/dead-code/baseline.txt` (frontend), `logic-seams.v1.json` `law`/`targetTests`, and
the test-evidence and stress policy files, and the helper list above. Changes to how tests are
selected or run are flagged too: edits to Gradle build logic and build scripts, vite and vitest
configuration and `package.json` test scripts whose changed lines touch tags, include or exclude
patterns, filters, test task configuration, timeouts, retries or forks (on the last 50 build-file
commits of main, 12 were flagged, about 4 of them false or borderline). Changes to annotations, tags, assumptions, conditional
disables, class-level configuration, imports and setup are file modifications and are flagged.
Production code, contract documents and governance registers that tests read by path (42 test
files do) are not flagged: they are sources or are governed by their own gates, and flagging them
would fail PRs that touch no test and block fixing a contract together with its test.
Only byte-identical renames and moves within the same execution context (same module and source
set; the same default vitest selection, with lockdown and e2e as separate selections) pass without
an entry; a move that changes where or whether a test runs is flagged. Parse failures and unresolved cases
flag; nothing disappears silently. Method-level pairing, helper-to-check propagation and
adaptation globs are a later increment, built only after an adversarial detection corpus (below)
shows they lose nothing.

**Coverage.** The gate writes a skeleton changeset listing every flagged item; the author fills in
class (D2) and source (D3). A PR fails while any item is uncovered, while any cited source does
not resolve, or while a cited document was added or modified in the same PR (a source written in
the PR is circular; the task's agreed scenarios are cited by task and scenario id and quoted).

**Acceptance record.** A PR with entries also needs an acceptance record, written by the acceptor
(D2), containing a digest over the content of every flagged item and over the changeset (the
record itself excluded), and the acceptor's role and session. The gate recomputes the digest and
fails when the record is missing or the digest differs, so any later edit to a flagged item or an
entry invalidates acceptance, while a rebase, a merge-group build or the squash push to main,
which leave flagged items unchanged, do not.

**Mechanics.** Base: merge-base with `GITHUB_BASE_REF` on pull requests, the merge-group base on
`merge_group`, the first parent on pushes to main; changesets anywhere in the branch count. A
shallow clone fails closed. The script runs locally the same way and is registered in
`run-publish-preflight.mjs --check` and `check-governance-ci-coverage.mjs`. Gates that already
govern tests (suppression ratchet, test-evidence policy, stress policy, test-efficacy changesets,
dead-code stores) keep their own mechanics. Only a `test-efficacy` changeset is a record with a
source; an item it already accounts for needs a reference to it, not a second entry. Suppression
ratchet raises, policy entries and ArchUnit store changes carry no intent source today and need a
full entry. Fork PRs without entries fail with a message saying a
maintainer agent completes the changeset.

**Detection corpus.** The gate ships with fixture PRs: a one-line `src/main` change plus the
matching `assertEquals` value; a deleted test; a renamed-and-edited test; `assertEquals` →
`assertNotNull`; an existing excluded tag added; `assumeTrue` added; a golden-file value edited; a baseline raised; a byte-identical move within one source set (must pass); moves into
`testFixtures`, `e2e/` or a lockdown name (must fail); an excluded tag, test filter or vitest glob
added in build configuration (must fail); a dependency bump in a build script (must pass); an
edit to a test-only helper or a system-tests oracle (must fail); a citation to a document added in the PR (must
fail); an acceptance record made stale by editing a flagged item (must fail); an accepted PR built
as a merge group after main moved (must pass); a contract document edited with no test change (must
pass).

### D2. Classes and acceptance

The author (builder) writes the entries. The acceptor is someone other than the author: the
verifier above the depth floor, the orchestrator below it when it did not build. The acceptor reads
the before-state test, the agreed scenarios and the cited sources first, forms a provisional class,
only then reads the candidate diff, and writes the acceptance record. In the seeded-entry experiment,
fresh verifiers rejected 6 of 6 bad entries and 3 of 6 entries a careful author meant as correct,
each for a real omission; expect rework.

| Class | Entry must contain | Acceptor checks |
| --- | --- | --- |
| New | The behaviour, its source, and the D4 evidence file | **The riskiest class.** Each expectation follows from the source, checked against the source, not the code. A snapshot seeded from current output is not a source. Per-file entries list each expectation that needs its own source |
| Obsolete | The specific source that changes the guarded behaviour; "decision records searched for a conflict: none / which" | Task scope or a heading is not a citation; the conflict search is repeated, not trusted |
| Adaptation | What moved (rename, signature, split, instrumentation, input re-encoding) | Whole oracle kept: inputs, preconditions, observation, fault injection, expected outcome, tags and conditions. An expected value computed by calling production code is an oracle change |
| Structural, rule kept | The rule and why it survives | Predicate unchanged or stricter; allowlist edits are renames or named in the brief; register guards still resolve |
| Incidental | Finite assessment: apparent purpose, callers and state owners, indirect behaviours, evidence for each, unresolved risks | Evidence must be able to fail (an `assertNotNull` on an Optional is not evidence). Preferred: the old inputs through the observable boundary on the before-state and the candidate. Missing documentation is uncertainty. Shape is never evidence |
| Defect pin | The defect source (D3) and the reproduction | The acceptor reproduces the defect and checks the violated obligation before reading the new expectation; establishing the defect does not by itself justify the particular new expectation, which needs its own source |

A red pre-existing test with no acceptable entry is a regression: fix the code, or the owner
decides. Flakes follow the recovery protocol, not this table. Tests that governance registers name
are removed only with the register updated in the same change. Structural PRs (more than 20 flagged
files) are classified per class of test against the PR's agreed scenarios in one pass.

### D3. Sources of intent

| Source | What counts | What it may support |
| --- | --- | --- |
| Owner words | A verbatim quote with its original location (task request, user turn, owner-quoted tempdoc line) | What the quote states |
| Owner-adopted scenario | A scenario the owner agreed in the task's agreement step, cited by task and scenario id; labelled as agent-drafted, owner-adopted | What the scenario states |
| Delegated choice | The owner's written delegation, quoted, with its scope; the agent's specific choice, labelled as an agent choice | The choice, if the acceptor confirms it lies within the delegation's scope and no stronger source conflicts. A delegation to "implement X" does not authorise changing an established contract |
| Removal | Only for Obsolete: production code the test guarded, deleted in the same PR (a deleted file, or a named member present at the base and gone at the head). A file merely modified does not count | Removing the test together with the code (P6) |
| Defect source | A safeguard every product must keep (no hang, no data loss, no crash, no leak across a requested scope) or a stronger source above, plus a reproduction | That the old behaviour is wrong; not which fix is right |
| Decision record | One that records the owner's decision for that behaviour | That behaviour; "accepted" status alone is not enough |

- Agent-written documents labelled "owner decision" without owner words count as nothing; a real
  delegation must be quoted separately.
- Sources must predate the PR (D1 checks it).
- Where no source exists, the existing expectation stays. Absence of a source never licenses a
  change.
- Ordinary tasks need nothing new from the owner: the agreement step already produces
  owner-adopted scenarios.

### D4. Execution evidence

A new check's entry attaches an evidence file written by a script, not by the agent. The script
runs the named checks itself and validates its own output: revision and tree hash, command,
environment, the selected checks as listed in `TEST-*.xml` or the vitest JSON report, executed
versus skipped per check, and, for repaired behaviour, a fail-before run on a before-worktree whose
failure is an assertion failure in the named check (not compilation, environment or an uncaught
exception; a check of "used to throw, now does not" wraps the call in `assertDoesNotThrow` or
`expect(...).not.toThrow()` so the old behaviour fails as an assertion). The gate
checks that the content of the checks it names is unchanged since the run and that every new
check in the entry appears as executed.

### D5. Pilot: which way of writing oracles finds defect pins (its own task)

Module: `modules/adapters-lucene` (owner's choice, 2026-10-05): deterministic in-process
interfaces, historical defect pins, 10 revertible past fixes. F-055 spans adapters-lucene and
app-services; the pilot names the specific obligation and boundary before use.

1. **Obligations, tagged** by provenance (owner words, owner-adopted scenario, delegated choice,
   defect source, decision record, agent-only) and kind (safeguard or product choice). Provenance
   is load-bearing: in the blind replay every miss came from an agent-only source stating the
   defect as intended. The owner gets one batch of yes/no questions, only for product choices
   whose source is agent-only (expected about 6–10). Packets are frozen before any agent sees code.
2. **Arms, same obligations, same budget, repeated with independent agents:** blind oracles (no
   implementation or tests, frozen; a second agent makes them executable, never amends one, returns
   unexecutable ones with a reason); code-aware checks; and a code-aware defect-hunting review, scored separately because it yields
   findings, not checks (in a 12-case trial with neutral wording and no history, it flagged 6 of 6
   defects and 0 of 6 correct behaviours; one run each).
3. **Red against today's code** is a defect or a question, never adjusted.
4. **Pre-registered before the run:** the fix commits and refactorings, with faulty and good
   revisions verified; exclusions; scoring; independent defect adjudication; thresholds (adopt
   blind oracles if they catch at least as many replayed regressions as existing tests, give at most
   half their refactoring false alarms, and have under 30% oracle error; inconclusive if the
   difference is within one case either way); an explicit inconclusive outcome. New checks must
   fail on the faulty revision and pass on the fix.

### D6. Code kept alive only by tests

- **JVM methods (app-launcher, first increment).** `UnreferencedCodeTest.java` already ignores test
bytecode but exempts test-only methods through its `KNOWN_UNREFERENCED` map and the name predicates
at `:349-353` (`*ForTest*`, `*ForTesting`, `install*`, `reset*`). Split the map: entries whose
callers are production code in modules outside app-launcher's classpath (for example
`RagContextOps.executeRetrieval`, `SearchTraceProjector.project`) stay as named cross-module
exemptions; entries whose only callers are tests move to the frozen store. Replace the name
predicates with the enumerated methods they match today, classified the same way (`install*` and
`reset*` also match production methods). Then wrap the rule in a
  `FreezingArchRule` with violations identified by fully qualified owner and signature, seed its
  store deliberately from the enumerated current violations, and configure it like
  `modules/dead-code-audit` (absolute path, Gradle inputs, store creation disabled in normal runs).
  Refreezing during normal verification is forbidden.
- **JVM classes.** `WholeProgramDeadCodeTest.java` (in `modules/dead-code-audit`) keeps its store.
- **Frontend.** Knip's `gates/dead-code/baseline.txt` is watched by D1; Knip counts test files as
entry points, so exports used only by tests are invisible today. Making Knip ignore test callers is
measured before it is decided.
- **Limit of the first increment.** Only app-launcher's non-public methods are covered. Public
  methods (no detector today; `WholeProgramDeadCodeTest` checks classes) and frontend exports are
  stated gaps, each measured before a rule is added.
- **Rule.** The stores shrink automatically as code is removed. An addition is allowed only with a
  D1 entry and acceptance (a reviewed exception, e.g. an identity-preserving rename); D1 compares
  violation sets, not counts. Public methods and unreachable production cycles stay outside this
  guarantee; a whole-program method rule is measured first (its earlier run reported about 6,400
  noisy findings, to be reconciled with the 300–600 estimate).

### D7. Order

1. Gate first increment, detection corpus, skeleton changeset, acceptance record, evidence script,
   D6 for app-launcher, project-knowledge prose for D2 and D3. Bounded prototype of about 2–3
   agent-days; the estimate is revised after the prototype.
2. Run for four weeks; read counts per class, rejections, rework time, audit results, and new
   checks per PR against the previous four weeks.
3. Pilot (D5) as its own task.

### D8. CI

Existing tests keep running and blocking exactly as today: lanes, retries
(`failOnPassedAfterRetry`), tag exclusions, timeouts and per-lane test counts unchanged, except that
`UnreferencedCodeTest` changes what it checks (D6). The only CI
changes are the new script inside an existing required job and the frozen rule inside an existing
test. Separate finding for its own task: CI retry appears not to run, and one app-services scan
test flakes about 1% of runs.

## Measurement and stop rules (automated)

These are the orchestrator's design choices, not implied by the owner's request: they exist
because the gate cannot prove acceptor independence (Goal) and the owner cannot be relied on to
notice drift.

- The gate selects every fifth accepted PR deterministically (by PR number) and requires a second
  acceptance record from a different reviewer on those, before merge. A disagreement goes to a third
  agent reviewer, whose verdict decides. A rejection on the current content blocks until a third
  reviewer accepts or the content changes.
- The gate writes rolling four-week counts (entries per class, rejections, audit disagreements,
  tie-breaks, new checks per PR) to the Public claims job summary on every run; there is no
  scheduled job (ADR-0026). The four-week review is a task the orchestrator opens on a date
  recorded at rollout.
- **Reconsider** if entry writing exceeds about 5k tokens per typical PR, if new checks per PR
  drop markedly, or if disagreements or tie-breaks exceed one in ten audited PRs. For the last,
  the gate prints a warning in its normal output (it never fails for it), and the orchestrator
  reports it to the owner in its next delivery, as a finding, not a question.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| Three standings, protected enforcement set, red-triggered classification (drafts 1–4) | Little cleanup friction in history; protected set 246–377 files, 6 of 22 sampled traced to the owner; red triggers miss edits that never go red |
| Owner sees only conflicts (sources taken from documents) | The documents are mostly agent-written with the code |
| Non-blocking existing tests | Blocking costs about 3–5 agent-hours a week and caught real regressions in #727 |
| Prose rules only | Followed about a third of the time; the owner cannot notice violations |
| Body-only, method-level gate first | Misses expectation edits in constants, helpers, data, tags and conditions; fine-grained pairing needs a corpus before it can be trusted |
| Delete and rebuild | Rebuilt tests written from code re-pin it |

## Open decisions for the owner

1. Scope: product tests under `modules/` including frontend and Rust (recommended), or Java only.
2. Task split: this task delivers D1–D4, D6 and D7 steps 1–2; the pilot is its own task
   (recommended).
