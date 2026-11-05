// SPDX-License-Identifier: Apache-2.0
/**
 * host.theme capability module (tempdoc 560 §24) — extracted from HostApiImpl, mirroring
 * createSelectionApi. Reads (subscribeActiveTheme / getActiveThemeId / getTokens) are available at
 * every tier; the WRITES are trust-attenuated:
 *   - `selectTheme` changes the active palette;
 *   - `previewTokens` restyles the running app.
 * UNTRUSTED structurally omits both (the optional PluginThemeState fields are simply absent — a
 * compile-time signal in plugin code), selected by tier with no runtime if(tier) in any method body.
 * An untrusted plugin therefore cannot repaint the app — no second presentation authority (§4.4),
 * consistent with the read/write tier discipline of data/selection/ui.
 *
 * `previewTokens` injects a PER-PLUGIN scoped `<style>` so two plugins never clobber each other's
 * preview and uninstall/Reset (`previewTokens(new Map())`) clears exactly this plugin's preview. The
 * host builds the `@layer user-theme` CSS from {name→value} pairs — every name allowlisted, every
 * value sanitized in applyTokenPreview — so the plugin never supplies raw CSS.
 */
import type { PluginThemeState, PluginTrustTier, Unsubscribe } from '../plugin-types.js';
import {
  applyAppearance,
  clearActiveTheme,
  getActiveThemeId,
  subscribeActiveTheme,
  syncCustomThemesToCatalog,
} from '../../state/themeState.js';
import { applyTokenPreview, listTokens } from '../../themes/tokenIntrospection.js';
import { getThemeCatalogEntry, listAvailableThemes } from '../../themes/themesCatalog.js';
import {
  SEED_TOKEN_NAMES,
  validateDesignTokenTree,
  type DesignTokenTree,
} from '../../themes/designTokenTree.js';
import { ROLE_FG_TOKEN_NAMES } from '../../themes/themeRoles.js';
import { getDocument, mutateDocument } from '../../state/UserStateDocument.js';

/**
 * Tempdoc 567 §8 #4 / A2 — the ONE producer-side gate every Theme write flows through (saveTheme,
 * importTheme, renameTheme). It (1) validates the tree against the DesignTokenTree schema, (2) enforces
 * the "seeds + roles" authorable surface — rejecting any token that is neither a seed (`p-*`/`h-*`) nor
 * a role foreground (`accent-on-*`), since everything else DERIVES in CSS — and (3) persists it
 * replace-by-id into `UserStateDocument.customThemes` + registers it into the catalog's custom layer.
 *
 * Routing import through the SAME gate is the keystone: an imported (or hand-edited, shared) JSON theme
 * is held to the identical authorable surface as an editor save, so import can never become a backdoor
 * that authors a derived token — the second-authority defect 567 forecloses, kept foreclosed on the
 * import path too.
 */
function persistTree(tree: DesignTokenTree, who: string): void {
  const result = validateDesignTokenTree(tree);
  if (!result.ok) {
    throw new Error(`${who}: invalid theme — ${result.errors.join('; ')}`);
  }
  const disallowed = [
    ...new Set([
      ...Object.keys(tree.tokens),
      ...Object.keys(tree.tokensByMode?.light ?? {}),
      ...Object.keys(tree.tokensByMode?.dark ?? {}),
    ]),
  ].filter((name) => !SEED_TOKEN_NAMES.has(name) && !ROLE_FG_TOKEN_NAMES.has(name));
  if (disallowed.length > 0) {
    throw new Error(
      `${who}: ${disallowed.map((n) => `'${n}'`).join(', ')} ` +
        `${disallowed.length === 1 ? 'is not authorable' : 'are not authorable'} — the authorable ` +
        `surface is seeds (p-*/h-*) + role foregrounds (accent-on-*); other tokens derive in CSS`,
    );
  }
  mutateDocument((doc) => {
    const others = (doc.customThemes ?? []).filter((t) => t.id !== tree.id);
    return { ...doc, customThemes: [...others, tree] };
  });
  syncCustomThemesToCatalog();
}

