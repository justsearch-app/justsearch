// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({ isTauriRuntime: vi.fn(() => true), invoke: vi.fn(async () => undefined) }));
vi.mock('./tauriRuntime', () => ({ isTauriRuntime: mocks.isTauriRuntime }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
import { sendDesktopNotification } from './notify.js';

const notice = { id: 'authorization:a', title: 'Claude Desktop requests Ingest Files', body: 'Denied automatically in 4 min', sound: true };

beforeEach(() => {
  mocks.isTauriRuntime.mockReturnValue(true);
  mocks.invoke.mockReset().mockResolvedValue(undefined);
});

describe('sendDesktopNotification', () => {
  it('hands the event and its id to the shell, which shows it once and reports the click', async () => {
    await sendDesktopNotification(notice);
    expect(mocks.invoke).toHaveBeenCalledWith('show_desktop_notice', notice);
  });
  it('does nothing in a plain browser, and asks no permission', async () => {
    mocks.isTauriRuntime.mockReturnValue(false);
    await sendDesktopNotification(notice);
    expect(mocks.invoke).not.toHaveBeenCalled();
  });
  it('a shell that cannot show it raises no error', async () => {
    mocks.invoke.mockRejectedValue(new Error('unavailable'));
    await expect(sendDesktopNotification(notice)).resolves.toBeUndefined();
  });
});
