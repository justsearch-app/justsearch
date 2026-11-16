// SPDX-License-Identifier: Apache-2.0
// fm-07 form controls in a real browser (decisions PD1-PD6): the reading-rule radios and checkbox are
// drawn by the app (ring or box readable at 3:1 in dark and light, teal only when on, a visible keyboard
// focus, system controls in forced colours); the two disclosures show the app's own chevron, never the
// browser's triangle; the helper sentence is gone from view but kept as the rule field's description;
// with no rules only "Add a rule" shows, and an added field left empty goes without a save; "and"
// between the two times is 13 px body like the words around it.
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createServer, type ViteDevServer } from 'vite';
import { chromium, type Browser, type Page } from 'playwright-core';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

let server: ViteDevServer, browser: Browser, scratch: string, origin: string;
/** Library over a stubbed settings owner; ?rules= lists the saved skip rules (comma separated). The page
 *  background is the app's own --surface-0, as the Shell paints it behind every area. */
const harness = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<link rel="stylesheet" href="/src/styles/tokens.css"><style>body { background: var(--surface-0); }</style></head><body><script type="module">
const params = new URLSearchParams(location.search);
document.documentElement.dataset.theme = params.get('theme') || 'dark';
const rules = (params.get('rules') || '').split(',').filter(Boolean);
const s = { revision: 1, lastKey: '0194f72c-0000-7000-8000-000000000001', ui: { theme: 'dark', excludePatterns: rules,
  indexingPause: { paused: false, untilEpochMs: null, reason: null, pauseOnBattery: false } } };
window.fixture = { posts: [], ops: [] };
const fx = window.fixture;
const witness = () => ({ acceptedRevision: s.revision, lastCommittedOperationKey: s.lastKey });
window.fetch = async (url, init) => {
  const id = String(url).split('/').at(-2);
  const body = JSON.parse(String(init?.body ?? '{}'));
  fx.ops.push({ id, args: body.args });
  s.revision++; s.lastKey = body.idempotencyKey;
  return Response.json({ success: true, message: 'Committed', structuredData: { witness: witness() } });
};
const { createMockHostApi } = await import('/src/shell-v0/plugin-api/testHostApi.ts');
const ai = await import('/src/shell-v0/state/aiStateStore.ts');
await import('/src/shell-v0/views/LibrarySurface.ts');
ai.__feedForTest({ status: { worker: { core: { indexedDocuments: 8, ownSearchableDocuments: 8, indexState: 'IDLE', indexHealthy: true },
  migration: { migrationState: 'IDLE' } }, readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } } } });
const host = createMockHostApi({ data: {
  invokeOperation: async () => ({ success: true, structuredData: { dryRun: false, matchedFiles: 0,
    confirmedRemovals: 0, capped: false, perPattern: [], uncheckedScopes: [] } }),
  fetch: async (url, init) => {
    if (url === '/api/indexing/excludes') return Response.json({ source: 'settings.json', sourceDetail: null, patterns: s.ui.excludePatterns });
    if (url.startsWith('/api/indexing/forgets?')) return Response.json({ items: [], complete: true, nextId: null });
    if (url !== '/api/settings/v2') return new Response('{}', { status: 503 });
    if (init?.method !== 'POST') return Response.json({ settingsMode: 'persistent', ui: structuredClone(s.ui), witness: witness() });
    const attempt = JSON.parse(init.body);
    fx.posts.push(attempt.ui.excludePatterns);
    s.ui = { ...s.ui, excludePatterns: attempt.ui.excludePatterns }; s.revision++; s.lastKey = attempt.operationKey;
    return Response.json({ state: 'COMPLETE', operationKey: attempt.operationKey, witness: witness(), ui: structuredClone(s.ui) });
  } } });
const el = document.createElement('jf-library-surface');
el.host_ = host; el.refresh = async () => true;
document.body.append(el);
window.library = el;
window.editor = () => el.shadowRoot.querySelector('jf-indexing-rules-editor');
window.settle = async () => { for (let i = 0; i < 35; i++) { await new Promise(resolve => setTimeout(resolve)); await el.updateComplete; await window.editor()?.updateComplete; } };
window.focused = () => { let active = document.activeElement; while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  if (!active || active === document.body) return 'body';
  return active.getAttribute('aria-label') || (active.textContent || '').trim() || active.id || active.tagName; };
