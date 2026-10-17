---
title: "External agent-harness improvements: learn from Superpowers, install a library-docs MCP server"
type: tempdocs
status: open
created: 2026-06-19
updated: 2026-06-19
author: agent analysis (ecosystem research pass), filed by agent
category: dx / agent-tooling / context-engineering / mcp / external-ecosystem
related:
  - delegating-to-subagents
  - subagents-no-inheritance
  - explore-before-implementing
  - dev-stack
  - module-arch
---

# 616 - External agent-harness improvements: learn from Superpowers, install a library-docs MCP server

> What this document is. A scoped proposal, not a finished design. It records two concrete,
> externally-sourced (non-Anthropic, June 2026) improvements to our **agent harness** — the
> environment Claude Code runs in when developing JustSearch — and the reasoning for adopting
> one and merely mining the other. It deliberately keeps the surface small: this repo already
> over-invests in the behavior-governance layer (skills/hooks/gates), so the bar for adding
> external scaffolding is "removes a real, recurring failure without colliding with what we have."

## 0. Framing — what area of work this is

The general area is **agent-harness engineering**: improving the agent's *surrounding ecosystem*
(context sources, tools, extensions) rather than the model. It splits along three axes:

1. **Context engineering** — what the agent *knows* (what enters the context window, when). → a library-docs MCP server.
2. **Tool/capability engineering** — what the agent can *do* (MCP servers, the `justsearch-dev` stack).
3. **Extensibility / behavior governance** — how the agent's *behavior* is shaped (skills, hooks,
   discipline gates, `.claude/rules/`). → this repo's existing heavy investment; the lens for Superpowers.

The two improvements below sit in axes 1 and 3. We did NOT find anything in axis 2 worth adding right now
(MCP-server budget is already near the community-recommended 3–6 ceiling).

## 1. Background — the June 2026 external survey

A research pass over non-Anthropic Claude Code ecosystem activity (~mid-May to mid-June 2026) surfaced,
in rough order of relevance to this repo:

- **A library-docs MCP server** — an open-source MCP server that injects up-to-date, version-specific library
  docs/code examples into the prompt on demand. Top-3 highest-impact community install alongside
  GitHub MCP and Playwright MCP.
- **obra/superpowers** (Jesse Vincent) — community agentic-skills framework + engineering methodology
  shipped as markdown; cross-host (Claude Code / Cursor / Codex / Copilot CLI / Gemini CLI / OpenCode).
- **Community package managers / marketplaces** — `ccpi` CLI (tonsofskills), claudemarketplaces.com,
  OpenPlugins. Discovery infra; risky to bulk-install into a repo with custom hooks/gates.
- **MCP Shield** — third-party security scanner for MCP traffic (20 rules: secrets, injection, insecure config).

Caveat carried from the research: most of this lives in SEO/aggregator sources with inconsistent star
counts and dates; treat specific figures as approximate, mechanisms as well-corroborated.

## 2. Improvement A — Install a library-docs MCP server (ADOPT)

### A.1 What it is / how it works

It is an MCP server exposing two tools the model calls mid-turn:

- `resolve-library-id` — maps a library name (+ optional version) from the prompt to a canonical ID
  (e.g. `Next.js 14.1` → `/vercel/next.js`); returns ranked candidates with metadata when ambiguous.
- `get-library-docs` — takes that ID + an optional `topic` (e.g. `"routing"`) and returns current
  docs/code examples.

Backend behavior:
- **Pre-built index, not live crawl.** ~33k+ libraries crawled on a rolling ~10–15 day schedule, diffing
  git commit hashes + package versions (npm/PyPI/Maven) to detect real changes.
- **Server-side token budgeting.** Client passes a token limit (default ~10k); the server ranks within it
  (code examples / API signatures above prose). Each lookup injects a bounded, relevant slice.

### A.2 Why it fits JustSearch

