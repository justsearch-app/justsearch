// SPDX-License-Identifier: Apache-2.0
/** The document footer every details pane shares (design system §7: "Open file, Show in folder"). */
import { html, type TemplateResult } from 'lit';
import { openLocalFile, revealLocalPath } from '../../plugin-api/capabilities/platform.js';
import { icon } from '../Icon.js';
import { isTauriRuntime } from '../../../utils/tauriRuntime.js';
import { nothing } from 'lit';
import '../Button.js';

export function detailsDocumentActions(path: string): TemplateResult {
  return html`<jf-button variant="ghost" size="sm" label="Open file" data-testid="details-open-file"
      .onActivate=${() => void openLocalFile(path).catch(() => { /* Already reported. */ })}
      >${icon({ name: 'external-link', size: 14 })} Open file</jf-button>
    ${isTauriRuntime() ? html`<jf-button variant="ghost" size="sm" label="Show in folder" data-testid="details-show-in-folder"
      .onActivate=${() => void revealLocalPath(path).catch(() => { /* Already reported. */ })}
      >${icon({ name: 'folder', size: 14 })} Show in folder</jf-button>` : nothing}`;
}
