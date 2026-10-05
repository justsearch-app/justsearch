---
title: "Wire Codex CLI into the OTLP Sink"
type: how-to
status: superseded
description: "Point OpenAI Codex CLI's native OTel exporter at the repo's local otlp-sink.py so its token usage lands in the same gen_ai.usage-normalised metrics stream as Claude Code, for cross-harness cost/context analysis."
related: tempdoc 886 §10.3 option B, §12 PR 3
audience: maintainer
---

> **Superseded 2026-10-05.** JustSearch now uses agent-system for agent work: the projected `AGENTS.md` entry, private project knowledge and the system's own role contracts and guides. This repository's former agent layer (`CLAUDE.md`, `.claude/rules`, `.claude/skills`, `.agents/`, Codex agents and the hook manifest) was retired at adoption. This page is kept as history; it does not describe current behaviour.

# Wire Codex CLI into the OTLP Sink

## Why

Tempdoc 886 found that the machine this repo is developed on runs two
agent harnesses side by side — Claude Code and OpenAI Codex CLI — and
that Claude-only cost/context measurement was missing roughly a third
of actual agent spend (886 §9). Both harnesses can emit native OTel
telemetry; `scripts/agent-analytics/otlp-sink.py` already receives
Claude Code's stream and is harness-generic by construction (no
Claude-specific names anywhere in the receiver). §10.3 surveyed four
ways to fold Codex in and picked **option B (OTLP-only) as the live/
timing layer**, alongside log adapters (`lib/ledger/codex-adapter.mjs`)
as the source of truth for historical analysis. This doc is the OTLP
half: wiring Codex's own `[otel]` exporter at the same sink.

`otlp-sink.py` normalises both harnesses' token-usage metrics onto the
OTel GenAI semantic-convention vocabulary (`gen_ai.usage`,
`gen_ai.token.kind` in `input`/`output`/`cache_read`/`cache_creation`/
`reasoning`) additively — the original `claude_code.token.usage` /
`codex.turn.token_usage` records are kept unchanged, and a normalised
twin is appended alongside for every point whose raw type is known.
`gen_ai.system` on each normalised record identifies which harness
produced it (`claude-code` or `codex-cli`), so a reader over
`tmp/agent-telemetry/otlp/metrics.ndjson` never needs a branch per
harness once Codex is wired.

## Configuration

Codex CLI reads OTel settings from `~/.codex/config.toml`. Add (or
merge into an existing `[otel]` section):

```toml
[otel]
environment = "dev"
log_user_prompt = false
exporter = { otlp-http = { endpoint = "http://127.0.0.1:4318/v1/logs", protocol = "binary" } }
trace_exporter = { otlp-http = { endpoint = "http://127.0.0.1:4318/v1/traces", protocol = "binary" } }
metrics_exporter = { otlp-http = { endpoint = "http://127.0.0.1:4318/v1/metrics", protocol = "binary" } }
```

- `log_user_prompt = false` keeps prompt text out of the emitted
  `codex.user_prompt` log event — this repo's telemetry capture is
  local-only and never leaves the machine, but redaction at the
  source is still the safer default.
- `exporter`, `trace_exporter`, and `metrics_exporter` target the sink's
  explicit `/v1/logs`, `/v1/traces`, and `/v1/metrics` routes. Current Codex
  configures these independently; setting only `exporter` does not redirect
  metrics from its default backend.
- `protocol = "binary"` (OTLP/HTTP protobuf) matches what the sink's
  `ROUTES` table decodes; this is the same wire format Claude Code
  already sends.

Restart Codex CLI (or start a fresh session) after editing the config
so it picks up the new `[otel]` block.

## Sink startup

The repository's generated `.codex/hooks.json` projects the shared
`otlp-sink-ensure` SessionStart binding through
`scripts/agent-analytics/hooks/codex-hook-adapter.mjs`. A trusted Codex
session therefore starts or reuses the same ownerless sink automatically,
just as Claude Code does. If hooks are disabled or you are diagnosing startup,
the equivalent manual command is:

```bash
python scripts/agent-analytics/otlp-sink.py --port 4318
```

It appends to `tmp/agent-telemetry/otlp/{metrics,
traces,logs}.ndjson` exactly as it does for Claude Code, and rotates/
prunes those streams the same way (`docs/explanation/
21-agent-analytics-pipeline.md`, `RETENTION` in `otlp-sink.py`).

**Volume note:** the `gen_ai.usage` normalisation (886 §12 PR 3) writes an
additional record per mapped token-usage data point alongside the original —
roughly **doubling** `metrics.ndjson` volume for every point that gets a
twin. `RETENTION["metrics"]` is `None` (never pruned — metrics is the sole
cost-baseline source, see `otlp-sink.py`'s module comment), so this is
disk growth that accumulates indefinitely, not a self-cleaning cost — check
the current size with `du -sh tmp/agent-telemetry/otlp/metrics.ndjson`. This
is a stated tradeoff, not an oversight — changing the retention policy is an
owner decision, not made here.

## One-session smoke check

After a short Codex turn or two with `[otel]` wired as above, confirm
normalised Codex records actually landed:

```bash
grep -c '"gen_ai.system": "codex-cli"' tmp/agent-telemetry/otlp/metrics.ndjson
```

A count greater than `0` confirms the wiring end to end: Codex's
exporter reached the sink, `decode_metrics` recognised
`codex.turn.token_usage`, and `GENAI_TOKEN_MAP` produced at least one
normalised `gen_ai.usage` record. `0` (or the file not existing) means
one of: the `[otel]` block wasn't picked up (restart Codex), the sink
isn't listening on `4318` (check for a bound-port error), or the turn
ran before the sink started (start the sink first, then run Codex).

Current Codex emits token usage as histogram points; the sink reads each
point's `sum`. It maps `cached_input` to `cache_read`, `cache_write_input` to
`cache_creation`, and `reasoning_output` to `reasoning`. As of Codex CLI
0.153.0 those metric points do not carry `session.id`, so they support the
aggregate live feed but are not attached to a per-session cost row by
`loadCostsFromOtlp`. The rollout adapter under `lib/ledger/` remains the
per-session historical authority. Do not synthesize a session join from timing.

The migration smoke test runs a real non-interactive Codex turn and checks this
record. For later diagnosis, the same count remains a useful end-to-end probe.

## Hook coverage

Codex hooks are repository-governed: edit `governance/agent-hooks.v1.json` and
regenerate `.codex/hooks.json`, never hand-edit it. Codex rollouts do not
expose a reliable parent-tool-to-spawn transcript join, so per-spawn cost
attribution is Claude-only; aggregate Codex multi-agent economics remain
available through the neutral ledger reports.

## References

- `scripts/agent-analytics/otlp-sink.py` — `GENAI_TOKEN_MAP`,
  `_genai_normalize`, `ROUTES`.
- `scripts/agent-analytics/lib/telemetry-io.mjs` — `loadCostsFromOtlp`
  prefers `gen_ai.usage` records when present, with a fallback for
  archives written before this change.
- The design rationale (OTLP as the live layer next to the transcript
  adapters, and the one-session smoke check) is dated working history in
  the 886 tempdoc; the durable description lives in the explanation doc
  below.
- `docs/explanation/21-agent-analytics-pipeline.md` — sink hook
  wiring, rotation/retention policy, the harness-neutral session
  ledger.
