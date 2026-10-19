// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ invoke: vi.fn(), endpoint: vi.fn(), listen: vi.fn() }));
vi.mock('../utils/tauriRuntime.js', () => ({ isTauriRuntime: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
vi.mock('../api/http', () => ({ resolveApiEndpoint: mocks.endpoint }));
import { resolveBootWithRecovery } from './desktopRecovery.js';

const root = () => document.getElementById('root')!;
const settle = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
function status(phase: string, remaining: unknown[] = []): void {
  mocks.invoke.mockImplementation(async (command) => command === 'factory_reset_status'
    ? { phase, remaining }
    : command === 'supervisor_state' ? { state: 'running' } : { state: 'up_to_date' });
}
function action(name: string): HTMLButtonElement {
  const surface = root().querySelector('jf-factory-reset-recovery');
  const found = [...(surface?.shadowRoot?.querySelectorAll('button') ?? [])].find((b) => b.textContent?.trim() === name);
  expect(found, name).toBeTruthy();
  return found!;
}

describe('WP5.1 reset boot gate', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="root"></div>';
    localStorage.clear(); sessionStorage.clear();
    mocks.invoke.mockReset();
    mocks.listen.mockReset().mockResolvedValue(() => {});
    mocks.endpoint.mockReset().mockResolvedValue({ baseUrl: 'http://127.0.0.1:40404' });
    vi.spyOn(window.location, 'reload').mockImplementation(() => {});
  });
  afterEach(async () => {
    document.body.replaceChildren();
    await settle();
    vi.unstubAllGlobals();
    localStorage.clear(); sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('RS4 incomplete reset blocks API discovery and offers retry with each remaining path', async () => {
    status('incomplete', [{ path: 'conversations/held.json', error: 'Access denied' }]);
    expect(await resolveBootWithRecovery(root())).toBeNull();
    await settle();
    const surface = root().querySelector('jf-factory-reset-recovery')!;
    expect(surface.shadowRoot!.textContent).toContain('conversations/held.json');
    expect(surface.shadowRoot!.textContent).toContain('Access denied');
    expect(surface.shadowRoot!.textContent).toContain('Reset incomplete');
    expect(mocks.endpoint).not.toHaveBeenCalled();
    action('Retry reset').click();
    action('Retry reset').click();
    await settle();
    expect(mocks.invoke.mock.calls.filter(([c]) => c === 'retry_factory_reset')).toEqual([['retry_factory_reset']]);
  });

  it('RS5 Cancel reset keeps remaining data and restarts without opening ordinary stores', async () => {
    status('incomplete', [{ path: 'index/held', error: 'File in use' }]);
    localStorage.setItem('justsearch.draft.search-v3.composer', 'remaining draft');
    await resolveBootWithRecovery(root());
    await settle();
    action('Cancel reset').click();
    await settle();
    expect(mocks.invoke).toHaveBeenCalledWith('cancel_factory_reset');
    expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('remaining draft');
    expect(mocks.endpoint).not.toHaveBeenCalled();
  });

  it('RS6 clears drafts, recents, titles and per-window pointers before acknowledging cleanup', async () => {
    status('awaiting_storage');
    for (const key of ['justsearch.draft.search-v3.composer', 'justsearch.userState.v2', 'justsearch.recentSearches', 'justsearch.conversationTitles']) localStorage.setItem(key, 'private');
    sessionStorage.setItem('justsearch.search-v3.session', 'old conversation');
    mocks.invoke.mockImplementation(async (command) => {
      if (command === 'factory_reset_status') return { phase: 'awaiting_storage', remaining: [] };
      if (command === 'complete_factory_reset_storage') {
        expect(localStorage.length).toBe(0);
        expect(sessionStorage.length).toBe(0);
      }
      return { state: 'running' };
    });
    expect(await resolveBootWithRecovery(root())).toBeNull();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(mocks.invoke).toHaveBeenCalledWith('complete_factory_reset_storage');
    expect(mocks.endpoint).not.toHaveBeenCalled();
  });

  it('RS6 a storage-cleanup failure stays visible and is never acknowledged as complete', async () => {
    status('awaiting_storage');
    vi.stubGlobal('localStorage', new Proxy(localStorage, {
      get(target, key) {
        if (key === 'clear') return () => { throw new Error('Storage unavailable'); };
        return Reflect.get(target, key);
      },
    }));
    expect(await resolveBootWithRecovery(root())).toBeNull();
    await settle();
    expect(root().querySelector('jf-factory-reset-recovery')?.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain('Storage unavailable');
    expect(mocks.invoke).not.toHaveBeenCalledWith('complete_factory_reset_storage');
    expect(mocks.endpoint).not.toHaveBeenCalled();
  });

  it('RS4 completion is announced on the first screen and dismissed only by Continue', async () => {
    status('complete');
    expect(await resolveBootWithRecovery(root())).toBeNull();
    await settle();
    const surface = root().querySelector('jf-factory-reset-recovery')!;
    expect(surface.shadowRoot!.querySelector('[role="status"]')?.textContent).toContain('Reset complete');
    expect(surface.shadowRoot!.textContent).toContain('AI models and AI runtime were kept');
    expect(mocks.invoke).not.toHaveBeenCalledWith('acknowledge_factory_reset');
    action('Continue').click();
    await settle();
    expect(mocks.invoke).toHaveBeenCalledWith('acknowledge_factory_reset');
    expect(window.location.reload).toHaveBeenCalledOnce();
  });

  it('RS5 an unavailable reset-status channel blocks startup with a visible error', async () => {
    mocks.invoke.mockRejectedValue(new Error('Reset status unavailable'));
    expect(await resolveBootWithRecovery(root())).toBeNull();
    await settle();
    expect(root().querySelector('jf-factory-reset-recovery')?.shadowRoot?.querySelector('[role="alert"]')?.textContent).toContain('Reset status unavailable');
    expect(mocks.endpoint).not.toHaveBeenCalled();
  });

  it('RN3 no pending reset preserves window storage and resolves the backend normally', async () => {
    status('idle');
    localStorage.setItem('justsearch.draft.search-v3.composer', 'keep');
    expect(await resolveBootWithRecovery(root())).toBe('http://127.0.0.1:40404');
    expect(localStorage.getItem('justsearch.draft.search-v3.composer')).toBe('keep');
    expect(root().querySelector('jf-factory-reset-recovery')).toBeNull();
  });
});
