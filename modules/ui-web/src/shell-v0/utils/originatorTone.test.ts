import { describe, expect, it } from 'vitest';
import { toOriginator, originatorAccent, originatorAccentSoft } from './originatorTone.js';

describe('originatorTone — the one originator(role) → accent authority (574 §23.B)', () => {
  it('maps originator words to canonical roles (case-insensitive, with aliases)', () => {
    expect(toOriginator('agent')).toBe('agent');
    expect(toOriginator('Assistant')).toBe('agent');
    expect(toOriginator('user')).toBe('user');
    expect(toOriginator('HUMAN')).toBe('user');
    expect(toOriginator('system')).toBe('system');
  });

  it('maps unknown / empty / nullish originator to system (the neutral default)', () => {
    expect(toOriginator('whatever')).toBe('system');
    expect(toOriginator('')).toBe('system');
    expect(toOriginator(undefined)).toBe('system');
    expect(toOriginator(null)).toBe('system');
  });

  it('projects each role to a DISTINCT non-status accent token (agent=purple, user=primary, system=secondary)', () => {
    expect(originatorAccent('agent')).toBe('var(--accent-command)');
    expect(originatorAccent('user')).toBe('var(--text-primary)');
    expect(originatorAccent('system')).toBe('var(--text-secondary)');
    // the three text accents are mutually distinct (the originator-invisibility bug this fixes)
    const accents = new Set([
      originatorAccent('agent'),
      originatorAccent('user'),
      originatorAccent('system'),
    ]);
    expect(accents.size).toBe(3);
  });

  it('projects each role to its soft (tinted-background) sibling token', () => {
    expect(originatorAccentSoft('agent')).toBe('var(--accent-command-16)');
    expect(originatorAccentSoft('user')).toBe('var(--surface-active)');
    expect(originatorAccentSoft('system')).toBe('var(--surface-2)');
  });
});
