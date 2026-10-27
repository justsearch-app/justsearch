// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { takePendingAutoRun, takePendingForceShape } from '../utils/compose.js';
import { createCorePluginManifest } from '../plugin-api/CorePlugin.js';
import { __resetForTest, __seedForTest, listSurfaces, mergePluginSurfaceContributions } from '../../api/registry/SurfaceCatalogClient.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import type { SurfaceCatalog } from '../../api/types/surface.js';
import { resolveSurface, setSurfaceAliases } from '../router/catalogResolver.js';
import { __resetInMemoryStateForTest, __resetUserStateForTest, createProfile, getDocument, setActiveProfileId } from './UserStateDocument.js';
import { clearSurfaceOverride, clearSurfaceVisibility, getUserConfig } from './userConfigState.js';
import { getSavedViews, isViewSaved, saveView } from './savedViewState.js';
import { __resetJournalForTest, getJournalState, navigateBack, recordNavigation } from './NavigationJournal.js';

const SEARCH = 'core.search-v3-surface';
const OLD = 'core.unified-chat-surface';
const url = (id: string, state = '') => `justsearch://surface/${id}${state}`;

beforeEach(() => {
  localStorage.clear(); window.location.hash = '';
  __resetUserStateForTest(); __resetJournalForTest(); __resetForTest(); setSurfaceAliases({});
  takePendingAutoRun(); takePendingForceShape();
});
afterEach(() => {
  __resetForTest(); setSurfaceAliases({}); __resetJournalForTest(); __resetUserStateForTest();
  takePendingAutoRun(); takePendingForceShape();
});

describe('WP1.5a window cutover', () => {
  it('C1 promotes exactly one Search rail window and pairs it with Library', () => {
    const surfaces = createCorePluginManifest().capabilities.surfaces!;
    expect(surfaces.find(s => s.id === SEARCH)).toMatchObject({ audience: 'USER', placement: 'RAIL', splitPairing: { secondary: 'core.library-surface' } });
    expect(surfaces.some(s => s.id === OLD)).toBe(false);
    expect(surfaces.find(s => s.id === 'core.library-surface')?.splitPairing?.secondary).toBe(SEARCH);
  });

  it.each([OLD, 'core.search-surface', 'core.agent-surface'])('C2 resolves %s directly to Search', id => {
    __seedForTest({ entries: [{ id: SEARCH }] } as SurfaceCatalog);
    expect(resolveSurface(id)).toMatchObject({ status: 'redirected', id: SEARCH, originalId: id });
  });

  it('C4 normalizes a promoted alias whose target has retired', () => {
    __seedForTest({ entries: [{ id: SEARCH }] } as SurfaceCatalog);
    setSurfaceAliases({ 'core.my-search': { target: OLD, reason: 'renamed' } });
    expect(resolveSurface('core.my-search')).toMatchObject({ status: 'redirected', id: SEARCH });
  });

  it('C2 A5 recognizes and reuses an alias-equivalent bookmark without merging places', () => {
    const first = saveView('My query', url(OLD, '?query=a%20%26%20%E6%97%A5'), OLD)!;
    expect(isViewSaved(url(SEARCH, '?query=a+%26+%E6%97%A5'))).toBe(true);
    expect(saveView('New label', url(SEARCH, '?query=a+%26+%E6%97%A5'), SEARCH)).toEqual(first);
    expect(getSavedViews()).toHaveLength(1);
    expect(isViewSaved(url(SEARCH))).toBe(false);
    saveView('A', url(OLD, '?conversationId=A'), OLD);
    expect(isViewSaved(url(SEARCH, '?conversationId=A'))).toBe(true);
    expect(isViewSaved(url(SEARCH, '?conversationId=B'))).toBe(false);
  });

  it('C3 normalizes saved journal identity and preserves conversation traversal', async () => {
    localStorage.setItem('justsearch.navigationJournal.v1', JSON.stringify({ cursor: 1, entries: [
      { id: 'a', surfaceId: OLD, url: url(OLD, '?conversationId=A'), label: 'A', timestamp: 1, transport: 'BUTTON' },
      { id: 'b', surfaceId: OLD, url: url(OLD, '?conversationId=B'), label: 'B', timestamp: 2, transport: 'BUTTON' },
    ] }));
    expect(getJournalState().entries.map(e => e.surfaceId)).toEqual([SEARCH, SEARCH]);
    const seen: unknown[] = [];
    await navigateBack(async intent => { seen.push(intent.address); });
    expect(seen).toEqual([{ kind: 'navigate', target: SEARCH, state: { conversationId: 'A' } }]);
    recordNavigation(SEARCH, url(SEARCH, '?conversationId=C'), 'C', 'BUTTON');
    expect(getJournalState().entries.map(e => e.url)).toEqual([url(SEARCH, '?conversationId=A'), url(SEARCH, '?conversationId=C')]);
  });

  it('C4 A4 migrates every profile once with explicit Search preferences winning', () => {
    createProfile('other', 'Other');
    const stored = JSON.parse(localStorage.getItem('justsearch.userState.v2')!);
    stored.profiles.default.userConfig = { version: 1, surfaceVisibility: { [OLD]: false, [SEARCH]: true }, surfaceOrder: [OLD, 'core.library-surface', SEARCH], surfaceOverride: { [OLD]: 'vendor.x.old', [SEARCH]: 'vendor.x.new', 'core.library-surface': OLD }, secondaryActiveSurface: OLD };
    stored.profiles.other.userConfig = { version: 1, surfaceVisibility: { [OLD]: false }, surfaceOverride: { [OLD]: SEARCH }, surfaceOrder: [OLD, OLD, 'core.library-surface'] };
    localStorage.setItem('justsearch.userState.v2', JSON.stringify(stored));
    __resetInMemoryStateForTest();
    expect(getUserConfig()).toMatchObject({ surfaceVisibility: { [SEARCH]: true }, surfaceOrder: ['core.library-surface', SEARCH], surfaceOverride: { [SEARCH]: 'vendor.x.new', 'core.library-surface': SEARCH }, secondaryActiveSurface: SEARCH });
    expect(getUserConfig().surfaceVisibility).not.toHaveProperty(OLD);
    setActiveProfileId('other');
    expect(getUserConfig()).toMatchObject({ surfaceVisibility: { [SEARCH]: false }, surfaceOrder: [SEARCH, 'core.library-surface'] });
    expect(getUserConfig().surfaceOverride).toEqual({});
    clearSurfaceVisibility(SEARCH);
    clearSurfaceOverride(SEARCH);
    __resetInMemoryStateForTest();
    expect(getDocument().activeProfileId).toBe('other');
    expect(getUserConfig().surfaceVisibility?.[SEARCH]).toBeUndefined();
  });
});

