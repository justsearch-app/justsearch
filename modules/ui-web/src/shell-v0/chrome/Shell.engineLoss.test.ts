// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// S5c — engine loss after the app has loaded, browser runtime: the real Shell, status pill, status
// store and Health over a stubbed backend. Windows other than Health are probes: the treatment
// belongs to the frame, not to a window. The engine is lost by withholding every answer and every
// stream frame, through the status store's own test seams; nothing here imports the new modules.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './Shell.js';
import '../views/SystemSurface.js';
import '../views/HealthSurface.js';
import { __seedForTest } from '../../api/registry/SurfaceCatalogClient.js';
import { __feedContactForTest, __feedForTest, __resetAiStateForTest } from '../state/aiStateStore.js';
import { __resetStatusPollForTest } from '../utils/statusPoll.js';
import { __resetInferencePollForTest } from '../utils/inferencePoll.js';
import { __resetBootstrapForTest } from '../router/bootstrap.js';
import { __resetUserConfigForTest } from '../state/userConfigState.js';
import { __resetModalityForTest } from '../primitives/modality.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { EPHEMERAL_TOAST_EVENT } from '../components/advisory/ephemeralToast.js';
import statusFixture from '../../api/__fixtures__/status-response-live.json';

type Updating = HTMLElement & { updateComplete: Promise<unknown> };
const SEARCH = 'core.search-v3-surface';
const LIBRARY = 'core.library-surface';
const HELP = 'core.help-surface';
const SETTINGS = 'core.settings-surface';

for (const [tag, label] of [['jf-loss-probe-search', 'Ask or search'], ['jf-loss-probe-library', 'Folder name'],
  ['jf-loss-probe-help', 'Search help'], ['jf-loss-probe-settings', 'Setting']] as const) {
  if (!customElements.get(tag)) customElements.define(tag, class extends HTMLElement {
    connectedCallback(): void {
      if (!this.shadowRoot) this.attachShadow({ mode: 'open' }).innerHTML = `<textarea aria-label="${label}"></textarea>`;
    }
  });
}

const surface = (id: string, mountTag: string, extra: Record<string, unknown> = {}) => ({
  id, mountTag, audience: 'USER', placement: 'RAIL', presentation: { labelKey: id, descriptionKey: id },
  consumes: { operations: [], resources: [], prompts: [], diagnosticChannels: [] },
  provenance: { tier: 'CORE', contributorId: 'core', version: '1.0.0' }, ...extra,
});

/** Every engine answer: refused while `engine.down`; `/api/status` answers with the fixture. */
const engine = {
  down: false, calls: [] as { url: string; method: string }[],
  /** Only `/api/status` answers (an old engine's late answer); everything else stays down. */
  statusOnly: false,
  /** The `/api/status` body; `epoch` and `change` are its producer identity and revision headers. */
  status: statusFixture as Record<string, unknown>, epoch: '', change: 0,
  /** Only `/api/status` fails; inference and the rest keep answering. */
  statusDown: false,
  /** When `/api/status` last answered. */
  statusAnsweredAt: null as number | null,
};
const statusBody = (core: Record<string, unknown>, extra: Record<string, unknown> = {}): Record<string, unknown> => ({
  ...statusFixture, ...extra, worker: { ...statusFixture.worker, core: { ...statusFixture.worker.core, ...core } },
});
function respond(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const url = String((input as Request)?.url ?? input);
  engine.calls.push({ url, method: init?.method ?? 'GET' });
  if (url.includes('/api/registry/surfaces')) return Promise.resolve(new Response(JSON.stringify({ entries: [] }), { status: 200 }));
  if (engine.down || (engine.statusOnly && !url.includes('/api/status'))) return Promise.reject(new TypeError('Failed to fetch'));
  if (url.includes('/api/status')) {
    if (engine.statusDown) return Promise.reject(new TypeError('Failed to fetch'));
    engine.statusAnsweredAt = Date.now();
    const headers: Record<string, string> = engine.epoch ? { 'X-JustSearch-Status-Epoch': engine.epoch,
      'X-JustSearch-Status-Change': String(engine.change), 'X-JustSearch-Status-Reflected': String(engine.change) } : {};
    return Promise.resolve(new Response(JSON.stringify(engine.status), { status: 200, headers }));
  }
  return Promise.resolve(new Response(JSON.stringify({ entries: [] }), { status: 200 }));
}

