// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import { render } from 'lit';
import { projectBackend } from '../operations/ActionLedgerClient.js';
import { renderEventRow } from './eventRow.js';

vi.mock('../api/authorizedFetch.js', () => ({ authorizedFetch: (...args: Parameters<typeof fetch>) => fetch(...args) }));
const key = '0194f72c-0000-7000-8000-000000000001';
const message = 'The rebuild or migration could not safely start or resume the new index; check index health and current settings before starting a new rebuild. (Code: BULK_GENERATION_REFUSED)';
afterEach(() => { document.body.replaceChildren(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it('an accepted agent tool row follows the operation through to its later refusal', async () => {
  vi.useFakeTimers();
  let finished = false;
  const fetch = vi.fn(async () => new Response(JSON.stringify(finished
    ? { state: 'failed', historySince: 0, message, reason: 'BULK_GENERATION_REFUSED', result: { code: 'BULK_GENERATION_REFUSED' } }
    : { state: 'accepted', historySince: 0 })));
  vi.stubGlobal('fetch', fetch);
  const host = document.createElement('div'); document.body.append(host);
  const row = projectBackend({ id: `operation:${key}`, kind: 'operation', occurredAt: '2026-10-22T09:00:00Z',
    originator: 'agent', transport: 'AGENT_LOOP', operationId: 'core.bulk-reindex', outcome: 'SUCCESS', correlationId: 'session' });
  render(renderEventRow(row), host);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledTimes(1);
  finished = true;
  await vi.advanceTimersByTimeAsync(4_000);
  expect(host.querySelector('jf-operation-outcome-detail')?.shadowRoot?.textContent).toContain(message);
  expect(host.querySelector('jf-operation-outcome-detail')?.shadowRoot?.textContent).toContain('Code: BULK_GENERATION_REFUSED');
  expect(host.querySelector('jf-operation-outcome-detail')?.shadowRoot?.textContent?.split('Code: BULK_GENERATION_REFUSED')).toHaveLength(2);
  await vi.advanceTimersByTimeAsync(8_000);
  expect(fetch).toHaveBeenCalledTimes(2);
});

it('a promoted-with-gaps outcome does not display a failure sentence or keep polling', async () => {
  vi.useFakeTimers();
  const fetch = vi.fn(async () => new Response(JSON.stringify({ state: 'failed', historySince: 0,
    reason: 'PROMOTED_WITH_GAPS', result: { code: 'PROMOTED_WITH_GAPS' } })));
  vi.stubGlobal('fetch', fetch);
  const host = document.createElement('div'); document.body.append(host);
  render(renderEventRow(projectBackend({ id: `operation:${key}`, kind: 'operation', occurredAt: '2026-10-22T09:00:00Z',
    originator: 'user', operationId: 'core.bulk-reindex', outcome: 'FAILURE' })), host);
  await vi.advanceTimersByTimeAsync(0);
  const detail = host.querySelector('jf-operation-outcome-detail');
  expect(detail?.shadowRoot?.querySelector('[role="alert"]')).toBeNull();
  expect(detail?.shadowRoot?.textContent).not.toContain('did not complete');
  expect(detail?.shadowRoot?.textContent).toContain('Code: PROMOTED_WITH_GAPS');
  await vi.advanceTimersByTimeAsync(8_000);
  expect(fetch).toHaveBeenCalledTimes(1);
});
