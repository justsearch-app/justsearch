// SPDX-License-Identifier: Apache-2.0
/**
 * statusTone — Tempdoc 565 §3.B: THE single status → semantic-tone → accent-token authority.
 *
 * Before this, lifecycle status was coloured three divergent ways: the retrospective drawer's
 * History by `success` (inline `var(--accent-danger/success)`), its Timeline by `originator`
 * (purple/teal), and the inline `ToolCallCard` left the status word uncoloured (only the risk
 * border was tinted). A "rejected" call and a "completed" call read identically. This module makes
 * one mapping the authority every status render routes through, so a status reads semantically
 * (green ok / red failed / amber pending / blue running) everywhere by construction.
 *
 * The tone *vocabulary* stays single-sourced in {@link SystemNotice} (`NoticeTone`); this module
 * owns (a) the lifecycle-status → tone map and (b) the tone → accent-token map for STATUS chrome.
 * The non-neutral colours are identical to the notice authority's; `neutral` legitimately differs by
 * context (notices use a subtle border, status text uses the secondary text token). The status-tone
 * gate (565 §3.C / Phase 3) forbids ad-hoc inline status colours outside this module, so the literal
 * `var(--accent-*)` tokens live here and nowhere else for status.
 */
import type { NoticeTone } from '../components/SystemNotice.js';

export type { NoticeTone };

/**
 * Map any lifecycle status word — a {@link ToolCallStatus}, a History `ok`/`failed`, a progress
 * `severity` (`info`/`warn`/`error`), or a boolean rendered as `success`/`failed` — to its tone.
 * Unknown/absent ⇒ `neutral`.
 */
export function statusToTone(status: string | undefined | null): NoticeTone {
  switch ((status ?? '').toLowerCase()) {
    case 'completed':
    case 'approved':
    case 'accepted':
    case 'ok':
    case 'success':
      return 'success';
    case 'rejected':
    case 'failed':
    case 'failure':
    case 'error':
    // Tempdoc 612 §UX/§CI R3 — trust-disposition coverage: a DENIED gate is a refusal; it must read as
    // notable, not neutral (the §CI legibility gap — a denial read identically to a success before this).
    case 'denied':
      return 'error';
    case 'pending':
    case 'proposed':
    case 'awaiting':
    case 'warn':
    case 'warning':
    // Tempdoc 612 §UX/§CI R3 — a GATED firing (awaiting confirmation) and a REVOKED grant are notable
    // trust events; tone them as caution so they stand out from routine success in the curated feed.
    case 'gated':
    case 'revoked':
      return 'warning';
    case 'executing':
    case 'running':
    case 'info':
      return 'info';
    default:
      return 'neutral';
  }
}

/** Boolean terminal outcome → tone (History rows carry `success?: boolean`). */
export function outcomeToTone(success: boolean | undefined | null): NoticeTone {
  if (success === true) return 'success';
  if (success === false) return 'error';
  return 'neutral';
}

/**
 * Tempdoc 577 Goal 2 Ext I — THE outcome-aware presented status for a tool call. The lifecycle axis
 * alone conflates "finished" with "succeeded": a terminal `completed` whose result carried
 * `success === false` must READ as failed everywhere (card status word, spine glyph, run summary).
 * Every terminal tool-status render routes through this seam, so the failed-renders-as-✓ class
 * (577 §2.9 V2) cannot recur per render site. Non-terminal and unknown-outcome statuses pass through.
 */
export function presentedToolStatus(
  status: string | undefined | null,
  success?: boolean | null,
): string {
  const s = (status ?? '').toLowerCase();
  if (s === 'completed' && success === false) return 'failed';
  return s;
}

/**
 * The ONE tone → accent-token map for status chrome. Returns a CSS token reference (never a bare
 * colour literal), so consumers bind the CSS through Lit's `.style` property. The literal
 * token names never appear at the call site (the status-tone gate's invariant).
 */
export function toneAccent(tone: NoticeTone): string {
  switch (tone) {
    case 'success':
      return 'var(--accent-success)';
    case 'warning':
      return 'var(--accent-warning)';
    case 'error':
      return 'var(--accent-danger)';
    case 'info':
      return 'var(--accent-tint)';
    case 'neutral':
    default:
      return 'var(--text-secondary)';
  }
}

/** Convenience: status word → its accent token in one call. */
export function statusAccent(status: string | undefined | null): string {
  return toneAccent(statusToTone(status));
}

