/**
 * Shared transcript-cost parsing + pricing table (extracted from cost-session.mjs,
 * tempdoc 743 Phase 1). cost-session.mjs and baseline-economics.mjs both need the
 * same per-turn token/cost extraction; this module is the single source so pricing
 * updates land once.
 *
 * Tempdoc 745 item B — four verified defects fixed here:
 *   1. dedup scope is now the CALLER's choice (optional `seen` map), not per-file;
 *   2. a repeated (message.id, requestId) keeps the LAST usage snapshot, not the
 *      first — subagent transcripts persist STREAMING PARTIALS that grow
 *      (`output_tokens: 5, 5, 5, 5, 5, 291` reproduced on a real transcript), so
 *      first-wins undercounted corpus output by ~30%;
 *   3. cache writes are priced per ephemeral tier — transcripts DO distinguish
 *      them via `usage.cache_creation.{ephemeral_5m,ephemeral_1h}_input_tokens`,
 *      and 1h writes cost 2.0x input, not the 5m 1.25x;
 *   4. pricing is date-conditional (Sonnet-5's intro window) and fails CLOSED on
 *      an unrecognized model instead of silently pricing it as Sonnet.
 */

import fs from 'node:fs';

// Pricing per 1M tokens, verified against platform.claude.com/docs/en/about-claude/pricing
// (2026-07-16). Cache-write multipliers off input: 5-minute tier 1.25x, 1-hour tier 2.0x;
// cache read 0.1x. Values are written out literally rather than derived so the table can be
// diffed straight against the published one.
// Keys are matched exact-first, then by longest prefix (findPricing), so a suffixed id like
// `claude-opus-4-8[1m]` resolves via the bare `claude-opus-4-8` entry.
// `provider` (886 §12 PR 5a): every row below is Anthropic — this machine's
// other harness (Codex CLI) runs on a ChatGPT/Codex subscription, not
// API-metered tokens, so there is no real per-token rate to record for it;
// pricing it at OpenAI's API list price would be a MODELLED number standing
// in for an unmeasured one, not a verified fact like every other row in this
// table. `providerOf` (below) is deliberately Anthropic-only for that reason
// — it is not a placeholder waiting for OpenAI rows, it is the honest state
// of what this repo can price today.
const OPUS_CURRENT = { input: 5.0, output: 25.0, cache_write_5m: 6.25, cache_write_1h: 10.0, cache_read: 0.50, provider: 'anthropic' };
const OPUS_LEGACY = { input: 15.0, output: 75.0, cache_write_5m: 18.75, cache_write_1h: 30.0, cache_read: 1.50, provider: 'anthropic' };
const SONNET_STANDARD = { input: 3.0, output: 15.0, cache_write_5m: 3.75, cache_write_1h: 6.0, cache_read: 0.30, provider: 'anthropic' };
const HAIKU_4_5 = { input: 1.0, output: 5.0, cache_write_5m: 1.25, cache_write_1h: 2.0, cache_read: 0.10, provider: 'anthropic' };

/**
 * Sonnet-5 is a FLAT $2/$10 — no dated cliff (tempdoc 841, re-verified against
 * platform.claude.com/docs/en/about-claude/pricing 2026-08-18).
 *
 * This was previously a two-step `schedule` because $2/$10 was announced as
 * introductory pricing through 2026-08-31, rising to $3/$15 on 2026-09-01. That
 * increase was CANCELLED — the pricing page now states the introductory rate
 * "is now the standard price" and that the scheduled increase "will not occur".
 *
 * The schedule is deleted rather than left with a far-future cliff date: a dated
 * rule that nobody re-checks is a time bomb, and this one was ~2 weeks from
 * silently overpricing every Sonnet-5 turn by 50% with no symptom (the total
 * would simply have been larger, and still plausible). `SONNET_5_INTRO_ENDS_MS`
 * and `SONNET_5_INTRO` are gone with it — they had no consumer outside this file.
 */
const SONNET_5 = { input: 2.0, output: 10.0, cache_write_5m: 2.5, cache_write_1h: 4.0, cache_read: 0.20, provider: 'anthropic' };

