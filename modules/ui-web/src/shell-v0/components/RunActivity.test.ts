// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// DP3 — the retrospective's background runs, now an Activity utility. ("This run" is the feed's run
// scope: views/ActivitySurface.details.test.ts.)
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './RunActivity.js';
import type { RunActivity } from './RunActivity.js';
import { __resetAgentSessionStore, getAgentSessionController, peekAgentSessionController } from '../state/agentSessionStore.js';
import { NAVIGATE_TO_SURFACE_EVENT } from '../controllers/navigateRequest.js';
import type { AgentSessionController } from '../controllers/AgentSessionController.js';

let presence: () => Promise<Response>;
let ledger: () => Promise<Response>;

beforeEach(() => {
  presence = async () => Response.json({ runs: [] });
  ledger = async () => Response.json({ entries: [] });
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('/api/presence')) return presence();
    if (path.includes('/api/action-ledger')) return ledger();
    return Response.json({});
  }));
});

afterEach(() => {
  document.body.replaceChildren();
  __resetAgentSessionStore();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: RunActivity): Promise<void> {
  for (let i = 0; i < 10; i += 1) { await new Promise(resolve => setTimeout(resolve, 0)); await el.updateComplete; }
}

async function mount(runs?: unknown[]): Promise<RunActivity> {
  if (runs) presence = async () => Response.json({ runs });
  const el = document.createElement('jf-run-activity');
  el.apiBase = 'http://x';
  document.body.append(el);
  await settle(el);
  return el;
}

const text = (el: Element | null | undefined): string => (el?.shadowRoot?.textContent ?? el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const ctrl = (): AgentSessionController => getAgentSessionController('http://x');
const run = (sessionId: string, state: string): Record<string, unknown> => ({ sessionId, state, actor: 'agent', toolCalls: 1, turns: 1, iterations: 1 });

describe('agentSessionStore — ONE shared controller (561 surface tier)', () => {
  it('returns the same controller instance to every consumer', () => {
    expect(peekAgentSessionController()).toBeNull();
    const a = getAgentSessionController('http://x');
    expect(getAgentSessionController('http://x')).toBe(a);
    expect(peekAgentSessionController()).toBe(a);
  });
});

describe('Background runs', () => {
  it('§33 — groups by the REAL LifecycleState vocabulary: merged, ranked, friendly', async () => {
    const el = await mount([run('r1', 'DONE'), run('r2', 'ERROR'), run('r3', 'READY_FOR_LLM'), run('r4', 'AFTER_TOOL_RESULT')]);
    expect([...el.shadowRoot!.querySelectorAll('.group-header')].map(text)).toEqual(['Running · 2', 'Done · 1', 'Failed · 1']);
    expect([...el.shadowRoot!.querySelectorAll('.group')].map(group => group.getAttribute('data-status'))).toEqual(['running', 'done', 'failed']);
  });

  it('814 — says what record it answers from, and an empty list names what fills it', async () => {
    const el = await mount([]);
    const section = el.shadowRoot!.querySelector('[data-testid="activity-background-runs"]');
    expect(text(section)).toContain('Runs working in the background, across every conversation.');
    expect(text(section)).toContain('Runs you launch in the background appear here');
  });

  it('814 — the count is a legible zero once read, and never a false zero before', async () => {
    let release!: (response: Response) => void;
    presence = () => new Promise(resolve => { release = resolve; });
    const el = await mount();
    const count = (): string => text(el.shadowRoot!.querySelector('[data-testid="background-runs-count"]'));
    expect(count()).toBe('…');
    release(Response.json({ runs: [] }));
    await settle(el);
    expect(count()).toBe('0');
    presence = async () => Response.json({ runs: [run('r1', 'DONE'), run('r2', 'READY_FOR_LLM')] });
    await ctrl().loadPresence();
    await settle(el);
    expect(count()).toBe('2');
  });

  it('a finished run opens for replay in Search; a running one is not a control', async () => {
    const el = await mount([run('finished', 'DONE'), run('running', 'READY_FOR_LLM')]);
    const replay = vi.spyOn(ctrl(), 'loadReplay').mockResolvedValue(undefined as never);
    const targets: string[] = [];
    const onNavigate = (event: Event): void => { targets.push((event as CustomEvent<{ surfaceId: string }>).detail.surfaceId); };
    document.addEventListener(NAVIGATE_TO_SURFACE_EVENT, onNavigate);
    expect(el.shadowRoot!.querySelectorAll('.run.clickable')).toHaveLength(1);
    el.shadowRoot!.querySelector<HTMLElement>('.run.clickable')!.click();
    document.removeEventListener(NAVIGATE_TO_SURFACE_EVENT, onNavigate);
    expect(replay).toHaveBeenCalledWith('finished');
    expect(targets).toEqual(['core.search-v3-surface']);
  });

  it('launches a background run from the field, and only with something to run', async () => {
    const el = await mount([]);
    const start = vi.spyOn(ctrl(), 'runBackgroundTask').mockResolvedValue(undefined as never);
    const input = el.shadowRoot!.querySelector<HTMLInputElement>('input')!;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(start).not.toHaveBeenCalled();
    input.value = '  summarise the inbox ';
    input.dispatchEvent(new Event('input'));
    await el.updateComplete;
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(start).toHaveBeenCalledWith('summarise the inbox');
  });

  it('a failed first read says so instead of reading as empty, and Try again recovers', async () => {
    presence = async () => new Response('nope', { status: 503 });
    const el = await mount();
    const section = el.shadowRoot!.querySelector('[data-testid="activity-background-runs"]')!;
    expect(text(section)).toContain("Couldn't load background runs.");
    expect(text(section)).not.toContain('Runs you launch in the background appear here');
    presence = async () => Response.json({ runs: [run('r1', 'DONE')] });
    (section.querySelector('[data-testid="run-activity-failed"] jf-button') as HTMLElement & { onActivate(): void }).onActivate();
    await settle(el);
    expect(text(section)).not.toContain("Couldn't load");
    expect(text(section)).toContain('Done · 1');
  });

  it('a failed refresh keeps the rows and says when they were last seen', async () => {
    const el = await mount([run('r1', 'DONE')]);
    presence = () => Promise.reject(new TypeError('no connection'));
    await ctrl().loadPresence();
    await settle(el);
    const section = el.shadowRoot!.querySelector('[data-testid="activity-background-runs"]')!;
    expect(text(section)).toContain('Done · 1');
    expect(text(section)).toContain("Couldn't refresh background runs. Showing the list last seen");
  });
});
