// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * One deliberate interaction answers one prompt. Answering a prompt presents the next queued one in
 * the same dialog, under the same pointer and keyboard focus; the next prompt's decision buttons
 * ignore activation until a fresh press after its arming delay. Real-browser counterpart:
 * AuthorizationHost.activation-lockdown.test.ts.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './AuthorizationHost.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import { requestAuthorization, setAuthorizationPresenter, type AuthorizationDecision } from '../operations/authorizationBroker.js';
import { requestCapability, checkCapability, __resetConsentForTest } from '../substrates/consent/index.js';

let host: AuthorizationHost;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['performance'] });
  __resetConsentForTest();
  localStorage.clear();
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.appendChild(host);
});

afterEach(() => {
  host.remove();
  setAuthorizationPresenter(null);
  __resetConsentForTest();
  localStorage.clear();
  vi.useRealTimers();
});

async function settle(): Promise<void> {
  await host.updateComplete;
  await Promise.resolve();
  await host.updateComplete;
}

type Kind = 'lightweight' | 'typed' | 'capability';
/** The decision a stray second activation would take on each kind (typed Approve stays disabled). */
const target: Record<Kind, string> = {
  lightweight: 'authorization-approve',
  typed: 'authorization-deny',
  capability: 'capability-allow-always',
};

function enqueue(kind: Kind, id: string): Promise<unknown> {
  if (kind === 'capability') return requestCapability({ contributorId: `plugin.${id}`, capability: 'fs.read' });
  return requestAuthorization({ pendingId: id, operationId: `core.${id}`,
    gateBehavior: kind === 'typed' ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM' });
}

async function nativeButton(testid: string): Promise<HTMLButtonElement> {
  const atom = host.shadowRoot!.querySelector(`[data-testid="${testid}"]`) as HTMLElement & { updateComplete: Promise<unknown> } | null;
  // Every queued prompt has been answered when it is gone.
  expect(atom, `${testid} is on screen: a prompt is still waiting for its decision`).not.toBeNull();
  await atom!.updateComplete;
  const control = atom!.shadowRoot!.querySelector('jf-control') as HTMLElement & { updateComplete: Promise<unknown> };
  await control.updateComplete;
  return control.shadowRoot!.querySelector('button')!;
}

/** A pointer click as a browser sends it: press, release, click carrying its click count. */
function pointerClick(button: HTMLElement, detail: number): void {
  button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, cancelable: true }));
  button.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, composed: true, cancelable: true }));
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, cancelable: true, detail }));
}

/** A key event plus the native default a browser runs when it is not cancelled. */
function key(button: HTMLElement, type: 'keydown' | 'keyup', value: string, repeat = false): void {
  const event = new KeyboardEvent(type, { key: value, repeat, bubbles: true, composed: true, cancelable: true });
  button.dispatchEvent(event);
  // happy-dom has no keyboard activation: a button clicks on (every) Enter keydown, buttons and
  // checkboxes on Space release.
  const enter = type === 'keydown' && value === 'Enter' && button instanceof HTMLButtonElement;
  if (!event.defaultPrevented && (enter || (type === 'keyup' && value === ' '))) button.click();
}

function answered(kind: Kind, id: string, decision: Promise<unknown>): Promise<boolean> {
  if (kind === 'capability') return Promise.resolve(checkCapability(`plugin.${id}`, 'fs.read') !== undefined);
  return Promise.race([decision.then(() => true), new Promise<boolean>(resolve => setTimeout(() => resolve(false), 0))]);
}

async function twoQueued(kind: Kind) {
  const first = enqueue(kind, 'first');
  const second = enqueue(kind, 'second');
  await settle();
  vi.advanceTimersByTime(1000);
  return { first, second };
}

const kinds: Kind[] = ['lightweight', 'typed', 'capability'];

