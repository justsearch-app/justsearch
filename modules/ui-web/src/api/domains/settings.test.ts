// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';
import { updateSettingsV2 } from './settings.js';
import { authorizedFetch } from '../../shell-v0/api/authorizedFetch.js';

vi.mock('../../shell-v0/api/authorizedFetch.js', () => ({ authorizedFetch: vi.fn() }));
afterEach(() => vi.resetAllMocks());

describe('public settings domain mutation', () => {
  it('uses the caller observation and exposes receipt-only completion without a fresh GET', async () => {
    const witness = { acceptedRevision: 4, lastCommittedOperationKey: '01993ba0-0000-7000-8000-000000000001' };
    const send = vi.mocked(authorizedFetch).mockImplementation(async (_, init) => {
      const body = JSON.parse(String(init?.body));
      return new Response(JSON.stringify({ state: 'COMPLETE', operationKey: body.operationKey,
        witness: { acceptedRevision: 5, lastCommittedOperationKey: body.operationKey } }));
    });
    const result = await updateSettingsV2('http://localhost:8080', { ui: { excludePatterns: ['derived'] } }, witness);
    expect(send).toHaveBeenCalledTimes(1);
    expect(send.mock.calls[0]?.[0]).toBe('http://localhost:8080/api/settings/v2');
    expect(send.mock.calls[0]?.[1]?.method).toBe('POST');
    expect(JSON.parse(String(send.mock.calls[0]?.[1]?.body)).witness).toEqual(witness);
    expect(result.state).toBe('COMPLETE');
    expect(result.projection.ui).toBeUndefined();
  });

  it('surfaces stale-base conflict without reading a newer witness or silently resubmitting', async () => {
    const send = vi.mocked(authorizedFetch).mockResolvedValue(new Response(JSON.stringify({ errorCode: 'VERSION_CONFLICT' }), { status: 409 }));
    await expect(updateSettingsV2('', { ui: { theme: 'dark' } }, { acceptedRevision: 0, lastCommittedOperationKey: null }))
      .rejects.toMatchObject({ code: 'VERSION_CONFLICT', attempt: { witness: { acceptedRevision: 0 } } });
    expect(send).toHaveBeenCalledTimes(1);
  });
});
