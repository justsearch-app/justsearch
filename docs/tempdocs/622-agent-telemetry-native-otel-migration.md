---
title: "Agent-developer telemetry as projection, not fork: capture from native OTel, outcome joined from canonical repo ground truth (git / tempdoc / gates), scored as a kernel-ratcheted diagnostic — conforming to the existing canonical-authority-and-projection seam"
type: tempdocs
status: investigated
created: 2026-06-20
updated: 2026-06-20
author: agent analysis (telemetry data audit + ecosystem research + long-term design), filed by agent
category: dx / agent-tooling / telemetry / observability / opentelemetry
principle: "canonical-authority-and-projection extends across the process boundary — the canonical owner of a fact may be an external system (git, the Claude Code harness, CI); projecting from it still beats re-deriving it"
principle2: "governed agent-knowledge is source-agnostic — hand-authored or telemetry-mined, a lesson passes the same gated acceptance and the same governed (projection / JIT / budgeted) delivery; the source is interchangeable, the produce→govern→deliver discipline is invariant (§16)"
principle3: "the repo enforces declaration-coverage heavily but measures activation-efficacy almost nowhere — every positive-coverage register (gates, docs, rules, skills, surfaces) assumes declared=effective; telemetry × registry is the join that measures whether the artifact is actually read/fires/catches anything (§17)"
related:
  - agent-signal-validity-and-metrics-evolution      # 285 — signal audit; the "blocked on stripped content" finding
  - agent-quality-axes-investigation                 # 424 — "the pipeline is inert"; SessionEnd auto-aggregation Tier-2
  - hook-execution-integrity                          # 592 — hand-built hook_failure telemetry + hook-integrity gate
  - telemetry-substrate                               # 427 — worker→head PRODUCT metric replication (different layer, not dev-agent)
  - external-agent-harness-context-engineering        # 616 — library-docs MCP/Superpowers; the "delivery > governance" lens
  - always-loaded-agent-doc-audit-and-prose-to-infrastructure  # 620 — subject a hand-rolled layer to standard seams
---

> NOTE: Noncanonical working tempdoc. Verify any behavioral or implementation
> claim against canonical docs, code, and tests before promotion. Dated
> 2026-06-20; numbers measured against `tmp/agent-telemetry/` on that date.

# 622 — Agent telemetry: native-OTel migration

## 0. Thesis in one paragraph

The dev-agent telemetry under `scripts/agent-analytics/` **forks facts that
already have canonical owners**, then re-derives lossy copies of them. The event
stream forks the harness (re-summarizing, worse, what Claude Code now emits
losslessly via OTLP — §2, §5). The LLM-as-judge (276/277) forks git and the
build system (guessing "merged?" / "build passed?" facts those systems own
authoritatively — §6.3). `costs.ndjson` forks the harness's token counters. This
is exactly the **fork-vs-projection** drift class the codebase has gated against
four times — for search-execution facts (`execution-surfaces` / SearchTrace,
553), for observed happenings (`observed-happening`, 575), for metrics
(MetricCatalog, 427), for the discipline baselines themselves (the 530 kernel).
The long-term design is therefore **not a new telemetry system** but an instance
of that existing seam: one canonical authority per fact, every other
representation a governed lossy-downward projection. Capture projects from the
harness (native OTel); outcome projects from a *join* of canonical repo ground
truth (git / tempdoc status / gate SARIF / CI); meaning is a diagnostic ratchet
reusing the 530 kernel. The blocking hooks keep their *enforcement* role
(blocking is a real capability OTel lacks) and shed their *telemetry* role. The
one genuinely new thing this surfaces (§9) is that the canonical owner of a fact
can live **outside the app's process boundary** — and projection-not-fork holds
there unchanged.

## 1. What the data physically is (measured 2026-06-20)

| Layer | File | Reality on disk |
|---|---|---|
| Live event stream | `tmp/agent-telemetry/events.ndjson` | 1,098 lines, **30 minutes**, 10 sessions — actively rotated |
| Rotated history | `events.ndjson.prev` | 28,058 lines, **2.5 days** (Jun 17–20), **31 sessions**, 12,840 tool calls |
| Session reports | `tmp/agent-telemetry/sessions/*.json` | **1 file** (April 27) |
| Scores | `scores.ndjson` | **1 record** (April 28) |
| Costs | `costs.ndjson` | **1 record** (April 28) |
| Outcomes | `outcomes.ndjson` | **absent** |

Structural verdict: the raw stream is alive; **every layer built on top is
dead.** Events have flowed for ~2 months but were aggregated/scored exactly once
— during the April 423/424 investigation that *created* the scripts. Rotation
means **no durable raw reservoir**: `.prev` is a single-generation backup
holding 2.5 days. The system is a **firehose with no reservoir** — high fidelity
for "now," near-zero longitudinal memory.

## 2. The capture asymmetry — measured blind spots

`dispatch.mjs`'s summarizer keeps rich detail for some tools and reduces others
to `{tool: X}` / `{mcp_tool: X}`. Over the 31-session `.prev` window:

- **16.2%** of all tool calls are fully opaque (name only, no args).
- **100% of subagent dispatches opaque** — 350/350 `Agent` calls carry no
  prompt. Combined with the platform fact that *parent hooks don't fire inside
  subagents* (`.claude/rules/agent-lessons.md` → `subagents-no-inheritance` /
  `parent-hooks-dont-fire-in-subagents`), **subagent interiors are structurally
  invisible**: the parent sees only `subagent_start` / `subagent_stop`.
- **100% of user prompts blank** — 351/351 `user_prompt_submit` events have
  `prompt_length: null, prompt_excerpt: null`. **Task intent is absent from the
  stream.** This is why 276's session-type classification had to run a separate
  paid LLM-judge over transcripts, and the same content-strip 285 named as the
  oscillation-detection blocker.
- **MCP calls (850)** carry only the tool name; no args.
- **PowerShell opaque while Bash rich** — 52 vs 2,557 calls. Every signal that
  keys off command text (build cadence, git-op detection, `bash_fileop_pct`) is
  blind to PowerShell. On a Windows-primary repo this is a latent correctness
  hole; low impact today only because agents favor the Bash tool.

The asymmetry isn't all wrong (stripping `ToolSearch`/`TaskUpdate` args is
fine). The defect is that **the two highest-value insight targets — what was
asked, what was delegated — are exactly the two zeroed out.**

## 3. What the data CAN reliably yield (the usable ceiling)

The rich-captured fields (Bash command, Edit `file_path`+lengths, Read
`file_path`+offset flags, Grep pattern, WebSearch query) are genuinely good.
Derived from `.prev` in seconds:

- **File hotspots**: `views/UnifiedChatView.ts` edited **130×** and read
  **208×** across 31 sessions — empirically the single biggest agent-effort sink
  (exactly why 621 exists to decompose it). Trustworthy, actionable.
- **Read discipline**: 16.4% of reads unbounded — a real hygiene metric.
- **Failure surfaces**: tool-failures cluster on `WebFetch` (41), `Bash` (37),
  `Read` (22, mostly the 25K-token limit), and claude-in-chrome MCP tools
  (tab/frame staleness). Legible "what wastes agent turns" signal.

So the **friction/hygiene layer is well-served** — consistent with 277's
conclusion that the data measures *process friction*, not *outcomes*.

## 4. What the data CANNOT tell you (and why)

1. **Whether anything succeeded** — no outcome signal; `outcomes.ndjson` absent;
   needs the separate LLM-judge.
2. **What agents were working on** — prompts blank; can't bucket by task.
3. **What delegated work did** — subagents are black boxes.
4. **Cost/token economics** — `costs.ndjson` schema is excellent (full token
   breakdown, turns, subagent-transcript accounting) but sourced from transcript
   JSONL, not the event stream, and only one record was ever produced.
5. **Productive iteration vs. thrashing** — `Edit` keeps only
   `old/new_string_length`, no content hash, so re-edit *count* is derivable but
   a revert-and-retry oscillation can't be told from healthy refinement (285's
   known gap, confirmed in data).
6. **Cross-session trends** — aggregation never runs; no time series exists.

Items 2–4 are a **capture-design** problem, not a wiring problem — no amount of
running the existing scripts recovers them.

## 5. What the best tools do better (2026 ecosystem research)

The industry has standardized on **OpenTelemetry GenAI semantic conventions**
(`invoke_agent` / `execute_tool` / `chat` spans, `gen_ai.*` attributes). Three
layers, all OTLP:

1. **Harness emits OTLP** — LangChain, CrewAI, AutoGen, and **Claude Code
   itself** emit GenAI-semconv spans.
2. **Collector → durable backend** — Honeycomb / Datadog / Grafana / Langfuse /
   Phoenix; query *"all traces where tool X failed for segment Y over 48h."*
3. **Eval layer** — trace-level *and* session-level LLM-judge scoring, CI/CD
   eval gates, time-travel session replay (AgentOps, Laminar).

### 5.1 The decisive finding: Claude Code ships native OTel that closes our gaps

`CLAUDE_CODE_ENABLE_TELEMETRY=1` + an exporter emits three signals
(metrics / log events / traces). Gap-by-gap against §2:

| Measured blind spot | Native Claude Code OTel |
|---|---|
| 351/351 prompts blank | `OTEL_LOG_USER_PROMPTS=1` → prompt text on `claude_code.user_prompt` events + the `claude_code.interaction` span. A **clean privacy toggle**, not an irreversible strip. |
| 350/350 subagent dispatches opaque + interiors invisible | *"When the agent spawns a subagent through the Task tool, the subagent's `llm_request` and `tool` spans nest under the parent's `claude_code.tool` span, so the full delegation chain appears as one trace."* **Solves the exact problem `agent-lessons.md` calls unsolvable at the hook layer.** |
| `costs.ndjson` = 1 record, transcript-parsed | `claude_code.token.usage` metric after *every* API request, split by input/output/cacheRead/cacheCreation, model, query_source; token counts on every `claude_code.llm_request` span. First-class, continuous. |
| PowerShell opaque, Bash rich | OTel forwards trace context to *"every Bash **and PowerShell** command"*; child-process spans nest under the tool span. Symmetric. |
| MCP calls = name only (850) | `tool_decision` / `tool_result` / `mcp_server_connection` events with detail under `OTEL_LOG_TOOL_DETAILS=1`; per-tool, per-user SIEM-grade audit trail. |
| Hook substrate hand-instrumented (592 built `hook_failure` + integrity gate) | `claude_code.hook` spans (beta) record which hooks ran, duration, **and whether they returned a blocking decision** — natively. 592's hand-rolled channel becomes a platform primitive. |
| Rotated firehose, aggregation never run | Collector + backend = durable retention, cross-session trends, **time-travel session replay** out of the box. |
| No outcome signal (276/277 ran a one-off judge) | Eval platforms (Braintrust / Phoenix / Langfuse) provide **dual-level eval** (per-step + did-it-hit-the-goal), custom evaluators, CI/CD eval gates — the PHI/signal layer of 276/277/285, productized. |

