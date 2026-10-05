---
title: Test Intent Rules
type: reference
status: stable
description: "Agent-facing rules for the test-intent gate: what it flags, the entry classes, sources of intent, execution evidence and the acceptance record."
---

# Test Intent Rules

Every expected outcome that enters, changes or leaves the product test suite names a source of
intent and carries an acceptance record from someone other than its author. The `test-intent` gate
enforces this on every PR in the required "Public claims" job. This page is what the gate's failure
output points to. The design is tempdoc 966 (D1 to D4, D8).

The rule behind it: an expectation comes from the owner's intent, never from what the code
currently does. Where no source exists, the existing expectation stays. A missing source never
licenses a change.

## What the gate flags

The first increment is conservative and file-level. Every added, deleted, renamed or modified file
in scope is one flagged item. Annotations, tags, assumptions, conditional disables, class-level
configuration, imports and setup are file changes, so they are flagged too.

| In scope | Item |
| --- | --- |
| Java test source sets under `modules/<m>/src/<set>/` for any set other than `main` (test, integrationTest, systemTest, testFixtures), including their resources, golden and truth files | the file |
| `modules/test-support/src/**` and the app-api TCK (`modules/app-api-tck/src/**`) | the file |
| All of `modules/system-tests/**`, including `src/main` (the AI-judge oracles) and its build script | the file |
| Frontend `*.{test,spec}.{js,jsx,ts,tsx,mjs,cjs,mts,cts}`, including `*-lockdown.test.*` | the file |
| Frontend `src/mocks/**`, `src/__test-setup__/**`, any `__fixtures__/**` or `__snapshots__/**` under `modules/` | the file |
| Frontend `src` files that only tests import, listed in `gates/test-intent/test-support-paths.v1.json` (the list at the base and at the head both count) | the file |
| Rust `modules/shell/src-tauri`: files under `tests/` and files loaded by a `#[cfg(test)] mod x;` declaration | the file |
| Other Rust files of the crate that contain `#[cfg(test)]` or `#[test]` items (a file with `#![cfg(test)]` counts whole) | `<path>#cfg(test)`, flagged when the test items change |
| `scripts/ci/suppression-ratchet-baseline.v1.json`, `gates/test-efficacy/strength-baseline.v1.json`, `gates/dead-code/baseline.txt`, `scripts/ci/test-evidence-policy.v1.json`, `scripts/ci/stress-suite-policy.v1.json`, `scripts/ci/unit-test-shard-policy.v1.json`, `gates/test-intent/test-support-paths.v1.json`, any `modules/*/archunit_store/**` | the file |
| `governance/logic-seams.v1.json`, only the `law` and `targetTests` of each seam | `governance/logic-seams.v1.json#law-targetTests` |
| Build configuration outside `scripts/`: Gradle scripts and `build-logic/**` sources, `gradle.properties`, `vite.config.*`, `vitest.config.*`, `vitest.workspace.*`, `playwright.config.*`, and the test scripts and test-runner keys of `package.json`, when a changed line touches test selection or execution | the file |
| `settings.gradle(.kts)`: the Gradle rules above, and a project an `include` named at the base that no `include` names at the head | the file |
| Any `junit-platform.properties`, on any change | the file |
| Any `META-INF/services/org.junit.platform.*` (launcher listeners, post-discovery filters), on any change | the file |
| `.github/workflows/*.yml` and `*.yaml`, when a changed line runs or selects tests (see below) | the file |

### Test-only frontend helpers

A file under a frontend module's `src/` that only tests import is test infrastructure, whatever its
name. The committed list `gates/test-intent/test-support-paths.v1.json` names them. A static import
scan seeds it, and the gate's unit test recomputes the scan and fails when a file the scan finds is
missing from the list. When the test fails, add the file to the list. That edit is a watched
baseline change and needs an entry.

### Build configuration

A changed line of build configuration counts when it touches test selection or execution:

- it names tests or how they run (test tasks, `useJUnitPlatform`, tags, filters such as
  `excludeTestsMatching`, forks, retries, timeouts, `vitest`, `e2e`);