/** Contrast of a colour drawn over another (both composited on the page's own background first). */
window.contrast = (fore, back) => {
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = 1;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const page = getComputedStyle(document.body).backgroundColor;
  const pixel = (...colors) => { ctx.clearRect(0, 0, 1, 1); for (const c of colors) { ctx.fillStyle = c; ctx.fillRect(0, 0, 1, 1); }
    return [...ctx.getImageData(0, 0, 1, 1).data].slice(0, 3); };
  const lum = rgb => { const c = rgb.map(v => { const n = v / 255; return n <= .04045 ? n / 12.92 : ((n + .055) / 1.055) ** 2.4; });
    return .2126 * c[0] + .7152 * c[1] + .0722 * c[2]; };
  const b = lum(pixel(page, back)), f = lum(pixel(page, back, fore));
  return (Math.max(b, f) + .05) / (Math.min(b, f) + .05);
};
/** The colour an element's background resolves to, walking out through shadow hosts. */
window.backOf = node => {
  for (let at = node; at; at = at.parentElement ?? at.getRootNode().host ?? null) {
    const color = getComputedStyle(at).backgroundColor;
    if (color !== 'rgba(0, 0, 0, 0)' && color !== 'transparent') return color;
  }
  return 'rgba(0, 0, 0, 0)';
};
/** The used colour of a token inside the editor's shadow root. */
window.token = name => { const probe = document.createElement('i'); probe.style.color = 'var(' + name + ')';
  window.editor().shadowRoot.append(probe); const value = getComputedStyle(probe).color; probe.remove(); return value; };