The repo spans many version-sensitive libraries — Lucene, Tika, gRPC-Java/protobuf, Lit, Vite — exactly
the multi-library surface the server targets. The failure it removes (the model writing plausible-but-wrong
API code: hallucinated methods, renamed params) happens on *every* generation, silently, and is precisely
the `explore-before-implementing` / "don't guess" failure mode we already guard against in prose.

### A.3 Why it beats "occasionally run an agent to check versioning"

They are **different layers**, not substitutes:
- Library-docs MCP server = **correctness-while-coding** (continuous, automatic, ~1 cheap tool call when a lib is
  referenced; injects the actual signatures).
- A version-checking agent / `gradle dependencyUpdates` / `npm outdated` = **upgrade-planning** (detects
  that a newer version/CVE exists — which the server does NOT tell you).

"Occasionally" is the weakness of the agent approach: the wrong-API failure fires constantly but is only
checked periodically. The server fires exactly when relevant, at bounded cost.

### A.4 Honest limits

- **Coverage ceiling.** The server only knows indexed libraries. Our **native / uncommon deps**
  (`llama-server.exe`/llama.cpp, ORT/ONNX Runtime native bindings, Tauri/Rust crates, generated
  protobuf code) may have thin or no coverage → still need a live agent / `outdated` pass for those.
- **10–15 day index lag** — a library that shipped a breaking release yesterday is fresher via live web.
- **MCP-server budget.** We already run `justsearch-dev` + github + claude-in-chrome + gmail/calendar/drive.
  The server (+ a possible MCP Shield trial) is about our ceiling; do NOT also wire in a marketplace server.

### A.5 Action items

