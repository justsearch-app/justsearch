// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * The web UI reports a person acting through one USER_ACTION request, `POST /api/ui/activity`.
 * These checks drive the real Shell: input a person makes reaches its document listener through the
 * shadow roots the app's controls live in. The browser marks a person's input trusted, and nothing a
 * page dispatches itself is; `isTrusted` is set on the event to model that.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import '../shell-v0/chrome/Shell.js';
import {
  __resetForTest as resetSurfaceCatalog,
  __seedForTest as seedSurfaceCatalog,
} from '../api/registry/SurfaceCatalogClient.js';
import { __resetSurfaceSchemasForTest } from '../shell-v0/router/surfaceSchemas.js';
import { __resetStoreRegistryForTest } from '../shell-v0/router/storeRegistry.js';
import { __resetBootstrapForTest } from '../shell-v0/router/bootstrap.js';
import { __resetUserConfigForTest } from '../shell-v0/state/userConfigState.js';
import { __resetAiStateForTest } from '../shell-v0/state/aiStateStore.js';
import { deactivateProjection } from '../shell-v0/router/URLProjector.js';
import type { SurfaceCatalog } from '../api/types/surface.js';

interface ShellElement extends HTMLElement { apiBase: string; updateComplete: Promise<void> }

let calls: Array<{ method: string; path: string; body?: unknown }> = [];
let shell: ShellElement;
let inside: HTMLElement;
let now = Date.UTC(2026, 9, 12, 8, 0, 0);

const presence = (): number =>
  calls.filter((call) => call.method === 'POST' && call.path === '/api/ui/activity').length;

function event(type: string): Event {
  if (type.startsWith('key')) return new KeyboardEvent(type, { bubbles: true, composed: true, key: 'PageDown' });
  if (type === 'wheel') return new WheelEvent(type, { bubbles: true, composed: true, deltaY: 120 });
  if (type.startsWith('pointer')) return new PointerEvent(type, { bubbles: true, composed: true });
  if (type.startsWith('mouse') || type === 'click') return new MouseEvent(type, { bubbles: true, composed: true });
  return new Event(type, { bubbles: true, composed: true });
}

function person(type: string): void {
  const e = event(type);
  Object.defineProperty(e, 'isTrusted', { value: true });
  inside.dispatchEvent(e);
}

/** Lets each request the listener made complete. Only the clock is faked, so no presence timer fires. */
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) await new Promise((r) => setTimeout(r, 0));
  await shell.updateComplete;
}

/** Moves the clock past the presence interval without running any timer. */
function later(ms: number): void {
  now += ms;
  vi.setSystemTime(now);
}

beforeEach(async () => {
  calls = [];
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(now);
  resetSurfaceCatalog();
  __resetUserConfigForTest();
  __resetStoreRegistryForTest();
  __resetSurfaceSchemasForTest();
  __resetBootstrapForTest();
  __resetAiStateForTest();
  deactivateProjection();
  window.location.hash = '';
  const catalog: SurfaceCatalog = {
    schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface',
    entries: [{
      id: 'core.search-surface',
      presentation: { labelKey: 'search.label', descriptionKey: 'search.description' },
      audience: 'USER', placement: 'RAIL', mountTag: 'jf-search-surface',
      consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
      provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' },
    }],
  };
  seedSurfaceCatalog(catalog);
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({
      method: (init?.method ?? 'GET').toUpperCase(),
      path: new URL(String(input), 'http://ui.test').pathname,
      ...(typeof init?.body === 'string' ? { body: JSON.parse(init.body) as unknown } : {}),
    });
    return new Response(JSON.stringify({ entries: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  }));
  shell = document.createElement('jf-shell') as ShellElement;
  shell.apiBase = '';
  document.body.appendChild(shell);
  await shell.updateComplete;
  await new Promise((r) => setTimeout(r, 30));
  await shell.updateComplete;
  // A control inside a shadow root, as every control of the app is.
  const host = document.createElement('div');
  host.className = 'presence-probe';
  document.body.appendChild(host);
  inside = document.createElement('button');
  host.attachShadow({ mode: 'open' }).appendChild(inside);
});

afterEach(() => {
  document.querySelectorAll('jf-shell, .presence-probe').forEach((element) => element.remove());
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

it("a person's key press, click, scroll and touch each send the presence signal at once", async () => {
  expect(presence(), 'mounting sends no presence signal').toBe(0);
  const gestures: Array<[string, string[]]> = [
    ['key press', ['keydown', 'keyup']],
    ['click', ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']],
    ['wheel scroll', ['wheel']],
    ['touch', ['touchstart', 'touchend']],
  ];
  let expected = 0;
  for (const [name, events] of gestures) {
    later(10_000);
    for (const type of events) person(type);
    await settle();
    expected += 1;
    expect(presence(), `${name}: exactly one prompt presence signal`).toBe(expected);
  }
});

it('synthetic events, hover and pointer movement send no presence signal', async () => {
  for (const type of ['keydown', 'pointerdown', 'mousedown', 'click', 'wheel', 'touchstart', 'input']) {
    later(10_000);
    inside.dispatchEvent(event(type));
  }
  for (const type of ['pointerover', 'pointerenter', 'pointermove', 'mouseover', 'mouseenter', 'mousemove', 'pointerout', 'mouseout']) {
    later(10_000);
    person(type);
  }
  await settle();
  expect(presence()).toBe(0);
});

it('continuous input sends at most one presence signal per interval', async () => {
  for (let i = 0; i < 50; i++) {
    person(i % 2 === 0 ? 'keydown' : 'wheel');
    await settle();
    later(200);
  }
  // Ten seconds of typing and scrolling at the three-second interval: at most four leading reports.
  expect(presence()).toBeGreaterThanOrEqual(1);
  expect(presence()).toBeLessThanOrEqual(4);
});

it('the presence signal says how long ago the input it reports happened', async () => {
  const bodies = (): unknown[] =>
    calls.filter((call) => call.method === 'POST' && call.path === '/api/ui/activity').map((call) => call.body);
  later(10_000);
  person('keydown');
  await settle();
  expect(bodies(), 'the first input is reported at once, as happening now').toEqual([{ inputAgeMs: 0 }]);
  // Another key press 1 s later is reported when the interval ends, 2 s after the first report.
  later(1_000);
  person('keydown');
  later(1_500);
  await new Promise((r) => setTimeout(r, 2_200));
  await settle();
  expect(bodies(), 'the end-of-interval report places the input 1.5 s before it was sent')
    .toEqual([{ inputAgeMs: 0 }, { inputAgeMs: 1_500 }]);
});
