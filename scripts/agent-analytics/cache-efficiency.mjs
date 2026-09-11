#!/usr/bin/env node
/**
 * Prompt-cache efficiency reader (tempdoc 841).
 *
 * WHY THIS EXISTS. `cost-session.mjs` / `baseline-economics.mjs` already TOTAL
 * the cache columns, and `lib/transcript-cost.mjs` already parses the 5m/1h
 * write tiers correctly (tempdoc 745 item B). What no reader did was ask why a
 * cache write was paid for. Tempdoc 841 measured that by hand and found the
 * answer is not one thing: across the local corpus, ~556 prefix-INVALIDATION
 * events cost more cache-write than ~61.5k turns of ordinary per-turn
 * EXTENSION. A single `cache_write_tokens` number cannot show that, so the
 * finding was invisible to every existing report.
 *
 * This reader is deliberately a READER, not new substrate: discovery comes from
 * `lib/transcript-store.mjs`, pricing from `lib/transcript-cost.mjs`. It adds
 * classification on top. (The `iterateTurns` shape gained `model` /
 * `messageId` / `requestId` / `isCompactBoundary` for this — additive, so
 * existing consumers are untouched.)
 *
 * WHAT IT DOES NOT DO. It does not explain the in-TTL invalidations. Tempdoc
 * 841 §4 established that ~87% of them have no cause visible in a transcript —
 * transcripts record token USAGE, not the prompt prefix, so client-side
 * breakpoint re-anchoring and server-side eviction are indistinguishable here.
 * They are reported as `in-ttl-undetermined` and that label is honest, not a
 * placeholder for a cause someone forgot to fill in. Settling it needs
 * request-layer visibility (tempdoc 622's native OTel spans).
 *
 * FIRST LEDGER CONSUMER (tempdoc 886 §12 PR 1). File DISCOVERY now comes from
 * `lib/ledger/claude-adapter.mjs`'s `listClaudeTranscriptFiles` — the same
 * recursive depth-3 walk this reader used to do inline (`collectFiles` +
 * a `discoverProjectDirs` call), now centralised in the ledger so it is
 * provably the identical file set (bit-identical numbers before/after this
 * migration). The per-file CLASSIFICATION below (`analyseTranscript` and its
 * pure helpers) intentionally still reads `iterateTurns`/`message.usage`
 * directly rather than a `Call` object: the cache TTL/invalidation taxonomy
 * here is Claude-specific (prompt-cache write tiers, compaction boundaries)
 * with no Codex equivalent, and `analyseTranscript`'s file-based signature is
 * exercised directly by this file's own regression tests (notably the
 * partial-dedup + compaction test), which this migration does not touch.
 *
 * `--harness` (default `claude-code`) selects the provider. `codex-cli` runs
 * a much smaller ledger-only summary — Codex has no billable cache WRITE at
 * all (OpenAI's convention: `cached_input_tokens` is a read discount, never a
 * separate write charge), so the write axis prints as an explicit `n/a`
 * rather than a `0` that would misleadingly imply "measured and found zero".
 *
 * Usage:
 *   node scripts/agent-analytics/cache-efficiency.mjs [--since <ISO>] [--json] [--harness claude-code|codex-cli]
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { iterateTurns } from './lib/transcript-store.mjs';
import { findPricing, isKnownModel, isNonBillableModel, PER_M } from './lib/transcript-cost.mjs';
import { listClaudeTranscriptFiles } from './lib/ledger/claude-adapter.mjs';
import { listCalls } from './lib/ledger/index.mjs';

// --- classification constants (judgment calls, so they are named and visible) --

/**
 * Cache TTL by transcript kind. NOT our choice — measured, harness-set: across
 * the tempdoc 841 corpus, main sessions wrote 100% 1h-tier (16,402 turns) and
 * subagents 100% 5m-tier (46,188 turns), with zero mixing. If a future corpus
 * shows mixing, this constant is the thing that became wrong.
 */
