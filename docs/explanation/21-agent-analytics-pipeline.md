---
title: Agent Analytics Pipeline
type: explanation
status: in-progress
description: "Behavioral tracking pipeline for hooks, session and cost analysis, skill-delivery evidence, and LLM-as-judge evaluation. The dashboard and process-hygiene scoring layers are retired."
---

# Agent Analytics Pipeline

The agent analytics pipeline tracks behavioral patterns — how agents use tools, which files they re-read, when they make rapid re-edits, and how often they misuse Bash for file operations. It also estimates per-session costs from transcript token data and optionally evaluates task outcomes via LLM-as-judge.

All scripts live under `scripts/agent-analytics/`. All data lives under `tmp/agent-telemetry/` (gitignored). The composite process-hygiene score that once sat on top of the session reports is retired — what it measured, and why it cannot be re-measured, is recorded below under [Retired: process-hygiene scoring](#retired-process-hygiene-scoring).

> **Liveness is per-layer, not per-pipeline.** This doc describes several layers with different
> health. The **hook layer** (`hooks/`, `lib/`) is projected from
> `governance/agent-hooks.v1.json` into Claude and Codex routing. Verify it with the
> `hook-integrity` and `check-codex-agent-parity` gates. The
> **analysis scripts** (`cost-session`, `baseline-economics`, `cache-efficiency`, …) are
> maintainer CLI tools run on demand; nothing invokes them on a
> schedule, so an empty or stale store under `tmp/agent-telemetry/` is expected, not a fault. The
> **HTML dashboard layer** (`generate-dashboard.mjs`, `dashboard.html`), the **process-hygiene
> scoring layer** (`score-session.mjs`, `correlate-signals.mjs`, `scores.ndjson`,
> `scripts/ci/check-agent-quality-trend.mjs`) and the **session-report layer**
> (`analyze-session`, `analyze-trends`, `generate-index`, `evaluate-session`, `outcome-session`,
> `test-pipeline` — see [Retired (930)](#retired-930-the-session-report-layer)) are **retired** —
> deleted, not deprecated. Do not infer that a layer is live because this doc documents it.
>
> **Removed (tempdoc 638):** the run-centric workflow-telemetry layer and its session-to-workflow attribution bridge (`scripts/lib/workflow-telemetry.mjs`, `scripts/bench/report-workflow-attribution.mjs`, the `tmp/workflow-telemetry/runs/` artifacts, and the workflow-telemetry contract) were deleted. The session-centric agent analytics pipeline described below is unaffected by that removal.

The `hooks/export-session-env.mjs` `SessionStart` hook still writes `JUSTSEARCH_AGENT_SESSION_ID` into `CLAUDE_ENV_FILE` so that session attribution is available to downstream tooling.

## Architecture

```text
Claude/Codex hooks ──> NDJSON event stream ──> Session reports ──> Trend reports
                       (append-only)           (per session)       (on demand)
                                                    └──> Outcome evaluations (LLM-as-judge, optional)
```

### Storage

```text
tmp/agent-telemetry/
  events.ndjson                       # Append-only hook event stream
  costs.ndjson                        # Per-session cost estimates from transcript tokens
  read-counts-{sessionId}.json        # Per-session read count cache (for compact-save.mjs)
  edit-counts-{sessionId}.json        # Per-session edit count cache (for compact-save.mjs)
  turn-count-{sessionId}.txt          # Per-session tool-call counter (dispatch.mjs, cleaned on session end)
  otlp/                               # Native OTLP capture (otlp-sink.py), rotated at 20 MB per stream
    traces.ndjson                     # Decoded spans — 14 archives kept
    metrics.ndjson                    # Decoded metric points + gen_ai.usage twins — every archive kept (cost baseline)
    logs.ndjson                       # Decoded log records VERBATIM, request bodies included — 2 archives kept (~1 GB/active day)
    ledger.ndjson                     # Body-free projection of logs.ndjson — 90 archives kept (~1 MB/day)
  sessions/
    {sessionId}.json                  # Per-session analysis report — NO PRODUCER since tempdoc 930
                                      # deleted analyze-session.mjs. `cost-session --all` still
                                      # reads the directory listing to pick which sessions to cost,
                                      # so it costs 0 on a checkout with no pre-930 reports.
```

## Event Capture

Every NDJSON line:

```json
{
  "schema_version": 1,
  "ts": "2026-02-03T12:00:00.000Z",
  "event": "pre_tool_use",
  "session_id": "...",
  "tool_name": "Read",
  "tool_use_id": "toolu_...",
  "tool_call_number": 42,
  "input_summary": {
    "file_path": "modules/ui-web/src/components/BrainView.tsx",
    "has_offset": false,
    "has_limit": false
  }
}
```

`tool_call_number` is a monotonically increasing per-session counter, tracked via file-based counters in `tmp/agent-telemetry/turn-count-{sessionId}.txt`. Counter files are cleaned up on `session_end`.

Content is never stored. Tool inputs are summarized to analytics-relevant fields only:

| Tool | Extract | Strip |
|------|---------|-------|
| Read | file_path, has_offset, has_limit | — |
| Edit | file_path, old_string length, new_string length | content |
| Write | file_path, content length | content |
| Bash | command first 200 chars | full output |
| Grep/Glob | pattern, path, output_mode | — |
| Task | subagent_type, prompt length | full prompt |
| mcp__* | tool name only | all inputs |

## Retired (930): the session-report layer

Six scripts were deleted in tempdoc 930 §17.1 — `analyze-session.mjs` (the sole producer of the
`agent-session-report.v1` per-session report), `analyze-trends.mjs`, `generate-index.mjs`,
`evaluate-session.mjs`, `outcome-session.mjs`, and `test-pipeline.mjs`. The grounds were measured,
not aesthetic: each had **zero invokers** since 2026-07-16 (745 F-1), and the stores they fed had
not moved since (`outcomes.ndjson` was never produced at all). No code imported any of them; no
`lib/` module was orphaned by their removal.

With `analyze-session.mjs` gone, the `agent-session-report.v1` schema has **no emitter and no
field-level reader**, so the schema block that documented it is removed too — read it out of git
history (`git show <pre-930>:docs/explanation/21-agent-analytics-pipeline.md`) if you need to parse
a pre-930 store. `outcomes.ndjson`, `judge-outcomes.ndjson` and `session-index.json` likewise have
no producer. What survives is the live half of the pipeline: the hook layer, the friction miners
(`mine-friction.mjs`, `aggregate-friction.mjs`, `friction-timeline.mjs`, `signature-census.mjs`)
and the cost→merge join (`baseline-economics.mjs`, `cost-session.mjs`, `record-merge.mjs`,
`merge-links.mjs`) — the two instruments with a recorded decision behind them.

## Retired: process-hygiene scoring

A composite 0–100 **Process Hygiene Index** (PHI) once sat on top of the session reports:
`score-session.mjs` normalised ten behavioural signals into a weighted score, applied two boolean
classification rules (WASTEFUL, THRASHING) with per-task-type suppressions, and flagged MAD-based
outliers; `correlate-signals.mjs` correlated those signals against judged outcomes;
`scripts/ci/check-agent-quality-trend.mjs` watched a trend baseline over `scores.ndjson`. All of it
was deleted in tempdoc 858 §7, along with `scores.ndjson`, the `score` / `flags` / `anomalies_count`
fields of `session-index.json`, and the score `evaluate-session.mjs` (itself deleted in 930) loaded and handed to the
judge — which that script had deliberately kept out of the judge's prompt anyway, to avoid
anchoring it, so removing it changes no verdict. The judge, the session reports and every report
built on them are unaffected.

**The grounds are that it could not be scored, not that it scored badly.** Each of these is
individually sufficient:

- **Nothing consumed it.** The HTML dashboard was deleted the day before (tempdoc 858 D1), and
  `check-agent-quality-trend.mjs` was wired to no workflow.
- **Its own calibration was inert.** Both per-type suppressions read a field that moved in the
  tempdoc 622 refactor, so neither fired for months; the path was fixed 2026-08-19 and the metric
  still ran uncalibrated because `task_type` originates in the LLM-judge cache, which had scored
  almost nothing.
- **Re-measuring is not possible at this corpus size.** A Pearson correlation at this
  instrument's own `|r| > 0.30` threshold needs 44 joined pairs before an `r` is distinguishable
  from zero at α = 0.05. The events store held 10 distinct sessions and the judge cache 2.

### The finding, and why the numbers no longer describe anything runnable

Recorded here because the machinery is gone and the measurement is the part worth keeping
(tempdoc 844 §4.3). Source: **tempdoc 277 §C4** — not tempdoc 118, which several retired files
cited and which is unresolvable for a reader of this repo.

| Result | Value |
|---|---|
| Composite score vs. task completion | r = 0.064, Cohen's d = 0.13, N = 116 (73 complete / 38 partial) |
| No individual signal, globally | \|r\| > 0.20 |
| `bash_fileop_pct` on **feature** sessions | r = +0.51, d = +1.11 — positively associated with completion |
| THRASHING rule on **implementation** sessions | fired on 33% of completed, 0% of partial — inverted |

So the composite was non-predictive while two components carried signal in the opposite direction
to the rule that used them, which is why 277's response was per-type suppression rather than
abandonment.

**Caveat, and it is the load-bearing part: these numbers describe a metric that no longer exists in
that form.** Between the measurement and the retirement the signal set went from 7 to 10, weights
and normalisation ceilings were re-derived, per-type hierarchical pooling was added, anomaly
detection moved from IQR to MAD, and `bash_fileop_pct` — the r = +0.51 headline — was **redefined
with no record in any tempdoc**. A fresh measurement would not re-test 277; it would measure a
different metric that happens to share the names.

**If you are about to propose a process-hygiene score, this is what to take from it.** The
composite is the part that failed: aggregating signals into one number destroyed the per-type
structure that carried the actual effect. The signal-level results survive as hypotheses worth
re-testing, not as findings to build on — re-derive them against your own definitions, and
declare a sufficiency floor from what you intend to conclude before you collect anything.

## Context Attribution

`context-attribution.mjs` classifies every content block in Claude Code transcript JSONL files by category: tool outputs (broken down by tool name), assistant text, thinking, user messages, and system messages. Uses chars/4 as a token estimate — sufficient for proportional analysis without a tokenizer.

Key findings across real sessions (N=41): tool outputs consume 80–85% of context (median), with `Read` alone at ~51%. Directly actionable for tuning `intervene.mjs` read limits.

## Harness-neutral session ledger

`lib/ledger/` (tempdoc 886 §12 PR 1) is a small projection layer sitting alongside `lib/transcript-store.mjs`/`lib/transcript-cost.mjs`: every reader above speaks Claude Code's transcript shape natively, but the unit that actually sets the bill — context tokens re-presented per API call — is the same idea across harnesses, only the field names differ. The ledger's `Call` record (`{harness, provider, project, sessionId, callId, lineage, ts, model, tokens, contextTokens, compactionBoundary}`) and `ToolEvent` record (`{harness, sessionId, callRef, role, name, inputChars, outputChars, isError, ts}`) are that neutral shape — a projection of each adapter's own log, never a second authority. Absent token axes (Codex has no billable cache write; Claude has no reasoning-token axis) are `null`, never `0`, so a reader cannot silently sum "not billed by this provider" as "billed zero this call".

Two harnesses are covered: **`claude-code`** (`lib/ledger/claude-adapter.mjs`, wrapping `lib/transcript-store.mjs` discovery) and **`codex-cli`** (`lib/ledger/codex-adapter.mjs`, reading `~/.codex/sessions/**/rollout-*.jsonl` directly — Codex has no equivalent shared substrate module yet). `lib/ledger/index.mjs`'s `listCalls()` merges both. `lib/ledger/tool-roles.mjs` maps each harness's tool names onto a shared role vocabulary (`read`/`edit`/`shell`/`search`/`spawn`/`wait`/`web`/`other`) so a reader can compare "how much shell use" across harnesses without a tool-name switch per harness. The Codex adapter also exposes paired raw tool exchanges for attribution readers that need content-level proof. That view is not part of the neutral ledger and must be reduced to aggregate facts in memory; consumers must not persist its prompts, commands, or outputs.

**The boundary rule (886 §10.4):** this library is machine-level (every project on the machine could use it), while the hooks/gates elsewhere in this pipeline are repo-level policy. Nothing under `lib/ledger/` may read `governance/`, `CLAUDE.md`, or `tmp/agent-telemetry` paths, or resolve a repo root via a relative climb — enforced by `lib/ledger/boundary.test.mjs`, not just documented here. `cache-efficiency.mjs` is the first migrated consumer (its file discovery now comes from `lib/ledger/claude-adapter.mjs`'s `listClaudeTranscriptFiles`); `lib/input-summarizer.mjs`, `lib/transcript-cost.mjs`, `baseline-economics.mjs`, and `context-attribution.mjs` migrated in 886 §12 PR 5a; `analyze-session.mjs`, `evaluate-session.mjs`, `friction-timeline.mjs`, `mine-friction.mjs`, and `overhead-taxonomy.mjs` migrated in PR 5b (below), completing this pipeline's "migrate the ONE reader you're already touching" convention across every hand-rolled `.claude/projects` walk (see `lib/transcript-store.mjs`'s own module doc). Two readers shelled out to the `claude` CLI for LLM-as-judge work at the time — `evaluate-session.mjs` (task-completion verdicts, deleted in 930) and `mine-friction.mjs` (process-friction mining) — that boundary is unaffected by either PR: discovery moved onto the shared substrate, but the actual judging call stays a Layer 4 concern (886 §12's layer split) neither PR touches.

**PR 5a (886 §12):** `lib/input-summarizer.mjs`'s hardcoded `switch (toolName)` in `summarizeInput` now dispatches on `roleFor('claude-code', name)` first, refined by name only where a role's members disagree on output shape (Edit vs Write; Grep vs Glob; WebSearch vs WebFetch) — byte-identical output, proven by a snapshot test captured from the pre-migration switch. `lib/transcript-cost.mjs`'s `PRICING` rows gained a `provider` column (`'anthropic'` for every current row — Codex CLI runs on subscription, not metered API tokens, so there is no verified per-token rate to record for it) and an exported `providerOf(model)` (fails closed to `null`, same matching rule as `findPricing`). `baseline-economics.mjs`'s hand-rolled `.claude/projects` walk (`discoverSessions`/`findSessionTranscript`) now calls `lib/transcript-store.mjs`'s `discoverProjectDirs`/`listSubagentPaths`; its definitional per-file `firstTranscriptTimestamp` scan moved to `lib/transcript-store.mjs` (imported back and re-exported, since `record-merge.mjs` depends on both `findSessionTranscript` and `DEFAULT_PROJECTS_ROOT` resolving from `baseline-economics.mjs`). `context-attribution.mjs` sources per-`tool_result` NAME resolution from `callsFromClaudeTranscript`'s `ToolEvent`s (replacing a private `tool_use_id`→name join), but deliberately keeps its own local, image-inclusive char computation rather than `ToolEvent.outputChars` — a parity run against this repo's real corpus found `outputChars` (built on the ledger's shared, text-only `extractToolResultText`) undercounts image-heavy tools by 84–99% (`mcp__claude-in-chrome__computer`, `browser_batch`, screenshot `Read`s), which would silently break the one thing this instrument measures — reproduce with `node context-attribution.mjs --all --top 15` against the real corpus (before/after `outputChars` substitution). See tempdoc 886 §12's "PR 5a outcome" paragraph for the full parity evidence.

**PR 5b (886 §12, "second half" of PR 5)** — dated history; `analyze-session.mjs` and `evaluate-session.mjs` were deleted in 930, the rest of this paragraph still describes live code: `analyze-session.mjs`'s hand-rolled cwd→project-hash fallback (`estimateDataCompleteness`) now calls `baseline-economics.mjs`'s `findSessionTranscript` (itself transcript-store-backed since PR 5a); its `Edit`/`Write`/`NotebookEdit`/`Read` tool-name literals (subagent-transcript tool_use scan and the OTLP `read`-role checks) now dispatch on `roleFor('claude-code', name)` — except the headline `file_edits.total` OTLP filter, deliberately left as a literal `'Edit'` check, since widening it to the `edit` role would also count `Write` and change that report's own most-consumed metric, which was out of scope here. `evaluate-session.mjs`'s directory-scan fallback (`findTranscriptByDirectoryScan`) and `summarizeToolUse`'s `switch(name)` migrated the same way (role-first, refined by name only where Edit/Write, Grep/Glob, or Bash/PowerShell disagree in shape) — `resolveClaudeBin`/the `claude` spawn itself is untouched. `friction-timeline.mjs` and `mine-friction.mjs` drop their single hand-slugged `--project-dir` default in favor of `lib/transcript-store.mjs`'s `discoverProjectDirs` — an explicit `--project-dir` still narrows to one directory, but the default now unions every `/justsearch/i`-matching project dir on the machine (main checkout + every worktree), fixing a real gap: the old single-dir default could only ever resolve a session recorded under the exact worktree the reader happened to run from. `overhead-taxonomy.mjs` deleted its own private `firstTranscriptTimestamp` copy (byte-identical to `lib/transcript-store.mjs`'s own, confirmed before deleting) in favor of importing it — the fourth copy of that scan the PR 5a review had flagged as the one item still outstanding. `context-attribution.mjs` picked up three review nits: its `'(zip-mismatch)'` fallback now calls `addTo` once per folded `tool_result` (count previously stuck at 1 while `chars` already held the full sum); its zip-safety comment now states the two joins cannot disagree BY CONSTRUCTION (structurally identical predicates), rather than citing a corpus scan as proof; and a note documents that an orphan/forward-referenced tool_result is labelled `'(unknown)'` by the ledger adapter, not the bare `'unknown'` this module's pre-PR-5a private join used — the adapter's label is kept as the one source of truth. See tempdoc 886 §12's "PR 5b outcome" paragraph for the full parity evidence.

### OTLP sink normalisation (886 §12 PR 3)

`otlp-sink.py`'s `decode_metrics` is the one place either harness's token-usage metric is decoded (`_attrs`, `~line 47`; `decode_metrics`, `~line 77`). Since 886 §12 PR 3 it additionally normalises every decoded data point onto the OTel GenAI semantic-convention vocabulary: for a data point whose metric name is in the module-level `GENAI_TOKEN_MAP` table (currently `claude_code.token.usage{type}` and `codex.turn.token_usage{token_type}`), `_genai_normalize` appends a flat twin record — `{name: 'gen_ai.usage', normalized: true, attributes: {...original attributes, 'gen_ai.system', 'gen_ai.request.model', 'gen_ai.token.kind'}, value, time_unix_nano}` — into the SAME `metrics.ndjson` stream as the original, unmodified record. Claude emits number data points; current Codex emits histogram points, whose token value is `HistogramDataPoint.sum`. `gen_ai.token.kind` is one of `input`/`output`/`cache_read`/`cache_creation` (including Codex `cache_write_input`) or additionally `reasoning` (Codex); Codex's `total` type is deliberately skipped (derivable as input+output, not a new axis). Because Codex's raw `input` type already *includes* cached tokens (the OpenAI convention, unlike Claude's `input` which excludes `cacheRead`/`cacheCreation`), the normalised record for that point carries an explicit `gen_ai.input_includes_cache_read: true` flag so a cross-harness reader cannot silently sum the two harnesses' "input" as the same quantity. A third harness needing this vocabulary is one new `GENAI_TOKEN_MAP` entry, not a new code path. `loadCostsFromOtlp` (`lib/telemetry-io.mjs`) reads session-attributed `gen_ai.usage` records first when present and skips the matching original point (keyed on session + `time_unix_nano` + raw type) so the two are never double-counted; archives written before this change carry no `gen_ai.usage` records at all, so the fallback to the original `claude_code.token.usage` reading runs unchanged for them. Codex CLI 0.153.0 does not put `session.id` on token metric points; those remain useful aggregate live evidence but are deliberately not guessed into a session row. The rollout ledger remains Codex's per-session historical authority. See `docs/how-to/wire-codex-cli-into-the-otlp-sink.md` for exporter wiring.

### OTLP ledger stream (930 F2)

The four OTLP streams share one rotate-at-20-MB path but not one retention policy (`RETENTION`, `otlp-sink.py` ~line 240). `logs.ndjson` is the constraint: `api_request` records embed request and response bodies, so it rotates every ~25 minutes of active work (~1 GB/active day) and can only retain 2 archives — which aged the *numbers* out of the capture within the hour, together with the bodies nothing reads. `ledger.ndjson` separates those two lifetimes. It is a **projection, not a second capture authority**: the `/v1/logs` route still writes `logs.ndjson` verbatim first, and every ledger row derives from a record already in that batch (`build_ledger_rows`). A row is emitted only for `api_request`, `subagent_completed`, `tool_result` and `tool_decision`, carrying `{signal, event, time_unix_nano, ts, session.id, attributes}` where `attributes` is a per-event **allow-list** (`LEDGER_KEEP`) of model / token counts / cost / durations / tool name / outcome — no `tool_input`, no bodies. Under the allow-list sits a second net: a string value is refused if its attribute name contains a content word (`prompt`, `body`, `content`, `message`, `input`, `output`, `text`, `arguments`, `result`) or exceeds 512 chars; numbers and booleans are exempt, which is what lets `input_tokens`/`output_tokens` through. `session.id` is the only identity attribute kept — it is the field every reader joins on — so `user.email`, `user.id`, `user.account_id`, `user.account_uuid` and `organization.id` are all dropped. At ~1 MB/day, `RETENTION["ledger"] = 90` is years of history. The same change capped `traces` at 14 archives; it had been unpruned (`None`) and reached 17 GB. Read it with `readOtlpLedger(dir)` (`lib/telemetry-io.mjs`), which is `loadOtlpStream(dir, 'ledger')` — archives first, current last, like every other stream.
## Auditing project skill delivery

Run the project skill inventory and Codex tool-read audit with:

```powershell
npm run analyze:skill-delivery -- --since 2026-08-01 --until 2026-09-04T10:00:00Z
```

`skill-delivery.mjs` inventories `.agents/skills` as the Codex authority and
`.claude/skills` as the Claude Code authority. Same-name skills are independent rows; neither is
treated as a generated projection of the other. The report separates files present in the working
tree from paths in Git's index; for tracked rows it separately reports whether the current file and
Codex policy match `HEAD`. It validates Codex frontmatter names against their directory names and
reports invalid metadata instead of silently treating it as discoverable. Current working-tree,
index-membership, and `HEAD`-matching totals are deliberately separate; no current bytes are
mislabelled as historical Git-blob contents. The report includes body and catalog-description
sizes per harness. Use
`--json` for the schema-v2 aggregate. `presentCatalogFieldCharsLowerBound` sums only the raw name,
description, and path fields; framing and separators used by a client make the actual initial
catalog at least that large. [OpenAI's skill documentation](https://learn.chatgpt.com/docs/build-skills)
says the initial catalog is bounded to 2% of the model context, or 8,000 characters when the
context size is unknown; descriptions are shortened first and skills may then be omitted. Treat a
catalog-field lower bound near that budget as a discovery risk, not merely a token-cost statistic.

The transcript pass reads Codex's active and archived rollout fragments. It snapshots every
fragment as of `--until`, retains tool exchanges whose start timestamp is inside the inclusive
window, and unions copied exchanges by session/call/start identity after normalising timestamps to
milliseconds. Byte-equivalent copies are deduplicated; conflicting copies are counted and
quarantined rather than resolved by an arbitrary winner. Calls without a start timestamp are
omitted and counted. Outputs without a timestamp are retained but classified as
`timestamp_indeterminate`, because their eligibility at the as-of cutoff cannot be proven.
Malformed lines, unreadable fragments, and missing/unreadable source roots are counted explicitly;
the CLI fails closed when neither active nor archived rollout root is readable. A
fixed upper bound is stable when a live rollout later appends more timestamped events; deletion,
retroactive editing, or undated appended entries can still change a rerun.

An exact copy of the current harness-specific `SKILL.md` in a tool result is positive
`proven_full_current` evidence. Indeterminate output timestamps, explicit truncation, intentional
partial reads, ambiguous batches, missing outputs, and historical/current mismatches remain
separate non-causal classes. This is
tool-read delivery evidence, not telemetry from Codex's native skill loader. It does not prove
model attention, rule adherence, task correctness, or cumulative coverage across several partial
reads. Raw commands and outputs are reduced in memory and never appear in the human or JSON report.

## Implementation Components

| File | Purpose |
|------|---------|
| `hooks/codex-hook-adapter.mjs` | Codex hook entry point. Maps Codex event/tool/transcript shapes to the shared manifest contract, runs matching shared handlers sequentially, and combines block decisions, updated inputs, and additional context. Claude-only bindings are explicitly excluded in code. |
| `hooks/dispatch.mjs` | Async entry point for all hook events. Reads stdin JSON, validates session_id, branches on event type, appends one NDJSON line. |
| `hooks/export-session-env.mjs` | Sync SessionStart hook. Writes `JUSTSEARCH_AGENT_SESSION_ID` into `CLAUDE_ENV_FILE` for later Bash commands in the same Claude session. |
| `hooks/intervene.mjs` | Sync PreToolUse hook (matcher: Read, Edit). Auto-injects `limit: 200` for files >8KB. Tracks per-session read/edit counts for compact-save orientation data. |
| `hooks/repeat-guard.mjs` | Sync PreToolUse hook (matcher: all). Blocks 3+ consecutive identical tool calls. Per-tool fingerprinting with MCP/internal tool support. Excludes build commands (deferred to build-counter). Buffer written atomically. |
| `hooks/build-counter.mjs` | Sync hook (matcher: Bash). Counts consecutive build failures on **PostToolUse** (synchronous, replacing the former async dispatch write) and blocks build commands on **PreToolUse** after 3+ failures. One-shot advisory pattern. |
| `hooks/subagent-guide.mjs` | Sync SubagentStart hook. Injects codebase context (large files list, docs index path) into subagent prompts. |
| `hooks/compact-save.mjs` | Sync PreCompact hook. Produces orientation data from read/edit caches and a timestamped Git workspace observation resolved from the event `cwd`. The snapshot records worktree, branch, and staged, unstaged, and untracked paths; it never attributes those paths to the current session. |
| `hooks/compact-restore.mjs` | Sync **SessionStart** hook. Atomically consumes saved state once and emits orientation as `additionalContext`. It displays the Git snapshot only when session id, normalized worktree, and branch match the current event; otherwise it omits the unproven snapshot. The legacy `.claude/rules/compaction-state.md` path is delete-only migration cleanup and is never written. |
| `lib/hook-base.mjs` | Shared hook plumbing (tempdoc 520 P1a): `readStdin`/`readJsonStdin`, `repoRoot`/`telemetryDir`, `atomicWriteFileSync`, `isDirectRun`, the `runHook` entrypoint, and the `JUSTSEARCH_DISABLE_HOOKS` kill switch (`hooksDisabled`). |
| `lib/event-writer.mjs` | Synchronous NDJSON append. Rotates `events.ndjson` at 10 MB (one `.prev` generation). |
| `lib/input-summarizer.mjs` | Extracts analytics fields from tool inputs. Strips content per the capture table above. `summarizeInput` dispatches on `lib/ledger/tool-roles.mjs`'s `roleFor('claude-code', name)`, refined by name only where a role's members disagree on shape (886 §12 PR 5a). |
| `lib/telemetry-io.mjs` | Shared I/O utilities: `loadEvents`, `groupBySession`, `loadNdjsonArray`, `loadNdjsonMap`, `round`, `loadOtlpStream`, `readOtlpLedger`, `loadEventsFromOtlp`, `loadCostsFromOtlp`. `readOtlpLedger` reads the sink's body-free `ledger.ndjson` projection (see [OTLP ledger stream](#otlp-ledger-stream-930-f2) above) — the stream to read for tokens/cost/tool outcomes older than the last couple of `logs.ndjson` rotations. `loadCostsFromOtlp` prefers `gen_ai.usage` normalised records over their `claude_code.token.usage` origin when both are present (886 §12 PR 3 — see [OTLP sink normalisation](#otlp-sink-normalisation-886-12-pr-3) above), adding a `harness` field (from `gen_ai.system`) to each session's record; falls back to the original reading for pre-normalisation archives. |
| `cost-session.mjs` | Per-session cost estimation from transcript JSONL. Per-turn pricing by actual model. CLI: `--session-id`, `--all`, `--json`. |
| `cache-efficiency.mjs` | Prompt-cache **efficiency** across the corpus, as opposed to the cost totals `cost-session`/`baseline-economics` produce. Answers *why* a cache write was paid: classifies each into extension / invalidation / cold start, attributes invalidations (compaction, model switch, TTL expiry, or an honest `in-ttl-undetermined` residual), reports the TTL tier by agent kind, delegation economics, and pricing coverage. Exports its classifiers (`classifyWrite`, `invalidationCause`) for test. CLI: `--since <ISO>`, `--json`, `--harness claude-code\|codex-cli` (886 §12 PR 1 — the ledger's first consumer; `codex-cli` prints a smaller summary with the cache-write axis as `n/a`, not `0`, since Codex has no billable cache write). |
| `lib/ledger/record.mjs` | The neutral `Call`/`ToolEvent` record (tempdoc 886 §12 PR 1) — see [Harness-neutral session ledger](#harness-neutral-session-ledger) below. Exports `makeCall`/`isCall`, `makeToolEvent`/`isToolEvent`. |
| `lib/ledger/tool-roles.mjs` | Per-harness tool-name → `ToolEvent.role` map (`read`\|`edit`\|`shell`\|`search`\|`spawn`\|`wait`\|`web`\|`other`). Exports `roleFor(harness, toolName)` and the two data tables (`CLAUDE_TOOL_ROLES`, `CODEX_TOOL_ROLES`), consumed by `lib/input-summarizer.mjs`'s role-keyed dispatch (886 §12 PR 5a). |
| `lib/ledger/claude-adapter.mjs` | Claude Code transcripts → `Call`/`ToolEvent`. Wraps `lib/transcript-store.mjs` discovery; dedups by `message.id`; joins `tool_use`→`tool_result` for tool-name attribution; subagent lineage from `subagents/*.meta.json`. Exports `listClaudeCalls`, `listClaudeTranscriptFiles` (the file-discovery helper `cache-efficiency.mjs` now uses), and `callsFromClaudeTranscript(filePath, {sessionId, project, lineage})` (886 §12 PR 4 — a single-file parse for a caller that has already resolved ONE transcript). |
| `lib/ledger/codex-adapter.mjs` | OpenAI Codex CLI rollout transcripts (`~/.codex/sessions/**/rollout-*.jsonl`) → `Call`/`ToolEvent`. Verified corpus-wide rules from tempdoc 886 §11's derisk pass: `input_tokens` already includes `cached_input_tokens` (A1); `last_token_usage` is a per-call delta, and a `token_count` event whose cumulative total exactly repeats the previous one is dropped, not counted (A2); tool outputs are capped at 64k chars with a `truncated` flag (A7). **Usage source (A8, tempdoc 951):** a rollout carrying Codex's native per-response `token_usage_record` lines builds every `Call` from `payload.usage` (deduplicated first-wins by `payload.response_id`) and none from `token_count`; a rollout with no usable native record falls back to the A1/A2 `token_count` path unchanged. The two streams are never summed. `token_count` is a UI notification, so a response the CLI never notified on is absent from it; on a verified real rollout (38 native records, 40 `token_count` events) the compaction-summarizer response has a native record and no notification, and the legacy input total (5,235,254) understates the native total (5,469,732) by exactly that one record. `token_count` is still read on the native path for the legacy `selfCheck` diagnostics only. Current child rollouts carry a real parent edge and semantic role in `session_meta.payload.source.subagent.thread_spawn`; those calls use `lineage.kind = 'spawn'`, while sessions without that evidence remain `main`. `turn_context` supplies actual model and reasoning effort. `inter_agent_communication_metadata` remains only the session-level `multiAgent` flag because it names no parent. The first call after a `compacted` line carries `compactionBoundary: true` on either stream, and a `compacted` line with no following call on the active stream gets a `synthetic: true` boundary `Call`. A file with no usable `sessionId` is the one documented skip (`skipped: [{file, reason}]`); any other exception propagates. `session.selfCheck` reports `{deltaInputSum, maxCumulativeInput, resets, repeatsDropped, usageSource, nativeRecords, nativeDuplicatesDropped}` (`usageSource` is `'native'` or `'legacy'`). Exports `listCodexCalls`, `processCodexEntries`, and the deliberately raw `listCodexToolExchanges` / `processCodexToolExchanges` attribution view. The neutral ledger remains active-session/mtime based. The raw view pairs uncapped tool input/output across active and archived fragments, applies an as-of event-time window, deduplicates equivalent timestamp spellings, quarantines conflicting copies, preserves undated outputs as indeterminate evidence, and represents a call whose result had not arrived at the cutoff; it does not change or bypass the neutral `ToolEvent` output-size cap. |
| `lib/ledger/boundary-check.mjs` | Pure `findBoundaryViolations(sourceText, filename)` — the §10.4 boundary rule as an importable function (independent-review SHOULD-FIX 4), not inline test logic, so `boundary.test.mjs` can prove the checker catches a violation (side-effect import, multi-line `import {...} from`), not just that today's files pass. |
| `lib/ledger/index.mjs` | Harness merge point. Exports `listCalls({harnesses, sinceMs, untilMs, projectFilter})` → `{calls, toolEvents, sessions, skipped}`, never throws. |
| `context-residency.mjs` | The unmeasured variable tempdoc 886 §2 found: context tokens re-presented **per call**, not cache-hit rate. Sections (a-c) read the neutral ledger (harness-neutral by construction): per-call context distribution by harness × lineage-kind (main vs spawn/fork) × model (p50/p75/p90/p99/max, total context/output, ctx/out ratio); share of context tokens and cache-read-priced cost above `--cap` (fails closed on an unpriced model — counts tokens, never prices at silent `$0`); the compaction ledger (trigger breakdown, pre/post tokens, durationMs). Section (d) — compounded residency, tokens × calls-resident × cache-read-rate, reset at each compaction boundary — reads raw Claude transcripts directly (same precedent as `cache-efficiency.mjs`'s TTL taxonomy: no neutral `Call`/`ToolEvent` axis for a plain text/thinking block's size), so it reports `claude-code` only. Productionises `tmp/tokeff/{deep,deep3,deep4}.mjs` (886 §12 PR 2). CLI: `--since <ISO>` (default trailing 30 days), `--until`, `--harness claude-code\|codex-cli\|all`, `--cap <tokens>` (default 200000), `--json`. Synthetic calls are excluded from every distribution. |
| `spawn-economics.mjs` | Joins Claude and Codex spawn/fork lineage (`agentType`, requested model when the harness records it, actual model and effort, parent session) to COST (the ledger stores token axes, not dollars — priced per call via each axis's own rate) and Claude's `firstUserMessageChars` (opening-turn chars, read off the raw spawn transcript — a mixed proxy, not a clean "brief length"). Tables: requested→actual model, by `agentType`, by role/model/effort, run-length buckets (`[0-10,10-30,30-60,60-120,120-250,250-500,500+]`) with cost share, and top-N by cost. Codex parent sessions that expose only `session.multiAgent` remain in a separate parent-session table; attributed Codex child calls are excluded from that residual. Productionises `tmp/tokeff/deep3.mjs` (886 §12 PR 2) and adds current Codex thread-spawn attribution in tempdoc 937. CLI: `--since`, `--until`, `--harness`, `--json`, `--top N` (default 20). |
| `overhead-taxonomy.mjs` | Tempdoc 743 Phase 2's WAITING / RE-ORIENTATION / HOOK-FRICTION / CEREMONY taxonomy (byte-faithful category definitions since the scratchpad rescue). Default window is **trailing 30 days** (886 §12 PR 2 — a bare invocation previously hardcoded 2026-06-18..07-16 and so returned 0 sessions on any later date); pass `--since`/`--until` explicitly to reproduce the original T1 figures. Its own private `firstTranscriptTimestamp` copy was retired in favor of `lib/transcript-store.mjs`'s (886 §12 PR 5b). CLI: `--since`, `--until`, `--projects-root`. |
| `skill-delivery.mjs` | Independently inventories the native Codex and Claude Code skill trees, distinguishes current working-tree files, Git index membership, and rows matching `HEAD`, validates Codex skill metadata, and audits Codex rollout tool exchanges that read either harness path. Exact containment of the complete current harness-specific file proves `proven_full_current`; an undated output is `timestamp_indeterminate`, while explicit tool-result truncation, intentional partial reads, ambiguous batching, missing results, and unproven historical/current mismatches remain separate classifications. The schema records the project regex and source-root diagnostics. The reader does not claim native skill-loader selection, infer that a capped batch omitted a particular section, reconstruct cumulative coverage, or claim attention/adherence. Human and schema-v2 `--json` output contain aggregates only; raw prompts, commands, and outputs stay in memory. CLI: `--since`, `--until`, `--repo-root`, `--codex-home`, `--project-pattern`, `--json`. |
| `context-attribution.mjs` | Context window attribution: classifies transcript content blocks by category (tool outputs by tool name, assistant text, thinking, user messages, system). Chars/4 ≈ tokens. Per-`tool_result` tool-NAME resolution comes from `lib/ledger/claude-adapter.mjs`'s `callsFromClaudeTranscript` (886 §12 PR 5a); char counts stay a local, image-inclusive computation (see PR 5a outcome above — `ToolEvent.outputChars` is text-only and undercounts screenshot-heavy tools). An orphan/forward-referenced `tool_result` is labelled `'(unknown)'` (the ledger adapter's spelling, adopted in PR 5a; the module's own pre-migration join used bare `'unknown'` — 886 §12 PR 5b documented the difference, no behavior change). CLI: `--session-id`, `--all`, `--json`, `--top N`. |
| `friction-timeline.mjs` | Timeline view over `mine-friction.mjs` output — friction category counts/weights bucketed by session date (day/3day/week). Session-date resolution is `lib/transcript-store.mjs`-backed (`discoverProjectDirs`/`firstTranscriptTimestamp`, 886 §12 PR 5b). CLI: `--project-dir`, `--bucket`, `--include-excluded`. |
| `mine-friction.mjs` | Judges PROCESS friction (wasted turns/tokens) via a condense-then-judge-via-`claude`-CLI pass (still shells out, untouched by PR 5b). Its task-completion sibling `evaluate-session.mjs` was deleted in tempdoc 930, so this is the lane's only judge. Transcript discovery is `lib/transcript-store.mjs`-backed (`discoverProjectDirs`, 886 §12 PR 5b); output cached per-session in `tmp/agent-telemetry/friction-results/`. CLI: `--limit`, `--concurrency`, `--project-dir`. |

### Hook Configuration

`governance/agent-hooks.v1.json` is the binding authority. Claude's generator
projects `.claude/settings.json`; Codex's generator projects a single adapter
entry per supported event into `.codex/hooks.json`. The single adapter preserves
manifest order because Codex otherwise runs same-event command handlers
concurrently.

Claude-specific behavior:
- Hook entries across multiple event types
- `export-session-env.mjs` runs first on `SessionStart` so later Bash commands inherit `JUSTSEARCH_AGENT_SESSION_ID`
- Analytics hooks (`dispatch.mjs`) use `"async": true` — never block the agent
- Intervention hooks (`intervene.mjs`, `repeat-guard.mjs`, `build-counter.mjs`) are synchronous with matchers — only fire for matched tool calls
- `build-counter.mjs` is also wired synchronously on `PostToolUse` (Bash, gradlew) to record pass/fail — so the next `PreToolUse` check reads a fresh count (tempdoc 520 P0f closed the prior async-write/sync-read race)
- `compact-restore.mjs` is wired only on `SessionStart`; recovery context is one-shot
- 5s timeout for hot-path hooks; 30s for SessionEnd
- **Kill switch:** `JUSTSEARCH_DISABLE_HOOKS=1` disables all session-affecting hooks via `hook-base.runHook` / `hooksDisabled` (tempdoc 520 P1c)

Codex-specific behavior:

- `apply_patch`, unified shell execution, and native agent events are normalized to the shared Edit/Write, Bash, and Agent matchers.
- `PostToolUseFailure`, `CwdChanged`, and `InstructionsLoaded` have no Codex event equivalent and are not generated.
- `subagent-model-guard` is an explicit semantic exclusion, not a silent no-op.
- `compact-restore` injects one-shot recovery context directly and does not write a rule file in either harness.
- The same `JUSTSEARCH_DISABLE_HOOKS=1` kill switch applies.

### Hook Interaction

Hooks fire in registration order. For Bash tool calls, the public chain is
`publication-merge-guard.mjs` → `agent-spawn-build-hint.mjs` → conditional
`build-counter.mjs` → `repeat-guard.mjs`. A maintainer's full local projection
prepends asynchronous `dispatch.mjs` to that chain.
If a sync hook exits 2 (block), subsequent hooks likely do not fire (short-circuit).

For `SessionStart`, the public chain is `compact-restore.mjs` followed by the
asynchronous agent-spawn sweep hint. A maintainer's full local projection adds
`export-session-env.mjs`, asynchronous `dispatch.mjs`, `otlp-sink-ensure.mjs`,
and the same sweep hint around `compact-restore.mjs`, in manifest order.

Subagent attribution in phase 1 is parent-owned. `subagent-guide.mjs` includes the parent
`session_id` and instructs subagents to pass `--session-id <parent-session-id>` when invoking
maintained workflow wrappers or DAG runners.

Known interaction design decisions:
- **repeat-guard excludes build commands** (`/gradlew/i`). Without this, repeat-guard
  blocks the 3rd consecutive build before build-counter reaches its failure threshold.
  Build-counter has purpose-built one-shot advisory logic; repeat-guard defers to it.
- **build-counter owns its state synchronously.** Its PostToolUse invocation records
  the result before the next PreToolUse check; asynchronous dispatch telemetry is not
  part of the guard's decision path.
- **Parallel tool calls** produce race conditions on state files (last writer wins).
  Practical impact is low — parallel calls are typically different tools.

### Process Overhead

The public projection starts one synchronous `repeat-guard.mjs` process for every
PreToolUse event, plus matcher-specific processes such as the Bash publication
guard, build hint, and conditional Gradle build counter. The full local projection
adds one asynchronous `dispatch.mjs` process to every PreToolUse event. Hook count
therefore depends on the projection and tool matcher; startup cost should be
measured on the active host rather than inferred from a fixed per-call estimate.

### Error Isolation

`dispatch.mjs` wraps each event handler in try/catch. A crash in one tool type's summarizer must not prevent other events from recording. Errors are logged to `tmp/agent-telemetry/errors.log`, not stderr.

## Design Constraints

- **No external services.** All data local, file-based, under `tmp/`.
- **No new dependencies.** Uses Node.js stdlib only.
- **Async hooks for analytics, sync only for intervention.**
- **Transcript parsing is best-effort.** ~50% of subagent transcripts are cleaned up by Claude Code. `transcript_coverage` quantifies the gap.
- **Manual fallback for analysis.** `SessionEnd` may not fire on crashes — analyzers work as CLI tools.

## Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Hook stops firing silently ([#6305](https://github.com/anthropics/claude-code/issues/6305)) | Medium | `data_completeness` detects missing events |
| Transcript cleanup eliminates subagent data (~50%) | Medium | `transcript_coverage` quantifies; accepted |
| SessionStart hooks break stdin on Windows ([#23083](https://github.com/anthropics/claude-code/issues/23083)) | Medium | Monitor |
| 100% CPU hang with parallel instances + hooks ([#22172](https://github.com/anthropics/claude-code/issues/22172)) | Medium | Avoid parallel Claude Code instances |
| `intervene.mjs` Node startup adds latency (30-80ms per Read) | Low | Acceptable; scoped via matcher |
| Event rotation drops history a report was built from | Medium | Skip-if-degraded guard in `writeReport()`; rotation limit 10 MB |
| Memory pressure from large session analysis | Low | Fine at 50+ sessions; may need streaming at 100+ |

## Known Limitations

- **Self-monitoring paradox.** The agent exhibiting waste is also the one reading pipeline output. Mitigated by `.claude/rules/` (loaded at session start) rather than requiring mid-session analytics reads.
- **intervene.mjs effectiveness is untestable.** Analytics capture pre-intervention state. We know the hook fires but can't directly measure context savings.
- **There is no composite quality number for a session.** The process-hygiene score that used to supply one is retired; the reasoning and its measurement are in [Retired: process-hygiene scoring](#retired-process-hygiene-scoring). Do not reintroduce one without reading that section first.

## Test Suite

The wired entry point is `node scripts/agent-analytics/run-all-tests.mjs`, which discovers and runs
every `*.test.mjs` under `scripts/agent-analytics/` and exits non-zero if any file fails. That is
the suite to run and the one CI runs.

`test-pipeline.mjs` — a legacy standalone assertion script `run-all-tests.mjs` never discovered (it
is not a `*.test.mjs` file), so nothing invoked it and it had accumulated stale failures — was
deleted in tempdoc 930 along with the five scripts most of its groups covered. A suite nothing runs
asserts nothing; the live assertions are the `*.test.mjs` files above.
