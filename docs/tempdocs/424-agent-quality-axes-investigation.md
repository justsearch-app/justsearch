---
title: "Agent Quality Axes — 10-Axis Investigation"
type: tempdocs
status: open
created: 2026-04-28
related: [423]
---

# 424 — Agent Quality Axes — 10-Axis Investigation

## Status

**OPEN.** Created 2026-04-28 as a follow-on to tempdoc 423. Where 423
mapped the *static structure* of the agent environment (hooks, skills,
settings, MCP), this tempdoc investigates 10 *dynamic* axes that affect
agent quality but were never measured.

Each axis got either a runtime probe, primary-source research, or both.
Findings are presented per-axis with confidence ratings and concrete
recommendations.

## Goal

Move the agent's environment from "configured well" (423) to "tuned for
quality" (424). Specifically: understand actual context economics,
agentic-loop dynamics, effort/thinking budget, telemetry consumer
health, file-history mechanics, multi-agent coordination, workflow
patterns, MCP fit, design tensions, and failure modes.

---

## Axis 1: Context economics — measured

**Probe:** Read `~/.claude/projects/F--JustSearch/<sid>.jsonl` for this session and aggregated `usage` fields across 506 messages.

### Numbers from this session

| Metric | Value |
|---|---|
| Total messages with usage | 506 |
| Total non-cached input tokens | 107,326 |
| Total cache **read** tokens | 137,454,621 (137M) |
| Total cache **creation** tokens | 2,178,920 (2.2M) |
| Total output tokens | 724,162 |
| Cost (per `cost-session.mjs`) | **$77.58** |
| Duration | 7.27 hours |
| Tool calls | 346 |

Cache read is 1278× larger than non-cached input — **99.92% cache hit rate**.

### Stable prefix size

The first cached layer is **~19,420 tokens** and stable across the early session. This is what every fresh-cache session loads:

| Component | Estimate |
|---|---|
| Anthropic system prompt + tool definitions | ~9-10K |
| CLAUDE.md (~291 lines × ~17 chars/token) | ~5K |
| `.claude/rules/*.md` (6 files, ~20KB total) | ~5-6K |
| **Total** | **~19-20K** ✓ matches observed |

### Cache growth and compaction

- Cache read grows monotonically as conversation accumulates.
- By message 470: cache read = **511,977 tokens**.
- By message 488: cache read = **518,957 tokens**.
- **Message 489: cache_read drops to 19,420** — a compaction fired.
- **Message 489 has cache_creation = 509,849** — post-compaction cache rebuild.
- Second compaction visible around message 497.

**Implication:** auto-compaction fires near 50% of the 1M context window (around 520K tokens). Each compaction event recreates ~510K of cache at cache-write pricing. With 1h-ephemeral cache pricing this is a meaningful cost component. The user has not set `autoCompactWindow`; the default applies.

### Score-session.mjs signals (real numbers)

| Signal | Value | Interpretation |
|---|---|---|
| `unbounded_read_pct` | 0.082 | 8.2% of reads were unbounded — low (intervene cap helping) |
| `bash_fileop_pct` | 0.149 | 14.9% of Bash calls did file ops (likely jq/grep/awk via Bash; bash-guard now redirects bare cat/head/tail/grep) |
| `rapid_reedit_count` | 1 | One rapid re-edit pattern caught |
| `hot_file_concentration` | 0.224 | Moderate; tempdoc 423 read 5× of 36 unique files |
| `tool_failure_rate` | 0.02 | 2% — healthy |
| `subagent_density` | 0.275 | Single Agent call out of 346 tool calls; the metric formula evidently weights more heavily |
| `subagent_failure_rate` | 0.5 | **50%** — but with only 1 Agent call, the heuristic is over-sensitive |
| `reedit_per_edit` | 0.077 | 7.7% of edits got re-edited shortly — reasonable |
| `build_cycle_rate` / `failed_build_pct` | 0 / 0 | No builds this session |
| **Score** | **78** | (out of presumably 100) |

### Recommendations from Axis 1

