// SPDX-License-Identifier: Apache-2.0
/** DE5/C10 — the clicked-notification listener follows its owner's lifetime, even mid-subscription. */
import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ isTauriRuntime: vi.fn(() => true), listen: vi.fn(), unlisten: vi.fn() }));
vi.mock('../../utils/tauriRuntime.js', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
import { installNoticeActivationBridge } from './tauriBridge.js';

const settled = () => vi.waitFor(() => expect(mocks.listen).toHaveBeenCalled()).then(() => new Promise(resolve => setTimeout(resolve, 0)));

beforeEach(() => {
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.unlisten.mockReset();
  mocks.listen.mockReset().mockResolvedValue(mocks.unlisten);
});

describe('DE5 the shell contract', () => {
  // Each side's tests mock the other, so only this reads both: a rename would otherwise pass here
  // and fail silently on a real desktop, where every call is swallowed by design.
  const shell = (file: string) => readFileSync(new URL(`../../../../shell/src-tauri/src/${file}`, import.meta.url), 'utf8');
  const web = (file: string) => readFileSync(new URL(`../../${file}`, import.meta.url), 'utf8');
  it('the commands the frontend invokes are registered, and the clicked event has one name', () => {
    expect(web('utils/notify.ts')).toContain("invoke('show_desktop_notice'");
    expect(web('utils/taskbarBadge.ts')).toContain("invoke('set_needs_you_badge'");
    expect(shell('lib.rs')).toContain('notices::show_desktop_notice');
    expect(shell('lib.rs')).toContain('notices::set_needs_you_badge');
    expect(shell('notices.rs')).toContain('"justsearch://notice-activated"');
    expect(web('shell-v0/router/tauriBridge.ts')).toContain("'justsearch://notice-activated'");
  });
  it('both sides agree on the overlay size the shell validates', () => {
    const size = /OVERLAY_SIZE(?:: u32)? = (\d+);/;
    expect(web('utils/taskbarBadge.ts').match(size)?.[1]).toBe(shell('notices.rs').match(size)?.[1]);
  });
});

describe('DE5 installNoticeActivationBridge', () => {
  it('reports the clicked event id from the shell', async () => {
    const activated = vi.fn();
    installNoticeActivationBridge(activated);
    await settled();
    expect(mocks.listen.mock.calls[0]![0]).toBe('justsearch://notice-activated');
    mocks.listen.mock.calls[0]![1]({ payload: 'authorization:a' });
    expect(activated).toHaveBeenCalledWith('authorization:a');
  });
  it('stopping removes the listener', async () => {
    const stop = installNoticeActivationBridge(vi.fn());
    await settled();
    stop();
    expect(mocks.unlisten).toHaveBeenCalledTimes(1);
  });
  it('C10 stopping before the subscription lands still removes it', async () => {
    installNoticeActivationBridge(vi.fn())();
    await settled();
    expect(mocks.unlisten).toHaveBeenCalledTimes(1);
  });
  it('listens to nothing in a plain browser', () => {
    mocks.isTauriRuntime.mockReturnValue(false);
    installNoticeActivationBridge(vi.fn())();
    expect(mocks.listen).not.toHaveBeenCalled();
  });
});
