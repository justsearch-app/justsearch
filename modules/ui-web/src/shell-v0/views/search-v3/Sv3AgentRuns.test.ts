// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// DP3 — the retrospective's sessions, now at the end of Search's list.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Sv3AgentRuns.js';
import type { Sv3AgentRuns } from './Sv3AgentRuns.js';
import { __resetAgentSessionStore, getAgentSessionController } from '../../state/agentSessionStore.js';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';

type Listing = () => Promise<Response>;
let listing: Listing;

beforeEach(() => {
  listing = async () => Response.json({ sessions: [] });
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => (String(url).includes('/api/chat/sessions') ? listing() : Response.json({}))));
});

afterEach(() => {
  document.body.replaceChildren();
  __resetAgentSessionStore();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Sv3AgentRuns): Promise<void> {
  for (let i = 0; i < 20; i += 1) { await Promise.resolve(); await el.updateComplete; }
  await new Promise(resolve => setTimeout(resolve, 0));
  await el.updateComplete;
}

async function open(sessions?: unknown[]): Promise<Sv3AgentRuns> {
  if (sessions) listing = async () => Response.json({ sessions });
  const el = document.createElement('jf-sv3-agent-runs');
  el.apiBase = 'http://x';
  document.body.append(el);
  await el.updateComplete;
  el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-agent-runs-toggle"]')!.click();
  await settle(el);
  return el;
}

const text = (el: Element): string => (el.shadowRoot?.textContent ?? el.textContent ?? '').replace(/\s+/g, ' ').trim();
const buttons = (el: Sv3AgentRuns): string[] => [...el.shadowRoot!.querySelectorAll('.actions button')].map(b => b.textContent!.trim());
const ctrl = (): AgentSessionController => getAgentSessionController('http://x');

describe('agent runs in Search’s list', () => {
  it('a closed section asks the backend nothing', async () => {
    const el = document.createElement('jf-sv3-agent-runs');
    document.body.append(el);
    await settle(el);
    expect(fetch).not.toHaveBeenCalled();
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-toggle"]')!.getAttribute('aria-expanded')).toBe('false');
  });

  it('renders Resume ONLY on resumable runs; finished ones offer Replay and Export', async () => {
    const el = await open([
      { sessionId: 's-live', preview: 'resumable run', resumable: true },
      { sessionId: 's-done', preview: 'finished run', resumable: false },
      { sessionId: 's-legacy', preview: 'legacy run (no flag)' },
    ]);
    expect(buttons(el).filter(label => label === 'Resume')).toHaveLength(1);
    expect(buttons(el).filter(label => label === 'Replay')).toHaveLength(2);
    expect(buttons(el).filter(label => label === 'Export')).toHaveLength(2);
    expect(text(el)).toContain('finished run');
    expect(text(el)).toContain('Finished');
  });

  it('renders the four interruption presentations, and only where one applies', async () => {
    const at = '2026-08-18T10:00:00Z';
    const el = await open([
      { sessionId: 's-fin', preview: 'finished run', state: 'DONE', resumable: false },
      { sessionId: 's-mid', preview: 'mid-step run', state: 'READY_FOR_LLM', resumable: true, interruptedAt: at },
      { sessionId: 's-appr', preview: 'gated run', state: 'WAITING_APPROVAL', resumable: true, interruptedAt: at },
      { sessionId: 's-fork', preview: 'budget-parked run', state: 'WAITING_BUDGET', resumable: false, interruptedAt: at },
    ]);
    const notices = [...el.shadowRoot!.querySelectorAll('[data-testid="session-interrupted"]')].map(text);
    expect(notices).toEqual([
      'Interrupted when the app closed. Resume.',
      'Interrupted while waiting for your approval. Resume.',
      'Interrupted while waiting for your decision about tokens/context. Cannot be resumed — start a new run from this transcript',
    ]);
    const forked = [...el.shadowRoot!.querySelectorAll('[data-testid="sv3-agent-run"]')].find(row => row.textContent!.includes('budget-parked'));
    expect(forked!.textContent).not.toContain('Finished');
    expect(forked!.querySelector('.actions button')!.textContent).toBe('Replay');
  });

  it('Resume goes through the control seam with the run’s own lifecycle, Replay through the replay primitive', async () => {
    const el = await open([
      { sessionId: 's-live', preview: 'resumable run', resumable: true },
      { sessionId: 's-done', preview: 'finished run', resumable: false },
    ]);
    const replay = vi.spyOn(ctrl(), 'loadReplay').mockResolvedValue(undefined as never);
    const resume = vi.spyOn(ctrl(), 'resumeSession').mockResolvedValue(undefined as never);
    const [resumeButton, replayButton] = el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.actions button');
    resumeButton!.click();
    replayButton!.click();
    await settle(el);
    expect(resume).toHaveBeenCalledWith('s-live');
    expect(replay).toHaveBeenCalledWith('s-done');
  });

  it('opening settles without looping', async () => {
    let notifies = 0;
    const el = await open([{ sessionId: 's', preview: 'a run', resumable: false }]);
    const { subscribeAgentSession } = await import('../../state/agentSessionStore.js');
    const stop = subscribeAgentSession(() => { notifies += 1; });
    const toggle = el.shadowRoot!.querySelector<HTMLButtonElement>('[data-testid="sv3-agent-runs-toggle"]')!;
    toggle.click();
    toggle.click();
    await settle(el);
    stop();
    expect(notifies).toBeLessThan(5);
  });

  it('an empty list names what fills it', async () => {
    const el = await open([]);
    expect(text(el)).toContain('Agent runs appear here once you start one');
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-failed"]')).toBeNull();
  });

  it('a failed first read says so instead of reading as empty, and Try again recovers', async () => {
    listing = async () => new Response('nope', { status: 503 });
    const el = await open();
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-empty"]')).toBeNull();
    const failed = el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-failed"]')!;
    expect(text(failed)).toContain("Couldn't load agent runs.");
    listing = async () => Response.json({ sessions: [{ sessionId: 's', preview: 'back again', resumable: false }] });
    failed.querySelector('button')!.click();
    await settle(el);
    expect(text(el)).toContain('back again');
    expect(el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-failed"]')).toBeNull();
  });

  it('a failed refresh keeps the rows and says when they were last seen', async () => {
    const el = await open([{ sessionId: 's', preview: 'kept run', resumable: false }]);
    listing = () => Promise.reject(new TypeError('no connection'));
    await ctrl().loadSessions();
    await settle(el);
    expect(text(el)).toContain('kept run');
    expect(text(el.shadowRoot!.querySelector('[data-testid="sv3-agent-runs-failed"]')!)).toContain("Couldn't refresh agent runs. Showing the list last seen");
  });

  it('a shared replay that is not a transcript is refused in words', async () => {
    const el = await open([]);
    const input = el.shadowRoot!.querySelector<HTMLInputElement>('input[type="file"]')!;
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['not json'], 'x.json')] });
    input.dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(text(el)).toContain('Could not read that file as a run transcript.'));
    vi.spyOn(ctrl(), 'loadReplayFromExport').mockReturnValue(false);
    Object.defineProperty(input, 'files', { configurable: true, value: [new File(['{}'], 'x.json')] });
    input.dispatchEvent(new Event('change'));
    await vi.waitFor(() => expect(text(el)).toContain('That file has no replayable events.'));
  });
});
