// SPDX-License-Identifier: Apache-2.0
/**
 * 478 §4.E (V1.5.1 minimum-viable subset) — DesignTokenTree.
 *
 * Themes contribute structured token values; the host compiles
 * them to layered CSS. Themes CANNOT emit raw CSS, selectors,
 * or rules outside `:root`. The malicious-theme attack surface
 * (`*::before { content: 'pwned' }`) is closed by construction.
 *
 * V1.5.1 alpha shipped CSS-file themes (V1.5.0 backward-compat
 * preserved; H2.2 layered cascade + H3.3 manifest format).
 * V1.5.1 §4.E adds the JSON theme format alongside CSS — themes
 * can ship as `.json` token trees OR `.css` files. The loader
 * detects format by file extension; JSON themes go through the
 * validator + compiler; CSS themes go through the legacy direct-
 * inject path.
 *
 * V1.6 / DTCG-stable: when DTCG (W3C Design Tokens) reaches
 * stable, this internal format aligns with DTCG. The internal
 * type is forward-compatible: it's a strict subset of DTCG v2025.10
 * preview, so a DTCG-targeted converter is a one-pass transform.
 *
 * Why a JustSearch-internal subset (not DTCG preview directly):
 *   - DTCG v2025.10 is preview-stage; the spec changes between
 *     drafts. Implementing preview means re-implementing on every
 *     spec change.
 *   - The full DTCG type vocabulary (color, dimension, fontFamily,
 *     duration, cubicBezier, asset, gradient, transition, etc.)
 *     is more than V1.5.1 themes need. Subset = simpler authoring.
 *   - JustSearch's own token vocabulary is the canonical authoring
 *     contract. DTCG alignment is forward-work; the subset is
 *     enough for V1.5.1 marketplace + plugin theming.
 *
 * Design properties:
 *   - Themes can ONLY set values for tokens whose names are listed
 *     in {@link KNOWN_TOKEN_NAMES}. Unknown tokens are rejected
 *     with a clear error (catches typos + restricts attack surface).
 *   - All values are STRINGS in the JSON. The host compiler emits
 *     `:root { --<name>: <value>; }` directly. CSS-side validation
 *     (e.g., is `red` a valid color) is left to the browser.
 *   - The output is wrapped in `@layer user-theme` automatically
 *     (matches the H2.2 themeState injection pattern).
 *
 * Trust model: a theme manifest can declare `format: 'tree'`
 * pointing at a JSON file; the loader fetches + validates +
 * compiles. UNTRUSTED tier themes (V1.5.2+) MUST use the tree
 * format — CSS themes are TRUSTED-only because they can write
 * arbitrary CSS rules.
 */

/**
 * The (V1.5.1) closed list of known design tokens. Themes can
 * only set values for these names; unknown names are rejected.
 *
 * The list is the union of:
 *   - Primitive tokens (--p-*, --h-*, --z-*) — RGB triplets, hue
 *     angles, z-index reservations
 *   - Semantic tokens (--accent-*, --surface-*, --text-*, etc.) —
 *     the consumer-facing token surface
 *   - Component tokens (--inspector-*, --gutter-*, --density-*) —
 *     specific layout dimensions
 *
 * Adding a new token:
 *   1. Add the name to this list
 *   2. Add a default value to `tokens.css`'s :root block
 *   3. Themes can now override it
 */
