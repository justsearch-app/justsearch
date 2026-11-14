import { describe, expect, it } from 'vitest';
import {
  statusToTone,
  outcomeToTone,
  toneAccent,
  statusAccent,
  presentedToolStatus,
} from './statusTone.js';

describe('statusTone — the one status → tone → accent authority (565 §3.B)', () => {
  it('maps lifecycle status words to semantic tones (case-insensitive)', () => {
    expect(statusToTone('completed')).toBe('success');
    expect(statusToTone('approved')).toBe('success');
    expect(statusToTone('accepted')).toBe('success');
    expect(statusToTone('OK')).toBe('success');
    expect(statusToTone('rejected')).toBe('error');
    expect(statusToTone('failed')).toBe('error');
    expect(statusToTone('pending')).toBe('warning');
    expect(statusToTone('proposed')).toBe('warning');
    expect(statusToTone('warn')).toBe('warning');
    expect(statusToTone('executing')).toBe('info');
    expect(statusToTone('running')).toBe('info');
    expect(statusToTone('info')).toBe('info');
  });

  // Tempdoc 612 §UX/§CI R3 — trust-disposition coverage so a DENIED gate / REVOKED grant reads as
  // notable, not neutral (the curated Activity feed's "importance" emphasis, via the one tone authority).
  it('tones trust dispositions: denied=error, gated/revoked=warning', () => {
    expect(statusToTone('denied')).toBe('error');
    expect(statusToTone('DENIED')).toBe('error');
    expect(statusToTone('gated')).toBe('warning');
    expect(statusToTone('revoked')).toBe('warning');
    // approved stays success; routine grant lifecycle stays neutral (not over-emphasized).
    expect(statusToTone('approved')).toBe('success');
    expect(statusToTone('issued')).toBe('neutral');
    expect(statusToTone('consumed')).toBe('neutral');
  });

  it('maps unknown / empty / nullish status to neutral', () => {
    expect(statusToTone('whatever')).toBe('neutral');
    expect(statusToTone('')).toBe('neutral');
    expect(statusToTone(undefined)).toBe('neutral');
    expect(statusToTone(null)).toBe('neutral');
  });

  it('maps a boolean outcome to a tone', () => {
    expect(outcomeToTone(true)).toBe('success');
    expect(outcomeToTone(false)).toBe('error');
    expect(outcomeToTone(undefined)).toBe('neutral');
  });

  it('maps each tone to exactly one accent token (no bare colour literals)', () => {
    expect(toneAccent('success')).toBe('var(--accent-success)');
    expect(toneAccent('warning')).toBe('var(--accent-warning)');
    expect(toneAccent('error')).toBe('var(--accent-danger)');
    expect(toneAccent('info')).toBe('var(--text-secondary)');
    expect(toneAccent('neutral')).toBe('var(--text-secondary)');
    // Every returned value is a token reference, never a hex/rgb literal.
    for (const tone of ['success', 'warning', 'error', 'info', 'neutral'] as const) {
      expect(toneAccent(tone)).toMatch(/^var\(--[a-z-]+\)$/);
    }
  });

  it('statusAccent composes status → tone → accent', () => {
    expect(statusAccent('rejected')).toBe('var(--accent-danger)');
    expect(statusAccent('completed')).toBe('var(--accent-success)');
    expect(statusAccent('pending')).toBe('var(--accent-warning)');
  });
});

describe('presentedToolStatus — the 577 Ext I outcome-aware presented status', () => {
  it('presents a terminal completed-with-failure as failed', () => {
    expect(presentedToolStatus('completed', false)).toBe('failed');
    expect(statusToTone(presentedToolStatus('completed', false))).toBe('error');
    expect(statusAccent(presentedToolStatus('completed', false))).toBe('var(--accent-danger)');
  });

  it('passes through a successful or unknown-outcome terminal status', () => {
    expect(presentedToolStatus('completed', true)).toBe('completed');
    expect(presentedToolStatus('completed', undefined)).toBe('completed');
    expect(presentedToolStatus('completed', null)).toBe('completed');
  });

  it('never rewrites non-terminal statuses, even with success=false on the record', () => {
    expect(presentedToolStatus('executing', false)).toBe('executing');
    expect(presentedToolStatus('pending', false)).toBe('pending');
    expect(presentedToolStatus('rejected', false)).toBe('rejected');
  });

  it('normalizes case and nullish like the tone authority', () => {
    expect(presentedToolStatus('COMPLETED', false)).toBe('failed');
    expect(presentedToolStatus(undefined, false)).toBe('');
  });
});
