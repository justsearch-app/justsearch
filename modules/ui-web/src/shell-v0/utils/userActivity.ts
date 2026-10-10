// SPDX-License-Identifier: Apache-2.0
/** Report actual app input independently of the requests its views make to observe state. */
export function startUserActivity(
  target: EventTarget,
  report: () => Promise<unknown>,
): () => void {
  const events = ['pointerdown', 'pointermove', 'keydown', 'input', 'wheel', 'touchstart'] as const;
  let active = true;
  let reporting = false;
  let inputPending = false;
  const send = (): void => {
    reporting = true;
    inputPending = false;
    void report().catch(() => { /* next input retries */ }).finally(() => {
      reporting = false;
      if (active && inputPending) send();
    });
  };
  const onInput = (event: Event): void => {
    if (!active || !event.isTrusted) return;
    // Keep the final input even during a slow request. Idle timers never invent input.
    if (reporting) inputPending = true;
    else send();
  };
  for (const event of events) target.addEventListener(event, onInput, { capture: true, passive: true });
  return () => {
    active = false;
    inputPending = false;
    for (const event of events) target.removeEventListener(event, onInput, true);
  };
}
