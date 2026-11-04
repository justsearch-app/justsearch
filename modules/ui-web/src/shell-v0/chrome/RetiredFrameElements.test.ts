// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Shell slice 2 — the rail and the title bar's recents menu and bookmarks list retired; a user
 * presentation that names one still validates and mounts, and the element renders nothing.
 */
import { afterEach, expect, it, vi } from 'vitest';
import './RetiredFrameElements.js';
import { isComponentTag } from '../renderers/component-vocabulary.generated.js';
import { isAuthorableComponent } from '../themes/authorableComponents.js';
afterEach(() => document.body.replaceChildren());
it.each(['jf-rail', 'jf-recents-menu', 'jf-bookmarks-popover'])('frozen %s is defined, in the vocabulary and inert without listeners', tag => {
  const element = document.createElement(tag);
  const documentListener = vi.spyOn(document, 'addEventListener');
  const windowListener = vi.spyOn(window, 'addEventListener');
  try {
    document.body.append(element);
    expect(customElements.get(tag)).toBeDefined();
    expect(element.hidden).toBe(true); expect(element.shadowRoot).toBeNull(); expect(element.textContent).toBe('');
    expect(documentListener).not.toHaveBeenCalled(); expect(windowListener).not.toHaveBeenCalled();
    expect(isComponentTag(tag)).toBe(true);
    expect(isAuthorableComponent(tag)).toBe(true);
  } finally { documentListener.mockRestore(); windowListener.mockRestore(); }
});
it('the top bar is reserved from authored presentations, like the shell and the status pill', () => {
  expect(isAuthorableComponent('jf-top-bar')).toBe(false);
  expect(isComponentTag('jf-top-bar')).toBe(true);
});
