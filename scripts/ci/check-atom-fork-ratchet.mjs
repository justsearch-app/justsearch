#!/usr/bin/env node
/**
 * atom-fork-ratchet gate — tempdoc 574 §22.D (Move 3 completeness, the prevention rung for atoms).
 *
 * Move 3 built the visual-atom tier (`jf-status-badge`, `jf-status-dot`, `jf-button`, `jf-error-alert`)
 * and migrated the forks onto it — but ADOPTION alone does not make a NEW hand-rolled badge/chip/dot
 * unrepresentable. §22.D's de-risk established that a clean grep is ~60/40 true/false (a badge-like CSS
 * rule appears in legit molecules too), so a hard ban would be too noisy. This gate is the same
 * SHRINKING-BASELINE ratchet that `check-style-literal-ratchet` runs for token literals: the existing
 * matches are baselined (so the legit/not-yet-migrated tail does not fail), and a NEW raw atom-class CSS
 * rule outside the atom authority fails the build. The tail shrinks to zero as the forks migrate.
 *
 * Detected = a BASE CSS rule whose selector is an atom-class name — `.badge` / `.pill` / `.chip` /
 * `.tag` / `.status-dot` / `.outcome-tag` (a tone-tinted labeled pill/badge or a status dot). Modifiers
 * (`.chip.user`, `.chip:hover`) and compound names (`.chip-count`, `.card-status-dot`) are NOT counted —
 * only the base `.<name> {` definition, the fork's anchor.
 *
 * AUTHORITY (excluded — these legitimately define the atom look): every `@atom`-marked component (derived,
 * so a NEW atom is auto-excluded — the §21/P6 register-coverage form) PLUS the AHA-distinct chips
 * (e.g. `ProvenanceChip` = trust attribution) that are deliberately their
 * own components, not "a badge with a tone" (574 §22.B / §19.6 AHA cut).
 *
 * Baseline = per-file current counts (`atom-fork-ratchet-baseline.v1.json`). FAILS when a file's count
 * EXCEEDS its baseline (a regression) or a file NOT in the baseline carries any (a new file is born
 * clean — compose an atom). `--rebalance` rewrites the baseline to current; since the gate blocks growth,
 * it only ever shrinks as forks migrate. Honest ceiling (same as run-renderers): a fork that hand-rolls a
 * differently-named class is import/grep-invisible and slips — register + DISCIPLINE, not absolute.
 *
 * Lighter scripts/ci tier; wired as a ci.yml step + the CLAUDE.md pre-merge list. Coverage is the full
 * shell-v0 tree (new files scanned automatically); no enumerated allowlist beyond the derived authority.
 */
import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, extname, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { stripComments } from '../lib/strip-comments.mjs';

export const SRC = 'modules/ui-web/src/shell-v0';
export const BASELINE = 'scripts/ci/atom-fork-ratchet-baseline.v1.json';
const REBALANCE = process.argv.includes('--rebalance');

const norm = (p) => p.replace(/\\/g, '/');

// §22.F — the detection vocabulary + authority exclusions are PROJECTED from the atom-facet catalog,
// so a new atom row + its fork-classes auto-extends coverage (coverage-projects-from-catalog, 557 §5.2).
// Module-relative so detection reads the REAL catalog even when scanning a self-test fixture tree.
const HERE = dirname(fileURLToPath(import.meta.url));
const CATALOG = resolve(HERE, '../../governance/atom-facets.v1.json');
const catalog = JSON.parse(readFileSync(CATALOG, 'utf8'));
const forkClasses = [...new Set(catalog.atoms.flatMap((a) => a.forkClasses))].sort();
// A base atom-class rule: `.badge {`, `.chip {`, … — NOT `.chip-count` / `.chip.mod` / `.chip:hover`.
const ATOM_FORK = new RegExp(`\\.(${forkClasses.join('|')})\\s*\\{`, 'g');

/** rel key = path under `modules/ui-web/src/` (so `shell-v0/...`), robust to the scan root. */
const relKey = (p) => norm(p).split('modules/ui-web/src/')[1] ?? norm(p);

