// @vitest-environment happy-dom

/**
 * 569 Move 4 — the trusted ceremony renders on the Top Layer (a native <dialog> opened
 * with showModal()), so a user-authored skin overlay cannot occlude or spoof it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './AuthorizationHost.js';
import type { AuthorizationHost } from './AuthorizationHost.js';
import {
  requestAuthorization,
  setAuthorizationPresenter,
} from '../operations/authorizationBroker.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { requestCapability, listPendingConsentRequests, resolveConsentRequest } from '../substrates/consent/index.js';

let host: AuthorizationHost;

beforeEach(() => {
  host = document.createElement('jf-authorization-host') as AuthorizationHost;
  document.body.appendChild(host);
});

afterEach(() => {
  if (host.parentNode) host.remove();
  setAuthorizationPresenter(null);
  for (const request of listPendingConsentRequests()) resolveConsentRequest(request.id, 'deny');
  vi.restoreAllMocks();
});

async function settle(): Promise<void> {
  await host.updateComplete;
  await Promise.resolve();
  await host.updateComplete;
}

describe('Move 4 — trusted ceremony on the Top Layer', () => {
  const kinds = ['lightweight', 'typed', 'capability'] as const;
  type Kind = typeof kinds[number];
  function enqueue(kind: Kind, id: string): void {
    if (kind === 'capability') {
      void requestCapability({ contributorId: `plugin.${id}`, capability: 'net.fetch' });
    } else {
      void requestAuthorization({ pendingId: id, operationId: `core.${id}`,
        gateBehavior: kind === 'typed' ? 'TYPED_CONFIRM' : 'INLINE_CONFIRM' });
    }
  }

  it.each(kinds.flatMap(first => kinds.map(second => [first, second] as const)))(
    'queued %s → %s applies each prompt’s own modality', async (first, second) => {
      const invoker = document.createElement('button');
      document.body.append(invoker);
      invoker.focus();
      await settle();
      const dialog = host.shadowRoot!.querySelector('dialog')!;
      // happy-dom implements show/showModal on real dialogs but not :modal. Observe the native
      // entry points (calling through), alongside the app's modality authority and scroll lock.
      const modal = vi.spyOn(dialog, 'showModal');
      const nonModal = vi.spyOn(dialog, 'show');
      const assertMode = (kind: Kind, opened = true) => {
        expect(dialog.open).toBe(true);
        expect(modalOwnsFocus()).toBe(kind !== 'lightweight');
        expect(document.documentElement.style.overflow).toBe(kind === 'lightweight' ? '' : 'hidden');
        if (opened) expect(kind === 'lightweight' ? nonModal : modal).toHaveBeenCalledTimes(1);
        expect(kind === 'lightweight' ? modal : nonModal).not.toHaveBeenCalled();
      };
      const deny = async (kind: Kind) => {
        const button = host.shadowRoot!.querySelector(`[data-testid="${kind === 'capability' ? 'capability' : 'authorization'}-deny"]`)!;
        await (button as unknown as { updateComplete: Promise<unknown> }).updateComplete;
        const control = button.shadowRoot!.querySelector('jf-control')!;
        await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
        control.shadowRoot!.querySelector('button')!.click();
        await settle();
      };
      try {
        enqueue(first, 'first');
        enqueue(second, 'second');
        await settle();
        assertMode(first);
        modal.mockClear();
        nonModal.mockClear();
        await deny(first);
        assertMode(second, (first === 'lightweight') !== (second === 'lightweight'));
        await deny(second);
        expect(dialog.open).toBe(false);
        expect(modalOwnsFocus()).toBe(false);
        expect(document.activeElement).toBe(invoker);
      } finally {
        invoker.remove();
      }
    },
  );

  it.each(kinds.flatMap(first => kinds.filter(second => second !== first).map(second => [first, second] as const)))(
    'A1 selecting %s → %s from the status panel applies the selected prompt’s own modality', async (first, second) => {
      await settle();
      const dialog = host.shadowRoot!.querySelector('dialog')!;
      const modal = vi.spyOn(dialog, 'showModal');
      const nonModal = vi.spyOn(dialog, 'show');
      const id = (kind: Kind, name: string) => kind === 'capability'
        ? String(listPendingConsentRequests().find(r => r.contributorId === `plugin.${name}`)!.id) : name;
      enqueue(first, 'first');
      enqueue(second, 'second');
      await settle();
      // The first opens on arrival in its own modality; the status panel lists both.
      expect(dialog.open).toBe(true);
      expect(modalOwnsFocus()).toBe(first !== 'lightweight');
      expect(host.statusRequests()).toHaveLength(2);
      // Reviewing the open one keeps it open in the same modality.
      host.reviewStatusRequest(id(first, 'first'));
      await settle();
      expect(dialog.open).toBe(true);
      expect(modalOwnsFocus()).toBe(first !== 'lightweight');
      modal.mockClear(); nonModal.mockClear();
      // The first stays open (undecided) while the user selects the second from the panel.
      host.reviewStatusRequest(id(second, 'second'));
      await settle();
      expect(dialog.open).toBe(true);
      expect(host.shadowRoot!.querySelector('dialog')!.getAttribute('data-testid'))
        .toBe(second === 'capability' ? 'capability-ceremony' : 'authorization-ceremony');
      expect(modalOwnsFocus(), `${second} modality`).toBe(second !== 'lightweight');
      expect(document.documentElement.style.overflow).toBe(second === 'lightweight' ? '' : 'hidden');
      if ((first === 'lightweight') !== (second === 'lightweight')) {
        expect(second === 'lightweight' ? nonModal : modal, `${second} reopened in its own mode`).toHaveBeenCalledTimes(1);
      }
      expect(second === 'lightweight' ? modal : nonModal).not.toHaveBeenCalled();
    },
  );

  it('opens a high-risk ceremony as a modal <dialog> (showModal → top layer, un-occludable)', async () => {
    void requestAuthorization({
      pendingId: 'p',
      operationId: 'core.bulk-reindex',
      gateBehavior: 'TYPED_CONFIRM',
    });
    await settle();
    const dialog = host.shadowRoot?.querySelector('dialog.ceremony') as HTMLDialogElement | null;
    expect(dialog).not.toBeNull();
    expect(dialog?.open).toBe(true);
    expect(dialog?.classList.contains('lightweight')).toBe(false);
  });

  it('opens a low/medium ceremony non-blocking (lightweight)', async () => {
    void requestAuthorization({
      pendingId: 'p2',
      operationId: 'core.x',
      gateBehavior: 'INLINE_CONFIRM',
    });
    await settle();
    const dialog = host.shadowRoot?.querySelector('dialog.ceremony') as HTMLDialogElement | null;
    expect(dialog?.open).toBe(true);
    expect(dialog?.classList.contains('lightweight')).toBe(true);
  });
});
