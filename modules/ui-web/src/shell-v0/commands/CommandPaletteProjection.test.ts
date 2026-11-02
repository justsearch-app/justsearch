// @vitest-environment happy-dom
import { takeIndexingRuleFocus } from '../router/indexingRuleIntent.js';

/**
 * Tempdoc 543 §21.C — palette layers over Action.listActions.
 *
 * Verifies the projection:
 *  - global Actions surface in the palette
 *  - non-global Actions (with appliesTo) are excluded
 *  - legacy Commands still surface for back-compat
 *  - Action ids are re-mapped to legacy 'shell.*' / 'op.*' so that
 *    keybinding + recent-id persistence stay back-compatible
 *  - origin discriminator is set correctly
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  registerAction,
  __resetActionsForTest,
  projectOperationsToActions,
  registerShellActions,
} from '../substrates/actions/index.js';
import {
  registerCommand,
  commandLabel,
  invokeCommandWithResult,
  __resetForTest as __resetCommandsForTest,
} from './CommandRegistry.js';
import { searchPaletteEntries } from './CommandPaletteProjection.js';
import { __resetShellContextForTest } from '../state/shellContextState.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import {
  __seedForTest as __seedResourceCatalog,
  __resetForTest as __resetResourceCatalog,
} from '../../i18n/resourceCatalog.js';

describe('CommandPaletteProjection (§21.C)', () => {
  beforeEach(() => {
    __resetActionsForTest();
    __resetCommandsForTest();
    __resetShellContextForTest();
    __resetResourceCatalog();
  });

  it('projects global Actions into PaletteEntry shape', () => {
    registerAction({
      id: 'core.action.shell.toggle-palette',
      title: 'Toggle Command Palette',
      category: 'Navigation',
      shortcut: 'mod+k',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    const results = searchPaletteEntries('toggle');
    const hit = results.find((r) => r.entry.id === 'shell.toggle-palette');
    expect(hit).toBeDefined();
    expect(hit!.entry.label).toBe('Toggle Command Palette');
    expect(hit!.entry.shortcut).toBe('mod+k');
    expect(hit!.entry.category).toBe('Navigation');
    expect(hit!.entry.origin).toBe('action');
  });

  it('excludes non-global Actions (appliesTo set)', () => {
    registerAction({
      id: 'core.action.contextual.copy',
      title: 'Copy',
      appliesTo: ['search-result'],
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    const results = searchPaletteEntries('copy');
    expect(results.find((r) => r.entry.id === 'core.action.contextual.copy')).toBeUndefined();
  });

  it('WP3.8b opens indexing rules in Settings instead of invoking the operation without arguments', async () => {
    __seedResourceCatalog({ 'ops.set-indexing-rules.label': 'Set indexing rules' });
    registerShellActions({
      navigate: () => {}, toggleInspector: () => {}, togglePalette: () => {}, focusComposer: () => {},
    });
    projectOperationsToActions([
      { id: 'core.set-indexing-rules', presentation: { labelKey: 'ops.set-indexing-rules.label' } },
    ]);
    const navigation = vi.fn();
    const operation = vi.fn();
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, navigation);
    document.addEventListener('jf-invoke-operation', operation);
    try {
      const entries = searchPaletteEntries('indexing rules').filter(r => r.entry.origin !== 'intent');
      expect(entries).toHaveLength(1);
      expect((await invokeCommandWithResult(entries[0]!.entry.id)).ok).toBe(true);
      expect(operation).not.toHaveBeenCalled();
      expect(entries[0]!.entry.label).toBe('Change indexing rules');
      expect(navigation).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
        detail: { surfaceId: 'core.library-surface' },
      }));
      expect(takeIndexingRuleFocus()).toBe('hours');
      expect(searchPaletteEntries('').some(r => r.entry.id === 'op.core.set-indexing-rules')).toBe(false);
      await invokeCommandWithResult('op.core.set-indexing-rules');
      expect(operation).not.toHaveBeenCalled();
    } finally {
      document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, navigation);
      document.removeEventListener('jf-invoke-operation', operation);
    }
  });

  it('back-maps core.action.op.* to legacy op.* id', () => {
    registerAction({
      id: 'core.action.op.search.reset',
      title: 'Reset Search',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    const results = searchPaletteEntries('reset');
    const hit = results.find((r) => r.entry.id === 'op.search.reset');
    expect(hit).toBeDefined();
    expect(hit!.entry.origin).toBe('action');
  });

  it('includes legacy Commands alongside Actions', () => {
    registerCommand({
      id: 'template.foo',
      label: 'Foo Template',
      source: 'plugin',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    const results = searchPaletteEntries('foo');
    const hit = results.find((r) => r.entry.id === 'template.foo');
    expect(hit).toBeDefined();
    expect(hit!.entry.origin).toBe('command');
  });

  it('557 P2 — commandLabel localizes a labelKey through the display projector', () => {
    __seedResourceCatalog({ 'plugin.cmd.greet': 'Say Hello' });
    expect(commandLabel({ label: 'raw-fallback', labelKey: 'plugin.cmd.greet' })).toBe('Say Hello');
  });

  it('557 P2 — commandLabel falls back to the raw label when no labelKey', () => {
    expect(commandLabel({ label: 'Raw Label' })).toBe('Raw Label');
  });

  it('557 P2 — a Command with labelKey projects the localized label into the palette', () => {
    __seedResourceCatalog({ 'plugin.cmd.translateme': 'Translated Command' });
    registerCommand({
      id: 'plugin.localized',
      label: 'untranslated',
      labelKey: 'plugin.cmd.translateme',
      source: 'plugin',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    const results = searchPaletteEntries('Translated');
    const hit = results.find((r) => r.entry.id === 'plugin.localized');
    expect(hit).toBeDefined();
    expect(hit!.entry.label).toBe('Translated Command');
  });

  it('§8 v0 — surfaces a search-intent entry for non-empty free text', () => {
    const results = searchPaletteEntries('kubernetes errors');
    const hit = results.find((r) => r.entry.id === 'intent.search');
    expect(hit).toBeDefined();
    expect(hit!.entry.origin).toBe('intent');
    expect(hit!.entry.label).toContain('kubernetes errors');
    expect(hit!.entry.intentText).toBe('kubernetes errors');
  });

  it('§8 v0 — no search-intent entry for an empty query', () => {
    const results = searchPaletteEntries('');
    expect(results.find((r) => r.entry.id === 'intent.search')).toBeUndefined();
  });

  it('§8 v0 — search-intent entry is appended last (commands keep ranking)', () => {
    registerAction({
      id: 'core.action.shell.search-thing',
      title: 'Search Thing',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    const results = searchPaletteEntries('search');
    // The fuzzy-matched command outranks the synthetic fallback.
    expect(results.length).toBeGreaterThan(1);
    expect(results.at(-1)?.entry.id).toBe('intent.search');
  });

  it('preserves Action over legacy Command when both share the projected id', () => {
    registerAction({
      id: 'core.action.shell.toggle-palette',
      title: 'Toggle Palette (Action)',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    registerCommand({
      id: 'shell.toggle-palette',
      label: 'Toggle Palette (Legacy)',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    const results = searchPaletteEntries('toggle');
    const hits = results.filter((r) => r.entry.id === 'shell.toggle-palette');
    expect(hits.length).toBeGreaterThanOrEqual(1);
    // Both origins coexist intentionally — back-compat path means a plugin
    // can still wedge a legacy Command in. The Action wins at invoke time
    // via resolveActionIdFromCommandId.
    expect(hits.some((h) => h.entry.origin === 'action')).toBe(true);
  });

  // Tempdoc 804 §B9 (round-10 F10) — the palette matched leading tokens only: `repair` found
  // "Repair AI Install", but `install ai` (the most natural phrasing for the flagship first-run
  // action) returned NO action at all, only the raw-search intent. The query's space could never
  // be matched after consuming `install` at the end of the label.
  describe('multi-word queries match non-leading tokens (F10)', () => {
    const registerAiInstallActions = (): void => {
      registerAction({
        id: 'core.action.shell.go-to-brain-install',
        title: 'Start AI Install',
        provenance: CORE_PROVENANCE,
        handler: () => ({ kind: 'noop' as const }),
      });
      registerAction({
        id: 'core.action.shell.go-to-brain-repair',
        title: 'Repair AI Install',
        provenance: CORE_PROVENANCE,
        handler: () => ({ kind: 'noop' as const }),
      });
    };

    it('ranks the AI-install actions above the raw-search intent for "install ai"', () => {
      registerAiInstallActions();
      const results = searchPaletteEntries('install ai');

      const labels = results.map((r) => r.entry.label);
      expect(labels).toContain('Start AI Install');
      expect(labels).toContain('Repair AI Install');

      const searchIntentIdx = results.findIndex((r) => r.entry.origin === 'intent');
      expect(searchIntentIdx).toBeGreaterThan(-1);
      expect(results.findIndex((r) => r.entry.label === 'Start AI Install')).toBeLessThan(
        searchIntentIdx,
      );
      expect(results.findIndex((r) => r.entry.label === 'Repair AI Install')).toBeLessThan(
        searchIntentIdx,
      );
    });

    it('still matches the single-token query "repair" (no regression)', () => {
      registerAiInstallActions();
      const results = searchPaletteEntries('repair');
      expect(results.map((r) => r.entry.label)).toContain('Repair AI Install');
    });

    it('requires EVERY token to be present (a token the label lacks still misses)', () => {
      registerAiInstallActions();
      const results = searchPaletteEntries('install zzz');
      expect(results.map((r) => r.entry.label)).not.toContain('Start AI Install');
    });
  });
});
