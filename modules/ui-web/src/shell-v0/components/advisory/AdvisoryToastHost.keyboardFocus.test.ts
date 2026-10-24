// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './AdvisoryToastHost.js';
import type { AdvisoryToastHost } from './AdvisoryToastHost.js';
import { AdvisoryStore } from './AdvisoryStore.js';
import { emitEphemeralToast } from './ephemeralToast.js';

vi.mock('../../../utils/windowFocus.js', () => ({ isWindowFocused: async () => true }));
vi.mock('../../../utils/notify.js', () => ({ sendDesktopNotification: async () => {} }));
afterEach(() => { document.body.replaceChildren(); vi.useRealTimers(); });

it.each(['retargeted host', 'nested native control'])('XD2 keeps a retained toast mounted during focusout to its %s', async target => {
  vi.useFakeTimers();
  const store = new AdvisoryStore(); store.start();
  const host = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  host.store = store; document.body.append(host);
  try {
    for (let i = 0; i < 3; i++) {
      emitEphemeralToast({ message: `Keyboard ${i}`, severity: 'error', supersede: false,
        actionLabel: `Action ${i}`, onAction: () => {} });
      await host.updateComplete;
    }
    const first = host.shadowRoot!.querySelector<HTMLElement>('.toast')!;
    const action = first.querySelector<HTMLButtonElement>('.action-btn')!;
    const dismissHost = first.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-button')!;
    await dismissHost.updateComplete;
    const control = dismissHost.shadowRoot!.querySelector<HTMLElement & { updateComplete: Promise<unknown> }>('jf-control')!;
    await control.updateComplete;
    const dismiss = control.shadowRoot!.querySelector<HTMLButtonElement>('button')!;
    action.focus();
    emitEphemeralToast({ message: 'Arrival', severity: 'info', supersede: false, durationMs: 5000 });
    await host.updateComplete;
    const arrival = host.shadowRoot!.querySelectorAll('.toast')[2]!;
    expect(action.isConnected).toBe(true);
    // Keyboard navigation can yield to the renderer between focusout and focusin. Exercise that
    // gap with the browser's retargeted host as well as a raw node under two shadow roots.
    action.dispatchEvent(new FocusEvent('focusout', { bubbles: true, composed: true,
      relatedTarget: target === 'retargeted host' ? dismissHost : dismiss }));
    await host.updateComplete;
    expect(first.isConnected, 'moving between controls must not detach the retained toast').toBe(true);
    dismiss.focus(); await host.updateComplete;
    expect(dismiss.getRootNode() instanceof ShadowRoot && (dismiss.getRootNode() as ShadowRoot).activeElement).toBe(dismiss);
    action.focus(); await host.updateComplete;
    expect(host.shadowRoot!.activeElement).toBe(action);
    const external = document.createElement('button'); document.body.append(external);
    external.focus(); await host.updateComplete;
    expect(first.isConnected, 'protection ends when focus really leaves').toBe(false);
    expect(document.activeElement).toBe(external);
    await vi.advanceTimersByTimeAsync(5000);
    // Complete the CSS exit now that Happy DOM exposes getAnimations().
    arrival.dispatchEvent(new Event('animationend'));
    await host.updateComplete;
    expect(first.isConnected).toBe(true);
    expect(first.querySelector('jf-system-notice')!.getAttribute('aria-live')).toBe('off');
    expect(document.activeElement).toBe(external);
  } finally { host.remove(); store.stop(); }
});
