// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// First-minutes PA2 / PA4: the pane marks a search result's found words with the snippet's own
// matching, and splits the folder so the middle of it gives way first.
import { describe, expect, it } from 'vitest';
import { html, render } from 'lit';
import * as presentation from './resultRowPresentation.js';

type Segment = { readonly text: string; readonly mark: boolean };
const segments = (text: string, query: string): Segment[] => {
  expect(typeof (presentation as Record<string, unknown>).highlightSegments).toBe('function');
  return (presentation as unknown as { highlightSegments(t: string, q: string): Segment[] }).highlightSegments(text, query);
};
const split = (folder: string): { head: string; tail: string } => {
  expect(typeof (presentation as Record<string, unknown>).splitFolderTail).toBe('function');
  return (presentation as unknown as { splitFolderTail(f: string): { head: string; tail: string } }).splitFolderTail(folder);
};
const snippetMarks = (text: string, query: string): string[] => {
  const host = document.createElement('div');
  render(html`${presentation.highlightTerms(text, query)}`, host);
  return [...host.querySelectorAll('mark.hl')].map((mark) => mark.textContent ?? '');
};

describe('highlightSegments — the one matching for the snippet and the pane', () => {
  it('marks the same words the snippet marks, and keeps every character in order', () => {
    const text = 'Harbor plan: the harbors and lanterns, not "harb".';
    const runs = segments(text, 'harbor lantern');
    expect(runs.map((run) => run.text).join('')).toBe(text);
    expect(runs.filter((run) => run.mark).map((run) => run.text)).toEqual(snippetMarks(text, 'harbor lantern'));
    expect(runs.filter((run) => run.mark).map((run) => run.text)).toEqual(['Harbor', 'harbor', 'lantern']);
  });

  it('marks nothing for an empty query, stop words or excluded words', () => {
    expect(segments('the harbor', '')).toEqual([{ text: 'the harbor', mark: false }]);
    expect(segments('the harbor', 'the').some((run) => run.mark)).toBe(false);
    expect(segments('the harbor', '-harbor').some((run) => run.mark)).toBe(false);
    expect(segments('', 'harbor')).toEqual([]);
  });

  it('treats the query as words, never as a pattern', () => {
    expect(segments('a (b) c.*', '(b) .*').some((run) => run.mark)).toBe(false);
  });
});

describe('splitFolderTail — the middle of a long folder gives way first', () => {
  it('keeps the last folder whole, with its separator', () => {
    expect(split('/home/reader/.cache/am/tmp/run/home/Documents')).toEqual({ head: '/home/reader/.cache/am/tmp/run/home', tail: '/Documents' });
    expect(split('C:\\Users\\reader\\Documents')).toEqual({ head: 'C:\\Users\\reader', tail: '\\Documents' });
  });

  it('a folder with nothing before its last name is all tail', () => {
    expect(split('/Documents')).toEqual({ head: '', tail: '/Documents' });
    expect(split('/')).toEqual({ head: '', tail: '/' });
    expect(split('C:\\')).toEqual({ head: '', tail: 'C:\\' });
    expect(split('')).toEqual({ head: '', tail: '' });
  });
});