</script></body></html>`;
beforeAll(async () => {
  scratch = await mkdtemp(path.join(tmpdir(), 'fm07-controls-browser-'));
  server = await createServer({ configFile: false, root: process.cwd(), cacheDir: scratch,
    server: { host: '127.0.0.1', port: 0 }, plugins: [{ name: 'fm07-controls-fixture', configureServer(vite) {
      vite.middlewares.use((req, res, next) => {
        if (!req.url?.startsWith('/fm07-controls.html')) return next();
        res.setHeader('Content-Type', 'text/html'); res.end(harness);
      });
    } }],
  });
  await server.listen();
  expect(server.resolvedUrls).not.toBeNull(); expect(server.resolvedUrls!.local[0]).toBeDefined();
  origin = server.resolvedUrls!.local[0]!;
  browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
}, 30_000);
afterAll(async () => { await browser?.close(); await server?.close(); if (scratch) await rm(scratch, { recursive: true, force: true }); });

type FixtureWindow = Window & { settle(): Promise<void>; focused(): string; contrast(fore: string, back: string): number;
  backOf(node: Element): string; token(name: string): string; fixture: { posts: string[][]; ops: unknown[] } };
const settle = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).settle());
const focused = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).focused());
const posts = (page: Page) => page.evaluate(() => (window as unknown as FixtureWindow).fixture.posts);
async function open(theme: 'dark' | 'light' = 'dark', rules: string[] = []): Promise<Page> {
  const page = await browser.newPage({ viewport: { width: 1024, height: 700 }, colorScheme: theme });
  page.setDefaultTimeout(3000);
  const errors: Error[] = [];
  page.on('pageerror', error => { errors.push(error); });
  await page.goto(`${origin}fm07-controls.html?theme=${theme}&rules=${encodeURIComponent(rules.join(','))}`, { timeout: 45_000 });
  await page.waitForFunction(() => typeof Reflect.get(window, 'settle') === 'function');
  await settle(page);
  expect(errors).toEqual([]);
  expect(await page.getByRole('radio', { name: 'Any time', exact: true }).isEnabled(), 'reading rules loaded').toBe(true);
  return page;
}
const anyTime = (page: Page) => page.getByRole('radio', { name: 'Any time', exact: true });
const between = (page: Page) => page.getByRole('radio', { name: 'Only between', exact: true });
const battery = (page: Page) => page.getByRole('checkbox', { name: 'Pause while on battery', exact: true });
const ruleFields = (page: Page) => page.getByRole('textbox', { name: /^Skip rule \d+$/ });
const removeButtons = (page: Page) => page.getByRole('button', { name: /^Remove skip rule \d+$/ });
/** Where focus is in every frame while `act` runs and the page settles: a late repair still shows the page. */
async function focusTrail(page: Page, act: () => Promise<void>): Promise<string[]> {
  await page.evaluate(() => {
    const w = window as unknown as FixtureWindow & { trail: string[]; tracing: boolean };
    w.trail = []; w.tracing = true;
    const read = () => { w.trail.push(w.focused()); if (w.tracing) requestAnimationFrame(read); };
    requestAnimationFrame(read);
  });
  await act(); await settle(page);
  return page.evaluate(() => { const w = window as unknown as { trail: string[]; tracing: boolean }; w.tracing = false; return w.trail; });
}

/** What a control draws: its edge colour and fill, its size, and its contrast against what is behind it. */
async function drawn(control: ReturnType<typeof anyTime>) {
  return control.evaluate(node => {
    const w = window as unknown as FixtureWindow;
    const style = getComputedStyle(node);
    const back = w.backOf(node.parentElement!);
    return { appearance: style.appearance, edge: style.borderTopColor, edgeWidth: parseFloat(style.borderTopWidth),
      fill: style.backgroundColor, width: node.getBoundingClientRect().width, height: node.getBoundingClientRect().height,
      edgeContrast: w.contrast(style.borderTopColor, back), teal: w.token('--accent') };
  });
}

describe('PD1: the reading-rule radios and checkbox are drawn by the app', () => {
  for (const theme of ['dark', 'light'] as const) {
    it(`${theme}: unselected ring and box read at 3:1, teal only when on`, async () => {
      const page = await open(theme);
      try {
        const off = [await drawn(between(page)), await drawn(battery(page))];
        for (const [name, d] of [['Only between (off)', off[0]!], ['Pause while on battery (off)', off[1]!]] as const) {
          expect(d.appearance, `${name}: not the browser's own control`).toBe('none');
          expect(d.edgeWidth, `${name}: drawn edge (1.5 px, one device pixel at 1x)`).toBeGreaterThanOrEqual(1);
          expect(d.edgeContrast, `${name}: edge against the page`).toBeGreaterThanOrEqual(3);
          expect(d.edge, `${name}: not teal while off`).not.toBe(d.teal);
          expect(d.fill, `${name}: not teal while off`).not.toBe(d.teal);
          expect(d.width, `${name}: 16 px as drawn`).toBe(16); expect(d.height).toBe(16);
        }
        const on = await drawn(anyTime(page));
        expect(on.appearance).toBe('none');
        expect(on.edge, 'Any time (on): teal ring').toBe(on.teal);
        await battery(page).check(); await settle(page);
        const checked = await drawn(battery(page));
        expect(checked.fill, 'Pause while on battery (on): teal box').toBe(checked.teal);
        expect(await battery(page).isChecked()).toBe(true);
      } finally { await page.close(); }
    }, 60_000);

    it(`${theme}: keyboard focus on a radio and on the checkbox is visible`, async () => {
      const page = await open(theme);
      try {
        await page.locator('jf-indexing-rules-editor').evaluate(el => el.shadowRoot!.querySelector<HTMLInputElement>('input[type=time]')!.focus());
        await page.keyboard.press('Shift+Tab');
        expect(await anyTime(page).evaluate(node => node.matches(':focus'))).toBe(true);
        const ring = async (control: ReturnType<typeof anyTime>) => control.evaluate(node => {
          const style = getComputedStyle(node);
          return { style: style.outlineStyle, width: parseFloat(style.outlineWidth), visible: node.matches(':focus-visible') };
        });
        const radio = await ring(anyTime(page));
        expect(radio.visible).toBe(true);
        expect(radio.style).not.toBe('none'); expect(radio.width).toBeGreaterThanOrEqual(2);
        await battery(page).focus(); await page.keyboard.press('Shift+Tab'); await page.keyboard.press('Tab');
        expect(await battery(page).evaluate(node => node.matches(':focus'))).toBe(true);
        const box = await ring(battery(page));
        expect(box.visible).toBe(true);
        expect(box.style).not.toBe('none'); expect(box.width).toBeGreaterThanOrEqual(2);
      } finally { await page.close(); }
    }, 60_000);
  }

  it('forced colours: the radios and checkbox are the system controls', async () => {
    const page = await open('dark');
    try {
      await page.emulateMedia({ forcedColors: 'active' }); await settle(page);
      for (const control of [anyTime(page), between(page), battery(page)]) {
        expect(await control.evaluate(node => getComputedStyle(node).appearance)).toBe('auto');
      }
    } finally { await page.close(); }
  }, 60_000);
});