export const TTL_SEC_BY_KIND = { main: 3600, subagent: 300 };

/**
 * A turn counts as an invalidation when its `cache_read` falls below this
 * fraction of the previous turn's. Not 1.0: `cache_read` jitters slightly
 * turn-to-turn without the prefix being lost. 0.9 was the threshold used for
 * 841's measurements; every number in that tempdoc assumes it.
 */
export const INVALIDATION_READ_DROP_RATIO = 0.9;

/**
 * Invalidations below this write size are reported separately from "large"
 * ones. 841 §4's floor analysis (293 of 305 large events collapsed to a
 * 10k-40k prefix) only holds for the large class; small ones are dominated by
 * breakpoint jitter and are not evidence of a lost conversation body.
 */
export const LARGE_INVALIDATION_WRITE = 50_000;

// --- pure classifiers (exported for test) ------------------------------------

/**
 * Why did this turn pay a cache write?
 *   'cold'         — nothing was read at all: a fresh session/subagent prefix.
 *   'invalidation' — the readable prefix SHRANK: the cached body was lost.
 *   'extension'    — the normal case: prefix grew, the delta was written.
 * `prev`/`cur` are `{ read, write }`. A first turn with a non-zero read is
 * 'extension' (a resumed session legitimately reads an existing prefix).
 */
export function classifyWrite(prev, cur) {
  if (cur.read === 0) return 'cold';
  if (prev && cur.read < prev.read * INVALIDATION_READ_DROP_RATIO) return 'invalidation';
  return 'extension';
}

/**
 * Best available attribution for an invalidation. Check order is deliberate and
 * load-bearing: compaction and a model switch are POSITIVE evidence, so they
 * win over the merely-circumstantial TTL test; `in-ttl-undetermined` is the
 * residual and must stay last so it never absorbs a case we can actually name.
 */
export function invalidationCause({ prev, cur, gapSec, kind, compactSeen }) {
  if (compactSeen) return 'compaction';
  if (prev && cur.model && prev.model && cur.model !== prev.model) return 'model-switch';
  const ttl = TTL_SEC_BY_KIND[kind] ?? TTL_SEC_BY_KIND.main;
  if (gapSec != null && gapSec > ttl) return 'ttl-expiry';
  return 'in-ttl-undetermined';
}

/**
 * Dedupe usage-bearing turns by `(messageId, requestId)` keeping the LAST usage
 * snapshot while preserving first-occurrence ORDER. Last-wins is required
 * because subagent transcripts persist streaming partials that grow (tempdoc
 * 745 item B bug 2); first-occurrence order is required because every
 * classifier here is sequential.
 */
export function dedupeUsageTurns(turns) {
  const order = [];
  const byKey = new Map();
  for (const t of turns) {
    const key = `${t.messageId}|${t.requestId}`;
    if (!byKey.has(key)) order.push(key);
    byKey.set(key, t);
  }
  return order.map((k) => byKey.get(k));
}

// --- aggregation -------------------------------------------------------------

