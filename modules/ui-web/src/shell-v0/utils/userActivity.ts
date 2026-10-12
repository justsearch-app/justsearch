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
 */
const PRESENCE_INTERVAL_MS = 3_000;

export function startUserActivity(
  target: EventTarget,
  report: () => Promise<unknown>,
): () => void {
  let active = true;
  let lastReport = Number.NEGATIVE_INFINITY;
  let trailing: ReturnType<typeof setTimeout> | null = null;
  const send = (): void => {
    const sentAt = Date.now();
    lastReport = sentAt;
    // A report that did not arrive does not hold back the next input's report.
    void report().catch(() => { if (lastReport === sentAt) lastReport = Number.NEGATIVE_INFINITY; });
  };
  const onInput = (event: Event): void => {
    if (!active || !event.isTrusted) return;
    const elapsed = Date.now() - lastReport;
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
