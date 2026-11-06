// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from './tauriRuntime';

/**
 * DE5 — report each change in whether this window has the person's attention, so a focus read
 * already in flight can be recognised as old. The DOM's signals fire when the webview itself gains
 * or loses focus; inside the shell the native signal also covers focus reaching the window but not
 * the webview. A change may be reported by more than one of them. Returns the stop function, which
 * also holds when the native subscription has not landed yet (C10).
 */
export function onWindowFocusChange(changed: () => void): () => void {
  const report = (): void => changed();
  window.addEventListener('focus', report);
  window.addEventListener('blur', report);
  document.addEventListener('visibilitychange', report);
  let stopNative: (() => void) | null = null;
  let stopped = false;
  if (isTauriRuntime()) {
    void import('@tauri-apps/api/window')
      .then(({ getCurrentWindow }) => getCurrentWindow().onFocusChanged(report))
      .then((unlisten) => { if (stopped) unlisten(); else stopNative = unlisten; })
      .catch(() => {});
  }
  return () => {
    stopped = true;
    stopNative?.();
    window.removeEventListener('focus', report);
    window.removeEventListener('blur', report);
    document.removeEventListener('visibilitychange', report);
  };
}
