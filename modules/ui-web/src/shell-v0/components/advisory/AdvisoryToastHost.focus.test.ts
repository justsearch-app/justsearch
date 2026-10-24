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

it.each(['Extraction complete.', 'Extraction failed: invalid JSON', 'An unrelated notice'])('XD2 keeps the focused toast action when %s overflows the stack', async message => {
  vi.useFakeTimers();
  const store = new AdvisoryStore(); store.start();
  const host = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  host.store = store; document.body.append(host);
  try {
    for (let i = 0; i < 3; i++) {
      emitEphemeralToast({ message: `Existing ${i}`, severity: 'error', supersede: false,
        ...(i === 0 ? { actionLabel: 'Keep notification focus', onAction: () => {} } : {}) });
      await host.updateComplete;
    }
    const original = host.shadowRoot!.querySelector<HTMLButtonElement>('.action-btn')!;
    const displaced = host.shadowRoot!.querySelectorAll('jf-system-notice')[1]!;
    original.focus(); expect(host.shadowRoot!.activeElement).toBe(original);
    emitEphemeralToast({ message, severity: 'info', durationMs: 500, supersede: false });
    await host.updateComplete;
    expect(original.isConnected, 'arrival must not displace a toast containing keyboard focus').toBe(true);
    expect(host.shadowRoot!.activeElement).toBe(original);
    expect(host.shadowRoot!.querySelectorAll('.toast')).toHaveLength(3);
    expect([...host.shadowRoot!.querySelectorAll('jf-system-notice')].map(n => n.textContent)).toEqual([
      expect.stringContaining('Existing 0'), expect.stringContaining('Existing 2'), expect.stringContaining(message),
    ]);
    const arrival = host.shadowRoot!.querySelectorAll('.toast')[2]!;
    await vi.advanceTimersByTimeAsync(500);
    // Happy DOM exposes getAnimations(); finish the exit after the dwell timer fires.
    arrival.dispatchEvent(new Event('animationend'));
    await host.updateComplete;
    expect(host.shadowRoot!.activeElement).toBe(original);
    expect(host.shadowRoot!.querySelectorAll('jf-system-notice')[1]).toBe(displaced);
    expect(displaced.getAttribute('aria-live'), 'a returning sibling must not announce again').toBe('off');
  } finally { host.remove(); store.stop(); }
});
