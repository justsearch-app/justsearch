// SPDX-License-Identifier: Apache-2.0
/**
 * format — the ONE shared value formatter (tempdoc 594 §17.2).
 *
 * Before 594's value-authority close, three components (StatusDeck, HealthSurface, BrainSurface) each
 * defined their own `formatBytes` and they had already DRIFTED (`'0'` vs `'0 B'` for the zero case) —
 * the representation-drift class this codebase governs against. This module is the single source for
 * byte/number value formatting, consumed by the Fact catalog (facts.ts) and by the display shells
 * that still format contextual values (e.g. Brain's download/VRAM byte values, which are NOT facts).
 */

const NUM = new Intl.NumberFormat();

/** Human-readable byte size. Zero/absent → `'0 B'` (the chosen single canonical zero-case). */
export function formatBytes(n: number | undefined | null): string {
  if (!n || n <= 0) return '0 B';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/** Locale-grouped integer (e.g. `12,340`). */
export function formatCount(n: number): string {
  return NUM.format(n);
}

/** Local deadline time, with a short weekday only when it is not today. */
export function formatDeadline(deadline: Date): string {
  const now = new Date();
  const today = deadline.getFullYear() === now.getFullYear() &&
    deadline.getMonth() === now.getMonth() && deadline.getDate() === now.getDate();
  // SES tames Date#toLocaleString to a raw date string, ignoring its options.
  return new Intl.DateTimeFormat(undefined, {
    ...(today ? {} : { weekday: 'short' }), hour: '2-digit', minute: '2-digit',
  }).format(deadline);
}
