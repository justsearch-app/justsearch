// SPDX-License-Identifier: Apache-2.0
/**
 * fm-pane-match — which passage the side pane opens a search result at (`sv3ResultMatch`), and which
 * passage it keeps when a result is opened again (`sv3NextPaneMatch`).
 */
import { describe, expect, it } from 'vitest';
import { sv3NextPaneMatch, sv3ResultMatch } from './sv3-result-match.js';
import type { SearchExcerptRegion, SearchHitChunk } from '../../state/searchState.js';

const hit = (...excerptRegions: SearchExcerptRegion[]) => ({ excerptRegions });
/** A hit found through one of its file's chunks: the regions count from the chunk's start. */
const chunkHit = (chunk: SearchHitChunk, ...excerptRegions: SearchExcerptRegion[]) => ({ excerptRegions, chunk });
const PASSAGE = 'x'.repeat(420);

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

describe('sv3ResultMatch — a hit found through a chunk of its file (R1, R2)', () => {
  it('R1 is placed at the chunk\'s start plus the region\'s span, never at the chunk-relative span', () => {
    expect(sv3ResultMatch(chunkHit({ startChar: '249619', endChar: '251219' }, { text: PASSAGE, startChar: 345, endChar: 765 })))
      .toEqual({ startChar: 249_964, endChar: 250_384, excerpt: PASSAGE });
  });

  it('R2 a first chunk that does not start at 0 (leading whitespace stripped) is placed at its start plus the region\'s', () => {
    expect(sv3ResultMatch(chunkHit({ startChar: '3', endChar: '1203' }, { text: 'gate', startChar: 40, endChar: 44 })))
      .toEqual({ startChar: 43, endChar: 47, excerpt: 'gate' });
  });

  it('R2 a region ending exactly at the chunk\'s end is still inside it', () => {
    expect(sv3ResultMatch(chunkHit({ startChar: '1000', endChar: '1044' }, { text: 'gate', startChar: 40, endChar: 44 })))
      .toEqual({ startChar: 1040, endChar: 1044, excerpt: 'gate' });
  });

  it('R2 without an end the chunk start alone places the region', () => {
    expect(sv3ResultMatch(chunkHit({ startChar: '1000' }, { text: 'gate', startChar: 40, endChar: 44 })))
      .toEqual({ startChar: 1040, endChar: 1044, excerpt: 'gate' });
  });

  it.each([
    ['chunk start missing', { endChar: '251219' }],
    ['chunk start not a number', { startChar: 'abc', endChar: '251219' }],
    ['chunk start negative', { startChar: '-5', endChar: '251219' }],
    ['chunk start not whole', { startChar: '12.5', endChar: '251219' }],
    ['chunk start empty', { startChar: '', endChar: '251219' }],
    ['chunk start beyond safe integers', { startChar: '9007199254740993', endChar: '9007199254741993' }],
    ['region beyond the chunk\'s length', { startChar: '249619', endChar: '250000' }],
    ['chunk end not a number', { startChar: '249619', endChar: 'abc' }],
    ['chunk end before its start', { startChar: '249619', endChar: '1000' }],
  ])('R2 %s: no position, so the pane opens as before', (_label, chunk) => {
    expect(sv3ResultMatch(chunkHit(chunk, { text: PASSAGE, startChar: 345, endChar: 765 }))).toBeNull();
  });
});

describe('sv3NextPaneMatch — opening again', () => {
  const shown = { startChar: 100, endChar: 120, excerpt: 'shown passage' };
  const newer = { startChar: 5, endChar: 25, excerpt: 'newer passage' };

  it('N2 the file on show at the identical passage (same span and text) keeps the passage on show', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', { ...shown })).toBe(shown);
  });

  it('N1 the file on show at another passage takes the result\'s own', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', newer)).toBe(newer);
    const sameSpan = { ...shown, excerpt: 'other passage' };
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', sameSpan)).toBe(sameSpan);
    const sameText = { ...shown, endChar: shown.endChar + 1 };
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', sameText)).toBe(sameText);
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/a.md', null)).toBeNull();
  });

  it('another file takes its own passage', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: shown }, '/b.md', newer)).toBe(newer);
    expect(sv3NextPaneMatch({ docPath: null, match: null }, '/a.md', null)).toBeNull();
  });

  it('the same file on show without a passage (opened from elsewhere) takes the result\'s', () => {
    expect(sv3NextPaneMatch({ docPath: '/a.md', match: null }, '/a.md', newer)).toBe(newer);
  });
});
