// SPDX-License-Identifier: Apache-2.0
/**
 * TauriDeepLinkSource — Intent substrate tier 1 (slice 492).
 *
 * Owns the OS-level deep-link ingress channel:
 *   1. **Cold-launch read.** On `start(...)`, queries the Tauri plugin for
 *      any URL the app was opened with (e.g., user clicked a
 *      `justsearch://...` link in their email).
 *   2. **Warm event subscription.** Subscribes to the
 *      `justsearch://deep-link` Tauri event so subsequent deep-link
 *      arrivals (single-instance forwarded argv; `on_open_url` events)
 *      route through the same dispatcher.
 *
 * In browser / vite dev (no Tauri runtime), `start(...)` is a no-op.
 *
 * Transport-tag policy: every dispatched Intent stamps `URL_DEEPLINK` —
 * distinguishes OS-level entries from URL-bar paste (`URL_BAR`).
 *
 * Replaces the prior split between `Shell.connectedCallback`'s cold-launch
 * branch (calling `readColdLaunchDeepLink` + `parseUrl` + direct
 * `intentRouter.dispatch`) and `installDeepLinkBridge` (a warm-event
 * subscription that also called `intentRouter.dispatch`). Both halves are
 * now one source class with one `start(...)` entry.
 */

import { isTauriRuntime } from '../../../utils/tauriRuntime.js';
import { isSurfaceUrl, parseUrl } from '../parser.js';
import type { Intent } from '../types.js';
import type { IntentSource, SourceDispatch } from './IntentSource.js';

const TAURI_EVENT_NAME = 'justsearch://deep-link';

/** Stable Manifest-tier id this source corresponds to. */
export const TAURI_DEEP_LINK_SOURCE_REF = 'core.os-tauri-deeplink';

/** C1 — `onUnknownSurface`: a window address the grammar rejects; the frame says so. */
export function createTauriDeepLinkSource(config: { onUnknownSurface?: (url: string) => void } = {}): IntentSource {
  return {
    ref: TAURI_DEEP_LINK_SOURCE_REF,
    async start(dispatch: SourceDispatch): Promise<() => void> {
      if (!isTauriRuntime()) {
        return () => {
          /* no-op when not running under Tauri */
        };
      }

      // 1. Cold-launch read.
      try {
        const { getCurrent } = await import('@tauri-apps/plugin-deep-link');
        const urls = await getCurrent();
        const coldLaunchUrl =
          urls?.find((u: string) => u.startsWith('justsearch://')) ?? null;
        if (coldLaunchUrl) {
          const intent = toIntent(coldLaunchUrl);
          if (intent) dispatch(intent, { pushHistory: true });
          else if (isSurfaceUrl(coldLaunchUrl)) config.onUnknownSurface?.(coldLaunchUrl);
        }
      } catch {
        // Cold-launch read failures are non-fatal — proceed to warm-event subscription.
      }

      // 2. Warm-event subscription.
      try {
        const { listen } = await import('@tauri-apps/api/event');
        const unlisten = await listen<string>(TAURI_EVENT_NAME, (event) => {
          const url = event.payload;
          if (typeof url !== 'string') return;
          const intent = toIntent(url);
          if (!intent) {
            console.warn(
              `[TauriDeepLinkSource] received un-parseable deep-link URL: ${url}`,
            );
            if (isSurfaceUrl(url)) config.onUnknownSurface?.(url);
            return;
          }
          dispatch(intent, { pushHistory: true });
        });
        return unlisten;
      } catch (err) {
        console.warn('[TauriDeepLinkSource] failed to subscribe to Tauri event:', err);
        return () => {
          /* no-op */
        };
      }
    },
  };
}

function toIntent(url: string): Intent | null {
  const address = parseUrl(url);
  if (!address) return null;
  return {
    address,
    transport: 'URL_DEEPLINK',
  };
}