async function resetEngineLoss(): Promise<void> {
  // The engine-loss store is module state. It does not exist before S5c: found by glob, nothing
  // matches there and the checks fail by assertion rather than at import.
  const store = import.meta.glob<typeof import('../state/engineLoss.js')>('../state/engineLoss.ts');
  for (const load of Object.values(store)) (await load()).__resetEngineLossForTest();
}

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  engine.down = false; engine.statusOnly = false; engine.calls = []; engine.status = statusFixture; engine.epoch = ''; engine.change = 0;
  engine.statusDown = false; engine.statusAnsweredAt = null;
  vi.stubGlobal('fetch', vi.fn(respond));
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest();
  await resetEngineLoss();
  __seedForTest({ schemaVersion: '1.0.0', catalogVersion: 1, namespace: 'core', primitive: 'Surface', entries: [
    surface(SEARCH, 'jf-loss-probe-search'), surface(LIBRARY, 'jf-loss-probe-library'),
    surface(HELP, 'jf-loss-probe-help', { placement: 'DEEPLINK' }),
    surface('core.system-surface', 'jf-system-surface', { members: ['core.health-surface'] }),
    surface('core.health-surface', 'jf-health-surface', { placement: 'DEEPLINK' }),
    surface(SETTINGS, 'jf-loss-probe-settings', { placement: 'MODAL' }),
  ] } as never);
});
afterEach(async () => {
  document.body.replaceChildren();
  __resetAiStateForTest(); __resetStatusPollForTest(); __resetInferencePollForTest();
  __resetBootstrapForTest(); __resetUserConfigForTest(); __resetModalityForTest();
  await resetEngineLoss();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function pass(ms: number): Promise<void> {
  // One second at a time: the status store's 5 s clock and the pill's 1 s refresh both run.
  for (let left = ms; left > 0; left -= 1_000) await vi.advanceTimersByTimeAsync(Math.min(1_000, left));
}

async function mountShell(): Promise<Updating> {
  const shell = document.createElement('jf-shell') as Updating;
  shell.setAttribute('api-base', 'http://127.0.0.1:4100');
  document.body.append(shell);
  await shell.updateComplete;
  await pass(1_000);
  __feedContactForTest();
  await pass(1_000);
  return shell;
}

async function goTo(shell: Updating, id: string): Promise<void> {
  requestSurfaceNavigation(id);
  await pass(1_000);
  await shell.updateComplete;
}

/** Lose the engine: no answer and no stream frame from now on. */
function loseEngine(): void { engine.down = true; }

/** Bring the engine back: answers resume and a stream frame arrives. */
async function restoreEngine(): Promise<void> {
  engine.down = false;
  __feedContactForTest();
  await pass(1_000);
}

const deepText = (root: Node | null | undefined): string => {
  if (!root) return '';
  let text = '';
  for (const node of (root as ParentNode).childNodes ?? []) {
    if (node.nodeType === Node.TEXT_NODE) text += node.textContent;
    else if (node instanceof HTMLTextAreaElement) text += ` ${node.value} `;
    else if (node instanceof Element) {
      text += ` ${deepText(node)}`;
      if (node.shadowRoot) text += ` ${deepText(node.shadowRoot)}`;
    }
  }
  return text.replace(/\s+/g, ' ');
};

/** The treatment's section, when it shows. */
function treatment(shell: Updating): HTMLElement | null {
  const host = shell.shadowRoot!.querySelector('jf-engine-loss');
  return host?.shadowRoot?.querySelector<HTMLElement>('[data-testid="engine-loss"]') ?? null;
}

function button(shell: Updating, name: string): (HTMLElement & { onActivate: () => unknown }) | null {
  const section = treatment(shell);
  return [...section?.querySelectorAll<HTMLElement & { onActivate: () => unknown }>('jf-button') ?? []]
    .find(b => b.textContent?.replace(/\s+/g, ' ').trim() === name) ?? null;
}

function stage(shell: Updating): HTMLElement { return shell.shadowRoot!.querySelector<HTMLElement>('.stage')!; }

function deepActive(): Element | null {
  let active = document.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active;
}

function probeField(shell: Updating): HTMLTextAreaElement | null {
  const jfStage = shell.shadowRoot!.querySelector('jf-stage')!;
  for (const el of jfStage.shadowRoot?.querySelectorAll('*') ?? []) {
    const field = el.shadowRoot?.querySelector('textarea');
    if (field) return field;
  }
  return null;
}

/** Lose the engine and wait out the contact window and the pill's grace. */
async function outage(shell: Updating): Promise<void> {
  loseEngine();
  await pass(56_000);
  await shell.updateComplete;
}

describe('SC1 engine loss after the app has loaded', () => {
  it.each([
    ['Search', SEARCH], ['Library', LIBRARY], ['Help', HELP],
  ])('dims the window area from %s behind the explanation, actions and times, keeping the top bar', async (_name, id) => {
    const shell = await mountShell();
    await goTo(shell, id);
    await outage(shell);
    const section = treatment(shell);
    expect(section, 'the treatment shows').not.toBeNull();
    const words = deepText(section);
    expect(words).toContain('JustSearch can’t reach its engine');
    expect(words).toContain('Searching and indexing are on hold. Your files and your index are safe. JustSearch tries again every few seconds.');
    expect(button(shell, 'Try again'), 'the browser offers Try again').not.toBeNull();
    expect(button(shell, 'Open Health')).not.toBeNull();
    expect(button(shell, 'Copy report')).not.toBeNull();
    expect(button(shell, 'Restart the engine'), 'the browser has no restart').toBeNull();
    expect(words).toMatch(/Last connected \d+ s ago/);
    expect(words).toContain('The report leaves out paths and keys');
    expect(words).toContain('Restart it where it runs, then try again.');
    expect(stage(shell).inert, 'the window area takes no input').toBe(true);
    const bar = shell.shadowRoot!.querySelector<HTMLElement>('jf-top-bar')!;
    expect(bar.inert || !!bar.closest('[inert]'), 'the top bar stays usable').toBe(false);
    const pill = shell.shadowRoot!.querySelector('jf-status-pill')!;
    expect(deepText(pill.shadowRoot), 'pill and treatment tell one story').toContain('Can\'t reach the engine');
  });
});

describe('SC1 from open overlays', () => {
  it('appears beneath an open Settings window, which stays open and usable', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await goTo(shell, SETTINGS);
    const settings = shell.shadowRoot!.querySelector('jf-settings-window') as Updating & { open: boolean };
    expect(settings.open, 'Settings is open before the loss').toBe(true);
    await outage(shell);
    expect(treatment(shell), 'the treatment shows').not.toBeNull();
    expect(settings.open, 'Settings stays open on top').toBe(true);
    expect(settings.inert || !!settings.closest('[inert]'), 'Settings keeps taking input').toBe(false);
  });

  it('appears with the status panel open, which stays open and says the engine cannot be reached', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    const pill = shell.shadowRoot!.querySelector('jf-status-pill') as Updating & { open: boolean; toggle(): void };
    pill.toggle(); await pill.updateComplete;
    expect(pill.open).toBe(true);
    await outage(shell);
    expect(treatment(shell), 'the treatment shows').not.toBeNull();
    expect(pill.open, 'the panel stays open').toBe(true);
    expect(deepText(pill.shadowRoot)).toContain('Can\'t reach the engine');
  });
});