export function emptyReport() {
  return {
    transcripts: { main: 0, subagent: 0 },
    turns: { main: 0, subagent: 0 },
    tokens: { read: 0, write: 0, write5m: 0, write1h: 0, input: 0, output: 0 },
    writeCause: { cold: { n: 0, tokens: 0 }, extension: { n: 0, tokens: 0 }, invalidation: { n: 0, tokens: 0 } },
    invalidationCause: {},
    largeInvalidations: { n: 0, tokens: 0 },
    // Compaction is tracked INDEPENDENTLY of the write-cause split, because it
    // usually lands in `cold` (a compacted context reads 0) and so would never
    // appear in the invalidation table — leaving a reader to conclude, wrongly,
    // that compaction never costs a prefix. It is counted wherever it lands.
    compaction: { boundaries: 0, writeTurns: 0, tokens: 0 },
    tierByKind: { main: { w5m: 0, w1h: 0 }, subagent: { w5m: 0, w1h: 0 } },
    delegation: { spawns: 0, coldWrite: 0, ttlRewrite: 0, ttlEvents: 0, subRead: 0, subOut: 0, mainRead: 0, mainOut: 0 },
    pricing: {
      pricedCost: 0, unpricedModels: {}, unpricedRead: 0, unpricedTurns: 0,
      // known-non-billable placeholders (tempdoc 908 §4.5, e.g. `<synthetic>`)
      // tracked SEPARATELY from genuinely unknown models, so they never trip
      // the loud "!!" alarm below -- that alarm is reserved for a real gap.
      nonBillable: {},
      // per-line split, so "N% of spend is context re-presentation rather than
      // generation" is a figure this tool produces rather than a claim someone
      // has to take on trust
      byLine: { cacheRead: 0, cacheWrite: 0, input: 0, output: 0 },
    },
  };
}

export function analyseTranscript(file, report, { sinceMs = null } = {}) {
  const kind = /[\\/]subagents[\\/]/.test(file) ? 'subagent' : 'main';

  // One pass: keep usage turns, and remember whether a compact boundary sat
  // between this usage turn and the previous one.
  //
  // The flag is keyed to the MESSAGE, not to the individual JSONL line. A
  // compaction boundary is followed by streaming partials of one message; the
  // flag rides the first partial, and `dedupeUsageTurns` keeps the LAST one. A
  // per-line flag therefore gets silently discarded by the dedup — which is
  // exactly the bug that made this counter read 0 while 16 real boundaries sat
  // in the corpus.
  const raw = [];
  const compactedKeys = new Set();
  let compactPending = false;
  for (const t of iterateTurns(file)) {
    if (t.isCompactBoundary) compactPending = true;
    if (!t.usage) continue;
    if (compactPending) { compactedKeys.add(`${t.messageId}|${t.requestId}`); compactPending = false; }
    raw.push({
      read: t.usage.cache_read_input_tokens ?? 0,
      write: t.usage.cache_creation_input_tokens ?? 0,
      w5m: t.usage.cache_creation?.ephemeral_5m_input_tokens ?? 0,
      w1h: t.usage.cache_creation?.ephemeral_1h_input_tokens ?? 0,
      input: t.usage.input_tokens ?? 0,
      output: t.usage.output_tokens ?? 0,
      speed: t.usage.speed ?? null,
      ts: t.timestamp ? Date.parse(t.timestamp) : null,
      model: t.model,
      messageId: t.messageId,
      requestId: t.requestId,
    });
  }
  const turns = dedupeUsageTurns(raw);
  for (const t of turns) t.compactSeen = compactedKeys.has(`${t.messageId}|${t.requestId}`);
  if (turns.length === 0) return;
  if (sinceMs != null && turns[turns.length - 1].ts != null && turns[turns.length - 1].ts < sinceMs) return;

  report.transcripts[kind] += 1;
  if (kind === 'subagent') report.delegation.spawns += 1;

  let prev = null;
  for (let i = 0; i < turns.length; i++) {
    const cur = turns[i];
    report.turns[kind] += 1;
    report.tokens.read += cur.read;
    report.tokens.write += cur.write;
    report.tokens.write5m += cur.w5m;
    report.tokens.write1h += cur.w1h;
    report.tokens.input += cur.input;
    report.tokens.output += cur.output;
    report.tierByKind[kind].w5m += cur.w5m;
    report.tierByKind[kind].w1h += cur.w1h;

    if (kind === 'subagent') { report.delegation.subRead += cur.read; report.delegation.subOut += cur.output; }
    else { report.delegation.mainRead += cur.read; report.delegation.mainOut += cur.output; }

    const cause = classifyWrite(prev, cur);
    report.writeCause[cause].n += 1;
    report.writeCause[cause].tokens += cur.write;
    if (cur.compactSeen) {
      report.compaction.boundaries += 1;
      if (cur.write > 0) { report.compaction.writeTurns += 1; report.compaction.tokens += cur.write; }
    }
    if (i === 0 && kind === 'subagent') report.delegation.coldWrite += cur.write;

    const gapSec = (cur.ts != null && prev?.ts != null) ? (cur.ts - prev.ts) / 1000 : null;
    if (cause === 'invalidation') {
      const why = invalidationCause({ prev, cur, gapSec, kind, compactSeen: cur.compactSeen });
      const slot = (report.invalidationCause[why] ||= { n: 0, tokens: 0 });
      slot.n += 1; slot.tokens += cur.write;
      if (cur.write >= LARGE_INVALIDATION_WRITE) {
        report.largeInvalidations.n += 1;
        report.largeInvalidations.tokens += cur.write;
      }
      if (why === 'ttl-expiry' && kind === 'subagent') {
        report.delegation.ttlEvents += 1;
        report.delegation.ttlRewrite += cur.write;
      }
    }

    // pricing coverage — the point is to make an unpriceable model LOUD
    const p = findPricing(cur.model, cur.ts, cur.speed);
    if (p) {
      const bl = report.pricing.byLine;
      bl.cacheRead += cur.read * p.cache_read / PER_M;
      bl.cacheWrite += (cur.w5m * p.cache_write_5m + cur.w1h * p.cache_write_1h) / PER_M;
      bl.input += cur.input * p.input / PER_M;
      bl.output += cur.output * p.output / PER_M;
      report.pricing.pricedCost += (cur.read * p.cache_read + cur.w5m * p.cache_write_5m
        + cur.w1h * p.cache_write_1h + cur.input * p.input + cur.output * p.output) / PER_M;
    }
    if (!isKnownModel(cur.model)) {
      const m = cur.model || '(missing-model)';
      if (isNonBillableModel(m)) {
        const slot = (report.pricing.nonBillable[m] ||= { turns: 0, read: 0 });
        slot.turns += 1;
        slot.read += cur.read;
      } else {
        report.pricing.unpricedModels[m] = (report.pricing.unpricedModels[m] || 0) + cur.read;
        report.pricing.unpricedRead += cur.read;
        report.pricing.unpricedTurns += 1;
      }
    }
    prev = cur;
  }
}