it.each(kinds)('a double-click answers only the first queued %s prompt', async kind => {
  const { first, second } = await twoQueued(kind);
  pointerClick(await nativeButton(target[kind]), 1);
  await settle();
  vi.advanceTimersByTime(120); // a double-click's second press, on the next prompt now under the pointer
  pointerClick(await nativeButton(target[kind]), 2);
  await settle();
  expect(await answered(kind, 'first', first)).toBe(true);
  expect(await answered(kind, 'second', second)).toBe(false);
  expect(host.statusRequests()).toHaveLength(1);
});

it.each(kinds)('repeated activation of the same native button answers only the first %s prompt', async kind => {
  const { first, second } = await twoQueued(kind);
  const button = await nativeButton(target[kind]);
  pointerClick(button, 1);
  pointerClick(button, 2);
  button.click();
  await settle();
  button.click();
  (await nativeButton(target[kind])).click();
  await settle();
  expect(await answered(kind, 'first', first)).toBe(true);
  expect(await answered(kind, 'second', second)).toBe(false);
});

it.each(kinds)('held Enter auto-repeat answers only the first %s prompt', async kind => {
  const { first, second } = await twoQueued(kind);
  key(await nativeButton(target[kind]), 'keydown', 'Enter');
  key(await nativeButton(target[kind]), 'keyup', 'Enter');
  await settle();
  key(await nativeButton(target[kind]), 'keydown', 'Enter');
  await settle();
  for (let i = 0; i < 10; ++i) {
    vi.advanceTimersByTime(100);
    key(await nativeButton(target[kind]), 'keydown', 'Enter', true);
    await settle();
  }
  key(await nativeButton(target[kind]), 'keyup', 'Enter');
  await settle();
  expect(await answered(kind, 'first', first)).toBe(true);
  expect(await answered(kind, 'second', second)).toBe(false);
});

it.each(['Enter', ' '])('a key pressed again before the next prompt arms does not answer it (%j)', async value => {
  const { first, second } = await twoQueued('capability');
  key(await nativeButton('capability-allow-always'), 'keydown', value);
  key(await nativeButton('capability-allow-always'), 'keyup', value);
  await settle();
  vi.advanceTimersByTime(150);
  key(await nativeButton('capability-allow-always'), 'keydown', value);
  vi.advanceTimersByTime(300); // released after the delay, but pressed before it
  key(await nativeButton('capability-allow-always'), 'keyup', value);
  await settle();
  expect(await answered('capability', 'first', first)).toBe(true);
  expect(await answered('capability', 'second', second)).toBe(false);
});

it('a pointer press that began before the next prompt armed does not answer it', async () => {
  const { second } = await twoQueued('capability');
  pointerClick(await nativeButton('capability-allow-always'), 1);
  await settle();
  const button = await nativeButton('capability-allow-always');
  vi.advanceTimersByTime(100);
  button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true }));
  vi.advanceTimersByTime(500);
  button.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, cancelable: true, detail: 1 }));
  await settle();
  expect(await answered('capability', 'second', second)).toBe(false);
});

it.each(kinds)('a fresh press after the delay answers the next %s prompt exactly once', async kind => {
  const { first, second } = await twoQueued(kind);
  pointerClick(await nativeButton(target[kind]), 1);
  await settle();
  vi.advanceTimersByTime(400);
  pointerClick(await nativeButton(target[kind]), 1);
  await settle();
  expect(await answered(kind, 'first', first)).toBe(true);
  expect(await answered(kind, 'second', second)).toBe(true);
  expect(host.statusRequests()).toHaveLength(0);
});

it.each(['Enter', ' '])('a fresh %j press after the delay answers the next prompt on release', async value => {
  const first = enqueue('lightweight', 'first');
  const second = enqueue('lightweight', 'second') as Promise<AuthorizationDecision>;
  await settle();
  vi.advanceTimersByTime(400);
  key(await nativeButton('authorization-approve'), 'keydown', value);
  expect(host.statusRequests()).toHaveLength(2);
  key(await nativeButton('authorization-approve'), 'keyup', value);
  await settle();
  await expect(first).resolves.toEqual({ approved: true, allowAlways: false });
  vi.advanceTimersByTime(400);
  key(await nativeButton('authorization-deny'), 'keydown', value);
  key(await nativeButton('authorization-deny'), 'keyup', value);
  await expect(second).resolves.toEqual({ approved: false, allowAlways: false });
});

