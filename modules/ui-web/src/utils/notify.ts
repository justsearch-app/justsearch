// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from './tauriRuntime';

export interface DesktopNotification {
  /** The event's id: the shell shows it once and reports it back when it is clicked. */
  readonly id: string;
  readonly title: string;
  readonly body?: string;
  readonly sound: boolean;
}

/**
 * Show a desktop notification through the shell. The shell owns delivery because only it can see
 * the click. No-ops silently in browser mode or if the shell cannot show it.
 */
export async function sendDesktopNotification(notification: DesktopNotification): Promise<void> {
  if (!isTauriRuntime()) return;

  try {
    const { invoke } = await import('@tauri-apps/api/core');
    await invoke('show_desktop_notice', { ...notification });
  } catch {
    // Shell command not available — swallow silently.
  }
}