/**
 * Fast mode bills Opus at a premium and is recorded per-turn as
 * `message.usage.speed` — "standard" | "fast" (null on transcripts predating the
 * field). Verified corpus-wide 2026-07-16: 59,332 turns, ALL "standard", zero
 * "fast" — so this table currently prices nothing and is forward-looking only.
 * It exists because the alternative is silent: without it, one fast-mode toggle
 * would understate Opus by 2x with no symptom, and the cheap-to-add case is
 * exactly the one that goes unnoticed for a month.
 *
 * Rates re-verified at platform.claude.com/docs/en/about-claude/pricing
 * (2026-08-18, tempdoc 841). Fast mode is **Opus 5 and Opus 4.8 only**, both at
 * $10/$50. Two corrections landed with that check:
 *   - `claude-opus-4-7` had a $30/$150 row here. Fast mode is NOT available on
 *     Opus 4.7 at all — such requests return an error — so the row described a
 *     state that cannot occur, at a rate 3x the real premium. Removed; a "fast"
 *     4.7 turn now falls back to standard AND is surfaced by
 *     isFastPricedCorrectly(), which is the honest handling.
 *   - `claude-opus-5` was missing despite supporting fast mode.
 * Cache multipliers stack ON TOP of fast pricing, per that page: 5m write =
 * 1.25x input, 1h write = 2.0x input, cache read = 0.1x input.
 */
const OPUS_FAST = { input: 10.0, output: 50.0, cache_write_5m: 12.5, cache_write_1h: 20.0, cache_read: 1.00, provider: 'anthropic' };

export const FAST_PRICING = {
  'claude-opus-5': OPUS_FAST,
  'claude-opus-4-8': OPUS_FAST,
};

export const PRICING = {
  'claude-fable-5':             { input: 10.0, output: 50.0, cache_write_5m: 12.5, cache_write_1h: 20.0, cache_read: 1.00, provider: 'anthropic' },
  // Opus 5 was ABSENT until tempdoc 841. findPricing fails closed, so it did not
  // mis-price anything — it priced 36,514 turns and 7.93G cache-read tokens
  // (51.9% of all cache-read in the local corpus) at exactly $0, and every
  // baseline-economics / cost-session total silently excluded the busiest model.
  // Rates are identical to Opus 4.8, verified 2026-08-18.
  'claude-opus-5':              OPUS_CURRENT,
  'claude-opus-4-8':            OPUS_CURRENT,
  'claude-opus-4-7':            OPUS_CURRENT,
  'claude-opus-4-6':            OPUS_CURRENT,
  'claude-opus-4-5':            OPUS_CURRENT,
  'claude-opus-4-1':            OPUS_LEGACY,
  'claude-opus-4-20250514':     OPUS_LEGACY,
  'claude-sonnet-5':            SONNET_5,
  'claude-sonnet-4-6':          SONNET_STANDARD,
  'claude-sonnet-4-5-20250929': SONNET_STANDARD,
  'claude-haiku-4-5':           HAIKU_4_5,
  'claude-haiku-4-5-20251001':  HAIKU_4_5,
};
export const PER_M = 1_000_000;
// Sentinel by_model key for a usage-bearing turn with no `message.model` tag.
// Always resolves isKnownModel(...) === false, so it surfaces via unknown_models.
export const MISSING_MODEL_KEY = '(missing-model)';

const PRICING_KEYS_LONGEST_FIRST = Object.keys(PRICING).sort((a, b) => b.length - a.length);

export function round(n, decimals = 4) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}

/**
 * Look a model id up in a pricing map: exact match, else longest-prefix match, so
 * a specific dated id is not shadowed by a shorter bare-family key when the two
 * ever diverge in price. Shared by the standard and fast tables so both resolve
 * model ids by identical rules — a second copy of this matching would be a place
 * for them to silently disagree.
 */
function findEntryIn(table, model) {
  if (!model) return null;
  if (table[model]) return table[model];
  const key = Object.keys(table).sort((a, b) => b.length - a.length).find((k) => model.startsWith(k));
  return key ? table[key] : null;
}

function findEntry(model) {
  if (!model) return null;
  if (PRICING[model]) return PRICING[model];
  const key = PRICING_KEYS_LONGEST_FIRST.find((k) => model.startsWith(k));
  return key ? PRICING[key] : null;
}

