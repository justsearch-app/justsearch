// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import '../shell-v0/views/LibrarySurface.js';
import '../shell-v0/views/BrainSurface.js';
import '../shell-v0/views/MemorySurface.js';
import '../shell-v0/views/BrowseSurface.js';
import '../shell-v0/views/SettingsSurface.js';
import '../shell-v0/views/HealthSurface.js';
import '../shell-v0/views/UnifiedChatView.js';
import '../shell-v0/views/ApiExplorerView.js';
import { __feedForTest, __tickClockForTest, __resetAiStateForTest, type StatusSnapshot } from '../shell-v0/state/aiStateStore.js';
import { __seedForTest as seedResources, __resetForTest as resetResources } from '../api/registry/ResourceCatalogClient.js';
import { __resetForTest as resetPaths } from '../shell-v0/hooks/resolvePathLazy.js';
import type { PluginHostApi } from '../shell-v0/plugin-api/plugin-types.js';
import { createMockHostApi } from '../shell-v0/plugin-api/testHostApi.js';
import type { Resource } from '../api/types/registry.js';
import '../shell-v0/chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../api/registry/SurfaceCatalogClient.js';
import { __resetSurfaceSchemasForTest } from '../shell-v0/router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../shell-v0/router/storeRegistry.js';
import { __resetBootstrapForTest } from '../shell-v0/router/bootstrap.js';
import { __resetUserConfigForTest } from '../shell-v0/state/userConfigState.js';
import { deactivateProjection } from '../shell-v0/router/URLProjector.js';
import type { SurfaceCatalog } from '../api/types/surface.js';

const root = resolve(import.meta.dirname, '../../../..');
const api = resolve(root, 'modules/ui/src/main/java/io/justsearch/ui/api');
const roles = new Map<string, string>();
const constants = new Map<string, string>();
for (const entry of readdirSync(api, { recursive: true }).filter((name) => String(name).endsWith('.java'))) {
  const source = readFileSync(resolve(api, String(entry)), 'utf8');
  const className = String(entry).split(/[\\/]/).at(-1)!.replace('.java', '');
  for (const constant of source.matchAll(/String\s+(\w+)\s*=\s*"([^"]+)"/g)) constants.set(`${className}.${constant[1]}`, constant[2]!);
}
for (const entry of readdirSync(api, { recursive: true }).filter((name) => String(name).endsWith('.java'))) {
  const source = readFileSync(resolve(api, String(entry)), 'utf8');
  for (const m of source.matchAll(/app\.(get|post|sse|head|options)\(\s*"([^"]+)"[\s\S]*?ActivityRole\.(\w+)\)/g)) {
    roles.set(`${m[1] === 'sse' ? 'GET' : m[1]!.toUpperCase()} ${m[2]}`, m[3]!);
  }
  for (const m of source.matchAll(/app\.(get|post|sse|head|options)\(\s*(\w+\.\w+)\s*,[\s\S]*?ActivityRole\.(\w+)\)/g)) {
    const path = constants.get(m[2]!);
    if (path) roles.set(`${m[1] === 'sse' ? 'GET' : m[1]!.toUpperCase()} ${path}`, m[3]!);
  }
}
function roleOf(method: string, path: string): string | undefined {
  const direct = roles.get(`${method} ${path}`);
  if (direct) return direct;
  for (const [operation, role] of roles) {
    const template = operation.split(' ')[1]!;
    const regex = '^' + template.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{[^}]+\}/g, '[^/]+') + '$';
    if (operation.startsWith(method + ' ') && new RegExp(regex).test(path)) return role;
  }
  return undefined;
}

afterEach(() => {
  document.body.replaceChildren();
  __resetAiStateForTest(); resetResources(); resetPaths();
  vi.unstubAllGlobals(); vi.useRealTimers();
});