// --- CLI ---------------------------------------------------------------------

function fmtM(n) { return (n / 1e6).toFixed(1) + 'M'; }
function pct(a, b) { return b ? (100 * a / b).toFixed(1) + '%' : 'n/a'; }

/**
 * `--harness codex-cli` summary. Codex has no billable cache WRITE at all —
 * OpenAI's `cached_input_tokens` is a read discount folded into `input_tokens`,
 * never a separate charge — so the Claude-shaped invalidation/TTL taxonomy in
 * `analyseTranscript` above has no Codex analogue to migrate onto; this is a
 * deliberately smaller ledger-only summary (886 §12 PR 1 first-consumer scope),
 * not a parity rewrite of the Claude classification pipeline.
 */
/**
 * A session's self-check (NIT 7, 886 §12 PR 1 independent-review fix-up):
 * `deltaInputSum` (sum of per-call fresh-delta input) vs `maxCumulativeInput`
 * (the largest cumulative `total_token_usage.input_tokens` this adapter saw)
 * legitimately DIVERGE for a resumed thread — the derisk pass (886 §11 A2)
 * found the corpus-wide gap runs 5.04B vs 6.51B for exactly that reason.
 * This is a REPORTED ratio, not an assertion: a session over the 1% band is
 * not a bug, it is "this session's history includes a resume".
 */
export const SELF_CHECK_DIVERGENCE_THRESHOLD = 0.01;

