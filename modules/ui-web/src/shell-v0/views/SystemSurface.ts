// SPDX-License-Identifier: Apache-2.0
/**
 * SystemSurface (`jf-system-surface`) — tempdoc 571 §11 / 578: the System hub.
 *
 * A pure-container host: it has no own body, it just presents its declared member surfaces
 * (Health · Logs · Activity) as tabs via the one `<jf-surface-tabs>` composition primitive. The
 * members are read from the live catalog (the wire's `members`), so the hub's contents are a pure
 * catalog declaration — adding/removing a member is a `CoreSurfaceCatalog` edit, no FE change here.
 *
 * Cross-altitude (578 §9.4.B): the members span DIAGNOSTIC (Health, Logs) and TRUST (Activity).
 * Altitude derivation stays per-surface (members are never folded into the host's), so there is no
 * altitude conflict; `<jf-surface-tabs>` preserves each member's altitude framing (the TRUST Activity
 * tab keeps its "Audit" marker) so the trust distinction survives inside the composite.
 *
 * Layout: a `display: contents` pass-through (the layout-purity-approved host pattern) — the tabs and
 * the mounted member own the layout.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem, SurfaceTabs } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { present } from '../display/present.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

const SYSTEM_SURFACE_ID = 'core.system-surface';

export class SystemSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    activeTab: { state: true },
    // Set by the factory when the Shell mounts this surface; threaded to <jf-surface-tabs> so member
    // tabs (Health) mount WITH host_ — without it, Health's this.host_.data.fetch throws (578 fix).
    host_: { attribute: false },
  };

  declare apiBase: string;
  /** Active member-tab id; defaults to the first declared member. */
  declare activeTab: string;
  declare host_: PluginHostApi | undefined;

  constructor() {
    super();
    this.apiBase = '';
    this.activeTab = '';
    this.host_ = undefined;
  }

  static styles = css`
    :host {
      display: contents;
    }
  `;

  private memberTabUnsub: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    // If reached via a member deep-link (e.g. core.activity-surface → redirected here), open that tab.
    // Drain a pending intent (mounting now) AND subscribe (member deep-link while already active).
    const requested = takeMemberTabIntent(SYSTEM_SURFACE_ID);
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== SYSTEM_SURFACE_ID) return false;
      this.activeTab = memberId;
      return true;
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
  }

  /** Escape with the keyboard on the page: the member on show closes its details (Activity's). */
  closeDetails(): boolean {
    return this.renderRoot.querySelector<SurfaceTabs>('jf-surface-tabs')?.closeDetails() ?? false;
  }

  override render(): TemplateResult {
    const members = getSurface(SYSTEM_SURFACE_ID)?.members ?? [];
    if (members.length === 0) return html`${nothing}`;
    const items: SurfaceTabItem[] = members.map((mid) => ({
      id: mid,
      label: present({ kind: 'surface', id: mid }).label,
      altitude: getSurface(mid)?.altitude,
      surfaceId: mid,
    }));
    return html`
      <jf-surface-tabs
        tablist-label="System views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      ></jf-surface-tabs>
    `;
  }
}

customElements.define('jf-system-surface', SystemSurface);