describe('PD2: the two disclosures show the app\'s own mark', () => {
  for (const theme of ['dark', 'light'] as const) {
    it(`${theme}: "How rules work" and "About reading times" have no browser triangle; the chevron turns when open`, async () => {
      const page = await open(theme);
      try {
        for (const words of ['How rules work', 'About reading times']) {
          const summary = page.locator('summary', { hasText: words });
          expect(await summary.count(), words).toBe(1);
          const look = await summary.evaluate(node => {
            const style = getComputedStyle(node);
            const marker = getComputedStyle(node, '::marker');
            const mark = node.querySelector('svg');
            return { text: (node.textContent ?? '').trim(), display: style.display, listStyle: style.listStyleType,
              markerContent: marker.content, mark: !!mark, markTransform: mark ? getComputedStyle(mark.parentElement!).transform : null,
              size: style.fontSize };
          });
          expect(look.text, 'the words stay').toBe(words);
          expect(look.display === 'list-item' && look.listStyle !== 'none', `${words}: no browser triangle`).toBe(false);
          expect(look.mark, `${words}: the app's chevron`).toBe(true);
          await summary.click(); await settle(page);
          const turned = await summary.evaluate(node => getComputedStyle(node.querySelector('svg')!.parentElement!).transform);
          expect(turned, `${words}: the chevron turns when open`).not.toBe(look.markTransform);
          expect(await summary.evaluate(node => (node.parentElement as HTMLDetailsElement).open)).toBe(true);
          await summary.click(); await settle(page);
        }
      } finally { await page.close(); }
    }, 60_000);
  }
});