describe('SC1 SC7 what is not an outage', () => {
  it('a contact gap shorter than the grace shows nothing', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    loseEngine();
    const { getAiState } = await import('../state/aiStateStore.js');
    let unreachableSeen = false;
    for (let second = 0; second < 48; second++) {
      await pass(1_000);
      unreachableSeen ||= !getAiState().connection.reachable;
      expect(treatment(shell), `nothing at ${second + 1} s`).toBeNull();
    }
    expect(unreachableSeen, 'the gap reached the unreachable state before the grace ended').toBe(true);
    await restoreEngine();
    await pass(30_000);
    expect(treatment(shell)).toBeNull();
    expect(stage(shell).inert).toBe(false);
  });

  it('polls failing while live streams keep arriving is not an outage', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    loseEngine();
    for (let second = 0; second < 180; second += 15) {
      await pass(15_000);
      __feedContactForTest(); // a stream heartbeat: the engine is alive, its polls are starved
      expect(treatment(shell), `nothing at ${second + 15} s`).toBeNull();
    }
    expect(stage(shell).inert).toBe(false);
    const pill = shell.shadowRoot!.querySelector('jf-status-pill')!;
    expect(deepText(pill.shadowRoot)).not.toContain('Can\'t reach the engine');
  });
});

describe('SC4 Open Health', () => {
  it('shows the last known facts, each with when it was seen, and nothing live', async () => {
    engine.status = statusBody({ indexState: 'SERVING', searchableDocuments: 1234, pendingJobs: 7 },
      { indexBasePath: '/home/secret-person/.justsearch/index' });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000); // the status poll has answered
    await outage(shell);
    const open = button(shell, 'Open Health');
    expect(open, 'Open Health is offered').not.toBeNull();
    open!.onActivate();
    await pass(1_000); await shell.updateComplete;
    const health = (() => {
      const find = (root: ParentNode): Element | null => {
        for (const el of root.querySelectorAll('*')) {
          if (el.localName === 'jf-health-surface') return el;
          const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
        }
        return null;
      };
      return find(shell.shadowRoot!) as Updating | null;
    })();
    expect(health, 'Health opened').not.toBeNull();
    await health!.updateComplete;
    const words = deepText(health!.shadowRoot);
    expect(words).toContain('JustSearch can’t reach its engine');
    expect(words).toContain('Nothing here is live.');
    expect(words).toMatch(/Engine Can’t be reached · last connected \d+ (s|min) ago/);
    expect(words).toMatch(/Searchable files 1,234 · last seen \d+ (s|min) ago/);
    expect(words).toMatch(/Files waiting 7 · last seen \d+ (s|min) ago/);
    expect(words).toMatch(/Index Serving · last seen \d+ (s|min) ago/);
    for (const live of ['Auto-refresh', 'Quick actions', 'Up to date', 'Healthy', 'secret-person', '127.0.0.1']) {
      expect(words, `Health claims nothing live: ${live}`).not.toContain(live);
    }
    expect(words.replaceAll('last connected', ''), 'no "connected" claim').not.toMatch(/connected/i);
    // The treatment steps aside to a band so Health stays readable; its actions remain.
    expect(stage(shell).inert, 'Health takes input').toBe(false);
    expect(treatment(shell), 'the band still says so').not.toBeNull();
    expect(button(shell, 'Try again')).not.toBeNull();
    expect(button(shell, 'Copy report')).not.toBeNull();
    expect(button(shell, 'Open Health'), 'no route to where you are').toBeNull();
    await restoreEngine();
    await health!.updateComplete;
    expect(deepText(health!.shadowRoot)).not.toContain('Nothing here is live.');
  });
});

