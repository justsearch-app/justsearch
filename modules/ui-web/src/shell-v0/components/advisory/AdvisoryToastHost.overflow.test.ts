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

it('XD2 announces an overflowed toast only once across repeated cap displacements', async () => {
  vi.useFakeTimers();
  const store = new AdvisoryStore(); store.start();
  const host = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  host.store = store; document.body.append(host);
  const observed = new WeakMap<Element, string>();
  const announcements: string[] = [];
  async function scan(): Promise<void> {
    await host.updateComplete;
    for (const notice of host.shadowRoot!.querySelectorAll('jf-system-notice')) {
      const text = notice.textContent!.trim();
      if (observed.get(notice) !== text) {
        observed.set(notice, text);
        if (notice.getAttribute('aria-live') === 'polite' || notice.getAttribute('aria-live') === 'assertive') {
          announcements.push(text);
        }
      }
    }
  }
  try {
    const failure = 'Extraction failed: invalid JSON';
    emitEphemeralToast({ message: failure, severity: 'info', durationMs: 10000 });
    await scan();
    for (let round = 0; round < 2; round++) {
      for (const [i, severity] of (['warning', 'info', 'success'] as const).entries()) {
        emitEphemeralToast({ message: `Other ${round}-${i}`, severity, durationMs: 500 + i * 500, supersede: false });
        await scan();
      }
      expect(host.shadowRoot!.querySelector('[data-testid="toast-more"]')).not.toBeNull();
      expect([...host.shadowRoot!.querySelectorAll('jf-system-notice')].some(n => n.textContent!.includes(failure))).toBe(false);
      for (let i = 0; i < 3; i++) { await vi.advanceTimersByTimeAsync(500); await scan(); }
      expect([...host.shadowRoot!.querySelectorAll('jf-system-notice')].some(n => n.textContent!.includes(failure))).toBe(true);
    }
    expect(announcements.filter(text => text.includes(failure)), 'one exposed live announcement over the toast lifetime').toHaveLength(1);
    const restored = [...host.shadowRoot!.querySelectorAll('jf-system-notice')].find(n => n.textContent!.includes(failure))!;
    expect(restored.getAttribute('aria-live'), 'a returning toast must be silent').toBe('off');
    expect(restored.hasAttribute('role')).toBe(false);
    for (let round = 0; round < 2; round++) {
      for (let i = 0; i < 3; i++) expect(announcements.filter(text => text.includes(`Other ${round}-${i}`))).toHaveLength(1);
    }
    await vi.advanceTimersByTimeAsync(10000); await host.updateComplete;
  } finally { host.remove(); store.stop(); }
});