describe('PD3: the helper sentence is not on the page; the rule field still says how it saves', () => {
  it('no visible sentence; a rule field\'s description says it is saved when its field is left', async () => {
    const page = await open('dark', ['*.log']);
    try {
      const library = page.locator('jf-library-surface');
      const visible = await library.evaluate(el => {
        const walker = document.createTreeWalker(el.shadowRoot!, NodeFilter.SHOW_TEXT);
        const shown: string[] = [];
        for (let n = walker.nextNode(); n; n = walker.nextNode()) {
          const range = document.createRange(); range.selectNodeContents(n);
          const box = range.getBoundingClientRect(), own = n.parentElement?.getBoundingClientRect();
          if (own && box.width > 1 && box.height > 1 && own.width > 1 && own.height > 1 && n.textContent!.trim()) shown.push(n.textContent!.trim());
        }
        return shown.join(' ');
      });
      expect(visible).not.toContain('One rule per line');
      expect(visible).not.toContain('A rule is saved when you leave its field');
      const description = await ruleFields(page).first().evaluate(node => {
        const root = node.getRootNode() as ShadowRoot;
        const own = node.getAttribute('aria-description') ?? '';
        const ids = (node.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
        return [ids.map(id => root.getElementById(id)?.textContent ?? '').join(' '), ids.length ? '' : own].join(' ').replace(/\s+/g, ' ');
      });
      expect(description).toContain('A rule is saved when you leave its field.');
    } finally { await page.close(); }
  }, 60_000);
});

describe('PD4: with no rules only "Add a rule" shows', () => {
  it('no field and no remove cross; Add a rule gives one focused empty field; leaving it empty removes it without a save', async () => {
    const page = await open('dark');
    try {
      expect(await ruleFields(page).count()).toBe(0);
      expect(await removeButtons(page).count()).toBe(0);
      expect(await page.getByRole('button', { name: 'Add a rule', exact: true }).count()).toBe(1);
      await page.getByRole('button', { name: 'Add a rule', exact: true }).click(); await settle(page);
      expect(await ruleFields(page).count()).toBe(1);
      expect(await ruleFields(page).first().inputValue()).toBe('');
      expect(await focused(page)).toBe('Skip rule 1');
      expect(await removeButtons(page).count(), 'an empty field has no remove cross').toBe(0);
      await page.getByRole('button', { name: 'Add a rule', exact: true }).click(); await settle(page);
      expect(await ruleFields(page).count(), 'a second press keeps one empty field').toBe(1);
      expect(await focused(page)).toBe('Skip rule 1');
      await page.keyboard.press('Tab'); await settle(page);
      expect(await focused(page), 'focus goes on to Add a rule').toBe('Add a rule');
      expect(await ruleFields(page).count()).toBe(0);
      expect(await removeButtons(page).count()).toBe(0);
      expect(await posts(page)).toEqual([]);
      expect(await page.locator('[data-testid="excludes-result"]').textContent()).not.toContain('saved');
    } finally { await page.close(); }
  }, 60_000);

  it('by keyboard: Enter on Add a rule, type a rule, Tab saves it as before', async () => {
    const page = await open('dark');
    try {
      await page.getByRole('button', { name: 'Add a rule', exact: true }).focus();
      await page.keyboard.press('Enter'); await settle(page);
      expect(await focused(page)).toBe('Skip rule 1');
      await page.keyboard.type('*.tmp'); await page.keyboard.press('Tab'); await settle(page);
      expect(await posts(page)).toEqual([['*.tmp']]);
      expect(await ruleFields(page).count()).toBe(1);
      expect(await removeButtons(page).count()).toBe(1);
    } finally { await page.close(); }
  }, 60_000);

  it('existing rules stay; an added field left empty goes without a save, and removing the last rule leaves only Add a rule', async () => {
    const page = await open('dark', ['*.log']);
    try {
      expect(await ruleFields(page).count()).toBe(1);
      expect(await ruleFields(page).first().inputValue()).toBe('*.log');
      await page.getByRole('button', { name: 'Add a rule', exact: true }).click(); await settle(page);
      expect(await focused(page)).toBe('Skip rule 2');
      await page.mouse.click(5, 690); await settle(page);
      expect(await ruleFields(page).count(), 'left by a click elsewhere').toBe(1);
      expect(await posts(page)).toEqual([]);
      await ruleFields(page).first().fill(''); await page.keyboard.press('Tab'); await settle(page);
      expect(await posts(page), 'an existing rule emptied and left is removed and saved as before').toEqual([[]]);
      expect(await ruleFields(page).count()).toBe(0);
      expect(await focused(page)).toBe('Add a rule');
    } finally { await page.close(); }
  }, 60_000);

  it('removing the last rule with its cross leaves only Add a rule', async () => {
    const page = await open('dark', ['*.log']);
    try {
      await removeButtons(page).first().click(); await settle(page);
      expect(await posts(page)).toEqual([[]]);
      expect(await ruleFields(page).count()).toBe(0);
      expect(await removeButtons(page).count()).toBe(0);
      expect(await page.getByRole('button', { name: 'Add a rule', exact: true }).count()).toBe(1);
    } finally { await page.close(); }
  }, 60_000);

  // PD4' — a field cleared of an existing rule counts as an empty field, whatever its position.
  it('a middle rule cleared, then Add a rule: still one empty field, the focused one', async () => {
    const page = await open('dark', ['*.a', '*.b', '*.c']);
    try {
      await ruleFields(page).nth(1).fill(''); await settle(page);
      await page.getByRole('button', { name: 'Add a rule', exact: true }).click(); await settle(page);
      const values = await ruleFields(page).evaluateAll(nodes => nodes.map(node => (node as HTMLInputElement).value));
      expect(values.filter(value => value === ''), 'one empty field, not a second').toEqual(['']);
      expect(values.filter(Boolean)).toEqual(['*.a', '*.c']);
      expect(await focused(page)).toBe(`Skip rule ${values.indexOf('') + 1}`);
    } finally { await page.close(); }
  }, 60_000);

  it('the empty field left after that goes; no blank field without a cross remains', async () => {
    const page = await open('dark', ['*.a', '*.b', '*.c']);
    try {
      await ruleFields(page).nth(1).fill(''); await settle(page);
      await page.getByRole('button', { name: 'Add a rule', exact: true }).click(); await settle(page);
      await page.mouse.click(5, 690); await settle(page);
      expect(await ruleFields(page).evaluateAll(nodes => nodes.map(node => (node as HTMLInputElement).value))).toEqual(['*.a', '*.c']);
      expect(await removeButtons(page).count(), 'every field shown has its cross').toBe(2);
      expect((await posts(page)).at(-1)).toEqual(['*.a', '*.c']);
    } finally { await page.close(); }
  }, 60_000);

  it('a press on Add a rule released elsewhere leaves no blank field', async () => {
    const page = await open('dark', ['*.a', '*.b', '*.c']);
    try {
      await ruleFields(page).nth(1).fill(''); await settle(page);
      const add = await page.getByRole('button', { name: 'Add a rule', exact: true }).boundingBox();
      await page.mouse.move(add!.x + 5, add!.y + 5); await page.mouse.down();
      await page.mouse.move(5, 690); await page.mouse.up(); await settle(page);
      expect(await ruleFields(page).evaluateAll(nodes => nodes.map(node => (node as HTMLInputElement).value))).toEqual(['*.a', '*.c']);
      expect(await removeButtons(page).count()).toBe(2);
    } finally { await page.close(); }
  }, 60_000);

  it('by keyboard: removing the only rule moves focus to Add a rule, never to the page', async () => {
    const page = await open('dark', ['*.log']);
    try {
      await removeButtons(page).first().focus();
      const trail = await focusTrail(page, () => page.keyboard.press('Enter'));
      expect(await posts(page)).toEqual([[]]);
      expect(trail, 'no frame with focus on the page').not.toContain('body');
      expect(await focused(page)).toBe('Add a rule');
    } finally { await page.close(); }
  }, 60_000);

  it('by keyboard: removing a rule moves focus to the next rule\'s field, else the previous one', async () => {
    const page = await open('dark', ['*.a', '*.b', '*.c']);
    try {
      await page.getByRole('button', { name: 'Remove skip rule 2', exact: true }).focus();
      await page.keyboard.press('Enter'); await settle(page);
      expect(await focused(page), 'the next rule (*.c) is now Skip rule 2').toBe('Skip rule 2');
      expect(await ruleFields(page).nth(1).inputValue()).toBe('*.c');
      await page.getByRole('button', { name: 'Remove skip rule 2', exact: true }).focus();
      await page.keyboard.press('Space'); await settle(page);
      expect(await focused(page), 'with no next rule, the previous one').toBe('Skip rule 1');
      expect(await posts(page)).toEqual([['*.a', '*.c'], ['*.a']]);
    } finally { await page.close(); }
  }, 60_000);
});

describe('PD5 and PD6: type and alignment', () => {
  for (const theme of ['dark', 'light'] as const) {
    it(`${theme}: "and" is 13 px body like the words around it; the two columns still start level`, async () => {
      const page = await open(theme);
      try {
        const type = await page.locator('jf-indexing-rules-editor').evaluate(el => {
          const root = el.shadowRoot!;
          const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
          let and: Text | null = null;
          for (let n = walker.nextNode(); n; n = walker.nextNode()) if (n.textContent!.trim() === 'and') { and = n as Text; break; }
          const label = [...root.querySelectorAll('label')].find(l => (l.textContent ?? '').trim() === 'Only between')!;
          const of = (node: Element) => { const s = getComputedStyle(node); return { size: s.fontSize, weight: s.fontWeight, color: s.color }; };
          return { and: and ? of(and.parentElement!) : null, label: of(label) };
        });
        expect(type.and).not.toBeNull();
        expect(type.and!.size).toBe('13px');
        expect(type.and!.weight).toBe(type.label.weight);
        expect(type.label.size).toBe('13px');
        const tops = await page.locator('jf-library-surface').evaluate(el => {
          const skip = [...el.shadowRoot!.querySelectorAll('h3')].find(h => h.textContent!.trim() === 'Skip these files')!;
          const when = el.shadowRoot!.querySelector('jf-indexing-rules-editor')!.shadowRoot!.querySelector('h3')!;
          return [skip.getBoundingClientRect().top, when.getBoundingClientRect().top];
        });
        expect(Math.abs(tops[0]! - tops[1]!), 'the two headings stay level').toBeLessThanOrEqual(1);
      } finally { await page.close(); }
    }, 60_000);
  }
});
