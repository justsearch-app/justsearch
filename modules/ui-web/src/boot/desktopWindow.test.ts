// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// F-desktop B2 FW1 and the one focus-return rule (utils/focusReturn.ts): where focus goes after the
// window controls that send the window away, on every kind of page. desktopChrome-lockdown.test.ts
// exercises the running app and the start-up frame in Chromium.
import { afterEach, beforeAll, expect, it, vi } from 'vitest';

vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
import { installWindowControlFocus } from './desktopWindow.js';
import { focusedElement, returnFocus } from '../utils/focusReturn.js';

beforeAll(() => installWindowControlFocus());
afterEach(() => { document.body.replaceChildren(); });

/** index.html's window layer beside the root, and a root holding `page`. */
function layout(page: Element) {
  document.body.innerHTML = '<div id="root"></div><div id="window-controls" role="region" aria-label="Window">'
    + '<button type="button" data-window-command="minimize" aria-label="Minimize"><svg></svg></button>'
    + '<button type="button" data-window-command="toggle_maximize" aria-label="Maximize"></button>'
    + '<button type="button" data-window-command="close" aria-label="Close"></button></div>';
  document.getElementById('root')!.append(page);
}
/** A page component with its own open shadow root: a top bar control and a main landmark, as the shell renders them. */
function component(name: string, inert = false) {
  const host = document.createElement(name);
  if (inert) host.inert = true;
  host.attachShadow({ mode: 'open' }).innerHTML = '<header><button type="button" class="more">More</button></header><main><input aria-label="Search"></main>';
  return host;
}
const more = (host: Element) => host.shadowRoot!.querySelector<HTMLElement>('.more')!;
const main = (host: Element) => host.shadowRoot!.querySelector<HTMLElement>('main')!;
const control = (name: string) => document.querySelector<HTMLButtonElement>(`#window-controls [aria-label="${name}"]`)!;
/** Focus a control as a person does (focusin from the control), then use it. */
function use(name: string, target: Element = control(name)) {
  control(name).focus();
  target.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, cancelable: true }));
}

for (const name of ['Minimize', 'Close']) {
  it(`FW1 ${name} on the running app returns focus to the control that had it before, across shadow roots`, () => {
    const app = component('jf-shell');
    layout(app);
    more(app).focus();
    use(name);
    expect(focusedElement()).toBe(more(app));
  });
  it(`FW1 ${name} on a page whose remembered control is gone puts focus on the main content`, () => {
    const app = component('jf-shell');
    layout(app);
    more(app).focus();
    // The page changed beneath the controls (recovery replaced the app, a treatment action removed the control).
    const recovery = component('jf-engine-recovery');
    document.getElementById('root')!.replaceChildren(recovery);
    use(name);
    expect(focusedElement()).toBe(main(recovery));
    expect(main(recovery).getAttribute('tabindex')).toBe('-1');
  });
  it(`FW1 ${name} on the start-up frame over an inert app leaves focus off the app: nothing beneath the frame takes it`, () => {
    const app = component('jf-shell', true);
    layout(app);
    document.getElementById('root')!.prepend(Object.assign(document.createElement('div'), { id: 'boot-frame' }));
    use(name);
    // Focus does not move into the app beneath the frame; index.html's window layer lets go of the
    // control's own focus (windowLayer.test.ts).
    expect(focusedElement()).toBe(control(name));
  });
  it(`FW1 ${name} used through an inner element of the control returns focus too`, () => {
    const app = component('jf-shell');
    layout(app);
    more(app).focus();
    use(name, control(name).firstElementChild ?? control(name));
    expect(focusedElement()).toBe(more(app));
  });
}
it('FW1 Maximize keeps its focus, and a click outside the window controls moves nothing', () => {
  const app = component('jf-shell');
  layout(app);
  more(app).focus();
  use('Maximize');
  expect(focusedElement()).toBe(control('Maximize'));
  more(app).focus();
  main(app).dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }));
  expect(focusedElement()).toBe(more(app));
});

it('focus returns to the remembered element when it can still take focus', () => {
  const app = component('jf-shell');
  layout(app);
  expect(returnFocus(more(app))).toBe(more(app));
  expect(focusedElement()).toBe(more(app));
});
it('an element that is gone or inert is not returned to: the main content takes focus for one visit', () => {
  const app = component('jf-shell');
  layout(app);
  const gone = document.createElement('button');
  expect(returnFocus(gone)).toBe(main(app));
  expect(main(app).getAttribute('tabindex')).toBe('-1');
  main(app).blur();
  expect(main(app).hasAttribute('tabindex'), 'the visit leaves no tab stop behind').toBe(false);
  const held = component('jf-dialog', true);
  document.body.append(held);
  expect(returnFocus(more(held))).toBe(main(app));
});
it('the main content keeps its own tabindex, and with no main content nothing takes focus', () => {
  const app = component('jf-shell');
  layout(app);
  main(app).setAttribute('tabindex', '0');
  expect(returnFocus(null)).toBe(main(app));
  main(app).blur();
  expect(main(app).getAttribute('tabindex')).toBe('0');
  document.body.replaceChildren();
  expect(returnFocus(null)).toBeNull();
  expect(focusedElement()).toBeNull();
});
