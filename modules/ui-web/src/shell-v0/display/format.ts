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

/**
 * Some webviews (WebKitGTK under a POSIX locale) format numbers without digit groups, so 50000
 * would read "50000". Group explicitly when the runtime's default locale does not.
 */
const LOCAL_NUM = new Intl.NumberFormat();
const NUM = /\D/.test(LOCAL_NUM.format(10_000)) ? LOCAL_NUM : new Intl.NumberFormat('en-US');

/**
 * The one way to write a file or download size (first minutes, PE1): steps of 1024, units B, KB, MB, GB
 * and TB after a space, at most three figures, a decimal only below 10, no trailing zeros ("157 B",
 * "12 KB", "341 MB", "1.2 GB", "1 GB"). A value that would round to 1000 is written in the next unit.
 */
const STEP = 1024;
const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

function unitFor(n: number): number {
  let unit = 0;
  while (unit < UNITS.length - 1 && n / STEP ** unit >= 999.5) unit++;
  return unit;
}

function figures(n: number, unit: number): string {
  const value = n / STEP ** unit;
  return String(unit === 0 || value >= 9.95 ? Math.round(value) : Math.round(value * 10) / 10);
}

/** Human-readable byte size. Zero/absent → `'0 B'` (the chosen single canonical zero-case). */
export function formatBytes(n: number | undefined | null): string {
  if (!n || n <= 0) return '0 B';
  const unit = unitFor(n);
  return `${figures(n, unit)} ${UNITS[unit]}`;
}

/** "1.3 of 2.6 GB", "120 of 956 MB": a part of a whole, both in the unit of the whole. */
export function formatBytesOf(done: number, total: number): string {
  const whole = Math.max(0, total);
  const unit = unitFor(whole);
  return `${figures(Math.max(0, Math.min(done, whole)), unit)} of ${figures(whole, unit)} ${UNITS[unit]}`;
}

/** Locale-grouped integer (e.g. `12,340`). */
export function formatCount(n: number): string {
  return NUM.format(n);
}

/** Disk inventory count. Older Engines omit the cap flag at their 50,000-file limit. */
export function formatFileCount(count: number | null | undefined, capped?: boolean): string {
  if (count == null || !Number.isFinite(count) || count < 0) return '';
  const cap = capped === true || capped === undefined && count === 50_000;
  return `${formatCount(count)}${cap ? '+' : ''} ${count === 1 && !cap ? 'file' : 'files'}`;
}

/** Local numeric date and time, including seconds (Date#toLocaleString's default fields). */
export function formatDateTime(date: Date): string {
  if (Number.isNaN(date.getTime())) return 'Invalid Date';
  return new Intl.DateTimeFormat(undefined, {
    year: 'numeric', month: 'numeric', day: 'numeric',
    hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).format(date);
}

/** Local clock time including seconds (Date#toLocaleTimeString's default fields). */
export function formatTime(date: Date): string {
  if (Number.isNaN(date.getTime())) return 'Invalid Date';
  return new Intl.DateTimeFormat(undefined, {
    hour: 'numeric', minute: 'numeric', second: 'numeric',
  }).format(date);
}

/** Local clock time with two-digit hours and minutes. */
export function formatTimeMinutes(date: Date): string {
  if (Number.isNaN(date.getTime())) return 'Invalid Date';
  return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(date);
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

/** Compact file date: day and local month, retaining the year outside this year. */
export function formatShortDate(date: Date): string {
  if (Number.isNaN(date.getTime())) return 'Invalid Date';
  const parts = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short',
    ...(date.getFullYear() === new Date().getFullYear() ? {} : { year: 'numeric' }),
  }).formatToParts(date);
  return ['day', 'month', 'year'].map(type => parts.find(part => part.type === type)?.value).filter(Boolean).join(' ');
}
