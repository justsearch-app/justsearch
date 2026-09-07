<!-- budget: always-loaded; cross-harness authority for Codex and other AGENTS.md clients. -->

# JustSearch agent instructions

This file is the shared project contract for Codex CLI, the ChatGPT desktop
app, the Codex IDE extension, and other agents that support `AGENTS.md`.
Claude Code also has harness-specific delivery in `CLAUDE.md` and
`.claude/rules/`; where wording differs, preserve the invariant and use the
mechanism native to the active harness.

Canonical entry points: `docs/llms.txt` for documentation and
`docs/tempdocs/` for active work. Tempdocs are dated history, not current truth.

## Hard invariants

1. **Application code never touches Lucene.** Index I/O belongs to the index
   half, via a port (ADR-0049).
2. **Preserve the local API trust boundary.** Bind to loopback, enforce the Host
   allowlist, validate MCP Origin, and require the per-boot mutation token where
   ADR-0046 requires it.
3. **Do not resurrect legacy endpoints.** `/api/search` and `/api/settings` are
   removed contracts.
4. **Verify, do not guess.** Use `/api/debug/state` and `/api/health` for
   lifecycle state and `/infra/capabilities` for `host.*` contract versions.
5. **The frontend is Lit, not React.** The active UI is the `shell-v0`
   web-components stack.
6. **Search analysis is locale-invariant.** Do not add per-language analyzers,
   fields, stopwords, spelling dictionaries, or curated synonym authorities.
   The multilingual model stack supplies multilingual behavior.

## Start every substantial task

1. Read this file, `docs/llms.txt`, and the relevant canonical documentation.
2. Run `node scripts/agent-analytics/world-state.mjs` before choosing a tempdoc
   number, worktree, shared dev stack, or concurrent lane.
3. Inspect the owning module and neighboring implementations before creating a
   new helper, representation, registry, or schema.
4. For non-trivial implementation, create or adopt an active tempdoc and treat
   every checked item as part of the acceptance contract.

Codex users may invoke `$justsearch-start` for this orientation. Claude users
may invoke `/start`. A named Claude-only tool or command is never an executable
instruction in Codex; use the equivalent Codex capability.

## Implementation discipline

- Fix root causes. Never hide a failure by deleting code, weakening a test,
  suppressing a warning, broadening a catch, or removing validation.
- Treat a failing test after your change as evidence against the change. Ask
  before changing a test whose intent you believe is wrong.
- Before creating a second representation of existing data, find its register
  or source of truth and decide whether the new form is a projection or an
  intentional fork.
- Interrogate experiments and benchmarks. Establish why a result occurred,
  including expected-looking results, before using it as evidence.
- Keep changes scoped. Do not reformat unrelated files or modify another
  session's uncommitted work.
- A structural silent-failure class is actionable after one proven incident;
  do not defer it behind an invented recurrence threshold.
- When replacing a feature or workflow, sweep its code, configuration, gates,
  baselines, ignore entries, and docs in the same change.
- Load the relevant skill. `.agents/skills` and `.claude/skills` are the manual
  Codex and Claude authorities. Review both when shared behavior changes.

## Worktrees and git safety

The main checkout stays on `main`. Feature work happens in a dedicated
worktree and branch; never share a worktree between sessions.

- Do not switch branches, reset hard, clean, or restore the whole tree in the
  main checkout.
- Never delete, move, or restore files in the main checkout that you did not
  create. They may belong to another active session.
- Never force-push anywhere.
- After creating or resuming a worktree, verify its directory, branch, and base
  before editing.
- Stage explicit paths, never `git add -A`, when other work may be present.
- Implementing work does not authorize merging or publishing. Obtain explicit
  per-action authorization before opening/merging a PR or pushing a release.
- Squash-merged work must be checked by content diff, not branch ancestry.

Full worktree and publication rules: `.claude/rules/branch-safety.md` and
`docs/reference/contributing/agent-guide.md`. Those documents describe policy;
translate Claude-specific command names to the active harness.

## Delegation

Delegate only bounded work with a self-contained brief, acceptance criteria,
constraints, and a requirement for primary-source `file:line` evidence.
Exploration, independent research, review, and separable implementation chunks
are suitable. Shared-state changes, migrations, destructive git, merge/release
work, and unsupervised dev-stack ownership are not.

Codex roles: `explorer`/`worker` (Luna/high),
`complex_worker` (Sol/medium), and `reviewer` (Sol/high). Set `fork_turns` to
`"none"` or a positive integer; omitted/`"all"` inherits the parent model and
effort and bypasses role pins. Workers return escalation evidence to the parent.
System or session restrictions on delegation override repository preferences.

## Shared development stack

Only one JustSearch dev stack and one Gradle build may run at a time across
agents. Before starting the stack, use the `justsearch-dev` MCP `quick_health`
tool. Do not take over a conflicting lease without explicit user direction.
Declare an adequate lease for long work and stop an owned stack when finished.
Never kill registered helper processes directly; use the repository sweep
mechanism so identity and ownership are checked.

Codex, desktop, and IDE clients obtain the project MCP server from
`.codex/config.toml`. Claude obtains it from `.mcp.json`. If `justsearch-dev` is
not visible, fix the client configuration rather than bypassing its ownership
contract with ad-hoc process commands.

## Verification

Do not declare completion while relevant checks are red.

- Compilation: `./gradlew.bat build -x test`
- Multi-module changes: `./gradlew.bat test`
- Affected module: `./gradlew.bat :modules:<module>:test`
- Frontend: from `modules/ui-web`, run `npm run typecheck` and
  `npm run test:unit:run`
- Agent/governance changes: run the subject-specific Node checks documented in
  `CLAUDE.md` and `docs/reference/contributing/common-workflows.md`

For AI-facing behavior, use every available tier: compile/unit tests, live API
tests, then a real model query. `AI_OFFLINE` is not end-to-end verification when
the development tooling can activate the local model. For audit-driven fixes,
add a runnable regression test; a static audit is only a hypothesis.

After non-trivial control-flow or governance changes, perform a critical pass:
confirm the gate fires in the intended scenario, independently re-read the
evidence behind the change, and ensure the test cannot pass for the wrong
reason.

## Prompt and tooling surfaces

- `AGENTS.md`: shared, compact project policy.
- `CLAUDE.md` and `.claude/rules`: Claude-specific delivery and legacy routing.
- `.agents/skills`: manually maintained Codex project skills.
- `.claude/skills`: manually maintained Claude Code project skills.
- `governance/agent-hooks.v1.json`: single hook policy and binding authority.
- `.codex/hooks.json`: generated Codex hook projection; review and trust changes
  with `/hooks` before they run.
- `.codex/config.toml`: shared project Codex/MCP settings. Never commit secrets.
- Exact MCP schemas come from server implementation and contract tests, not
  copied prose.

Codex project hooks normalize `apply_patch`, shell, MCP, and subagent calls into
the shared policy layer. If a hook blocks or redirects an operation, follow its
remedy instead of retrying the same call. `JUSTSEARCH_DISABLE_HOOKS=1` remains
the recovery kill switch.

## Documentation ownership

Canonical documentation under `docs/explanation`, `docs/reference`,
`docs/how-to`, and `docs/decisions` must match shipped behavior. Update it in
the same change when a governed behavior changes. Regenerate derived docs and
skills rather than editing generated regions directly.

Use `docs/reference/contributing/agent-prompt-surface-governance.md` for the
ownership map and `docs/reference/contributing/common-workflows.md` for exact
regeneration commands.
