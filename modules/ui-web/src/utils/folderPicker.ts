// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 450 §1.6 — Framework-agnostic folder-picker abstraction.
 *
 * In Tauri runtime: opens the native plugin-dialog directory picker.
 * In browser runtime: returns null and the caller should fall back to
 * a manual text input UX (the React LibraryView pattern).
 *
 * Lazy-imports `@tauri-apps/plugin-dialog` to avoid pulling Tauri-only
 * code into browser bundles.
 */

import { isTauriRuntime } from './tauriRuntime';

export type FolderPickerResult = string | null | { readonly error: string };

export interface PickFolderOptions {
  title?: string;
  /** Optional starting directory for the dialog. */
  defaultPath?: string;
}

/**
 * Open a native folder-picker dialog.
 *
 * @returns the selected absolute path, `null` on cancellation or browser runtime,
 * or an error outcome for internal first-launch recovery. PluginPlatform maps
 * failures back to `null` to preserve the plugin API contract.
 */
export async function pickFolder(
  options: PickFolderOptions = {},
): Promise<FolderPickerResult> {
  if (!isTauriRuntime()) return null;
  try {
    const mod = await import('@tauri-apps/plugin-dialog');
    const chosen = await mod.open({
      title: options.title ?? 'Select folder',
      multiple: false,
      directory: true,
      defaultPath: options.defaultPath,
    });
    if (Array.isArray(chosen)) return chosen[0] ?? null;
    if (typeof chosen === 'string' && chosen.length > 0) return chosen;
    return null;
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) };
  }
}
