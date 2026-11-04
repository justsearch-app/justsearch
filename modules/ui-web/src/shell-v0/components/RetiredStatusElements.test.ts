// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest';
import './RetiredStatusElements.js';
import { isComponentTag } from '../renderers/component-vocabulary.generated.js';
import { isAuthorableComponent } from '../themes/authorableComponents.js';
afterEach(() => document.body.replaceChildren());
it.each(['jf-status-deck', 'jf-task-list', 'jf-pending-effect-queue', 'jf-agent-activity-panel',
  'jf-ai-activity-digest', 'jf-app-update-banner', 'jf-plugin-error-overlay', 'jf-indexing-overlay',
  'jf-indexing-overlay-host', 'jf-advisory-toast-host', 'jf-advisory-inbox-drawer',
  'jf-advisory-rail-badge', 'jf-resolution-toast'])('ST10 frozen %s is defined and inert without transports or listeners', tag => {
  const element = document.createElement(tag);
  const documentListener = vi.spyOn(document, 'addEventListener');
  const windowListener = vi.spyOn(window, 'addEventListener');
  try {
    document.body.append(element);
    expect(element.hidden).toBe(true); expect(element.shadowRoot).toBeNull(); expect(element.textContent).toBe('');
    expect(documentListener).not.toHaveBeenCalled(); expect(windowListener).not.toHaveBeenCalled();
    expect(isComponentTag(tag)).toBe(true);
  } finally { documentListener.mockRestore(); windowListener.mockRestore(); }
});
it('the frame decision entry and read view remain reserved from authored presentations', () => {
  expect(isAuthorableComponent('jf-status-pill')).toBe(false); expect(isAuthorableComponent('jf-status-panel')).toBe(false);
});
