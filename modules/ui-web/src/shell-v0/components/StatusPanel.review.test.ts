// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * DE3/C8/C12 — agent requests are reviewed and answered inside the status panel (ref-09, ref-10):
 * they arrive in Needs you without a dialog, announced once; the review names who asked, the
 * consequences, the requester's own words as theirs, the deadline and what happens on timeout, and
 * answers with Allow this time / Deny (keys 1/2) through the host's one decision path.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StatusPill } from './StatusPill.js';
import type { StatusPanel } from './StatusPanel.js';
import { AuthorizationHost } from './AuthorizationHost.js';
import { requestAuthorization, type AuthorizationPrompt } from '../operations/authorizationBroker.js';
import { proposeEffect, listPending, __resetPendingForTest } from '../substrates/pending-effects/index.js';
import { __resetJournalForTest } from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { __resetAiStateForTest, __feedForTest, type StatusSnapshot } from '../state/aiStateStore.js';
import { STATUS_REVIEW_EVENT } from '../state/frameStatus.js';
import { sendDesktopNotification } from '../../utils/notify.js';
import { isWindowFocused } from '../../utils/windowFocus.js';
vi.mock('../../utils/windowFocus.js', () => ({ isWindowFocused: vi.fn(async () => false) }));
vi.mock('../../utils/notify.js', () => ({ sendDesktopNotification: vi.fn(async () => {}) }));

let pill: StatusPill;
let host: AuthorizationHost;
const panel = () => pill.shadowRoot!.querySelector<StatusPanel>('jf-status-panel')!;
const root = () => panel().shadowRoot!;
async function flush() {
  for (let i = 0; i < 4; i++) await Promise.resolve();
  await host.updateComplete; pill.status.refresh(); await pill.updateComplete; await panel().updateComplete;
  await Promise.resolve(); await panel().updateComplete;
}
/** The arming delay passes: the next answer is deliberate. */
async function arm() { vi.advanceTimersByTime(400); await flush(); }
const row = (id: string) => root().querySelector<HTMLElement>(`article[data-fact="${id}"]`);
function rowButton(id: string, label: string): HTMLButtonElement {
  expect(row(id), `${id} is listed`).not.toBeNull();
  const button = [...row(id)!.querySelectorAll<HTMLButtonElement>('.actions button')].find(b => b.textContent!.trim() === label);
  expect(button, `${id} offers ${label}`).toBeDefined();
  return button!;
}
const review = () => root().querySelector<HTMLElement>('[data-testid="status-review"]');
const answer = (which: 'allow' | 'deny') => review()!.querySelector<HTMLButtonElement>(`[data-answer="${which}"]`)!;
/** A deliberate pointer click: the press starts on the control, then the click completes it. */
function press(button: HTMLElement) {
  button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, detail: 1 }));
}
function key(target: EventTarget, init: KeyboardEventInit) {
  const event = new KeyboardEvent('keydown', { bubbles: true, composed: true, cancelable: true, ...init });
  target.dispatchEvent(event);
  return event;
}
function agent(id: string, extra: Partial<AuthorizationPrompt> = {}) {
  const reject = vi.fn(async () => {});
  const prompt: AuthorizationPrompt = { pendingId: id, operationId: 'core.ingest-files', gateBehavior: 'INLINE_CONFIRM',
    riskTier: 'MEDIUM', argsSummary: '/home/me/Downloads', requestedBy: 'Claude Desktop', arrival: 'needs-you', live: true,
    askedAt: new Date(Date.now() - 60_000).toISOString(), expiresAt: new Date(Date.now() + 4 * 60_000).toISOString(), reject, ...extra };
  return { decision: requestAuthorization(prompt), reject, prompt };
}
async function openPanel() { pill.open = true; await flush(); }
async function openReview(id: string) {
  await openPanel();
  rowButton(id, 'Review').click(); await flush();
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['performance'] });
  vi.clearAllMocks();
  vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
  __resetAiStateForTest(); __resetPendingForTest(); __resetJournalForTest();
  host = new AuthorizationHost(); document.body.append(host);
  host.addEventListener('status-requests-changed', () => pill.status.refresh());
  pill = new StatusPill(); pill.authorizationHost = () => host; document.body.append(pill); await flush();
});
afterEach(() => {
  pill.remove(); host.remove(); __resetAiStateForTest(); __resetPendingForTest(); __resetJournalForTest();
  vi.unstubAllGlobals(); vi.useRealTimers();
});