export const KNOWN_TOKEN_NAMES: ReadonlySet<string> = new Set([
  // Primitive palette (RGB triplets)
  'p-glass', 'p-shadow', 'p-text',
  // Hue angles
  'h-teal', 'h-purple', 'h-green', 'h-amber', 'h-red', 'h-blue', 'h-magenta',
  // Z-index reservations
  'z-base', 'z-glass', 'z-float', 'z-modal',
  // Accent colors (oklch values typically)
  'accent-tint', 'accent-command', 'accent-chat',
  'accent-success', 'accent-warning', 'accent-danger',
  'accent-warm', 'accent-warm-muted', 'accent-on-tint',
  // Tempdoc 567 §8 / A2 — semantic role foregrounds (auto-derived to a WCAG floor over the accent bg)
  'accent-on-command', 'accent-on-chat', 'accent-on-success', 'accent-on-warning', 'accent-on-danger',
  // Tempdoc 577 Phase 7 — the search window's highlight + link roles
  'accent-highlight', 'accent-on-highlight', 'accent-link', 'accent-on-link',
  // Keyboard focus indicator
  'focus-ring-color',
  // Glass surfaces
  'glass-surface', 'glass-surface-hover', 'glass-surface-active',
  'glass-border', 'glass-border-hover', 'glass-border-strong',
  // Surface elevation
  'surface-0', 'surface-1', 'surface-2', 'surface-3', 'surface-4',
  'surface-primary', 'surface-secondary', 'surface-tertiary', 'surface-hover',
  // Text
  'text-primary', 'text-secondary', 'text-tertiary', 'text-muted', 'text-ghost',
  // Layer base (background)
  'layer-base', 'layer-base-solid',
  // Shadows
  'shadow-glass', 'shadow-float', 'shadow-lift', 'shadow-menu', 'shadow-panel-edge',
  // Border
  'border-subtle',
  // Inspector / Layout
  'inspector-width-collapsed', 'inspector-width-expanded', 'gutter-icon',
  // Typography
  'font-native', 'font-display', 'font-mono',
  // Motion
  'duration-instant', 'duration-fast', 'duration-normal',
  // Density
  'density-stack-gap-sm', 'density-stack-gap', 'density-stack-gap-lg',
  'density-inner-pad-x', 'density-inner-pad-y',
  // Halo / glow
  'halo-shadow', 'glow-inset-subtle', 'glow-inset-rim',
  // Result row
  'result-row-cursor',
  // Backdrop
  'backdrop-filter-blur',
]);

/**
 * Tempdoc 567 §8 #4 — the SEEDS: the only tokens a USER may author via the Theme Editor. Every other
 * known token DERIVES from these in CSS (`rgb(var(--p-*))`, hue-driven `oklch()`), so authoring a
 * derived token is meaningless (the cascade recomputes it) and is rejected at the `saveTheme`
 * capability boundary — which makes "seeds only" STRUCTURAL, not just an editor-UI convention. Seeds =
 * the primitive channels (`p-*`) + the hue angles (`h-*`); derived strictly from
 * {@link KNOWN_TOKEN_NAMES} so the seed set and the closed token vocabulary can never drift.
 */
export const SEED_TOKEN_NAMES: ReadonlySet<string> = new Set(
  [...KNOWN_TOKEN_NAMES].filter((name) => name.startsWith('p-') || name.startsWith('h-')),
);

/**
 * Theme as a structured token tree. Themes set values for the
 * tokens they want to override; unset tokens fall back to the
 * core-theme defaults (per H2.2 layered cascade).
 *
 * Token names in the JSON are the CSS variable names WITHOUT
 * the `--` prefix. `tokens.surface-1: '#3b4252'` compiles to
 * `:root { --surface-1: #3b4252; }`.
 */
export interface DesignTokenTree {
  /** Schema version. V1.5.1 = 1. */
  readonly schemaVersion: 1;
  /** Stable theme id. */
  readonly id: string;
  /** Human-readable label. */
  readonly displayName: string;
  /** Optional description. */
  readonly description?: string;
  /** Theme version (semver-like). */
  readonly version?: string;
  /** Author identifier. */
  readonly author?: string;
  /** Token name → CSS value. Compiled into the `:root` block (the dark/base layer). */
  readonly tokens: Record<string, string>;
  /**
   * Tempdoc 567 §8 / A3 — per-mode token overrides. `dark` merges into the `:root` base (mirroring
   * tokens.css, whose `:root` is the dark default); `light` compiles into a `[data-theme="light"]`
   * block. This lets a theme author the mode-variant primitive channels (`p-*`) so a custom theme is
   * correct in BOTH light and dark, while mode-invariant seeds (`h-*`) stay in {@link tokens}.
   */
  readonly tokensByMode?: {
    readonly light?: Record<string, string>;
    readonly dark?: Record<string, string>;
  };
}