- [x] Add a library-docs MCP server as a **user-scope** MCP server (not committed into the repo's shared `.mcp.json`).
      **Installed 2026-06-19** via the remote HTTP transport at user scope:
      `claude mcp add --transport http --scope user <name> <server URL>`
      → written to `C:\Users\<user>\.claude.json`; `claude mcp list` reports `✔ Connected`.
      **Deviation from plan:** the official installer `npx ctx7 setup --claude` (the API-key / OAuth path)
      **crashed on Windows** — its first arrow-key TUI prompt hit a libuv assertion
      (`UV_HANDLE_CLOSING ... src\win\async.c`) when run without a full TTY, and `ctx7@0.5.3` also emitted an
      "unsettled top-level await" warning under Node v24. So we installed the **anonymous** HTTP server
      (no API key yet) as the robust path. The MCP **tools** load only on a fresh session start, not mid-session.
- [x] **Upgrade to a free API key** (higher personal rate limits). **Done 2026-06-19**: removed the anonymous
      server and re-added at user scope with a API-key header (the `claude mcp` CLI redacts the key
      in its output and stores it in `C:\Users\<user>\.claude.json`, not in any committed file). `claude mcp list`
      reports `✔ Connected`. The key now governs our quota; revisit only if parallel-worktree use ever throttles
      (§A.3/§A.4).
- [ ] **Restart Claude Code, then smoke-test** against our **pinned** dependency versions (Lucene, gRPC-Java,
      Lit, Vite) on a real task and confirm the injected docs match the versions we actually pin in
      `build.gradle.kts` / `modules/ui-web/package.json`.
- [ ] Document the install + the "correctness-while-coding vs upgrade-planning" split in the relevant
      skill (`/dev-stack` or a short note) so it is discoverable, not tribal knowledge.
- [ ] Record the coverage gap explicitly: native/uncommon deps still go through `outdated`-style checks.

## 3. Improvement B — Learn from Superpowers (MINE, do not adopt wholesale)

### B.1 What it is

obra/superpowers ships an opinionated engineering culture as a folder of markdown skills — structured
phases (brainstorming → TDD RED-GREEN-REFACTOR → subagent-driven parallel execution → requesting code
review). Its notable property is **cross-host portability**: one definition runs across many agent hosts.

### B.2 Why we MINE rather than ADOPT

Superpowers overlaps heavily with infrastructure this repo already has and enforces at a higher tier:
- `.claude/skills/` (registers + workflows), `.claude/rules/slice-execution.md` (bidirectional pass,
  independent-reviewer-required, ux-audit-closure), the discipline-gate kernel, ~20 hooks.

Adopting it wholesale would **duplicate the behavior-governance layer** and risk colliding with our hooks
and worktree discipline. The value is in *ideas we don't yet encode*, not the package.

### B.3 Candidate lessons — evaluated (verdicts recorded 2026-06-19)

Primary source read: the repo ships **7 sequential phases** (brainstorming → git-worktrees → planning →
implementation → TDD red-green-refactor → code-review → branch-completion) and **15 skills**
(`test-driven-development`, `systematic-debugging`, `verification-before-completion`, `brainstorming`,
`writing-plans`, `executing-plans`, `dispatching-parallel-agents`, `requesting-code-review`,
`receiving-code-review`, `using-git-worktrees`, `finishing-a-development-branch`,
`subagent-driven-development`, `writing-skills`, `using-superpowers`). Its two genuinely *novel* elements are
authoring techniques, not phases: each skill opens with a capitalized **"Iron Law"** and then enumerates
**"red flags"** — the predictable rationalizations an agent uses to skip the discipline ("tests passing on
first run", "just this once").

- [x] **B.3a — Cross-host portability as a design value. → REJECT (for the internal harness).**
      Superpowers stays host-agnostic by *avoiding* host coupling. Our governance layer does the opposite **on
      purpose**: rules reference `.claude/rules/`, the hook set (`bash-guard`/`build-counter`/`repeat-guard`),
      `EnterWorktree`, the `justsearch-dev` MCP, and platform *facts* (`subagents-no-inheritance` is literally a
      Claude Code behavior). That coupling is what buys the ~100% `hook`/`gate` enforcement tier; stripping it for
      portability would demote rules to prose. There is no second host in use. **No action.** The one place
      portability is legitimately valuable — JustSearch's *own* emitted agent tools (`AgentToolEmitter`) consumed
      by external agents — is explicitly out of scope (§4) and tracked separately.

- [x] **B.3b — Explicit phase scaffolding. → REJECT new phases; ADOPT one authoring convention.**
      Phase-by-phase we already have an equivalent or a *deliberate* divergence:
      worktrees → `branch-safety.md`; brainstorming → `ask-when-uncertain` + EnterPlanMode/Plan agent;
      planning → `tempdoc-is-your-contract` + Task tools; code-review → `independent-reviewer-required`;
      branch-completion → the `branch-safety.md` merge workflow; verification → `verify-your-work` +
      `audit-driven-fixes-need-test`. We intentionally do **not** mandate strict test-first TDD (we are
      audit/verify-heavy, not red-green-first) and intentionally do **not** default to fresh-subagent-per-task
      (see B.3c). So: **no new phase is missing.** The one transferable idea is the **red-flags /
      anti-rationalization enumeration** — and we already embody it in our two highest-stakes rules
      (`structural-defects-no-repeat` lists the "wait-for-more-evidence" aliases; `fix-root-causes-not-symptoms`
      lists 6 named anti-patterns). **Verdict: adopt it as a lightweight *authoring convention* for future
      load-bearing rules — when a rule is "must/never" and has a predictable evasion, name the evasion inline —
      but do NOT mass-retrofit existing rules** (that would fail the `before-appending-to-rules` bloat gate). This
      is convention guidance, not a new anchored rule, so it needs no `prose-tier-register` changeset.

- [x] **B.3c — Subagent-driven parallel execution. → PARTIAL ADOPT (principle); REJECT as default loop.**
      Superpowers dispatches a *fresh agent per task with limited context (plan + tests only)* and a two-stage
      review. The limited-context part **converges with our hard constraint**: since `subagents-no-inheritance`
      means a subagent gets *nothing* of our CLAUDE.md/hooks for free, packaging each delegation as a
      **self-contained brief (plan + tests + acceptance criteria)** is exactly the right adaptation — and sharpens
      the existing `delegating-to-subagents` "brief inline is mandatory" line from *what* to *what shape*.
      **Adopt that framing as principle.** But **reject "fresh subagent per task" as a default implementation
      strategy**: our subagents run **without `build-counter`/`repeat-guard`/Read-limit**, so a delegated TDD loop
      can thrash on builds unguarded — which is precisely why `delegating-to-subagents` already says subagents
      suit research/exploration, *not* long iterative refactors. Superpowers' pattern assumes host guardrails we
      deliberately don't extend into subagents. **Net actionable:** one-line sharpening of the existing rule's
      briefing guidance (plan + tests + acceptance), gated through `before-appending-to-rules` if pursued.

### B.4 Explicit non-goals — confirmed

- [x] Do **not** install the Superpowers plugin/marketplace into this repo. **Confirmed:** nothing installed;
      the analysis was source-read only.
- [x] Do **not** import its skills verbatim. **Confirmed:** the two surviving ideas (B.3b red-flags convention,
      B.3c self-contained-brief framing) are *principles*, not copied skills, and are gated through
      `before-appending-to-rules` before any rule-file edit.

### B.5 Synthesis — the harvest

As the §0 prior predicted, the actionable harvest is small (2 principles, 0 installs), and **both are optional
convention-level sharpenings of rules we already have**, not new structure:

1. **Red-flags authoring convention** (from B.3b) — name the predictable evasion inline when writing a future
   `must`/`never` rule. Already practised in 2 rules; adopt forward, don't retrofit.
2. **Self-contained subagent brief** (from B.3c) — when delegating, hand the subagent plan + tests + acceptance,
   reinforcing `subagents-no-inheritance`.

Everything else (cross-host portability, new phases, fresh-agent-per-task default, strict TDD mandate) is
**rejected** as either already-covered or a deliberate divergence.

**Applied 2026-06-19** (both, ~2 sentences, inside existing anchored sections of `CLAUDE.md`):
1. `delegating-to-subagents` — appended the self-contained-brief sentence (plan + tests/acceptance + constraints).
2. `before-appending-to-rules` — added the red-flags / name-the-evasion authoring convention.
Both land *inside* already-anchored sections, so no new `prose-tier-register` row / changeset was needed;
`node scripts/governance/run.mjs --gate prose-tier-register --mode gate` → **pass** (0 findings).

## 4. Out of scope (recorded so the boundary is explicit)

- **Community package managers / marketplaces** (`ccpi`, claudemarketplaces, OpenPlugins) — discovery only;
  bulk-installing into a hook/gate-governed repo creates conflicts + cross-worktree merge churn.
- **MCP Shield** — worth a *trial* before adding any third-party MCP server, but not part of these two
  improvements; track separately if/when we add external MCP servers.
- **The in-product agent system** (`modules/app-agent`, `OperationCatalog`, `AgentToolEmitter`) — aligning
  its emitted wire schema with current MCP tool conventions is a *separate, higher-leverage* piece of work,
  not covered here.

## 5. Verification / closure criteria

- Improvement A is "done" when the library-docs MCP server is installed at user scope, validated against at least one pinned
  library on a real task (injected docs match the pinned version), the coverage gap is documented, and the
  install + layer-split note lands in a discoverable skill.
- Improvement B is "done" when each B.3 candidate has an explicit verdict (adopt-into-existing-home /
  reject) recorded here, with no verbatim import. **✓ Met 2026-06-19** — B.3a REJECT, B.3b REJECT-new-phases
  /adopt-one-convention, B.3c PARTIAL-ADOPT-principle/reject-as-default; B.4 non-goals confirmed; synthesis in
  §B.5. The two surviving principles are optional, gated through `before-appending-to-rules`, and tracked as
  future rule-file edits outside this tempdoc.

## 6. Claude Code patch-notes review (2.1.174–2.1.183) — DEFERRED

Added 2026-06-19. Reviewed the official changelog (`code.claude.com/docs/en/changelog`, verbatim, not
aggregators) for changes that might force a `CLAUDE.md` / `.claude/rules/*` edit. **Verdict: no edit is
required right now.** One item needs verification before any edit; one is an enhancement opportunity.
**Status: deferred — revisit later.**

### 6.1 Auto-mode git safety vs `delegating-to-subagents` — DO NOT EDIT (verify first)

**2.1.183** "Improved auto mode safety" natively blocks `git reset --hard`, `git checkout -- .`,
`git clean -fd`, `git stash drop` ("when you didn't ask to discard local work") and `git commit --amend`
("when the commit wasn't made by the agent this session").

On its face this collides with the `delegating-to-subagents` claim *"Destructive git commands are not blocked …
a subagent can run `git reset --hard` … in the main worktree."* Reasons NOT to edit:
- The feature is **auto-mode-scoped** (changelog files it under *auto mode safety*), not all sessions.
- The changelog **never says it reaches subagents**; the rule's real point — parent hooks (`bash-guard`) don't
  fire in subagents — is a *separate mechanism* and remains true.
- **`git push --force` is NOT in the native list**; `bash-guard` blocks it everywhere → native block is
  narrower, not a replacement.
- Weakening a *safety* claim on an auto-mode-scoped, subagent-unconfirmed line violates `verify-dont-guess` /
  `audit-driven-fixes-need-test`. Over-stating the danger ("assume unguarded, don't delegate destructive git")
  is conservatively correct regardless.

- [ ] **Verify** whether native auto-mode git-blocking actually fires inside a subagent shell (runtime probe;
      evidence chain: probe > official docs > aggregators). Only if confirmed does the rule get a *precision*
      note — never a weakening — and on evidence, not speculation.

### 6.2 `Agent(model:…)` permission syntax — ENHANCEMENT OPPORTUNITY (not a fix)

**2.1.178** added `Tool(param:value)` permission rules, e.g. `Agent(model:opus)` to block Opus subagents.
Today our model guidance is **prose-only** (`context-efficiency.md`: "use `model: haiku` for simple subagent
tasks"). This new syntax could **enforce** subagent-model policy at ~100% (a tier upgrade from prose-only),
consistent with our "hooks enforce vs prose ~70%" philosophy.

- [ ] Decide whether to add an `Agent(model:…)` permission rule to `settings.json` (via `/update-config`) to
      enforce/cap subagent models. **`settings.json` change, NOT a `CLAUDE.md` edit.** Not urgent.

### 6.3 No-action items (recorded so the boundary is explicit)

- **2.1.178** nested `.claude/skills` namespacing (`<dir>:<name>` on clash; closest `.claude/` wins for
  agents/workflows) — our worktrees inherit config from main, no duplicate skill dirs today → no action.
- **2.1.178** TeamCreate/TeamDelete removed — our rules never reference agent teams → no action.
- **2.1.175/176** `enforceAvailableModels`/`availableModels` managed setting — org/admin model pinning, not a
  `CLAUDE.md` item.
- **2.1.183** `attribution.sessionUrl` (omit claude.ai link from commits/PRs) — optional preference; we use
  `Co-Authored-By` → no rule impact.
- **2.1.179/181/183** connection-drop / thinking-block / WebSearch-in-subagent fixes — pure bug fixes → no action.

## 7. Where to look next + first evidence pass (2026-06-19)

The external survey was **low-yield** (a 174K-star framework → ~2 sentences; the library-docs MCP server the one real win). So
the next improvements should come from *internal, evidence-based* sources, not broad external scanning. Map of
where to look, then a first real pass.

### 7.1 The map (by §0 axis)

- **A. Internal telemetry (highest signal).** `tmp/agent-telemetry/events.ndjson` + `scripts/agent-analytics/`
  (`analyze-trends`, `correlate-signals`, `generate-dashboard`) + `scripts/ci/report-{doc-relevance,pit-strength,
  reliability-budget}.mjs`. Your observed failure distribution beats any blogger's.
- **B. Context axis beyond the library-docs MCP server.** The native/uncommon-deps gap (llama.cpp/ORT/Tauri/protobuf); the
  dogfooding angle (JustSearch indexing its own codebase/docs as the agent's retrieval backend); extending the
  `consult-doc-hint` delivery mechanism.
- **C. Capability axis.** Extend `justsearch-dev` MCP (index inspector, gRPC introspection, VRAM telemetry)
  rather than add external servers (near the 3–6 ceiling); track MCP-spec evolution for `AgentToolEmitter`.
- **D. Governance axis.** Walk the `tier-register` for `prose-only` rows that could become `hook`/`lint`/`gate`
  (the `Agent(model:…)` item in §6.2 is one such upgrade).
- **E. Better external sources.** Primary only: CC GitHub issues/changelog, Agent SDK docs, the Piebald
  subagent-prompt leak (re-check as CC evolves), Code-with-Claude talks — not SEO aggregators.
- **F. Cadence.** A recurring harness review (`/doc-audit` + telemetry sweep + tier-register pass) beats bursts.

### 7.2 First evidence pass — findings (telemetry: 255 transcripts, 8.6k recent events)

- **PRIMARY — doc-delivery gap (context axis, strong evidence).** `report-doc-relevance`: of **146** canonical
  docs, only **11 are ever read** and **10 delivered** (skill/Consult hook) across **4754** Read calls — **46
  high-value docs read 0× and wired nowhere**, including `22-agent-system-architecture.md`,
  `21-agent-analytics-pipeline.md`, `26-extension-substrate.md`, the whole `ui/frontend-kernel/kernel/*` set, and
  `api-contract-map.md`. The delivery mechanism *works* but `GOVERNED_REGIONS` (in
  `scripts/agent-analytics/lib/governed-regions.mjs`) covers exactly **1 region (`shell-v0`) / 2 docs** — and its
  own comment invites growth ("promote to `governance/consult-register.v1.json` if it grows"). **This is the
  highest-leverage, evidence-grounded next improvement:** map more code regions → their governing docs (e.g.
  `modules/app-agent*` → `22-agent-system-architecture.md`; `modules/adapters-lucene` → `18-…deep-dive.md`).
- **Blocking hooks are NOT a friction source.** 0 block/deny events in the 8.6k-event window → do not invest in
  loosening `bash-guard`/`repeat-guard`/`build-counter`.
- **Tool-failure distribution (42 failures).** `claude-in-chrome` = **16 (~38%)** (computer/javascript/find/
  browser_batch/navigate/tabs), Bash 15, Read 9. Corroborates the existing `agent-lessons.md` "too many tabs
  hang the chrome extension" lesson — load-bearing, no new rule needed. Read failures ≈ large-file/auto-limit
  friction (already covered by `intervene`).
- **Tier-register upgrade pass (governance axis).** Mature/well-curated; few low-hanging upgrades. The one real
  candidate is `Agent(model:…)` enforcement (already captured §6.2). **Honest finding: the leverage is in
  delivery (context), not more enforcement (governance).**

### 7.3 Recommended next action (deferred for the user)

- [ ] **Extend the doc-delivery map.** Add code-region → governing-doc entries to `GOVERNED_REGIONS` (start with
      the highest-value unreached docs surfaced above; keep it tiny per the file's own guidance, promote to a
      `governance/consult-register.v1.json` only if it grows). This is the data-backed Context-axis win and the
      concrete successor to the library-docs MCP server. Verify with a follow-up `report-doc-relevance` run that read/delivery rises.

## 8. Sources (June 2026 research pass)

- Dwarves Memo — breakdown of a library-docs MCP server (tools, indexing, token budget) (link removed)
- GitHub — obra/superpowers: <https://github.com/obra/superpowers/>
- Marc Nuri — Superpowers framework writeup: <https://blog.marcnuri.com/superpowers-claude-code-skills-framework>
- Composio — Best Claude Code plugins 2026: <https://composio.dev/content/top-claude-code-plugins>
- Claude Code changelog (official, used for §6 patch-notes review): <https://code.claude.com/docs/en/changelog>