describe('DE3 arrival: Needs you, no dialog, one announcement', () => {
  it('an agent request lists in Needs you with Allow, Deny and Review, opens no dialog and is announced once', async () => {
    agent('a1'); await flush();
    const dialog = host.shadowRoot!.querySelector('dialog')!;
    expect(dialog.open, 'no dialog on arrival').toBe(false);
    expect(host.active).toBeNull();
    expect(pill.status.model.pill).toMatchObject({ kind: 'needs', count: 1 });
    expect([...row('authorization:a1')!.querySelectorAll('.actions button')].map(b => b.textContent!.trim()))
      .toEqual(['Allow', 'Deny', 'Review']);
    expect(row('authorization:a1')!.querySelector('[data-deadline]')!.textContent).toContain('Denied automatically in 4 min');
    expect(pill.status.assertive).toBe('Needs you: Claude Desktop requests Ingest Files.');
    const key = pill.status.assertiveKey;
    pill.status.refresh(); await flush();
    expect(pill.status.assertiveKey, 'announced once per arrival').toBe(key);
    expect(sendDesktopNotification).toHaveBeenCalledTimes(1);
  });

  it('a request rediscovered after a reload or reconnect is listed without an announcement', async () => {
    agent('old', { live: false }); await flush();
    expect(row('authorization:old')).not.toBeNull();
    expect(pill.status.assertive).toBe('');
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });

  it('a focused window is told in the page, not on the desktop', async () => {
    vi.mocked(isWindowFocused).mockResolvedValueOnce(true);
    agent('focused'); await flush();
    expect(pill.status.assertive).toContain('Claude Desktop');
    expect(sendDesktopNotification).not.toHaveBeenCalled();
  });

  it('builder class: arriving while typing in Search leaves focus and text where they are', async () => {
    const search = document.createElement('input'); document.body.append(search); search.focus();
    try {
      agent('typing'); await flush();
      expect(document.activeElement).toBe(search);
      key(search, { key: '1' });
      await arm();
      expect(host.statusRequests()).toHaveLength(1);
    } finally { search.remove(); }
  });

  it('builder class: arriving while the panel is open updates the list; arriving during a review keeps the review', async () => {
    await openPanel();
    agent('first'); await flush();
    expect(row('authorization:first')).not.toBeNull();
    rowButton('authorization:first', 'Review').click(); await flush();
    agent('second'); await flush();
    expect(review()!.getAttribute('data-fact')).toBe('authorization:first');
    expect(pill.status.model.pill.count).toBe(2);
  });
});