/** Open Health from the treatment and return it. */
async function openHealth(shell: Updating): Promise<Updating> {
  const open = button(shell, 'Open Health');
  expect(open, 'Open Health is offered').not.toBeNull();
  open!.onActivate();
  await pass(1_000); await shell.updateComplete;
  const find = (root: ParentNode): Element | null => {
    for (const el of root.querySelectorAll('*')) {
      if (el.localName === 'jf-health-surface') return el;
      const inner = el.shadowRoot && find(el.shadowRoot); if (inner) return inner;
    }
    return null;
  };
  const health = find(shell.shadowRoot!) as Updating | null;
  expect(health, 'Health opened').not.toBeNull();
  await health!.updateComplete;
  return health!;
}

/** The age a Health fact states, as the range of seconds its words allow. */
function factAge(health: Updating, label: string): { words: string; range: [number, number] } {
  const words = health.shadowRoot!.querySelector(`[data-fact="${label}"] .when`)?.textContent ?? '';
  const match = /(just now)|(\d+) s ago|(\d+) min ago|(\d+) h ago/.exec(words);
  if (!match) return { words, range: [Number.NaN, Number.NaN] };
  if (match[1]) return { words, range: [0, 2] };
  if (match[2]) return { words, range: [Number(match[2]) - 1, Number(match[2]) + 1] };
  if (match[3]) return { words, range: [Number(match[3]) * 60, Number(match[3]) * 60 + 59] };
  return { words, range: [Number(match[4]) * 3600, Number(match[4]) * 3600 + 3599] };
}

