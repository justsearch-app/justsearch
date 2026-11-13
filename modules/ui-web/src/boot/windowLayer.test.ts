// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// F-desktop B2: index.html's desktop window layer, its own inline script run on its own markup, over
// every kind of page the root holds. Its rules sit at window capture and do not depend on the page:
// window controls that send the window away let go of focus (FW1), and the production build's
// context-menu policy (FW2). desktopChrome-lockdown.test.ts exercises one page of each in Chromium.
import { afterEach, expect, it, vi } from 'vitest';
import indexHtml from '../../index.html?raw';

type Listener = [EventTarget, string, EventListenerOrEventListenerObject, boolean | AddEventListenerOptions | undefined];
const added: Listener[] = [];
const invoke = vi.fn(async (command: string) => command === 'plugin:window|is_maximized' ? false : null);

/** Load the page as the desktop runtime does: index.html's body, then its inline script, built for production or development. */
function load(build: 'production' | 'development' = 'production') {
  vi.useFakeTimers();
  for (const target of [window, document] as EventTarget[]) {
    const original = target.addEventListener.bind(target);
    vi.spyOn(target, 'addEventListener').mockImplementation((type, listener, options) => {
      if (listener) added.push([target, type, listener, options]);
      original(type, listener, options);
    });
  }
  Object.assign(window, { __TAURI_INTERNALS__: { invoke, transformCallback: () => 0 } });
  document.head.innerHTML = '<meta name="justsearch-version" content="1.0" />';
  document.body.innerHTML = indexHtml.match(/<body>([\s\S]*?)<script>/)![1]!;
  // Vite replaces %PROD% in index.html: 'true' in production builds.
  const script = indexHtml.match(/<script>([\s\S]*?)<\/script>/)![1]!.replaceAll('%PROD%', build === 'production' ? 'true' : 'false');
  new Function(script)();
  expect(document.querySelector('#window-controls'), 'the desktop window layer is attached').not.toBeNull();
}
afterEach(() => {
  for (const [target, type, listener, options] of added.splice(0)) target.removeEventListener(type, listener, options);
  vi.restoreAllMocks();
  vi.useRealTimers();
  invoke.mockClear();
  delete (window as { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__;
  delete (window as { __justsearchBootFailure?: unknown }).__justsearchBootFailure;
  document.head.replaceChildren(); document.body.replaceChildren();
});

const root = () => document.getElementById('root')!;
/** A page component as the application mounts it: its own open shadow root with a top drag region, content and an action. */
function component(name: string, inert = false) {
  const host = document.createElement(name);
  if (inert) host.inert = true;
  host.attachShadow({ mode: 'open' }).innerHTML = '<header data-tauri-drag-region><span>JustSearch</span><button type="button">More</button></header>'
    + '<main><h1>Heading</h1><p>Some words</p><ul><li>An item</li></ul><button type="button">Action</button></main>';
  return host;
}
const inShadow = (host: Element) => [...host.shadowRoot!.querySelectorAll('header, span, main, h1, p, li, button')];
type View = { top: Element; content: Element[] };
/** Every kind of page the root holds, after index.html's script has run. */
const pages: { name: string; open: () => View }[] = [
  { name: 'the start-up frame', open: () => {
    const frame = document.getElementById('boot-frame')!;
    return { top: frame.querySelector('.boot-topbar')!, content: [frame, frame.querySelector('[role="status"]')!] };
  } },
  { name: 'the failure page', open: () => {
    (window as unknown as { __justsearchBootFailure: (kind: string) => void }).__justsearchBootFailure('entry');
    const page = document.getElementById('boot-failure')!;
    return { top: page.querySelector('.boot-window-top')!, content: [page, ...page.querySelectorAll('main, h1, p, button')] };
  } },
  { name: 'a recovery or reset page', open: () => {
    const page = component('jf-engine-recovery');
    root().replaceChildren(page);
    return { top: page.shadowRoot!.querySelector('header')!, content: [page, ...inShadow(page)] };
  } },
  { name: 'the running app', open: () => {
    const app = component('jf-shell');
    root().replaceChildren(app);
    return { top: app.shadowRoot!.querySelector('header')!, content: [app, ...inShadow(app)] };
  } },
  { name: 'the app held beneath the start-up frame', open: () => {
    const app = component('jf-shell', true);
    root().append(app);
    const frame = document.getElementById('boot-frame')!;
    return { top: frame.querySelector('.boot-topbar')!, content: [frame, app, ...inShadow(app)] };
  } },
];
/** Right-click on an element: whether its default (the web view's menu) was left alone, and whether the element's own listener heard it. */
function rightClick(target: Element) {
  let heard = false;
  target.addEventListener('contextmenu', () => { heard = true; }, { once: true });
  const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, composed: true, button: 2 });
  target.dispatchEvent(event);
  return { menu: !event.defaultPrevented, heard };
}
const describeElement = (el: Element) => `${el.localName}${el.getRootNode() instanceof ShadowRoot ? ' (in a shadow root)' : ''}`;
const control = (name: string) => document.querySelector<HTMLButtonElement>(`#window-controls [aria-label="${name}"]`)!;