// AHA-distinct chips that own their look but are not the badge atom (574 §19.6 / §22.B), from the
// catalog. Normalized through relKey on BOTH sides so the exclusion holds whether the scan root is an
// absolute kernel path (`F:/…/modules/ui-web/src/shell-v0/…`) or a repo-relative CLI path.
const AHA_DISTINCT = new Set(catalog.ahaDistinct.map((p) => relKey(p)));

/** Walk a shell-v0 source root; return per-file raw atom-class rule counts (only files with ≥1). */
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
      const p = norm(join(d, e));
      const s = statSync(p);
      if (s.isDirectory()) {
        if (e === 'node_modules' || e === 'generated') continue;
        walk(p);
      } else if (extname(p) === '.ts' && !/\.(test|spec)\.ts$/.test(p)) {
        files.push(p);
      }
    }
  })(srcRoot);
  const current = {};
  for (const f of files) {
    const raw = readFileSync(f, 'utf8');
    // AUTHORITY: @atom components (derived) + the AHA-distinct chips legitimately define the look.
    if (/@atom\b/.test(raw) || AHA_DISTINCT.has(relKey(f))) continue;
    const code = stripComments(raw);
    const count = (code.match(ATOM_FORK) ?? []).length;
    if (count) current[relKey(f)] = count;
  }
  return current;
}

/**
 * Pure detection: scan `srcRoot`, compare each file's raw atom-class count to the
 * baseline, return one structured failure per regression. Shared by the CLI and
 * the 530 kernel enforcer (`scripts/governance/gates/atom-fork-ratchet/`).
 */
export function detect({ srcRoot = SRC, baselinePath = BASELINE } = {}) {
  const current = scanFiles(srcRoot);
  const baseline = existsSync(baselinePath) ? JSON.parse(readFileSync(baselinePath, 'utf8')) : {};
  const failures = [];
  for (const [file, count] of Object.entries(current)) {
    const base = baseline[file] ?? 0;
    if (count > base) {
      failures.push({
        file,
        count,
        base,
        message:
          `${file}: ${count} raw atom-class rule(s) > baseline ${base} — compose a registered atom ` +
          `(jf-status-badge / jf-status-dot / jf-button / jf-error-alert), not a hand-rolled .badge/.chip/.dot.`,
      });
    }
  }
  const total = Object.values(current).reduce((t, n) => t + n, 0);
  return { current, baseline, failures, total };
}

/** Rewrite the baseline to the current (shrunk) counts. Returns {files, total}. */
export function rebalanceBaseline({ srcRoot = SRC, baselinePath = BASELINE } = {}) {
  const { current, failures } = detect({ srcRoot, baselinePath });
  if (failures.length > 0) {
    throw new Error('Cannot rebalance atom-fork baseline upward: ' + failures.map((f) => f.message).join('\n'));
  }
  const sorted = Object.fromEntries(Object.entries(current).sort(([a], [b]) => a.localeCompare(b)));
  writeFileSync(baselinePath, JSON.stringify(sorted, null, 2) + '\n');
  return { files: Object.keys(current).length, total: Object.values(current).reduce((t, n) => t + n, 0) };
}

// ── CLI (back-compat: ci.yml / hooks / direct dev runs) ───────────────────────
if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  if (REBALANCE) {
    const { files, total } = rebalanceBaseline();
    console.log(`atom-fork-ratchet baseline rebalanced — ${files} files, ${total} raw atom-class rules.`);
    process.exit(0);
  }
  if (!existsSync(BASELINE)) {
    console.error(
      `atom-fork-ratchet: missing baseline ${BASELINE}. Run:\n  node scripts/ci/check-atom-fork-ratchet.mjs --rebalance`,
    );
    process.exit(1);
  }
  const { failures, total, current } = detect();
  if (failures.length > 0) {
    console.error('atom-fork-ratchet gate FAILED — new raw atom-class CSS (574 §22.D Move 3):\n');
    for (const f of failures) console.error('  ✗ ' + f.message);
    console.error(
      '\nUse a visual atom. If you legitimately REDUCED a file’s count, run\n' +
        '`node scripts/ci/check-atom-fork-ratchet.mjs --rebalance` to shrink the baseline.',
    );
    process.exit(1);
  }
  console.log(
    `atom-fork-ratchet gate OK — no new raw atom-class CSS. Tail remaining (shrinking): ${total} rule(s) ` +
      `across ${Object.keys(current).length} file(s).`,
  );
}
