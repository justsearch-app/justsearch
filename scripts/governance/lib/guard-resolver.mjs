/**
 * guard-resolver — the shared home for resolving a register's `guard` declarations to real
 * artifacts (tempdoc 576 §3.1). A "guard" names what proves a declared surface bites: `gate:<id>`
 * (a discipline gate), `test:<Name>` (a regression test), or `self`/`none-yet`/absent (the rung-3
 * marker — declared but NOT actually guarded).
 *
 * This lifts the previously-duplicated parsing/resolution out of the execution-surface enforcer
 * (guard-string grammar + gate/test resolution) and the since-retired prose-tier-register enforcer
 * (the archunit/test class-file resolver), so the surface gates AND the `register-guard-resolution`
 * meta-pass resolve guards through ONE implementation.
 */

import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

const WALK_EXCLUDES = new Set(['build', 'node_modules', 'dist', '.git', '.gradle', 'tmp']);

/** Bounded recursive walk; skips build/node_modules/etc. Returns absolute file paths matching `keep`. */
export function walk(dir, keep) {
  const out = [];
  if (!existsSync(dir)) return out;
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (e.name.startsWith('.') && e.name !== '.') {
      if (WALK_EXCLUDES.has(e.name)) continue;
    }
    if (WALK_EXCLUDES.has(e.name)) continue;
    const abs = join(dir, e.name);
    let isDir = e.isDirectory();
    if (e.isSymbolicLink()) {
      try {
        isDir = statSync(abs).isDirectory();
      } catch {
        continue;
      }
    }
    if (isDir) out.push(...walk(abs, keep));
    else if (keep(e.name)) out.push(abs);
  }
  return out;
}

/** Parse a guard string like "gate:wire + test:Foo" / "self" / "none-yet" into tokens. */
export function parseGuards(guard) {
  if (!guard || guard === 'self' || guard === 'none-yet') return [];
  return String(guard)
    .split('+')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((tok) => {
      const i = tok.indexOf(':');
      if (i < 0) return { kind: 'other', value: tok };
      return { kind: tok.slice(0, i).trim(), value: tok.slice(i + 1).trim() };
    });
}

/**
 * The enforcement strength of a guard (tempdoc 576 §5 — the rung axis for the no-silent-downgrade
 * detector). A real guard (gate:/test:) is strongest; an accountable exemption is weaker; a bare
 * self/none-yet/absent is the floor (and is itself forbidden by §3.1's invalid-guard-form). A
 * baseline→HEAD DECREASE in this value is a guard downgrade (a removed/weakened guard).
 *   2 = real guard (names a gate:/test:)   1 = exempt:<reason>   0 = bare self/none-yet/absent
 */
export function guardStrength(guard) {
  const tokens = parseGuards(guard);
  if (tokens.some((t) => t.kind === 'gate' || t.kind === 'test')) return 2;
  if (tokens.some((t) => t.kind === 'exempt')) return 1;
  return 0;
}

const STRENGTH_LABEL = { 0: 'bare', 1: 'exempt', 2: 'real-guard' };

/**
 * The shared no-silent-downgrade detector for guard-string registers (tempdoc 576 §5). Compares each
 * entry's guard strength baseline→HEAD and emits a detectBaselineTamper event for every DECREASE (a
 * removed/weakened guard — e.g. gate:/test: → exempt:, or → bare). New entries (absent at baseline)
 * are not downgrades; deletions are caught by the per-register orphan/scan checks. Returns events for
 * `detectBaselineTamper` — `covered` marks whether a guard-downgrade changeset accounts for the PR.
 */
export function detectGuardDowngradeEvents({ baselineSurfaces, headSurfaces, regRel, covered }) {
  const baseById = new Map((baselineSurfaces || []).filter((s) => s && s.id).map((s) => [s.id, s.guard]));
  const events = [];
  for (const s of headSurfaces || []) {
    if (!s || !s.id || !baseById.has(s.id)) continue;
    const before = guardStrength(baseById.get(s.id));
    const after = guardStrength(s.guard);
    if (after < before) {
      const delta = `${STRENGTH_LABEL[before]} → ${STRENGTH_LABEL[after]}`;
      events.push({
        raised: true,
        covered: Boolean(covered),
        silentRuleId: 'register-guard-resolution/silent-guard-downgrade',
        silentMessage:
          `${regRel} :: ${s.id}: guard enforcement weakened (${delta}) without a 'guard-downgrade' ` +
          `changeset — a removed/weakened guard is a silent enforcement downgrade (tempdoc 576 §5). ` +
          `Restore the guard, or declare a guard-downgrade changeset stating why.`,
        declaredRuleId: 'register-guard-resolution/declared-guard-downgrade',
        declaredMessage: `${regRel} :: ${s.id}: guard weakened (${delta}); a guard-downgrade changeset covers it.`,
        uri: regRel,
      });
    }
  }
  return events;
}

/** Load the set of gate ids declared in a discipline-gate registry JSON. */
export function loadGateIds(root, registryRel) {
  const abs = resolve(root, registryRel);
  if (!existsSync(abs)) return new Set();
  try {
    const r = JSON.parse(readFileSync(abs, 'utf8'));
    const gates = Array.isArray(r.gates) ? r.gates : Array.isArray(r) ? r : [];
    return new Set(gates.map((g) => g.id).filter(Boolean));
  } catch {
    return new Set();
  }
}

/** True if some *<name>*.{java,py,ts,tsx} test/source file exists under modules/ or scripts/. */
export function testFileExists(root, name) {
  const isTestSrc = (f) =>
    f.endsWith('.java') || f.endsWith('.py') || f.endsWith('.ts') || f.endsWith('.tsx');
  for (const r of ['modules', 'scripts']) {
    for (const abs of walk(resolve(root, r), isTestSrc)) {
      const base = abs.replace(/\\/g, '/').split('/').pop();
      if (base.includes(name)) return true;
    }
  }
  return false;
}

/**
 * Resolve a single parsed guard token against the repo. Returns `{ resolved, reason }`.
 *   - gate:<id>      → id must exist in the registry's gate ids
 *   - test:<Name>    → a *<Name>*.{java,py,ts,tsx} file must exist under modules/ or scripts/
 *   - exempt:<reason>→ an explicit, accountable non-guard (tempdoc 576 §3.1 rung-1): a surface that
 *     legitimately has no biting guard (canonical type, re-export barrel, opaque carrier, a consumer
 *     with no projection law). Resolves ONLY when it carries a non-empty reason — a bare `exempt:`
 *     is rejected so the exemption is always justified. (Reason must not contain `+`, the guard
 *     token separator.)
 *   - anything else (other) → unresolved (an unrecognized guard kind)
 */
export function resolveGuardToken(token, { root, gateIds }) {
  if (token.kind === 'gate') {
    return gateIds.has(token.value)
      ? { resolved: true }
      : { resolved: false, reason: `gate:${token.value} (no such gate)` };
  }
  if (token.kind === 'test') {
    return testFileExists(root, token.value)
      ? { resolved: true }
      : { resolved: false, reason: `test:${token.value} (no matching test file)` };
  }
  if (token.kind === 'exempt') {
    return token.value && token.value.trim()
      ? { resolved: true }
      : { resolved: false, reason: `exempt: (missing reason — an exemption must state why it has no guard)` };
  }
  return { resolved: false, reason: `${token.kind}:${token.value} (unrecognized guard kind)` };
}
