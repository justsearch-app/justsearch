# Maintaining JustSearch — the agent-driven development process

JustSearch is developed in the open with AI coding agents (Claude Code and OpenAI Codex) under a deliberate
discipline system. **This document is for maintainers. Contributors do not need it** — to
contribute, see [`CONTRIBUTING.md`](CONTRIBUTING.md) (clone → build → test). None of the machinery
below is required, and a fresh clone does **not** impose it on you.

We publish the machinery rather than hide it — for transparency, and because the
agent-discipline system is part of what JustSearch is. The principle is **present but opt-in**:
the apparatus is visible and documented, never forced on a contributor.

## The machinery (all published; none required to contribute)

- **`AGENTS.md`** — compact cross-harness instructions loaded by Codex and other compatible agents.
- **`CLAUDE.md`** — Claude-specific delivery; its hard-invariant block is generated from `AGENTS.md`.
- **`.claude/rules/`** — always-loaded discipline rules: general engineering discipline plus the
  maintainer-only operational rules listed below.
- **`.claude/skills/`** — task-specific playbook authority. `.agents/skills/` is the committed,
  generated Codex projection.
- **`.codex/`** — repository-safe Codex MCP config, generated hooks, and native agent roles.
- **`governance/`, `gates/`, `scripts/{governance,ci}/`** — the discipline-gate kernel (CI checks).
- **`scripts/agent-analytics/`** — the discipline hooks (guards + hints) plus maintainer
  telemetry/analytics tooling.

## Maintainer-only operational setup (contributors can skip)

- **Agent work** — JustSearch uses agent-system: the projected `AGENTS.md` entry in this repository,
  plus private project knowledge, task records and role contracts in the maintainer's agent-system
  private root. The former agent layer (`CLAUDE.md`, `.claude/rules`, skills, Codex agent roles and
  the hook manifest) was retired at adoption on 2026-10-05; its history is in
  [`docs/reference/contributing/agent-guide.md`](docs/reference/contributing/agent-guide.md) and the
  superseded contributor pages.
- **Shared local dev stack** — one local backend at a time, with an ownership/lease handshake through
  the `justsearch-dev` MCP server. `.mcp.json` registers it for Claude Code (seed it with
  `cp .mcp.json.example .mcp.json`; no secret required) and `.codex/config.toml` registers it for
  Codex. GitHub work goes through the `gh` CLI (`scripts/dev/run-gh.mjs`), not an MCP server.
- **Parallel worktrees** — each agent session works in its own worktree under
  `.claude/worktrees/`, created and released through `node scripts/dev/worktree-lifecycle.cjs`
  ([`common-workflows.md`](docs/reference/contributing/common-workflows.md) §Worktree mechanics).
- **Telemetry** — local-only OpenTelemetry capture of agent sessions, for measuring
  agent-assisted development. It never leaves the machine.

## Succession and emergency handover

This is a public custody map, not a credential store. Follow the
[`cut-a-release` runbook](docs/how-to/cut-a-release.md) for release execution and key-rotation
constraints; do not duplicate those procedures here. Never commit credential values, personal
emails, account IDs, private URLs, recovery codes, or the contents of the private recovery package.
Every bracketed field below is deliberately founder-fillable and limited to an identity, role,
active provider or mode, custodian, or private location class.