describe('DE3 the review shows what the person needs to decide', () => {
  it('names who asked and when, the question, consequence lines, the deadline and its consequence', async () => {
    agent('r1', { undoSupported: false }); await flush();
    await openReview('authorization:r1');
    const text = review()!.textContent!.replace(/\s+/g, ' ');
    expect(text).toContain("What's going on");
    expect(review()!.querySelector('.who-name')!.textContent).toBe('Claude Desktop');
    expect(text).toContain('Agent on this computer · asked 1 min ago');
    expect(review()!.querySelector('h2')!.textContent).toBe('Let Claude Desktop run “Ingest Files”?');
    expect([...review()!.querySelectorAll('.lines li')].map(li => [li.getAttribute('data-line'), li.textContent!.trim()])).toEqual([
      ['target', '▸/home/me/Downloads'], ['risk', '⚠Medium risk'], ['undo', '↺No undo: it cannot be reversed']]);
    expect(review()!.querySelector('.lines .path')!.textContent).toBe('/home/me/Downloads');
    expect(review()!.querySelector('[data-deadline]')!.textContent).toContain('Denied automatically in 4 min');
    expect(review()!.querySelector('[data-deadline] progress')!.getAttribute('value')).toBe('80');
    expect(text).toContain('1 allow'); expect(text).toContain('2 deny'); expect(text).toContain('Esc back, still waiting');
    expect(root().activeElement?.tagName, 'focus moves to the question').toBe('H2');
  });

  it('a request without a deadline says it waits; the agent in Search says the run ends it', async () => {
    agent('nodeadline', { expiresAt: undefined, requestedBy: undefined }); await flush();
    await openReview('authorization:nodeadline');
    expect(review()!.querySelector('.who-name')!.textContent).toBe('An agent');
    expect(review()!.querySelector('[data-deadline]')!.textContent).toContain('No deadline: it waits until you answer');
    expect(review()!.querySelector('[data-deadline] progress')).toBeNull();
    review()!.querySelector<HTMLButtonElement>('.back')!.click(); await flush();
    agent('inapp', { owningRunId: 'run-1', expiresAt: undefined, allowAlwaysSupported: false }); await flush();
    rowButton('authorization:inapp', 'Review').click(); await flush();
    expect(review()!.querySelector('.who-name')!.textContent).toBe('The agent in Search');
    expect(review()!.querySelector('[data-deadline]')!.textContent).toContain('it waits until you answer or the run stops');
  });

  it.each([
    ['MEDIUM from an outside agent', {}, true, ''],
    ['HIGH', { riskTier: 'HIGH' }, false, '“Ingest Files” is asked every time; it can\'t be allowed permanently.'],
    ['the agent in Search', { owningRunId: 'run-1', allowAlwaysSupported: false }, false, 'The agent in Search asks every time; its requests can\'t be allowed permanently.'],
  ] as const)('durable scope for %s: offered only where the backend keeps it', async (_name, extra, offered, note) => {
    const { decision } = agent('d', extra as Partial<AuthorizationPrompt>); await flush();
    await openReview('authorization:d');
    const box = review()!.querySelector<HTMLInputElement>('label.always input');
    expect(!!box).toBe(offered);
    if (!offered) {
      expect(review()!.querySelector('.note')!.textContent!.trim()).toBe(`ⓘ ${note}`);
      return;
    }
    await arm();
    press(box!); box!.checked = true; box!.dispatchEvent(new Event('change')); await flush();
    press(answer('allow')); await flush();
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: true });
  });

  it("an agent's suggestion shows its reason marked as written by the agent and not checked", async () => {
    proposeEffect({ kind: 'navigate', to: '#elsewhere' }, CORE_PROVENANCE, 'agent', { rationale: 'You asked to see the invoices.' });
    await flush(); await openPanel();
    const id = row(`proposal:${listPending()[0]!.id}`)!.getAttribute('data-fact')!;
    rowButton(id, 'Review').click(); await flush();
    const reason = review()!.querySelector('[data-reason]')!;
    expect(reason.querySelector('h3')!.textContent).toBe('Its reason');
    expect(reason.querySelector('blockquote')!.textContent).toBe('You asked to see the invoices.');
    expect(reason.querySelector('.detail')!.textContent).toBe('Written by the agent, not checked by JustSearch');
    expect(review()!.querySelector('.note')!.textContent).toContain('Suggestions are asked every time.');
    await arm(); key(review()!, { key: '2' }); await flush();
    expect(listPending()).toHaveLength(0);
    expect(review()).toBeNull();
    expect(root().querySelector('[role="status"]'), 'answered here: no "no longer waiting" notice').toBeNull();
  });
});

