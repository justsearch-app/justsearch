// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { startUserActivity } from './userActivity.js';

afterEach(() => vi.useRealTimers());

function target() {
  const listeners = new Map<string, EventListener>();
  const events = {
    addEventListener: (name: string, listener: EventListener) => listeners.set(name, listener),
    removeEventListener: (name: string) => listeners.delete(name),
  } as unknown as EventTarget;
  return { events, listeners, input: (name: string, trusted = true) =>
    listeners.get(name)!({ isTrusted: trusted } as Event) };
}

it('reports the actual start click promptly without a delayed duplicate; idle and synthetic input stay quiet', async () => {
  vi.useFakeTimers();
  const t = target();
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(t.events, report);
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).not.toHaveBeenCalled();
  t.input('pointerdown');
  expect(report).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).toHaveBeenCalledTimes(1);
  t.input('pointermove', false);
  t.input('input', false);
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).toHaveBeenCalledTimes(1);
  stop();
  expect(t.listeners.size).toBe(0);
});

it('coalesces requests in flight and reports the final genuine input, but disconnect prevents a pending report', async () => {
  vi.useFakeTimers();
  const t = target();
  let resolve!: () => void;
  const report = vi.fn(() => new Promise<void>(done => { resolve = done; }));
  const stop = startUserActivity(t.events, report);
  t.input('keydown');
  expect(report).toHaveBeenCalledTimes(1);
  t.input('input');
  t.input('pointermove');
  t.input('wheel');
  expect(report).toHaveBeenCalledTimes(1);
  resolve();
  await vi.advanceTimersByTimeAsync(0);
  expect(report).toHaveBeenCalledTimes(2);
  t.input('touchstart');
  stop();
  resolve();
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).toHaveBeenCalledTimes(2);
  expect(t.listeners.size).toBe(0);
});

it('keeps reporting continuous input beyond the full idle window and does not report after input ends', async () => {
  vi.useFakeTimers();
  const t = target();
  const report = vi.fn(async () => undefined);
  const stop = startUserActivity(t.events, report);
  for (let i = 0; i < 2000; i++) {
    t.input('pointermove');
    await vi.advanceTimersByTimeAsync(200);
  }
  expect(report).toHaveBeenCalledTimes(2000);
  await vi.advanceTimersByTimeAsync(300_000);
  expect(report).toHaveBeenCalledTimes(2000);
  stop();
});
