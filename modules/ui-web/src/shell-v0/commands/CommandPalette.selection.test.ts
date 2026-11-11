// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * SP6/SP8 with SP4/SP9 — expanding a group ("Show more …") keeps the selected result by identity,
 * and Enter invokes only that result: never the neighbour that moved into its position.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { CommandPalette } from './CommandPalette.js';
import { registerAction, __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetForTest as resetCommands } from './CommandRegistry.js';
import { __resetForTest as resetBindings } from './KeybindingRegistry.js';
import { __resetShellContextForTest } from '../state/shellContextState.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetInMemoryStateForTest } from '../state/UserStateDocument.js';
import { __resetConversationListForTest, loadConversations } from '../state/conversationListStore.js';
import { __resetAiStateForTest } from '../state/aiStateStore.js';
import { NAVIGATE_TO_SURFACE_EVENT, type NavigateToSurfaceDetail } from '../controllers/navigateRequest.js';

const handlers: ReturnType<typeof vi.fn>[] = [];
let sessions: { sessionId: string; title: string; firstUserMessage: string; lastActiveAt: number }[] = [];
const flush = async (): Promise<void> => { for (let i = 0; i < 40; i++) await Promise.resolve(); };

beforeEach(() => {
  localStorage.clear(); sessionStorage.clear();
  __resetInMemoryStateForTest(); __resetShellContextForTest(); __resetActionsForTest(); resetCommands(); resetBindings();
  __resetConversationListForTest(); __resetAiStateForTest();
  handlers.length = 0; sessions = [];
  // More actions than the first rendering bound, so the Actions group above Settings can grow.
  for (let index = 0; index < 12; index++) {
    const handler = vi.fn(() => ({ kind: 'noop' as const }));
    handlers.push(handler);
    registerAction({ id: `go-${index}`, title: `Go to Place ${String(index).padStart(2, '0')}`, provenance: CORE_PROVENANCE, handler });
  }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
    const url = String(input);
    if (url.includes('/api/indexing-roots/substrate')) return Response.json({ items: [] });
    if (url.includes('/api/chat/conversations?')) return Response.json({ sessions });
    if (url.includes('/api/settings/v2')) return Response.json({ ui: {}, settingsMode: 'read_write' });
    return Response.json({});
  });
});
afterEach(() => { document.body.replaceChildren(); vi.restoreAllMocks(); });

async function openPalette(): Promise<CommandPalette> {
  const palette = new CommandPalette(); document.body.append(palette); palette.show(); await flush(); await palette.updateComplete;
  return palette;
}
function selectedRow(palette: CommandPalette): string | undefined {
  return palette.shadowRoot!.querySelector('[data-selected] .result-label')?.textContent?.trim();
}
async function showMoreActions(palette: CommandPalette): Promise<void> {
  const button = [...palette.shadowRoot!.querySelectorAll('button')].find(b => b.textContent?.includes('Show more actions'));
  expect(button).toBeDefined();
  button!.click(); await palette.updateComplete;
}
async function enter(palette: CommandPalette): Promise<void> {
  palette.shadowRoot!.querySelector('input')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
  await flush();
}

it('SP6-SP8 Show more keeps the selected Settings row by identity and Enter opens that row', async () => {
  const palette = await openPalette();
  // The Settings category row "General" (its label is a catalog key when no words are seeded).
  const id = 'settings-row:general:';
  const general = palette.results.findIndex(row => row.entry.id === id);
  expect(general).toBeGreaterThan(0);
  const label = palette.results[general]!.entry.label;
  palette.selectedIndex = general; await palette.updateComplete;
  expect(selectedRow(palette)).toBe(label);

  await showMoreActions(palette);
  expect(palette.results.filter(row => row.entry.group === 'Actions')).toHaveLength(12);
  expect(palette.results[palette.selectedIndex]?.entry.id).toBe(id);
  expect(selectedRow(palette)).toBe(label);

  const arrivals: NavigateToSurfaceDetail[] = [];
  const listen = (e: Event): void => { arrivals.push((e as CustomEvent<NavigateToSurfaceDetail>).detail); };
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listen);
  try { await enter(palette); } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listen); }
  expect(arrivals).toEqual([{ surfaceId: 'core.settings-surface', state: { section: 'general' } }]);
  for (const handler of handlers) expect(handler).not.toHaveBeenCalled();
});

it('SP4-SP9 a selection that disappears after Show more leaves no selection, and Enter invokes nothing', async () => {
  sessions = [{ sessionId: 'conv-a', title: 'Conversation A', firstUserMessage: 'Question A', lastActiveAt: 1 }];
  await loadConversations();
  const palette = await openPalette();
  palette.selectedIndex = palette.results.findIndex(row => row.entry.id === 'conversation:conv-a');
  expect(palette.selectedIndex).toBeGreaterThan(0);
  await showMoreActions(palette);
  expect(palette.results[palette.selectedIndex]?.entry.id).toBe('conversation:conv-a');
  sessions = [];
  await loadConversations(); await flush(); await palette.updateComplete;
  expect(palette.results.some(row => row.entry.id === 'conversation:conv-a')).toBe(false);
  expect(palette.shadowRoot!.querySelector('[data-selected]')).toBeNull();

  const arrivals: unknown[] = [];
  const listen = (e: Event): void => { arrivals.push((e as CustomEvent).detail); };
  document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, listen);
  try { await enter(palette); } finally { document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, listen); }
  expect(arrivals).toEqual([]);
  for (const handler of handlers) expect(handler).not.toHaveBeenCalled();
});
