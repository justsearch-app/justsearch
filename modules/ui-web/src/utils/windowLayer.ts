// SPDX-License-Identifier: Apache-2.0
/** The desktop window layer from index.html (minimize, maximize, close beside #root) is present. */
export function hasWindowLayer(): boolean {
  return typeof document !== 'undefined' && document.documentElement.hasAttribute('data-window-controls');
}