it('assistive-technology activation (no pointer or key press) answers an armed prompt', async () => {
  const decision = enqueue('lightweight', 'only');
  await settle();
  const button = await nativeButton('authorization-approve');
  button.click();
  expect(host.statusRequests()).toHaveLength(1);
  vi.advanceTimersByTime(400);
  button.click();
  await expect(decision).resolves.toEqual({ approved: true, allowAlways: false });
});

it('Review re-presents the open prompt: the activation that asked for it cannot answer it', async () => {
  const decision = enqueue('lightweight', 'only');
  await settle();
  vi.advanceTimersByTime(1000);
  host.reviewStatusRequest('only');
  await settle();
  const button = await nativeButton('authorization-deny');
  pointerClick(button, 2);
  button.click();
  expect(host.statusRequests()).toHaveLength(1);
  vi.advanceTimersByTime(400);
  pointerClick(button, 1);
  await expect(decision).resolves.toEqual({ approved: false, allowAlways: false });
});

/** The Always-allow checkbox on the open authorize prompt. */
function allowAlwaysBox(): HTMLInputElement {
  return host.shadowRoot!.querySelector<HTMLInputElement>('[data-testid="authorization-allow-always"] input')!;
}

/** Two lightweight prompts; the first is answered by a deliberate Approve and the second is on screen. */
async function secondPresented() {
  const first = enqueue('lightweight', 'first');
  const second = enqueue('lightweight', 'second') as Promise<AuthorizationDecision>;
  await settle();
  vi.advanceTimersByTime(1000);
  pointerClick(await nativeButton('authorization-approve'), 1);
  await settle();
  await expect(first).resolves.toEqual({ approved: true, allowAlways: false });
  return { second };
}

it.each(['checkbox', 'label'] as const)('S1 a click on the next prompt’s Always-allow %s 30 ms after it appears does not tick it; a deliberate Approve stays one-time', async part => {
  const { second } = await secondPresented();
  vi.advanceTimersByTime(30);
  const box = allowAlwaysBox();
  pointerClick(part === 'checkbox' ? box : box.closest('label')!, 1);
  await settle();
  expect(box.checked, 'the early click did not tick it').toBe(false);
  vi.advanceTimersByTime(400);
  pointerClick(await nativeButton('authorization-approve'), 1);
  await expect(second).resolves.toEqual({ approved: true, allowAlways: false });
});

it('S1 Space pressed on the Always-allow checkbox before the prompt arms does not tick it on release', async () => {
  const { second } = await secondPresented();
  const box = allowAlwaysBox();
  key(box, 'keydown', ' ');
  vi.advanceTimersByTime(500);
  key(box, 'keyup', ' ');
  await settle();
  expect(box.checked).toBe(false);
  vi.advanceTimersByTime(400);
  pointerClick(await nativeButton('authorization-approve'), 1);
  await expect(second).resolves.toEqual({ approved: true, allowAlways: false });
});

it.each(['pointer', 'Space'] as const)('S1 a deliberate %s on the Always-allow checkbox after arming ticks it', async how => {
  const { second } = await secondPresented();
  vi.advanceTimersByTime(400);
  const box = allowAlwaysBox();
  if (how === 'pointer') pointerClick(box.closest('label')!, 1);
  else { key(box, 'keydown', ' '); key(box, 'keyup', ' '); }
  await settle();
  expect(box.checked).toBe(true);
  pointerClick(await nativeButton('authorization-approve'), 1);
  await expect(second).resolves.toEqual({ approved: true, allowAlways: true });
});