/**
 * Tempdoc 558 Deepening 3 — the tone → TEXT-grade token map: the legible-as-body-text sibling of
 * {@link toneAccent}. Accent tokens are FILLS (the contrast matrix validates text ON the fill, not the
 * fill used AS text on the app surface), so a status/outcome rendered as a coloured WORD on the surface
 * (e.g. the activity row's outcome cell) must use the `--text-<role>` grade `check-contrast-matrix`
 * validates for text-on-surface — never `toneAccent` (which would be accent-as-text). Lives here so the
 * `--text-*` status literals stay single-sourced in this authority, like `toneAccent`.
 */
export function toneText(tone: NoticeTone): string {
  switch (tone) {
    case 'success':
      return 'var(--text-success)';
    case 'warning':
      return 'var(--text-warning)';
    case 'error':
      return 'var(--text-danger)';
    case 'info':
      return 'var(--text-tint)';
    case 'neutral':
    default:
      return 'var(--text-secondary)';
  }
}

/**
 * The tone → SOFT (tinted-background) accent token — the alpha-graded sibling of {@link toneAccent},
 * for chrome that needs a tinted fill behind solid-tone text (e.g. a status badge: `-16` alpha bg +
 * solid text). Single-sourced here so a badge never hand-picks `var(--accent-success-16)`. The 574
 * atom tier (`jf-status-badge`) reads this; neutral falls back to the subtle surface token.
 */
export function toneAccentSoft(tone: NoticeTone): string {
  switch (tone) {
    case 'success':
      return 'var(--accent-success-16)';
    case 'warning':
      return 'var(--accent-warning-16)';
    case 'error':
      return 'var(--accent-danger-16)';
    case 'info':
      return 'var(--accent-tint-16)';
    case 'neutral':
    default:
      return 'var(--surface-2)';
  }
}

/**
 * Tempdoc 565 §17 — the semantic glyph vocabulary for a run step. A glyph TOKEN (not a raw char), so
 * the run-node primitive maps it to a themeable icon; `none` renders as a plain dot.
 */
export type StepGlyph = 'none' | 'pending' | 'running' | 'done' | 'denied' | 'error' | 'warn';

/**
 * Tempdoc 565 §17 — THE status → semantic GLYPH map, the SIBLING of {@link statusToTone}. Glyph and
 * tone are two facets of the ONE status authority; a step's icon, like its colour, is projected here
 * once, never hand-rolled per render site (the §17 run-step presentation leaf the spine/trace/card
 * each forked).
 *
 * SPARSE BY INTENT, aligned with the backend AgentProgress severity model (561 #5 / `AgentEvent.java`:
 * routine INFO phases carry NO decorative glyph — only decisive states do). A running step is
 * `running` (rendered alive); terminal outcomes carry their mark (`done`/`denied`/`error`/`warn`); a
 * gate is `pending`; routine/info/unknown is `none` (a plain dot — no over-decoration). Note `rejected`
 * (a denied tool) ⇒ `denied` while `error`/`failed` (a run failure) ⇒ `error` — distinct glyphs the
 * pre-§17 spine conflated because both share the red tone.
 */
export function statusGlyph(status: string | undefined | null): StepGlyph {
  switch ((status ?? '').toLowerCase()) {
    case 'completed':
    case 'approved':
    case 'accepted':
    case 'ok':
    case 'success':
      return 'done';
    case 'rejected':
      return 'denied';
    case 'failed':
    case 'failure':
    case 'error':
      return 'error';
    case 'warn':
    case 'warning':
      return 'warn';
    case 'executing':
    case 'running':
      return 'running';
    case 'pending':
    case 'proposed':
    case 'awaiting':
      return 'pending';
    case 'info':
    default:
      return 'none';
  }
}

/**
 * Tempdoc 565 §17 — THE glyph-token → display char map (the ONE place the status glyph CHARS live). The
 * run-node primitive and any string-icon consumer (e.g. the advisory inbox) render a status glyph
 * through this, so a status icon is never hand-rolled as a `status === 'ok' ? '✓' : '✕'` ternary.
 * `none`/`pending`/`running` return '' — the primitive draws those as CSS shapes (a dot / ring / pulse).
 */
export function glyphChar(glyph: StepGlyph): string {
  switch (glyph) {
    case 'done':
      return '✓';
    case 'denied':
      return '⊘';
    case 'error':
      return '✕';
    case 'warn':
      return '!';
    case 'none':
    case 'pending':
    case 'running':
    default:
      return '';
  }
}