/** Each fact states the age of its own observation. */
function expectAge(health: Updating, label: string, observedAt: number | null): void {
  expect(observedAt, `${label} was observed`).not.toBeNull();
  const age = (Date.now() - observedAt!) / 1000;
  const { words, range } = factAge(health, label);
  expect(age >= range[0] && age <= range[1], `${label} says "${words}", observed ${Math.round(age)} s ago`).toBe(true);
}

const STATUS_FACTS = ['Index', 'Searchable files', 'Files waiting'];

describe('SC4 each Health fact carries the time it was itself observed', () => {
  it('status failing while inference answers: the index facts keep the status answer’s age, not the newer contact', async () => {
    engine.status = statusBody({ indexState: 'SERVING', searchableDocuments: 1234, pendingJobs: 7 });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000);
    engine.statusDown = true;
    await pass(120_000);
    expect(treatment(shell), 'inference answers: no outage yet').toBeNull();
    const { getAiState } = await import('../state/aiStateStore.js');
    expect(getAiState().connection.lastSuccessMs! - engine.statusAnsweredAt!, 'inference contact is newer than status').toBeGreaterThan(100_000);
    await outage(shell);
    const health = await openHealth(shell);
    for (const label of STATUS_FACTS) expectAge(health, label, engine.statusAnsweredAt);
    expectAge(health, 'Engine', getAiState().connection.lastContactMs);
  });

  it('polls failing while live streams keep arriving: the index facts keep the last status answer’s age', async () => {
    engine.status = statusBody({ indexState: 'SERVING', searchableDocuments: 1234, pendingJobs: 7 });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000);
    loseEngine();
    for (let second = 0; second < 180; second += 15) {
      await pass(15_000);
      __feedContactForTest();
    }
    expect(treatment(shell), 'streams arrive: no outage yet').toBeNull();
    await outage(shell);
    const health = await openHealth(shell);
    const { getAiState } = await import('../state/aiStateStore.js');
    for (const label of STATUS_FACTS) expectAge(health, label, engine.statusAnsweredAt);
    expectAge(health, 'Engine', getAiState().connection.lastContactMs);
  });

  it('everything lost at once: every fact states the same age, its own', async () => {
    engine.status = statusBody({ indexState: 'SERVING', searchableDocuments: 1234, pendingJobs: 7 });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000);
    await outage(shell);
    await pass(120_000);
    const health = await openHealth(shell);
    const { getAiState } = await import('../state/aiStateStore.js');
    for (const label of STATUS_FACTS) expectAge(health, label, engine.statusAnsweredAt);
    expectAge(health, 'Engine', getAiState().connection.lastContactMs);
    expect(new Set(['Engine', ...STATUS_FACTS].map(label => factAge(health, label).words.replace(/^.*?(\d+ \w+ ago|just now)$/, '$1'))).size,
      'equally stale facts read alike').toBe(1);
  });
});