export function countSelfCheckDivergent(sessions) {
  let n = 0;
  for (const s of sessions) {
    const { deltaInputSum, maxCumulativeInput } = s.selfCheck || {};
    const denom = Math.max(1, maxCumulativeInput || 0);
    const diffPct = Math.abs((deltaInputSum || 0) - (maxCumulativeInput || 0)) / denom;
    if (diffPct > SELF_CHECK_DIVERGENCE_THRESHOLD) n += 1;
  }
  return n;
}

function runCodex({ asJson, sinceMs }) {
  const { calls, sessions } = listCalls({ harnesses: ['codex-cli'], sinceMs });

  let read = 0;
  let output = 0;
  let input = 0;

  for (const c of calls) {
    read += c.tokens.cacheRead || 0;
    output += c.tokens.output || 0;
    input += c.tokens.fresh || 0;
  }

  const divergent = countSelfCheckDivergent(sessions);

  if (asJson) {
    console.log(JSON.stringify({
      harness: 'codex-cli',
      sessions: sessions.length,
      calls: calls.length,
      tokens: { read, write: null, output, input },
      selfCheck: { divergentSessions: divergent, totalSessions: sessions.length, thresholdPct: SELF_CHECK_DIVERGENCE_THRESHOLD * 100 },
    }, null, 2));
    return;
  }

  console.log(`cache-efficiency [codex-cli] — ${sessions.length} sessions, ${calls.length} calls`);
  console.log(`\ntokens: read=${fmtM(read)} write=n/a (provider has no billable cache write) `
    + `output=${fmtM(output)} input=${fmtM(input)}`);
  console.log(`\nself-check: ${divergent}/${sessions.length} sessions where `
    + `|deltaInputSum - maxCumulativeInput| > ${SELF_CHECK_DIVERGENCE_THRESHOLD * 100}%`);
}

const VALID_HARNESSES = ['claude-code', 'codex-cli'];