- it sits inside a test task or test configuration block (`tasks.named<Test>`, `withType<Test>`,
  vitest's `test:` key);
- it declares a value that such a block reads.

These lines do not count:

- comment lines;
- dependency declarations, including test dependencies;
- Gradle `inputs` and `outputs` declarations, which decide whether a cached result is reused, not
  which tests run.

In a `gradle.properties` file (root or module), every changed property line counts. A project
property such as `windowsOnly=true` can select tests under any name. The only exception is a key
whose name ends in `version`, when its value changes from one version to another and nothing else
changes.

A `vitest.config.*`, `vitest.workspace.*` or `playwright.config.*` change always counts. A file whose
braces cannot be matched is flagged on doubt. In `package.json` under `modules/`, a script counts when
its name or command names tests. Elsewhere, only scripts whose command runs `modules/`, Gradle or a
frontend test runner count.

A `junit-platform.properties` change always counts, wherever the file sits. On a test runtime
classpath it reconfigures every JUnit run of the module. For example,
`junit.platform.execution.dryRun.enabled=true` skips every test and the build still succeeds. The
same holds for `META-INF/services/org.junit.platform.*` files. They register launcher listeners and
post-discovery filters, which can drop tests the same way.

In `settings.gradle(.kts)`, removing a project from `include` counts when no `include` names it at
the head, because its tests leave the build. Reordering includes, or splitting them across
statements, does not count. An include whose arguments are not plain string literals counts on
doubt.

A changed line of a GitHub workflow counts when:

- it runs or selects product tests itself. This covers a Gradle invocation naming `test`, `check`,
  `build` or `*Test` tasks, test properties or a task-list expression. It also covers a
  colon-qualified test task such as a lane task list entry (`:modules:core:test`), a `-x` or
  `--exclude-task` exclusion, vitest, jest, mocha, `playwright test`, `cargo test`, and
  `node --test` outside `scripts/`. An npm, pnpm or yarn test script counts too. A root script
  counts only when its root `package.json` command reaches the product suite.
- it sits in a step that runs tests. That is a step with such a line, or one that reads a matrix
  or `env` value holding test tasks (`${{ matrix.gradle_tasks }}`).
- it is the `if`, `continue-on-error`, `runs-on` or `env` of a job that runs tests.
- it is a matrix line of such a job that is under `exclude`, or whose key a test step or one of
  those job keys reads (`runs-on: ${{ matrix.os }}`).
- it is a trigger (`on:`) line of a workflow that runs tests.
- in a workflow that runs tests, it is an `env:` entry at any level (workflow, job or step), or it
  writes to `$GITHUB_ENV`. The environment reaches every JVM and Gradle run, for example
  `JAVA_TOOL_OPTIONS` or `ORG_GRADLE_PROJECT_*` variables.

Comments and `name`, `id`, `key`, `restore-keys` and `description` lines never count. Step names
and cache keys do not select tests.

Not flagged:

- Production code, contract documents and governance registers that tests read by path. They are
  sources, or their own gates govern them.
- Script and gate self-tests under `scripts/` and the jseval suite. They are out of scope by design,
  as governance tooling rather than product tests, and the gate's output says how many changed.
- Byte-identical renames and moves that keep the file in the same execution context. They pass
  without an entry. An edited rename flags both the old and the new path.

A byte-identical move into another execution context flags both paths, because it changes how
the test runs, or whether it runs at all. The execution context is:

- for a Java file, the same module and the same source set. So `src/test` to `src/testFixtures`
  is flagged.
- for a `modules/ui-web` test, the same vitest selection. The default selection is
  `src/**/*.{test,spec}.{js,ts,tsx}` without `*-lockdown.test.*`. The lockdown selection is
  `src/**/*-lockdown.test.{ts,tsx}`. A move into `e2e/`, or a rename to `*-lockdown.test.*`, is
  flagged.
- for Rust, the same directory.

Mocks, test setup, fixtures, snapshots, test-only helpers and watched baselines have no execution
context of their own. Their moves are always flagged.

A Rust file or the logic-seam register that does not parse is flagged. Nothing disappears silently.

The diff runs from the base to the working tree, including deletions, renames and uncommitted
files. The gate resolves the base itself:

- On `pull_request`, the merge-base with `origin/$GITHUB_BASE_REF`.
- On `merge_group`, the event's `base_sha`.
- On a push to main, the first parent.
- Locally, the merge-base with `origin/main` (or `main`). Pass `--base <ref>` to override.

A shallow clone fails closed.

## Running it

```bash
node scripts/governance/gates/test-intent/cli.mjs                       # check the working tree
node scripts/governance/gates/test-intent/cli.mjs --skeleton <name> --task <task id> --session <your session id>
node scripts/governance/gates/test-intent/cli.mjs digest gates/test-intent/.changesets/<name>.md
node scripts/governance/run.mjs --gate test-intent --mode gate          # the same check, as CI runs it
```

Exit codes: 0 for pass, 1 for fail, 2 for a usage or runner error. `--pr <n>` tells a local run
the PR number so it can evaluate audit selection. In CI the number comes from the event.

## Filling the skeleton

`--skeleton` writes `gates/test-intent/.changesets/<name>.md` with one TODO entry per uncovered
item. An entry whose class is still `TODO` covers nothing. Fill each entry, group items that share
one warrant, and delete the `note` fields. Changesets anywhere in the branch count. The format:

````markdown
---
schema: test-intent.v1
task: t-20261005-123456
author-role: builder
author-session: <the author's session id>
---

# Test-intent entries

Optional prose.

```json
{ "entries": [
  { "items": ["modules/core/src/test/java/io/x/CalcTest.java"], "class": "Obsolete",
    "sources": [{ "kind": "owner-adopted-scenario", "task": "t-20261005-123456", "scenario": "S3",
                  "quote": "Totals are rounded half-even.", "label": "agent-drafted, owner-adopted" }],
    "conflictSearch": "decision records searched for a conflict: none" }
] }
```

## Acceptance records
````

The changeset has exactly one `json` block above `## Acceptance records`. Everything from that
heading on is excluded from the digest.

## Classes

The author (the builder) writes the entries. Fields marked as optional are still checked when
present.

| Class | Entry fields | The acceptor checks |
| --- | --- | --- |
| `New` | `behaviour`; `sources` with at least one expectation source; `evidence` (a D4 evidence file); `newChecks` (the check ids it covers); `repaired: true` when the check pins repaired behaviour, which then needs an assertion fail-before | The riskiest class. Each expectation follows from the source, checked against the source, not the code. A snapshot seeded from current output is not a source. Per-file entries list each expectation that needs its own source. |
| `Obsolete` | `sources` naming the specific source that changes the guarded behaviour; `conflictSearch`: "decision records searched for a conflict: none / which" | Task scope or a heading is not a citation. The conflict search is repeated, not trusted. |
| `Adaptation` | `moved`: one or more of `rename`, `signature`, `split`, `instrumentation`, `input re-encoding`; `whatMoved`; optional `sources` | The whole oracle is kept: inputs, preconditions, observation, fault injection, expected outcome, tags and conditions. An expected value computed by calling production code is an oracle change. |
| `Structural rule kept` | `rule`; `whySurvives`; optional `sources` | The predicate is unchanged or stricter. Allowlist edits are renames or named in the brief. Register guards still resolve. |
| `Incidental` | `assessment` with `purpose`, `callersAndStateOwners`, `indirectBehaviours`, `evidence`, `unresolvedRisks`; optional `sources` | The evidence must be able to fail (an `assertNotNull` on an Optional is not evidence). Preferred: the old inputs through the observable boundary on the before-state and the candidate. Missing documentation is uncertainty. Shape is never evidence. |
| `Defect pin` | `defectSource` (kind `defect-source`); `reproduction`; `sources` with an expectation source of its own; `evidence` and `newChecks` with an assertion fail-before | The acceptor reproduces the defect and checks the violated obligation before reading the new expectation. Establishing the defect does not justify the particular new expectation, which needs its own source. |

Other rules:

- A red pre-existing test with no acceptable entry is a regression. Fix the code, or the owner
  decides.
- Flakes follow the recovery protocol, not this table.
- Tests that governance registers name are removed only with the register updated in the same
  change.
- Structural PRs (more than 20 flagged files) are classified per class of test against the PR's
  agreed scenarios in one pass.

### Items another gate already records

A `test-efficacy` changeset is the only existing record with a source. A
`gates/test-efficacy/strength-baseline.v1.json` or logic-seam `law`/`targetTests` change that a
`test-efficacy` changeset in the same branch accounts for needs a reference, not a second entry:

```json
{ "items": ["gates/test-efficacy/strength-baseline.v1.json"], "ref": "gates/test-efficacy/.changesets/<name>.md" }
```

A changeset holding only references needs no acceptance record. Suppression-ratchet raises,
policy entries and ArchUnit store changes carry no intent source today, so they need a full entry.

## Sources of intent

Each entry in `sources` has a `kind`. Sources must predate the PR. A repository document cited as
a source must exist at the base and must not be added or modified in the same PR, because a
source written in the PR is circular.

| `kind` | What counts | Fields | What it may support |
| --- | --- | --- | --- |
| `owner-words` | A verbatim quote with its original location (task request, user turn, owner-quoted tempdoc line) | `quote`, `location` | What the quote states |
| `owner-adopted-scenario` | A scenario the owner agreed in the task's agreement step | `task`, `scenario` (e.g. `S4`), `quote`, `label: "agent-drafted, owner-adopted"` | What the scenario states |
| `delegated-choice` | The owner's written delegation, quoted, with its scope, and the agent's specific choice | `delegationQuote`, `delegationLocation`, `delegationScope`, `choice`, `label: "agent choice"` | The choice, if the acceptor confirms it lies within the delegation's scope and no stronger source conflicts. A delegation to "implement X" does not authorise changing an established contract. |
| `defect-source` | A safeguard every product must keep, or a stronger source, plus a reproduction | `safeguard` (`no hang`, `no data loss`, `no crash`, `no leak across a requested scope`) or `stronger` (another source object); `reproduction` | That the old behaviour is wrong, not which fix is right |
| `decision-record` | A record under `docs/decisions/` of the owner's decision for that behaviour | `location`, `quote` | That behaviour. "Accepted" status alone is not enough. |
| `removal` | Only for `Obsolete`: the same PR removes the production code the test guarded | `removed`: production paths the PR deletes, or `path#member` entries | Removing the test with the code |

A `removed` entry is one of:

- a production file the PR deletes;
- `path#member`, where the member (an identifier) occurs in the production file at the base and
  does not occur anywhere in it at the head. The match is whole-word and includes comments.

A production file that is only modified is not a removal. Changing the code and then editing the
test to match needs a real source.

A `location` is either a repository path (`<path>`, `<path>:12` or `<path>:12-18`),
where the gate checks that the quote is present, or a task record, such as
`task t-20261005-123456 request` or `task <id> user turn 3`, where the acceptor checks the quote.

Never citations: `task-scope`, `task`, `scope`, `heading`, `brief`, `agent-decision`. Agent-written
documents labelled "owner decision" without owner words count as nothing. A real delegation must
be quoted separately. Ordinary tasks need nothing new from the owner, because the agreement step
already produces owner-adopted scenarios.

## Execution evidence

A new check's entry attaches an evidence file written by the script, never by hand:

```bash
node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/<name>.json \
  --check modules/core/src/test/java/io/x/CalcTest.java#adds \
  --check modules/ui-web/src/views/list.test.ts#"lists rows" \
  --check modules/shell/src-tauri/src/lib.rs#tests::adds
```

The script handles three kinds of check:

- Java checks run through Gradle and are read from `TEST-*.xml`.
- Frontend checks run through vitest and are read from its JSON report.
- Rust checks run through `cargo test` and are read from the libtest output.

For each check the script records the revision, the working-tree hash, the commands, the
environment, a content digest of each check's file, and whether the check executed or was skipped.

For repaired behaviour, add `--before <ref>`. The checks then run on a worktree of the
before-state, and the failure must be an assertion failure in the named check. A compile or
environment failure does not count.

An uncaught exception is not an assertion failure either. This matters when the repair is that a
call used to throw and now does not. Wrap the call so that the before-state fails an assertion:

- JUnit: `assertDoesNotThrow(() -> parser.parse(input))`. It fails with `AssertionFailedError`.
- vitest: `expect(() => parse(input)).not.toThrow()`. It fails with an `AssertionError`.

A bare call that throws on the before-state is recorded as `exception` and is rejected.

The gate rejects the evidence when any of these hold:

- The file was edited after the script wrote it.
- A named check's file changed since the run.
- A new check is missing, skipped, partly skipped or not passed.
- A required fail-before is not an assertion failure.

## The acceptance record

A changeset with entries needs an acceptance record from someone other than its author. The
acceptor is the verifier above the depth floor, or the orchestrator below it when it did not build.

The acceptor works in this order:

1. Reads the before-state test, the agreed scenarios and the cited sources.
2. Forms a provisional class.
3. Only then reads the candidate diff.
4. Checks the entry against the class table above.
5. Appends the record:

```bash
node scripts/governance/gates/test-intent/cli.mjs accept gates/test-intent/.changesets/<name>.md \
  --role verifier --session <acceptor session id> --verdict accept
```

The record carries a digest over the content of every flagged item the changeset covers (before
and after), the changeset above the acceptance heading, and every evidence file it cites. Any
later edit to a flagged item, an entry or an evidence file makes the record stale, and the
acceptor reviews again and appends a new record. A rebase, a merge-group build or the squash push
to main leaves the digest unchanged. The gate fails when:

- the record is missing or stale;
- it was written by the author's session;
- its role is not `verifier` or `orchestrator`;
- any record on the current content rejects (see below).

Records are appended, never deleted. Expect rework: in the seeded-entry experiment fresh verifiers
rejected entries a careful author meant as correct, each for a real omission.

### Rejections

Any rejection on the current digest blocks the PR. Only changed content clears a rejection: rework
the entries or the flagged items, which gives a new digest and leaves the rejection stale, and then
get a new record on the new content.

- The same reviewer accepting the same content after rejecting it does not clear the rejection.
- A different reviewer accepting the same content is a disagreement. It passes only with a
  `--kind tie-break` record from a third reviewer that accepts. The third reviewer is not the author
  and not one of the disagreeing reviewers.

### Audit and tie-break

Every fifth PR by number (PR numbers divisible by 5) is audit-selected. It also needs a record with
`--kind audit` from a reviewer whose session differs from the author's and the acceptor's.

If the auditor and the acceptor disagree, a third agent reviewer appends a record with
`--kind tie-break`, and its verdict decides. The third reviewer is distinct from the author, the
acceptors and the auditor.

### Counts

On every CI run the gate step in "Public claims" appends the rolling four-week counts to the job
summary. The counts read merged history on main:

- entries per class;
- references to test-efficacy changesets;
- rejections;
- audited PRs;
- disagreements;
- third-reviewer tie-breaks;
- new checks per PR.

There is no scheduled workflow, because ADR-0026 forbids `schedule:` triggers.

The reconsider rule is checked on every run. Over the same four weeks, it compares third-reviewer
tie-breaks per audited PR against one in ten. Before any tie-break has happened, it uses
two-reviewer disagreements per audited PR instead. When the rate is above one in ten, the gate
prints a marked warning in its normal output (`WARNING test-intent/reconsider-rate`) and in the job
summary. The warning names the rate. The orchestrator must report it as a finding in its next
delivery. The warning never fails the gate. Locally:
`node scripts/governance/gates/test-intent/weekly-report.mjs [--days 28] [--out-md <file>] [--out-json <file>]`.

## Fork PRs

A fork PR without entries fails with a message saying a maintainer agent completes the test-intent
changeset. Contributors are not expected to write one.
