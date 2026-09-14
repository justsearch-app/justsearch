---
description: "TRIGGER when: a build or test command fails, agent is asked to fix CI, or investigating a failed GitHub Actions run. Loads the CI failure symptom-to-fix decision tree."
user-invocable: true
---

# CI Triage

Decision tree for diagnosing CI and build failures. Match the symptom, follow the fix.

## Workflow Signals

Before interpreting a GitHub Actions failure, run:

```bash
node scripts/ci/workflow-signal-health.mjs --repo justsearch-app/justsearch --md
```

Use the computed failure class to route the public hosted `CI` fact lanes and the other
workflows: `docs-lint.yml`, `build-installer.yml`, `codeql.yml`, `cla.yml`, `onramp-smoke.yml`,
`ci-walltime-trend.yml`, `sign-vendored-mirrors.yml`.

- Public hosted `CI` failures route by fact-lane job name first — `ci.yml` has eight:
  `public-claims`, `license-and-notices`, `build`, `unit-tests`, `windows-native-tests`,
  `shell-rust-tests`, `integration-tests`, `secret-scan` (plus the `ci-walltime` reporter).
- `release-blocking-failure` on `Build Installer` routes to tempdoc 374 / Production-Reality Verification.
- `Docs Lint` failures route to the matching docs lint section below.
- `CodeQL` failures route to the reported query/path first; do not treat them as generic build failures.
- `CLA Assistant` is the contributor gate (it replaced the earlier DCO check). On a
  `merge_group` event it is a deliberate no-op — signature enforcement already ran on the PR.

After touching any `@Tag("stress")` subject or concurrency-sensitive code, run:

```bash
./gradlew.bat test -PincludeStress=true
```

For a focused run, select only modules containing stress tests and place `--tests`
after each selected test task. A repository-wide `--tests "*Stress*"` fails in
modules with no matching class; keep Gradle's no-match validation enabled.


## Where did the CI time go? (wall-clock attribution)

To see which lane is the critical path and how much of each lane is fixed runner tax vs addressable work — instead of hand-rolling `gh api .../jobs | jq`:

```bash
node scripts/ci/report-ci-walltime-attribution.mjs --run-id <run-id> --md   # a specific run
node scripts/ci/report-ci-walltime-attribution.mjs --latest --md            # the current branch's most recent CI run
```

Add `--download-artifacts` to also split the unit lanes into test CPU vs framework overhead. Attribution only — it changes no check result.

## Merge-queue (`merge-group`) failures

Merging goes through the live GitHub merge queue (ruleset `main-merge-queue`, SQUASH), so
`ci.yml` and `cla.yml` also fire on `merge_group` — against your PR *merged with current
`main`*, not against your branch. A **queue rejection** (the entry drops out of the queue and
the PR stays open, unmerged) therefore signals a failure your own green PR checks could not
have caught: an integration conflict with something that landed while you waited.

- Pull the `merge-group` run itself and read it — do not re-read the PR's own passing run.
- Do not blind-retry the enqueue; a second rejection costs another full queue cycle.
- Re-run the full local suite against freshly-pulled `main` before re-enqueuing
  (`subset-isnt-the-suite`).

## Symptom → Fix Map

### Test failure

```bash
./gradlew.bat :modules:<module>:test              # reproduce
# Read the test, understand its intent, fix YOUR code (not the test)
```

### PMD violation

```bash
./gradlew.bat pmdAll                              # reproduce (every source set; CI runs this task)
# Test sources use config/pmd/ruleset-tests.xml, main uses ruleset.xml. Edited a ruleset?
#   node scripts/ci/check-pmd-ruleset-sync.mjs
# Fix the code to satisfy the rule. Do NOT add @SuppressWarnings.
# If rule is wrong for this case, check agent-guide §3.3 PMD table for exceptions.
```

### Spotless violation

```bash
./gradlew.bat spotlessCheck                       # reproduce
./gradlew.bat spotlessApply                       # fix
# Then re-commit the formatted files.
```

### Markdownlint failure

```bash
npx markdownlint "docs/**/*.md"                   # reproduce
# Common: MD040 (fenced code block language), MD013 (line length)
```

### Docs lint — link check failure

```bash
node scripts/docs/verify-canonical-doc-links.mjs  # reproduce
# Canonical docs must not reference tempdocs. Replace with source file references.
```

### Docs lint — runtime config matrix drift

```bash
node scripts/docs/verify-runtime-config-matrix.mjs  # reproduce
node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md  # fix
```

### Docs lint — module dep graph drift

```bash
node scripts/architecture/module-deps.mjs --check-canonical  # reproduce
node scripts/architecture/module-deps.mjs --update-canonical  # fix
```

### Docs lint — llms.txt drift

```bash
node scripts/docs/llmstxt-generate.mjs --check    # reproduce
node scripts/docs/llmstxt-generate.mjs             # fix
```

### Docs lint — skills drift

```bash
node scripts/docs/skills-sync.mjs --check          # reproduce
node scripts/docs/skills-sync.mjs                   # fix
```

### Gradle lockfile failure

```bash
# Symptom: "Dependency verification failed" or lock-skew errors
./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks  # fix
node scripts/ci/report-lock-skew.mjs                                    # verify
```

Never hand-edit a lockfile, and commit the regenerated files in the same commit as the
`build.gradle.kts` change. The `lockfile-hint` hook delivers this at the edit itself,
including the tempdoc 637 #4 trap where a neighbour's `resolveAndLockAll` silently reverts
your added dependency.

## PMD Categories (Quick Reference)

| Category | Key Rules |
|----------|-----------|
| Best Practices | UnusedPrivateMethod, UnusedLocalVariable, AvoidReassigningParameters |
| Code Style | UnnecessaryImport, FieldNamingConventions, ClassNamingConventions |
| Design | CyclomaticComplexity, NPathComplexity, TooManyMethods, CouplingBetweenObjects |
| Error Prone | EmptyCatchBlock, AvoidDuplicateLiterals, CloseResource |
| Performance | InefficientStringBuffering, ConsecutiveLiteralAppends |

## Key Rule: Fix Root Causes

- **Never** comment out failing code, weaken assertions, delete tests, or add suppressions
- **Never** broaden catch clauses or remove validation to make errors disappear
- If a test fails after your changes, the test is probably right and your code is wrong
