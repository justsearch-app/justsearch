// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C0) — generic `<jf-chat-shape-mount>` Lit element.
 *
 * Resolves a shape-id attribute to a typed view via the {@link viewFactoryRegistry}
 * and mounts the resulting custom element into its shadow tree. Used by:
 *
 * - Surfaces hosting a chat shape (per `SurfaceConsumes.conversationShapes`) —
 *   their `mountTag` becomes `'jf-chat-shape-mount'` with `shape-id` attribute
 *   set to the ConversationShapeRef. The chrome's existing Stage/render path
 *   constructs the element + the typed view materializes inside it.
 *
 * - Programmatic mounts (tests, dev probes). Append a freshly-constructed
 *   element with `shape-id` set; the connectedCallback resolves the factory
 *   and mounts.
 *
 * <p>If the shape-id has no registered ViewFactory, renders an inline error
 * placeholder (rather than failing silently) — visible signal for unregistered
 * shapes.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';

import { mountView } from '../../router/view-factory.js';
import { getViewFactory } from '../../router/viewFactoryRegistry.js';
import type { PluginHostApi } from '../../plugin-api/plugin-types.js';

export class ChatShapeMount extends JfElement {
  static properties = {
    shapeId: { attribute: 'shape-id', type: String },
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
  };

  declare shapeId: string;
  declare apiBase: string;
  /**
   * 507/508-merge T2.4 — host API for the wrapped inner view. The
   * outer Stage/Shell passes `host_` to this wrapper; we forward it
   * through {@link mountView} so the inner view (FreeChatView,
   * AgentView, etc.) sees the same PluginHostApi the surface owner
   * was given. Without this, the inner view falls back to direct
   * imports for host-aware code paths (host.ui.scrollSurfaceTo,
   * host.data.fetch, etc.).
   */
  declare host_: PluginHostApi | undefined;

  private mountedElement: HTMLElement | null = null;

  constructor() {
    super();
    this.shapeId = '';
    this.apiBase = '';
    this.host_ = undefined;
  }

  static styles = css`
    :host {
      display: contents;
    }
    .chat-shape-mount-error {
      padding: 0.75rem 1rem;
      background: var(--accent-danger-08);
      color: var(--text-danger);
      border: 1px solid var(--accent-danger-30);
      border-radius: 8px;
      font-family: var(--font-native);
      font-size: var(--font-size-sm);
    }
    .chat-shape-mount-error code {
      font-family: ui-monospace, monospace;
      background: rgba(0, 0, 0, 0.2);
      padding: 0.075rem 0.25rem;
      border-radius: 0.25rem;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.tryMount();
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('shapeId') || changed.has('apiBase') || changed.has('host_')) {
      this.tryMount();
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.mountedElement = null;
  }

  private tryMount(): void {
    if (!this.shapeId) {
      this.mountedElement = null;
      this.requestUpdate();
      return;
    }
    const factory = getViewFactory(this.shapeId);
    if (!factory) {
      this.mountedElement = null;
      this.requestUpdate();
      return;
    }
    // Slice 491 F3: dispatch through mountView so the brand verification
    // (Symbol identity + WeakSet membership) fires at every mount site, not
    // just at registration. Mirrors SurfaceCatalogClient.mountSurface() which
    // calls verifyFactoryBrand before factory.mount(). Without this, a forged
    // ViewFactory (Symbol stolen from a real factory) would mount unverified.
    // A brand-verification throw is treated like the no-factory case: log,
    // null out, render the error placeholder — never crash the chrome.
    try {
      this.mountedElement = mountView(factory, {
        apiBase: this.apiBase,
        ...(this.host_ !== undefined ? { host_: this.host_ } : {}),
      });
      // Tempdoc 561 surface tier: stamp the shape-id on the mounted view so the one interaction
      // window (jf-unified-chat-view), now the view for every shape, can preset its mode from a
      // deeplink. Single-shape specialized views ignore it; harmless.
      this.mountedElement?.setAttribute('shape-id', this.shapeId);
    } catch (err) {
      console.warn(
        `[jf-chat-shape-mount] mountView rejected factory for shape '${this.shapeId}':`,
        err,
      );
      this.mountedElement = null;
    }
    this.requestUpdate();
  }

  override render(): TemplateResult {
    if (!this.shapeId) {
      return html`<div class="chat-shape-mount-error">
        <strong>ChatShapeMount:</strong> no <code>shape-id</code> attribute set.
      </div>`;
    }
    if (!this.mountedElement) {
      return html`<div class="chat-shape-mount-error">
        <strong>ChatShapeMount:</strong> no view factory registered for shape
        <code>${this.shapeId}</code>. Ensure the typed view's module is imported
        (side-effect register) before mount.
      </div>`;
    }
    return html`${this.mountedElement}`;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-chat-shape-mount')
) {
  customElements.define('jf-chat-shape-mount', ChatShapeMount);
}
