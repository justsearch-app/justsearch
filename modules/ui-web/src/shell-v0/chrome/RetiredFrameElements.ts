// SPDX-License-Identifier: Apache-2.0
/**
 * Frozen presentation vocabulary (shell slice 2). The left rail and the title bar's recents menu
 * and bookmarks list retired with the one top bar; a user Presentation that names one of them
 * still validates and mounts, and renders nothing (the RetiredStatusElements pattern). The
 * bookmark and navigation-journal data they read stay stored. The frame's side drawers retired the
 * same way when every window got its details pane.
 */
if (!customElements.get('jf-rail')) customElements.define('jf-rail', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
if (!customElements.get('jf-recents-menu')) customElements.define('jf-recents-menu', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
if (!customElements.get('jf-bookmarks-popover')) customElements.define('jf-bookmarks-popover', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
if (!customElements.get('jf-sources-pane')) customElements.define('jf-sources-pane', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
if (!customElements.get('jf-interaction-retrospective-panel')) customElements.define('jf-interaction-retrospective-panel', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
if (!customElements.get('jf-failed-jobs-drawer')) customElements.define('jf-failed-jobs-drawer', class extends HTMLElement {
  connectedCallback(): void { this.hidden = true; }
});