| Responsibility | Named service or surface | Minimum successor role | Active credential/provider mode | Repository secret and variable names | Recovery or rotation checkpoint | Custodian |
|---|---|---|---|---|---|---|
| GitHub organization ownership | `justsearch-app` organization | [FOUNDER TO FILL: minimum successor role] | GitHub organization ownership | None | Confirm the successor can reach organization ownership and recovery settings. | [FOUNDER TO FILL: custodian identity] |
| Repository administration and releases | `justsearch-app/justsearch`, Actions, and GitHub Releases | [FOUNDER TO FILL: minimum successor role] | GitHub repository administration | None | Confirm repository administration, Actions, and release access without changing policy. | [FOUNDER TO FILL: custodian identity] |
| Ruleset and merge-queue custody | `main-merge-queue` ruleset, required checks, and merge queue | [FOUNDER TO FILL: minimum successor role] | GitHub ruleset administration | None | Confirm the successor can inspect and administer the ruleset and its bypass custody. | [FOUNDER TO FILL: custodian identity] |
| Release-asset repository | `justsearch-releases` | [FOUNDER TO FILL: minimum successor role] | [FOUNDER TO FILL: active repository access mode] | None | Confirm access to upload the signed mirrors and native packs named by the release runbook. | [FOUNDER TO FILL: custodian identity] |
| npm ownership and 2FA | npm scope `@justsearch`; packages `@justsearch/plugin-api` and `@justsearch/runtime-client` | [FOUNDER TO FILL: minimum npm role] | [FOUNDER TO FILL: active npm ownership and 2FA mode] | No repository secret or variable is currently declared. | Confirm package/scope ownership and 2FA recovery; rotate any future publish credential through npm and never store it in Git. | [FOUNDER TO FILL: custodian identity] |
| Windows Authenticode signing | `build-installer.yml`, `sign-vendored-mirrors.yml`, and the configured signing provider | [FOUNDER TO FILL: minimum release-signing role] | [FOUNDER TO FILL: active signing provider and `pfx`, `store`, or `command` mode] | Secrets: `JUSTSEARCH_CODESIGN_MODE`, `JUSTSEARCH_CODESIGN_PFX_B64`, `JUSTSEARCH_CODESIGN_PFX_PASSWORD`, `JUSTSEARCH_CODESIGN_THUMBPRINT`, `JUSTSEARCH_CODESIGN_TIMESTAMP_URL`, `JUSTSEARCH_CODESIGN_COMMAND`. Variable: `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED`. Local/script controls: `JUSTSEARCH_CODESIGN_PFX_PATH`, `JUSTSEARCH_CODESIGN_STORE`, `JUSTSEARCH_REQUIRE_SIGNING`. | Rotate at the provider and repository, then verify a signed non-tag candidate as directed by the release runbook. `JUSTSEARCH_CODESIGN_ALLOW_UNTRUSTED` is rehearsal-only and must be unset in production. | [FOUNDER TO FILL: custodian identity] |
| Tauri updater artifact signing | Updater signature emitted by the release workflow | [FOUNDER TO FILL: minimum updater-signing role] | [FOUNDER TO FILL: active updater key-storage/signing mode] | Secrets: `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY`, `JUSTSEARCH_TAURI_UPDATER_PRIVATE_KEY_PASSWORD`. Workflow aliases: `TAURI_SIGNING_PRIVATE_KEY`, `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`. Variables: `JUSTSEARCH_UPDATE_ARTIFACT_PUBLIC_KEY`, `JUSTSEARCH_UPDATE_ARTIFACT_KEY_ID`. | Follow the release runbook; trust-root replacement requires a designed bridge/dual-root release, not an ordinary variable edit. | [FOUNDER TO FILL: custodian identity] |
| Authenticated release metadata and update endpoint | `release.v1.json`, metadata trust root, and descriptor endpoint | [FOUNDER TO FILL: minimum release-metadata role] | [FOUNDER TO FILL: active metadata key-storage/signing mode] | Secret: `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PEM`. Variables: `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PEM`, `JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY`, `JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID`, `JUSTSEARCH_RELEASE_DESCRIPTOR_URL`. Workflow-local paths: `JUSTSEARCH_RELEASE_METADATA_PRIVATE_KEY_PATH`, `JUSTSEARCH_RELEASE_METADATA_PUBLIC_KEY_PATH`. | Follow the release runbook and preserve the authenticated update chain; never rotate a trust root as a one-variable change. | [FOUNDER TO FILL: custodian identity] |
| CLA administration | `cla.yml` and the `cla-signatures` branch | [FOUNDER TO FILL: minimum CLA-administration role] | Built-in `GITHUB_TOKEN`; `PERSONAL_ACCESS_TOKEN` is an optional compatibility path, not a current requirement. | `GITHUB_TOKEN`; optional secret `PERSONAL_ACCESS_TOKEN`. | Confirm signature-branch access; rotate or remove the optional PAT through GitHub if that compatibility path is enabled. | [FOUNDER TO FILL: custodian identity] |
| Upstream model accounts and terms | `model-registry.v2.json` sources and their upstream terms pages | [FOUNDER TO FILL: minimum model-distribution role] | [FOUNDER TO FILL: active upstream provider/account mode] | No upstream-model credential secret or variable is referenced by the tracked repository. | Confirm account recovery and current redistribution terms for every provider before changing a registry source. | [FOUNDER TO FILL: custodian identity] |
| Private recovery package | Account-recovery and emergency-handover material; contents stay outside Git | [FOUNDER TO FILL: minimum emergency-recovery role] | [FOUNDER TO FILL: active protection/access mode] | None; never commit it. | After any custody or credential rotation, update the package and re-check authorized recovery access. | [FOUNDER TO FILL: custodian identity] |