for (const page of pages) it(`FW2 production build: the top area, the drag region, the content and the window controls of ${page.name} open no web view menu`, () => {
  load();
  const { top, content } = page.open();
  for (const target of [top, ...content, ...document.querySelectorAll('#window-controls, #window-controls button')]) {
    expect(rightClick(target), describeElement(target)).toEqual({ menu: false, heard: true });
  }
});

/** Fields in the light DOM and in the running app's shadow root, as the person meets them. */
function fieldsIn(markup: string) {
  load();
  const app = component('jf-shell');
  root().replaceChildren(app);
  const light = document.createElement('div');
  light.innerHTML = markup;
  root().append(light);
  app.shadowRoot!.querySelector('main')!.insertAdjacentHTML('beforeend', `<div class="fields">${markup}</div>`);
  return [...light.children, ...app.shadowRoot!.querySelectorAll('.fields > *')];
}
const named = (target: Element) => `${describeElement(target)} ${target.getAttribute('type') ?? ''}${target.hasAttribute('disabled') ? ' disabled' : ''}`;
it('FW2 production build: text fields keep the platform edit menu', () => {
  const fields = fieldsIn('<input type="text"><input type="search"><input type="email"><input type="password"><input type="url"><input type="number"><textarea></textarea><div contenteditable="true">Words</div>');
  expect(fields).toHaveLength(16);
  for (const target of fields) expect(rightClick(target), named(target)).toEqual({ menu: true, heard: true });
});
it('FW2 production build: controls that take no typing and disabled fields open no web view menu', () => {
  const others = fieldsIn('<input type="checkbox"><input type="radio"><input type="range"><input type="button" value="Go"><input type="text" disabled><textarea disabled></textarea>');
  expect(others).toHaveLength(12);
  for (const target of others) expect(rightClick(target), named(target)).toEqual({ menu: false, heard: true });
});

it('FW2 development build: the web view menu stays for Inspect', () => {
  load('development');
  const { top, content } = pages[3]!.open();
  for (const target of [top, ...content, control('Minimize')]) expect(rightClick(target), describeElement(target)).toEqual({ menu: true, heard: true });
});

for (const page of pages) it(`FW1 Minimize and Close on ${page.name} let go of focus as they send the window away; Maximize keeps it`, async () => {
  load();
  page.open();
  for (const [name, command] of [['Minimize', 'minimize'], ['Close', 'close']] as const) {
    invoke.mockClear();
    control(name).focus();
    expect(document.activeElement, `${name} has focus`).toBe(control(name));
    control(name).click();
    await vi.advanceTimersByTimeAsync(0);
    expect(invoke.mock.calls.map(([call]) => call).filter(call => !call.endsWith('|is_maximized')), name).toEqual([`plugin:window|${command}`]);
    expect(document.querySelector('#window-controls')!.contains(document.activeElement), `${name}: focus left the window controls`).toBe(false);
  }
  control('Maximize').focus();
  control('Maximize').click();
  expect(document.activeElement, 'Maximize keeps its focus').toBe(control('Maximize'));
});
