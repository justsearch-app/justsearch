// @vitest-environment happy-dom

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './BrainSurface.js';
import {
  __resetUiModeForTest,
  getUiMode,
  setUiMode,
  UI_MODE_INTENT_HEADER,
} from '../state/uiModeState.js';

interface BrainHost extends HTMLElement {
  apiBase: string;
  updateComplete: Promise<boolean>;
}

const UUID_V7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const realFetch = globalThis.fetch;
let posts: Array<{ url: string; body: string; intent: string | null }> = [];
let postStatus = 200;
let deferPosts = false;
let postResolvers: Array<(response: Response) => void> = [];
let acceptedRevision = 0;
let lastCommittedOperationKey: string | null = null;

function settingsSnapshot(): Response {
  return new Response(JSON.stringify({
    ui: { mode: 'simple' },
    llm: {},
    witness: { acceptedRevision, lastCommittedOperationKey },
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

function response(status = postStatus): Response {
  if (status !== 200) {
    return new Response('{}', { status, headers: { 'content-type': 'application/json' } });
  }
  const request = JSON.parse(posts[posts.length - 1]?.body ?? '{}') as {
    operationKey?: string;
    witness?: { acceptedRevision?: number };
  };
  acceptedRevision = (request.witness?.acceptedRevision ?? 0) + 1;
  lastCommittedOperationKey = request.operationKey ?? null;
  return new Response(JSON.stringify({
    state: 'COMPLETE',
    operationKey: request.operationKey,
    witness: { acceptedRevision, lastCommittedOperationKey },
  }), { status: 200, headers: { 'content-type': 'application/json' } });
}

function expectModeAttempt(body: string, mode: 'simple' | 'advanced', revision: number): void {
  const attempt = JSON.parse(body) as {
    ui: { mode: string };
    witness: { acceptedRevision: number; lastCommittedOperationKey: string | null };
    operationKey: string;
  };
  expect(attempt).toEqual({
    ui: { mode },
    witness: {
      acceptedRevision: revision,
      lastCommittedOperationKey: revision === 0 ? null : expect.stringMatching(UUID_V7),
    },
    operationKey: expect.stringMatching(UUID_V7),
  });
  expect(attempt.operationKey).toMatch(UUID_V7);
}

async function settle(el: BrainHost): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

function modeButtons(el: BrainHost): HTMLButtonElement[] {
  return [...(el.shadowRoot?.querySelectorAll<HTMLButtonElement>('.mode-toggle button') ?? [])];
}

beforeEach(() => {
  __resetUiModeForTest();
  posts = [];
  postStatus = 200;
  deferPosts = false;
  postResolvers = [];
  acceptedRevision = 0;
  lastCommittedOperationKey = null;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.endsWith('/api/settings/v2') && method === 'POST') {
      posts.push({
        url,
        body: String(init?.body ?? ''),
        intent: new Headers(init?.headers).get(UI_MODE_INTENT_HEADER),
      });
      if (deferPosts) {
        return new Promise<Response>((resolve, reject) => {
          postResolvers.push(resolve);
          init?.signal?.addEventListener('abort', () => reject(init.signal?.reason), { once: true });
        });
      }
      return response();
    }
    if (url.endsWith('/api/settings/v2')) {
      return settingsSnapshot();
    }
    return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
});

afterEach(() => {
  document.body.innerHTML = '';
  globalThis.fetch = realFetch;
  vi.useRealTimers();
  __resetUiModeForTest();
});

describe('BrainSurface shared detail level', () => {
  it('renders the stable advanced value as the user-facing Detailed label', async () => {
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    expect(modeButtons(el).map((button) => button.textContent?.trim())).toEqual([
      'Simple',
      'Detailed',
    ]);
    expect(modeButtons(el).map((button) => button.type)).toEqual(['button', 'button']);
    expect(modeButtons(el).map((button) => button.getAttribute('aria-pressed'))).toEqual([
      'true',
      'false',
    ]);

    setUiMode('advanced');
    await el.updateComplete;
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.classList)
      .toContain('active');
    expect(modeButtons(el).map((button) => button.getAttribute('aria-pressed'))).toEqual([
      'false',
      'true',
    ]);
  });

  it('does not let its settings refresh overwrite a newer shared choice', async () => {
    setUiMode('advanced');
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    expect(getUiMode()).toBe('advanced');
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.classList)
      .toContain('active');
  });

  it('publishes a Brain change through uiModeState while persisting the compatible wire value', async () => {
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await settle(el);

    expect(getUiMode()).toBe('advanced');
    expect(posts).toHaveLength(1);
    expectModeAttempt(posts[0]!.body, 'advanced', 0);
    expect(posts[0]!.intent).toMatch(/:1$/);
  });

  it('rolls the shared projection back when persistence rejects the change', async () => {
    postStatus = 500;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await settle(el);

    expect(getUiMode()).toBe('simple');
    expect(modeButtons(el).find((button) => button.textContent?.trim() === 'Simple')?.classList)
      .toContain('active');
    expect(el.shadowRoot?.textContent).toContain('Settings request failed (HTTP_500)');
  });

  it('serializes rapid changes so the last click is the final persisted value', async () => {
    deferPosts = true;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);

    const [simple, detailed] = modeButtons(el);
    detailed?.click();
    simple?.click();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(getUiMode()).toBe('simple');
    expect(posts).toHaveLength(1);
    expectModeAttempt(posts[0]!.body, 'advanced', 0);
    expect(posts[0]!.intent).toMatch(/:\d+$/);
    postResolvers.shift()?.(response(200));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(posts).toHaveLength(2);
    expectModeAttempt(posts[1]!.body, 'simple', 1);
    expect(posts[1]!.intent).toMatch(/:\d+$/);
    postResolvers.shift()?.(response(200));
    await settle(el);

    expect(getUiMode()).toBe('simple');
    expect(simple?.classList).toContain('active');
  });

  it('aborts a hung save, clears Brain busy state, and rolls back', async () => {
    deferPosts = true;
    const el = document.createElement('jf-brain-surface') as BrainHost;
    el.apiBase = '';
    document.body.appendChild(el);
    await settle(el);
    vi.useFakeTimers();

    modeButtons(el).find((button) => button.textContent?.trim() === 'Detailed')?.click();
    await Promise.resolve();
    expect((el as unknown as { busy: { mode: boolean } }).busy.mode).toBe(true);

    await vi.advanceTimersByTimeAsync(10_000);
    await el.updateComplete;

    expect((el as unknown as { busy: { mode: boolean } }).busy.mode).toBe(false);
    expect(getUiMode()).toBe('simple');
    expect(el.shadowRoot?.textContent).toContain('Settings completion timed out; the original attempt may still complete.');
  });
});
