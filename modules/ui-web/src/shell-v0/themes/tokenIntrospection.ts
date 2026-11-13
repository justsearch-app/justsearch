// SPDX-License-Identifier: Apache-2.0
/**
 * tokenIntrospection — Tempdoc 560 §23 — the shared design-token read + value-based preview behind
 * the plugin `theme` capability (HostApiImpl / createThemeApi), consumed by the bundled token-editor
 * plugin (560 §25/§26). (It originally also backed the core TokenEditorSurface, retired in §25/§26.)
 *
 * One source of truth for: enumerating {@link KNOWN_TOKEN_NAMES} with their computed values + a
 * widget category, and applying a value-only live preview through a host-generated
 * `@layer user-theme` constructed stylesheet. The host always generates the CSS from
 * {known-token-name → value} pairs — a caller (including an untrusted plugin reaching this via the
 * `theme` capability) never supplies raw CSS, and every key is validated against
 * {@link KNOWN_TOKEN_NAMES}. So a preview can only ever restyle a vetted token: no second
 * presentation authority, no CSS-injection vector (560 §4.4).
 */
import { KNOWN_TOKEN_NAMES, isSafeTokenValue } from './designTokenTree.js';

const previewSheets = new WeakMap<Document, Map<string, CSSStyleSheet>>();

export type TokenWidgetType = 'color' | 'number' | 'angle' | 'duration' | 'text';

/**
 * Is {@code value} a standalone CSS color literal (hex or a self-contained color function)? Used to
 * decide whether the color-picker widget is safe for a color-categorized token: many `p-*` / channel
 * tokens hold a *partial* value (an `R, G, B` triplet consumed as `rgb(var(--p-glass))`, or a
 * `var(...)` ref), where a hex from the picker would corrupt the downstream composition — those get a
 * text input instead.
 */
export function isColorLiteral(value: string): boolean {
  const v = value.trim();
  if (v === '' || v.includes('var(')) return false;
  if (/^#[0-9a-f]{3,8}$/i.test(v)) return true;
  return /^(rgb|rgba|hsl|hsla|hwb|lab|lch|oklab|oklch|color)\([^{}<>]*\)$/i.test(v);
}

/** A live design token: its name, current computed value, and the widget category for editing. */
export interface TokenInfo {
  readonly name: string;
  readonly currentValue: string;
  readonly category: string;
  readonly widgetType: TokenWidgetType;
}

/** Categorize a token name into a display group + the widget type used to edit it. */
export function categorizeToken(name: string): { category: string; widgetType: TokenWidgetType } {
  if (name.startsWith('p-') || name.startsWith('accent-') || name.startsWith('glass-') ||
      name.startsWith('surface-') || name.startsWith('text-') || name.startsWith('border-') ||
      name.startsWith('glow-') || name.startsWith('layer-') || name.startsWith('result-row')) {
    return { category: 'Colors', widgetType: 'color' };
  }
  if (name.startsWith('h-')) return { category: 'Hues', widgetType: 'angle' };
  if (name.startsWith('z-')) return { category: 'Z-Index', widgetType: 'number' };
  if (name.startsWith('duration-')) return { category: 'Motion', widgetType: 'duration' };
  if (name.startsWith('density-') || name.includes('width') || name.includes('gap') || name.includes('pad')) {
    return { category: 'Spacing', widgetType: 'number' };
  }
  if (name.startsWith('font-')) return { category: 'Typography', widgetType: 'text' };
  if (name.startsWith('shadow-') || name.startsWith('backdrop-')) return { category: 'Effects', widgetType: 'text' };
  return { category: 'Other', widgetType: 'text' };
}

/**
 * Enumerate every {@link KNOWN_TOKEN_NAMES} token with its current computed value (read from
 * `getComputedStyle(document.documentElement)`) and widget category. Read-only.
 */
export function listTokens(): TokenInfo[] {
  const out: TokenInfo[] = [];
  const style = getComputedStyle(document.documentElement);
  for (const name of KNOWN_TOKEN_NAMES) {
    const value = style.getPropertyValue(`--${name}`).trim();
    const { category } = categorizeToken(name);
    let widgetType = categorizeToken(name).widgetType;
    // Value-aware downgrade: only offer the color picker for a standalone color literal; a partial
    // value (channel triplet / var()-ref / unset) gets a text input so the picker can't corrupt it.
    if (widgetType === 'color' && !isColorLiteral(value)) widgetType = 'text';
    out.push({ name, currentValue: value || '(unset)', category, widgetType });
  }
  return out;
}

/**
 * Apply a value-only live preview: for each `[name, value]` in {@code changes}, set
 * `--name: value` inside a host-generated `@layer user-theme` stylesheet owned by `styleId`. Validation
 * happens BEFORE any DOM write — if any name is not in {@link KNOWN_TOKEN_NAMES} the call throws and
 * nothing is applied (the allowlist closure). An empty map clears the preview (removes the sheet).
 *
 * Tempdoc 567 §8 / A3 — an optional `mode` scopes the preview to `[data-theme="<mode>"]` instead of
 * `:root`, so a light-mode-only edit (a `p-*` primitive channel) previews against the active light
 * palette rather than leaking into the dark base.
 *
 * @throws Error if any key is not a known design token.
 */
export function applyTokenPreview(
  changes: ReadonlyMap<string, string>,
  styleId: string,
  mode?: 'light' | 'dark',
): void {
  if (changes.size === 0) {
    clearTokenPreview(styleId);
    return;
  }
  const decls: string[] = [];
  for (const [name, value] of changes) {
    if (!KNOWN_TOKEN_NAMES.has(name)) {
      throw new Error(`token preview: unknown token '${name}' (not in KNOWN_TOKEN_NAMES)`);
    }
    // The value is interpolated raw into `:root { --name: value; }`. Reject brace/angle-bracket
    // characters (the same isSafeTokenValue rule the theme-tree loader uses) so a value can never
    // break out of the rule and inject a sub-rule — no second presentation authority (560 §24).
    if (!isSafeTokenValue(value)) {
      throw new Error(
        `token preview: value for '${name}' contains brace/angle-bracket characters that could ` +
          `break out of the :root rule`,
      );
    }
    decls.push(`  --${name}: ${value};`);
  }
  const selector = mode ? `[data-theme="${mode}"]` : ':root';
  const css = `${selector} {\n${decls.join('\n')}\n}`;
  let sheets = previewSheets.get(document);
  if (!sheets) {
    sheets = new Map();
    previewSheets.set(document, sheets);
  }
  let sheet = sheets.get(styleId);
  if (!sheet) {
    sheet = new CSSStyleSheet();
    sheets.set(styleId, sheet);
  }
  sheet.replaceSync(`@layer user-theme { ${css} }`);
  if (!document.adoptedStyleSheets.includes(sheet)) {
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  }
}

/** Remove the preview stylesheet owned by `styleId` if present (the reset path). */
export function clearTokenPreview(styleId: string): void {
  const sheets = previewSheets.get(document);
  const sheet = sheets?.get(styleId);
  if (sheet) {
    document.adoptedStyleSheets = document.adoptedStyleSheets.filter((s) => s !== sheet);
    sheets!.delete(styleId);
  }
}
