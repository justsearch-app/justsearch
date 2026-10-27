// SPDX-License-Identifier: Apache-2.0
/**
 * host.ui capability module (548 §4.2 Increment A) — extracted from HostApiImpl.
 * Owns the trusted/untrusted showNotification composition (UNTRUSTED is
 * rate-limited to 1/sec, selected by tier — no runtime if(tier)) plus
 * confirm-dialog / clipboard / inspector / scroll-surface affordances.
 */

import { confirmAsync } from '../../components/ConfirmDialog.js';
import { copyToClipboard } from '../../utils/clipboardCopy.js';
import { setSelected } from '../../state/inspectorState.js';
import type { SelectedItem } from '../../state/inspectorState.js';
import { listSurfaces as catalogListSurfaces } from '../../../api/registry/SurfaceCatalogClient.js';
import type {
  PluginHostApi,
  PluginTrustTier,
  NotificationOptions,
  ConfirmDialogOptions,
  InspectorItem,
} from '../plugin-types.js';

type PluginUi = PluginHostApi['ui'];

interface UiDeps {
  showNotification?: (message: string, options?: NotificationOptions) => void;
}

export function createUiApi(tier: PluginTrustTier, deps: UiDeps): PluginUi {
  const isUntrusted = tier === 'UNTRUSTED_PLUGIN';
  const showNotificationFn: (message: string, options?: NotificationOptions) => void = isUntrusted
    ? (() => {
        // Rate-limit: 1/sec for UNTRUSTED
        let lastShown = 0;
        return (message: string, options?: NotificationOptions) => {
          const now = Date.now();
          if (now - lastShown < 1000) return;
          lastShown = now;
          deps.showNotification?.(message, options);
        };
      })()
    : (message: string, options?: NotificationOptions) => deps.showNotification?.(message, options);

  return {
    showNotification: showNotificationFn,
    showConfirmDialog: async (message: string, options?: ConfirmDialogOptions): Promise<boolean> => {
      return confirmAsync({
        title: 'Confirm',
        message,
        confirmLabel: options?.confirmLabel ?? 'Confirm',
        cancelLabel: options?.cancelLabel ?? 'Cancel',
        variant: options?.destructive ? ('danger' as const) : undefined,
        ...(options?.typedConfirmWord !== undefined ? { typedConfirmWord: options.typedConfirmWord } : {}),
      });
    },
    copyToClipboard: async (text: string): Promise<void> => {
      await copyToClipboard(text);
    },
    showInspector: (item: InspectorItem) => {
      const selected: SelectedItem = {
        id: item.id,
        title: item.title,
        path: item.path ?? '',
        kind: item.kind,
      };
      const start = item.highlightStartLine;
      const end = item.highlightEndLine;
      if (typeof start === 'number' && typeof end === 'number' &&
          Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end >= start) {
        selected.highlightStartLine = start;
        selected.highlightEndLine = end;
      }
      setSelected(selected);
    },
    // Tempdoc 508-followup §ε1 — best-effort scroll for a mounted surface.
    scrollSurfaceTo: (surfaceId: string, target) => {
      try {
        const surface = catalogListSurfaces().find((s) => s.id === surfaceId);
        if (!surface) return;
        const el = document.querySelector(surface.mountTag) as
          | (HTMLElement & { scrollSurfaceTo?: (t: typeof target) => void })
          | null;
        if (!el) return;
        if (typeof el.scrollSurfaceTo === 'function') {
          el.scrollSurfaceTo(target);
          return;
        }
        if (target.top) {
          el.scrollTop = 0;
          return;
        }
        if (target.bottom) {
          el.scrollTop = el.scrollHeight;
          return;
        }
      } catch {
        // Best-effort: scroll failures don't propagate to the plugin.
      }
    },
  };
}
