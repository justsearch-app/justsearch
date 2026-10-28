/** @vitest-environment happy-dom */
import { describe, expect, it, vi } from 'vitest';
import { createTauriDeepLinkSource } from './TauriDeepLinkSource.js';

const bridge = vi.hoisted(() => ({
  getCurrent: vi.fn(),
  listen: vi.fn(),
}));
vi.mock('../../../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/plugin-deep-link', () => ({ getCurrent: bridge.getCurrent }));
vi.mock('@tauri-apps/api/event', () => ({ listen: bridge.listen }));

describe('desktop deep links (Linux argv and single-instance events)', () => {
  it('dispatches the fresh-instance URL, then a URL forwarded to the running instance', async () => {
    const url = 'justsearch://surface/core.help-surface';
    bridge.getCurrent.mockResolvedValue([url]);
    const unlisten = vi.fn();
    bridge.listen.mockResolvedValue(unlisten);
    const dispatch = vi.fn();
    const teardown = await createTauriDeepLinkSource().start(dispatch);
    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(dispatch.mock.calls[0][0]).toMatchObject({ transport: 'URL_DEEPLINK' });
    const coldIntent = dispatch.mock.calls[0][0];
    expect(bridge.listen.mock.calls[0][0]).toBe('justsearch://deep-link');
    // The single-instance callback emits its forwarded argv URL on this event.
    bridge.listen.mock.calls[0][1]({ payload: url });
    expect(dispatch).toHaveBeenCalledTimes(2);
    expect(dispatch.mock.calls[1]).toEqual([coldIntent, { pushHistory: true }]);
    if (typeof teardown === 'function') teardown();
    expect(unlisten).toHaveBeenCalledOnce();
  });
});