/**
 * Resolve a per-1M-token pricing row for a model id at a point in time.
 * Returns **null** for an unrecognized model — pricing FAILS CLOSED (tempdoc 745
 * item B, bug 4): the previous DEFAULT_PRICING fallback priced any unknown model
 * at Sonnet rates, producing a plausible-looking but silently wrong dollar figure.
 * Callers must treat null as "cannot price" ($0 + surfaced via isKnownModel).
 *
 * `timestampMs` selects the dated row for models with a price `schedule`;
 * null/omitted resolves to the undated row. **No model currently uses a
 * schedule** — Sonnet-5's was the only one and its cliff was cancelled
 * (tempdoc 841). The mechanism is kept because dated price changes recur and
 * re-deriving it costs more than carrying it; it is called out as unused here
 * so nobody reads the code and infers a live dated rule exists.
 *
 * `speed` is the turn's `message.usage.speed` ("standard" | "fast" | null). Only
 * "fast" changes anything, and only for the Opus models that offer it; anything
 * else — including an unknown speed string — resolves to standard pricing, which
 * is the honest default given fast mode is opt-in per turn.
 */
export function findPricing(model, timestampMs = null, speed = null) {
  if (speed === 'fast') {
    const fast = findEntryIn(FAST_PRICING, model);
    // A "fast" turn on a model with no fast row (e.g. a future model, or Opus 4.6
    // where fast was withdrawn) falls through to standard rather than guessing a
    // premium — but it is NOT silent: isFastPricedCorrectly() lets callers surface it.
    if (fast) return fast;
  }
  const entry = findEntry(model);
  if (!entry) return null;
  if (!entry.schedule) return entry;
  for (const step of entry.schedule) {
    if (step.before == null) return step.pricing;
    if (timestampMs != null && timestampMs < step.before) return step.pricing;
  }
  return null;
}

/**
 * True when a turn's (model, speed) pair is priced by a row that actually matches
 * it. False only for a "fast" turn on a model with no fast row — i.e. the one case
 * where findPricing knowingly falls back to standard and would understate. Lets a
 * caller bucket the tokens loudly instead of shipping a plausible wrong number,
 * the same contract isKnownModel provides for unknown models.
 */
export function isFastPricedCorrectly(model, speed) {
  if (speed !== 'fast') return true;
  return Boolean(findEntryIn(FAST_PRICING, model));
}

/**
 * Whether `model` resolves to a real pricing-table row (exact or prefix match).
 * A model id absent from the table is priced at $0 by the parser and its tokens
 * are surfaced as "unknown" so a report doesn't hide a mispriced model behind a
 * plausible-looking dollar figure (tempdoc 743 Phase 1 / 745 item B).
 */
export function isKnownModel(model) {
  if (!model) return true; // absent model info is a different failure mode, not an unknown-model one
  return findEntry(model) != null;
}

/**
 * Known-non-billable model placeholders (tempdoc 908 §4.5). These are NOT
 * genuinely unknown models — they are Claude Code harness internals that
 * legitimately carry zero billable cost — so a caller must not raise the same
 * loud "!!" alarm for them that a real missing pricing row deserves. The
 * distinction matters because that alarm is load-bearing (it caught
 * `claude-opus-5` hiding a third of all spend, README "Pricing coverage");
 * firing it every run for a benign placeholder is how a maintainer learns to
 * skim past the line that matters.
 */
export const NON_BILLABLE_MODELS = new Set([
  // Claude Code's own internal placeholder on compaction-summary / orphan-
  // boundary turns. Carries a real usage snapshot (turns, tokens) but bills
  // nothing -- confirmed by corpus inspection (908 §4.5: 69 turns, 0.0M
  // tokens, 0.0% of cache-read), not merely absent from PRICING.
  '<synthetic>',
]);

/** True when `model` is a KNOWN non-billable placeholder, never a genuinely unknown one. */
export function isNonBillableModel(model) {
  return NON_BILLABLE_MODELS.has(model);
}

