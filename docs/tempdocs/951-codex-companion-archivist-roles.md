---
title: "951 — Codex Companion and Archivist roles; anomaly-only thread cap"
type: tempdocs
status: implementing
created: 2026-09-10
updated: 2026-09-10
lane: agent tooling / Codex subagents
related:
  - 920-codex-cli-dual-harness-migration
  - 937-codex-agent-model-routing
  - 950-agent-workflow-contract
---

# 951 — Codex Companion and Archivist roles; anomaly-only thread cap

## Goal

Extend the Codex subagent role vocabulary with a persistent read-only
**Companion** (supporting-context specialist) and a docs-only **Archivist**
(closure and handoff writer), and reclassify `max_concurrent_threads_per_session`
from a workflow instrument to an anomaly guard. Keep the existing ownership,
verification, publication, and shared-resource rules authoritative.

## Owner decisions (2026-09-10)

1. The thread cap does not shape workflow. It exists only as a ceiling against
   runaway spawning. Set it to **10**.
2. Add **Companion** and **Archivist** as first-class roles alongside
   `explorer`, `worker`, `complex_worker`, and `reviewer`.
3. Design first; research any uncertain aspect before implementing.

## Provenance of the current state

- The cap `max_concurrent_threads_per_session = 3` was written 2026-09-03 11:04Z
  in the single `apply_patch` that created the Codex harness (tempdoc 920,
  PR #623, commit `4de6f20d0`). No reason was recorded in the tempdoc, PR body,
  how-to, `AGENTS.md`, or the session transcript. The Codex docs the session had
  fetched use 6 and 8 in their examples; 3 most plausibly mirrored the Claude
  guidance "up to 3-4 agent sessions run concurrently", which is about worktree
  sessions, not Codex child threads. It was an undefended default.
- Roles and pins were made explicit by tempdoc 937 (2026-09-06).
- An external assessment supplied by the owner (2026-09-10) compared the
  `viettran-edgeAI/codex_workflow` Heavy route against this harness and against
  five real child/main rollouts. Its repo-specific claims were verified in this
  session; see "Research findings".

## Research findings

### R1. Codex in-client multi-agent contract (primary source: developer message recorded in the 920 rollout, client 0.153.x)

- Tools available to the parent: `spawn_agent`, `followup_task`, `send_message`,
  `wait_agent`, `interrupt_agent`, `list_agents`. **There is no `close_agent`.**
  A spawned agent stays open until the session ends; "persistent" costs nothing
  extra to arrange and cannot be undone mid-session.
- Slot wording: "There are N+1 available concurrency slots, meaning that up to
  N+1 agents can be active at once, including you" where N is the configured cap.
  The docs say the cap counts "concurrently open spawned-agent threads". Whether
  an idle-but-open thread counts is not documented. With the cap at 10 this
  ambiguity stops mattering for normal work.
- "Full-history forks (`fork_turns` omitted or `"all"`) inherit the parent model
  and reasoning effort and do not accept overrides." A positive-integer
  `fork_turns` does accept the role pin. This is why the author's Archivist uses
  `fork_turns="200"` and still gets Luna.
- Subagents share the filesystem and cwd with the parent. Context isolation is
  not filesystem isolation (already stated in `agent-workflow.md`).

### R2. Sandbox precedence: a role file cannot set the child's sandbox (resolved 2026-09-10)

- **Source of truth is the client code.** `openai/codex` PR #39299 "Restrict
  agent roles to bounded configuration overrides" (2026-08-18, in every 0.153.x
  build) rewrote `codex-rs/core/src/agent/role.rs`. Its header: "Roles may
  customize the child or reduce its capabilities, but never replace the parent
  session's authority." The role layer now carries only
  `developer_instructions`, `model`, `model_reasoning_effort`, and the ability
  to *disable* features, skills, and plugins. Tests:
  `apply_role_preserves_parent_sandbox_permissions` (even a narrowing
  `[sandbox_workspace_write]` in the role file leaves `config.permissions`
  equal to the parent's), `apply_role_cannot_expand_parent_authority`
  (`sandbox_mode`/`approval_policy`/`notify` in a role file are dropped), and
  `spawn_agent_reapplies_runtime_sandbox_after_role_config` in
  `multi_agents_tests.rs` ("role config must discard the runtime permission
  override before it is reapplied").
- **Docs.** `learn.chatgpt.com/docs/agent-configuration/subagents` now says
  "Codex also reapplies the parent turn's live runtime overrides when it spawns
  a child … even if the selected custom agent file sets different defaults."
  Its older sentence about overriding "the sandbox configuration for individual
  custom agents" predates #39299 and is stale.
- **Observation on this machine (361 rollouts, clients 0.151 to 0.153.4).**
  Every one of the 160 role-tagged children (`explorer` 60, `reviewer` 48,
  `worker` 29, `complex_worker` 9, other 14) recorded
  `sandbox_policy.type = danger-full-access`, `approval_policy = never`, equal
  to its parent. Zero children ever recorded `read-only`. The three
  `read-only`/`workspace-write` sessions on disk are all parents.
- **Answer to the owner's question.** The global `danger-full-access` does
  apply to every subagent, and there is no per-agent lever in the current
  client: a child's sandbox and approval policy are always the parent turn's.
  The only way to give children a stricter sandbox is to run the *parent* turn
  stricter (desktop permission mode or `sandbox_mode` in `~/.codex/config.toml`
  set to `workspace-write`), which then also binds `worker`/`complex_worker`.
- **Consequence for the harness.** The `sandbox_mode` keys in the four existing
  role files and the parity gate's "explicitly sandboxed" check assert intent
  that the client ignores. Keep the keys as declared intent (harmless, and the
  format is forward-compatible), but the how-to and `agent-workflow.md` must
  say plainly that they are not enforced, and the parity-check label changes to
  "declared". The Companion's read-only contract lives in its
  `developer_instructions`, which the client does deliver.

### R3. The author's roles (verbatim source: `codex_workflow/agents/{companion,archivist}.toml` @ `6d9b06f`)

| | Companion | Archivist |
|---|---|---|
| model / effort | `gpt-5.6-luna` / `xhigh` | `gpt-5.6-luna` / `xhigh` |
| sandbox | read-only | workspace-write |
| purpose | "Persistent read-only project-context companion, secretary, and context-load reducer for the main agent." | "Documentation editor and deployment handoff worker for verified project knowledge." |
| cardinality | exactly one per session after first Medium/Heavy entry; reused across route changes | one owns closure; more may take non-overlapping doc assignments |
| protocol | task capsule (Task ID, Project Context Scope, Context Task + Goal, Main-Agent Context Guidance); follow-ups repeat the Task ID and send only changed fields; "should not be used for tiny lookups, repeated broad summaries, or status-only requests because every rollout reloads its retained history" | capsule (Documentation Context + Audience, Task + Goal, Guidance); spawned with `fork_turns="200"`; maintains `agent_docs/` (overview, core_tech, structure, module docs); never touches the three main-owned files; runs `$deployment-token-report` once; returns handoff + verbatim token table |
| explicitly not | "direction, architecture, causal and acceptance decisions, implementation, worker allocation, integration, final claims, and user communication" | production source, tests, Git state |

### R4. Ledger accounting gaps (verified against `scripts/agent-analytics/lib/ledger/codex-adapter.mjs:344-393`)

- The adapter consumes only `event_msg/token_count`. The native
  `token_usage_record` type (one per model response, keyed by response id) is
  not read anywhere in `scripts/` or `docs/`.
- Sample A (explorer, 38 responses) contains a compaction usage record of
  234,478 input / 232,192 cached / 4,350 output with no matching `token_count`.
  The adapter therefore under-reports that session by exactly that amount.
- The adapter's repeat filter (`A2`, line 355) correctly drops the duplicate
  notification that the author's `report_tokens.py` double-counts (sample B,
  +6.63% input). Importing the author's reporter would be a regression.
- Consequence for this design: the Archivist's usage summary must come from the
  deterministic ledger, and the ledger must first read native usage records.

### R5. Repository touchpoints for adding a role

| Surface | Current state | Change needed |
|---|---|---|
| `.codex/config.toml` | cap 3, Luna/high defaults | cap 10 with an "anomaly guard" comment |
| `.codex/agents/*.toml` | 4 roles | + `companion.toml`, `archivist.toml` |
| `scripts/ci/check-codex-agent-parity.mjs:60-83` | hard-codes the 4 role filenames and pins | extend `expectedRouting`; assert cap value and comment; assert Companion/Archivist instruction markers |
| `AGENTS.md:91-95` | role sentence; file is 8286 of 8296-byte ceiling | one added sentence needs `check-always-loaded-budget.mjs --bump AGENTS.md --reason …` or an equal trim |
| `CLAUDE.md` shared block | generated from `AGENTS.md` | `node scripts/docs/agent-instructions-sync.mjs` |
| `docs/how-to/use-codex-for-development.md:89-108` | role table | two rows + Companion/Archivist usage subsection |
| `docs/reference/contributing/agent-workflow.md` "Keep delegation bounded" | disposable-child model | add a "persistent helper" paragraph: revision-aware, stale-state, no authority |
| `scripts/agent-analytics/hooks/codex-hook-adapter.test.mjs:192` | routes SubagentStart by `agent_type` for explorer/Plan/worker | add `companion`, `archivist` fixtures (no adapter code change expected) |
| `scripts/agent-analytics/spawn-economics.mjs:271` | groups by role dynamically | none; new roles appear automatically |
| `scripts/agent-analytics/lib/ledger/codex-adapter.mjs` | see R4 | native usage support + fixtures |
| `docs/tempdocs/937-codex-agent-model-routing.md` | dated history | leave; this tempdoc supersedes the cap rationale only |

## Design

### D1. Thread cap is an anomaly guard

`.codex/config.toml`:

```toml
[agents]
enabled = true
# Anomaly guard only. Workflow concurrency is governed by the shared-resource
# rules in AGENTS.md (one dev stack, one Gradle build, disjoint file ownership),
# not by this number. Raise or lower it for runaway-spawn protection, never to
# shape delegation. (tempdoc 951)
max_concurrent_threads_per_session = 10
```

`AGENTS.md` gains one sentence in the Codex roles paragraph: "The thread cap is
an anomaly guard, not a concurrency budget; shared-resource rules bound parallel
work." The parity gate asserts the value and the comment marker.

Rejected: leaving it unset. "Codex chooses the default" is undocumented and can
change per client release; an explicit ceiling keeps the failure legible.

### D2. Companion role

`.codex/agents/companion.toml`:

- `model = "gpt-5.6-luna"`, `model_reasoning_effort = "xhigh"`,
  `sandbox_mode = "read-only"`.
- Effort is `xhigh` by owner decision (2026-09-10), matching the author's
  Companion. Rationale accepted: the Companion's value is consolidation quality
  over many follow-ups, and its history is re-billed each time, so a weaker
  first pass costs more than a stronger one. D6 still records a `high` run so
  the effort's contribution is measured rather than assumed.
- `description`: "Persistent read-only supporting-context specialist for the
  parent: consolidates module contracts, logs, and doc deltas across several
  related questions and returns compact, revision-stamped, source-linked
  findings. Never an authority over architecture or acceptance."
- `developer_instructions` cover, in this order:
  1. Read `AGENTS.md`, the governing tempdoc, and the canonical docs the capsule
     names. Stay read-only; never edit, run builds, or touch the dev stack.
  2. **Capsule protocol.** Every task names a stable Task ID, the project
     context scope, the concrete question(s), and the decision they feed.
     Follow-ups reuse the Task ID and send only changed fields; answer only the
     delta.
  3. **Revision-aware evidence.** Stamp every answer with `git rev-parse --short
     HEAD` of the assigned worktree and the file mtimes or commit of anything
     mutable. Separate *stable facts* (interfaces, contracts, owning modules)
     from *mutable state* (test results, branch status, lease owner, uncommitted
     edits). Report conflicts between retained knowledge and current source
     instead of silently keeping the older version.
  4. **Read discipline.** grep or ripgrep first, then ranged reads. Never
     concatenate whole files into one tool call. When an artifact is large,
     keep it on disk under the tempdoc's evidence directory and return the path
     plus the few lines that matter. This is the direct response to 27/94
     truncated tool outputs in the sampled rollouts.
  5. **Return envelope.** Findings first with `file:line`, then limitations,
     freshness limits, and any question that needs external research (routed to
     the parent, not answered from memory). Target under 200 words per
     follow-up unless the parent asks for a map.
  6. **Not yours:** direction, architecture, root-cause verdicts, acceptance
     judgment, implementation, worker allocation, publication, user
     communication. Return these to the parent.

Parent-side rules (how-to + `agent-workflow.md`):

- At most one Companion per session. Spawn with `fork_turns = "none"` and a
  self-contained brief; the role pin must apply.
- Open it when at least two related questions need the same supporting context
  (a module-contract map, a log corpus, a set of doc deltas) or when a
  multi-question lifecycle/gRPC/contract investigation is under way. Do not open
  it for a single lookup, a status check, or a task with no repeated context.
- Use `followup_task` with the same Task ID; use `send_message` only to hand it
  new facts without asking for a turn.
- Since the client has no `close_agent`, "closing" means ceasing follow-ups.
  Its retained history is re-billed on every follow-up, so stop using it once
  the repeated-context benefit ends.
- The parent still reads any evidence that controls an architectural or
  acceptance decision. The Companion's summary is a pointer, not the proof.

### D3. Archivist role

`.codex/agents/archivist.toml`:

- `model = "gpt-5.6-luna"`, `model_reasoning_effort = "high"`,
  `sandbox_mode = "workspace-write"`.
- `description`: "Docs-only closure and handoff writer: reconciles the tempdoc
  acceptance items with evidence pointers, updates canonical docs and their
  generated projections, and returns a ledger-sourced usage summary. Never
  edits source, tests, or Git state."
- `developer_instructions`:
  1. Read `AGENTS.md`, the governing tempdoc, and
     `docs/reference/contributing/agent-workflow.md` "Reconcile acceptance with
     evidence". Load `$session-closeout` and `$docs-maintenance`.
  2. **Write surface** is exactly: the governing tempdoc, its
     `docs/tempdocs/<n>-evidence/` directory, canonical docs under
     `docs/{explanation,reference,how-to,decisions}` that the parent names, and
     the regenerated projections those edits require. Never `modules/**`,
     `scripts/**`, `contracts/**`, `.codex/**`, `.claude/**`, or `governance/**`
     unless the parent's capsule lists the exact file.
  3. **Reconciliation.** Every acceptance item gets result, tested revision,
     environment, and an accessible evidence pointer. A claim without one moves
     to "unverified assumptions". Distinguish implementation, local proof,
     hosted proof, and authorized deferral (a deferral needs a decision and a
     destination).
  4. **Regeneration and checks.** Run the docs checks named in the how-to for the
     paths touched (`docs-validate`, `regen-all --check`, `check-tempdoc-size`,
     `check-tempdoc-numbers`). Report red output verbatim; never edit a gate to
     pass.
  5. **Usage summary.** Run `node scripts/agent-analytics/cost-session.mjs
     --session-id <parent id> --json` and, when available,
     `spawn-economics.mjs` for the by-role table. Paste tool output; do no token
     arithmetic yourself. If the ledger cannot resolve the session, say so.
  6. **Commit, narrowly.** You may commit your own docs-only changes on the
     assigned worktree branch: stage explicit paths inside your write surface
     (never `git add -A`), one commit, plain message naming the tempdoc. Never
     touch the main checkout, never `push`, open or merge a PR, amend, rebase,
     reset, or stash. Report the commit SHA and `git show --stat` output.
  7. **Not yours:** publication, source or test edits, the final completion
     claim to the user. Return the two-list state (BLOCKED ON YOU / PROCEEDING)
     to the parent, who reviews the commit before anything is published.

Decision on Archivist commits (2026-09-10, reasoned in-session at the owner's
request): **allowed, bounded to its own docs-only diff on the worktree branch.**

- A worktree commit is a checkpoint, not publication. ADR-0045 squash-merges,
  so the commit message never reaches `main`; the PR title/body does.
- The other write-capable roles (`worker`, `complex_worker`) already commit
  under "never destructive git or publication"; forbidding the Archivist alone
  would be an inconsistency with no matching risk.
- `session-closeout` expects owned work committed so `world-state` does not
  read the worktree as DIRTY-IDLE; an uncommitted closeout defeats the
  Archivist's purpose.
- The real risk is not the commit but the content: the same agent writing the
  verification claims also seals them. That is addressed by item 3 (claims
  without evidence pointers go to "unverified assumptions", never upgraded) and
  by the parent reviewing `git show --stat` before publication, which it must
  do anyway for the PR. Publication authority is unchanged
  (`no-merge-without-authorization`).
- Shared-filesystem hazard: another child may have uncommitted edits in the
  same worktree. Explicit-path staging inside the write surface is the guard;
  `git add -A` is forbidden in the role text and the parity gate asserts the
  phrase.

Parent-side rules:

- Spawn at actual completion, pause, or handoff (the same trigger as
  `session-closeout`), with a positive-integer `fork_turns` (suggested 40) so it
  sees the recent decisions while the Luna pin still applies. `fork_turns =
  "all"` is forbidden for this role (937 rule).
- One Archivist owns closure. Additional Archivists may take non-overlapping
  canonical-doc assignments mid-task.
- Publication authority stays with the parent and the user
  (`no-merge-without-authorization`).

Why not the author's `agent_docs/` framework: the tempdoc plus canonical docs
already own the work record and current truth; a parallel canonical tree would
create a second source of truth (`agent-prompt-surface-governance.md`).

### D4. Ledger prerequisite: native usage records

Extend `codex-adapter.mjs` `processCodexEntries`:

- Prefer `token_usage_record` entries deduplicated by native response id; fall
  back to the existing `token_count` path only for rollouts with no native
  records. Never sum both streams.
- Emit the compaction record as a normal `Call` with `compactionBoundary: true`
  (replacing today's synthetic boundary when a real record exists).
- Extend `session.selfCheck` with `{nativeRecords, legacyFallback}`.
- Fixtures: sample A (compaction record present, expect +234,478 input), sample
  B (duplicate notification, expect no double count), a legacy-only fixture
  (expect unchanged totals). Redact transcript prose; keep only usage shapes.
- Update `docs/explanation/21-agent-analytics-pipeline.md:261`.

This lands first: the Archivist's usage summary is only trustworthy after it.

### D5. Sandbox: document the inheritance rule; re-probe only on client change

R2 settles the question from client source plus 160 observed children, so no
new probe is needed for this change. The docs state: children inherit the
parent turn's sandbox and approval policy; role-file `sandbox_mode` is declared
intent only (Codex #39299). Add a one-line trigger to `agent-workflow.md`
"Delivery checks and runtime probe": re-run the step-3 write probe for a child
role when the Codex client changes major/minor version, and record the result
against the client version.

Owner lever (outside this repo): running the parent at `workspace-write`
tightens every child at once; there is no per-role setting to change.

### D6. Measurement before promotion to routine use

Run two comparisons on the repaired ledger, same models and efforts, cap at 10:

1. A parallel-friendly task (e.g. a three-question ownership survey across
   `modules/ui` and `modules/indexer-worker`) with disposable explorers vs.
   one Companion plus follow-ups.
2. A coupled task (a lifecycle or gRPC contract investigation with a shared
   dev-stack lease) the same two ways.

Record per run: accepted items, independent defects found later, total
parent-plus-descendant usage from `spawn-economics.mjs`, repair cycles,
critical-path time, user interventions. Then, and only then, decide whether the
how-to recommends the Companion by default or on request. A separate run with
`xhigh` on the Companion tests effort alone.

## Superseded or retired by this change

- The undocumented cap of 3 and any reading of it as a concurrency budget.
- The parity gate's closed four-role list.
- Nothing in tempdoc 937's routing decisions; they remain in force.

## Acceptance contract

Evidence is at worktree `951-codex-companion-archivist`, base `da79f8d57`,
Windows 11, Node 24.12, Codex CLI 0.153.4 installed (not exercised).

- [x] `.codex/config.toml` cap is 10 with the anomaly-guard comment; parity gate
      asserts both. Evidence: `check-codex-agent-parity: OK (8 checks)`.
- [x] `companion.toml` and `archivist.toml` exist with the pins in D2/D3; parity
      gate asserts pins, declared sandbox, and the capsule/revision/write-surface
      markers (first run caught a wrapped phrase; matcher now tolerates wraps).
- [x] `AGENTS.md` names both roles and the cap semantics within budget: bump
      recorded 8296 to 8573 B (+277) with reason; `CLAUDE.md` projection
      regenerated; `agent-instructions-sync --check` green inside the parity run;
      `check-always-loaded-budget` pass.
- [x] `use-codex-for-development.md` role table and three subsections added;
      `agent-workflow.md` persistent-helper paragraph and probe step 5 added;
      `docs-validate`: "No errors"; `llmstxt-generate` and `skills-sync` produced
      no diff.
- [x] Hook adapter test fixtures cover `companion` and `archivist` SubagentStart
      routing (outside the `Explore|Plan` matcher, like `worker`):
      `codex-hook-adapter.test: 19 passed`.
- [x] `codex-adapter.mjs` reads native usage records (rule A8) with the three
      fixtures in D4 (compaction gap, duplicate notification, legacy-only);
      `codex-adapter.test: 50 passed`; `agent-analytics: 55/55 test files
      passed`; pipeline doc row updated. Corpus smoke on the real sample A
      rollout re-run by the parent: 38 calls, input 5,469,732 (legacy path
      5,235,254; gap 234,478 = the compaction record), `usageSource: native`,
      boundary on the first native record after `compacted`, no synthetic call.
      Implemented by a delegated opus worker; reviewed and re-verified by the
      parent (downstream readers `cache-efficiency`, `context-residency`,
      `spawn-economics` checked, no change needed).
- [x] Green at commit `36135f9fd` (worktree, base `da79f8d57`):
      `check-codex-agent-parity: OK (8 checks)`; `prompt-surface-inventory`
      ran clean; `check-always-loaded-budget` pass; `agent-analytics: 55/55`;
      `docs-validate` no errors; `check-tempdoc-size` pass (426/800 lines);
      `check-tempdoc-numbers` OK; `llmstxt-generate --check` and
      `skills-sync --check` OK. Pre-merge compile gate
      `./gradlew.bat build -x test -PskipWebBuild=true` exit 0 (no Java or
      ui-web sources changed, so the web build was skipped; the full
      `build -x test` remains the PR-ready requirement if a reviewer wants it).
      `regen-all --check` fails only at `notices` because this fresh worktree
      has no Gradle license report; no generated file in the regen set differs.
- [x] Sandbox inheritance documented in the how-to and `agent-workflow.md`;
      parity label reads "declared sandbox intent"; re-probe trigger is step 5
      of the probe procedure.
- [x] Archivist role text carries explicit-path staging and publication
      prohibitions; parity gate asserts the phrases (wrap-tolerant).
- [ ] D6 measurement plan recorded here with the first run's results, or an
      explicit deferral with owner decision. **Authorized deferral (owner,
      2026-09-10: "proceed with the remaining work end to end" covers the
      repository change; D6 needs live Codex sessions the owner drives).**
      Destination: first Companion-vs-explorers comparison run from the desktop
      app after this lands, results appended here under a dated heading.

## Resolved questions

1. Companion effort: **xhigh** (owner, 2026-09-10). D6 measures `high` as the
   comparison arm.
2. Per-agent sandbox: **not possible in the current client** (R2). Role-file
   `sandbox_mode` is declared intent; the parent turn's mode binds all children.
   Whether to run the parent at `workspace-write` is a machine-config choice
   outside this repo and does not block the design.
3. Archivist commits: **allowed, bounded** (D3, reasoned in-session).

## Dated index

- 2026-09-10 — Design written; research R1-R5 recorded; owner decisions 1-3
  captured. Implementation not started.
- 2026-09-10 (later) — Companion effort set to xhigh. R2 resolved from Codex
  source (#39299) plus 361-rollout scan; D5 downgraded to documentation plus a
  client-change re-probe trigger. Archivist commit authority decided: bounded
  yes. Implementation still not started (owner instruction).
