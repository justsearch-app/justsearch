// SPDX-License-Identifier: Apache-2.0
/**
 * Tauri deep-link bridge — slice 489 §15.
 *
 * In Tauri builds: subscribes to the `justsearch://deep-link` event emitted
 * by the Rust shell (see `modules/shell/src-tauri/src/lib.rs` setup block)
 * and routes the URL through the IntentRouter.
 *
 * In browser (vite dev): no-op — Tauri APIs absent.
 *
 * The detection follows the pattern established by `dragDetect.ts` and
 * `http.ts`: check `isTauriRuntime()` and dynamically import
 * `@tauri-apps/api/event` only when running inside Tauri.
 *
 * Tauri-emitted event payload shape: a plain string carrying the URL
 * (`"justsearch://surface/core.library-surface?folder=docs"`). The bridge
 * parses it via the shared parser and dispatches a {@link Intent} with
 * `transport: 'URL_DEEPLINK'`.
 */

import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { parseUrl } from './parser.js';
import type { IntentRouter } from './intentRouter.js';
import { setNativeSearchShortcutActive } from '../commands/KeybindingRegistry.js';

export type SearchSummon = { kind: 'focusSearch' } | { kind: 'deepLink'; url: string };

/** Subscribe before draining; teardown also covers startup and partial subscription failures. */
export function installSearchSummonBridge(
  summon: (request: SearchSummon) => Promise<void>,
  startupSettled: () => void = () => {},
): () => void {
  if (!isTauriRuntime()) { startupSettled(); return () => {}; }
  let disposed = false;
  let draining = false;
  let requested = false;
  let revision = -1;
  const stops: Array<() => void> = [];
  const stop = (): void => {
    if (!disposed) setNativeSearchShortcutActive(false);
    disposed = true;
    for (const unsubscribe of stops.splice(0)) unsubscribe();
  };
  const updateStatus = (value: unknown): void => {
    if (disposed || !value || typeof value !== 'object' || !('active' in value) || typeof value.active !== 'boolean') return;
    const next = 'revision' in value && typeof value.revision === 'number' ? value.revision : 0;
    if (next < revision) return;
    revision = next;
    setNativeSearchShortcutActive(value.active);
  };
  const start = async (): Promise<void> => {
    const { invoke } = await import('@tauri-apps/api/core');
    const { listen } = await import('@tauri-apps/api/event');
    const drain = async (): Promise<void> => {
      requested = true;
      if (draining || disposed) return;
      draining = true;
      try {
        do {
          requested = false;
          const request = await invoke<SearchSummon | null>('take_search_summon');
          if (request && !disposed) await summon(request);
        } while (requested && !disposed);
      } finally { draining = false; }
    };
    if (disposed) return;
    stops.push(await listen('justsearch://search-shortcut-status', (event) => updateStatus(event.payload)));
    if (disposed) { stop(); return; }
    stops.push(await listen('justsearch://summon-search', () => { void drain().catch(() => {}); }));
    if (disposed) { stop(); return; }
    // Registration status must not delay an otherwise ready focus request.
    void invoke('search_shortcut_status').then(updateStatus).catch(() => {});
    await drain();
  };
  void start().catch(stop).finally(startupSettled);
  return stop;
}

const TAURI_EVENT_NAME = 'justsearch://deep-link';

/**
 * Install the deep-link listener. Returns an unsubscribe handle (no-op when
 * not running under Tauri or when subscription fails).
 *
 * Per Tauri convention, dynamic-imports the plugin only when needed so vite
 * dev builds don't drag the Tauri runtime in.
 */
export async function installDeepLinkBridge(router: IntentRouter): Promise<() => void> {
  if (!isTauriRuntime()) {
    return () => {
      /* no-op */
    };
  }
  try {
    const { listen } = await import('@tauri-apps/api/event');
    const unlisten = await listen<string>(TAURI_EVENT_NAME, (event) => {
      const url = event.payload;
      if (typeof url !== 'string') return;
      const address = parseUrl(url);
      if (!address) {
        console.warn(`[tauriBridge] received un-parseable deep-link URL: ${url}`);
        return;
      }
      void router.dispatch({
        address,
        transport: 'URL_DEEPLINK',
      });
    });
    return unlisten;
  } catch (err) {
    console.warn('[tauriBridge] failed to subscribe to Tauri event:', err);
    return () => {
      /* no-op */
    };
  }
}

/**
 * Read the cold-launch URL (if Tauri was launched via a deep-link). Returns
 * null when not in Tauri, when the API call fails, or when no URL is queued.
 *
 * Called once during Shell's connectedCallback to handle the case where
 * Tauri was opened *from* a deep-link (rather than receiving one mid-session).
 */
export async function readColdLaunchDeepLink(): Promise<string | null> {
  if (!isTauriRuntime()) return null;
  try {
    const { getCurrent } = await import('@tauri-apps/plugin-deep-link');
    const urls = await getCurrent();
    if (!urls || urls.length === 0) return null;
    // The plugin returns all URLs the app was launched with; the first
    // justsearch:// URL is the one this slice cares about.
    return urls.find((u: string) => u.startsWith('justsearch://')) ?? null;
  } catch {
    return null;
  }
}