/**
 * Resolve `model` to its billing provider ('anthropic' for every row in
 * `PRICING` today — see the module-level comment above `OPUS_CURRENT`).
 * FAILS CLOSED like `findPricing`/`isKnownModel`: an unrecognized or absent
 * model resolves to `null`, never a guessed provider. This is deliberately
 * NOT "does this look like an Anthropic model id" string-sniffing — it reads
 * the same pricing-table match `findPricing` already uses (exact, else
 * longest-prefix), so a model this module can price and a model this module
 * can attribute to a provider are always the same set.
 */
export function providerOf(model) {
  const entry = findEntry(model);
  return entry ? entry.provider ?? null : null;
}

export function emptyModelBucket() {
  return { input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0, turns: 0, cost_usd: 0 };
}

export function emptyTotals() {
  return {
    input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0,
    cost_usd: 0, turns: 0,
  };
}

export function addTotals(acc, r) {
  acc.input_tokens += r.input_tokens;
  acc.output_tokens += r.output_tokens;
  acc.cache_write_tokens += r.cache_write_tokens;
  acc.cache_read_tokens += r.cache_read_tokens;
  acc.cost_usd += r.cost_usd;
  acc.turns += r.turns;
}

export function mergeByModel(target, source) {
  for (const [model, bucket] of Object.entries(source || {})) {
    if (!target[model]) target[model] = emptyModelBucket();
    const t = target[model];
    t.input_tokens += bucket.input_tokens;
    t.output_tokens += bucket.output_tokens;
    t.cache_write_tokens += bucket.cache_write_tokens;
    t.cache_read_tokens += bucket.cache_read_tokens;
    t.turns += bucket.turns;
    t.cost_usd += bucket.cost_usd;
  }
  return target;
}

/**
 * Split a turn's cache-creation tokens into the 5-minute and 1-hour ephemeral
 * tiers. `usage.cache_creation` is an object carrying both counts (verified on
 * real transcripts, tempdoc 745 item B bug 3 — where ~100% of writes were 1h).
 *
 * The tiered object is the SOURCE OF TRUTH; the flat `cache_creation_input_tokens`
 * is only a fallback for a transcript carrying no tiered form. Do not assume the
 * flat field equals the tiers' sum — measured on the 125-session corpus, **1,313
 * snapshots carry tiered writes with flat == 0**, hiding **16,992,717 cache-write
 * tokens (2.34%)** from any flat-only reader (sonnet-5 9.9M, opus-4-8 7.1M). That
 * is what the pre-745 parser did, so bug 3's fix recovers those tokens outright,
 * not merely their tier. Anything deciding "is this snapshot real?" must read
 * through THIS function, never the flat field (see usageIsAllZero).
 *
 * An un-tiered write is charged at the cheaper 5m rate rather than guessed upward.
 */
function splitCacheWrite(usage) {
  const cc = usage.cache_creation;
  if (cc && typeof cc === 'object') {
    const w5 = cc.ephemeral_5m_input_tokens ?? 0;
    const w1 = cc.ephemeral_1h_input_tokens ?? 0;
    if (w5 || w1) return { w5, w1 };
  }
  return { w5: usage.cache_creation_input_tokens ?? 0, w1: 0 };
}

/**
 * True when a snapshot reports no usage at all — a re-carried placeholder rather
 * than a measurement (see the displacement rule in parseTranscriptTokens).
 *
 * It reads cache writes through `splitCacheWrite`, deliberately: reading the flat
 * `cache_creation_input_tokens` directly would call a tiered-only snapshot
 * "all-zero" and let a true placeholder displace it. The two functions must agree
 * on where cache writes live, or the guard protects a different field than the
 * pricing does.
 */
function usageIsAllZero(usage) {
  const { w5, w1 } = splitCacheWrite(usage);
  return !(usage.input_tokens || 0) && !(usage.output_tokens || 0) &&
    !(usage.cache_read_input_tokens || 0) && !w5 && !w1;
}

/**
 * Dedup key for a usage-bearing assistant line. Claude Code persists a single
 * turn as N JSONL lines (one per content block) and re-carries prior turns
 * verbatim into a RESUMED session's file, so the same turn appears many times
 * both within and across transcripts. `(message.id, requestId)` — requestId
 * lives at ENTRY level, not inside `message` — is the true per-turn identity
 * (matches ccusage). A line with no message.id has no usable identity and is
 * counted individually rather than risking a silent drop.
 */