export function createThemeApi(tier: PluginTrustTier, pluginId: string): PluginThemeState {
  const previewStyleId = `jf-plugin-token-preview--${pluginId}`;

  const reads = {
    subscribeActiveTheme: (handler: (id: string | null) => void): Unsubscribe =>
      subscribeActiveTheme(handler),
    getActiveThemeId: (): string | null => getActiveThemeId(),
    getTokens: () => listTokens(),
    // Tempdoc 567 — list the available themes (built-in + user custom) for the editor/picker.
    listThemes: (): ReadonlyArray<{ id: string; displayName: string; isCustom: boolean }> =>
      listAvailableThemes().map((t) => ({
        id: t.id,
        displayName: t.displayName,
        isCustom: t.tokens !== undefined,
      })),
    // Tempdoc 567 §8 #2 — serialize a saved custom theme to JSON for export/share (copy to clipboard /
    // save to file). Returns null for a non-custom or unknown id. Read-only; available at every tier.
    exportTheme: (id: string): string | null => {
      const entry = getThemeCatalogEntry(id);
      return entry?.tokens ? JSON.stringify(entry.tokens, null, 2) : null;
    },
  };

  if (tier === 'UNTRUSTED_PLUGIN') {
    return reads;
  }

  return {
    ...reads,
    // C1: route palette selection through the one appearance writer.
    selectTheme: async (id: string | null): Promise<void> => {
      await applyAppearance({ paletteId: id });
    },
    previewTokens: (changes: ReadonlyMap<string, string>, mode?: 'light' | 'dark'): void => {
      applyTokenPreview(changes, previewStyleId, mode);
    },
    // Tempdoc 567 — persist a user theme as a first-class declaration. The host assembles the
    // DesignTokenTree from the plugin's {name→value} seed map and flows it through the one producer
    // gate (validate + seeds/roles authorable surface + persist replace-by-id + register into the
    // catalog's custom layer). Persist-only; the editor applies via selectTheme(id).
    saveTheme: (theme: {
      id: string;
      displayName: string;
      description?: string;
      tokens: Record<string, string>;
      tokensByMode?: { light?: Record<string, string>; dark?: Record<string, string> };
    }): void => {
      const tree: DesignTokenTree = {
        schemaVersion: 1,
        id: theme.id,
        displayName: theme.displayName,
        ...(theme.description !== undefined ? { description: theme.description } : {}),
        tokens: theme.tokens,
        ...(theme.tokensByMode !== undefined ? { tokensByMode: theme.tokensByMode } : {}),
      };
      persistTree(tree, 'saveTheme');
    },
    // Tempdoc 567 §8 (deferred → built) — IMPORT: the counterpart to exportTheme. Parse a JSON theme
    // string, validate it, and persist it through the SAME producer gate as saveTheme (so an imported
    // theme can never carry a derived/non-authorable token — import is not a backdoor around the
    // seeds+roles authorable surface). Rejects a JSON parse error, an invalid tree, or an id that
    // collides with a built-in (a custom entry shadowing a built-in would duplicate in listThemes).
    // Returns the saved id + displayName for caller feedback; throws on any failure.
    importTheme: (json: string): { id: string; displayName: string } => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(json);
      } catch (err) {
        throw new Error(
          `importTheme: not valid JSON — ${err instanceof Error ? err.message : String(err)}`,
        );
      }
      const result = validateDesignTokenTree(parsed);
      if (!result.ok) {
        throw new Error(`importTheme: invalid theme — ${result.errors.join('; ')}`);
      }
      const tree = result.tree;
      // A built-in catalog entry carries no token tree; refuse to shadow its id with a custom theme.
      const existing = getThemeCatalogEntry(tree.id);
      if (existing && existing.tokens === undefined) {
        throw new Error(
          `importTheme: '${tree.id}' is a built-in theme id — change the theme's id before importing`,
        );
      }
      persistTree(tree, 'importTheme');
      return { id: tree.id, displayName: tree.displayName };
    },
    // Tempdoc 567 §8 (deferred → built) — RENAME: change a custom theme's human label only. The id is
    // the stable key (activeThemeId references it; customThemes is keyed by it), so rename never
    // touches the id — there is no dangling-active-theme cascade by construction. Throws if the id is
    // not a known custom theme, or the new label is blank.
    renameTheme: (id: string, displayName: string): void => {
      const label = displayName.trim();
      if (label === '') {
        throw new Error('renameTheme: displayName must be a non-empty string');
      }
      const current = getDocument().customThemes ?? [];
      const tree = current.find((t) => t.id === id);
      if (!tree) {
        throw new Error(`renameTheme: '${id}' is not a custom theme`);
      }
      persistTree({ ...tree, displayName: label }, 'renameTheme');
    },
    deleteTheme: (id: string): void => {
      const wasActive = getActiveThemeId() === id;
      mutateDocument((doc) => ({
        ...doc,
        customThemes: (doc.customThemes ?? []).filter((t) => t.id !== id),
        activeThemeId: doc.activeThemeId === id ? null : doc.activeThemeId,
      }), true);
      syncCustomThemesToCatalog();
      // If the deleted theme was the applied one, revert to default (no dangling activeThemeId).
      if (wasActive) {
        clearActiveTheme();
      }
    },
  };
}
