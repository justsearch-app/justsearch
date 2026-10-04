// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './SettingsSurface.js';
import { createMockHostApi } from '../plugin-api/testHostApi.js';
import {
  __resetUiModeForTest,
  enqueueUiModePersistence,
  getUiMode,
  setUiMode,
  UI_MODE_INTENT_HEADER,
} from '../state/uiModeState.js';

interface SettingsHost extends HTMLElement {
  host_?: unknown;
  ui: { mode?: 'simple' | 'advanced' };
  updateComplete: Promise<unknown>;
}

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const INITIAL_WITNESS = { acceptedRevision: 0, lastCommittedOperationKey: null };

function completeSettings(init?: { body?: unknown; method?: string }): Response {
  const request = JSON.parse(String(init?.body ?? '{}')) as {
    operationKey?: string;
    witness?: { acceptedRevision?: number };
  };
  return new Response(JSON.stringify({
    state: 'COMPLETE',
    operationKey: request.operationKey,
    witness: {
      acceptedRevision: (request.witness?.acceptedRevision ?? 0) + 1,
      lastCommittedOperationKey: request.operationKey,
    },
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

function settingsSnapshot(): Response {
  return new Response(JSON.stringify({ ui: { mode: 'simple' }, llm: {}, witness: INITIAL_WITNESS }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
}

beforeEach(() => {
  __resetUiModeForTest();
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) =>
    init?.method === 'POST' ? completeSettings(init) : settingsSnapshot()));
});

afterEach(() => {
  document.body.innerHTML = '';
  __resetUiModeForTest();
  vi.unstubAllGlobals();
});

describe('SettingsSurface shared detail level', () => {
  it('does not let a delayed settings load overwrite a newer shared choice', async () => {
    let resolveSettings!: (response: Response) => void;
    let settingsRequested = false;
    const settingsResponse = new Promise<Response>((resolve) => {
      resolveSettings = resolve;
    });
    const el = document.createElement('jf-settings-surface') as SettingsHost;
    el.host_ = createMockHostApi({
      data: {
        fetch: (path: string, init?: { method?: string }) => {
          if (path === '/api/settings/v2' && init?.method !== 'POST') {
            settingsRequested = true;
            return settingsResponse;
          }
          return Promise.resolve(init?.method === 'POST' ? completeSettings(init) : settingsSnapshot());
        },
      },
    });
    document.body.appendChild(el);
    await Promise.resolve();
    expect(settingsRequested).toBe(true);

    // Simulate a later top-bar/Brain choice while Settings' mount-time GET is still in flight.
    setUiMode('advanced');
    resolveSettings(
      new Response(JSON.stringify({ ui: { mode: 'simple' }, witness: INITIAL_WITNESS }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    await new Promise((resolve) => setTimeout(resolve, 0));
    await el.updateComplete;

    expect(getUiMode()).toBe('advanced');
    expect(el.ui.mode).toBe('advanced');
  });

  it('queues its mode POST behind an earlier mode write from another projection', async () => {
    const posts: Array<{ body: string; intent: string | null }> = [];
    const el = document.createElement('jf-settings-surface') as SettingsHost;
    el.host_ = createMockHostApi({
      data: {
        fetch: (path: string, init?: {
          method?: string;
          body?: string | object;
          headers?: Record<string, string>;
        }) => {
          if (path === '/api/settings/v2' && init?.method === 'POST') {
            posts.push({
              body: String(init.body),
              intent: new Headers(init.headers).get(UI_MODE_INTENT_HEADER),
            });
            return Promise.resolve(completeSettings(init));
          }
          return Promise.resolve(path === '/api/settings/v2'
            ? settingsSnapshot()
            : new Response('{}', { status: 200 }));
        },
      },
    });
    document.body.appendChild(el);
    await new Promise((resolve) => setTimeout(resolve, 0));

    let release!: (response: Response) => void;
    const blocker = enqueueUiModePersistence(
      () => new Promise<Response>((resolve) => {
        release = resolve;
      }),
    );
    await Promise.resolve();
    document.dispatchEvent(new CustomEvent('jf-save-settings', {
      detail: { settings: { ui: { mode: 'advanced' } } },
    }));
    await Promise.resolve();
    expect(posts).toEqual([]);

    release(new Response('{}', { status: 200 }));
    await blocker;
    await new Promise((resolve) => setTimeout(resolve, 0));
    await new Promise((resolve) => setTimeout(resolve, 0));
    const firstBody = JSON.parse(posts[0]!.body) as {
      ui: { mode: string };
      witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
      operationKey: string;
    };
    expect(firstBody).toEqual({
      ui: { mode: 'advanced' },
      witness: { acceptedRevision: 0, lastCommittedOperationKey: null },
      operationKey: expect.stringMatching(UUID_V7),
    });
    expect(firstBody.operationKey).toMatch(UUID_V7);
    expect(posts[0]?.intent).toMatch(/:\d+$/);
    expect(posts[0]?.intent).toMatch(/:2$/);
  });
});