describe('SC6 SC9 leaving, focus and announcements', () => {
  it('moves focus in once, leaves without a reload when the engine is back, returns focus, and a second outage behaves the same', async () => {
    const reload = vi.spyOn(window.location, 'reload').mockImplementation(() => {});
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    const field = probeField(shell)!;
    field.focus();
    expect(deepActive()).toBe(field);
    for (const round of [1, 2]) {
      await outage(shell);
      const section = treatment(shell);
      expect(section, `round ${round}: shows`).not.toBeNull();
      expect(deepActive(), `round ${round}: focus moved into the treatment`).toBe(section);
      expect(section!.getAttribute('aria-labelledby'), 'named by its heading').toBeTruthy();
      expect(section!.getAttribute('aria-describedby'), 'described by its explanation').toBeTruthy();
      // Announced once: the status line is empty until the person acts, ages update silently.
      expect(section!.querySelector('[role="status"]')!.textContent!.trim()).toBe('');
      expect(section!.querySelectorAll('[role="alert"],[aria-live="assertive"]')).toHaveLength(0);
      const pillLive = deepText(shell.shadowRoot!.querySelector('jf-status-pill')!.shadowRoot);
      expect(pillLive.split('JustSearch can’t reach its engine').length - 1, 'the pill does not repeat the treatment').toBe(0);
      const before = section!.innerHTML;
      await pass(5_000);
      expect(deepActive(), `round ${round}: focus stays put`).toBe(treatment(shell));
      expect(treatment(shell)!.innerHTML).not.toBe(before); // ages move
      await restoreEngine();
      await shell.updateComplete; await pass(1_000);
      expect(treatment(shell), `round ${round}: leaves`).toBeNull();
      expect(stage(shell).inert, `round ${round}: the window area takes input again`).toBe(false);
      expect(deepActive(), `round ${round}: focus returns to where it was`).toBe(field);
    }
    expect(reload, 'never reloads the window').not.toHaveBeenCalled();
  });
});

describe('SC3 browser Try again', () => {
  it('reads the status once per request, says the engine still cannot be reached, then leaves when it answers', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await outage(shell);
    const words = deepText(treatment(shell));
    expect(words).not.toMatch(/reload/i);
    const again = button(shell, 'Try again');
    expect(again).not.toBeNull();
    // Try again is a fresh status read; the ordinary poll keeps its own schedule beside it.
    const statusReads = () => engine.calls.filter(c => c.url.includes('/api/status?fresh=true')).length;
    const before = statusReads();
    again!.onActivate(); again!.onActivate(); again!.onActivate();
    await pass(1_000); await shell.updateComplete;
    expect(statusReads() - before, 'one read for three clicks').toBe(1);
    expect(deepText(treatment(shell))).toContain('Still can’t reach the engine.');
    engine.down = false;
    button(shell, 'Try again')!.onActivate();
    await pass(1_000); await shell.updateComplete;
    expect(treatment(shell), 'the answer ends the outage').toBeNull();
  });
});

/** Replace the clipboard for one test. */
function useClipboard(clipboard: { writeText(text: string): Promise<void> }): void {
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true });
  restoreClipboard = () => {
    if (original) Object.defineProperty(navigator, 'clipboard', original);
    else Reflect.deleteProperty(navigator, 'clipboard');
  };
}
let restoreClipboard: (() => void) | null = null;
afterEach(() => { restoreClipboard?.(); restoreClipboard = null; });

