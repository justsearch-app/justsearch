// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511-followup-A — `<jf-health-event>` aggregate component.
 *
 * The sanctioned consumption point for rendering a wire HealthEvent.
 * Mirrors JfOperation / JfResource shape with one key difference:
 * HealthEvents are stream-fed (SSE), not catalog-fetched. The
 * component takes an `event` property (inline data), not a
 * `health-event-id` attribute.
 *
 * Usage:
 *   <jf-health-event
 *     context="activity-row"
 *     .event=${event}
 *   ></jf-health-event>
 *
 * Light DOM so surrounding surface CSS cascades (HealthSurface
 * declares `.event-row`, `.event-message`, `.event-time` styles
 * that the strategy emits).
 */

import { type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import {
  dispatchAggregateStrategy,
  type StrategyHost,
} from '../aggregateRegistry.js';
import type { SurfaceContextKind } from '../surfaceContextKinds.js';
import type { HealthEvent } from '../../../api/generated/index.js';
// Tempdoc 526 §17 T1C — health-condition click-to-select gesture.
import {
  setSingleSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
} from '../../state/selectionState.js';
import { setMenuAnchor } from '../../utils/selectionAnchor.js';

export class JfHealthEvent extends JfElement {
  static override properties = {
    event: { attribute: false },
    context: { type: String },
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare event: HealthEvent | null;
  declare context: SurfaceContextKind;
  declare apiBase: string;

  constructor() {
    super();
    this.event = null;
    this.context = 'activity-row';
    this.apiBase = '';
  }

  protected override createRenderRoot(): HTMLElement {
    return this;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 526 §17 T1C — light-DOM bubbled click handler. Picking a
    // condition card publishes a typed `health-condition` SelectionItem
    // and the anchor rect; the F9 menu then floats above the card with
    // the registered explain action.
    this.addEventListener('click', this.handleConditionClick);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.removeEventListener('click', this.handleConditionClick);
  }

  private handleConditionClick = (e: MouseEvent): void => {
    if (!this.event) return;
    // Skip clicks on action buttons (recovery operations like recover-component)
    // — those have their own handlers and should not double as selection.
    const target = e.target as HTMLElement | null;
    if (target && target.closest('button')) return;
    const rect = this.getBoundingClientRect();
    if (rect.width > 0 || rect.height > 0) {
      setMenuAnchor({ top: rect.top, left: rect.left, bottom: rect.bottom, right: rect.right });
    }
    const ev = this.event as unknown as {
      readonly id?: string;
      readonly severity?: string;
      readonly message?: string;
      readonly summary?: string;
    };
    setSingleSelection(
      {
        kind: 'health-condition',
        conditionId: ev.id ?? '',
        severity: ev.severity,
        summary: ev.summary ?? ev.message,
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['health-condition'],
      },
      'core.health-surface',
    );
  };

  override render(): TemplateResult | typeof nothing {
    if (!this.event) return nothing;
    const strategy = dispatchAggregateStrategy('HealthEvent', this.context);
    if (!strategy) return nothing;
    const host: StrategyHost = { apiBase: this.apiBase };
    const ctx = {} as Record<string, unknown>;
    const result = strategy(this.event, ctx as never, host);
    return (result ?? nothing) as TemplateResult | typeof nothing;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-health-event')
) {
  customElements.define('jf-health-event', JfHealthEvent);
}
