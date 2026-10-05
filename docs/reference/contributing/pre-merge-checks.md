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
Hosted CI runs most of them again on the pull request and in the merge queue
([ADR-0044](../../decisions/0044-public-hosted-ci-fact-lanes.md)). This table moved here from
the retired root `CLAUDE.md` when JustSearch adopted agent-system (2026-10-05).

Always: `./gradlew.bat build -x test` from the candidate worktree.

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
| `justsearch-dev-mcp/**` | `check-dev-mcp-doc-sync` |
| `StoreCatalog.java` · store construction sites · `governance/store-{recoverability,corruption-policies}.v1.json` | `check-store-recoverability` |
| `modules/ui-web/src/**` | `node scripts/ci/run-ui-web-gates.mjs` |
| ui-shot harness · new RAIL surface | `check-ui-step-coverage` |
| `scripts/agent-analytics/**` | `node scripts/agent-analytics/run-all-tests.mjs` |
| `scripts/**` · `packaging/**` js · `*.ps1` | `npm run lint:scripts` · `check-ps1-warning-comments` |

Frontend: from `modules/ui-web`, `npm run typecheck` and `npm run test:unit:run`.
