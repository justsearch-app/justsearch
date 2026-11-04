// SPDX-License-Identifier: Apache-2.0
export interface PluginErrorEventDetail {
  pluginUrl?: string;
  pluginId?: string;
  message: string;
  stack?: string;
}
export function dispatchPluginError(detail: PluginErrorEventDetail): void {
  document.dispatchEvent(new CustomEvent('jf-plugin-load-error', { detail }));
}
export function dispatchPluginLoaded(pluginUrl: string): void {
  document.dispatchEvent(new CustomEvent('jf-plugin-loaded', { detail: { pluginUrl } }));
}
