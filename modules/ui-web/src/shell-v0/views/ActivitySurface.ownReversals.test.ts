// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/** DE6 — System › Activity lists the person's own actions that can still be undone, with their reversal. */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ActivitySurface } from './ActivitySurface.js';
import { offerReversal, __resetOwnReversalsForTest } from '../state/ownReversals.js';

let host: ActivitySurface;
afterEach(() => { host?.remove(); __resetOwnReversalsForTest(); vi.unstubAllGlobals(); });
async function flush() { await Promise.resolve(); await host.updateComplete; await Promise.resolve(); }
async function activateJfButton(el: Element | null | undefined) {
  if (!el) throw new Error('Missing action');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('Activity lists own reversals (DE6)', () => {
  it('DE6 Activity lists the person\'s own actions that can still be undone, with their reversal', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })));
    __resetOwnReversalsForTest();
    const reverse = vi.fn(async () => {});
    offerReversal({ message: 'Removed Notes from your library', actionLabel: 'Add Notes again', reverse, reversed: 'Added Notes again' });
    host = new ActivitySurface(); document.body.append(host);
    await flush();
    const section = host.shadowRoot?.querySelector('[data-testid="activity-own-reversals"]');
    expect(section?.textContent).toContain('Removed Notes from your library');
    await activateJfButton(section?.querySelector('jf-button'));
    await flush(); await flush();
    expect(reverse).toHaveBeenCalledTimes(1);
    expect(host.shadowRoot?.querySelector('[data-testid="activity-own-reversals"]')).toBeNull();
  });
});