function main() {
  const argv = process.argv.slice(2);
  const asJson = argv.includes('--json');
  const sinceArg = argv[argv.indexOf('--since') + 1];
  const sinceMs = argv.includes('--since') && sinceArg ? Date.parse(sinceArg) : null;
  const harnessIdx = argv.indexOf('--harness');
  const harness = (harnessIdx !== -1 && argv[harnessIdx + 1]) ? argv[harnessIdx + 1] : 'claude-code';

  if (!VALID_HARNESSES.includes(harness)) {
    console.error(`cache-efficiency: unknown --harness "${harness}" (expected one of: ${VALID_HARNESSES.join(', ')})`);
    console.error('Usage: node scripts/agent-analytics/cache-efficiency.mjs [--since <ISO>] [--json] [--harness claude-code|codex-cli]');
    process.exit(2);
  }

  if (harness === 'codex-cli') {
    runCodex({ asJson, sinceMs });
    return;
  }

  const files = listClaudeTranscriptFiles().map((f) => f.path);
  const report = emptyReport();
  for (const f of files) analyseTranscript(f, report, { sinceMs });
  report.harness = harness;

  if (asJson) { console.log(JSON.stringify(report, null, 2)); return; }

  const t = report.tokens;
  console.log(`cache-efficiency [${harness}] — ${report.transcripts.main} main + ${report.transcripts.subagent} subagent transcripts, `
    + `${report.turns.main + report.turns.subagent} usage turns`);
  console.log(`\ntokens: read=${fmtM(t.read)} write=${fmtM(t.write)} output=${fmtM(t.output)} input=${fmtM(t.input)}`);
  console.log(`  read:write = ${(t.read / Math.max(1, t.write)).toFixed(1)} : 1`
    + `   (high is GOOD — but see the cost split below before reading it as health)`);

  console.log(`\nwhy cache-write was paid (the split a single total cannot show):`);
  const wc = report.writeCause;
  const totalW = wc.cold.tokens + wc.extension.tokens + wc.invalidation.tokens;
  for (const k of ['extension', 'invalidation', 'cold']) {
    console.log(`  ${k.padEnd(13)} turns=${String(wc[k].n).padStart(6)}  ${fmtM(wc[k].tokens).padStart(8)}  ${pct(wc[k].tokens, totalW).padStart(6)}`);
  }

  console.log(`\ninvalidations by cause:`);
  const ic = Object.entries(report.invalidationCause).sort((a, b) => b[1].tokens - a[1].tokens);
  const totalI = ic.reduce((a, [, v]) => a + v.tokens, 0);
  for (const [k, v] of ic) {
    console.log(`  ${k.padEnd(22)} n=${String(v.n).padStart(5)}  ${fmtM(v.tokens).padStart(8)}  ${pct(v.tokens, totalI).padStart(6)}`);
  }
  console.log(`  large (>=${LARGE_INVALIDATION_WRITE / 1000}k write): ${report.largeInvalidations.n} events, ${fmtM(report.largeInvalidations.tokens)}`);
  console.log(`  compaction (counted separately — it usually lands in 'cold', not here):`
    + ` ${report.compaction.boundaries} boundaries, ${fmtM(report.compaction.tokens)} rewritten`);

  console.log(`\nTTL tier by kind (harness-set, not configurable here):`);
  for (const k of ['main', 'subagent']) {
    const v = report.tierByKind[k];
    console.log(`  ${k.padEnd(9)} 5m=${fmtM(v.w5m).padStart(8)}  1h=${fmtM(v.w1h).padStart(8)}`);
  }

  const d = report.delegation;
  console.log(`\ndelegation economics:`);
  console.log(`  spawns=${d.spawns}  cold prefix per spawn=${d.spawns ? Math.round(d.coldWrite / d.spawns / 1000) : 0}k  (${fmtM(d.coldWrite)} total)`);
  console.log(`  subagent 5m-TTL expiry rewrites: ${d.ttlEvents} events, ${fmtM(d.ttlRewrite)}`);
  console.log(`  read per output-token:  subagent ${d.subOut ? Math.round(d.subRead / d.subOut) : 0} : 1`
    + `   main ${d.mainOut ? Math.round(d.mainRead / d.mainOut) : 0} : 1`);

  console.log(`\npricing coverage:`);
  console.log(`  priceable spend: $${report.pricing.pricedCost.toFixed(0)}`);
  const bl = report.pricing.byLine;
  const blTot = bl.cacheRead + bl.cacheWrite + bl.input + bl.output;
  for (const [label, v] of [['cache_read', bl.cacheRead], ['cache_write', bl.cacheWrite],
    ['output', bl.output], ['input', bl.input]]) {
    console.log(`    ${label.padEnd(12)} $${v.toFixed(0).padStart(6)}  ${pct(v, blTot).padStart(6)}`);
  }
  console.log(`    => ${pct(bl.cacheRead + bl.cacheWrite, blTot)} of priceable spend is re-presenting context, not generating`);
  const um = Object.entries(report.pricing.unpricedModels).sort((a, b) => b[1] - a[1]);
  if (um.length === 0) {
    console.log('  every model resolved to a pricing row.');
  } else {
    console.log(`  !! ${report.pricing.unpricedTurns} turns / ${fmtM(report.pricing.unpricedRead)} cache-read tokens `
      + `(${pct(report.pricing.unpricedRead, t.read)} of all cache-read) are priced at $0 —`);
    console.log(`     the figure above EXCLUDES them. Add the missing row(s) to PRICING in lib/transcript-cost.mjs:`);
    for (const [m, read] of um) console.log(`       ${m.padEnd(28)} ${fmtM(read)} cache-read`);
  }
  // Known-non-billable placeholders (908 §4.5) get a quiet one-liner, never
  // the "!!" alarm above -- that alarm is reserved for a genuinely unknown model.
  const nb = Object.entries(report.pricing.nonBillable || {});
  for (const [m, s] of nb) {
    console.log(`  (known non-billable: ${m} — ${s.turns} turns, ${fmtM(s.read)} cache-read, priced at $0 by design, not a gap)`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