describe('DE3 fresh live details', () => {
  it('opening the review reads the live details again and shows them', async () => {
    const refresh = vi.fn(async () => ({ argsSummary: '/home/me/Downloads/old-exports' }));
    agent('fresh', { refresh }); await flush();
    await openReview('authorization:fresh');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(review()!.querySelector('.lines .path')!.textContent).toBe('/home/me/Downloads/old-exports');
  });

  it('a request that no longer waits leaves the review: the list says so and nothing can answer it', async () => {
    const { decision } = agent('gone', { refresh: async () => null }); await flush();
    await openReview('authorization:gone');
    await expect(decision).resolves.toMatchObject({ approved: false, superseded: true });
    expect(review()).toBeNull();
    expect(root().querySelector('[role="status"]')!.textContent).toBe('The request you were reviewing is no longer waiting.');
  });

  it('unavailable live details deny the request', async () => {
    const { decision, reject } = agent('unavailable', { refresh: async () => { throw new TypeError('offline'); } }); await flush();
    await openReview('authorization:unavailable');
    expect(reject).toHaveBeenCalledTimes(1);
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
  });

  it('when the denial of unreadable details cannot be sent, the request stays and can only be denied', async () => {
    const { decision, reject } = agent('stuck', { refresh: async () => { throw new TypeError('offline'); } });
    reject.mockRejectedValueOnce(new Error('offline')); await flush();
    await openReview('authorization:stuck');
    expect(review()!.querySelector('[role="alert"]')!.textContent).toBe('The request could not be cancelled. Try again.');
    expect(answer('allow').getAttribute('aria-disabled')).toBe('true');
    await arm(); press(answer('allow')); await flush();
    expect(host.statusRequests()).toHaveLength(1);
    press(answer('deny')); await flush();
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
  });
});

describe('DE3/DE8 answers', () => {
  it('Allow this time answers once after arming; the activation that opened the review cannot', async () => {
    const { decision } = agent('allow'); await flush();
    await openReview('authorization:allow');
    press(answer('allow')); await flush();
    expect(host.statusRequests(), 'a press before arming answers nothing').toHaveLength(1);
    await arm(); press(answer('allow')); press(answer('allow')); await flush();
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
    expect(host.statusRequests()).toHaveLength(0);
    expect(review()).toBeNull();
  });

  it('a press that started before arming does not answer when released after it', async () => {
    agent('early'); await flush();
    await openReview('authorization:early');
    const allow = answer('allow');
    allow.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
    await arm();
    allow.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, detail: 1 })); await flush();
    expect(host.statusRequests()).toHaveLength(1);
  });

  it('Deny cancels the backend request; a failed cancellation says so and keeps it answerable', async () => {
    const { decision, reject } = agent('deny'); await flush();
    reject.mockRejectedValueOnce(new Error('offline'));
    await openReview('authorization:deny'); await arm();
    press(answer('deny')); await flush();
    expect(review()!.querySelector('[role="alert"]')!.textContent).toBe('The request could not be cancelled. Try again.');
    expect(host.statusRequests()).toHaveLength(1);
    press(answer('deny')); await flush();
    expect(reject).toHaveBeenCalledTimes(2);
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
  });

  it('the row answers in place too, through the same arming', async () => {
    const { decision, reject } = agent('inline'); await openPanel();
    rowButton('authorization:inline', 'Deny').click(); await flush();
    expect(reject, 'a click within the arming delay of arrival answers nothing').not.toHaveBeenCalled();
    await arm(); rowButton('authorization:inline', 'Deny').click(); await flush();
    await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
    expect(row('authorization:inline')).toBeNull();
  });

  it('Escape steps back to the list; the request keeps waiting', async () => {
    agent('esc'); await flush();
    await openReview('authorization:esc');
    key(document.body, { key: 'Escape' }); await flush();
    expect(pill.open).toBe(true);
    expect(review()).toBeNull();
    expect(host.statusRequests()).toHaveLength(1);
    key(document.body, { key: 'Escape' }); await flush();
    expect(pill.open).toBe(false);
  });

  it('builder class: a request expiring while reviewed cannot be allowed and leaves the panel', async () => {
    const { decision } = agent('expiring', { expiresAt: new Date(Date.now() + 1_000).toISOString() }); await flush();
    await openReview('authorization:expiring'); await arm();
    const later = Date.now() + 2_000;
    const clock = vi.spyOn(Date, 'now').mockReturnValue(later);
    try {
      press(answer('allow')); await flush();
      await expect(decision).resolves.toMatchObject({ approved: false, superseded: true });
      expect(review()).toBeNull();
      expect(row('authorization:expiring')).toBeNull();
    } finally { clock.mockRestore(); }
  });
});