describe('SC5 Copy report', () => {
  const FIELDS = ['productVersion', 'runtimeKind', 'operatingSystem', 'time', 'lastContactAgeSeconds',
    'supervisorState', 'reason', 'recentExitCodes'];
  it('copies only the fixed fields, with no path, host, key or error text', async () => {
    // Assembled at run time so the source holds no key-shaped literal for the secret scan.
    const secret = ['sk', 'live', 'SECRET', '0042'].join('-');
    engine.status = statusBody({ indexState: 'SERVING' }, { indexBasePath: `/home/secret-person/${secret}`,
      knowledgeServerStartError: `boom at C:\\Users\\secret-person\\${secret}` });
    const writes: string[] = [];
    useClipboard({ writeText: async (text: string) => { writes.push(text); } });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000);
    // Every reachable error path carries a secret.
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError(`connect ECONNREFUSED 10.1.2.3:4100 ${secret} /home/secret-person`); }));
    await outage(shell);
    const copy = button(shell, 'Copy report');
    expect(copy, 'Copy report is offered').not.toBeNull();
    copy!.onActivate();
    await pass(1_000); await shell.updateComplete;
    expect(writes).toHaveLength(1);
    const report = JSON.parse(writes[0]!) as Record<string, unknown>;
    expect(Object.keys(report).sort()).toEqual([...FIELDS].sort());
    expect(report.runtimeKind).toBe('browser');
    expect(report.reason).toBe('unreachable');
    expect(report.supervisorState).toBeNull();
    expect(typeof report.lastContactAgeSeconds).toBe('number');
    for (const leak of [secret, 'secret-person', '10.1.2.3', '127.0.0.1', '4100', 'ECONNREFUSED', 'boom', '/home', 'C:\\']) {
      expect(writes[0], `no ${leak}`).not.toContain(leak);
    }
    expect(deepText(treatment(shell))).toContain('Report copied.');
  });

  it('shows the report ready to select when the clipboard refuses it', async () => {
    useClipboard({ writeText: async () => { throw new DOMException('denied', 'NotAllowedError'); } });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await outage(shell);
    const copy = button(shell, 'Copy report');
    expect(copy, 'Copy report is offered').not.toBeNull();
    copy!.onActivate();
    await pass(1_000); await shell.updateComplete;
    const field = treatment(shell)!.querySelector('textarea');
    expect(field, 'the report is shown').not.toBeNull();
    expect(field!.getAttribute('aria-label')).toBe('Report ready to select');
    expect(Object.keys(JSON.parse(field!.value)).sort()).toEqual([...FIELDS].sort());
    expect(deepActive(), 'ready to select').toBe(field);
  });
});

