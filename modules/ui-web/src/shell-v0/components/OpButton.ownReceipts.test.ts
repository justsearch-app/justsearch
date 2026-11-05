// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import './OpButton.js';
import type { OpButton } from './OpButton.js';
import type { ActionButton } from './ActionButton.js';
import { EPHEMERAL_TOAST_EVENT, type EphemeralToastSpec } from './advisory/ephemeralToast.js';
import { __seedForTest, __resetForTest } from '../../api/registry/OperationCatalogClient.js';
import type { OperationCatalog } from '../../api/types/registry.js';

const actions = [
  ['core.clear-failed-jobs', 'Failed jobs cleared'],
  ['core.rebuild-index', 'Rebuild accepted. Completion has not yet been confirmed.'],
  ['core.reset-settings', 'Settings reset'],
] as const;
const receipts: EphemeralToastSpec[] = [];
const receive = (event: Event) => receipts.push((event as CustomEvent<EphemeralToastSpec>).detail);
beforeEach(() => {
  receipts.length = 0;
  document.addEventListener(EPHEMERAL_TOAST_EVENT, receive);
  __seedForTest({ schemaVersion: '1', catalogVersion: 1, namespace: 'core', primitive: 'Operation',
    entries: actions.map(([id]) => ({ id, policy: { risk: 'MEDIUM', confirm: { kind: 'INLINE' } },
      presentation: { labelKey: id, descriptionKey: '' }, lineage: { affects: [], supersedes: [] } })),
  } as unknown as OperationCatalog);
});
afterEach(() => {
  document.removeEventListener(EPHEMERAL_TOAST_EVENT, receive);
  document.body.replaceChildren(); __resetForTest(); vi.unstubAllGlobals();
});

it.each(actions)('C1 %s acknowledges success in the pill only after the band is answered and the response succeeds', async (id, message) => {
  let finish!: (response: Response) => void;
  const fetch = vi.fn(() => new Promise<Response>(resolve => { finish = resolve; }));
  vi.stubGlobal('fetch', fetch);
  const el = document.createElement('jf-op-button') as OpButton; el.operationId = id;
  document.body.append(el); await el.updateComplete;
  const action = el.shadowRoot!.querySelector<ActionButton>('jf-action-button')!;
  await action.updateComplete;
  action.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click();
  await action.updateComplete;
  const band = action.shadowRoot!.querySelector('jf-confirm-band')!;
  await band.updateComplete;
  expect(receipts).toEqual([]);
  expect(fetch).not.toHaveBeenCalled();
  band.shadowRoot!.querySelector<HTMLElement & { onActivate(): void }>('jf-button.confirm')!.onActivate();
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining(`/api/operations/${id}/invoke`), expect.objectContaining({ method: 'POST' })));
  expect(receipts).toEqual([]);
  finish(new Response(JSON.stringify({ success: true, message: 'ok' })));
  await vi.waitFor(() => expect(receipts).toEqual([expect.objectContaining({ classId: 'core.own-action', severity: 'success', message })]));
});

it.each(actions)('C1 %s never emits a success receipt for a refused operation', async id => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ success: false, message: 'Refused', errorClass: 'HANDLER_FAILURE' }), { status: 409 })));
  const el = document.createElement('jf-op-button') as OpButton; el.operationId = id;
  document.body.append(el); await el.updateComplete;
  const action = el.shadowRoot!.querySelector<ActionButton>('jf-action-button')!;
  await action.updateComplete;
  const error = vi.fn(); el.addEventListener('op-error', error);
  action.shadowRoot!.querySelector<HTMLButtonElement>('button.invoke')!.click(); await action.updateComplete;
  const band = action.shadowRoot!.querySelector('jf-confirm-band')!; await band.updateComplete;
  band.shadowRoot!.querySelector<HTMLElement & { onActivate(): void }>('jf-button.confirm')!.onActivate();
  await vi.waitFor(() => expect(error).toHaveBeenCalledTimes(1));
  expect(receipts).toEqual([]);
});
