// SPDX-License-Identifier: Apache-2.0
import { css, html, type TemplateResult } from 'lit';
import { icon } from './Icon.js';

/**
 * fm-07 — a `<details>` disclosure drawn with the app's own mark: the folder row's chevron, turned when
 * open, in place of the browser's triangle. Put `class="disclosure"` on the `<summary>` and
 * {@link disclosureMark} first inside it.
 */
export const disclosureStyles = css`
  summary.disclosure { display: inline-flex; align-items: center; gap: .35rem; list-style: none; cursor: pointer; }
  summary.disclosure::-webkit-details-marker { display: none; }
  summary.disclosure .disclosure-mark { display: inline-flex; flex: none; color: var(--text-secondary); }
  details[open] > summary.disclosure .disclosure-mark { transform: rotate(90deg); }
  summary.disclosure:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }
  @media (forced-colors: active) {
    summary.disclosure .disclosure-mark { color: CanvasText; }
    summary.disclosure:focus-visible { outline-color: Highlight; }
  }
`;

export function disclosureMark(): TemplateResult {
  return html`<span class="disclosure-mark" aria-hidden="true">${icon({ name: 'chevron-right', size: 14 })}</span>`;
}