describe('SC7 SC8 what the treatment keeps and never does', () => {
  it('keeps the draft and the open window, and sends nothing again when the engine is back', async () => {
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    const field = probeField(shell)!;
    field.value = 'what did the landlord say about heating';
    const windowBefore = (field.getRootNode() as ShadowRoot).host;
    await outage(shell);
    expect(treatment(shell)).not.toBeNull();
    const sentBefore = engine.calls.filter(c => c.method !== 'GET').length;
    await restoreEngine();
    await pass(30_000);
    expect(probeField(shell), 'the same window').toBe(field);
    expect((field.getRootNode() as ShadowRoot).host).toBe(windowBefore);
    expect(field.value, 'the draft survives').toBe('what did the landlord say about heating');
    expect(engine.calls.filter(c => c.method !== 'GET').length, 'nothing resubmitted').toBe(sentBefore);
  });

  it('a typed approval open during the loss keeps its dialog, focus and rules; the treatment answers nothing', async () => {
    const { requestAuthorization } = await import('../operations/authorizationBroker.js');
    const { modalOwnsFocus } = await import('../primitives/modality.js');
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    const host = shell.shadowRoot!.querySelector('jf-authorization-host') as Updating;
    let settled = false;
    const decision = requestAuthorization({ pendingId: 'typed-loss', operationId: 'core.forget-folder', gateBehavior: 'TYPED_CONFIRM' })
      .then(result => { settled = true; return result; });
    await pass(1_000); await host.updateComplete;
    const dialog = host.shadowRoot!.querySelector('dialog')!;
    expect(dialog.open).toBe(true);
    const focusedBefore = deepActive();
    await outage(shell);
    expect(treatment(shell), 'the treatment shows beneath').not.toBeNull();
    expect(dialog.open, 'the security dialog stays open').toBe(true);
    expect(modalOwnsFocus(), 'and modal').toBe(true);
    expect(deepActive(), 'focus stays in the dialog').toBe(focusedBefore);
    await restoreEngine();
    await pass(2_000);
    expect(dialog.open, 'still waiting for its typed answer').toBe(true);
    expect(settled, 'nothing answered it').toBe(false);
    dialog.close();
    await pass(1_000);
    void decision;
  });

  it('returning never announces an approval already waiting, or a completion that happened unseen, as new', async () => {
    const toasts: { message?: string; silent?: boolean }[] = [];
    const onToast = (event: Event) => toasts.push((event as CustomEvent).detail);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const { requestAuthorization } = await import('../operations/authorizationBroker.js');
      engine.status = statusBody({ indexState: 'INDEXING', pendingJobs: 40, indexedDocuments: 10, searchableDocuments: 10 });
      const shell = await mountShell();
      await goTo(shell, SEARCH);
      const pill = shell.shadowRoot!.querySelector('jf-status-pill') as Updating & { status: { assertiveKey: string; assertive: string } };
      // An agent's request arrives live before the outage and is announced once.
      const request = { pendingId: 'agent-waiting', operationId: 'core.reindex', gateBehavior: 'INLINE_CONFIRM',
        arrival: 'needs-you', requestedBy: 'Claude Desktop' } as const;
      void requestAuthorization({ ...request, live: true });
      await pass(12_000);
      expect(pill.status.assertiveKey, 'announced on its live arrival').toBe('authorization:agent-waiting');
      const announcedBefore = pill.status.assertive;
      pill.status.assertiveKey = ''; pill.status.assertive = '';
      await outage(shell);
      expect(treatment(shell)).not.toBeNull();
      engine.status = statusBody({ indexState: 'SERVING', pendingJobs: 0, indexedDocuments: 50, searchableDocuments: 50, indexHealthy: true });
      await restoreEngine();
      // The engine replays the request it still holds; it is listed, not announced again.
      void requestAuthorization({ ...request, live: false });
      await pass(25_000);
      expect(treatment(shell)).toBeNull();
      expect(announcedBefore).not.toBe('');
      expect(pill.status.assertiveKey, 'the waiting request is not announced again').toBe('');
      expect(pill.status.assertive).toBe('');
      expect(toasts.filter(t => !t.silent && /up to date|complete|finished/i.test(t.message ?? '')),
        'an end that happened unseen is not announced').toEqual([]);
    } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast); }
  });
});

describe('SC6 engine identity', () => {
  it('a new engine identity rebinds; a late answer from the old one neither ends the outage nor shows', async () => {
    engine.epoch = 'engine-old'; engine.change = 1;
    engine.status = statusBody({ searchableDocuments: 111 });
    const shell = await mountShell();
    await goTo(shell, SEARCH);
    await pass(10_000);
    const { getAiState } = await import('../state/aiStateStore.js');
    expect(getAiState().status?.worker?.core?.searchableDocuments).toBe(111);
    // The engine restarts with a new identity, which answers and rebinds.
    engine.epoch = 'engine-new'; engine.change = 1;
    engine.status = statusBody({ searchableDocuments: 222 });
    await pass(10_000);
    expect(getAiState().status?.worker?.core?.searchableDocuments).toBe(222);
    await outage(shell);
    expect(treatment(shell)).not.toBeNull();
    // A late answer from the old engine: ignored, the outage goes on.
    engine.down = false; engine.statusOnly = true;
    engine.epoch = 'engine-old'; engine.change = 9;
    engine.status = statusBody({ searchableDocuments: 111 });
    await pass(12_000);
    expect(getAiState().status?.worker?.core?.searchableDocuments, 'the old answer is not shown').toBe(222);
    expect(treatment(shell), 'the old engine answering is not the engine coming back').not.toBeNull();
    // The new engine answers: the treatment leaves.
    engine.statusOnly = false;
    engine.epoch = 'engine-new'; engine.change = 2;
    engine.status = statusBody({ searchableDocuments: 333 });
    await pass(12_000);
    expect(getAiState().status?.worker?.core?.searchableDocuments).toBe(333);
    expect(treatment(shell)).toBeNull();
  });
});
