// SPDX-License-Identifier: Apache-2.0
/**
 * ProvenanceChip — kernel-rendered attribution chip per Tempdoc 543 §3.A.
 *
 * Renders a small badge ("plugin · <id> · v<version>") next to any
 * contribution-rendered chrome whose `provenance.tier !== 'CORE'`.
 * For CORE contributions the chip renders `nothing` (no visual noise
 * around first-party items).
 *
 * Distinct from `shell-v0/components/ProvenanceBadge.ts` — that
 * component renders user-config surface+layout *overrides* (a
 * different concept; name predates this primitive). The 543 §12.2
 * confidence-pass flagged the naming collision; this is the new
 * chrome-rendered component for per-contribution attribution.
 *
 * Consumed by chrome that mounts contributions: status-bar items,
 * inspector tabs, command-palette entries, context-menu items, etc.
 * Plugin-contributed Lit elements should NOT mount this themselves
 * — kernel renders attribution per "plugins request, kernel renders"
 * (543 §4).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { Provenance } from '../primitives/provenance.js';
import { isNonCore, displayTier } from '../primitives/provenance.js';

export class ProvenanceChip extends JfElement {
  static properties = {
    provenance: { attribute: false },
  };

  declare provenance: Provenance | undefined;

  constructor() {
    super();
    this.provenance = undefined;
  }

  static styles = css`
    :host {
      display: inline-flex;
      vertical-align: middle;
      max-inline-size: 100%;
    }
    /* Whole words only: a parent's overflow-wrap (the status panel's) never splits a word here; a row
       narrower than the chip shortens the contributor's name with an ellipsis, and the title keeps it all. */
    .chip {
      white-space: nowrap;
      overflow-wrap: normal;
      min-inline-size: 0;
      max-inline-size: 100%;
      box-sizing: border-box;
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.0625rem 0.4rem;
      border-radius: 9999px;
      font-size: var(--font-size-xs);
      font-weight: 500;
      letter-spacing: 0.02em;
      background: var(--surface-active);
      color: var(--text-secondary);
      border: 1px solid var(--border-strong);
    }
    .chip.untrusted {
      background: var(--accent-warning-16);
      color: var(--text-warning);
      border-color: var(--accent-warning-30);
    }
    .chip.verified {
      /* identity.verified === true — slightly stronger accent */
      background: var(--surface-active);
      border-color: var(--border-strong);
    }
    .chip-verify-mark {
      font-weight: 700;
    }
    .chip > span {
      flex: none;
    }
    .chip > .contributor {
      flex: 0 1 auto;
      min-inline-size: 0;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .chip-label {
      opacity: 0.85;
    }
    .chip-version {
      opacity: 0.6;
    }
  `;

  override render(): TemplateResult | typeof nothing {
    const p = this.provenance;
    if (!p) return nothing;
    if (!isNonCore(p)) return nothing;
    // Tempdoc 543 §13.2.3.1 — derived display from multi-axis signals.
    const display = displayTier(p);
    const verified = display === 'VERIFIED';
    const untrusted = display === 'UNTRUSTED';
    const reviewedAt = p.review?.lastReviewedAt;
    const capCount = p.capability?.length ?? 0;
    const titleParts: string[] = [
      `Contributed by ${p.contributorId}`,
      `tier=${p.tier}`,
    ];
    if (p.version && p.version !== '0') titleParts.push(`v${p.version}`);
    if (p.identity?.verified) titleParts.push('identity-verified');
    if (reviewedAt) titleParts.push(`reviewed ${reviewedAt}`);
    if (capCount > 0) titleParts.push(`${capCount} capabilities`);
    return html`<span
      class="chip ${untrusted ? 'untrusted' : ''} ${verified ? 'verified' : ''}"
      data-display-tier=${display}
      title=${titleParts.join(' · ')}
    >
      <span class="chip-label">${untrusted ? 'untrusted' : 'plugin'}</span>
      ${verified
        ? html`<span class="chip-verify-mark" title="identity-verified" aria-label="verified">✓</span>`
        : nothing}
      <span>·</span>
      <span class="contributor">${p.contributorId}</span>
      ${p.version && p.version !== '0'
        ? html`<span class="chip-version">v${p.version}</span>`
        : nothing}
    </span>`;
  }
}

if (!customElements.get('jf-provenance-chip')) {
  customElements.define('jf-provenance-chip', ProvenanceChip);
}
