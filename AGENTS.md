<!-- budget: always-loaded; shared policy authority. -->

# JustSearch agent instructions

Shared policy for Codex and Claude Code; generated into `CLAUDE.md`.
Use native tools; specific guidance lives outside the shared block.

Use `docs/llms.txt`. Verify dated tempdoc claims
against canonical docs and code.

## Hard invariants

1. **Head never touches Lucene.** All index I/O belongs to the Worker and is
   reached through gRPC.
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

## Task execution

Continue authorized work after checkpoints, commits, reviews, merges, and status
answers. Stop only at scope completion, user pause/handoff, or when no useful
work can proceed without an external dependency. Session-closeout does not
create a stopping point. Preserve decisions and authorization across compaction;
a pending approval blocks only dependent actions. Distinguish platform
interruptions from voluntary stops. Explanations are not automatically handoffs.

An explicitly authorized migration can supersede a named shipped architecture
rule within its assigned scope. Record the target, superseded rule, and proof in
the governing design; trust boundaries and permissions still apply.

## Start every substantial task

Read this file and relevant canonical docs. Run
`node scripts/agent-analytics/world-state.mjs` before selecting a tempdoc number,
worktree, shared stack, or concurrent lane. Inspect the owning module and nearby
implementations before creating helpers, registries, schemas, or representations.
Adopt an active tempdoc for non-trivial implementation; every acceptance item is
part of the contract. Orientation: Codex `$justsearch-start`, Claude `/start`.
Translate tool-specific commands to the active harness.

## Implementation discipline

- Fix root causes; never hide failure by deleting validation, weakening tests,
  suppressing warnings, or broadening catches. If a failing test's intent seems
  wrong, explain why and ask before changing it.
- Find the source of truth before introducing another representation. Decide
  whether the new form is a projection or an intentional fork.
- Establish why results occurred, including expected-looking results.
- Keep changes scoped and preserve other sessions' work.
- A proven structural failure is actionable without a recurrence threshold.
  Before adding a state machine, persistent marker, writer, or cross-language
  contract, compare simpler ownership and record the trade-off. A real defect
  establishes the need for a fix, not its first proposed mechanism's scope.
- Retire superseded code, configuration, gates, baselines, ignore entries, and
  docs in the same change. Load relevant skills; review both `.agents/skills`
  and `.claude/skills` when shared behavior changes.

## Worktrees and git safety

The main checkout stays on `main`; use a dedicated worktree and branch per
session. Verify directory, branch, and base before editing. Never switch branches,
reset hard, clean, restore the whole tree, or delete/move/restore others' files in
main. Never force-push. Stage explicit paths, not `git add -A`.

Implementation does not authorize publication or merging. Require explicit
per-action authorization before opening/merging a PR or pushing a release;
preserve authorization already given within its stated scope. Check squash-
merged work by content diff, not ancestry. Details:
`docs/reference/contributing/agent-guide.md`, `.claude/rules/branch-safety.md`.

## Delegation

Delegate bounded work with a stable deliverable, assigned files/worktree,
constraints, acceptance checks, and primary-source `file:line` evidence. Scope
growth or new lifecycle/concurrency/ownership ambiguity returns to the parent
before implementation continues. Consolidate feedback; after two substantive
correction rounds, reassess the brief, design, and owner. Never waive defects.
Exploration, review, and separable implementation are suitable. Shared-state changes, migrations, destructive git, merge/release
work, and unsupervised dev-stack ownership are not.

Codex roles: `explorer`/`worker` (Luna/high),
`complex_worker` (Sol/medium), and `reviewer` (Sol/high). Set `fork_turns` to
`"none"` or a positive integer; omitted/`"all"` inherits the parent model and
effort and bypasses role pins. Workers return escalation evidence to the parent.
System or session restrictions on delegation override repository preferences.

## Shared development stack

Only one dev stack and one Gradle build may run across agents. Before starting,
use `justsearch-dev` MCP `quick_health`; never take over a conflicting lease
without explicit user direction. Declare adequate leases and stop owned stacks
when finished. Use the repository sweep to check process identity and ownership;
never directly kill registered helpers.

Codex MCP config is `.codex/config.toml`; Claude uses `.mcp.json`. If the server
is unavailable, fix client configuration instead of bypassing stack ownership.

## Verification

Reconcile every acceptance item with result, tested revision, required environment,
and accessible evidence before claiming completion. Distinguish implementation,
local proof, hosted proof, and authorized deferral. CI wiring is not a successful
run. Name advisory failures and platform gaps; a deferral needs a decision and
destination. Required red or unperformed checks prevent completion.

- Compile: `./gradlew.bat build -x test`
- Multi-module: `./gradlew.bat test`
- Affected module: `./gradlew.bat :modules:<module>:test`
- Frontend, from `modules/ui-web`: `npm run typecheck`, `npm run test:unit:run`
- Agent/governance: subject-specific Node checks in `CLAUDE.md` and
  `docs/reference/contributing/common-workflows.md`

AI-facing behavior needs compile/unit, live API, and a real model query;
`AI_OFFLINE` is insufficient when model activation is available. Compact profile
proves plumbing; quality verification requires standard. Audit-driven fixes need
runnable regressions. For non-trivial control-flow/governance changes, confirm
the gate fires, independently re-read evidence, and refute wrong-reason passes.

Run focused checks during implementation and integrated checks at coherent
boundaries; start required hosted/platform checks early when authorized. Reuse
results only while revision and assumptions still apply. Preserve suite output
before targeted reruns overwrite it. Keep summaries/commands in Git and bulky
artifacts at an accessible location with retention limits; hashes are not access.

## Prompt, tooling, and documentation ownership

`AGENTS.md` is shared policy; `CLAUDE.md` contains its generated projection.
Each harness's rules/skills own scoped guidance. `governance/agent-hooks.v1.json`
owns hook policy; `.codex/hooks.json` is generated (review/trust with `/hooks`).
Never commit secrets to shared config. MCP schemas live in code/contract tests.
Follow blocking hooks' remedies; `JUSTSEARCH_DISABLE_HOOKS=1` is recovery only.
Instruction loading, conversation inheritance, and hook execution are separate;
verify delivery instead of trusting a subagent's self-report.

Canonical docs must match shipped behavior. Update them with governed changes;
regenerate derived docs and skills. Keep current decisions in owning sections,
with a short dated index linking rationale. Handoffs name current state and next
steps without requiring private transcripts or replay of amendments.

References under `docs/reference/contributing/`: `agent-workflow.md` (execution
and evidence), `agent-prompt-surface-governance.md` (ownership), and
`common-workflows.md` (regeneration).