describe('C8 keys 1 and 2', () => {
  it('1 allows and 2 denies from inside the review, after arming, once', async () => {
    const first = agent('k1'); const second = agent('k2'); await flush();
    await openReview('authorization:k1');
    key(review()!, { key: '1' }); await flush();
    expect(host.statusRequests(), 'not before arming').toHaveLength(2);
    await arm(); key(review()!, { key: '1' }); await flush();
    await expect(first.decision).resolves.toEqual({ approved: true, allowAlways: false });
    rowButton('authorization:k2', 'Review').click(); await flush(); await arm();
    key(review()!, { key: '2' }); await flush();
    await expect(second.decision).resolves.toEqual({ approved: false, allowAlways: false });
  });

  it.each([
    ['key repeat', { repeat: true }],
    ['IME composition', { isComposing: true }],
    ['IME process key', { keyCode: 229 }],
    ['a modifier', { ctrlKey: true }],
  ] as const)('never answers on %s', async (_name, init) => {
    agent('guard'); await flush();
    await openReview('authorization:guard'); await arm();
    key(review()!, { key: '1', ...init }); key(review()!, { key: '2', ...init }); await flush();
    expect(host.statusRequests()).toHaveLength(1);
  });

  it('never answers from a text field', async () => {
    agent('field'); await flush();
    await openReview('authorization:field'); await arm();
    const field = document.createElement('input'); review()!.append(field);
    key(field, { key: '1' }); await flush();
    expect(host.statusRequests()).toHaveLength(1);
  });

  it('never answers while another modal owns the keyboard', async () => {
    agent('under'); await flush();
    await openReview('authorization:under'); await arm();
    void requestAuthorization({ pendingId: 'typed-own', operationId: 'core.bulk-reindex', gateBehavior: 'TYPED_CONFIRM' }); await flush();
    key(review()!, { key: '1' }); key(review()!, { key: '2' }); await flush();
    expect(host.statusRequests().map(item => item.kind === 'authorize' && item.prompt.pendingId).sort()).toEqual(['typed-own', 'under']);
  });
});

