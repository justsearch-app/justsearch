// SPDX-License-Identifier: Apache-2.0
/**
 * Chronological order of ISO-8601 instants as the Engine and this window write them.
 *
 * Java's `Instant.toString()` drops a zero fraction and keeps up to nine digits ("…00Z",
 * "…00.1Z", "…00.123456Z"), while `Date.toISOString()` always writes three ("…00.100Z"). Their
 * lexical order is not their time order ("…00.100Z" sorts before "…00Z"), so callers compare the
 * parsed instant: whole milliseconds from `Date.parse`, then the digits finer than a millisecond.
 */

/** Milliseconds since the epoch plus the nanoseconds within that millisecond; null when unparseable. */
function instantParts(iso: string): readonly [number, number] | null {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return null;
  const fraction = /T\d{2}:\d{2}:\d{2}\.(\d+)/.exec(iso)?.[1] ?? '';
  return [ms, Number(fraction.slice(3, 9).padEnd(6, '0'))];
}

/** True when `iso` is a timestamp this module can order. */
export function isInstant(iso: unknown): iso is string {
  return typeof iso === 'string' && instantParts(iso) !== null;
}

/**
 * Negative when `a` is earlier than `b`, positive when later, 0 when the same instant. An
 * unparseable value is earlier than every parseable one (two unparseable values compare equal).
 */
export function compareInstants(a: string, b: string): number {
  const pa = instantParts(a);
  const pb = instantParts(b);
  if (!pa || !pb) return pa ? 1 : pb ? -1 : 0;
  return pa[0] !== pb[0] ? pa[0] - pb[0] : pa[1] - pb[1];
}
