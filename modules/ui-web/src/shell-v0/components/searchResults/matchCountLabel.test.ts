/**
 * matchCountLabel — Tempdoc 597 (funnel + mode-aware label) + Search Thread S1's
 * count-coherence fix: when MORE rows render than documents matched, the headline
 * now names both counts truthfully ("N results · M matched exactly") instead of
 * collapsing to a self-contradicting "M matches" above a longer row list (live
 * audit finding B1). The matched-count noun is also now singular at exactly 1
 * ("1 match", not "1 matches").
 *
 * Relocated from SearchSurface.searchTrace.test.ts's embedded
 * `SearchSurface.matchCountLabel` describe block (tempdoc 597 R-1 made that a thin
 * delegating static) to test the SHARED helper directly — the ONE authority both
 * SearchSurface and ResultsCard project the count label from. `SearchSurface.
 * matchCountLabel` forwards verbatim to this function and carries no logic of its
 * own, so it needs no separate test.
 */
import { describe, it, expect } from 'vitest';
import { matchCountLabel } from './matchCountLabel.js';

describe('matchCountLabel — collapse branch (the whole match set is on screen)', () => {
  it('collapses to "M matches" when the whole match set is on screen', () => {
    expect(matchCountLabel(8, 8)).toBe('8 files');
  });

  it('names both the shown slice and the matched total when not all are shown', () => {
    expect(matchCountLabel(451, 50)).toBe('Showing 50 of 451 files');
  });

  it('uses the singular "match" noun when matchCount is exactly 1 (Search Thread S1)', () => {
    expect(matchCountLabel(1, 1)).toBe('1 file');
  });
});

describe('matchCountLabel — count coherence (Search Thread S1: shown > matched)', () => {
  it('names both counts instead of contradicting the rendered rows ("N results · M matched exactly")', () => {
    // OLD (pre-Search-Thread-S1) truth for this exact call was `matchCountLabel(3, 50) === '3 matches'`
    // — a headline that silently disagreed with the 50 rendered rows. The shown>matched branch now
    // fires first, so the count is classified rather than hidden.
    expect(matchCountLabel(3, 50)).toBe('50 files · 3 matched by words');
  });

  it('the live audit-finding-B1 shape: 4 shown, 1 matched exactly — never "1 matches"', () => {
    const label = matchCountLabel(1, 4);
    expect(label).toBe('4 files · 1 matched by words');
    expect(label).not.toContain('1 matches');
  });

  it('uses the singular "result" noun on the shown side when only one row rendered', () => {
    expect(matchCountLabel(0, 1)).toBe('1 file · 0 matched by words');
  });

  it('a truncated (M+) matched count disables the shown>matched framing — stays collapsed with the "+" lower-bound intact', () => {
    // truncated=true means `matched` is a floor, not exact — "N results · M+ matched exactly" would
    // imply an exact count the scan cannot actually vouch for, so truncation keeps the collapsed form.
    expect(matchCountLabel(3, 50, false, 0, true)).toBe('3+ files');
  });
});

describe('matchCountLabel — §8.3 mode-aware: pure-dense (rankedOnly) uses "ranked", never "matches"', () => {
  it('renders the ranked-window cardinality, never a match count', () => {
    expect(matchCountLabel(0, 50, true, 167)).toBe('Showing 50 of 167 ranked files');
    expect(matchCountLabel(0, 50, true, 50)).toBe('50 ranked files'); // shown >= ranked
    expect(matchCountLabel(0, 12, true, 0)).toBe('12 ranked files'); // unknown window
  });
});

describe('matchCountLabel — §16.2 (M+): the matched total as a lower bound when the scan was truncated', () => {
  it('renders the matched total as a lower bound when the scan was truncated', () => {
    expect(matchCountLabel(200, 50, false, 0, true)).toBe('Showing 50 of 200+ files');
    // collapsed branch (shown >= matched) still carries the "+": the count is still a lower bound.
    expect(matchCountLabel(8, 50, false, 0, true)).toBe('8+ files');
    // not truncated ⇒ no "+", unchanged behavior.
    expect(matchCountLabel(451, 50, false, 0, false)).toBe('Showing 50 of 451 files');
  });
});
