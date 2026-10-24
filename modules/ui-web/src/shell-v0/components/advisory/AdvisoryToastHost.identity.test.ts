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

it('XD2 keeps each polite live region with its own toast when an older outcome expires', async () => {
  vi.useFakeTimers();
  const store = new AdvisoryStore();
  store.start();
  const host = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  host.store = store;
  document.body.append(host);
  try {
    emitEphemeralToast({ message: 'Extraction complete.', severity: 'info', durationMs: 5000 });
    await host.updateComplete;
    const success = host.shadowRoot!.querySelector('jf-system-notice')!;
    await vi.advanceTimersByTimeAsync(1000);
    emitEphemeralToast({ message: 'Extraction failed: invalid JSON', severity: 'info', durationMs: 5000 });
    await host.updateComplete;
    const failure = host.shadowRoot!.querySelectorAll('jf-system-notice')[1]!;
    expect(failure.textContent).toContain('Extraction failed: invalid JSON');
    expect(failure.getAttribute('role')).toBe('status');
    expect(failure.getAttribute('aria-live')).toBe('polite');

    await vi.advanceTimersByTimeAsync(4000);
    // Happy DOM exposes getAnimations(); expiry starts an exit animation before removal.
    success.closest('.toast')!.dispatchEvent(new Event('animationend'));
    await host.updateComplete;
    expect(host.shadowRoot!.querySelectorAll('jf-system-notice')).toHaveLength(1);
    // Reusing the success node for this text announces the same failure a second time.
    expect(host.shadowRoot!.querySelector('jf-system-notice'), 'failure must keep its already-announced live region').toBe(failure);
    expect(success.isConnected).toBe(false);
    expect(success.textContent).not.toContain('Extraction failed:');
    await vi.advanceTimersByTimeAsync(1000);
    failure.closest('.toast')!.dispatchEvent(new Event('animationend'));
    await host.updateComplete;
    expect(host.shadowRoot!.querySelector('jf-system-notice')).toBeNull();
  } finally {
    host.remove(); store.stop();
  }
});
