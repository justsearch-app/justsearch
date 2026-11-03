/**
 * Drift guards for the layered token system (slice 3a.1.1).
 *
 * Per `20-systems/04-theme-customization.md` §"Token Layers" and
 * `60-migration-history/09-foundation-correctness-audit.md` Concern 1,
 * the framework's normative architecture is:
 *
 *   1. primitives           (`primitives.css`)
 *   2. semantic tokens      (`default.css`, top half)
 *   3. component tokens     (`default.css`, bottom half)
 *   4. product/theme overrides     (`app-bridge.css`)
 *   5. plugin-scoped overrides
 *
 * This file enforces five invariants:
 *
 *   I1. Every renderer-referenced token has a catalog entry.
 *   I2. The renderer's fallback equals the catalog token's RESOLVED
 *       value (after walking through the var() chain to a primitive
 *       or CSS literal).
 *   I3. No orphan component tokens (every catalog entry has at least
 *       one renderer reference).
 *   I4. No direct primitive references at the component layer
 *       (component tokens reference only semantic tokens).
 *   I5. Every semantic token resolves to a primitive or a CSS
 *       literal — no semantic-references-semantic chains > 1.
 *
 * Catches: layer-skipping (component → primitive), layer-loops
 * (semantic → semantic indefinitely), drift between renderer
 * fallback and resolved catalog value, orphan tokens at any layer.
 */

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const SHELL_V0_DIR = resolve(__dirname, '..');
const PRIMITIVES_CSS = resolve(__dirname, 'primitives.css');
const DEFAULT_CSS = resolve(__dirname, 'default.css');
// The global base type scale (`--font-size-*`, the 574 scale). After the 574
// §25 two-scale consolidation the theme's semantic type tokens project from
// `--font-size-*` directly, so the layer chain bottoms out here. Only the
// `--font-size-*` block is pulled in as a primitive — the rest of tokens.css
// (incl. some `--justsearch-shell-*` colour tokens) is NOT in the theme
// system's modeled layers and would perturb I3/I4 if added wholesale.
const TOKENS_CSS = resolve(__dirname, '../../styles/tokens.css');

const COVERED_FILES = [
  // Renderers (slice 3a.0)
  'renderers/controls/TextControl.ts',
  'renderers/controls/NumberControl.ts',
  'renderers/controls/BooleanControl.ts',
  'renderers/controls/EnumControl.ts',
  'renderers/controls/DateControl.ts',
  'renderers/controls/TimeControl.ts',
  'renderers/controls/ObjectControl.ts',
  'renderers/controls/ArrayControl.ts',
  'renderers/layouts/VerticalLayout.ts',
  'renderers/layouts/HorizontalLayout.ts',
  'renderers/layouts/GroupLayout.ts',
  'renderers/layouts/CategorizationLayout.ts',
  // Generic shell components (slice 3a.1 Phase 4a + 4b)
  'components/Form.ts',
  'components/StatusCard.ts',
  'components/ActionButton.ts',
  'components/Table.ts',
  // TIMESERIES renderer pair (slice 3a.1.4 Phase 4) — no new component
  // tokens introduced; both components use `currentColor` literal which
  // the I4 drift-guard exception list permits.
  'components/TimeseriesPolyline.ts',
  'views/TimeseriesSparkline.ts',
];

interface TokenRef {
  name: string;
  fallback: string;
  source: string;
}

/**
 * Extract every `var(--justsearch-shell-…, fallback)` from a renderer
 * source file. The fallback group allows one level of balanced parens
 * so `rgba(...)` and `var(...)` literals parse cleanly.
 */
function extractRendererTokens(filePath: string): TokenRef[] {
  const text = readFileSync(filePath, 'utf8');
  const refs: TokenRef[] = [];
  const re =
    /var\((--justsearch-shell-[a-z0-9-]+)\s*,\s*((?:[^()]|\([^()]*\))+)\)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    refs.push({
      name: m[1] ?? '',
      fallback: (m[2] ?? '').trim(),
      source: filePath,
    });
  }
  return refs;
}

/**
 * Extract `--token: value;` pairs from a CSS file. The value is the
 * raw text between the colon and the terminating semicolon.
 */
function extractTokens(filePath: string): Map<string, string> {
  const text = readFileSync(filePath, 'utf8');
  const tokens = new Map<string, string>();
  const re = /(--[a-z][a-z0-9-]*)\s*:\s*([^;]+);/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    tokens.set((m[1] ?? '').trim(), (m[2] ?? '').trim());
  }
  return tokens;
}

/**
 * Resolve a token value through the layer chain. Substitutes every
 * `var(--name)` reference inside the value (compound values like
 * `var(--jf-space-1) var(--jf-space-2)` resolve to the literal
 * `0.25rem 0.5rem`). Recurses until no var() references remain.
 * Throws on cycles.
 */
function resolveToken(
  name: string,
  catalog: Map<string, string>,
  visiting: Set<string> = new Set(),
): string {
  if (visiting.has(name)) {
    throw new Error(`Token cycle: ${[...visiting, name].join(' -> ')}`);
  }
  const raw = catalog.get(name);
  if (raw === undefined) {
    return `<undefined:${name}>`;
  }
  return resolveValue(raw, catalog, new Set([...visiting, name]));
}