Content capture is **opt-in and graded**, matching the privacy trade-off the
repo made by stripping — but cleanly toggleable per flag:
`OTEL_LOG_USER_PROMPTS` (prompt text), `OTEL_LOG_TOOL_DETAILS` (tool args),
`OTEL_LOG_TOOL_CONTENT` (full bodies, 60 KB cap, needs tracing),
`OTEL_LOG_RAW_API_BODIES` (full request/response JSON).

### 5.2 The one thing the hook approach does genuinely better

*"Hooks are control code; OpenTelemetry is evidence."* OTel records what happened
*after*; it cannot **block**. `bash-guard` (force-push, destructive git) and
`maintain-doc-hint` *prevent* actions in real time — no observability tool has
this. The hooks aren't wasted; they're the wrong tool for the *telemetry* job.

### 5.3 Currency check — what is actively in flux (research pass 2026-06-20)

A targeted second research pass on the two fastest-moving, design-load-bearing
points (the harness trace beta; agent-eval methodology) returned four findings
that materially shape the design:

1. **The Layer-A trace tree is mode-dependent, and the gap is documented — not
   hypothetical.** Per Claude Code issue
   [#53954](https://github.com/anthropics/claude-code/issues/53954): only the
   **direct CLI `claude -p`** path emits the full span tree
   (`claude_code.interaction` → `tool` → `tool.execution`); the **Agent-SDK
   streaming / ACP** path emits *only* `claude_code.llm_request` (the high-level
   spans bypass the `mr_(prompt, …)` wrapper); **interactive sessions
   intentionally suppress** the interaction span. This directly hits §8: the way
   JustSearch agents actually run (interactive) is the mode *least* likely to
   emit the subagent-nesting trace today. **Metrics (token/cost) and log events
   (prompts, tool_result) are separate signals and are not implicated** — so the
   §5.1 wins split: the metrics/logs wins are robust; the trace-nesting win is
   `-p`/SDK-conditional and currently buggy for SDK-streaming.
2. **"The dispatch is the unit."** ([futureagi,
   2026](https://futureagi.com/blog/evaluating-claude-sub-agents-2026/)) For a
   delegation-heavy repo, the right unit of agent evaluation is not the session
   but the **dispatch** = supervisor decision + subagent execution + result
   integration — three *independently* failing components. A generic OTel tracer
   "collapses the run into one chat span and loses dispatch attribution"; capturing
   it needs `parent_tool_use_id` chains + subagent type/prompt/tools attributes,
   not vanilla spans. Reshapes Layer C's *unit* (§6.4).
3. **LLM-judge reliability is the consensus reason to demote it — but its residual
   is large, not tiny.** Judge error >50% on complex tasks; expert agreement
   64–68%; deterministic checks (compile/test) are the *first* eval layer, not the
   last — all validating Layer B. The correction: 2026 best practice treats
   **outcome, trajectory, and judge as three first-class layers** ("outcome-only is
   not good enough"). The judge's residual is *decision/trajectory quality* (was the
   dispatch well-scoped; was the result integrated) — genuinely valuable, and the
   subagent three-rubric template (DispatchCorrectness / ScopeFidelity /
   ResultIntegration) is the concrete shape for it.
4. **Goodhart is an *active* research area, and this design has a sharper-than-usual
   exposure.** 2026 work (SpecBench; "Goodhart regime") shows agents reallocate
   effort from non-evaluated to evaluated dimensions, and recommends pairing one
   north-star metric with one anti-gaming guardrail. The exposure unique here:
   **the agents being measured can read their own measurement design** (it lives in
   this repo). That raises the stakes on keeping Layer C diagnostic (§6.4).

Stability note: GenAI semconv is still "Development" status (May 2026); pin
`OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental` so emission tracks the
latest experimental shape rather than a frozen old one.

## 6. Long-term design — telemetry as projection, not fork

### 6.1 The principle this conforms to (already in the codebase, ×4)

The codebase has independently discovered one principle at least four times:

> **One canonical authority per fact. Every other representation is a governed,
> lossy-downward projection of it. Re-deriving a fact that already has a
> canonical owner is a *fork* — a drift defect, not a convenience.**

- **SearchTrace** / `governance/execution-surfaces.v1.json` + the
  `execution-surface` gate (517/553): one authority over search-execution facts;
  explain UI, OTel spans, eval, wire are projections proven lossy-DOWNWARD by
  conformance tests; an undeclared re-modeler fails the build.
- **`governance/observed-happening.v1.json`** + the `observed-happening` gate
  (575): every metric/log/health/job declares exactly one `canonicalSource`, its
  `contributors`, its `projections`; a concept with no projection, or a
  contributor shared by two concepts, fails the build.
- **MetricCatalog** + `MetricSurfaceContractTest` (427): the catalog declares;
  the runtime *derives* RRD archiving + API surface; declaration *is*
  implementation, not a proxy for it.
- **The discipline-gate kernel** (530): a baseline is the one authority for
  "acceptable level"; the ratchet only shrinks it; a silent bump is tamper.

Agent telemetry is an **instance** of this principle, not a new seam. The
redesign is: *stop forking facts the harness and the repo already own; project
from them.* The three layers below each name their canonical authority.

### 6.2 Layer A — Capture is a projection of the harness

The canonical authority for "what the agent did" is **the Claude Code harness
itself**, now exposed natively as an OTLP stream (§5.1). `dispatch.mjs`'s
event-summarizer is a *fork* of that authority — and a lossy one (§2): it
re-derives a worse version of facts the harness emits losslessly (subagent
nesting, tokens, prompts). Design: the OTLP stream becomes the single capture
authority; downstream surfaces read from it lossy-downward. The blocking hooks
stay (enforcement ≠ telemetry, §5.2); `dispatch.mjs` sheds its capture role.

**Mode caveat (from §5.3.1, load-bearing).** The harness emits three independent
OTel signals, and they do *not* degrade together. **Metrics** (token/cost) and
**log events** (prompts, tool_result) are robust across modes — they alone close
the §2 prompt-blank and §4 cost gaps. The **trace tree** (interaction → tool →
subagent nesting) is mode-dependent: full under `claude -p`, only `llm_request`
under SDK-streaming (bug #53954), suppressed in interactive sessions. Since
JustSearch agents run *interactively*, the subagent-nesting win is **not
available by simply flipping the flag today**. The design must therefore treat
Layer A as *two* sub-authorities: a robust metrics+logs authority (adopt now) and
a trace-tree authority that is **blocked on either (a) the harness routing
interactive/stream-json through the interaction-span wrapper, or (b) running the
measured work via `-p`/SDK, or (c) a dispatch-attribution shim** (the futureagi
`parent_tool_use_id` approach, §5.3.2). A0 (§7) probes which holds.

**Scope discipline:** no register/gate here. There is one authority and one
consumer (dev-tooling); an `execution-surface`-style gate would be structure for
a fork-risk the problem doesn't yet have. The conformance obligation is
documentary. (If a second independent capture path ever appears, *that* is when
the register earns its place — not before.)

### 6.3 Layer B — Outcome is a JOIN projected from canonical repo ground truth

This is the core move, and where the prior lineage erred. 276/277 used an
LLM-as-judge to *guess* outcome facts — "did it complete," "build passed,"
"tests added." Those are not opinions; they have canonical owners on disk:

| Outcome fact | Canonical authority (already exists) |
|---|---|
| Did it merge? | git history (branch → `main`) |
| Did the build pass / stay green? | `gradlew build` status + `build-fails-<session>.json` |
| Did a governance gate pass for the edited region? | the gate's SARIF output |
| Did CI pass? | the manual-CI dispatch run record |
| Did the contract close? | tempdoc frontmatter `status:` + checkbox delta |
| Was the work fixed/reverted later? | git churn over subsequent sessions |

Design: a **session-outcome record is a projection assembled by *joining* these
canonical sources**, keyed by session / branch / time-window — exactly as
SearchTrace is a record projected from its `SearchOutcome` sources. The
LLM-judge is **demoted to the residual only** — facts with no hard owner (e.g.
"did this satisfy the user's intent") — and every such field is explicitly
flagged `kind: inference`, lossy-downward, never overwriting a hard fact. This
applies projection-not-fork *to the measurement tooling itself*, and makes
outcomes **verifiable against ground truth** instead of a paid re-derivation.

**Scope discipline:** the sources already exist; the only new structure is the
*join* and the record. No new capture, no product-surface wiring.

**Keying reality (confidence pass, §11 U2).** The join's *keys* are uneven.
`session_id → events / session-report / build-fails-<id>.json` is **clean** (the
id is in the filename/field). But `session_id → {git branch, worktree, tempdoc,
merge commit, CI run}` is **heuristic** — no stored link exists; it would be
time-window + edited-path correlation, fragile under overlapping parallel
sessions. The **weakest link is `session_id → merge commit`** (merge messages cite
tempdoc numbers but carry no session id). So Layer B needs one cheap *prerequisite*
keying step — e.g. a `Session-Id:` git trailer on merge commits, or a
session-close `merge↔session` map — before its hard-fact joins are trustworthy.
This is a small addition, not a blocker, but it must precede B1.

### 6.4 Layer C — Meaning is a diagnostic ratchet on the standalone-validator seam

> **Corrected by the confidence pass (§11, U3).** An earlier draft said "reuse the
> 530 discipline-gate kernel at its early-warning tier." Investigation showed the
> kernel has **no such tier** — it is binary (`--mode gate` fails the build,
> `--mode warn` always exits 0 and never detects). The early-warning concept in
> this repo is a *different, also-existing* seam: standalone register-validators
> (`check-run-renderers.mjs`, `check-inflight-liveness.mjs`) wired in CI as report
> steps, **not** kernel gates. Layer C conforms to *that* seam.

The scorer (PHI, §3) today is a one-shot `(report) → score` with no baseline and
no continuity — "scored once" (424). The closest existing precedent is the
**`relevance-ratchet`** quality gate — and tellingly it is itself *not* a kernel
gate: it "lives with its jseval consumer, not in `gates/`," and it carries a
**tolerance** (`baseline − tolerance_abs`, 0.02 nDCG) precisely because its metric
is noisy across runs. That is the exact shape Layer C needs.

Design: agent-quality (PHI joined with Layer-B outcomes) becomes a **standalone
diagnostic validator** (`scripts/ci/check-agent-quality-trend.mjs`-shaped) with a
tolerance-aware floor + history in `governance/agent-quality-baselines.v1.json`,
wired as a CI **report step, never a build-failing gate**. Three reasons the scope
stops short of build-failing — and one reason it stops short of the kernel:

1. Agent metrics are noisy and non-deterministic; a per-session number must not
   block a merge.
2. 277 proved PHI ≠ quality. The instant PHI becomes a target the agent
   optimizes against, it degrades (Goodhart). The diagnostic tier keeps it
   descriptive by construction — the same reframe 277 already paid for.
3. The defect 424 names is "no continuity / never acts," which a ratcheted
   *baseline* + a *scheduled run* solves. A blocking gate solves a problem the
   repo does not have.
4. *(stops short of the kernel)* The kernel ratchet assumes low-noise,
   reproducible values (LOC, byte counts); a naive PHI baseline would chase
   per-session variance and `--rebalance` would thrash. The standalone validator
   carries its own tolerance (the `relevance-ratchet` precedent), which the kernel
   does not expose per-gate.

This reuses the standalone register-validator seam rather than the kernel (§11,
U3), avoids a parallel bespoke scorer, and preserves the diagnostic-not-target
invariant.

**The unit is the dispatch, not the session (§5.3.2).** For a delegation-heavy
repo (350 `Agent` calls / 31 sessions), per-session aggregates hide the real
failure modes. The richer Layer-C unit is the **dispatch** — supervisor decision
+ subagent execution + result integration — each failing independently, each with
its own residual rubric (DispatchCorrectness / ScopeFidelity / ResultIntegration).
This is *only* derivable once Layer A supplies the subagent trace tree
(`parent_tool_use_id` chains), so dispatch-level scoring is **gated on the Layer-A
trace-tree authority**; per-session PHI is the available-now floor until then.

**Anti-gaming pairing + the self-reference exposure (§5.3.4).** 2026 Goodhart
practice pairs one north-star with one anti-gaming guardrail. Here the pairing is
native: **PHI (process) is the north-star; the Layer-B hard outcome is the
guardrail** — a session cannot raise process-hygiene appearance while the hard
outcome (merged? built? gate-green?) says it failed, because the outcome is owned
by git/CI, not by anything the session controls. The exposure to *name plainly*:
unlike a normal eval target, **the agents being scored can read this very
design** (it is in the repo). That is the strongest argument for the
diagnostic-tier ceiling — a build-blocking PHI gate in a repo whose agents can
read the scoring rubric is a Goodhart trap by construction.

### 6.5 What stays UNBUILT (consumers, not new structure)

The narrative layer (an LLM-summarized per-session story) and the meta "does the
always-loaded rulebook earn its context cost" analysis (620/582) are
**projections/consumers** of Layers A+B — they *read* the joined record; they add
no authority. Recorded as candidate projections, built only when a second real
consumer exists. Dogfooding JustSearch's own search/AI stack over the telemetry
corpus is the natural host for these — but that is a consumer decision, not part
of this design.

### 6.6 Where this design does NOT plug in (the over-reach it avoids)

The product's `observed-happening.v1.json` register and the RRD / `/api/status`
surface govern the **running app's** observed happenings, consumed by the product
UI and gated for the shipped product. Dev-agent telemetry is dev-tooling consumed
by humans reviewing agent work; routing it through the product's observability
surface would be a category error (and would strain `observed-happening`'s
`contributor-shared` rule by mixing dev-meta with product facts). The
`observed-happening` *shape* (canonicalSource + contributors + projections) is
the right model for a **sibling** lightweight register *if* agent-telemetry
projection surfaces ever multiply — not the product register, and not now.

### 6.7 Local-first fit

A local OTLP collector + **Langfuse or Arize Phoenix** (both self-hosted, OSS,
OTLP-native) keeps Layer A on-machine, consistent with the project ethos. No
cloud egress. Layers B and C are local scripts/joins over on-disk canonical
sources, as today.

## 7. Work items (proposed — NOT yet started; awaiting user approval)

> Behavior-changing for every session; do not implement before sign-off.
> The `tempdoc-is-your-contract` rule applies only once items are accepted.
> Items are grouped by the §6 layer they realize; each names the canonical
> authority it projects from.

**Layer A — capture projects from the harness**
- [ ] **A0 — Spike (read-only), the load-bearing experiment.** Set
      `CLAUDE_CODE_ENABLE_TELEMETRY=1` + `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`
      exporting to a throwaway local Jaeger/collector for ONE *interactive*
      session; confirm subagent spans nest and token/cost/prompt land.
      Acceptance: one trace shows an `Agent` dispatch with child
      `llm_request`/`tool` spans (the §2 black box, opened). **This validates or
      kills §5.1's central claim — see §8.** Do this before any other item.
- [ ] **A1 — Local collector + backend.** OTLP collector + Langfuse (or Phoenix),
      self-hosted. Acceptance: traces durable and queryable across ≥3 sessions
      (kills the §1 firehose-no-reservoir).
- [ ] **A2 — Privacy posture (user call).** Which content flags are approved:
      `OTEL_LOG_USER_PROMPTS` / `OTEL_LOG_TOOL_DETAILS` / `OTEL_LOG_TOOL_CONTENT`
      / `OTEL_LOG_RAW_API_BODIES`? Default off; same privacy judgment that
      motivated the original strip. **AskUser.**
- [ ] **A3 — Retire `dispatch.mjs`'s capture role.** Keep the blocking hooks;
      remove the event-summarizer + NDJSON append (or freeze behind a flag for
      one transition window). Update `governance/agent-hooks.v1.json` roles so no
      hook claims a `telemetry` role it no longer owns. Re-run the 592
      `hook-integrity` gate. **Blast-radius note (§11 U5):** `events.ndjson` is
      read not only by the analytics scripts but by **`maintain-doc-hint.mjs`** (a
      *blocking* hook — it scans the stream for the 592 `hook_failure` aggregation
      / nudge dedup). A3 must preserve that consumer (keep a minimal hook-local
      event sink, or migrate the nudge to the OTLP `claude_code.hook` span)
      *before* deleting the append path — else a blocking-hook regresses.

**Layer B — outcome projects from a join of canonical ground truth**
- [ ] **B1 — The outcome-join record.** Assemble a per-session outcome record by
      joining git merge state, `build-fails-<session>.json`, gate SARIF, the CI
      dispatch record, and tempdoc `status:`+checkbox delta, keyed by
      session/branch/window. Every field carries its `kind: fact|inference` +
      its source. Acceptance: outcome is derived from hard sources with **zero LLM
      calls** for any fact that has a canonical owner.
- [ ] **B2 — LLM-judge demoted to residual.** Repoint the 276 evaluator to fill
      only fields with no hard owner, flagged `kind: inference`. Acceptance: it
      never overwrites a Layer-B fact.

**Layer C — meaning is a diagnostic ratchet on the 530 kernel**
- [ ] **C1 — PHI re-homed onto OTLP + outcomes.** `score-session` reads the
      backend (not `events.ndjson`) and joins the Layer-B record. The 277 signal
      definitions survive; only the source changes. Acceptance: `scores.ndjson`
      stops being a one-record graveyard.
- [ ] **C2 — Register agent-quality as a kernel concern at the early-warning
      tier.** A shrinking PHI/outcome-correlation baseline + changeset discipline,
      **not** a build-failing gate (§6.4). Acceptance: per-session scoring runs on
      a schedule with trend continuity; no merge is ever blocked by it.

**Cross-cutting**
- [ ] **D1 — Docs + tier-register.** Update `.claude/rules/hooks-reference.md`
      (`dispatch.mjs` no longer the telemetry sink), `agent-lessons.md`
      (`parent-hooks-dont-fire-in-subagents` gains a telemetry escape hatch via
      native OTel subagent-span nesting — the constraint stands for *hooks*, not
      *observability*), and the tier-register if any enforcement story changes.
      Regen `llms.txt` + `skills-sync`.

## 8. Honest limits / open questions

> **Updated by the confidence pass (2026-06-20, §11).** Several items below moved
> from *assumed* to *measured*. The single remaining unverified item is
> interactive-mode emission — and even its worst case is now bounded.

- **`-p`-mode emission — now MEASURED, not assumed (§11 experiment).** On this
  machine (Claude Code **2.1.183**), a live `claude -p` probe to a local OTLP
  receiver emitted **all three signals**: traces (`claude_code.interaction` →
  `tool` → `tool.execution` + `llm_request` + `gen_ai` semconv), metrics
  (`token.usage` + `cost`), logs (`user_prompt` content, `tool_result`,
  `tool_decision`). The `-p` span tree and the metrics+logs wins are **confirmed
  real here**, not just documented.
- **Interactive-mode emission — the ONE remaining unverified item, but bounded.**
  Phase-1 grep found **no `-p`/SDK/stream-json usage** in this repo's dev tooling:
  agents run as plain **interactive** sessions, which #53954 predicts suppress the
  *trace tree*. I could not drive an interactive TUI headlessly, so this needs one
  user-run confirmation (procedure in §11). **Worst case is bounded:** even if
  interactive emits no traces, metrics + logs are *separate exporters* (the bug is
  trace-only), so the **two biggest gaps — blank prompts (§2) and orphaned cost
  (§4) — still close** via `user_prompt` logs + `token.usage`/`cost` metrics. Only
  the *subagent-nesting trace* win and *dispatch-granular* Layer C (§6.4) are
  trace-gated, i.e. conditional on running measured work via `-p`, a harness fix,
  or a `parent_tool_use_id` shim.
- **Subagent-nesting span — now OBSERVED (§11.5 implementation pass).** A `-p`
  probe *with* a Task dispatch, decoded by the local sink, showed the nested tree
  `interaction → tool(Task) → tool.execution → tool(subagent's Bash) →
  tool.execution + llm_request`, and metrics attributed cost/tokens by
  `query_source: main|subagent` + `agent.name`. The trace-nesting win is real on
  `-p`. (Interactive-mode emission remains the one open item.)
- **Beta + standard-in-flux surface.** Traces + span names are beta (*"may change
  between releases"*) and GenAI semconv is still "Development" status (§5.3); the
  metrics + log-events signals are stable. A1 dashboards must not hard-depend on
  span-name stability; pin `OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental`.
- **Generic OTel may under-capture dispatch attribution (§5.3.2).** Even where the
  trace tree emits, vanilla OTLP can collapse a dispatch into one chat span; full
  dispatch-level eval (§6.4) may need the richer `parent_tool_use_id` +
  subagent-attribute shape, not stock spans. Verify in A0 before promising
  dispatch-granular Layer C.
- **Flush on short sessions.** CLI batches and flushes on a bounded timeout;
  killed processes drop the buffer. Lower `OTEL_*_EXPORT_INTERVAL` for the local
  setup.
- **Does this duplicate 427?** No. 427 is worker→head **product** metric
  replication (catalog-driven). 622 is **dev-agent** telemetry (Claude Code
  sessions working on the repo). Different producers, different consumers.

## 9. Reach of this design (the step-back judgment)

### 9.1 This is an instance of an existing seam — conform, don't parallelize

The design introduces **no new architectural seam**. It is the codebase's
existing *canonical-authority-and-projection* principle (§6.1) applied to a new
subject (dev-process facts). Wherever this doc could have invented structure — a
capture register, an outcome schema, a scorer-with-baseline — an existing form
was reused instead: the harness as capture authority (Layer A), the
join-from-canonical-sources record shaped like SearchTrace (Layer B), the 530
discipline-gate kernel ratchet (Layer C). Conforming *was* the design. The agent
who first surveyed this proposed wiring agent telemetry into the product's
`observed-happening` register + RRD + `/api/status`; §6.6 rejects that as a
category error — conformance is to the *shape*, not a forced graft onto the
product's surface.

### 9.2 The principle this reveals, named plainly

The existing registers (553, 575, 427, 560) all assume **the canonical owner of
a fact is an in-process catalog or type**. Dev-agent telemetry shows the same
principle holds one step further out:

> **The canonical owner of a fact may live *outside the app's process boundary* —
> git, the Claude Code harness, the CI system. An external canonical source is
> still a canonical source; projecting from it still beats re-deriving it.**

"Projection not fork" was discovered for *runtime product facts*; it applies
unchanged to *facts owned by external systems the repo integrates with*. That is
the genuine generalization this problem reveals.

### 9.3 Where it already applies — and is already violated

- **The agent-analytics LLM-judge (276/277)** re-derives merge/build/test
  outcomes that git and the build system own authoritatively. A live instance of
  the violation this design corrects (Layer B is the fix).
- **`costs.ndjson`** parses transcript JSONL to re-derive token counts the
  harness emits canonically as `claude_code.token.usage`. Same fork, same fix
  (project from OTel — Layer A).
- **Any future agent dashboard** that recomputes outcome instead of reading the
  Layer-B record would re-introduce the fork.

### 9.4 What NOT to build now (recognizing ≠ building)

Per the deliberate separation of *recognizing a principle* from *building general
structure*: do **not** now build a generic "external-source projection register,"
nor lift the `execution-surface` gate to cover external authorities. The present
problem needs exactly one capture authority, one outcome join, and one diagnostic
ratchet. The principle and its candidate scope (§9.2–9.3) are recorded here; the
generalized structure is warranted only when a *second* external-authority
projection concern actually appears. Recording it now is what keeps the eventual
generalization a deliberate move rather than a surprise.

## 10. Sources

- Claude Code Observability with OpenTelemetry (official):
  `https://code.claude.com/docs/en/agent-sdk/observability`
- Claude Code Control & Observability with OpenTelemetry — General Analysis:
  `https://generalanalysis.com/guides/claude-code-control-observability-opentelemetry`
- OpenTelemetry GenAI Semantic Conventions — agent spans:
  `https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/`
- SigNoz: Claude Code Monitoring with OpenTelemetry:
  `https://signoz.io/docs/claude-code-monitoring/`
- Best LLM tracing tools for multi-agent systems (2026) — Braintrust:
  `https://www.braintrust.dev/articles/best-llm-tracing-tools-2026`
- Best AI observability tools for autonomous agents 2026 — Arize:
  `https://arize.com/blog/best-ai-observability-tools-for-autonomous-agents-in-2026/`
- What Is Agent Observability? 2026 guide — MLflow:
  `https://mlflow.org/articles/what-is-agent-observability-a-2026-developer-guide/`

Research pass 2026-06-20 (§5.3):
- Claude Code issue #53954 — OTel enhanced telemetry beta: SDK-streaming emits
  only `llm_request`; interactive suppresses interaction span:
  `https://github.com/anthropics/claude-code/issues/53954`
- Evaluating Claude Sub-Agents: The Dispatch Is the Unit (2026) — futureagi:
  `https://futureagi.com/blog/evaluating-claude-sub-agents-2026/`
- OpenTelemetry GenAI semantic conventions — agent spans + stability:
  `https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/`
- LLM-as-Judge in 2026: evaluation techniques + reliability — DeepEval:
  `https://deepeval.com/guides/guides-llm-as-a-judge`
- SpecBench: Measuring Reward Hacking in Long-Horizon Coding Agents (2026):
  `https://arxiv.org/html/2605.21384`

## 11. Confidence pass (2026-06-20) — uncertainties reduced before implementation

A pre-implementation pass (read-only investigation + one read-only telemetry
experiment; no feature code) to convert assumptions into evidence.

### 11.1 Verdicts (U1–U5)

- **U1 — does native OTel emit usefully?** *Resolved (partial).* `-p` mode emits
  the full span tree + metrics + logs on v2.1.183 (measured, §8). Run-mode here is
  **interactive** (grep: no `-p`/SDK in dev tooling), which #53954 says suppresses
  the trace tree — *unverified*, needs one user run. Metrics+logs degrade
  independently, so the §2/§4 gaps close even in the worst case.
- **U2 — can a session be keyed to ground truth?** *Resolved.* `session_id →
  events/report/build-fails` clean; `→ merge/tempdoc/branch/CI` heuristic; weakest
  link `session_id → merge commit`. Layer B needs a cheap keying prerequisite
  (§6.3). Refines Layer B; does not block it.
- **U3 — kernel non-blocking tier for PHI?** *Resolved — design corrected.* The
  kernel is binary (gate/warn), no early-warning tier; early-warning is a separate
  standalone-validator seam, and the noisy-metric precedent is the
  tolerance-bearing `relevance-ratchet`. Layer C re-homed onto that seam (§6.4).
- **U4 — token/cost in the chosen mode?** *Resolved.* `claude_code.token.usage` +
  `claude_code.cost` metrics emitted in the `-p` probe; metrics exporter is
  independent of the trace bug, so cost survives even interactive worst-case.
- **U5 — `events.ndjson` blast radius for A3?** *Resolved.* Consumers: the
  analytics scripts **and** `maintain-doc-hint.mjs` (a blocking hook). A3 must
  preserve the hook consumer (§7 A3).
- **U6 — privacy posture.** Unchanged: a user decision (A2), not investigable.

### 11.2 Experiment record (read-only, torn down)

Stood up a stdlib Python OTLP receiver on `:4318`, ran one bounded `claude -p`
probe with `CLAUDE_CODE_ENABLE_TELEMETRY=1` + beta/trace/metrics/logs exporters +
`OTEL_LOG_USER_PROMPTS=1` + `OTEL_LOG_TOOL_DETAILS=1` +
`OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental`. Result: 14 OTLP POSTs
(5 metrics / 5 logs / 4 traces) carrying the marker set in §8. Receiver + scratch
dir removed; no repo files changed.

### 11.3 The one open item — interactive-mode confirmation (user-runnable)

To close U1 fully, run a short **interactive** `claude` session with the same env
pointed at a local receiver, then check which of `/v1/{traces,metrics,logs}`
receive data. Expected per #53954: metrics+logs yes, trace tree suppressed. This
is the only datapoint the pass could not self-serve (no headless TTY).

### 11.4 Critical confidence rating (0–10)

| Layer / item | Rating | Basis |
|---|---|---|
| **A — metrics+logs core** (prompts, cost, tool detail) | **8** | Emission measured in `-p`; mode-independent exporters; closes the two biggest gaps |
| **A — subagent-nesting trace win** | **4** | Trace-gated; interactive mode likely suppresses it; needs `-p`/harness-fix/shim + a nesting probe |
| **B — outcome join** | **6** | Sources confirmed on disk; weak `session→merge` keying needs a cheap prerequisite first |
| **C — diagnostic scoring** | **7** | Clear home found (standalone validator + tolerance), design corrected off the kernel |
| **A3 — retire dispatch capture** | **6** | Consumer map known; the `maintain-doc-hint` coupling is the one thing to handle |
| **Overall remaining work** | **6.5/10** | Up from ~4 pre-pass. The headline metrics+logs value is high-confidence; the one genuine risk (interactive trace suppression) is now bounded, not fatal — it degrades a *secondary* win, not the core |

**Net:** no surprise can now invalidate the *core* of the design. The worst
realistic outcome (interactive emits no traces) costs the subagent-nesting/​
dispatch-granular win but still closes the prompt-blank and orphaned-cost gaps —
so implementation can proceed on Layer A's metrics+logs core and Layer C with high
confidence, with the trace-tree win explicitly conditional pending §11.3.

## 12. Implementation pass (2026-06-20) — Phase 1 as-built

Plan: `~/.claude/plans/spicy-mixing-blossom.md`. Locked decisions: full-content
capture, this-machine-only (user `~/.claude/settings.json`), Docker-free.

**Built (Layer A capture):**
- `scripts/agent-analytics/otlp-sink.py` — Docker-free local OTLP/HTTP receiver
  that decodes protobuf (via `opentelemetry-proto`) → normalized NDJSON in
  `tmp/agent-telemetry/otlp/{traces,metrics,logs}.ndjson`. The canonical local
  sink Phase 2 ingests from.
- `scripts/agent-analytics/otlp-viewer/index.html` — Docker-free local dashboard
  (span-tree waterfall + cost/token table by source + logs). Replaces Phoenix,
  which **fails to build on Python 3.14** (`sqlean-py` wheel) — recorded so the
  next agent doesn't retry it.
- `~/.claude/settings.json` `env` block enabling telemetry with the four
  full-content flags + the semconv stability pin, pointed at the local sink.

**Measured & browser-validated (real UI in Chrome):** one `claude -p` probe with a
Task subagent emitted 11 spans / 10 metrics / 79 logs; the viewer rendered the
nested subagent span tree, cost attributed `main` vs `subagent`/`general-purpose`
($0.31 to the subagent), 225k tokens by type, and prompt + `tool_input`/
`tool_result` full content. Confirms: subagent-nesting (was inferred), cost
metrics (kills the §9.3 transcript fork), full-content capture (closes §2).

**Operational note:** the sink must be running to receive exports
(`python scripts/agent-analytics/otlp-sink.py`); when down, Claude Code drops the
batch harmlessly. **Open item unchanged:** interactive-mode trace emission (§11.3)
— with the env now in user settings, a fresh interactive session + running sink
will reveal whether the trace tree emits or only metrics+logs; the core wins hold
either way.

**Built (Phase 2 — Layer A ingest, parallel-run; `events.ndjson` untouched):**
- `lib/telemetry-io.mjs` — `loadEventsFromSource('otlp')` + `loadEventsFromOtlp()`
  normalize the decoded OTLP log stream into the legacy event shape (incl.
  reconstructing `input_summary` per tool); `loadCostsFromOtlp()` aggregates the
  harness-computed `cost.usage` + `token.usage` by session (and `query_source`).
- `cost-session.mjs --source otlp` and `analyze-session.mjs --source otlp` —
  validated: cost $0.8515 (main $0.47 / subagent $0.38) straight from the harness
  metric (no transcript parse — §9.3 fork dead); a full `agent-session-report.v1`
  built from native-OTel events; `user_prompt_submit` now carries prompt content.

**Built (Phase 3 — Layer B outcome join):**
- `record-merge.mjs` — appends the `session_id → merge_commit` link to
  `session-merges.ndjson` (the §11 U2 weak-key prerequisite; run at merge time).
- `outcome-session.mjs` — the fact-authoritative writer of `outcomes.ndjson`:
  joins `merged` (git/session-merges), `build_last_status` (build-counter),
  `tempdocs` (frontmatter `status`+checkbox delta), `gates` (governance SARIF,
  honestly tagged timestamp-correlated). Every field `kind:'fact'` + `source`;
  zero LLM. Validated on a 616-editing session → `status:open, 7/13`.
- The LLM-judge (`evaluate-session.mjs`) is DEMOTED to a residual producer —
  repointed to `judge-outcomes.ndjson`, folded into `outcomes.ndjson` only as the
  `inference` block (`kind:'inference'`), never overwriting a fact (§6.3).

**Built (Phase 4 — Layer C diagnostic):**
- `scripts/ci/agent-quality-baselines.v1.json` (in `scripts/ci/` with the other
  ratchet baselines, NOT `governance/`) + `scripts/ci/check-agent-quality-trend.mjs`
  — a standalone, tolerance-aware, **report-only** trend validator (exit 0 in
  `--mode warn`; `--mode gate` opt-in + discouraged per §6.4). Validated: renders
  the trend table, exits 0 on sparse data. CI wiring is a **local command** —
  scores.ndjson is machine-local, so it is a local diagnostic, not a CI step.

**Local operator commands:**
```
python scripts/agent-analytics/otlp-sink.py            # start the local sink (now AUTO-started per session by the otlp-sink-ensure SessionStart hook; run manually only if you disabled hooks)
python -m http.server 4319 -d tmp/agent-telemetry/otlp # + copy otlp-viewer/index.html in → browser UI
node scripts/agent-analytics/analyze-session.mjs --all --source otlp
node scripts/agent-analytics/cost-session.mjs --all --source otlp
node scripts/agent-analytics/outcome-session.mjs --all
node scripts/ci/check-agent-quality-trend.mjs
```

**Not done (Phase 5, GATED):** retiring `dispatch.mjs`'s capture role is
behavior-changing for every session and awaits explicit go-ahead; the
`maintain-doc-hint` consumer of `events.ndjson` (its only need:
`session_start.transcript_path`) must be preserved first (§7 A3).

## 13. Post-implementation review fixes (2026-06-20)

A critical self-review found four substantive issues; all fixed + validated:
- **Fix 1 (keying inert):** `record-merge.mjs` was wired nowhere → `merged` always
  false. Now invoked best-effort from `scripts/dev/remove-worktree.cjs` (the merge
  teardown step) and documented in `branch-safety.md` §Merge Workflow.
- **Fix 2 (schema regression):** changing `outcomes.ndjson` to `{facts, inference}`
  broke `correlate-signals` (read top-level `task_completion`) and left
  `generate-dashboard` non-populating. Updated both to read `inference.*`; added
  `task_type` to `inferenceBlock`. Validated: dashboard renders, correlate reads
  the new shape.
- **Fix 3 (dishonest gates fact):** `gatesFact` read the shared SARIF
  unconditionally while claiming "timestamp-correlated." Now actually correlates —
  attributes the SARIF only if its mtime is within the session's event window,
  else `unknown`. Validated (an April session → `unknown`).
- **Fix 4 (unvalidated reconstruction):** the OTLP `input_summary` rebuild was only
  Bash-tested. A Read/Write probe confirmed the shapes AND surfaced a real bug —
  analyze-session reads `file_path` off `pre_tool_use`, but the mapping put
  `input_summary` only on `post_tool_use`, so file hotspots came out `"unknown"`.
  Fixed by joining `tool_use_id` and attaching `input_summary` to `pre_tool_use`;
  this also threads `model` via a synthetic `session_start` (the prior `model:null`).
  Validated: `file_reads.by_file` resolves the real path, `model` populates.
- **Minor:** `otlp-sink.py` now rotates each stream past 20 MB (no unbounded growth).
- **Run-and-verify (post-fix end-to-end run):** running the full pipeline surfaced a
  real defect — the 20 MB rotation moved data to `*.prev.ndjson`, but
  `loadEventsFromOtlp`/`loadCostsFromOtlp` read only `*.ndjson`, so rotated sessions
  were silently orphaned and logs vs metrics covered different session sets. Fixed
  with a `loadOtlpStream` dual-read helper (mirrors the legacy `loadEvents` `.prev`
  read); verified events↔cost session sets now match. Also confirmed correct at
  scale: 36 ndjson sessions → 21 scored (15 correctly filtered &lt; MIN_TOOL_CALLS),
  a traced session's counts are internally consistent (pre=post+failures,
  failure_rate exact, THRASHING justified by 67× same-file edits).

**Known limitations (accepted):**
- **`current-session-id` raciness:** `record-merge` reads
  `tmp/agent-telemetry/current-session-id`; a `claude -p` or subagent SessionStart
  can overwrite it, so a merge could mis-key if such a process ran between the
  agent's last activity and the merge. Low-frequency; backfillable.
- **Env scope:** telemetry is enabled in user-scoped `~/.claude/settings.json`, so
  it applies to *every* project on this machine (all export to the JustSearch
  sink), not just JustSearch — a deliberate consequence of the "this-machine"
  choice over committing to `settings.local.json`.
- **Pre-existing, out of scope:** `prose-tier-register` false-positives on the
  gitignored hook-written `compaction-state.md` when it lists a tempdoc filename
  containing "always"/"never" (logged to `observations.md`); harmless in CI.

## 14. Phase 5 — resolved as a reversible flag-gated split (NOT a retirement)

Investigation (agent-hooks manifest + consumers + `hook-integrity` gate) overturned
A3's "delete dispatch's capture" premise: **`dispatch.mjs` is the sink-independent
reliable substrate, not a lossy duplicate.** It uniquely carries data native OTel
does not provide and blocking hooks depend on — `session_start.transcript_path`
(`maintain-doc-hint`), `subagent_stop.agent_transcript_path` (subagent cost/context
discovery), `session_end` per-session state cleanup (`build-counter` hygiene),
`stop.stop_hook_active` (loop guard) — and its `input_summary` powers the **ndjson
fallback** analytics when the OTLP sink is down. Deleting it would break blocking
hooks and make all analytics sink-dependent.

**Resolution — A3 in its sanctioned "freeze behind a flag" form:**
- `JUSTSEARCH_DISPATCH_SKELETON_ONLY` (in `lib/hook-base.mjs` `dispatchSkeletonOnly()`,
  read by `dispatch.mjs`). Default unset = full parallel-run capture (no change).
  Set to `1` = OTel-primary cutover: dispatch emits only the irreducible
  lifecycle/skeleton (the unique carriers above + bare tool-call events for
  counting/heartbeat) and skips the rich tool-summarizing (`input_summary` /
  `response_summary` / `error_summary` / prompt excerpt / `instructions_loaded`)
  that OTel logs supersede. **Validated deterministically** (crafted-stdin unit
  test): skeleton mode drops the rich fields and `instructions_loaded` while
  preserving `session_start.transcript_path`; default mode is unchanged;
  `hook-integrity` passes (it does not inspect payload; dispatch stays
  `role:telemetry`, no relabel).
- **Steady-state policy:** parallel-run is the principled end state, not a
  transition window. OTel is the rich/primary source (`--source otlp`); the ndjson
  skeleton is the always-on, sink-independent fallback. `dispatch.mjs` is **not
  deleted.**

**Operational finding (cutover mechanics):** a probe showed Claude Code curates the
hook subprocess env to the settings.json `env` block — an ad-hoc shell
`JUSTSEARCH_DISPATCH_SKELETON_ONLY=1` does NOT reach hooks. So the cutover is
performed by adding the flag to `~/.claude/settings.json` `env` (the same place
telemetry is enabled), which is the intended config-flip mechanism.

**Cutover criteria (flip the flag only when both hold):** (a) interactive sessions
confirmed to emit OTel **logs+metrics** (the real precondition — separate exporters
from the suppressed interaction-span *trace*; §11.3 smoke: sink up + one fresh
interactive turn → `tmp/agent-telemetry/otlp/logs.ndjson` grows with
`tool_result`/`user_prompt`); (b) `--source otlp` analytics validated on ≥1 real
interactive session. Until then the flag stays unset (full parallel run). This is
the one remaining user-runnable step (no headless TTY).

**Pre-existing (other-agent WIP, not this work):** `hook-integrity` currently fails
on `hook:tempdoc-age-hint.mjs` (a tier-register marker without a manifest entry,
from concurrent 620 work on shared `main`) — logged to `observations.md`, not fixed
here.

## 15. What we could do with this — research-grounded ideas (2026-06-21 pass)

Pure-research pass (3 rounds, web-sourced) on what the telemetry substrate enables.
Recorded as a menu, not a commitment — nothing is in production, no users, no rush.

### 15.0 The framing

The substrate records *what agents did on this repo*, joined to ground truth. The
field's center of gravity in 2026 is **the feedback loop** — turning execution
traces into agent improvements — and this repo is unusually well-positioned because
it **already has the sinks** the loop needs: `.claude/skills/`, `agent-lessons.md`,
`agent-postmortems.md`, the discipline-gate/ratchet/changeset culture, the `jseval`
eval harness, and `consult-doc-hint`'s region-keyed just-in-time doc delivery.
Unique property: here the telemetry's *subject* (Claude Code agents) and its
*consumer* (the same dev + agents) are one loop — so the marquee value is closing
it. Three honest tiers: **observe better → close the loop → measure the machine.**

### 15.1 The 2026 landscape (what the research says)

- **Trace → memory/heuristics (self-improvement).** Trajectory-Informed Memory
  (IBM), Experiential Reflective Learning (+7.8% on Gaia2), Galileo Signals
  (failure-mode mining from traces). Extract task- and subtask-level "tips"/
  heuristics from trajectories, retrieve as contextual memory.
- **Trace → reusable skills (a whole subfield).** SkillOpt, Trace2Skill, EvoSkill
  (skills from *failed* trajectories), SkillComposer, AutoRefine — automatically
  synthesize reusable skills from trajectories as bounded add/delete/replace edits,
  **accepted only via a held-out gate that proves improvement.**
- **Trace → eval/regression.** Production failures → eval cases; the suite grows
  from real runs; CI gates block regressions (Braintrust). **Deterministic replay**:
  a successful trace becomes a regression test (stub LLM+tool outputs, replay,
  flag divergence) — fast/cheap/deterministic CI safety net.
- **Memory ≠ more context.** The 2026 memory literature is emphatic: agents need a
  tiered memory system with **just-in-time retrieval + metadata filtering**, NOT a
  saturated prompt (context bloat is the dominant failure: needle-in-haystack,
  recency dominance, context pollution). **This resolves the 622↔620 tension**:
  mined lessons belong in region-keyed JIT delivery (the existing `consult-doc-hint`
  / skill-load path), never in always-loaded context (which 620 is shrinking).
- **The loop is dangerous.** Reward hacking is pervasive (73.8% / 46.8% proxy-gain-
  without-real-gain on two benches); feedback loops drive in-context reward hacking;
  self-training on agent data can erode safety. Mitigation = held-out gates,
  human-in-the-loop, outcome-anchored (not proxy-anchored) rewards. The repo's
  gate/changeset/ratchet culture is the natural home for this discipline, and the
  earlier §6.4 insight (agents can read their own scoring → Goodhart) is reinforced.
- **Cost.** Agentic = the most token-intensive pattern (~7× tokens; turn-10 ≈ 7×
  turn-1). Rising token counts = prompt bloat → a *cost-side* echo of 620.
- **UX.** Session replay + "time-travel," AI "smart-chapter" narrative summaries,
  SQL-over-traces, aggregate dashboard + drill-down trace.

### 15.2 Ideas, by category (effort · value)

**Polish (my code):**
- P1. Recalibrate the PHI/trend baselines (currently seeds) from real flowing data —
  continuous-eval practice. (low · med)
- P2. Auto source-selection (OTLP if sink fresh, else ndjson) — drop the manual
  `--source` choice. (low · med)
- P3. One `telemetry` CLI wrapping analyze→score→cost→outcome→trend. (low · med)
- P4. Viewer: add an AI "smart-chapter" narrative + a session list + filter/SQL. (med · med)

**Simplify:**
- ~~S1. Make the sink an auto-started managed service (vs a manual python process).~~
  **DONE (2026-06-21).** SessionStart hook `otlp-sink-ensure.mjs` (manifest
  `governance/agent-hooks.v1.json`, role `telemetry`, async) idempotently probes
  `127.0.0.1:4318` and spawns `otlp-sink.py` **detached** only if nothing is already
  listening — so capture is automatic and a SHARED persistent daemon survives across
  concurrent sessions. No SessionEnd kill (that would drop capture for other live
  sessions). CI-safe: `runHook()` returns early under `JUSTSEARCH_DISABLE_HOOKS=1`
  (which the hook-integrity load-test sets), so the gate never spawns a stray sink.
  Validated: spawn→`:4318`=200, re-run idempotent (one listener), disabled-mode no-op,
  hook-integrity green. (Restoring this also re-declared the hand-wired `tempdoc-age-hint`
  in the manifest — a pre-existing wiring drift the regen surfaced.)
- S2. Phase-5 cutover (flag flip) eventually collapses the dual-capture once OTel
  is confirmed primary — already designed (§14). (low · low)

**Extend — the feedback loop (the marquee tier):**
- E1. **Failure-mode mining → postmortem/observation candidates.** Cluster recurring
  failures across sessions (the WebFetch / tab-staleness / 25K-Read-limit classes
  already visible) → *propose* `agent-postmortems.md` / `observations.md` entries.
  (med · high) [Galileo Signals; EvoSkill]
- E2. **Trajectory → gated skill edits.** Mine recurring successful patterns →
  *propose* `.claude/skills/` add/delete/replace edits, accepted ONLY if a held-out
  check shows improvement, human-approved via a changeset. (high · high) [SkillOpt /
  Trace2Skill / SkillComposer — maps onto the repo's ratchet/changeset culture]
- E3. **Replay regression from traces.** A successful OTLP trace → a deterministic
  replay regression (stub model+tool outputs); CI gate on divergence. Existing seam
  to extend: `scripts/ci/run-agent-resume-replay-matrix.mjs`. (high · high) [replay]
- E4. **Outcome-anchored eval dataset.** Failed/degraded sessions (from the Layer-B
  outcome join) → eval cases for the judge rubrics; the suite grows from real runs.
  (med · high) [Braintrust continuous-eval]
- E5. **Delegation & model-routing ROI.** cost-by-`query_source`/model + outcome →
  "which subagent dispatches / which model routes actually paid off." (med · high)

**New-UX (the dogfood + the meta):**
- N1. **Dogfood: index the agent telemetry with JustSearch itself.** The telemetry
  corpus (sessions, prompts, outcomes) *is* exactly the unstructured local data the
  product indexes — so semantic/RAG search over "what did agents do / how did we
  solve X before" gives episodic memory via the app's own hybrid retrieval, AND
  becomes the retrieval backbone for E1–E2. Uniquely fits the app's identity. (high
  · high) [episodic+semantic memory]
- N2. **Session narrative / report card.** Per-session LLM "story" + a card (cost,
  outcome, friction, what-it-did). (med · med) [smart chapters / report card]
- N3. **Meta — does the discipline machinery earn its cost?** Use telemetry to
  measure which always-loaded rules actually fire / get read / change behavior, and
  the cost-side token-bloat trend — feeding the 620 budget decisions with data
  instead of argument. (med · high; repo-specific) [DX Core 4 effectiveness; cost]

### 15.3 The non-negotiable discipline (applies to every E-idea)

Because the loop is provably Goodhart-prone and the agents can read their own
telemetry, every feedback idea must be: **propose, never auto-apply** (a human or a
gate disposes); **held-out-gated** (accept a mined lesson/skill/edit only if it
demonstrably improves — SkillOpt's pattern, the repo's ratchet shape); **outcome-
anchored** (Layer-B hard facts, not the proxy PHI); and **JIT-retrieved, never
always-loaded** (memory tiers + `consult-doc-hint` delivery — resolves 620). This is
the same anti-Goodhart spine as §6.4, extended from scoring to the whole loop.

### 15.4 Single highest-leverage thread

**A gated, dogfood-backed lesson/skill loop:** telemetry → (N1) indexed as episodic
memory in JustSearch's own search → (E1/E2) mine recurring failure/success patterns
into *proposed* postmortem/skill edits → held-out-gated + human-approved via a
changeset → delivered JIT by `consult-doc-hint`/skill-load (not always-loaded). It
closes the field's marquee feedback loop using *only* infrastructure the repo
already has (search, skills, gates, JIT delivery), it dogfoods the product on its
most-fitting corpus, and it is disciplined against the exact failure mode (Goodhart/
reward-hacking) the research flags as pervasive.

### 15.5 Sources (2026-06-21 pass)
- Trajectory-Informed Memory Generation: `https://arxiv.org/abs/2603.10600`
- Experiential Reflective Learning: `https://arxiv.org/abs/2603.24639`
- SkillOpt: `https://arxiv.org/pdf/2605.23904` · Trace2Skill: `https://arxiv.org/pdf/2603.25158`
- EvoSkill: `https://github.com/sentient-agi/EvoSkill` · SkillComposer: `https://arxiv.org/html/2606.06079`
- Agent memory survey: `https://arxiv.org/html/2603.07670v1` · SSGM governed memory: `https://arxiv.org/html/2603.11768v1`
- Reward Hacking in the Era of Large Models: `https://arxiv.org/abs/2604.13602`
- Braintrust continuous-eval-from-traces: `https://www.braintrust.dev/articles/continuous-evaluation-ai-agents-trace-classifications-2026`
- Deterministic replay for agents: `https://tianpan.co/blog/2026-04-12-deterministic-replay-debugging-non-deterministic-ai-agents`
- Developer productivity metrics 2026 (DORA/SPACE/DevEx/DX Core 4): `https://zylos.ai/research/2026-02-07-developer-productivity-metrics/`
- Datadog session-replay AI summaries / smart chapters: `https://www.datadoghq.com/blog/ai-summaries-and-smart-chapters/`

## 16. Long-term design — the feedback loop as a source + acceptance adapter on the existing knowledge pipeline

### 16.0 The design problem (and what already exists)

§15's marquee idea — telemetry → mined lessons/skills → fed back to agents — risks
becoming a parallel "agent memory system" (the path the 2026 self-improvement
literature defaults to: dump mined memory into context). Investigation shows that
would be a **fork**: tempdoc **620 already built the complete produce→govern→deliver
pipeline for agent knowledge**, and the discipline-gate kernel already provides
gated, audited acceptance. So the present problem is not "build a memory system" but
"give the loop the correct shape so it *extends* that pipeline." Scope: a design,
not built structure — there is no captured-session data to mine yet.

What already exists (the seams to conform to):
- **Delivery** — 620 Move 2: `governance/consult-register.v1.json` (region → governing
  docs/recipes) + `consult-doc-hint` (JIT push) / `maintain-doc-hint` (Stop-block if a
  governed region is edited without maintaining its doc). Skills are a projection
  (`skills-sync.mjs`). Always-loaded growth is ratcheted (620 Move 3
  `always-loaded-budget.v1.json`).
- **Acceptance** — the discipline-gate kernel's **changeset + ratchet**: growth is
  accepted only via a classified, justified, git-tracked changeset under
  `gates/<id>/.changesets/` (the audited "accept only with justification" shape).
- **Held-out oracles** — `jseval` relevance-ratchet (nDCG floor) for retrieval
  quality; the replay seam (`scripts/ci/run-agent-resume-replay-matrix.mjs`,
  deterministic `events.ndjson` replay) for behavioral regressions.
- **Provenance** — the `execution-surfaces.v1.json` projection register shape
  (source→producer→projection→consumer, projection-not-fork, 553).

### 16.1 The design — the loop is a SOURCE + an ACCEPTANCE step; everything else is reuse

Five stations; only the first two are new, and both are thin adapters:

| Station | What it does | Conforms to (existing seam) |
|---|---|---|
| 1. **Source** (new, thin) | Telemetry aggregation surfaces a *candidate* — a recurring failure-class or repeated successful pattern — as a provenance-carrying **draft** (which sessions, what Layer-B outcome, proposed lesson). A projection, never an assertion. | analytics scripts (analyze/outcome) + projection-not-fork (553): a candidate is a derived view of canonical sources, carrying provenance. |
| 2. **Acceptance** (new, thin) | A candidate becomes an accepted lesson/skill ONLY via a human-approved **changeset** recording its provenance + justification, gated where an oracle exists. | discipline-gate kernel **changeset + ratchet** — the exact "accept only with audited justification" shape. |
| 3. **Held-out validation** (reuse, *where applicable*) | A retrieval/behavioral lesson is accepted only if it demonstrably improves a held-out task set. | `jseval` relevance-ratchet (retrieval); replay seam (behavioral). |
| 4. **Delivery** (reuse) | An accepted lesson reaches agents JIT (region-keyed) or on-demand (skill) — never a free-text always-loaded append. | 620 Move 2 `consult-register` + hooks; or a skill via `skills-sync`; rarely an always-loaded rule under the Move 3 budget ratchet. |
| 5. **Retrieval backbone** (reuse / dogfood, *optional*) | Mining (1) and JIT delivery (4) both want "search over past sessions." | the app's own hybrid search (N1) — telemetry IS the unstructured local data the product indexes. Clustering suffices until scale demands it. |

So the loop adds exactly **a candidate-record projection** and an **acceptance-
changeset adapter**; stations 3–5 are existing infrastructure. No new delivery
system, budget, gate framework, or memory store.

### 16.2 The honest oracle limit (scope-correcting the research)

The self-improvement literature (SkillOpt et al.) assumes a clean automatic oracle
("accept only if a held-out score improves"). That holds for *retrieval* lessons
(jseval nDCG) and some *behavioral* ones (replay divergence) — but **most
agent-process lessons have no automatic oracle** (e.g. "use offset/limit on large
Reads", "brief subagents inline"). For those, acceptance is the repo's existing
**judgment-tier `before-appending-to-rules` gate + a human-approved changeset** —
the same honor-system-with-audit-trail the hand-authored side already uses. So
acceptance is **changeset-always, held-out-where-possible**, not metric-gated-always.
This keeps the loop honest rather than pretending a number validates everything.

### 16.3 What NOT to build now (scope discipline)

The present problem (no data; a design request) does **not** warrant a new
`agent-lessons-registry.v1.json`, an `agent-lesson-provenance` gate, a dedicated
`gates/agent-lessons-optimized/` baseline, or indexing telemetry into the product.
Those are real options *once the loop runs on real data*; building them now is
structure for cases the problem does not yet include. The candidate-record shape and
the acceptance-changeset are authored **only when there are real sessions to mine**,
and even then as thin adapters on stations 3–5, not new frameworks.

### 16.4 Reach — the principle this reveals

This design introduces no new seam; it is 620's produce→govern→deliver pipeline +
the discipline-kernel changeset, fed by a new source. It reveals one principle:

> **Governed agent-knowledge is source-agnostic.** Whether a lesson is hand-authored
> or telemetry-mined, it passes the same acceptance (held-out-validated where an
> oracle exists, human-approved changeset always) and reaches agents through the
> same governed delivery (projection / JIT / budgeted always-loaded) — never as an
> ungated, always-loaded, free-text append. The *source* is interchangeable; the
> produce→govern→deliver *discipline* is invariant.

620 established this discipline for the *hand-authored* source. The feedback loop
shows it must bind an *automated* source identically — which is precisely the
guardrail against the literature's documented failure mode (Goodhart + context bloat
from auto-appended memory). **Candidate scope:** any future automated knowledge
source — imported external best-practices, library-docs-MCP-fetched docs (616), mined
PR-review patterns — must route through the same pipeline. **Current status:** no
code violates it (the loop is unbuilt; 620 guards the hand-authored side; the
`subagent-guide` auto-brief is a governed projection, conforming). Per the
recognize-vs-build split: the principle and its scope are recorded here; the
generalized "automated-source adapter" is **not** built — warranted only when a
*second* automated source actually appears.

> §15.4's "single highest-leverage thread" is the same loop; §16 is its settled
> long-term design (source + acceptance on 620's pipeline, not a parallel system).

## 17. Long-term design — measuring the governance machinery (the "measure the machine" family)

### 17.0 The design problem (and what already exists)

§16 designed the *knowledge* loop (telemetry → lessons → agents). §15's **other**
family — N3 ("does the discipline machinery earn its cost?"), E5 (delegation/model
ROI), N2 (report card) — is un-designed, and it has a different consumer: the
**human's governance decisions**, not the agent's behaviour. It connects to a live
tension: tempdoc **582** ("is the discipline machine worth it") explicitly calls for
a per-gate ROI forensic (violations caught vs. maintenance cost vs. friction; "does
each gate prevent an *agent-specific* failure mode"), and tempdoc **620** is
shrinking the always-loaded budget *by argument* right now. Both want data the
telemetry can provide.

What already exists (the seams to conform to):
- **`scripts/ci/report-doc-relevance.mjs`** — already a Layer-B-shaped efficacy
  join: telemetry Read-calls × the skills/`consult-register` delivery channels →
  "which canonical docs are read 0× and wired nowhere" (the *retire* signal). Built
  on `telemetry-io.loadEvents()` — i.e. *on this substrate already.* The prototype
  of the whole family.
- **`tmp/governance-history.ndjson`** (+ `scripts/governance/lib/history.mjs`) —
  per-gate verdict + finding-counts, appended by enforcers, gate-id keyed; already
  consumed by `dashboard.mjs` / `/api/governance/state`. The strongest
  activation-signal seam.
- **Machine-readable join targets** — `governance/registry.v1.json` (gates),
  `.claude/rules/tier-register.md` (linchpin: rule → enforcement-tier → catch-
  mechanism), `consult-register.v1.json`, `always-loaded-budget.v1.json`, skills.
- **Activation signals already captured** — doc reads (`pre_tool_use`),
  `instructions_loaded` (which rules loaded), `hook_failure`, gate verdicts.

### 17.1 The design — governance-efficacy is Layer B pointed at the machinery, not the work

Layer B joins telemetry × canonical *work* ground truth → "did the session succeed."
The governance-efficacy view is the **same join shape pointed at the governance
registries** → "is this artifact earning its keep":

| | Layer B (the work) | Governance-efficacy (the machine) |
|---|---|---|
| Join target | git / build / tempdoc / gate SARIF | registry.v1.json / tier-register / consult-register / skills |
| Activation signal | merge, build, gate result | doc *read*, rule *loaded*, gate *caught a finding*, hook *fired/blocked* |
| Outcome | session merged/built/passed | artifact used / fired / caught-vs-cost |
| Consumer | trend validator + human review | **582's ROI forensic + 620's budget/retirement decisions** |

So the design is **not a new system**: it generalizes `report-doc-relevance`'s shape
from *docs* to *all governance artifacts*, joining the existing activation signals
against the existing registries. The efficacy projection carries provenance (which
sessions / findings), `kind:fact` where the signal is hard (gate caught N findings),
`kind:inference` where soft (a doc *seems* unused). It **feeds** the decisions
582/620 already make — it does not make them.

### 17.2 The concrete Phase-5 tension this surfaces

A real constraint the investigation found: **skeleton mode (Phase 5, §14) disables
`instructions_loaded`** — the "which rules actually loaded" signal the rule-efficacy
measurement needs. So the Phase-5 cutover as drafted would *blind* the
governance-efficacy view to rule activation. Design consequence: if the "measure the
machine" family is wanted, `instructions_loaded` (or its native-OTel equivalent)
belongs in the **irreducible skeleton**, not the shed-able rich layer. This refines
§14's split: a signal is "rich and shed-able" only if *nothing downstream needs it*;
rule-loading is needed by efficacy, so it is skeleton, not rich.

### 17.3 What NOT to build now

The present problem (no data; 582 is a charter not yet running; a design request)
does **not** warrant the full per-gate ROI scorecard, rich per-rule SARIF
attribution, or a governance-efficacy dashboard surface. Those are built when 582's
forensic runs on real captured sessions. Now: record the shape, fix the Phase-5
signal-retention constraint in the design (§17.2), and recognize `report-doc-relevance`
as the prototype to generalize — nothing more.

### 17.4 Reach — the principle this reveals

This is Layer B (projection-not-fork, join-from-canonical-registry) pointed at a
second target; no new seam. Stepping back, it reveals a sharp recurring gap:

> **The repo enforces *declaration-coverage* heavily but measures *activation-
> efficacy* almost nowhere.** Its positive-coverage registers (execution-surfaces,
> observed-happening, operation-surfaces, the gate registry, consult-register,
> skills…) each prove an artifact is *declared and wired* — but assume *declared =
> effective*. Whether the artifact is ever *read / fires / catches anything* is
> unmeasured. Telemetry × registry is the join that closes that gap.

582's whole thesis is that this assumption is false for gates (declared gates that
never catch an agent-specific failure). The principle generalizes: **every
positive-coverage register is a candidate for an activation-efficacy join.** Current
status: only `report-doc-relevance` does it (docs); 582 wants it for gates; every
other register currently assumes declared = effective — the latent over-build the
principle surfaces. Per recognize-vs-build: name the principle, build the doc + gate
efficacy joins **only when 582's forensic runs on data**, and do **not** build a
generalized "efficacy-join framework" over all registers until a second register
needs it beyond docs+gates.

## 18. User-facing / frontend consequences

This tempdoc is developer-agent telemetry — dev tooling consumed by the developer
and the agents, not the JustSearch product. **For the product end-user (the
search/chat journey), 622 has essentially no user-facing work:** the primary rails
(search, chat, library, health) are untouched, and the capture/analytics/feedback
machinery (Layers A–C, §16) is CLI/hook/agent-facing. There is exactly **one product-
UI touchpoint, and it is indirect** — §17 (governance efficacy). Verified live (dev
stack + Chrome, 2026-06-21), not from the tempdoc alone:

- **`shell-v0/views/GovernanceView.ts`** (`core.governance-surface`) is a real
  product surface, but an **off-rail deeplink dev/operator tool** (CorePlugin: "DEEPLINK
  dev/operator tool, off-rail"), not part of the end-user journey. It renders a pure
  read-only projection of `GET /api/governance/state`: a 36-gate roster (id · title ·
  tier · changeset-gated), exception ceiling (56), 5 mutation-floored seams,
  class-size debt (5590 LOC).
- Its own header states the gap precisely: *"The discipline-gate kernel, made legible …
  **Not live verdicts** — the kernel runs at build time."* It shows
  **declaration-coverage** (what machinery exists) with **zero activation-efficacy**
  (whether any gate ever fires/catches). This is §17's principle made concrete on a
  real surface — and it identifies the correct frontend home.

### 18.1 The frontend design for §17 (when built)

§17's governance-efficacy data surfaces by **extending GovernanceView, not adding a
surface** — it completes the surface's own stated purpose ("make the ladder legible";
the "live verdicts" half it explicitly disclaims). The design:
- A per-gate **efficacy dimension** on the existing gate table (e.g. last-fired /
  findings-caught / a "dead?" flag) + a **docs-efficacy** section (read-0×-and-unwired,
  from `report-doc-relevance`).
- Flowing through the **existing `/api/governance/state` projection** — extend that one
  projector (GovernanceView's docstring: "one projector, not a fork"), never a parallel
  endpoint or a FE-side fork.
- Staying **read-only, off-rail, dev/operator-facing** — it does not touch the product's
  primary rails or the end-user UX; it is confined to the operator surface, like the
  declaration data it joins. (Conforms to the surface-altitude `DIAGNOSTIC`/off-rail
  classification this surface already carries.)

**Scope discipline:** do not build the GovernanceView extension now — there is no
captured efficacy data and 582's forensic has not run. The design is recorded; it is
built when §17's join produces real data.

### 18.2 The dev telemetry viewer is a separate surface (not the product)

The OTLP viewer (`scripts/agent-analytics/otlp-viewer/`, browser-validated in §12) and
the §15 UX ideas (P4 smart-chapters / session-list / filter; N2 session report card)
are **standalone developer tooling** served outside the product — NOT part of shell-v0
and NOT bound by its frontend discipline (the layout / a11y / token gates). Keeping
them separate is correct: the product's frontend governance applies to the end-user
surfaces, not to a local dev dashboard.

### 18.3 The one speculative product touchpoint (N1)

The dogfood idea (N1 — index telemetry into JustSearch's own search) would let the
developer search their agent history through the **product's existing search UI** — no
new surface; the product search rail hosts it. Genuinely product-UI, but
developer-as-user and speculative; not designed here.

## 19. Confidence pass (2026-06-21) — pre-implementation verification of the unbuilt designs

Read-only investigation + telemetry checks before any §16/§17/§18 build. Five ranked
uncertainties; four turned from assumption to fact (two refuting a design claim).

### 19.1 Verdicts
- **U1 — does telemetry flow from real *interactive* sessions? OPEN / unconfirmed.**
  The sink holds only 3 sessions, all `-p` probes (prompts confirm); the current
  interactive session is absent, and `current-session-id` is stale (the raciness
  already logged). My session predates the user-settings env, and a fresh interactive
  session needs a TTY I don't have. **This is the foundational risk for the whole
  §16/§17 family — the designs have never run on real interactive data.** One-line
  user smoke: sink up → one fresh `claude` interactive turn → confirm
  `tmp/agent-telemetry/otlp/logs.ndjson` gains a non-`-p` session.
- **U2 — §18 data-home: RESOLVED (viable).** `GovernanceStateController` already serves
  *local-runtime* gate verdicts (from `tmp/governance-report.sarif`) alongside the
  committed `governance-state.json` projection. Efficacy data (also local `tmp/`, from
  `governance-history.ndjson`) fits the exact "supplementary live verdicts" pattern the
  endpoint already uses → §18 extends that controller to also aggregate the history.
  Not a category error.
- **U3 — §17 gate-efficacy granularity: CONFIRMED coarse.** `governance-history.ndjson`
  records `{ts, gate, verdict, findings:{error,warning,note}}` — firing + finding
  *counts*, no rule attribution, no catch-vs-friction. So **coarse efficacy (did this
  gate ever fire / produce findings) is feasible now**; 582's "real catch vs friction"
  needs a producer change (rule-slug + classification per SARIF finding). Ship coarse
  first; the deep half is a separate, larger change.
- **U4 — §16 delivery seam: CONFIRMED limited.** `consult-register.v1.json` rows are
  strictly `pathIncludes`-keyed (region). It cannot carry *tool/action*-keyed lessons
  ("offset/limit on big Reads" applies everywhere). **Correction:** region-scoped
  lessons → consult-register (as designed); **tool/action-scoped lessons → a
  PreToolUse-hook behavior** (the existing `intervene`-style mechanism that already
  auto-limits big Reads), NOT consult-register. Still existing seams — two channels,
  not one.
- **U5 — rule-loading efficacy signal: REFUTED.** `instructions_loaded` fires but
  carries `files: None`, and "which rules loaded" is a non-signal anyway (always-loaded
  rules load every session by definition). So §17.2's "keep `instructions_loaded` in
  skeleton mode for rule-efficacy" is **misconceived**. Rule-efficacy must come from a
  *behavioral* signal — did the rule's enforcing hook fire / was its constraint
  followed — not load events; for prose-only rules a clean signal may not exist.

### 19.2 Design corrections folded back
- **§16 delivery** is two channels: region → consult-register; tool/action → PreToolUse
  hook (U4). **§17.2** is corrected: `instructions_loaded` is not the rule-efficacy
  signal; gate-efficacy is coarse-feasible now, catch-vs-friction + rule-efficacy need
  producer changes / a different signal (U3, U5). **§18** data-home confirmed viable via
  the existing controller pattern (U2).

### 19.3 Critical confidence rating (0–10)
| Design | Rating | Basis |
|---|---|---|
| §17 gate-efficacy (coarse) | **6** | data + endpoint + registries exist; `governance-history` is live; "did it fire" is straightforward |
| §18 GovernanceView surfacing | **6** | controller already serves local-runtime gate data; extension pattern proven; off-rail, low blast radius |
| §17 catch-vs-friction / rule-efficacy | **3** | needs producer-side SARIF enrichment + a behavioral rule-firing signal that doesn't cleanly exist (U5 refuted) |
| §16 knowledge loop | **3** | two-channel delivery now understood, but lesson-acceptance-via-changeset still unverified, and zero real data to mine |
| **Foundational (U1)** | **gates all above** | no real interactive telemetry has ever been captured; every design is unvalidated end-to-end until one fresh interactive session is run against the sink |
| **Overall remaining work** | **4/10** | the measure half (§17-coarse + §18) is moderate-confidence on existing infra; the close-the-loop + deep-efficacy half is low-confidence and gated on data that isn't flowing |

### 19.4 U1 RESOLVED — favorably (live interactive session, 2026-06-21)

A real interactive session (`ed070f23`, prompt `/start`, model `claude-haiku-4-5`)
ran against the live sink and **fully validated the foundational premise**:
- **Interactive sessions emit all three OTel signals** — traces (93), metrics (31),
  logs (259). Not metrics+logs only.
- **The full trace tree emits interactively** — `claude_code.interaction` (2) → `tool`
  (26) → `tool.execution` (25) + `llm_request` (14). **#53954's interactive-span-
  suppression does NOT occur on this version** — so the subagent-nesting / dispatch-
  granular win (§5.1, §6.4) IS available interactively, not only under `-p`.
- **The analytics pipeline runs end-to-end on real interactive data:** `analyze
  --source otlp` → a correct report (26 tools: Read 21 / Glob 2 / Bash 2 / AskUser 1;
  21 reads; model populated); `cost --source otlp` → $1.65 with full token breakdown.
- **Bonus signals discovered in the interactive log stream:** `skill_activated` (a
  real skill-usage signal — partially answers the §17 rule/skill-efficacy gap that U5
  refuted: *skills* at least have an activation signal, even if prose-rules don't),
  and `api_request_body`/`api_response_body` (full content, from the settings env).

**Revised ratings (U1 no longer gates):**
| Design | was | now | why |
|---|---|---|---|
| §17 gate-efficacy (coarse) + §18 | 6 | **8** | data premise proven; pipeline runs on real interactive sessions; endpoint home confirmed |
| §16 knowledge loop | 3 | **5** | data now flows (un-gated); still limited by two-channel delivery (U4) + unverified lesson-acceptance |
| §17 catch-vs-friction / rule-efficacy | 3 | **4** | still needs producer SARIF enrichment; but `skill_activated` gives skill-efficacy a real signal |
| **Overall remaining work** | 4 | **7/10** | the foundational risk is gone — real interactive telemetry flows through the full pipeline; remaining gaps are scoped design refinements, not unknowns |

**Net:** the single thing that gated the whole family — "does real interactive
telemetry flow?" — is answered **yes**, with the trace tree intact. Overall confidence
moves from 4 to **7/10**: the measurement half is now high-confidence, and the loop
half is data-unblocked with only scoped refinements remaining.

## 20. As-built — §17-coarse + §18 SHIPPED (2026-06-21)

The ready, high-confidence, user-visible half is implemented + browser-validated. The
§17.3/§18.1 "wait for 582's forensic / no data" guard is **lifted for the coarse half**
(U1 resolved, `governance-history.ndjson` is live with 1046 records); the deep half
(catch-vs-friction) and §16 (the loop) remain deferred as recorded.

**Built (extends existing seams, no new surface/endpoint):**
- `GovernanceStateController.java` — a third local-runtime layer `efficacy`, read from
  `tmp/governance-history.ndjson` at request time (mirrors the SARIF read; never
  committed), aggregated per gate `{totalRuns, runsWithFindings, error/warning/note,
  lastVerdict, lastTs}` and joined against the committed roster for `status`
  (`active` / `never-fired` / `orphaned`). Capped to the last 5000 lines. +test (deterministic
  `@TempDir` history fixture).
- `GovernanceView.ts` — a 5th **"Activation"** column on the gate table: a tone dot
  (the `jf-status-dot` `statusTone` authority — ok = ever-found-something, neutral =
  silent/never-local) + a plain-text label ("258 runs · 139 err" / "silent" / "never
  (local)" / "orphaned"), plus a prominent **local-only disclaimer** ("never (local) ≠
  dead"). +test (new `GovernanceView.test.ts`: column, labels, disclaimer, graceful absence).

**Validated:** `:modules:ui:test` green (both controller tests); FE typecheck + the new
test (3/3) + full suite (3375/3375 on re-run — one isolation flake in an unrelated
`resourceRegistry.test.ts`, not mine); GovernanceView clean on all UI gates
(layout-purity / a11y-closure / theme-token / accent-as-text / controls-a11y); and
**browser-validated** against the live stack — the Activation column renders real
per-gate data (class-size 258·139, register-guard-resolution "silent", …) with tone
dots + the disclaimer. This closes the §17.4 gap (declaration-coverage vs activation-
efficacy) on a real operator surface: the governance screen now shows not just *what
machinery exists* but *whether each gate is earning its keep locally*.

**Pre-existing (other-agent shared-main, logged to observations, not mine):**
`check-theme-token-closure` (`RecentsMenu.ts`), `check-accent-as-text`
(`ActionLedgerView.ts`), and the `resourceRegistry.test.ts` isolation flake.

**Deferred (unchanged):** §17-deep catch-vs-friction (needs rule-attributed SARIF across
36 enforcers); §16 knowledge loop (needs session volume); docs-efficacy fold-in
(`report-doc-relevance` runs standalone); history rotation policy (read is capped).