Private recovery-package location class: [FOUNDER TO FILL: private location class only; never a
path, account ID, or URL].

Run a succession dry run twice each year:

1. A second maintainer identifies every service, current custodian, and required role above without
   relying on the founder's active session.
2. Check the presence and intended scope of every named secret and variable without reading or
   copying its value; also confirm npm ownership, 2FA recovery, and model-provider terms access.
3. Rotate one non-production credential and confirm the corresponding recovery record was updated.
4. Run the existing non-tag branch candidate dispatch according to the release runbook; do not
   publish the candidate.
5. Locate the private recovery package and prove authorized access without copying its contents into
   the repository or dry-run record.

## Public main publication

Public `main` is a curated project-history surface, not the transcript of an
agent branch. Normal maintainer and agent work happens in branches or worktrees,
review happens in pull requests, and the merge result on `main` is one edited
squash commit.

Before merging a PR, make the PR title and body suitable as the public commit
title and body. Keep that body focused on the durable reason and observable
outcomes. Put mutable scope/risk, verification evidence, and review state in one
managed PR comment created from `.github/pr-review-record-template.md`. Branch
checkpoint commits, investigation commits, retry commits, and review transcripts
stay out of the public commit.

Use the default publication path for ordinary work, grouped Dependabot updates,
and tempdoc-heavy agent work:

1. Work on a branch or isolated worktree.
2. Open a PR and let the public CI fact lanes report.
3. Edit the PR title/body into the intended public commit message.
4. Dry-run and then confirm one exact managed review-comment upsert:

   ```powershell
   node scripts/ci/pr-review-record.mjs upsert --pr <number> --file <review-file>
   node scripts/ci/pr-review-record.mjs upsert --pr <number> --file <review-file> --execute --confirm <fresh-sha256>
   ```

5. After the final push and review update, run the strict comment check and the
   public squash preview:

   ```powershell
   node scripts/ci/pr-review-record.mjs check --pr <number>
   node scripts/ci/preview-squash-message.mjs --repo justsearch-app/justsearch --pr <number>
   ```

6. Enter the squash merge queue after the required checks are green.
7. Let GitHub delete the source branch after merge.

The review-record command owns its hidden PR/head/body marker, paginates the PR
conversation, refuses duplicate or foreign-owned managed comments, and is a
dry-run unless `--execute` carries the current fingerprint. Run the upsert again
after any head or public-body change. GitHub comment updates have no conditional
compare-and-swap, so the authenticated comment owner must be the sole writer
from dry-run through exact read-back; do not edit the managed comment concurrently.
Never place rich review text temporarily in the PR body or restore it after
enqueue. When checking the landed commit, compare durable content and absence of
review-only material; GitHub may reflow long lines or append its own co-author
material.

Rare non-squash publication is a maintainer exception, not a standing lane. Use
it only when the intermediate commits are themselves durable public review units
or the branch topology has independent public meaning. Record the reason in the
PR before changing repository settings for the exception.

The expected repository settings are declared in
`scripts/ci/repo-history-policy.v1.json`. Maintainers can verify live settings
with:

```powershell
node scripts/ci/check-repo-history-policy.mjs --repo justsearch-app/justsearch --branch main
```
