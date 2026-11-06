// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from './tauriRuntime';

/** Must equal OVERLAY_SIZE in the shell's notices.rs, which rejects any other size. */
const OVERLAY_SIZE = 32;

/** The count the taskbar shows. Outlives a page reload, which keeps the badge; a new process has none. */
const SHOWN_KEY = 'justsearch.taskbarBadge';

/**
 * The count drawn for the Windows taskbar, which has an overlay icon instead of a badge count.
 * Colours are the pill's own count tokens, so the badge matches the theme it was raised in.
 */
function overlayPixels(count: number): number[] | null {
  const context = document.createElement('canvas').getContext('2d', { willReadFrequently: true });
  if (!context) return null;
  context.canvas.width = context.canvas.height = OVERLAY_SIZE;
  const style = getComputedStyle(document.documentElement);
  context.fillStyle = style.getPropertyValue('--accent-warning').trim();
  context.beginPath();
  context.arc(OVERLAY_SIZE / 2, OVERLAY_SIZE / 2, OVERLAY_SIZE / 2, 0, Math.PI * 2);
  context.fill();
  context.fillStyle = style.getPropertyValue('--accent-on-warning').trim();
  context.font = `600 ${OVERLAY_SIZE * 0.625}px sans-serif`;
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.fillText(count > 9 ? '9+' : String(count), OVERLAY_SIZE / 2, OVERLAY_SIZE / 2);
  return [...context.getImageData(0, 0, OVERLAY_SIZE, OVERLAY_SIZE).data];
}

/**
 * Show the needs-you count on the taskbar or dock; 0 clears it. Only a change reaches the shell, so
 * a start with nothing waiting calls nothing. No error where there is no badge.
 */
export async function setTaskbarBadge(count: number): Promise<void> {
  if (!isTauriRuntime()) return;

  try {
    if (count === Number(sessionStorage.getItem(SHOWN_KEY))) return;
    sessionStorage.setItem(SHOWN_KEY, String(count));
    const { invoke } = await import('@tauri-apps/api/core');
    await invoke('set_needs_you_badge', { count, overlay: count > 0 ? overlayPixels(count) : null });
  } catch {
    // Shell command not available — swallow silently.
  }
}