- **Add `autoCompactWindow: 800000`** to `~/.claude/settings.json` — pushes auto-compaction from ~520K to 800K, halves the per-session compaction count for long sessions. Saves ~$5-10 per multi-hour session at current usage. Confidence 70%; needs validation that the schema accepts non-default values.
- **Don't shrink CLAUDE.md urgently.** The 5K token cost compares favorably to a ~$77 session — the 6.5% overhead per session is a reasonable price for guaranteed-loaded context. Path-scoped rules in `.claude/rules/` with `paths:` frontmatter would shave 3-4K only when working in non-matching code.

---

## Axis 2: Agentic loop mechanics

**Sources:** [Anthropic agent-loop docs](https://code.claude.com/docs/en/agent-sdk/agent-loop), [Stop hook patterns](https://claudefa.st/blog/tools/hooks/stop-hook-task-enforcement), [Ralph Wiggum technique](https://claudefa.st/blog/guide/mechanics/ralph-wiggum-technique).

### How Stop fires

The model emits an internal "I'm done" signal that triggers Stop. This is heuristic, not deterministic — the same task can stop early in one run and continue in another. Common causes of premature Stop:
- Tool-call exhaustion (model decides nothing more to do)
- Confidence threshold reached
- Pattern-matching on completion phrases
- Context bloat triggering early termination

### Highest-ROI single addition (per Boris Cherny, Claude Code creator)

> *"Give Claude a way to verify its work. If Claude has that feedback loop, it will 2-3x the quality of the final result."*

The canonical pattern: **a Stop hook that runs project tests and prevents stopping if they fail.**

### Ralph Wiggum technique

Block Stop until the agent emits a "completion promise" — a specific phrase the user defines. Forces genuine completion. Combined with `stop_hook_active` checking to prevent infinite loops.

### Project gap

The project's `dispatch.mjs` Stop handler is **logging-only**. No verification, no completion-promise check. The agent decides when to stop and the platform respects it.

### Recommendations from Axis 2

- **Tier-1: Add a verification Stop hook.** When the agent emits a phrase like "task complete" or any tempdoc-related stop, run `./gradlew.bat build -x test` (compile only, ~30s). If build broken, `decision: "block"` with stderr listing the broken module. Catches the most common failure mode (agent declares done with broken build) at zero token cost. Confidence 85% — pattern is documented and used in the wild.
- Pair with `stop_hook_active` check: if true, skip verification (already retrying). Standard pattern.
- Consider adding a project-specific completion phrase ("tempdoc shipped" or similar) for stronger signal.

---

## Axis 3: Effort levels + extended thinking

**Sources:** [Anthropic effort docs](https://platform.claude.com/docs/en/build-with-claude/effort), [Adaptive thinking](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking), [Opus 4.7 xhigh guide](https://www.verdent.ai/guides/claude-opus-4-7-xhigh-ultrareview-task-budgets).

### Effort levels (Opus 4.7 + Sonnet 4.7)

| Level | Description |
|---|---|
| `low` | Minimal thinking budget; pattern-recognition responses |
| `medium` | Moderate; default in many configs; everyday coding |
| `high` | Substantial budget; multi-approach reasoning, backtracking; noticeably slower + costlier |
| `xhigh` | For agentic work needing extended exploration. Anthropic benchmarks 75% on complex coding |
| `max` | Unconstrained budget; current session only (unless via env var) |

### Default for the user

**As of April 16, 2026, Pro/Max/Teams/Enterprise default to `xhigh`.** The user is on Pro/Max → already running xhigh. This explains the heavy cache use ($77/session) and quality.

### Adaptive thinking

For Opus 4.7+, use `thinking: {type: "adaptive"}` with the effort parameter. Replaces deprecated `budget_tokens` from earlier models.

### What's not configured

- `effortLevel` is not set in the user's `~/.claude/settings.json` — defaults apply.
- `alwaysThinkingEnabled` not set — defaults to enabled for supported models.
- No subagent-specific model overrides; subagents inherit Opus 4.7 by default.

### Recommendations from Axis 3

- **None urgent.** xhigh is the right default; auto-applied. The user is already running the most capable configuration.
- **Optional:** Consider routing research-heavy subagents to Sonnet 4.6 via `Agent { model: "sonnet" }` to save cost when subagent tasks are read-only exploration (the Explore agent specifically). Could halve subagent token cost. Confidence 70%; depends on whether the user is cost-sensitive.
- **Optional:** Use `/effort low` explicitly for trivial sessions (file lookups, single-file edits) where xhigh budget is wasted. Manual control.

---

## Axis 4: Analytics scripts — they work

**Probe:** Ran each script with `--help` and on a real session.

### Script status

| Script | Working | Notes |
|---|---|---|
| `analyze-session.mjs` | ✓ | Found 110 sessions, 34,651 events. Wrote `tmp/agent-telemetry/sessions/<sid>.json`. |
| `cost-session.mjs` | ✓ | Reported $77.58 for current session. Writes `costs.ndjson`. |
| `score-session.mjs` | ✓ | Returns score 78 with 10 signals. **Requires `analyze-session` first** — has implicit dependency on the per-session JSON. |
| `evaluate-session.mjs` | ✗ (was) | LLM-as-judge. The `auto-evaluate.mjs` hook that fed this was broken from day one (deleted in tempdoc 423 §14.19). The script itself works manually. |
| `analyze-trends.mjs` | not tested | |
| `correlate-signals.mjs` | not tested | |
| `generate-dashboard.mjs` | not tested | |
| `cost-session.mjs --all` | tested | Walks all 110 sessions |
| `context-attribution.mjs` | not tested | |
| `test-pipeline.mjs` | not tested | |

### Pipeline observation

The chain is: `events.ndjson` → `analyze-session` → `<sid>.json` → `score-session` → `scores.ndjson`. `cost-session` reads events directly and writes `costs.ndjson`. `evaluate-session` reads transcripts directly.

The chain has **no automated invocation**. Scripts run manually. With auto-evaluate deleted, even the LLM-judge step that did fire automatically is gone. **The analytics scripts are sound but the pipeline is inert.**

### Recommendations from Axis 4

- **Tier-2: Wire `analyze-session` + `score-session` into the SessionEnd hook chain.** They're cheap (run on disk data, no LLM call). Result: per-session score + signals automatically appear in `scores.ndjson` after every session ends. Could replace what auto-evaluate was supposed to do, without LLM cost. Confidence 80%.
- **Tier-3: A monthly cron via `/schedule` to run `generate-dashboard.mjs`** — surfaces trends without manual prompt. Low priority.
- For now: when you want session analytics, manually run `node scripts/agent-analytics/analyze-session.mjs --session-id <sid>` then `cost-session.mjs` and `score-session.mjs` on the output. Three commands.

---

## Axis 5: file-history format and rewind

**Probe:** Read blobs from `~/.claude/file-history/<sid>/<hash>@v<N>` directly.

### Format

- **Plain text snapshots, no compression, no diffing.**
- Each `<hash>@v<N>` is a full file content blob at version N.
- The hash (e.g., `42391eb87f238e0c`) is content/path-derived, stable per file within a session.
- File extension preserved implicitly (it's just the file's bytes).

### Observed in this session

23 file-history blobs total. The tempdoc 423 file alone has 9 versions:

| Version | Size | Time |
|---|---|---|
| v2 | 26,763 bytes | 2026-04-27 23:22 |
| v3 | 40,039 bytes | 2026-04-28 03:27 |
| v4 | 58,627 bytes | 2026-04-28 03:58 |
| v5 | 68,183 bytes | 2026-04-28 04:16 |
| ... | ... | ... |
| v10 | 106,232 bytes | 2026-04-28 05:23 |

Each version is a snapshot taken when the file is about to be modified. Sizes grow monotonically as the tempdoc grew during this session.

### `/rewind` semantics

Per [Anthropic interactive-mode docs](https://code.claude.com/docs/en/interactive-mode): `Esc+Esc` restores file content to a previous point. Reads from the per-session file-history dir. Worktree-isolated (subagent worktree has its own file-history if isolated).

### Implications

- **Disk cost:** This session's tempdoc has ~600KB of historical snapshots; full project session-history grew measurably. With 110 sessions × ~10 files × ~50KB average = ~55MB across all sessions. Not huge but accumulates.
- **Power:** You can rewind any file edit during this session. Even after agent shipped tempdoc 423 §14.19 with all the changes, you could `Esc+Esc` and roll back any single file.
- **Worktree note:** Worktree-isolated subagents have their own file-history dir. If a subagent makes changes you don't want, rewinding in the parent session won't help — you'd have to rewind in the subagent's session if it still exists.

### Recommendations from Axis 5

- **None.** Mechanism works as documented; useful to know `Esc+Esc` is the universal undo and that snapshots are plain text (auditable).
- **Optional cleanup:** If `~/.claude/file-history/` ever bloats, it's safe to delete old session subdirs (older than active sessions). But it's not pressing.

---

## Axis 6: Multi-agent coordination patterns

**Investigation:** Conceptual analysis based on §8 and observed project mechanisms.

### Existing project mechanisms

| Mechanism | Purpose | Strength |
|---|---|---|
| Worktrees in `.claude/worktrees/<name>/` | Branch isolation per agent session | Strong; git enforces |
| `tmp/dev-runner/active.json` lease | One dev stack at a time | Strong; dev-runner enforces |
| `docs/observations.md` inbox | Cross-agent issue collection | Weak; append-only, no triage |
| Shared `tmp/agent-telemetry/events.ndjson` | Cross-session telemetry | Strong; NDJSON append is atomic |
| Tempdoc files (one tempdoc, one author convention) | Soft coordination on what each agent works on | Weak; no enforcement |

### What's missing

For 3-4 parallel agents (current scale), the existing mechanisms are sufficient. For 10+ this would break down. Specific gaps:

- **No collision detection** when two agents pick up the same tempdoc.
- **No "in-progress" registry** — the only way to know what another agent is doing is to look at git log on their branch.
- **No structured cross-agent messaging** — comments in shared files (observations.md) are the only channel.

### Recommendations from Axis 6

- **None at current scale.** The project doesn't have 10+ parallel agents.
- If scale grows: a custom MCP server `justsearch-coord` exposing `claim_tempdoc(N)`, `release_tempdoc(N)`, `list_active_work()` would close the collision-detection gap. ~half-day MCP development.

---

## Axis 7: Workflow pattern inference

**Probe:** `skillUsage` from `~/.claude.json` + this session's `analyze-session.mjs` output.

### Skill usage (lifetime)

| Skill | Uses | Purpose |
|---|---|---|
| `start` | 27 | Session orientation |
| `jseval` | 8 | Eval/profiling |
| `dev-stack` | 4 | Backend lifecycle |
| `inference-runtime` | 4 | GPU/ORT debugging |
| `ui-check` | 3 | Visual UI verification |
| `update-config` | 2 | Settings changes (Anthropic-shipped) |
| `loop` | 2 | Recurring tasks |
| `docs-maintenance` | 2 | Doc regen |
| `api-record` | 2 | Java record edits |
| `frontend-design:frontend-design` | 2 | UI components |
| `search-quality` | 2 | Search baselines |
| `simplify` | 1 | Code review (Anthropic-shipped) |
| `review` | 1 | PR review (Anthropic-shipped) |
| `ci-triage` | 1 | CI debugging |
| `lockfile` | 1 | Dep changes |
| `ssot-catalog` | 0 | Never used |
| `installer` | 0 | Never used |
| `module-arch` | 0 | Never used |
| `doc-audit` | 0 | Never used |

### Inferred workflow patterns

The user mostly does:
1. **Orientation-heavy work** (`start` 27×) — tempdoc-based research and design
2. **Eval and profiling** (`jseval` 8×) — measurement-driven development
3. **Runtime debugging** (`dev-stack` + `inference-runtime` 8× combined) — operating live backends
4. **Doc-heavy work** (`docs-maintenance` + tempdoc churn)

The user **rarely** does:
- New module creation (`module-arch` 0×)
- Installer work (`installer` 0×)
- Periodic doc audits (`doc-audit` 0×)
- SSOT catalog edits (`ssot-catalog` 0×)

### This session pattern (per analyze-session.mjs)

346 tool calls in 7.3 hours. Distribution:
- Bash: 87 (25%)
- TaskUpdate: 75 (22%)
- TaskCreate: 49 (14%)
- Read: 46 (13%)
- Edit: 30 (9%)
- WebSearch: 29 (8%)
- WebFetch: 15 (4%)
- Write: 6
- Grep: 4
- Glob: 2
- Agent: 1

This is a **research-and-tempdoc-write** pattern — heavy task tracking, web research, doc editing. Light on code editing (Edit 30 / Write 6 = 36 actual file changes out of 346 tool calls = 10%).

### Recommendations from Axis 7

- **The 4 unused skills (ssot-catalog, installer, module-arch, doc-audit) are noise in the skill listing**, consuming ~6K tokens of the dynamic budget. If the maintainer is confident they're unused, remove them or set `skillOverrides: {ssot-catalog: "name-only", ...}` to suppress descriptions. Confidence 60% — skills *might* be used during agent dispatch even if maintainer hasn't manually invoked them.
- The `simplify`/`review`/`security-review` Anthropic-shipped skills could become more useful — `simplify` after a long edit session, `security-review` before merging. **Awareness, not action.**

---

## Axis 8: Library-docs MCP servers

**Sources:** the vendor's documentation, a third-party review and a vulnerability disclosure (links removed).

### What a library-docs MCP server is

An open-source, free MCP server that fetches **version-specific library documentation** and injects it into prompts. Supports JavaScript, TypeScript, Python, Go, Rust, Java, C#, PHP, Ruby + hundreds of frameworks.

### Why it fits JustSearch

JustSearch uses Java + Kotlin (build) + TypeScript (UI) + Python (jseval) + Rust (Tauri). Five language ecosystems. Without such a server, agents rely on:
- Their training cutoff (Jan 2026) for library docs
- WebFetch on docs sites (slow, unstructured)
- WebSearch (generic, low-precision)

With such a server, asking "use Lucene 9.x's MultiFieldQueryParser" gets the actual current API as part of the prompt.

### Why **not** to adopt

1. **Prompt-injection vulnerability (Feb 2026):** the server's "Custom Rules" let library publishers inject "AI Instructions" the agent treated as trusted instructions — including tool-using ones. Patched, but exemplifies the supply-chain risk.
2. **Free tier cut 83-92% in January 2026.** Heavy use needs a paid tier.
3. **Always-on overhead** — even with Skills-based intelligent activation (98% trigger rate), it adds context cost when libraries are mentioned.
4. **JustSearch already has good in-repo docs.** `docs/explanation/`, `docs/reference/`, the agent-guide, the skills system. Often the agent doesn't need external library docs because the project's own docs cover the relevant patterns.

### Recommendations from Axis 8

- **Skip for now.** The supply-chain risk + paid tier + already-good in-repo docs make it a marginal win. Worth revisiting if the agent ever produces wrong answers about Lucene/ORT/Tauri APIs that current docs don't catch.
- **If adopted:** install via `ctx7 setup` and verify the security patch is current. Use the Skill-based activation, not the SessionStart hook variant.

---

## Axis 9: Inversion analysis

**Investigation:** Where do current setup choices subvert stated project goals? CLAUDE.md and the rules contain stated principles; the runtime sometimes pushes against them.

### Tension 1: "Fix root causes, not symptoms" vs `build-counter` one-shot

- **Stated rule:** Don't comment out failing code, weaken assertions, etc.
- **Runtime:** `build-counter.mjs` blocks the 3rd consecutive Gradle failure with an advisory, then **allows the next attempt** (one-shot pattern). The rationale (avoiding deadlock) is sound.
- **Inversion risk:** the path of least resistance after the advisory is "revert and try a slightly different surface fix" rather than "actually diagnose the underlying issue." The advisory is text the agent reads once and proceeds past.
- **Mitigation:** could be tightened by requiring an explicit `decision-rationale` Bash echo before allowing the next build. Adds friction. Probably overkill.

### Tension 2: "Verify, don't guess" vs Read auto-limit

- **Stated rule:** Use `/api/debug/state` and `/api/health`, not log grepping. Verify, don't infer.
- **Runtime:** `intervene.mjs` silently truncates Reads >8KB to 200 lines. With the new visibility annotation (§14.25), the agent now SEES the truncation. But the path of least resistance is still "make a guess about what's beyond line 200."
- **Inversion risk:** Truncated context encourages confident-but-wrong assertions. Demonstrated multiple times in this very investigation (the third-party docs that were wrong about CLAUDE.md propagation, the misread about scratch dir contents).
- **Mitigation:** the §14.25 annotation is the right fix. Stronger: the annotation could be louder ("STOP — file is N lines, you have only seen 200, re-read with offset before claiming knowledge of full content").

### Tension 3: "Tempdoc is your contract" vs implicit "infeasible" exits

- **Stated rule:** Implement every item in the tempdoc unless explicitly told to skip.
- **Runtime:** No mechanism enforces this. Agents can declare items "infeasible" or "deferred" silently. The previous tempdoc 423 §14.19 actually invoked this rule positively (the `compactPrompt` recommendation was *correctly* withdrawn after probing). But there's no platform-level enforcement.
- **Inversion risk:** The line between "legitimately infeasible" and "I don't want to" is judgment-dependent. With `bypassPermissions`, no human checks each declaration.
- **Mitigation:** maybe a "TaskCompleted" hook that requires the agent to write a one-line rationale to a file before marking complete. Friction, but auditable. Not pressing.

### Tension 4: "Log pre-existing issues, don't fix" vs inbox rot

- **Stated rule:** Append observation, keep working.
- **Runtime:** No automation triages or actions the inbox. Items rot.
- **Inversion risk:** Discipline trains the agent to log-and-forget. Without a pull, observations.md becomes a graveyard.
- **Mitigation:** a periodic `/loop` running on a weekly cadence to summarize new observation entries and propose tempdoc creation. Or just maintainer discipline. The structure permits rot; only humans prevent it.

### Tension 5: "Stay focused on assigned work" vs investigation creep

- **Stated rule:** Don't propose switching tempdocs unless current is fully complete.
- **Runtime:** Tempdoc 423 grew from "environment survey" to "critical analysis" to "implementation pass" to "extended investigation" to this 10-axis follow-up. Scope creep.
- **Inversion risk:** Open-ended investigation tempdocs naturally expand. The discipline holds for tempdocs that are well-bounded; less for explicitly exploratory ones.
- **Mitigation:** be more aggressive about closing 423 and starting new tempdocs when scope shifts (which I did when starting 424). The structure works; depends on agent vigilance.

### Recommendations from Axis 9

- **Tension 2 (Read auto-limit) is the most pressing.** §14.25 added visibility. Stronger language in the additionalContext is a 5-min change.
- **Other tensions are tolerable.** They represent design trade-offs more than defects. Acknowledge them as such.

---

## Axis 10: Failure modes and state corruption

**Probe:** Inspected `tmp/agent-telemetry/`, `.claude/scheduled_tasks.lock`, file-history dirs.

### events.ndjson rotation

- Active: 7,344 lines
- `.prev`: 10MB (rotated April 24, 2026)
- Single-file rotation; no size cap visible. Manual or threshold-driven? Looking at the source of `event-writer.mjs` would settle this — not yet probed.
- **Risk:** if `.prev` is overwritten by a new rotation, older events are gone. Multi-week trend analysis depends on retention.

### Stale per-session state files

In `tmp/agent-telemetry/`:
- `compact-state-0c17f291-…json` (April 27 21:30) — for a previous session, never cleaned.
- `build-fails-*.json` from at least 3 previous sessions — `intervene.mjs` prunes these via 24h staleness check on the next session start.
- `edit-counts-*.json` from previous sessions — same prune logic.
- `current-session-id` — overwritten on each SessionStart (vulnerable to race conditions; demonstrated when my probe overwrote it earlier).

### Stale cron lock

`.claude/scheduled_tasks.lock`:
```json
{"sessionId":"7c268642-…","pid":16900,"acquiredAt":1777233612408}
```

Acquired April 27. The session 7c268642 may or may not still be running. If not, the lock is stale. If a `/loop` or `/schedule` task tries to fire, it may block on this lock unless the holder is verified alive. **Not currently probed.**

### Compact-restore failure modes

- If `compact-state-<sid>.json` is malformed, `compact-restore.mjs` catches the error silently and exits 0 — no agent context restored.
- If the rules-file write to `.claude/rules/compaction-state.md` fails (disk full, permissions), the fallback `additionalContext` channel still emits. Resilient.
- If a stale `.claude/rules/compaction-state.md` from another session persists into this one, `compact-restore.mjs` deletes it on non-compact session start. Self-healing.

### Concurrent write races

- `tmp/agent-telemetry/events.ndjson`: NDJSON appends are atomic at OS level for small writes. Multiple agents writing concurrently should not corrupt — though line ordering becomes interleaved.
- `current-session-id`: NOT atomic — overwrite. Last writer wins. Demonstrated.
- `docs/observations.md`: Bash heredoc append via `>>` is atomic at OS level (single `write()` call). Should be safe under typical load.

### Recommendations from Axis 10

- **Tier-3: Stale-lock check in `/loop` setup.** Verify the PID in `.claude/scheduled_tasks.lock` is alive before treating the lock as held. Probably already implemented in the `loop` skill — verify before adding.
- **Tier-3: Periodic prune of `compact-state-*.json`.** `compact-restore.mjs` already cleans the current session's file; older sessions accumulate. Add to the staleness-prune list in `intervene.mjs`. ~5 lines.
- **events.ndjson rotation policy:** worth understanding the trigger. If size-based, set the cap thoughtfully. Read `event-writer.mjs` to find out.

---

## Cross-axis synthesis: top concrete recommendations

Filtering across all 10 axes for highest-leverage actionable items:

| # | Item | Confidence | Effort | Source |
|---|---|---|---|---|
| 1 | **Add a Stop-hook verification step** (e.g., compile-only build before allowing stop) | 85% | ~30-60 lines | Axis 2 — highest-ROI per Anthropic |
| 2 | **Wire `analyze-session` + `score-session` into SessionEnd** | 80% | ~10 lines (settings + small wrapper) | Axis 4 — already-built telemetry, currently inert |
| 3 | **Strengthen `intervene.mjs` truncation language** to discourage guessing | 80% | ~3 lines | Axis 9 / §14.25 follow-on |
| 4 | **Set `autoCompactWindow: 800000`** in user settings | 70% | 1 line | Axis 1 — saves $5-10/long-session |
| 5 | **Add `compact-state-*.json` to staleness prune** | 70% | ~5 lines | Axis 10 — housekeeping |
| 6 | **Consider Sonnet 4.6 for read-only Explore subagents** | 70% | per-call decision | Axis 3 — cost optimization |
| 7 | **Suppress unused skills via `skillOverrides`** (ssot-catalog, installer, module-arch, doc-audit if confirmed unused) | 60% | ~4 lines | Axis 7 — context budget |

Items NOT recommended:

- The library-docs MCP server (Axis 8): security risk + already-good docs
- Multi-agent coordination MCP (Axis 6): not at scale
- Tighter inversion-tension fixes (Axis 9 Tensions 1, 3, 4, 5): trade-offs, not defects

## Sources

- [Claude Code agent-loop docs](https://code.claude.com/docs/en/agent-sdk/agent-loop)
- [Stop hook patterns](https://claudefa.st/blog/tools/hooks/stop-hook-task-enforcement)
- [Ralph Wiggum technique](https://claudefa.st/blog/guide/mechanics/ralph-wiggum-technique)
- [Anthropic effort docs](https://platform.claude.com/docs/en/build-with-claude/effort)
- [Adaptive thinking](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking)
- [Opus 4.7 xhigh + task budgets](https://www.verdent.ai/guides/claude-opus-4-7-xhigh-ultrareview-task-budgets)
- Third-party review of a library-docs MCP server (link removed)
- Prompt-injection vulnerability disclosure, Feb 2026 (link removed)
- [Claude Code interactive mode](https://code.claude.com/docs/en/interactive-mode)
- Local: this session's transcript JSONL, `analyze-session.mjs` output, `cost-session.mjs` output, file-history blobs, `~/.claude.json` skillUsage