it('mount, render, timers, and corpus-growth updates send only observations with no person acting', async () => {
  __resetAiStateForTest(); resetPaths();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  const calls: Array<{ method: string; path: string }> = [];
  const hash = 'a'.repeat(64);
  seedResources({ schemaVersion: '1.0', catalogVersion: 1, namespace: 'core', primitive: 'Resource', entries: [{
    id: 'core.indexed-roots', primaryKey: 'pathHash',
    privacy: { pathPolicy: 'HASHED_REQUIRES_RESOLVER', resolver: 'core.resolve-path-hash', loopbackOnly: true },
  } as Resource] });
  const fetcher = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(input), 'http://idle.test').pathname;
    calls.push({ method: init?.method ?? 'GET', path });
    let body: unknown = {};
    if (path === '/api/indexing-roots/substrate') body = { items: [{ pathHash: hash, collection: 'folder', inFlightCount: 3, fileCount: 4 }] };
    if (path === '/api/library/path-label') body = { found: true, path: 'C:/fixture/folder' };
    if (path === '/api/knowledge/collection-facet' || path === '/api/knowledge/search') body = { facets: { collection: { folder: 4, imported: 2 } } };
    if (path === '/api/indexing/roots') body = { roots: [{ path: 'C:/fixture/folder' }] };
    if (path === '/api/memory') body = { items: [] };
    if (path === '/api/condition-recovery-index') body = { entries: [] };
    if (path === '/api/settings/v2') body = { ui: {}, llm: {} };
    if (path === '/api/chat/conversations') body = { sessions: [] };
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetcher);
  vi.stubGlobal('EventSource', class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1; onopen = null; onerror = null; onmessage = null;
    constructor(url: string) { calls.push({ method: 'GET', path: new URL(url, 'http://idle.test').pathname }); }
    addEventListener(): void {} removeEventListener(): void {} close(): void {}
  });
  const host = createMockHostApi({
    platform: { capabilities: new Set<string>() },
    utilities: { formatRelativeTime: () => 'just now' },
    ui: { showConfirmDialog: vi.fn() },
    data: { fetch: fetcher as PluginHostApi['data']['fetch'],
      invokeOperation: async (id, input) => {
        await fetcher(`/api/operations/${id}/invoke`, { method: 'POST', body: JSON.stringify(input) });
        return { success: true };
      } },
  });
  const elements: Array<HTMLElement & { host_: PluginHostApi; apiBase: string; updateComplete: Promise<unknown> }> = [];
  for (const tag of ['jf-library-surface', 'jf-brain-surface', 'jf-memory-surface', 'jf-browse-surface',
    'jf-settings-surface', 'jf-health-surface', 'jf-unified-chat-view', 'jf-api-explorer-view']) {
    const element = document.createElement(tag) as typeof elements[number];
    element.host_ = host; element.apiBase = '';
    elements.push(element); document.body.appendChild(element);
  }
  async function settle(): Promise<void> {
    for (let i = 0; i < 10; i++) {
      await Promise.all(elements.map((element) => element.updateComplete));
      await new Promise((done) => setTimeout(done, 0));
    }
  }
  await settle();
  const mountCount = calls.filter((call) => call.path.includes('/knowledge/')).length;
  expect(mountCount, 'the Library must really issue its mount-time facet probe').toBeGreaterThan(0);
  __feedForTest({ status: { worker: { core: { indexedDocuments: 900_000, searchableDocuments: 900_000,
    indexState: 'INDEXING', indexHealthy: true } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  __tickClockForTest(); await settle();
  expect(calls.filter((call) => call.path.includes('/knowledge/')).length,
    'corpus growth must really issue a second facet probe').toBeGreaterThan(mountCount);
  await vi.advanceTimersByTimeAsync(10_000);
  expect(calls.some((call) => call.path === '/api/inference/transitions'), 'Brain timer must really fire').toBe(true);
  expect(calls.some((call) => call.path === '/api/library/path-label' || call.path.includes('core.resolve-path-hash')),
    'nonempty rows must exercise path-label hydration').toBe(true);
  expect(calls.filter((call) => roleOf(call.method, call.path) !== 'OBSERVATION')
    .map((call) => `${call.method} ${call.path}: ${roleOf(call.method, call.path)}`),
    'Every unattended request, including the mount and growth facet probes, must declare OBSERVATION').toEqual([]);
});

it('an untouched shell open on Library: mount, reload, timers, streams, status delivery and a resting pointer send no USER_ACTION request and no presence signal', async () => {
  __resetAiStateForTest(); resetPaths(); resetSurfaceCatalog(); __resetUserConfigForTest();
  __resetStoreRegistryForTest(); __resetSurfaceSchemasForTest(); __resetBootstrapForTest();
  deactivateProjection();
  window.location.hash = '';
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  const calls: Array<{ method: string; path: string }> = [];
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [{
      id: 'core.library-surface',
      presentation: { labelKey: 'registry-surface.library-surface.label', descriptionKey: 'registry-surface.library-surface.description' },
      audience: 'USER', placement: 'RAIL', mountTag: 'jf-library-surface',
      consumes: { operations: [], resources: ['core.indexed-roots'], prompts: [], diagnosticChannels: [] },
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
    }],
  };
  seedSurfaceCatalog(catalog);
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(input), 'http://idle.test').pathname;
    calls.push({ method: (init?.method ?? 'GET').toUpperCase(), path });
    const body = path === '/api/indexing-roots/substrate' ? { items: [] }
      : path === '/api/knowledge/collection-facet' ? { facets: { collection: { folder: 4 } } }
        : { entries: [] };
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
  // Every stream the shell opens, so the test can deliver to it what the server would.
  const streams: Array<{ listeners: Map<string, EventListener[]>; onmessage: ((e: MessageEvent) => void) | null }> = [];
  vi.stubGlobal('EventSource', class {
    static CONNECTING = 0; static OPEN = 1; static CLOSED = 2;
    readyState = 1; onopen = null; onerror = null; onmessage: ((e: MessageEvent) => void) | null = null;
    listeners = new Map<string, EventListener[]>();
    constructor(url: string) {
      calls.push({ method: 'GET', path: new URL(url, 'http://idle.test').pathname });
      streams.push(this);
    }
    addEventListener(type: string, listener: EventListener): void {
      this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
    }
    removeEventListener(): void {}
    close(): void { this.readyState = 2; }
  });
  type Mounted = HTMLElement & { apiBase: string; updateComplete: Promise<unknown> };
  let shell: Mounted | null = null;
  async function settle(): Promise<void> {
    for (let i = 0; i < 10; i++) {
      await shell?.updateComplete;
      await new Promise((done) => setTimeout(done, 0));
    }
  }
  async function mount(): Promise<Mounted> {
    const mounted = document.createElement('jf-shell') as Mounted;
    mounted.apiBase = '';
    shell = mounted;
    document.body.appendChild(mounted);
    await settle();
    return mounted;
  }
  const presence = (): Array<{ method: string; path: string }> => calls.filter((call) => call.path === '/api/ui/activity');

  const first = await mount();
  expect(calls.length, 'the shell must really issue its mount-time requests').toBeGreaterThan(0);
  // Reload: the page goes away and an untouched one mounts in its place.
  first.remove();
  const current = await mount();
  expect(document.querySelectorAll('jf-shell').length).toBe(1);
  // Timers: well past the status, staleness and auto-lock intervals.
  await vi.advanceTimersByTimeAsync(10 * 60_000);
  await settle();
  // Streams: the server's connect burst and heartbeats on every stream the shell holds open.
  expect(streams.length, 'the shell must really open its streams').toBeGreaterThan(0);
  const frame = (payload: unknown, seq: number): MessageEvent => new MessageEvent('frame', {
    data: JSON.stringify({ streamId: 'surface:indexing-jobs', frameKind: 'LIFECYCLE', seq,
      ts: new Date(0).toISOString(), payload, resumeToken: `surface:indexing-jobs:${seq}` }),
  });
  for (const stream of streams) {
    for (const listener of stream.listeners.get('open') ?? []) listener(new Event('open'));
    for (const [index, kind] of ['connected', 'snapshot', 'heartbeat'].entries()) {
      for (const listener of stream.listeners.get('frame') ?? []) listener(frame({ kind }, index + 1));
      for (const listener of stream.listeners.get('message') ?? []) listener(new MessageEvent('message', { data: '{}' }));
      stream.onmessage?.(new MessageEvent('message', { data: '{}' }));
    }
  }
  await settle();
  // Status delivery: the corpus grows while nobody watches.
  __feedForTest({ status: { worker: { core: { indexedDocuments: 900_000, searchableDocuments: 900_000,
    indexState: 'INDEXING', indexHealthy: true } },
    readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } as unknown as StatusSnapshot });
  __tickClockForTest();
  await settle();
  // A pointer resting over the window while content changes under it: the browser delivers trusted
  // boundary and move events although nobody is acting.
  for (const type of ['pointerover', 'pointerenter', 'pointermove', 'mouseover', 'mouseenter', 'mousemove']) {
    const event = type.startsWith('pointer')
      ? new PointerEvent(type, { bubbles: true, composed: true })
      : new MouseEvent(type, { bubbles: true, composed: true });
    Object.defineProperty(event, 'isTrusted', { value: true });
    current.dispatchEvent(event);
  }
  await settle();
  expect(presence(), 'an untouched UI sends no presence signal').toEqual([]);
  expect(calls.filter((call) => roleOf(call.method, call.path) === 'USER_ACTION')
    .map((call) => `${call.method} ${call.path}`), 'an untouched UI sends no USER_ACTION request').toEqual([]);
  // The harness does observe the signal: one key press a person makes sends exactly one.
  const press = new KeyboardEvent('keydown', { bubbles: true, composed: true, key: 'a' });
  Object.defineProperty(press, 'isTrusted', { value: true });
  current.dispatchEvent(press);
  await settle();
  expect(presence()).toEqual([{ method: 'POST', path: '/api/ui/activity' }]);
});
