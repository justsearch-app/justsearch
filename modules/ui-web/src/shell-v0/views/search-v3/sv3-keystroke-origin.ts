// SPDX-License-Identifier: Apache-2.0
/**
 * Where an Escape comes from: the window's capture-phase key listener yields to the most local
 * transient that closes itself, and these ask which one, if any, the keystroke came out of.
 */

/**
 * Is the keystroke coming out of a session row that is BEING RENAMED? The row is asked, not the
 * DOM shape: `renaming` is the row's own public state, so the test cannot drift from the markup the
 * edit happens to use. Open shadow roots put the real origin in the composed path, so a capture-phase listener on the host can see
 * an editor three shadow roots down.
 */
export const renamingRowInPath = (event: KeyboardEvent): boolean =>
  event.composedPath().some((node) => {
    const el = node as Element & { renaming?: unknown };
    return el.localName === 'jf-sv3-session-row' && el.renaming === true;
  });

/**
 * Is the keystroke coming out of a composer whose control MENU is open (tempdoc 822 Phase F10)? The
 * same question as the rename above, asked of the same kind of state, and answered the same way:
 * the element is asked for its own public flag rather than the DOM being pattern-matched.
 */
export const openControlMenuInPath = (event: KeyboardEvent): boolean =>
  event.composedPath().some((node) => {
    const el = node as Element & { effortMenuOpen?: unknown; tierMenuOpen?: unknown };
    if (el.localName !== 'jf-sv3-composer') return false;
    // EITHER control menu (852 S4 added the second). The ladder's rung is "a menu is open in the
    // control row", not "the effort menu is open" — a mode menu that Escape closed by falling
    // through to the window would take the composer back to hero with it.
    return el.effortMenuOpen === true || el.tierMenuOpen === true;
  });

/** Is the keystroke coming out of a result list whose Space preview is open? It closes itself first. */
export const previewOpenInPath = (event: KeyboardEvent): boolean =>
  event.composedPath().some((node) => {
    const el = node as Element & { previewOpen?: unknown };
    return el.localName === 'jf-sv3-results' && el.previewOpen === true;
  });
