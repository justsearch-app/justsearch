// @vitest-environment node
/**
 * format — the one shared value formatter (594 §17.2). Pins the canonical zero-case (`'0 B'`) that the
 * three previously-divergent `formatBytes` had drifted on (`'0'` vs `'0 B'`).
 */
import { describe, it, expect } from 'vitest';
import { formatBytes, formatBytesOf, formatCount } from './format';

const MiB = 1024 * 1024;
const GiB = 1024 * MiB;

describe('formatBytes', () => {
  it('zero / absent → the single canonical "0 B"', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(null)).toBe('0 B');
    expect(formatBytes(undefined)).toBe('0 B');
  });
  it('scales B / KB / MB / GB / TB in steps of 1024, with a space before the unit', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(53_477_376)).toBe('51 MB');
    expect(formatBytes(8 * GiB)).toBe('8 GB');
    expect(formatBytes(3 * 1024 * GiB)).toBe('3 TB');
  });
  // PE1 — one way to write a size: at most three figures, no trailing zeros. The review's own
  // examples: '2.60 GB' → '2.6 GB', '1.19 GB' → '1.2 GB', '341.4 MB' → '341 MB', '158 bytes' → '158 B'.
  it('writes at most three figures, a decimal only below 10, and no trailing zeros (PE1)', () => {
    expect(formatBytes(157)).toBe('157 B');
    expect(formatBytes(158)).toBe('158 B');
    expect(formatBytes(Math.round(2.6 * GiB))).toBe('2.6 GB');
    expect(formatBytes(Math.round(1.19 * GiB))).toBe('1.2 GB');
    expect(formatBytes(Math.round(341.4 * MiB))).toBe('341 MB');
    expect(formatBytes(12 * 1024)).toBe('12 KB');
    expect(formatBytes(GiB)).toBe('1 GB');
    // A decimal only below 10, as the pictures write sizes ('5.7 GB', '23 MB', '69 KB', '12 GB').
    expect(formatBytes(Math.round(5.7 * GiB))).toBe('5.7 GB');
    expect(formatBytes(Math.round(22.6 * MiB))).toBe('23 MB');
    expect(formatBytes(Math.round(9.96 * MiB))).toBe('10 MB');
    expect(formatBytes(Math.round(10.14 * GiB))).toBe('10 GB');
  });
  it('never writes four figures: a value that would round to 1000 moves to the next unit (PE1)', () => {
    expect(formatBytes(999)).toBe('999 B');
    expect(formatBytes(1000)).toBe('1 KB');
    expect(formatBytes(1023)).toBe('1 KB');
    expect(formatBytes(Math.round(999.4 * MiB))).toBe('999 MB');
    expect(formatBytes(Math.round(1010 * MiB))).toBe('1 GB');
  });
});

describe('formatBytesOf', () => {
  // PE2 — install progress "X of Y": both amounts in the unit of the whole, each in the one format.
  it('writes both amounts in the unit of the whole, each with at most three figures (PE1, PE2)', () => {
    expect(formatBytesOf).toBeTypeOf('function');
    expect(formatBytesOf(Math.round(1.3 * GiB), Math.round(2.6 * GiB))).toBe('1.3 of 2.6 GB');
    expect(formatBytesOf(Math.round(120 * MiB), Math.round(956 * MiB))).toBe('120 of 956 MB');
    expect(formatBytesOf(0, Math.round(1.19 * GiB))).toBe('0 of 1.2 GB');
    expect(formatBytesOf(Math.round(5.25 * MiB), Math.round(1.19 * GiB))).toBe('0 of 1.2 GB');
    expect(formatBytesOf(Math.round(60 * MiB), Math.round(1.19 * GiB))).toBe('0.1 of 1.2 GB');
    expect(formatBytesOf(Math.round(5.3 * MiB), Math.round(341.4 * MiB))).toBe('5.3 of 341 MB');
    expect(formatBytesOf(Math.round(12.6 * MiB), Math.round(341.4 * MiB))).toBe('13 of 341 MB');
    expect(formatBytesOf(40, 157)).toBe('40 of 157 B');
  });
  it('keeps the amount between zero and the whole', () => {
    expect(formatBytesOf).toBeTypeOf('function');
    expect(formatBytesOf(-5, 2 * GiB)).toBe('0 of 2 GB');
    expect(formatBytesOf(3 * GiB, 2 * GiB)).toBe('2 of 2 GB');
  });
});

describe('formatCount', () => {
  it('locale-groups integers', () => {
    expect(formatCount(12_340)).toBe(new Intl.NumberFormat().format(12340));
  });
});