describe('typed agent requests open the protected dialog from Review', () => {
  it('lists with Review only; Review opens the security dialog modally; closing it returns the request to Needs you', async () => {
    const { decision } = agent('typed', { gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH' }); await flush();
    expect(host.shadowRoot!.querySelector('dialog')!.open).toBe(false);
    await openPanel();
    expect([...row('authorization:typed')!.querySelectorAll('.actions button')].map(b => b.textContent!.trim())).toEqual(['Review']);
    rowButton('authorization:typed', 'Review').click(); await flush();
    const dialog = host.shadowRoot!.querySelector('dialog')!;
    expect(pill.open).toBe(false);
    expect(dialog.open).toBe(true);
    expect(dialog.classList.contains('lightweight')).toBe(false);
    expect(host.shadowRoot!.querySelector('[data-testid="authorization-typed-input"]')).not.toBeNull();
    dialog.close(); dialog.dispatchEvent(new Event('close')); await flush();
    expect(host.active).toBeNull();
    expect(row('authorization:typed')).not.toBeNull();
    expect(await Promise.race([decision.then(() => 'answered'), Promise.resolve('waiting')])).toBe('waiting');
  });

  it('builder class: an agent request arriving while a typed dialog is open does not replace it', async () => {
    void requestAuthorization({ pendingId: 'own', operationId: 'core.bulk-reindex', gateBehavior: 'TYPED_CONFIRM' }); await flush();
    agent('during'); await flush();
    expect(host.active?.kind === 'authorize' && host.active.prompt.pendingId).toBe('own');
    expect(host.shadowRoot!.querySelector('dialog')!.open).toBe(true);
    expect(pill.status.model.sections.needs.map(f => f.id)).toContain('authorization:during');
  });
});

describe('C12 one decision, one execution', () => {
  it('a duplicate route is superseded; the panel answer resolves the one owner once', async () => {
    const { decision, prompt } = agent('c12'); await flush();
    const duplicate = requestAuthorization({ ...prompt, arrival: undefined });
    await expect(duplicate).resolves.toMatchObject({ approved: false, superseded: true });
    expect(host.shadowRoot!.querySelector('dialog')!.open, 'the duplicate opened no dialog').toBe(false);
    await openReview('authorization:c12'); await arm();
    const allow = answer('allow');
    press(allow); key(review()!, { key: '1' }); await flush();
    press(allow); await flush();
    await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
    await host.answerStatusRequest('c12', true);
    expect(host.statusRequests()).toHaveLength(0);
  });
});

describe('a waiting run reaches its request', () => {
  it("the run's Review opens that request's review in the panel; a typed one opens the security dialog", async () => {
    expect(STATUS_REVIEW_EVENT).toBeTypeOf('string');
    agent('from-run', { owningRunId: 'run-1', allowAlwaysSupported: false, expiresAt: undefined }); await flush();
    document.dispatchEvent(new CustomEvent(STATUS_REVIEW_EVENT, { detail: 'authorization:from-run' })); await flush();
    expect(pill.open).toBe(true);
    expect(review()!.getAttribute('data-fact')).toBe('authorization:from-run');
    pill.close(); await flush();
    agent('typed-run', { owningRunId: 'run-1', gateBehavior: 'TYPED_CONFIRM', riskTier: 'HIGH' }); await flush();
    document.dispatchEvent(new CustomEvent(STATUS_REVIEW_EVENT, { detail: 'authorization:typed-run' })); await flush();
    expect(pill.open).toBe(false);
    expect(host.active?.kind === 'authorize' && host.active.prompt.pendingId).toBe('typed-run');
    expect(host.shadowRoot!.querySelector('dialog')!.open).toBe(true);
  });
});

describe('waiting requests stay until their own owner ends them', () => {
  it("the engine's pending check withdraws a gone outside request but never the agent in Search's run-owned one", async () => {
    // The engine's pending store answers 404 for both: an MCP request it no longer holds, and a
    // tool call of the agent in Search, which lives in the run and is not a pending-store id.
    const fetchSpy = vi.fn(async (url: string) => String(url).includes('/api/authorizations/pending/')
      ? new Response('{}', { status: 404 }) : new Response('{}', { status: 503 }));
    vi.stubGlobal('fetch', fetchSpy);
    const outside = agent('gone-outside');
    const inApp = agent('run-call', { owningRunId: 'run-1', allowAlwaysSupported: false, expiresAt: undefined });
    await flush();
    __feedForTest({ status: { worker: { core: { indexState: 'IDLE', pendingJobs: 0 }, enrichment: {} } } as unknown as StatusSnapshot });
    await vi.waitFor(() => expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('/pending/gone-outside'))).toBe(true));
    await flush();
    await expect(outside.decision).resolves.toMatchObject({ approved: false, superseded: true });
    expect(pill.status.model.sections.needs.map(row => row.id)).toEqual(['authorization:run-call']);
    expect(await Promise.race([inApp.decision.then(() => 'answered'), Promise.resolve('waiting')])).toBe('waiting');
  });
});
