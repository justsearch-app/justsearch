#!/usr/bin/env node
/**
 * steering-arbitration gate — tempdoc 565 §30 (the DIRECTION authority's anti-drift teeth).
 *
 * The human's direction over an agent run is ONE control-intent channel: `initiate` (the composer),
 * `set-posture` (the autonomy dial), `interject` (the live-run steer input), `halt` (the stop). Every
 * run-control affordance dispatches through the ONE `dispatchRunControl` seam (declared in
 * `governance/steering-surfaces.v1.json`), so a hand-rolled stop/steer that bypasses the channel cannot
 * drift (the same "compose the one seam, by construction" shape as check-transient/modal-arbitration).
 *
 * Two checks:
 *  1. POSITIVE coverage — every `adopters` entry composes the `dispatchRunControl` symbol.
 *  2. FORBIDDEN reintroduction — each PER-RUN directive's controller call (`.steer(` for interject,
 *     `cancelSession(` for halt — `runDirectiveChannels.callPatterns`) has EXACTLY ONE dispatch site
 *     (the seam, `runDirectiveChannels.allowed`). Any other FE file calling one directly is a second
 *     steer / a hand-rolled stop bypassing the authority → fail. (Tests + the controller's own method
 *     definitions `async steer(` / `async cancelSession(` do not match `.steer(` / `cancelSession(` only
 *     when prefixed by a receiver; the controller defs are excluded via its own file not being scanned
 *     for its definitions — see the allow/skip handling below.) set-posture is intentionally NOT gated
 *     here: posture is the global 561 P-D autonomy store, a peer channel, not a per-run seam directive.
 *
 * A NEW run-control affordance is a discovery step: add the surface to `adopters` and dispatch through
 * the seam. Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md).
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { stripComments as stripCommentsShared } from '../lib/strip-comments.mjs';

const REGISTER = 'governance/steering-surfaces.v1.json';
const reg = JSON.parse(readFileSync(REGISTER, 'utf8'));

const norm = (p) => p.replace(/\\/g, '/');
const SYMBOL = new RegExp(`\\b${reg.primitive.symbol}\\b`);

const stripComments = (s) => stripCommentsShared(s, { withHtml: false });

const failures = [];

// 1. Positive coverage — each adopter composes the seam.
for (const adopter of reg.adopters) {
  const f = norm(adopter);
  const src = stripComments(readFileSync(f, 'utf8'));
  if (!SYMBOL.test(src)) {
    failures.push(
      `${f}: declared a steering adopter but does NOT compose ${reg.primitive.symbol} — route its ` +
        `run-control affordances (initiate/interject/halt) through the ONE control-intent seam (565 §30).`,
    );
  }
}

// 2. Forbidden reintroduction — each per-run directive call (`.steer(` / `.cancelSession(`) only at the
//    allowed dispatch site (the seam). Dotted patterns, so the controller's own `async steer(` /
//    `async cancelSession(` definitions never match.
const SRC_ROOT = 'modules/ui-web/src';
const allowed = new Set(reg.runDirectiveChannels.allowed.map(norm));
const patterns = reg.runDirectiveChannels.callPatterns;
const walk = (dir, acc) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, acc);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) acc.push(norm(p));
  }
  return acc;
};
for (const f of walk(SRC_ROOT, [])) {
  if (allowed.has(f)) continue;
  const src = stripComments(readFileSync(f, 'utf8'));
  for (const pattern of patterns) {
    if (src.includes(pattern)) {
      failures.push(
        `${f}: calls a controller \`${pattern}\` directly — the DIRECTION authority's per-run directives ` +
          `(interject/halt) must flow ONLY through ${reg.primitive.symbol} (${[...allowed].join(', ')}). A ` +
          `second steer / a hand-rolled stop that bypasses the seam is the drift the 565 §30 register ` +
          `forbids; dispatch through the seam instead.`,
      );
    }
  }
}

// 3. Lifecycle-predicate integrity (tempdoc 577 §2.12 Move 1) — every per-run directive channel
//    declares the lifecycle predicate under which it is dispatchable, and the seam implements the
//    ONE predicate function. An affordance that does not consult the state it acts on is the
//    raise-budget-404 / Resume-500 class; this keeps the register and the seam from drifting apart.
if (reg.lifecyclePredicates) {
  const channels = reg.lifecyclePredicates.channels ?? {};
  for (const pattern of patterns) {
    if (!(pattern in channels)) {
      failures.push(
        `${REGISTER}: directive channel \`${pattern}\` has NO lifecyclePredicates entry — every ` +
          `per-run directive must declare when it is dispatchable (577 Move 1).`,
      );
    }
  }
  for (const declared of Object.keys(channels)) {
    if (!patterns.includes(declared)) {
      failures.push(
        `${REGISTER}: lifecyclePredicates declares \`${declared}\` but it is not a registered ` +
          `callPattern — orphan predicate row.`,
      );
    }
  }
  const seamSrc = stripComments(readFileSync(norm(reg.primitive.module), 'utf8'));
  const predSymbol = reg.lifecyclePredicates.predicateSymbol;
  if (!new RegExp(`\\b${predSymbol}\\b`).test(seamSrc)) {
    failures.push(
      `${norm(reg.primitive.module)}: the seam does not implement the declared predicate symbol ` +
        `\`${predSymbol}\` — the register's lifecyclePredicates have no enforcement seam.`,
    );
  }
} else {
  failures.push(
    `${REGISTER}: missing lifecyclePredicates block (577 Move 1 — per-directive lifecycle predicates).`,
  );
}

if (failures.length > 0) {
  console.error('✗ steering-arbitration gate FAILED:\n' + failures.map((x) => '  - ' + x).join('\n'));
  process.exit(1);
}
console.log(
  `✓ steering-arbitration gate OK — ${reg.adopters.length} steering surface(s) compose the ONE ` +
    `${reg.primitive.symbol}; ${reg.runDirectiveChannels.callPatterns.length} directive channel(s) ` +
    `each have a single dispatch site + a declared lifecycle predicate (565 §30 + 577 Move 1).`,
);