export type ManifestValidationResult =
  | { readonly ok: true; readonly tree: DesignTokenTree }
  | { readonly ok: false; readonly errors: readonly string[] };

/**
 * Is {@code value} safe to interpolate into a `:root { --name: <value>; }` declaration?
 *
 * The token-tree compiler (and the live token-preview in `tokenIntrospection.ts`) build CSS by raw
 * string interpolation without CSS escaping. A value containing a brace or angle-bracket could break
 * out of the `:root` rule and inject a sub-rule (a second presentation authority / `</style>`). This
 * is the single authority for that rule — reused by {@link validateDesignTokenTree} and the plugin
 * `theme` capability's preview path. (`;` is intentionally allowed: a stray `;` only ends the current
 * declaration early — it cannot break out of the `:root` block.)
 */
export function isSafeTokenValue(value: string): boolean {
  return !/[}{<>]/.test(value);
}

/**
 * Validate a `{ tokenName → value }` map: every name must be a known token, every value a
 * brace/angle-bracket-free string. Shared by the base `tokens` and each `tokensByMode` map.
 * `prefix` is the JSON path (e.g. 'tokens' or 'tokensByMode.light') used in error messages.
 */
function validateTokenMap(
  tokens: Record<string, unknown>,
  prefix: string,
  errors: string[],
): void {
  for (const name of Object.keys(tokens)) {
    if (!KNOWN_TOKEN_NAMES.has(name)) {
      errors.push(
        `${prefix}.${name} is not a known token. ` +
          `See KNOWN_TOKEN_NAMES (modules/ui-web/src/shell-v0/themes/designTokenTree.ts) ` +
          `for the V1.5.1 list.`,
      );
      continue;
    }
    const value = tokens[name];
    if (typeof value !== 'string') {
      errors.push(`${prefix}.${name} must be a string; got ${typeof value}`);
      continue;
    }
    // Extra security: reject CSS escape characters that could break out of the
    // `{ --name: <value>; }` context. The compiler doesn't do CSS-string escaping.
    if (!isSafeTokenValue(value)) {
      errors.push(
        `${prefix}.${name} value contains brace or angle-bracket characters ` +
          `that could break the :root rule context. Refusing for safety.`,
      );
    }
  }
}

/**
 * Validate a candidate token-tree against the V1.5.1 schema.
 * Accepts unknown input; never throws.
 */
export function validateDesignTokenTree(
  candidate: unknown,
): ManifestValidationResult {
  const errors: string[] = [];

  if (candidate === null || typeof candidate !== 'object') {
    return { ok: false, errors: ['theme tree is not an object'] };
  }
  const t = candidate as Record<string, unknown>;

  if (t['schemaVersion'] !== 1) {
    errors.push(
      `schemaVersion must be the literal number 1; got ${JSON.stringify(t['schemaVersion'])}`,
    );
  }
  if (typeof t['id'] !== 'string' || !/^[a-z][a-z0-9.-]+$/.test(t['id'] as string)) {
    errors.push(
      `id must be a string matching /^[a-z][a-z0-9.-]+$/; got ${JSON.stringify(t['id'])}`,
    );
  }
  if (typeof t['displayName'] !== 'string' || (t['displayName'] as string).length === 0) {
    errors.push('displayName must be a non-empty string');
  }
  if (t['description'] !== undefined && typeof t['description'] !== 'string') {
    errors.push('description must be a string when present');
  }
  if (t['version'] !== undefined && typeof t['version'] !== 'string') {
    errors.push('version must be a string when present');
  }
  if (t['author'] !== undefined && typeof t['author'] !== 'string') {
    errors.push('author must be a string when present');
  }

  // Tokens must be an object with string values for known token names.
  if (t['tokens'] === null || typeof t['tokens'] !== 'object') {
    errors.push('tokens must be an object');
    return { ok: false, errors };
  }
  validateTokenMap(t['tokens'] as Record<string, unknown>, 'tokens', errors);

  // Tempdoc 567 §8 / A3 — validate the optional per-mode override maps the same way.
  if (t['tokensByMode'] !== undefined) {
    const byMode = t['tokensByMode'];
    if (byMode === null || typeof byMode !== 'object') {
      errors.push('tokensByMode must be an object when present');
    } else {
      for (const mode of Object.keys(byMode as Record<string, unknown>)) {
        if (mode !== 'light' && mode !== 'dark') {
          errors.push(`tokensByMode.${mode} is not a valid mode (expected 'light' or 'dark')`);
          continue;
        }
        const modeMap = (byMode as Record<string, unknown>)[mode];
        if (modeMap === null || typeof modeMap !== 'object') {
          errors.push(`tokensByMode.${mode} must be an object`);
          continue;
        }
        validateTokenMap(modeMap as Record<string, unknown>, `tokensByMode.${mode}`, errors);
      }
    }
  }

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return { ok: true, tree: candidate as DesignTokenTree };
}

