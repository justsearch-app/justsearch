---
title: "Pre-merge Checks"
type: reference
status: stable
description: "Which local check to run before merging, by the subject a change edits."
audience: contributor
---

# Pre-merge Checks

Run the check whose **subject** you edited before marking a change ready. Commands are
`node scripts/ci/<name>.mjs` or `node scripts/governance/run.mjs --gate <id> --mode gate`.
Hosted CI runs affected checks on the pull request and the full suite on main
([ADR-0044](../../decisions/0044-public-hosted-ci-fact-lanes.md)). This table moved here from
the retired root `CLAUDE.md` when JustSearch adopted agent-system (2026-10-05).

## One-command candidate preflight

From a clean, committed worktree, run:

```bash
node scripts/dev/prepare-worktree.cjs --preflight --pr 123
```

This opt-in mode prepares isolated npm/Python tools with `prepare-ui-checks.cjs`,
builds this candidate's distribution, regenerates required governance inputs on every
run, and executes the existing local reproductions of required checks selected by the CI
change classifier. The single command inventory is `contexts[].commands` in
`scripts/ci/public-ci-local-repro.v1.json`; optional context lanes select checks.
Preparation stays in `prepare-worktree.cjs`; execution and the versioned inventory
stay in `run-publish-preflight.mjs`. Ordinary preparation and `--no-dist` keep their
existing behavior and do not start these checks.

Use `--pr N` for the number whose audit-selection rules should apply. The base is
merge-base with `origin/main`; fetch that branch and full history before running.
Without `--pr`, the latest issue/PR number plus one is predicted through `gh` and
marked provisional; a missing prediction leaves the test-intent audit check
unverified. The runner passes the base and number to the test-intent CLI. The
advisory gate receives PR mode and the base through its existing environment inputs.
The Python suite needs CI's Python 3.13; an unavailable interpreter version is
reported as not checked here.

Add `--list` to print commands, regenerated outputs and prerequisites without
installing, building, querying GitHub or running checks. The plan describes the
committed candidate; commit source edits before relying on its lane selection.
`node scripts/ci/run-publish-preflight.mjs --prepare` is the equivalent runner entry.

Independent checks continue after failures. Each result is **passed**, **failed**,
**blocked** by a prerequisite, or **not checked here** with a reason. Unavailable
advisory API evidence, Windows-specific commands, unreproduced display/GPU cases
and hosted identity/toolchain checks count as incomplete, never passed. A complete run requires every
selected step to pass; incomplete runs exit nonzero. Logs and `summary.json` are
written under ignored `tmp/preflight/`. The disposable virtualenv and scratch files
are recreated each run; private download caches are retained. Generated inputs are discarded before their
producers run, including when prerequisites fail, so old reports cannot count as
current evidence. No shared Python environment or application configuration is installed;
Playwright browser installation disables shared-cache garbage collection. The existing Gitleaks command requires an installed CLI. Gradle commands require
`js-gradle-slot` on the shared machine. System browser-package installation is
reported unverified; the prepared isolated Chromium is used by the browser check.

The existing inventory-only `run-publish-preflight.mjs --check` and `--list`, and the
legacy full-subset `--run`, remain available. The prepared mode supplies isolation and
PR semantics; legacy `--run` still expects its tools and inputs to be prepared by the
caller. Neither mode is the launcher's cheap `.agents/preflight` hook.

For a focused manual build: `node scripts/lib/tool-invoker.cjs gradle build -x test`
from the candidate worktree.

## Checks by edited subject

| Edited subject | Check(s) |
|---|---|
| Java sources | `node scripts/dev/java-prepush.mjs` (see below) |
| `.github/workflows/*.yml` · root README | `check-workflow-triggers` · `check-root-readme` |
| repo history publication settings ([ADR-0045](../../decisions/0045-public-main-history-publication.md)) | `check-repo-history-policy` |
| PR title/body plus managed review record | `preview-squash-message` · `pr-review-record check` |
| `contracts/**` | `--gate wire` |
| `docs/decisions/**` | `--gate adr-coverage` |
| new `<dataDir>/runtime/` file | `check-runtime-manifest-closure` |
| generated files (regen set) | `regen-all --check` |
| any `package-lock.json` | `check-lockfile-completeness` · `regen-all --check --only notices` · `node scripts/dev/generate-dev-mcp-runtime.mjs --check` |
| NSIS hooks · tauri bundle resources · sidecar staging | `check-update-preserves-models` |
| `SSOT/catalogs/**` · analyzers schema · `adapters-lucene/**` | `check-language-agnostic-analysis` |
| `config/pmd/**` | `check-pmd-ruleset-sync` |
| `docs/tempdocs/**` | `check-tempdoc-numbers` · `check-tempdoc-size` |
| `docs/{explanation,reference,how-to,decisions}/**` | `docs-validate` |
| indexing-job lifecycle surfaces | `--gate operation-surface` |
| `CoreSurfaceCatalog.java` / surface `altitude` | `--gate surface-altitude` |
| `governance/logic-seams.v1.json` or a registered seam | `check-logic-seams --mode gate` |
| guard-string register (`execution-surfaces`/`operation-surfaces`) | `--gate register-guard-resolution` |
| `LifecycleReasonCode.java` / `readinessNotice.ts` | `check-readiness-reason-codes` |
| `scripts/dev/justsearch-dev-mcp/**` · `scripts/dev/justsearch-dev-mcp.mjs` | `check-dev-mcp-doc-sync` |
| `StoreCatalog.java` · store construction sites · `governance/store-{recoverability,corruption-policies}.v1.json` | `check-store-recoverability` |
| `modules/ui-web/src/**` | `node scripts/ci/run-ui-web-gates.mjs` |
| ui-shot harness · new RAIL surface | `check-ui-step-coverage` |
| `scripts/agent-analytics/**` | `node scripts/agent-analytics/run-all-tests.mjs` |
| `scripts/**` · `packaging/**` js · `*.ps1` | `npm run lint:scripts` · `check-ps1-warning-comments` |

Frontend: from `modules/ui-web`, `npm run typecheck` and `npm run test:unit:run`.

Java: run `node scripts/dev/java-prepush.mjs` for test and integration-test compilation,
affected modules' integration tests (including transitive dependents in the hosted module tier),
and all PMD checks. When available on PATH, `js-gradle-slot` (the maintainer machine's
shared-build-slot tool) runs the Gradle wrapper; otherwise the command prints a
notice and runs `./gradlew` (`gradlew.bat` on Windows) directly. Add `--unreferenced` when replacing or
overloading a non-public method to include `UnreferencedCodeTest` in the same Gradle
invocation. Use `--dry-run` to print the command. This check is run manually; the
pre-push hook does not run it.

## Publication and main verification

For CI selection, publication and post-merge verification, follow [ADR-0053 § Decision](../../decisions/0053-one-pr-ci-run-and-squash-auto-merge.md#decision)
and its [Measured axe amendment](../../decisions/0053-one-pr-ci-run-and-squash-auto-merge.md#amendment-2026-10-24-measured-axe-becomes-required).
