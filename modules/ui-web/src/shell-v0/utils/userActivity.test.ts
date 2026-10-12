// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { startUserActivity } from './userActivity.js';

/** The presence interval this design chose: at most one report every three seconds of input. */
const INTERVAL_MS = 3_000;

let inside: HTMLElement;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
  // The app's controls live in shadow roots; input reaches the document listener retargeted to the host.
  const host = document.createElement('div');
  document.body.appendChild(host);
  inside = document.createElement('button');
  host.attachShadow({ mode: 'open' }).appendChild(inside);
});

afterEach(() => {
  document.body.replaceChildren();
  vi.useRealTimers();
});

function event(type: string): Event {
  if (type.startsWith('key')) return new KeyboardEvent(type, { bubbles: true, composed: true, key: 'a' });
  if (type === 'wheel') return new WheelEvent(type, { bubbles: true, composed: true, deltaY: 100 });
  if (type.startsWith('pointer')) return new PointerEvent(type, { bubbles: true, composed: true });
  if (type.startsWith('mouse')) return new MouseEvent(type, { bubbles: true, composed: true });
  return new Event(type, { bubbles: true, composed: true });
}

/** Input the browser delivers from a person: `isTrusted` is true, which page-dispatched events never are. */
function person(type: string, at: EventTarget = inside): void {
  const e = event(type);
  Object.defineProperty(e, 'isTrusted', { value: true });
  at.dispatchEvent(e);
}

function page(type: string, at: EventTarget = inside): void {
  at.dispatchEvent(event(type));
}

it('a single key press, pointer press, wheel turn, touch or text entry is reported at once; idle time sends nothing', async () => {
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(document, report);
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).not.toHaveBeenCalled();
  let expected = 0;
  for (const type of ['keydown', 'pointerdown', 'wheel', 'touchstart', 'input']) {
    person(type);
    expected += 1;
    // Synchronously, before any timer runs: this is what interrupts background work promptly.
    expect(report, type).toHaveBeenCalledTimes(expected);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(report, `${type} has no delayed duplicate`).toHaveBeenCalledTimes(expected);
  }
  stop();
});

it('synthetic events, hover and pointer movement are not a person acting', async () => {
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(document, report);
  for (const type of ['keydown', 'pointerdown', 'wheel', 'touchstart', 'input', 'click', 'mousedown']) page(type);
  for (const type of ['pointermove', 'pointerover', 'pointerenter', 'pointerout', 'mousemove', 'mouseover', 'mouseenter', 'scroll']) {
    person(type);
    person(type, document);
  }
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).not.toHaveBeenCalled();
  stop();
});

it('continuous input sends at most one report per interval and reports the last input when its interval ends', async () => {
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(document, report);
  const sentAt: number[] = [];
  report.mockImplementation(async () => { sentAt.push(Date.now()); });
  const start = Date.now();
  let lastInput = start;
  for (let i = 0; i < 600; i++) {
    person(i % 2 === 0 ? 'keydown' : 'wheel');
    lastInput = Date.now();
    await vi.advanceTimersByTimeAsync(100);
  }
  await vi.advanceTimersByTimeAsync(INTERVAL_MS);
  expect(sentAt[0]).toBe(start);
  for (let i = 1; i < sentAt.length; i++) expect(sentAt[i]! - sentAt[i - 1]!).toBeGreaterThanOrEqual(INTERVAL_MS);
  // Sixty seconds of input: about one report per interval, not one per input.
  expect(sentAt.length).toBeGreaterThanOrEqual(60_000 / INTERVAL_MS);
  expect(sentAt.length).toBeLessThanOrEqual(60_000 / INTERVAL_MS + 1);
  // The idle clock starts no earlier than the person's last input.
  expect(sentAt.at(-1)!).toBeGreaterThanOrEqual(lastInput);
  const reports = report.mock.calls.length;
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).toHaveBeenCalledTimes(reports);
  stop();
});

it('a report that failed does not hold back the next input', async () => {
  const report = vi.fn(async (): Promise<unknown> => { throw new Error('host not ready'); });
  const stop = startUserActivity(document, report);
  person('pointerdown');
  await vi.advanceTimersByTimeAsync(0);
  report.mockImplementation(async () => undefined);
  person('keydown');
  expect(report).toHaveBeenCalledTimes(2);
  stop();
});

it('disconnect removes the listeners and drops a pending end-of-interval report', async () => {
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(document, report);
  person('keydown');
  person('keydown');
  expect(report).toHaveBeenCalledTimes(1);
  stop();
  await vi.advanceTimersByTimeAsync(300_000);
  person('keydown');
  expect(report).toHaveBeenCalledTimes(1);
});