function resolveValue(
  value: string,
  catalog: Map<string, string>,
  visiting: Set<string>,
): string {
  // Substitute every var() reference inside the value. Match
  // `var(--name)` or `var(--name, fallback)`; the inner --name
  // recurses through the catalog.
  return value.replace(
    /var\((--[a-z][a-z0-9-]*)(?:\s*,[^)]*)?\)/g,
    (_match, refName: string) => resolveToken(refName, catalog, visiting),
  );
}

/**
 * Classify a CSS token name by layer using actual file membership.
 * Primitive = declared in `primitives.css` OR the `--font-size-*` base
 * type scale (tokens.css). Component = starts with `--justsearch-shell-`.
 * Semantic = `--jf-*` not in primitives.
 *
 * Membership-based avoids the false-positive that name-pattern
 * classifiers hit (e.g., `--jf-weight-medium` is primitive but
 * `--jf-text-label-weight` is semantic — both share the `--jf-`
 * prefix, so only membership distinguishes them).
 */
type Layer = 'primitive' | 'semantic' | 'component';

function makeLayerClassifier(
  primitives: Map<string, string>,
): (name: string) => Layer | null {
  return (name: string): Layer | null => {
    if (name.startsWith('--justsearch-shell-')) return 'component';
    if (primitives.has(name)) return 'primitive';
    if (name.startsWith('--jf-')) return 'semantic';
    return null;
  };
}

/**
 * Pull the var() reference out of a "raw" catalog value. Returns the
 * inner --token name, or null if the value isn't a single var().
 */
function singleVarReference(raw: string): string | null {
  const m = /^var\((--[a-z][a-z0-9-]*)(?:\s*,[^)]*)?\)$/.exec(raw);
  return m ? (m[1] as string) : null;
}

describe('Shell V0 token catalog — layer integrity', () => {
  const fontScale = new Map(
    [...extractTokens(TOKENS_CSS)].filter(([k]) => k.startsWith('--font-size-')),
  );
  const primitives = new Map([...extractTokens(PRIMITIVES_CSS), ...fontScale]);
  const defaultTokens = extractTokens(DEFAULT_CSS);
  const catalog = new Map<string, string>([
    ...primitives,
    ...defaultTokens,
  ]);
  const classifyLayer = makeLayerClassifier(primitives);

  const allRefs: TokenRef[] = [];
  for (const f of COVERED_FILES) {
    allRefs.push(...extractRendererTokens(resolve(SHELL_V0_DIR, f)));
  }

  it('I1: catalog declares every token referenced by a renderer', () => {
    const referencedNames = new Set(allRefs.map((r) => r.name));
    const missing: string[] = [];
    for (const name of referencedNames) {
      if (!catalog.has(name)) missing.push(name);
    }
    expect(missing).toEqual([]);
  });

  it('I2: renderer fallbacks match the resolved catalog defaults', () => {
    const mismatches: string[] = [];
    for (const ref of allRefs) {
      if (!catalog.has(ref.name)) continue;
      const resolved = resolveToken(ref.name, catalog);
      if (resolved !== ref.fallback) {
        mismatches.push(
          `${ref.name}: renderer=${ref.fallback} vs resolved=${resolved}`,
        );
      }
    }
    const unique = Array.from(new Set(mismatches));
    expect(unique).toEqual([]);
  });

  it('I3: catalog has at least one renderer reference for every component token', () => {
    const referencedNames = new Set(allRefs.map((r) => r.name));
    const orphaned: string[] = [];
    for (const name of catalog.keys()) {
      if (!name.startsWith('--justsearch-shell-')) continue;
      if (!referencedNames.has(name)) orphaned.push(name);
    }
    expect(orphaned).toEqual([]);
  });

  it('I4: component tokens reference only semantic tokens (no direct primitives, no literals)', () => {
    const violations: string[] = [];
    for (const [name, value] of catalog.entries()) {
      if (classifyLayer(name) !== 'component') continue;
      const referenced = singleVarReference(value);
      if (!referenced) {
        // Component token holds a non-var literal. Allowed only for
        // CSS keywords like `transparent` / `inherit` since those
        // aren't in the primitive catalog by convention.
        if (
          value === 'transparent' ||
          value === 'inherit' ||
          value === '0' ||
          value === 'currentColor'
        ) {
          continue;
        }
        violations.push(`${name}: literal '${value}' (expected var() reference)`);
        continue;
      }
      const refLayer = classifyLayer(referenced);
      if (refLayer !== 'semantic') {
        violations.push(
          `${name}: refs ${referenced} (layer=${refLayer}), expected semantic`,
        );
      }
    }
    expect(violations).toEqual([]);
  });

  it('I5: semantic tokens resolve to a primitive or CSS literal (no chains > 1)', () => {
    const violations: string[] = [];
    for (const [name, value] of catalog.entries()) {
      if (classifyLayer(name) !== 'semantic') continue;
      const referenced = singleVarReference(value);
      if (!referenced) continue; // CSS literal is fine.
      const refLayer = classifyLayer(referenced);
      if (refLayer === 'semantic') {
        // Semantic-references-semantic IS allowed if the chain
        // bottoms out within one extra hop. Walk it.
        const next = catalog.get(referenced);
        if (!next) {
          violations.push(`${name}: refs ${referenced} which is undefined`);
          continue;
        }
        const nextRef = singleVarReference(next);
        const nextLayer = nextRef ? classifyLayer(nextRef) : null;
        if (nextLayer === 'semantic') {
          violations.push(
            `${name}: ${referenced} -> ${nextRef} chains semantic-to-semantic; resolve to a primitive in fewer hops`,
          );
        }
      }
    }
    expect(violations).toEqual([]);
  });
});
