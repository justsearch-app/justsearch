#!/usr/bin/env node
/**
 * style-literal ratchet — tempdoc 574 Phase C.
 *
 * Tempdoc 930 chunk F retired the kernel gate that wrapped this script; the script itself is
 * unchanged and still the enforcement. It moved from the `ui-web-gates` recipe's kernel-gate line
 * to its script line, so `node scripts/ci/run-ui-web-gates.mjs` still runs it on every ui-web PR.
 * What went is the changeset/registry ceremony around it, not the check.
 *
 * The 574 ambient/token authorities exist (`--z-overlay-*`, `--duration-*`/`--ease-*`,
 * `--font-size-*` in tokens.css), but the per-component FORKS that should reference them are
 * migrated incrementally (B3 z-war done; B4 motion and B5 typography in progress). This gate is the
 * COMPLETENESS GUARANTEE the §18 critique demanded: it turns "preventable" into "resolved" by
 * forbidding NEW raw literals of three classes while letting the existing tail shrink to zero —
 * the shrinking-baseline ratchet pattern (cf. `check-message-single-model` / the class-size ratchet).
 *
 * Three literal classes are ratcheted across the `modules/ui-web/src` .ts tree (CSS template literals +
 * inline `style=` strings), each of which has a single-authority token it MUST route through:
 *
 *   (1) z-index   — a raw magic number (the §16 S4 z-war). Allowed: `var(--z...)` tokens, and the
 *                   within-context locals `0` / `1`. Anything else (`9999`, `1000`, …) is a fork.
 *   (2) transition / transition-duration — a raw time (`120ms`, `0.2s`). Allowed: `var(--duration-*)`
 *                   + `none`. (`--ease-*` covers the timing-function half.)
 *   (3) font-size — a raw length (`0.75rem`, `12px`). Allowed: `var(--font-size-*)` + keywords
 *                   (`inherit` / `smaller` / …). The single typographic scale is the authority.
 *
 * Baseline = per-file current counts (`style-literal-ratchet-baseline.v1.json`). The gate FAILS when
 * a file's count for a class EXCEEDS its baseline (a regression) or a file NOT in the baseline carries
 * any (a new file must be born clean — use the tokens). `--rebalance` rewrites the baseline to the
 * current counts; since the gate blocks growth, the baseline only ever shrinks as B4/B5 land.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the pre-merge checks table (docs/reference/contributing/pre-merge-checks.md). Coverage is the full
 * ui-web tree (new files scanned automatically); no enumerated allowlist.
 */
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, extname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { stripComments } from '../lib/strip-comments.mjs';

export const SRC = 'modules/ui-web/src';
export const BASELINE = 'scripts/ci/style-literal-ratchet-baseline.v1.json';
const REBALANCE = process.argv.includes('--rebalance');

/** A CSS declaration value is "tokenized" when it routes through the named custom-property family. */
const usesVar = (value, family) => new RegExp(`var\\(\\s*--${family}`).test(value);

/**
 * Count raw-literal violations of each ratcheted class in one file's (comment-stripped) source.
 * Heuristic but conservative: the declaration names (`z-index:` / `font-size:` / `transition:`) appear
 * only in CSS template literals + inline styles in this codebase, never in ordinary TS.
 */
export function countViolations(src) {
  const counts = { zIndex: 0, transition: 0, fontSize: 0 };

  // (1) z-index — raw magic number (allow var(--z…), 0, 1, auto/inherit/unset).
  for (const m of src.matchAll(/z-index\s*:\s*([^;}\n]+)/g)) {
    const v = m[1].trim();
    if (usesVar(v, 'z')) continue;
    if (/^(0|1|auto|inherit|unset|initial|revert)$/.test(v)) continue;
    if (/\d/.test(v)) counts.zIndex++;
  }

  // (2) transition / transition-duration — a raw time, not var(--duration…).
  for (const m of src.matchAll(/transition(?:-duration)?\s*:\s*([^;}\n]+)/g)) {
    const v = m[1].trim();
    if (usesVar(v, 'duration')) continue;
    if (/^(none|inherit|unset|initial|revert)$/.test(v)) continue;
    if (/\d*\.?\d+\s*m?s\b/.test(v)) counts.transition++;
  }

  // (3) font-size — a raw length, not var(--font-size…).
  for (const m of src.matchAll(/font-size\s*:\s*([^;}\n]+)/g)) {
    const v = m[1].trim();
    if (usesVar(v, 'font-size')) continue;
    if (/^(inherit|unset|initial|revert|smaller|larger|medium|small|large|x-small|xx-small|x-large|xx-large)$/.test(v)) continue;
    if (/\d/.test(v)) counts.fontSize++;
  }

  return counts;
}

