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

Always: `node scripts/lib/tool-invoker.cjs gradle build -x test` from the candidate worktree.

| Edited subject | Check(s) |
|---|---|
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

## Publication and main verification

`node scripts/dev/run-gh.mjs enqueue N` validates the squash preview and managed review
record, then enables squash auto-merge bound to the validated head, title and body. A new push
requires a new enqueue. The PR runs the union of lanes that consume its changed files; all
required names report, including intentional skips. Unknown inputs run every lane. Strict stays
off, so an older green base needs no update push. There is no merge queue.

After merging, run `node scripts/dev/run-gh.mjs run-wait-sha <merge commit>`. Main runs the full
suite, including both integration tiers (system-tests and module), Windows stop-census, supervisor
conformance and Measured axe. The module tier runs app-api, app-services, indexing, reranker and ui
integration tests on Linux. Both integration tiers run only on main pushes and manual dispatch;
neither is a required PR check.
Its running CI is never cancelled; only the newest pending commit runs, and the wait command
follows a newer run that covers the requested commit. If main fails in your change's area, open
a fix or revert PR at once through the normal path. Report a failure in a test listed in
`scripts/ci/pr-flakes.tsv`; do not revert for that listed flake.
