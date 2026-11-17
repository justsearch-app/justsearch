// SPDX-License-Identifier: Apache-2.0
/**
 * fm-pane-match — which passage the side pane opens a search result at (`sv3ResultMatch`), and which
 * passage it keeps when a result is opened again (`sv3NextPaneMatch`).
 */
import { describe, expect, it } from 'vitest';
import { sv3NextPaneMatch, sv3ResultMatch } from './sv3-result-match.js';
import type { SearchExcerptRegion } from '../../state/searchState.js';

const hit = (...excerptRegions: SearchExcerptRegion[]) => ({ excerptRegions });

describe('sv3ResultMatch — the first returned passage, or none', () => {
  it('takes the first region\'s span and its text as the witness', () => {
    expect(sv3ResultMatch(hit({ text: 'gate opens', startChar: 8123, endChar: 8133 }, { text: 'later', startChar: 9000, endChar: 9005 })))
      .toEqual({ startChar: 8123, endChar: 8133, excerpt: 'gate opens' });
  });

  it('skips a region without text, as the result row does', () => {
    expect(sv3ResultMatch(hit({ text: '  ', startChar: 10, endChar: 20 }, { text: 'gate', startChar: 30, endChar: 34 })))
      .toEqual({ startChar: 30, endChar: 34, excerpt: 'gate' });
  });

  it.each([
    ['no regions', hit()],
    ['regions absent', {}],
    ['offsets missing', hit({ text: 'gate' })],
    ['offsets not numbers', hit({ text: 'gate', startChar: '30' as never, endChar: '34' as never })],
    ['offsets not finite', hit({ text: 'gate', startChar: Number.NaN, endChar: 34 })],
    ['offsets not whole', hit({ text: 'gate', startChar: 30.5, endChar: 34 })],
    ['negative start', hit({ text: 'gate', startChar: -1, endChar: 34 })],
    ['empty span', hit({ text: 'gate', startChar: 34, endChar: 34 })],
    ['inverted span', hit({ text: 'gate', startChar: 34, endChar: 30 })],
  ])('%s: no passage', (_label, candidate) => {
    expect(sv3ResultMatch(candidate)).toBeNull();
  });

  it('a first passage with bad offsets is not replaced by a later one: the row shows the first', () => {
    expect(sv3ResultMatch(hit({ text: 'first', startChar: 50, endChar: 40 }, { text: 'second', startChar: 60, endChar: 66 }))).toBeNull();
  });
});

describe('sv3NextPaneMatch — opening again', () => {
  const shown = { startChar: 100, endChar: 120, excerpt: 'shown passage' };
  const newer = { startChar: 5, endChar: 25, excerpt: 'newer passage' };

  it('the file on show at a passage keeps that passage', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', newer)).toBe(shown);
  });

  it('another file takes its own passage', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/b.md', newer)).toBe(newer);
    expect(sv3NextPaneMatch({ docPath: null, match: null }, '/a.md', null)).toBeNull();
  });

  it('the same file on show without a passage (opened from elsewhere) takes the result\'s', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: null }, '/a.md', newer)).toBe(newer);
  });
});
