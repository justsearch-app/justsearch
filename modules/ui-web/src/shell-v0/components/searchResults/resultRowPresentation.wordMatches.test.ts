// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import { render } from 'lit';
import { highlightTerms } from './resultRowPresentation.js';

function highlighted(text: string, query: string): HTMLElement {
  const host = document.createElement('div');
  render(highlightTerms(text, query), host);
  return host;
}
function marks(text: string, query: string): string[] {
  return [...highlighted(text, query).querySelectorAll('mark.hl')].map((m) => m.textContent ?? '');
}

describe('Search presentation word matches', () => {
  it('A1 marks word prefixes, never interior substrings', () => {
    expect(marks('desktop directory report port Invoice invoices', 'to port invoice')).toEqual(['port', 'Invoice', 'invoice']);
  });

  it('A1 treats Unicode letters and digits as words, case insensitively', () => {
    expect(marks('École préécole ÉCOLES 中文 中文字 42 142 αβ γαβ', 'éco 中文 42 αβ')).toEqual(['Éco', 'ÉCO', '中文', '中文', '42', 'αβ']);
  });

  it('A2 ignores every default English stopword, including in phrases', () => {
    const stopwords = 'a an and are as at be but by for if in into is it no not of on or such that the their then there these they this to was will with';
    expect(highlightTerms(stopwords, stopwords)).toBe(stopwords);
    expect(marks('The invoice to the office', '"the invoice to the office"')).toEqual(['invoice', 'office']);
  });

  it('A3 excludes words and quoted phrases from highlighting', () => {
    expect(marks('invoice draft late payment archive', 'invoice -draft -"late payment" -archive')).toEqual(['invoice']);
    // Character.isWhitespace excludes NBSP, but includes the Java file separator.
    expect(marks('invoice draft', 'invoice\u00a0-draft')).toEqual(['invoice', 'draft']);
    expect(marks('invoice draft', 'invoice\u001c-draft')).toEqual(['invoice']);
  });

  it('A3 separates recognized field prefixes and phrase words', () => {
    expect(marks('title report author Ada annual budget', 'title:report author:Ada "annual budget"')).toEqual(['report', 'Ada', 'annual', 'budget']);
  });

  it('A3 follows escaped quotes, escaped exclusions and case-sensitive field names', () => {
    expect(marks('invoice draft annual budget Title report author Ada 42', String.raw`invoice \-draft "annual \"budget\"" Title:report -author:Ada -42`)).toEqual(['invoice', 'draft', 'annual', 'budget', 'Title', 'report', '42']);
  });

  it('A4 preserves literal text and safely renders untrusted query and snippet text', () => {
    const text = '<img src=x onerror="alert(1)"> Invoice & invoices';
    const host = highlighted(text, '<img> invoice');
    expect(host.textContent).toBe(text);
    expect(host.querySelector('img')).toBeNull();
    expect([...host.querySelectorAll('mark')].every((m) => m.className === 'hl')).toBe(true);
    for (const query of ['', 'x', '"x"']) expect(highlightTerms(text, query)).toBe(text);
  });
});
