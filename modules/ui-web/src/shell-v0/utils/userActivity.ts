// SPDX-License-Identifier: Apache-2.0
/**
 * The web UI's user-presence signal: report that a person is acting, independently of the requests
 * the views make to observe state.
 *
 * Only trusted input a person makes counts: a key press, a pointer press, a wheel turn, a touch, and
 * text entry. Pointer movement and hover do not count (a pointer resting over content that changes
 * under it fires them with nobody acting), and neither does anything the page dispatches itself
 * (`isTrusted` is false). `scroll` events are not listened to: the browser marks them trusted even
 * when the page scrolls itself, for example a chat transcript following a streamed answer. A person
 * scrolls with the wheel, the keyboard, a touch, or a press on the scrollbar, and each of those is
 * reported here.
 */
const PRESENCE_EVENTS = ['keydown', 'pointerdown', 'wheel', 'touchstart', 'input'] as const;

/**
 * At most one report per interval while input continues. The first input after a quiet interval is
 * reported at once, so a single key press or click interrupts background work promptly; the last
 * input of a burst is reported when its interval ends, so the idle clock never trails the person by
 * more than this. The background idle window is minutes long, so a few seconds of lag cost nothing.
 *
 * Each report says how long ago the input it reports happened (`inputAgeMs`): 0 for the report sent
 * at once, the time since the last input for the end-of-interval report. The server places the input
 * at that time, so an end-of-interval report of input made before background work was accepted does
 * not count as acting during that work, while input made after it still does.
 */
const PRESENCE_INTERVAL_MS = 3_000;

export function startUserActivity(
  target: EventTarget,
  report: (inputAgeMs: number) => Promise<unknown>,
): () => void {
  let active = true;
  let lastReport = Number.NEGATIVE_INFINITY;
  let lastInput = Number.NEGATIVE_INFINITY;
  let trailing: ReturnType<typeof setTimeout> | null = null;
  const send = (): void => {
    const sentAt = Date.now();
    lastReport = sentAt;
    // A clock that went backwards reports the input as just now, never as in the future.
    const inputAgeMs = Math.max(0, sentAt - lastInput);
    // A report that did not arrive does not hold back the next input's report.
    void report(inputAgeMs).catch(() => { if (lastReport === sentAt) lastReport = Number.NEGATIVE_INFINITY; });
  };
  const onInput = (event: Event): void => {
    if (!active || !event.isTrusted) return;
    lastInput = Date.now();
    const elapsed = lastInput - lastReport;
    // A clock that went backwards (sleep, time change) never silences input.
    if (elapsed >= PRESENCE_INTERVAL_MS || elapsed < 0) {
      send();
      return;
    }
    trailing ??= setTimeout(() => {
      trailing = null;
      if (active) send();
    }, PRESENCE_INTERVAL_MS - elapsed);
  };
  for (const event of PRESENCE_EVENTS) target.addEventListener(event, onInput, { capture: true, passive: true });
  return () => {
    active = false;
    if (trailing !== null) clearTimeout(trailing);
    trailing = null;
    for (const event of PRESENCE_EVENTS) target.removeEventListener(event, onInput, true);
  };
}