function dedupKey(entry) {
  const msgId = entry.message?.id;
  if (!msgId) return null;
  return `${msgId}|${entry.requestId ?? ''}`;
}

/**
 * Parse a JSONL transcript and extract token usage from assistant messages.
 * Computes cost per-turn using each turn's actual model and timestamp, so
 * mixed-model transcripts (e.g. Opus main + Haiku subagents) are priced
 * accurately.
 *
 * `by_model` breaks the same totals down per model id seen in the transcript,
 * so callers can report model mix and flag unknown models (via isKnownModel)
 * without re-parsing.
 *
 * `seen` (optional Map, key -> true) makes the dedup SCOPE the caller's choice
 * (tempdoc 745 item B, bug 1): keys already present are skipped, and every key
 * this call counted is added on the way out. Pass a per-session map to dedup a
 * main transcript against its subagents; pass a corpus-scoped map, feeding
 * sessions oldest-first, to stop a resumed session's re-carried history from
 * being counted a second time under its new session id. Omit it for per-file
 * scope.
 */
export function parseTranscriptTokens(transcriptPath, { seen } = {}) {
  const result = {
    input_tokens: 0,
    output_tokens: 0,
    cache_write_tokens: 0,
    cache_read_tokens: 0,
    cost_usd: 0,
    turns: 0,
    model: null,
    error: null,
    by_model: {},
  };

  try {
    if (!transcriptPath || !fs.existsSync(transcriptPath)) {
      result.error = 'file_not_found';
      return result;
    }

    // A repeated key's snapshots are STREAMING PARTIALS THAT GROW, not identical
    // copies (verified on a real subagent transcript: output_tokens 5,5,5,5,5,291),
    // so the LAST snapshot is the turn's true usage. That forces buffering: slots
    // are aggregated after the read, not accumulated inline. The last snapshot is
    // taken WHOLESALE rather than a per-field max, which would fabricate a snapshot
    // that never existed.
    //
    // EXCEPT: last-wins is not unconditional, because the growth premise has a
    // documented counterexample. Transcripts also re-carry a turn with an ALL-ZERO
    // usage snapshot after its real one:
    //     in=2 out=760 cr=804035 cw=290   (x3)
    //     in=0 out=0   cr=0      cw=0     (x3)   <- naive last-wins takes this
    // So an all-zero snapshot NEVER displaces a non-zero one. Zero is a placeholder
    // for "no usage reported on this line", not a measurement of zero.
    //
    // This is an artifact of OUR design, not of the transcripts: the zero copy is
    // only reachable because we dedup GLOBALLY (across files/sessions), so a turn's
    // real usage in one file can be displaced by its zero re-carry in another.
    // Measured over 227 in-scope sessions: 1,455 keys, recovering 1.288G cache_read
    // + 16.5M cache_write + 1.62M output — and a differential reconciliation showed
    // the guard's effect is explained by those keys with residual EXACTLY 0.
    //
    // NOTE FOR THE NEXT AGENT: this rule moves us TOWARD ccusage, not away
    // (cache_read −4.78% -> −0.43% against it). ccusage does NOT have this bug — it
    // sidesteps it by not deduping globally, which costs it its own over-count.
    // Do not delete this rule to chase the residual (tempdoc 745 F-11).
    const slots = [];
    const slotByKey = new Map();

    const content = fs.readFileSync(transcriptPath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let entry;
      try { entry = JSON.parse(line); } catch { continue; }

      if (entry.type !== 'assistant') continue;

      const usage = entry.message?.usage;
      if (!usage) continue;

      const tsMs = entry.timestamp ? Date.parse(entry.timestamp) : NaN;
      const slot = {
        usage,
        model: entry.message?.model ?? null,
        tsMs: Number.isNaN(tsMs) ? null : tsMs,
      };

      const key = dedupKey(entry);
      if (!key) {
        slots.push(slot);
        continue;
      }
      if (seen?.has(key)) continue; // already counted by an earlier file/session in this scope

      const existing = slotByKey.get(key);
      if (existing) {
        // Last snapshot wins, in the turn's original position — unless it is an
        // all-zero placeholder displacing a real measurement (see above).
        if (!(usageIsAllZero(slot.usage) && !usageIsAllZero(existing.usage))) {
          Object.assign(existing, slot);
        }
      } else {
        slotByKey.set(key, slot);
        slots.push(slot);
      }
    }

    // Claim a key in the cross-file scope ONLY if what we recorded for it is real.
    // A key whose only snapshot here is an all-zero placeholder must stay UNCLAIMED,
    // or it suppresses the real turn in a later file — the same displacement the
    // in-loop guard prevents within a file, one scope up. Marking unconditionally
    // made the result depend on file-visit order: a zero-only copy in file B
    // silently deleted the real turn in file C (804,035 cache_read on the repro).
    for (const [key, slot] of slotByKey.entries()) {
      if (!usageIsAllZero(slot.usage)) seen?.set(key, true);
    }

    for (const slot of slots) accumulate(result, slot);
  } catch (err) {
    result.error = err.message;
  }

  return result;
}