export const CLASSES = ['zIndex', 'transition', 'fontSize'];
const CLASS_HINT = {
  zIndex: 'z-index → var(--z-overlay-*) (or a within-context local 0/1)',
  transition: 'transition duration → var(--duration-*) (+ var(--ease-*))',
  fontSize: 'font-size → var(--font-size-*)',
};

/** Walk a source root and return per-file violation counts (only files with ≥1). */
export function scanFiles(srcRoot = SRC) {
  const files = [];
  (function walk(d) {
    let entries;
    try {
      entries = readdirSync(d);
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(d, e).replace(/\\/g, '/');
      const s = statSync(p);
      if (s.isDirectory()) {
        if (e === 'node_modules' || e === 'generated') continue;
        walk(p);
      } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
        files.push(p);
      }
    }
  })(srcRoot);
  const rel = (p) => p.slice(srcRoot.length + 1);
  const current = {};
  for (const f of files) {
    const c = countViolations(stripComments(readFileSync(f, 'utf8')));
    if (c.zIndex || c.transition || c.fontSize) current[rel(f)] = c;
  }
  return current;
}

const sumTotals = (current) =>
  Object.values(current).reduce(
    (t, c) => ({ z: t.z + c.zIndex, tr: t.tr + c.transition, fs: t.fs + c.fontSize }),
    { z: 0, tr: 0, fs: 0 },
  );

/**
 * Pure detection: scan `srcRoot` and compare each file's per-class count to the
 * baseline. Returns the current counts, the parsed baseline, and one structured
 * failure per (file, class) regression. Exported so the ratchet has ONE detection authority
 * even though only the CLI below consumes it today (the 530 kernel enforcer that was the second
 * consumer was retired by tempdoc 930 chunk F).
 */
export function detect({ srcRoot = SRC, baselinePath = BASELINE } = {}) {
  const current = scanFiles(srcRoot);
  const baseline = existsSync(baselinePath) ? JSON.parse(readFileSync(baselinePath, 'utf8')) : {};
  const failures = [];
  for (const [file, c] of Object.entries(current)) {
    const base = baseline[file] ?? { zIndex: 0, transition: 0, fontSize: 0 };
    for (const cls of CLASSES) {
      if (c[cls] > (base[cls] ?? 0)) {
        failures.push({
          file,
          cls,
          count: c[cls],
          base: base[cls] ?? 0,
          message: `${file}: ${cls} raw literals ${c[cls]} > baseline ${base[cls] ?? 0} — ${CLASS_HINT[cls]}`,
        });
      }
    }
  }
  return { current, baseline, failures, totals: sumTotals(current) };
}

/** Rewrite the baseline to the current (shrunk) counts. Returns the totals. */
export function rebalanceBaseline({ srcRoot = SRC, baselinePath = BASELINE } = {}) {
  const current = scanFiles(srcRoot);
  const sorted = Object.fromEntries(Object.entries(current).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(baselinePath, JSON.stringify(sorted, null, 2) + '\n');
  return { files: Object.keys(current).length, totals: sumTotals(current) };
}

// ── CLI (back-compat: ci.yml / hooks / direct dev runs) ───────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  if (REBALANCE) {
    const { files, totals } = rebalanceBaseline();
    console.log(
      `style-literal-ratchet baseline rebalanced — ${files} files; ` +
        `z-index=${totals.z}, transition=${totals.tr}, font-size=${totals.fs}.`,
    );
    process.exit(0);
  }
  if (!existsSync(BASELINE)) {
    console.error(
      `style-literal-ratchet: missing baseline ${BASELINE}. Run:\n  node scripts/ci/check-style-literal-ratchet.mjs --rebalance`,
    );
    process.exit(1);
  }
  const { failures, totals } = detect();
  if (failures.length > 0) {
    console.error('style-literal-ratchet gate FAILED — new raw style literals (574 Phase C):\n');
    for (const f of failures) console.error('  ✗ ' + f.message);
    console.error(
      '\nRoute the value through its single-authority token. If you legitimately REDUCED a file’s count,\n' +
        'run `node scripts/ci/check-style-literal-ratchet.mjs --rebalance` to shrink the baseline.',
    );
    process.exit(1);
  }
  console.log(
    `style-literal-ratchet gate OK — no new raw z-index/transition/font-size literals. ` +
      `Tail remaining (shrinking): z-index=${totals.z}, transition=${totals.tr}, font-size=${totals.fs}.`,
  );
}