/**
 * Compile a validated DesignTokenTree to CSS. The output is the
 * theme's `:root { --token: value; ... }` block — themeState
 * wraps it in `@layer user-theme` per H2.2 cascade discipline.
 *
 * The tree must have already been validated; calling with an
 * un-validated tree could produce broken CSS. The compiler is
 * intentionally simple — one declaration per token; no
 * transformations.
 */
export function compileTokenTreeToCss(tree: DesignTokenTree): string {
  const lines: string[] = [];
  lines.push(`/* Compiled from DesignTokenTree: ${tree.id}`);
  if (tree.author) lines.push(` * Author: ${tree.author}`);
  if (tree.version) lines.push(` * Version: ${tree.version}`);
  lines.push(` */`);
  // :root carries the shared tokens + the DARK overrides — mirroring tokens.css, whose :root is the
  // dark default with [data-theme="light"] as the override. A custom theme is thus correct in both
  // modes: dark/base at :root, light at [data-theme="light"]. (567 §8 / A3)
  const rootTokens = { ...tree.tokens, ...(tree.tokensByMode?.dark ?? {}) };
  lines.push(`:root {`);
  for (const [name, value] of Object.entries(rootTokens)) {
    lines.push(`  --${name}: ${value};`);
  }
  lines.push(`}`);
  const light = tree.tokensByMode?.light;
  if (light && Object.keys(light).length > 0) {
    lines.push(`[data-theme="light"] {`);
    for (const [name, value] of Object.entries(light)) {
      lines.push(`  --${name}: ${value};`);
    }
    lines.push(`}`);
  }
  return lines.join('\n');
}

/**
 * Boot-time fetch + validate + compile. Used by the loader when
 * a theme manifest entry has `format: 'tree'`.
 *
 * Returns the compiled CSS on success; null on fetch / parse /
 * validation failure (caller falls back gracefully).
 */
export async function fetchAndCompileTokenTree(
  url: string,
  // Static theme file (not an API call). Eslint's
  // restricted-globals warning is for backend API calls; theme
  // files are co-located static assets.
  // eslint-disable-next-line no-restricted-globals
  fetchImpl: typeof fetch = fetch,
): Promise<{ tree: DesignTokenTree; css: string } | null> {
  let body: unknown;
  try {
    const response = await fetchImpl(url);
    if (!response.ok) {
      console.debug(
        `[designTokenTree] fetch ${url} returned ${response.status}; theme load fails`,
      );
      return null;
    }
    const ct = response.headers.get('content-type') ?? '';
    if (ct.includes('html')) {
      return null;
    }
    body = await response.json();
  } catch (err) {
    console.debug(`[designTokenTree] fetch ${url} failed`, err);
    return null;
  }

  const result = validateDesignTokenTree(body);
  if (!result.ok) {
    console.debug(
      `[designTokenTree] validation failed (${result.errors.length} error(s)):\n` +
        result.errors.map((e) => `  - ${e}`).join('\n'),
    );
    return null;
  }
  return { tree: result.tree, css: compileTokenTreeToCss(result.tree) };
}