it('C6 answer and query verbs default to Search and never park an automatic send', async () => {
  const { createIntentRouter } = await import('../router/intentRouter.js');
  const { takePendingAutoRun, takePendingForceShape } = await import('../utils/compose.js');
  const addresses: unknown[] = [];
  const router = createIntentRouter({ navigationHandler: { handle: async address => { addresses.push(address); } },
    invocationHandler: { handle: async () => undefined }, isKnownSurface: () => true,
    resolveSurface: id => ({ status: 'resolved', id }), resolveOperation: id => ({ status: 'resolved', id }) });
  await router.dispatch({ transport: 'URL_DEEPLINK', address: { kind: 'answer', prompt: 'question', shape: 'core.extract', state: {} } });
  expect(addresses[0]).toEqual({ kind: 'navigate', target: SEARCH, state: { query: 'question', forceShapeId: 'core.extract' } });
  expect(takePendingAutoRun()).toBe(false); takePendingForceShape();
  await router.dispatch({ transport: 'URL_DEEPLINK', address: { kind: 'query', query: '日本 & ?', state: {} } });
  expect(addresses[1]).toEqual({ kind: 'navigate', target: SEARCH, state: { query: '日本 & ?' } });
});

it('C2 A5 bookmark identity ignores irrelevant fields after alias resolution', () => {
  __resetUserStateForTest();
  saveView('A', url(OLD, '?conversationId=A&irrelevant=ignored'), OLD);
  expect(isViewSaved(url(SEARCH, '?conversationId=A'))).toBe(true);
});

it('C6 answer preparation follows the resolved target when a sender still names the retired window', async () => {
  const { createIntentRouter } = await import('../router/intentRouter.js');
  const { takePendingAutoRun, takePendingForceShape } = await import('../utils/compose.js');
  const addresses: unknown[] = [];
  const router = createIntentRouter({ answerSurfaceId: OLD,
    navigationHandler: { handle: async address => { addresses.push(address); } },
    invocationHandler: { handle: async () => undefined }, isKnownSurface: () => true,
    resolveSurface: id => ({ status: 'redirected', id: SEARCH, originalId: id, reason: 'renamed' }),
    resolveOperation: id => ({ status: 'resolved', id }) });
  await router.dispatch({ transport: 'URL_DEEPLINK', address: { kind: 'answer', prompt: 'question', shape: 'core.extract', state: { affordance: 'agent' } } });
  expect(addresses[0]).toEqual({ kind: 'navigate', target: SEARCH, state: { affordance: 'agent', query: 'question', forceShapeId: 'core.extract' } });
  expect(takePendingAutoRun()).toBe(false); takePendingForceShape();
});

it('C1 an upgrade catalog cache cannot keep the retired rail window beside Search', () => {
  __resetForTest(); setSurfaceAliases({});
  __seedForTest({ entries: [{ id: OLD, mountTag: 'jf-unified-chat-view', audience: 'USER', placement: 'RAIL' }] } as SurfaceCatalog);
  mergePluginSurfaceContributions(createCorePluginManifest().capabilities.surfaces!.map(contribution => ({
    pluginId: 'core', contribution, effectiveAudience: contribution.audience, provenance: CORE_PROVENANCE,
  })));
  expect(listSurfaces().some(surface => surface.id === OLD)).toBe(false);
  expect(listSurfaces().find(surface => surface.id === SEARCH)).toMatchObject({ audience: 'USER', placement: 'RAIL' });
  expect(resolveSurface(OLD)).toMatchObject({ status: 'redirected', id: SEARCH });
});
