// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { render } from 'lit';
import { projectBackend } from '../operations/ActionLedgerClient.js';
import { renderEventRow } from './eventRow.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (...args: unknown[]) => fetch(...args as Parameters<typeof fetch>) }));

const key = '019940c0-0000-7000-8000-000000000001';
const message = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild.';

afterEach(() => { document.body.innerHTML = ''; vi.unstubAllGlobals(); });

it('shows the recorded refusal sentence and code in the shared Activity and agent History row', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ state: 'failed', historySince: 0,
    reason: 'BULK_GENERATION_REFUSED', message, result: { code: 'BULK_GENERATION_REFUSED' } })));
  vi.stubGlobal('fetch', fetchMock);
  const row = projectBackend({ id: `operation:${key}`, kind: 'operation', occurredAt: '2026-10-22T09:00:00Z',
    originator: 'user', operationId: 'core.bulk-reindex', outcome: 'FAILURE' });
  const host = document.createElement('div'); document.body.append(host);
  render(renderEventRow(row, { apiBase: 'http://127.0.0.1:33221/' }), host);
  await vi.waitFor(() => {
    const detail = host.querySelector('jf-operation-outcome-detail');
    expect(detail?.shadowRoot?.textContent).toContain(message);
    expect(detail?.shadowRoot?.textContent).toContain('Code: BULK_GENERATION_REFUSED');
  });
  expect(fetchMock).toHaveBeenCalledWith(`http://127.0.0.1:33221/api/operation-history/${key}`);
});

it('does not read a receipt for unrelated operations or unrecorded rebuilds', () => {
  const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock);
  for (const [operationId, outcome] of [['core.reindex', 'FAILURE'], ['core.bulk-reindex', 'SUCCESS']]) {
    const host = document.createElement('div'); document.body.append(host);
    render(renderEventRow(projectBackend({ id: operationId === 'core.reindex' ? `operation:${key}` : 'operation:legacy', kind: 'operation', occurredAt: '2026-10-22T09:00:00Z',
      originator: 'user', operationId, outcome })), host);
    expect(host.querySelector('jf-operation-outcome-detail')).toBeNull();
  }
  expect(fetchMock).not.toHaveBeenCalled();
});