/** Fold one deduped turn's final usage snapshot into the running result. */
function accumulate(result, { usage, model, tsMs }) {
  result.turns++;
  const inp = usage.input_tokens ?? 0;
  const out = usage.output_tokens ?? 0;
  const { w5, w1 } = splitCacheWrite(usage);
  const cr = usage.cache_read_input_tokens ?? 0;

  result.input_tokens += inp;
  result.output_tokens += out;
  result.cache_write_tokens += w5 + w1;
  result.cache_read_tokens += cr;

  // Per-turn cost using this turn's actual model and date. A turn whose model is
  // absent OR unrecognized is NOT priced at a default (that would silently mask a
  // mispriced turn behind a plausible-looking dollar figure) — its tokens are
  // routed into their model's bucket at $0 and surfaced via unknown_models by the
  // caller (isKnownModel is false for both cases).
  const pricing = findPricing(model, tsMs, usage.speed ?? null);
  let turnCost = 0;
  if (pricing) {
    turnCost = (inp / PER_M) * pricing.input
      + (out / PER_M) * pricing.output
      + (w5 / PER_M) * pricing.cache_write_5m
      + (w1 / PER_M) * pricing.cache_write_1h
      + (cr / PER_M) * pricing.cache_read;
    result.cost_usd += turnCost;
    result.model = model; // track most recent priced model
  }

  const modelKey = model || MISSING_MODEL_KEY;
  if (!result.by_model[modelKey]) result.by_model[modelKey] = emptyModelBucket();
  const bucket = result.by_model[modelKey];
  bucket.input_tokens += inp;
  bucket.output_tokens += out;
  bucket.cache_write_tokens += w5 + w1;
  bucket.cache_read_tokens += cr;
  bucket.turns += 1;
  bucket.cost_usd += turnCost;
}

/**
 * Parse one session: its main transcript plus every subagent transcript, sharing
 * ONE dedup scope across them. This is the single combine (tempdoc 745 item B,
 * D7) — cost-session.mjs's costSession() and baseline-economics.mjs's
 * computeSessionCost() previously each carried their own copy of this loop, so a
 * fix to one left a knowingly-wrong twin.
 *
 * `seen` widens the scope beyond this session (see parseTranscriptTokens); when
 * omitted, the session is its own scope. `main` is always a result object — a
 * missing/absent mainPath yields `error: 'file_not_found'`, as before.
 */
export function parseSessionTokens({ mainPath, subagentPaths = [], seen } = {}) {
  const scope = seen ?? new Map();
  const main = parseTranscriptTokens(mainPath, { seen: scope });

  const subagents = { found: 0, missing: 0, totals: emptyTotals(), by_model: {} };
  for (const p of subagentPaths || []) {
    const r = parseTranscriptTokens(p, { seen: scope });
    if (r.error) {
      subagents.missing += 1;
      continue;
    }
    subagents.found += 1;
    addTotals(subagents.totals, r);
    mergeByModel(subagents.by_model, r.by_model);
  }

  return { main, subagents };
}
