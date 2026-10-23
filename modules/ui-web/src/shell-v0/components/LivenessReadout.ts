// SPDX-License-Identifier: Apache-2.0
/**
 * LivenessReadout — tempdoc 569 §14 (Move 3, the liveness facet made co-projected).
 *
 * A declared surface region opts into a live observed-state readout by naming a signal
 * (`liveness: <metricId>`); the ENGINE derives the actual state — the author has NO field
 * for it, so a skin can never paint a fake "healthy/live" indicator (rung-2 unrepresentable,
 * the liveness analogue of the 558 contrast co-projection).
 *
 * The state is projected from the ONE observed-state authority the FE runs — `aiStateStore`
 * (the same authority StatusDeck reads) — not a second poll. The declared ref names the signal
 * (its accessible label projects through `present({kind:'metric'})`, the one display authority);
 * richer per-observed-happening reads are tempdoc 575's data tier. The tone dot is a SEMANTIC
 * token (theme-managed contrast), never an author-chosen colour.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { present } from '../display/present.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { isIndexingObservation } from '../state/readinessNotice.js';

/** The observed tones the engine derives from the authority (never authored). */
type LivenessTone = 'live' | 'info' | 'degraded' | 'error' | 'idle';

function toneFor(state: AiState | null): LivenessTone {
  // Tempdoc 649 — derive the dot tone from the ONE verdict-derived `statusTone` (not `statusTier`), so
  // the calm "Catching up…" state (`info`) shows a calm tint dot instead of an amber `degraded` dot,
  // matching the Health badge and the status-bar pill. The tone IS the real observed state, never an
  // author choice. (`error` now shows a danger dot — previously unreachable fell through to grey `idle`.)
  switch (state?.statusTone ?? 'neutral') {
    case 'success':
      return 'live';
    case 'info':
      return 'info';
    case 'warning':
      return 'degraded';
    case 'error':
      return 'error';
    default:
      return 'idle'; // neutral — offline / unknown
  }
}

export class LivenessReadout extends JfElement {
  static override properties = {
    metricId: { type: String, attribute: 'metric-id' },
    aiState: { state: true },
  } as const;

  declare metricId: string;
  declare aiState: AiState | null;
  private unsub: (() => void) | null = null;

  static override styles = css`
    :host {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .dot {
      inline-size: 0.5rem;
      block-size: 0.5rem;
      border-radius: 50%;
      flex: none;
      /* The tone is a SEMANTIC token (theme-managed), never an author colour. */
      background: var(--text-secondary);
    }
    .dot[data-tone='live'] {
      background: var(--accent-success);
    }
    .dot[data-tone='degraded'] {
      background: var(--accent-warning);
    }
    .dot[data-tone='info'] {
      /* Tempdoc 649 — the calm in-flux tone ("Catching up…"); reuses the existing tint token. */
      background: var(--accent-tint);
    }
    .dot[data-tone='error'] {
      background: var(--accent-danger);
    }
    .dot[data-tone='idle'] {
      background: var(--text-secondary);
      opacity: 0.5;
    }
    .name {
      font-weight: 600;
      color: var(--text-primary);
    }
  `;

  constructor() {
    super();
    this.metricId = '';
    this.aiState = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Project from the ONE observed-state authority — fires immediately with the current value.
    this.unsub = subscribeAiState((s) => {
      this.aiState = s;
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
  }

  override render(): TemplateResult {
    const state = this.aiState;
    const indexingTransition = state?.verdict?.kind === 'transitioning' && state.verdict.reasons.some(isIndexingObservation);
    const activity = state && (state.statusLabel === 'Indexing' || indexingTransition)
      ? selectIndexingProgress(state.status, state.indexingStatusLive, state.episodeMaxPendingJobs, state.enrichSettleSamples).activity
      : null;
    const tone = activity && !activity.confirmed ? 'idle' : toneFor(state);
    // The accessible name projects through the one display authority (no inline string).
    const name = this.metricId ? present({ kind: 'metric', id: this.metricId }).label : '';
    const liveText = activity && (!indexingTransition || !activity.confirmed) ? [activity.summary, activity.detail].filter(Boolean).join(' — ') : state?.statusLabel ?? 'unknown';
    return html`
      <span
        class="dot"
        data-tone=${tone}
        role="img"
        aria-label=${`${String(name)}: ${liveText}`}
      ></span>
      ${name ? html`<span class="name">${name}</span>` : nothing}
      <span class="state" data-testid="liveness-state">${liveText}</span>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-liveness-readout')
) {
  customElements.define('jf-liveness-readout', LivenessReadout);
}
